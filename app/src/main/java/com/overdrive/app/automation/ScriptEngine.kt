package com.overdrive.app.automation

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Sandboxed Rhino runtime for user scenarios.
 *
 * Safety properties:
 *  - Interpreter mode (optimizationLevel = -1): required on Android (no JVM bytecode gen) and
 *    a prerequisite for the instruction observer below.
 *  - ClassShutter denies all Java class access except our own automation package, so scripts
 *    cannot reach java.*, android.*, reflection, IO, or Runtime. They see only the curated
 *    globals from the prelude.
 *  - Instruction observer enforces a wall-clock time budget per call, so an infinite loop or
 *    runaway script is aborted instead of hanging the engine thread.
 *
 * Single-threaded by contract: the owning AutomationEngine drives load()/dispatch() from one
 * thread, so the shared scope and handler map need no extra locking.
 */
class ScriptEngine(
    private val host: ScriptHost,
    private val maxRunMillis: Long = 3_000L,
    private val instructionThreshold: Int = 10_000,
) {
    private val factory = object : ContextFactory() {
        override fun hasFeature(cx: Context, feature: Int): Boolean =
            if (feature == Context.FEATURE_ENHANCED_JAVA_ACCESS) false else super.hasFeature(cx, feature)

        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.setInstructionObserverThreshold(instructionThreshold)
            cx.setClassShutter(ClassShutter { name -> name.startsWith("com.overdrive.app.automation.") })
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = cx.getThreadLocal(DEADLINE) as? Long ?: return
            if (System.currentTimeMillis() > deadline) {
                throw Error("automation script exceeded its time budget")
            }
        }
    }

    private val handlers = LinkedHashMap<String, MutableList<Function>>()
    private var scope: ScriptableObject? = null

    init {
        host.registrar = { type, fn -> handlers.getOrPut(type) { mutableListOf() }.add(fn) }
        host.argConverter = { raw -> jsToMap(raw) }
    }

    private fun jsToMap(raw: Any?): Map<String, Any?> {
        if (raw == null || raw is org.mozilla.javascript.Undefined) return emptyMap()
        if (raw is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return raw as Map<String, Any?>
        }
        if (raw is Scriptable) {
            val out = LinkedHashMap<String, Any?>()
            for (id in ScriptableObject.getPropertyIds(raw)) {
                val key = id?.toString() ?: continue
                out[key] = unwrap(ScriptableObject.getProperty(raw, key))
            }
            return out
        }
        return emptyMap()
    }

    private fun unwrap(v: Any?): Any? = when {
        v is org.mozilla.javascript.Wrapper -> v.unwrap()
        v === Scriptable.NOT_FOUND || v is org.mozilla.javascript.Undefined -> null
        else -> v
    }

    /** Evaluate a scenario source once; its on()/scenario() calls register handlers. */
    fun load(name: String, source: String) {
        inContext { cx, sc -> cx.evaluateString(sc, source, name, 1, null) }
    }

    /** Fire a trigger: run every handler registered for its type. Returns how many ran. */
    fun dispatch(trigger: Trigger): Int = inContext { cx, sc ->
        val fns = handlers[trigger.type] ?: return@inContext 0
        ScriptableObject.putProperty(sc, "state", mapToJs(cx, sc, host.stateSnapshot()))
        val ctxObj = mapToJs(cx, sc, mapOf("type" to trigger.type, "ts" to trigger.ts) + trigger.payload)
        var ran = 0
        for (fn in fns) {
            try {
                fn.call(cx, sc, sc, arrayOf<Any>(ctxObj))
                ran++
            } catch (t: Throwable) {
                host.log("scenario error on ${trigger.type}: ${t.message}")
            }
        }
        ran
    }

    val registeredTriggerTypes: Set<String> get() = handlers.keys.toSet()

    private fun <T> inContext(block: (Context, ScriptableObject) -> T): T {
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal(DEADLINE, System.currentTimeMillis() + maxRunMillis)
            val sc = scope ?: cx.initStandardObjects().also {
                ScriptableObject.putProperty(it, "__host", Context.javaToJS(host, it))
                cx.evaluateString(it, PRELUDE, "prelude", 1, null)
                scope = it
            }
            return block(cx, sc)
        } finally {
            Context.exit()
        }
    }

    private fun mapToJs(cx: Context, scope: Scriptable, map: Map<String, Any?>): Scriptable {
        val o = cx.newObject(scope)
        for ((k, v) in map) ScriptableObject.putProperty(o, k, Context.javaToJS(v, scope))
        return o
    }

    companion object {
        private const val DEADLINE = "automation.deadline"

        // Friendly globals defined in terms of the single __host bridge. Trunk is intentionally
        // absent from `vehicle`; ScriptHost.vehicleCall also blocks it as a second layer.
        private val PRELUDE = """
            var on = function (type, fn) { __host.register(type, fn); };
            var scenario = function (spec) { __host.register(spec.when, spec.run); };
            var log = function (m) { __host.log(String(m)); };
            var notify = function (m) { __host.notify(String(m)); };
            var vehicle = {
              lock:         function () { return __host.vehicleCall('lock'); },
              unlock:       function () { return __host.vehicleCall('unlock'); },
              closeWindows: function () { return __host.vehicleCall('windows-close-all'); },
              flash:        function () { return __host.vehicleCall('flash'); },
              climateOn:    function () { return __host.vehicleCall('climate-on'); },
              climateOff:   function () { return __host.vehicleCall('climate-off'); },
              sunroof:      function (action) { return __host.vehicleCall('sunroof', { action: action }); },
              sunshade:     function (action) { return __host.vehicleCall('sunshade', { action: action }); },
              climateTemp:  function (c) { return __host.vehicleCall('climate-temp', { tempC: c }); },
              climateFan:   function (level) { return __host.vehicleCall('climate-fan', { level: level }); },
              lights:       function (on) { return __host.vehicleCall('lights', { on: on }); }
            };
        """.trimIndent()
    }
}
