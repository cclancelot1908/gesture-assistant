package com.orico.gestureassistant.action

import android.content.Context
import com.orico.gestureassistant.rules.ActionId
import com.orico.gestureassistant.smarthome.SmartDeviceController
import com.orico.gestureassistant.smarthome.SmartDeviceStore
import kotlinx.coroutines.flow.first

/** 只负责按动作 ID 路由，具体系统 API 保持在独立执行器中。 */
class CompositeActionExecutor(
    context: Context,
    globalPerformer: ((Int) -> Boolean)? = com.orico.gestureassistant.accessibility.GestureAccessibilityService::performGlobal,
    globalAvailable: () -> Boolean = com.orico.gestureassistant.accessibility.GestureAccessibilityService::isRunning,
) : ActionExecutor {
    private val flashlight = FlashlightActionExecutor(context)
    private val media = MediaActionExecutor(context)
    private val launcher = LaunchAppActionExecutor(context)
    private val global = GlobalActionRouter(globalPerformer, globalAvailable, android.os.Build.VERSION.SDK_INT)
    private val webhook = WebhookActionExecutor()
    private val smartDeviceStore = SmartDeviceStore(context.applicationContext)
    private val smartDeviceController = SmartDeviceController()

    override suspend fun execute(action: ActionId, packageName: String?): ActionResult = when (action) {
        ActionId.TOGGLE_FLASHLIGHT -> flashlight.execute(action, packageName)
        ActionId.MEDIA_PLAY_PAUSE -> media.execute(action, packageName)
        ActionId.LAUNCH_APP -> launcher.execute(action, packageName)
        ActionId.HTTP_WEBHOOK -> webhook.execute(action, packageName)
        ActionId.SMART_DEVICE -> executeSmartDevice(packageName)
        ActionId.TAKE_SCREENSHOT, ActionId.LOCK_SCREEN, ActionId.OPEN_NOTIFICATIONS,
        ActionId.GLOBAL_BACK, ActionId.GLOBAL_HOME, ActionId.GLOBAL_RECENTS -> global.execute(action)
    }

    /** 参数编码为 "deviceId|op"（op ∈ on/off/toggle）；按 id 从本地库取设备后局域网直控。 */
    private suspend fun executeSmartDevice(parameter: String?): ActionResult {
        val parts = parameter?.split('|') ?: emptyList()
        val deviceId = parts.getOrNull(0)?.trim().orEmpty()
        val op = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
        if (deviceId.isEmpty() || op.isEmpty()) return ActionResult(false, "未绑定智能家居设备")
        val device = runCatching { smartDeviceStore.devices.first() }.getOrNull()
            ?.firstOrNull { it.id == deviceId }
            ?: return ActionResult(false, "设备已被删除，请重新绑定")
        val result = runCatching {
            when (op) {
                "on" -> smartDeviceController.turnOn(device)
                "off" -> smartDeviceController.turnOff(device)
                else -> smartDeviceController.toggle(device)
            }
        }.getOrElse { return ActionResult(false, "设备控制异常") }
        return ActionResult(result.successful, result.message)
    }
}
