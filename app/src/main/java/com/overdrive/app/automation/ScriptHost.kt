package com.overdrive.app.automation

import org.mozilla.javascript.Function

/**
 * Backs the native-function API (on, scenario, vehicle.*, notify, log) that ScriptEngine installs
 * on the scope. This object is NEVER exposed to scripts; the native functions call into it from
 * Kotlin, so scripts cannot reflect off it.
 *
 * Every side effect goes through here, which is where the safety rules live:
 *  - trunk actions are refused outright (never exposed, and blocked even if called),
 *  - dry-run short-circuits actuation,
 *  - everything is recorded to the audit log.
 */
class ScriptHost(
    private val sink: VehicleActionSink,
    private val state: StateProvider,
    private val notifier: Notifier,
    private val audit: AuditLog,
    @Volatile var dryRun: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Set by ScriptEngine; receives each on()/scenario() registration. */
    lateinit var registrar: (String, Function) -> Unit

    fun register(type: String?, fn: Function?) {
        if (type.isNullOrBlank() || fn == null) return
        registrar(type, fn)
    }

    /** Kotlin-side state read; ScriptEngine converts this to the JS `state` object per dispatch. */
    fun stateSnapshot(): Map<String, Any?> = state.snapshot()

    fun notify(message: String?) {
        val m = message ?: ""
        audit.record(AuditEntry(clock(), "notify", m))
        notifier.notify(m)
    }

    fun log(message: String?) {
        audit.record(AuditEntry(clock(), "log", message ?: ""))
    }

    /** The only path from JS to vehicle actuation. Enforces trunk-block, dry-run, and audit. */
    fun vehicleCall(action: String?, args: Map<String, Any?> = emptyMap()): ActionResult {
        val a = action ?: return ActionResult(false, "null action")
        if (a.contains("trunk", ignoreCase = true)) {
            audit.record(AuditEntry(clock(), "blocked", "trunk action refused: $a"))
            return ActionResult(false, "trunk actions are not allowed")
        }
        val argStr = if (args.isEmpty()) "" else " $args"
        if (dryRun) {
            audit.record(AuditEntry(clock(), "dry-run", a + argStr))
            return ActionResult(true, "dry-run: $a")
        }
        val r = try {
            sink.execute(a, args)
        } catch (e: Exception) {
            ActionResult(false, "error: ${e.message}")
        }
        audit.record(AuditEntry(clock(), if (r.ok) "action" else "action-failed", "$a$argStr -> ${r.detail}"))
        return r
    }
}
