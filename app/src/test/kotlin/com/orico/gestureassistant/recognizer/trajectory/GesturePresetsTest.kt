package com.orico.gestureassistant.recognizer.trajectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePresetsTest {
    @Test
    fun `包含七个可辨识预设且 id 唯一`() {
        assertEquals(7, GesturePresets.all.size)
        assertEquals(GesturePresets.all.size, GesturePresets.all.map { it.id }.distinct().size)
        assertEquals(
            listOf("Z", "L", "V（对勾）", "O（顺时针）", "O（逆时针）", "上划", "下划"),
            GesturePresets.all.map { it.displayName },
        )
    }

    @Test
    fun `每个预设含二十到四十个归一化参考点`() {
        GesturePresets.all.forEach { preset ->
            assertTrue("${preset.id} 点数=${preset.stroke.size}", preset.stroke.size in 20..40)
            preset.stroke.forEach { (x, y) ->
                assertTrue("${preset.id} x=$x", x in 0f..1f)
                assertTrue("${preset.id} y=$y", y in 0f..1f)
            }
        }
    }
}
