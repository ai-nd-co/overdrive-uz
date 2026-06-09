package com.overdrive.app.automation

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide facade the daemon and HTTP API talk to.
 *
 * Concurrency: the Rhino engine has a single shared scope and is NOT internally synchronized, so
 * EVERY engine operation (load, dispatch, reload, enable/dry-run toggle, scenario CRUD) is funneled
 * onto one dedicated single-thread worker. Trigger sources (ACC, door, charge, battery, schedule)
 * submit fire-and-forget so they never block the daemon; the HTTP API submits and waits for a result.
 *
 * Safety: enabled and dry-run are read live at actuation time, so toggling Live off takes effect for
 * the next dispatch. Defaults are disabled + dry-run (flag files `enabled` / `live` in the dir).
 */
object Automation {

    @Volatile private var engine: AutomationEngine? = null
    @Volatile private var dir: File? = null
    @Volatile private var logger: (String) -> Unit = { println(it) }
    @Volatile private var lastAccOff: Boolean? = null
    @Volatile private var charging: Boolean = false
    @Volatile private var schedulerStarted: Boolean = false
    @Volatile private var workerThread: Thread? = null

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "automation-worker").apply { isDaemon = true; workerThread = this }
        }
    }
    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "automation-schedule").apply { isDaemon = true } }
    }

    // No-op deps used only to syntax/registration-validate a scenario in a throwaway engine.
    private val noopAudit = object : AuditLog { override fun record(entry: AuditEntry) {} }
    private val noopNotifier = object : Notifier { override fun notify(message: String) {} }
    private val emptyState = object : StateProvider { override fun snapshot() = emptyMap<String, Any?>() }
    private val noopSink = object : VehicleActionSink {
        override fun execute(action: String, args: Map<String, Any?>) = ActionResult(true, "noop")
    }

    @JvmOverloads
    fun init(
        scenariosDir: File,
        enabled: Boolean = false,
        dryRun: Boolean = true,
        log: (String) -> Unit = { println(it) },
    ) {
        if (engine != null) return
        dir = scenariosDir
        logger = log
        if (enabled) toggleFlag(File(scenariosDir, FLAG_ENABLED), true)
        if (!dryRun) toggleFlag(File(scenariosDir, FLAG_LIVE), true)
        onWorker(15_000, Unit) { engine = buildInternal(scenariosDir) }
        startScheduler()
    }

    // ---- worker plumbing ----

    private fun <T> onWorker(timeoutMs: Long, default: T, block: () -> T): T {
        // Fast path: if already on the worker thread, run inline (prevents any self-deadlock).
        if (Thread.currentThread() === workerThread) {
            return runCatching(block).getOrElse { logger("automation inline op failed: ${it.javaClass.simpleName} ${it.message}"); default }
        }
        return runCatching {
            worker.submit(Callable { block() }).get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse { logger("automation worker op timed out/failed: ${it.javaClass.simpleName} ${it.message}"); default }
    }

    private fun async(block: () -> Unit) {
        runCatching {
            worker.execute { runCatching(block).onFailure { logger("automation async failed: ${it.message}") } }
        }
    }

    // ---- engine build (worker-thread only) ----

    private fun buildInternal(scenariosDir: File): AutomationEngine {
        val armed = File(scenariosDir, FLAG_ENABLED).exists()
        val live = File(scenariosDir, FLAG_LIVE).exists()
        val audit = FileAuditLog(File(scenariosDir, AUDIT_FILE))
        val host = ScriptHost(RouterVehicleActionSink(), DaemonStateProvider(), LogNotifier(logger), audit, dryRun = !live)
        val eng = AutomationEngine(ScriptEngine(host), host, audit, enabled = armed)
        runCatching {
            scenariosDir.mkdirs()
            scenariosDir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    val src = runCatching { f.readText() }.getOrNull()
                    when {
                        src == null -> logger("scenario read failed ${f.name}")
                        !validates(src) -> logger("scenario invalid, skipped: ${f.name}")
                        else -> runCatching { eng.load(f.name, src) }
                            .onFailure { logger("scenario load failed ${f.name}: ${it.message}") }
                    }
                }
        }
        logger("automation built: enabled=$armed dryRun=${!live} triggers=${eng.triggerTypes}")
        return eng
    }

    /** True if the source loads (registers) without throwing, in an isolated throwaway engine. */
    private fun validates(source: String): Boolean = runCatching {
        ScriptEngine(ScriptHost(noopSink, emptyState, noopNotifier, noopAudit, dryRun = true)).load("validate", source)
        true
    }.getOrDefault(false)

    /** Fires SCHEDULE_TICK periodically, but only while armed and a scenario actually listens. */
    private fun startScheduler() {
        if (schedulerStarted) return
        schedulerStarted = true
        runCatching {
            scheduler.scheduleWithFixedDelay({
                val e = engine
                if (e != null && e.enabled && e.triggerTypes.contains(Triggers.SCHEDULE_TICK)) {
                    async { engine?.fire(Trigger(Triggers.SCHEDULE_TICK, emptyMap(), System.currentTimeMillis())) }
                }
            }, SCHEDULE_PERIOD_SEC, SCHEDULE_PERIOD_SEC, TimeUnit.SECONDS)
        }
    }

    // ---- triggers (fire-and-forget on the worker) ----

    /** Single idempotent entry for ACC edges (accIsOff: true = sleep, false = wake). */
    fun onAccEdge(accIsOff: Boolean) {
        synchronized(this) {
            if (lastAccOff == accIsOff) return
            lastAccOff = accIsOff
        }
        async { engine?.fire(if (accIsOff) Triggers.VEHICLE_SLEEP else Triggers.VEHICLE_WAKE) }
    }

    fun onVehicleWake() = async { engine?.fire(Triggers.VEHICLE_WAKE) }
    fun onVehicleSleep() = async { engine?.fire(Triggers.VEHICLE_SLEEP) }
    fun activate() = async { engine?.fire(Triggers.APP_ACTIVATE) }

    fun onDoorEvent(opened: Boolean, area: Int) = async {
        engine?.fire(Trigger(if (opened) Triggers.DOOR_OPEN else Triggers.DOOR_CLOSE, mapOf("area" to area), System.currentTimeMillis()))
    }

    fun onChargeEvent(started: Boolean) {
        charging = started
        async { engine?.fire(if (started) Triggers.CHARGE_START else Triggers.CHARGE_STOP) }
    }

    fun onBatteryLow(voltage: Double) = async {
        engine?.fire(Trigger(Triggers.BATTERY_LOW, mapOf("voltage" to voltage), System.currentTimeMillis()))
    }

    fun isCharging(): Boolean = charging

    /** Manually fire a trigger (UI test button). Runs on the worker; returns handlers run. */
    fun fireManual(type: String): Int = onWorker(5_000, 0) { engine?.fire(type) ?: 0 }

    // ---- state / toggles ----

    fun isEnabled(): Boolean = engine?.enabled ?: false
    fun isDryRun(): Boolean = engine?.dryRun ?: true
    fun triggerTypesList(): List<String> = engine?.triggerTypes?.toList() ?: emptyList()

    fun setEnabled(enabled: Boolean) = onWorker(2_000, Unit) {
        engine?.enabled = enabled
        dir?.let { toggleFlag(File(it, FLAG_ENABLED), enabled) }
    }

    fun setDryRun(dryRun: Boolean) = onWorker(2_000, Unit) {
        engine?.dryRun = dryRun
        dir?.let { toggleFlag(File(it, FLAG_LIVE), !dryRun) }
    }

    // ---- scenario CRUD (worker-serialized; rebuild keeps handlers consistent) ----

    fun listScenarios(): List<String> {
        val d = dir ?: return emptyList()
        return d.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun readScenario(name: String): String? {
        val f = safeFile(name) ?: return null
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    /** Returns false (UI shows an error) if the name is invalid or the source fails to load. */
    fun saveScenario(name: String, source: String): Boolean = onWorker(5_000, false) {
        val d = dir
        val f = safeFile(name)
        if (d == null || f == null || !validates(source)) false
        else runCatching { d.mkdirs(); f.writeText(source); engine = buildInternal(d); true }.getOrDefault(false)
    }

    fun deleteScenario(name: String): Boolean = onWorker(5_000, false) {
        val d = dir
        val f = safeFile(name)
        if (d == null || f == null) false
        else {
            val ok = runCatching { !f.exists() || f.delete() }.getOrDefault(false)
            if (ok) engine = buildInternal(d)
            ok
        }
    }

    fun reload() = onWorker(5_000, Unit) { dir?.let { engine = buildInternal(it) } }

    fun readAudit(maxLines: Int): List<String> {
        val d = dir ?: return emptyList()
        val f = File(d, AUDIT_FILE)
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
    }

    fun engineOrNull(): AutomationEngine? = engine

    // ---- helpers ----

    private fun toggleFlag(f: File, on: Boolean) {
        runCatching {
            if (on) { f.parentFile?.mkdirs(); if (!f.exists()) f.createNewFile() }
            else if (f.exists()) f.delete()
        }
    }

    /** Path-traversal-safe scenario file: basename only, must end in .js. */
    private fun safeFile(name: String): File? {
        val d = dir ?: return null
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.isBlank() || base.contains("..") || !base.endsWith(".js")) return null
        return File(d, base)
    }

    private const val FLAG_ENABLED = "enabled"
    private const val FLAG_LIVE = "live"
    private const val AUDIT_FILE = "audit.log"
    private const val SCHEDULE_PERIOD_SEC = 60L
}
