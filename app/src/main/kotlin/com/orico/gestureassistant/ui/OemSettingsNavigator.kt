package com.orico.gestureassistant.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object OemSettingsNavigator {
    /**
     * OEM 设置组件不是公开 API，按 ColorOS、OriginOS 常见版本依次探测；
     * 找不到时退回应用详情页，避免按钮在其他 ROM 上崩溃。
     */
    fun openAutoStart(context: Context) {
        val candidates = listOf(
            // ColorOS 各版本组件名不一，且部分未导出——resolveActivity 可能非空但 startActivity 仍抛异常，
            // 故不做预检、逐个 try 启动，全失败再降级到应用详情页。
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.list.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        for (component in candidates) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, intent)) return
        }
        openAppDetails(context)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        if (tryStart(context, direct)) return
        if (tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(context)
    }

    /** 统一的兜底启动：任何异常（ActivityNotFound / Security 等）都不让它崩溃。 */
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    private fun openAppDetails(context: Context) {
        tryStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }
}
