package com.overdrive.app.automation

import com.overdrive.app.byd.routing.VehicleCommandRouter

/**
 * Android adapter: maps automation action names to VehicleCommandRouter commands, so JS
 * scenarios inherit the router's PIN gate, rate limiting, and cloud/SDK dual-path. Trunk
 * commands are never mapped here (defense in depth on top of ScriptHost's trunk block).
 */
class RouterVehicleActionSink(
    private val router: VehicleCommandRouter = VehicleCommandRouter.getInstance(),
    private val defaultTempC: Double = 23.0,
) : VehicleActionSink {

    override fun execute(action: String, args: Map<String, Any?>): ActionResult {
        if (action.contains("trunk", ignoreCase = true)) {
            return ActionResult(false, "trunk actions are not allowed")
        }
        val cmd: VehicleCommandRouter.VehicleCommand = when (action) {
            "lock" -> VehicleCommandRouter.LockCommand()
            "unlock" -> VehicleCommandRouter.UnlockCommand()
            "windows-close-all" -> VehicleCommandRouter.CloseAllWindowsCommand()
            "flash" -> VehicleCommandRouter.FlashLightsCommand()
            "climate-on" -> VehicleCommandRouter.ClimateOnCommand((args["tempC"] as? Number)?.toDouble() ?: defaultTempC)
            "climate-off" -> VehicleCommandRouter.ClimateOffCommand()
            "sunroof" -> VehicleCommandRouter.SunroofCommand(panelCommand(args["action"]) ?: return ActionResult(false, "sunroof needs action open|close|stop"))
            "sunshade" -> VehicleCommandRouter.SunshadeCommand(panelCommand(args["action"]) ?: return ActionResult(false, "sunshade needs action open|close|stop"))
            "climate-temp" -> {
                val t = (args["tempC"] as? Number)?.toDouble() ?: defaultTempC
                if (!t.isFinite() || t < 16.0 || t > 33.0) return ActionResult(false, "climate-temp needs 16..33 C")
                VehicleCommandRouter.ClimateSetTempCommand(0, t)
            }
            "climate-fan" -> {
                val l = (args["level"] as? Number)?.toDouble()
                if (l == null || !l.isFinite() || l < 0.0 || l > 7.0 || l != Math.floor(l)) {
                    return ActionResult(false, "climate-fan needs integer level 0..7")
                }
                VehicleCommandRouter.ClimateSetFanCommand(l.toInt())
            }
            "lights" -> VehicleCommandRouter.LightsCommand(args["on"] as? Boolean ?: true)
            else -> return ActionResult(false, "unknown action: $action")
        }
        return try {
            val res = router.execute(cmd)
            val ok = res.outcome == VehicleCommandRouter.Outcome.SUCCESS
            ActionResult(ok, "${res.pathString()}:${res.outcome} ${res.displayMessage ?: ""}".trim())
        } catch (e: Exception) {
            ActionResult(false, "router error: ${e.message}")
        }
    }

    /** Map an open/close/stop action string to the BYD panel command int (1/2/3). */
    private fun panelCommand(v: Any?): Int? = when ((v as? String)?.lowercase()) {
        "open" -> 1
        "close" -> 2
        "stop" -> 3
        else -> null
    }
}
