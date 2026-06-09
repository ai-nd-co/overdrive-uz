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
}
