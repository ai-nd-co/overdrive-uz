package com.overdrive.app.automation

import java.io.File

/**
 * Process-wide facade the daemon talks to. init() wires the real adapters and loads scenario
 * files; the daemon then calls onVehicleWake()/onVehicleSleep()/activate() at the right moments.
 *
 * Safe defaults: enabled=false and dryRun=true, so nothing actuates the car until the owner
 * explicitly turns the engine on and takes it out of dry-run.
 */
object Automation {

    @Volatile
    private var engine: AutomationEngine? = null

    @Volatile
    private var lastAccOff: Boolean? = null

    @JvmOverloads
    @Synchronized
    fun init(
        scenariosDir: File,
        enabled: Boolean = false,
        dryRun: Boolean = true,
        log: (String) -> Unit = { println(it) },
    ): AutomationEngine {
        engine?.let { return it }

        // No-rebuild owner toggles: drop an empty `enabled` file in the scenarios dir to arm
        // the engine, and a `live` file to leave dry-run (actually actuate). Absent both, the
        // safe default holds: disabled + dry-run.
        val armed = enabled || File(scenariosDir, "enabled").exists()
        val live = !dryRun || File(scenariosDir, "live").exists()

        val audit = FileAuditLog(File(scenariosDir, "audit.log"))
        val host = ScriptHost(
            sink = RouterVehicleActionSink(),
            state = DaemonStateProvider(),
            notifier = LogNotifier(log),
            audit = audit,
            dryRun = !live,
        )
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

        engine = eng
        log("automation initialized: enabled=$armed dryRun=${!live} triggers=${eng.triggerTypes}")
        return eng
    }

    /**
     * Single idempotent entry for ACC transitions (accIsOff: true = sleep, false = wake).
     * Dedups repeat edges itself, so the daemon can call it from anywhere without double-firing.
     */
    @Synchronized
    fun onAccEdge(accIsOff: Boolean) {
        if (lastAccOff == accIsOff) return
        lastAccOff = accIsOff
        if (accIsOff) engine?.fire(Triggers.VEHICLE_SLEEP) else engine?.fire(Triggers.VEHICLE_WAKE)
    }

    fun onVehicleWake() { engine?.fire(Triggers.VEHICLE_WAKE) }
    fun onVehicleSleep() { engine?.fire(Triggers.VEHICLE_SLEEP) }
    fun activate() { engine?.fire(Triggers.APP_ACTIVATE) }

    fun engineOrNull(): AutomationEngine? = engine
}
