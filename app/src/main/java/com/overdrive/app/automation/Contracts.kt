package com.overdrive.app.automation

/**
 * Core contracts for the JS automation engine. Deliberately Android-free so the engine,
 * sandbox, and host bridge can be unit tested on the plain JVM with fakes. The Android
 * adapters (router-backed action sink, ACC-backed state, file audit log) implement these.
 */

/** A normalized event that can fire scenarios. type is e.g. Triggers.VEHICLE_WAKE. */
data class Trigger(
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
    val ts: Long = 0L,
)

/** Built-in trigger types. */
object Triggers {
    const val VEHICLE_WAKE = "vehicle.wake"
    const val VEHICLE_SLEEP = "vehicle.sleep"
    const val APP_ACTIVATE = "app.activate"
    const val DOOR_OPEN = "door.open"
    const val DOOR_CLOSE = "door.close"
    const val CHARGE_START = "charge.start"
    const val CHARGE_STOP = "charge.stop"
    const val BATTERY_LOW = "battery.low"
    const val SCHEDULE_TICK = "schedule.tick"
}

/** Result of a single vehicle action, surfaced back to JS as { ok, detail }. */
data class ActionResult(val ok: Boolean, val detail: String)

/** Sink that actually performs a vehicle action. Real impl routes through VehicleCommandRouter. */
interface VehicleActionSink {
    fun execute(action: String, args: Map<String, Any?>): ActionResult
}

/** Supplies the live vehicle state snapshot exposed to JS as the `state` global. */
interface StateProvider {
    fun snapshot(): Map<String, Any?>
}

/** Where notify(...) calls go (telegram / system notification / log). */
interface Notifier {
    fun notify(message: String)
}

/** One audit record of something a scenario did (action, dry-run, notify, log, block, error). */
data class AuditEntry(val ts: Long, val kind: String, val detail: String)

/** Append-only audit of every scenario side effect. */
interface AuditLog {
    fun record(entry: AuditEntry)
}
