package com.overdrive.app.automation

import java.io.File

/**
 * Process-wide facade the daemon and the HTTP API talk to.
 *
 * init() wires the real adapters and loads scenario files. The enabled / dry-run state is
 * persisted as flag files in the scenarios dir (`enabled`, `live`) so it survives a daemon
 * restart and can be toggled with plain adb. The REST handler drives the same flags + CRUD.
 *
 * Safe defaults: with no flags the engine loads scenarios but fires nothing; `enabled` alone
 * runs them in dry-run (audited, no actuation); add `live` to actually actuate. Trunk is never
 * reachable from JS.
 */
object Automation {

    @Volatile private var engine: AutomationEngine? = null
    @Volatile private var dir: File? = null
    @Volatile private var logger: (String) -> Unit = { println(it) }
    @Volatile private var lastAccOff: Boolean? = null
    @Volatile private var charging: Boolean = false
    @Volatile private var schedulerStarted: Boolean = false

    private val scheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "automation-schedule").apply { isDaemon = true }
        }
    }

    @JvmOverloads
    @Synchronized
    fun init(
        scenariosDir: File,
        enabled: Boolean = false,
        dryRun: Boolean = true,
        log: (String) -> Unit = { println(it) },
    ): AutomationEngine {
        engine?.let { return it }
        dir = scenariosDir
        logger = log
        // Honor explicit init args by seeding the flag files (flags are the source of truth).
        if (enabled) toggleFlag(File(scenariosDir, FLAG_ENABLED), true)
        if (!dryRun) toggleFlag(File(scenariosDir, FLAG_LIVE), true)
        val eng = build(scenariosDir, log).also { engine = it }
        startScheduler()
        return eng
    }

    /** Fires SCHEDULE_TICK periodically, but only while armed and a scenario actually listens. */
    private fun startScheduler() {
        if (schedulerStarted) return
        schedulerStarted = true
        runCatching {
            scheduler.scheduleWithFixedDelay({
                runCatching {
                    val e = engine
                    if (e != null && e.enabled && e.triggerTypes.contains(Triggers.SCHEDULE_TICK)) {
                        fire(Triggers.SCHEDULE_TICK, emptyMap())
                    }
                }
            }, SCHEDULE_PERIOD_SEC, SCHEDULE_PERIOD_SEC, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun reload(): AutomationEngine? {
        val d = dir ?: return null
        return build(d, logger).also { engine = it }
    }

    private fun build(scenariosDir: File, log: (String) -> Unit): AutomationEngine {
        val armed = File(scenariosDir, FLAG_ENABLED).exists()
        val live = File(scenariosDir, FLAG_LIVE).exists()
        val audit = FileAuditLog(File(scenariosDir, AUDIT_FILE))
        val host = ScriptHost(RouterVehicleActionSink(), DaemonStateProvider(), LogNotifier(log), audit, dryRun = !live)
        val eng = AutomationEngine(ScriptEngine(host), host, audit, enabled = armed)
        runCatching {
            scenariosDir.mkdirs()
            scenariosDir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    runCatching { eng.load(f.name, f.readText()) }
                        .onFailure { log("scenario load failed ${f.name}: ${it.message}") }
                }
        }
        log("automation built: enabled=$armed dryRun=${!live} triggers=${eng.triggerTypes}")
        return eng
    }

    // ---- triggers ----

    /** Single idempotent entry for ACC edges (accIsOff: true = sleep, false = wake). */
    @Synchronized
    fun onAccEdge(accIsOff: Boolean) {
        if (lastAccOff == accIsOff) return
        lastAccOff = accIsOff
        if (accIsOff) engine?.fire(Triggers.VEHICLE_SLEEP) else engine?.fire(Triggers.VEHICLE_WAKE)
    }

    fun onVehicleWake() { engine?.fire(Triggers.VEHICLE_WAKE) }
    fun onVehicleSleep() { engine?.fire(Triggers.VEHICLE_SLEEP) }
    fun activate() { engine?.fire(Triggers.APP_ACTIVATE) }

    /** Manually fire a trigger (for the "test" button in the UI). Respects enabled/dry-run. */
    fun fireManual(type: String): Int = engine?.fire(type) ?: 0

    /** Fire a trigger with a payload (used by the event hooks below). */
    fun fire(type: String, payload: Map<String, Any?>): Int =
        engine?.fire(Trigger(type, payload, System.currentTimeMillis())) ?: 0

    /** Door edge from DoorEventNotifier (area = BYD bodywork area constant). */
    fun onDoorEvent(opened: Boolean, area: Int): Int =
        fire(if (opened) Triggers.DOOR_OPEN else Triggers.DOOR_CLOSE, mapOf("area" to area))

    /** Charge edge from ChargingEventNotifier. Also updates the `charging` state field. */
    fun onChargeEvent(started: Boolean): Int {
        charging = started
        return fire(if (started) Triggers.CHARGE_START else Triggers.CHARGE_STOP, emptyMap())
    }

    /** Low 12V battery from BatteryPowerMonitor. */
    fun onBatteryLow(voltage: Double): Int = fire(Triggers.BATTERY_LOW, mapOf("voltage" to voltage))

    fun isCharging(): Boolean = charging

    // ---- state (for the REST API / UI) ----

    fun isEnabled(): Boolean = engine?.enabled ?: false
    fun isDryRun(): Boolean = engine?.dryRun ?: true
    fun triggerTypesList(): List<String> = engine?.triggerTypes?.toList() ?: emptyList()

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val d = dir ?: return
        toggleFlag(File(d, FLAG_ENABLED), enabled)
        reload()
    }

    @Synchronized
    fun setDryRun(dryRun: Boolean) {
        val d = dir ?: return
        toggleFlag(File(d, FLAG_LIVE), !dryRun) // `live` present means NOT dry-run
        reload()
    }

    // ---- scenario CRUD ----

    fun listScenarios(): List<String> {
        val d = dir ?: return emptyList()
        return d.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun readScenario(name: String): String? {
        val f = safeFile(name) ?: return null
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    @Synchronized
    fun saveScenario(name: String, source: String): Boolean {
        val d = dir ?: return false
        val f = safeFile(name) ?: return false
        return runCatching { d.mkdirs(); f.writeText(source); reload(); true }.getOrDefault(false)
    }

    @Synchronized
    fun deleteScenario(name: String): Boolean {
        val f = safeFile(name) ?: return false
        val ok = runCatching { !f.exists() || f.delete() }.getOrDefault(false)
        if (ok) reload()
        return ok
    }

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
