package com.orico.gestureassistant.rules

import com.orico.gestureassistant.recognizer.GestureLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEngineTest {
    @Test
    fun `default bindings map double and triple independently`() {
        val engine = RuleEngine(GestureBindings.defaults())

        assertEquals(ActionId.TOGGLE_FLASHLIGHT, engine.resolve(GestureLabel.BACK_DOUBLE))
        assertEquals(ActionId.MEDIA_PLAY_PAUSE, engine.resolve(GestureLabel.BACK_TRIPLE))
        assertEquals(null, engine.resolve(GestureLabel.SHAKE))
    }

    @Test
    fun `custom bindings are resolved independently`() {
        val engine = RuleEngine(GestureBindings.defaults())
        engine.bind(GestureLabel.BACK_DOUBLE, ActionId.LAUNCH_APP, "com.tencent.mm")
        engine.bind(GestureLabel.BACK_TRIPLE, ActionId.TOGGLE_FLASHLIGHT)

        assertEquals(ActionId.LAUNCH_APP, engine.resolve(GestureLabel.BACK_DOUBLE))
        assertEquals(ActionId.TOGGLE_FLASHLIGHT, engine.resolve(GestureLabel.BACK_TRIPLE))
        assertEquals("com.tencent.mm", engine.packageFor(GestureLabel.BACK_DOUBLE))
    }

    @Test
    fun `open app package is stored separately for each gesture`() {
        val bindings = GestureBindings.defaults().copy(
            backDoublePackage = "com.tencent.mm",
            backTriplePackage = "com.android.music",
        )
        val engine = RuleEngine(bindings)

        assertEquals("com.tencent.mm", engine.packageFor(GestureLabel.BACK_DOUBLE))
        assertEquals("com.android.music", engine.packageFor(GestureLabel.BACK_TRIPLE))
    }
}
