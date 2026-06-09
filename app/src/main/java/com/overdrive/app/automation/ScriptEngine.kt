package com.overdrive.app.automation

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Sandboxed Rhino runtime for user scenarios.
 *
 * Safety properties:
 *  - Interpreter mode (optimizationLevel = -1): required on Android (no JVM bytecode gen) and a
 *    prerequisite for the instruction observer.
 *  - initSafeStandardObjects(): the scope has NO LiveConnect bridge - `Packages`, `java`,
 *    `JavaAdapter`, `getClass` are not installed - so scripts cannot reach Java classes at all.
 *  - ClassShutter denies every class name as a second layer.
 *  - The whole API is exposed as NATIVE Rhino functions (no Java host object is injected), so a
 *    script can never reflect off an injected object to reach dryRun, the Automation singleton,
 *    persistence, or anything else. Trunk has no method and is also blocked in ScriptHost.
 *  - Instruction observer enforces a wall-clock time budget; a runaway script is aborted.
 *
 * Concurrency: this type is NOT internally synchronized. The owning AutomationEngine / Automation
 * facade serializes all load()/dispatch()/reload() onto a single worker thread.
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
            cx.setClassShutter(ClassShutter { _ -> false })
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
            val sc = scope ?: cx.initSafeStandardObjects(null, false).also {
                installApi(cx, it)
                scope = it
            }
            return block(cx, sc)
        } finally {
            Context.exit()
        }
    }

    /** Install the native-function API onto the scope. No Java object is exposed to scripts. */
    private fun installApi(cx: Context, scope: ScriptableObject) {
        ScriptableObject.putProperty(scope, "on", fn { _, args ->
            val type = (args.getOrNull(0) as? CharSequence)?.toString()
            val handler = args.getOrNull(1) as? Function
            if (type != null && handler != null) host.register(type, handler)
            Undefined.instance
        })
        ScriptableObject.putProperty(scope, "scenario", fn { _, args ->
            val spec = args.getOrNull(0) as? Scriptable ?: return@fn Undefined.instance
            val whenT = (ScriptableObject.getProperty(spec, "when") as? CharSequence)?.toString()
            val run = ScriptableObject.getProperty(spec, "run") as? Function
            if (whenT != null && run != null) host.register(whenT, run)
            Undefined.instance
        })
        ScriptableObject.putProperty(scope, "log", fn { _, args -> host.log(str(args.getOrNull(0))); Undefined.instance })
        ScriptableObject.putProperty(scope, "notify", fn { _, args -> host.notify(str(args.getOrNull(0))); Undefined.instance })

        val vehicle = cx.newObject(scope)
        fun act(name: String, action: String, argBuilder: (Array<out Any?>) -> Map<String, Any?>) {
            ScriptableObject.putProperty(vehicle, name, fn { c, args ->
                resultToJs(c, scope, host.vehicleCall(action, argBuilder(args)))
            })
        }
        act("lock", "lock") { emptyMap() }
        act("unlock", "unlock") { emptyMap() }
        act("closeWindows", "windows-close-all") { emptyMap() }
        act("flash", "flash") { emptyMap() }
        act("climateOn", "climate-on") { emptyMap() }
        act("climateOff", "climate-off") { emptyMap() }
        act("sunroof", "sunroof") { a -> mapOf("action" to str(a.getOrNull(0))) }
        act("sunshade", "sunshade") { a -> mapOf("action" to str(a.getOrNull(0))) }
        act("climateTemp", "climate-temp") { a -> mapOf("tempC" to (a.getOrNull(0) as? Number)) }
        act("climateFan", "climate-fan") { a -> mapOf("level" to (a.getOrNull(0) as? Number)) }
        act("lights", "lights") { a -> mapOf("on" to (a.getOrNull(0) as? Boolean)) }
        ScriptableObject.putProperty(scope, "vehicle", vehicle)
    }

    private fun fn(f: (Context, Array<out Any?>) -> Any?): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any =
            f(cx, args) ?: Undefined.instance
    }

    private fun resultToJs(cx: Context, scope: Scriptable, r: ActionResult): Scriptable {
        val o = cx.newObject(scope)
        ScriptableObject.putProperty(o, "ok", r.ok)
        ScriptableObject.putProperty(o, "detail", r.detail)
        return o
    }

    private fun str(v: Any?): String = when (v) {
        null, is Undefined -> ""
        else -> v.toString()
    }

    private fun mapToJs(cx: Context, scope: Scriptable, map: Map<String, Any?>): Scriptable {
        val o = cx.newObject(scope)
        for ((k, v) in map) ScriptableObject.putProperty(o, k, Context.javaToJS(v, scope))
        return o
    }

    companion object {
        private const val DEADLINE = "automation.deadline"
    }
}
