package com.orico.gestureassistant.accessibility

/** 与 Android UI 解耦的按键策略，便于单元测试。 */
object GlobalGestureKeyPolicy {
    fun shouldConsume(keyCode: Int, configuredKeyCode: Int): Boolean = keyCode == configuredKeyCode
    fun isShortPress(durationMs: Long, longPressMs: Long): Boolean = durationMs < longPressMs
}

/** 解析 Settings.Secure 中以冒号分隔的无障碍服务列表。 */
object AccessibilityServiceState {
    fun isEnabled(enabledServices: String?, packageName: String, serviceClassName: String): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        return enabledServices.split(':').any { flattenedName ->
            val separator = flattenedName.indexOf('/')
            if (separator <= 0 || separator == flattenedName.lastIndex) return@any false
            val enabledPackage = flattenedName.substring(0, separator).trim()
            val rawClass = flattenedName.substring(separator + 1).trim()
            val enabledClass = if (rawClass.startsWith('.')) enabledPackage + rawClass else rawClass
            enabledPackage == packageName && enabledClass == serviceClassName
        }
    }
}
