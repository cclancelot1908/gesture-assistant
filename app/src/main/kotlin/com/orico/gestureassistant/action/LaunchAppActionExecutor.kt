package com.orico.gestureassistant.action

import android.content.Context
import android.content.Intent
import com.orico.gestureassistant.rules.ActionId

class LaunchAppActionExecutor(private val context: Context) : ActionExecutor {
    override suspend fun execute(action: ActionId, packageName: String?): ActionResult {
        if (action != ActionId.LAUNCH_APP) return ActionResult(false, "不支持的启动动作")
        if (packageName.isNullOrBlank()) return ActionResult(false, "尚未为此手势选择 App")

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ActionResult(false, "所选 App 不存在或无法启动")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult(true, "已打开所选 App")
        } catch (_: Exception) {
            ActionResult(false, "无法打开所选 App")
        }
    }
}
