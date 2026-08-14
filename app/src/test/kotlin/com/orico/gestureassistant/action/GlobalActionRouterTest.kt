package com.orico.gestureassistant.action

import com.orico.gestureassistant.rules.ActionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionRouterTest {
    @Test fun `all system action ids route to their global action`() {
        val calls = mutableListOf<Int>()
        val router = GlobalActionRouter({ action -> calls += action; true })
        val actions = listOf(
            ActionId.TAKE_SCREENSHOT, ActionId.LOCK_SCREEN, ActionId.OPEN_NOTIFICATIONS,
            ActionId.GLOBAL_BACK, ActionId.GLOBAL_HOME, ActionId.GLOBAL_RECENTS,
        )
        actions.forEach { assertTrue(router.execute(it).successful) }
        assertEquals(listOf(9, 8, 4, 1, 2, 3), calls)
    }

    @Test fun `missing accessibility service returns actionable failure`() {
        val result = GlobalActionRouter(null).execute(ActionId.GLOBAL_HOME)
        assertFalse(result.successful)
        assertEquals("需先开启无障碍服务", result.message)
    }

    @Test fun `screenshot is rejected below Android 9`() {
        val result = GlobalActionRouter({ true }, sdkInt = 27).execute(ActionId.TAKE_SCREENSHOT)
        assertFalse(result.successful)
        assertEquals("此系统操作需要 Android 9 或更高版本", result.message)
    }
}
