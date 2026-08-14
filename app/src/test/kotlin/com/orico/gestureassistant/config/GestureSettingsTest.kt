package com.orico.gestureassistant.config

import org.junit.Assert.assertFalse
import org.junit.Test

class GestureSettingsTest {
    @Test
    fun `silent audio keep alive defaults to disabled`() {
        assertFalse(GestureSettings().silentKeepAliveEnabled)
    }
}
