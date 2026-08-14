package com.orico.gestureassistant.action

import com.orico.gestureassistant.rules.ActionId

/** Android 常量值稳定，抽成纯 Kotlin 路由以便 JVM 单测。 */
class GlobalActionRouter(
    private val performer: ((Int) -> Boolean)?,
    private val available: () -> Boolean = { performer != null },
    private val sdkInt: Int = 36,
) {
    fun execute(action: ActionId): ActionResult {
        val globalAction = when (action) {
            ActionId.GLOBAL_BACK -> 1
            ActionId.GLOBAL_HOME -> 2
            ActionId.GLOBAL_RECENTS -> 3
            ActionId.OPEN_NOTIFICATIONS -> 4
            ActionId.LOCK_SCREEN -> 8
            ActionId.TAKE_SCREENSHOT -> 9
            else -> return ActionResult(false, "不是系统全局动作")
        }
        if ((action == ActionId.TAKE_SCREENSHOT || action == ActionId.LOCK_SCREEN) && sdkInt < 28) {
            return ActionResult(false, "此系统操作需要 Android 9 或更高版本")
        }
        val callback = performer?.takeIf { available() }
            ?: return ActionResult(false, "需先开启无障碍服务")
        return if (runCatching { callback(globalAction) }.getOrDefault(false)) {
            ActionResult(true, "系统操作已执行")
        } else {
            ActionResult(false, "系统操作执行失败")
        }
    }
}
