package com.overdrive.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEngineTest {

    private class RecordingSink : VehicleActionSink {
        val calls = mutableListOf<String>()
        override fun execute(action: String, args: Map<String, Any?>): ActionResult {
            calls += action
            return ActionResult(true, "ok")
        }
    }

    private class FixedState(private val m: Map<String, Any?>) : StateProvider {
        override fun snapshot() = m
    }

    private class MemAudit : AuditLog {
        val entries = mutableListOf<AuditEntry>()
        override fun record(entry: AuditEntry) { entries += entry }
        fun kinds() = entries.map { it.kind }
        fun details() = entries.joinToString("\n") { "${it.kind}: ${it.detail}" }
    }

    private class CapturingNotifier : Notifier {
        val msgs = mutableListOf<String>()
        override fun notify(message: String) { msgs += message }
    }

    private fun rig(
        state: Map<String, Any?> = mapOf("accOn" to true, "soc" to 80),
        dryRun: Boolean = false,
        enabled: Boolean = true,
        maxRunMillis: Long = 3_000L,
        instructionThreshold: Int = 10_000,
    ): Triple<AutomationEngine, RecordingSink, MemAudit> {
        val sink = RecordingSink()
        val audit = MemAudit()
        val host = ScriptHost(sink, FixedState(state), CapturingNotifier(), audit, dryRun) { 1L }
        val engine = ScriptEngine(host, maxRunMillis, instructionThreshold)
        return Triple(AutomationEngine(engine, host, audit, enabled) { 1L }, sink, audit)
    }

    @Test
    fun registersAndDispatchesHandler() {
        val (eng, sink, _) = rig()
        eng.load("s", "on('app.activate', function(c){ vehicle.lock(); });")
        assertTrue(eng.triggerTypes.contains(Triggers.APP_ACTIVATE))
        val ran = eng.fire(Triggers.APP_ACTIVATE)
        assertEquals(1, ran)
        assertEquals(listOf("lock"), sink.calls)
    }

    @Test
    fun scenarioSpecFormAlsoWorks() {
        val (eng, sink, _) = rig()
        eng.load("s", "scenario({ when: 'vehicle.wake', run: function(){ vehicle.closeWindows(); vehicle.flash(); } });")
        eng.fire(Triggers.VEHICLE_WAKE)
        assertEquals(listOf("windows-close-all", "flash"), sink.calls)
    }

    @Test
    fun disabledEngineFiresNothing() {
        val (eng, sink, audit) = rig(enabled = false)
        eng.load("s", "on('app.activate', function(){ vehicle.lock(); });")
        val ran = eng.fire(Triggers.APP_ACTIVATE)
        assertEquals(0, ran)
        assertTrue(sink.calls.isEmpty())
        assertTrue(audit.kinds().contains("skip"))
    }

    @Test
    fun dryRunNeverActuates() {
        val (eng, sink, audit) = rig(dryRun = true)
        eng.load("s", "on('app.activate', function(){ vehicle.lock(); });")
        eng.fire(Triggers.APP_ACTIVATE)
        assertTrue(sink.calls.isEmpty())
        assertTrue(audit.kinds().contains("dry-run"))
    }

    @Test
    fun trunkActionIsBlockedEvenIfCalledDirectly() {
        val (eng, sink, audit) = rig()
        // bypass the curated vehicle object and hit the host bridge directly
        eng.load("s", "on('app.activate', function(){ __host.vehicleCall('trunk-open'); });")
        eng.fire(Triggers.APP_ACTIVATE)
        assertTrue(sink.calls.isEmpty())
        assertTrue(audit.kinds().contains("blocked"))
    }

    @Test
    fun stateIsReadableAndGatesActions() {
        val (eng, sink, _) = rig(state = mapOf("accOn" to true, "soc" to 20))
        eng.load("s", "on('vehicle.wake', function(){ if (state.soc > 30) vehicle.climateOn(); });")
        eng.fire(Triggers.VEHICLE_WAKE)
        assertTrue("low soc should skip climate", sink.calls.isEmpty())

        val (eng2, sink2, _) = rig(state = mapOf("accOn" to true, "soc" to 80))
        eng2.load("s", "on('vehicle.wake', function(){ if (state.soc > 30) vehicle.climateOn(); });")
        eng2.fire(Triggers.VEHICLE_WAKE)
        assertEquals(listOf("climate-on"), sink2.calls)
    }

    @Test
    fun sandboxDeniesJavaAccess() {
        val (eng, sink, audit) = rig()
        eng.load("s", "on('app.activate', function(){ var t = java.lang.System.currentTimeMillis(); vehicle.lock(); });")
        eng.fire(Triggers.APP_ACTIVATE)
        // the java access throws before vehicle.lock(); the error is caught and logged
        assertTrue(sink.calls.isEmpty())
        assertTrue("expected a logged scenario error", audit.details().contains("scenario error"))
    }

    @Test(timeout = 5_000)
    fun runawayScriptIsTimeBoxed() {
        val (eng, _, audit) = rig(maxRunMillis = 250L, instructionThreshold = 100)
        eng.load("s", "on('app.activate', function(){ while (true) {} });")
        eng.fire(Triggers.APP_ACTIVATE) // must return, not hang
        assertTrue("expected a logged time-budget error", audit.details().contains("scenario error"))
    }

    @Test
    fun notifyIsAudited() {
        val (eng, _, audit) = rig()
        eng.load("s", "on('app.activate', function(){ notify('hello'); });")
        eng.fire(Triggers.APP_ACTIVATE)
        assertTrue(audit.kinds().contains("notify"))
        assertFalse(audit.entries.none { it.detail.contains("hello") })
    }
}
