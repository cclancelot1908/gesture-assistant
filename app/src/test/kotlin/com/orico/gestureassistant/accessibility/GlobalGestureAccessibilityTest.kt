package com.orico.gestureassistant.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalGestureAccessibilityTest {
    @Test
    fun configuredVolumeDown_isAlwaysConsumedWhileServiceRuns() {
        assertTrue(GlobalGestureKeyPolicy.shouldConsume(keyCode = 25, configuredKeyCode = 25))
        assertFalse(GlobalGestureKeyPolicy.shouldConsume(keyCode = 24, configuredKeyCode = 25))
    }

    @Test
    fun pressDuration_distinguishesShortAndLongPressAtBoundary() {
        assertTrue(GlobalGestureKeyPolicy.isShortPress(349L, 350L))
        assertFalse(GlobalGestureKeyPolicy.isShortPress(350L, 350L))
    }

    @Test
    fun enabledServices_acceptsFullAndRelativeServiceClassNames() {
        val packageName = "com.orico.gestureassistant"
        val className = "$packageName.accessibility.GestureAccessibilityService"

        assertTrue(
            AccessibilityServiceState.isEnabled(
                "$packageName/$className:other.package/.OtherService",
                packageName,
                className,
            ),
        )
        assertTrue(
            AccessibilityServiceState.isEnabled(
                "$packageName/.accessibility.GestureAccessibilityService",
                packageName,
                className,
            ),
        )
    }

    @Test
    fun enabledServices_rejectsMissingOrLookalikeEntries() {
        val packageName = "com.orico.gestureassistant"
        val className = "$packageName.accessibility.GestureAccessibilityService"

        assertFalse(AccessibilityServiceState.isEnabled(null, packageName, className))
        assertFalse(
            AccessibilityServiceState.isEnabled(
                "$packageName/$className.fake:com.example/.GestureAccessibilityService",
                packageName,
                className,
            ),
        )
    }
}
