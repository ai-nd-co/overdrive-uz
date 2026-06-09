package com.overdrive.app.automation

/**
 * Orchestrates the sandboxed ScriptEngine: loads scenarios, holds the enabled / dry-run
 * gates, and fires triggers. Disabled by default - a freshly loaded engine fires nothing
 * until something explicitly enables it.
 */
class AutomationEngine(
    private val engine: ScriptEngine,
    private val host: ScriptHost,
    private val audit: AuditLog,
    @Volatile var enabled: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** When true, actions are audited as "dry-run" and never actuate the car. */
    var dryRun: Boolean
        get() = host.dryRun
        set(value) { host.dryRun = value }

    fun load(name: String, source: String) = engine.load(name, source)

    fun loadAll(sources: Map<String, String>) = sources.forEach { (n, s) -> engine.load(n, s) }

    /** Fire a trigger. No-op (audited) while disabled. Returns how many handlers ran. */
    fun fire(trigger: Trigger): Int {
        if (!enabled) {
            audit.record(AuditEntry(clock(), "skip", "disabled: ${trigger.type}"))
            return 0
        }
        return engine.dispatch(trigger)
    }

    fun fire(type: String): Int = fire(Trigger(type, ts = clock()))

    val triggerTypes: Set<String> get() = engine.registeredTriggerTypes
}
