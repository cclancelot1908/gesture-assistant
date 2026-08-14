package com.orico.gestureassistant.recognizer.trajectory

import com.orico.gestureassistant.rules.ActionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryGestureStoreTest {
    @Test
    fun deleteTemplate_removesOnlyRequestedTemplate() {
        val first = listOf(point(1.0))
        val second = listOf(point(2.0))
        val gestures = listOf(gesture("target", listOf(first, second)), gesture("other", listOf(first)))

        val result = TrajectoryGestureStore.deleteTemplateFrom(gestures, "target", 0)

        assertEquals(2, result.size)
        assertEquals(listOf(second), result.first { it.id == "target" }.templates)
        assertEquals(listOf(first), result.first { it.id == "other" }.templates)
    }

    @Test
    fun deleteTemplate_removesGestureWhenLastTemplateIsDeleted() {
        val result = TrajectoryGestureStore.deleteTemplateFrom(
            listOf(gesture("target", listOf(listOf(point(1.0))))),
            "target",
            0,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun deleteTemplate_ignoresMissingGestureAndOutOfRangeIndex() {
        val gestures = listOf(gesture("target", listOf(listOf(point(1.0)))))

        assertEquals(gestures, TrajectoryGestureStore.deleteTemplateFrom(gestures, "missing", 0))
        assertEquals(gestures, TrajectoryGestureStore.deleteTemplateFrom(gestures, "target", -1))
        assertEquals(gestures, TrajectoryGestureStore.deleteTemplateFrom(gestures, "target", 3))
    }

    @Test
    fun `gestures default to enabled and can be independently disabled`() {
        val gestures = listOf(gesture("target", listOf(listOf(point(1.0)))), gesture("other", listOf(listOf(point(2.0)))))

        assertTrue(gestures.all { it.enabled })

        val result = TrajectoryGestureStore.setEnabledIn(gestures, "target", false)

        assertEquals(false, result.first { it.id == "target" }.enabled)
        assertTrue(result.first { it.id == "other" }.enabled)
    }

    private fun gesture(id: String, templates: List<List<ImuPoint>>) = TrajectoryGesture(
        id = id,
        name = id,
        templates = templates,
        action = ActionId.TOGGLE_FLASHLIGHT,
    )

    private fun point(value: Double) = ImuPoint(value, 0.0, 0.0, 0.0, 0.0, 0.0)
}
