package com.orico.gestureassistant.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackTapDetectorTest {
    private val config = BackTapConfig(
        sensitivity = 0.5f,
        cooldownMs = 1_000L,
        minTapGapMs = 80L,
        maxTapGapMs = 450L,
    )

    @Test
    fun `double tap is delayed until triple confirmation window expires`() {
        val detector = BackTapDetector(config)

        assertNull(detector.onSample(impact(100L)))
        assertNull(detector.onSample(impact(300L)))
        assertNull(detector.confirmPending(749L))
        assertEquals(GestureLabel.BACK_DOUBLE, detector.confirmPending(750L))
    }

    @Test
    fun `third qualified impact upgrades pending double to triple`() {
        val detector = BackTapDetector(config)
        assertNull(detector.onSample(impact(100L)))
        assertNull(detector.onSample(impact(300L)))
        assertEquals(GestureLabel.BACK_TRIPLE, detector.onSample(impact(500L)))
        assertNull(detector.confirmPending(800L))
    }

    @Test
    fun `impacts too close together are treated as one physical tap`() {
        val detector = BackTapDetector(config)

        assertNull(detector.onSample(impact(100L)))
        assertNull(detector.onSample(impact(140L)))
    }

    @Test
    fun `cooldown blocks a second trigger`() {
        val detector = BackTapDetector(config)

        detector.onSample(impact(100L))
        detector.onSample(impact(300L))
        assertEquals(GestureLabel.BACK_DOUBLE, detector.confirmPending(750L))
        assertNull(detector.onSample(impact(800L)))
        assertNull(detector.onSample(impact(1_000L)))
        assertNull(detector.confirmPending(1_450L))
    }

    @Test
    fun `high rotation gates out normal handling movement`() {
        val detector = BackTapDetector(config)

        assertNull(detector.onSample(impact(100L).copy(rotationMagnitude = 8.0f)))
        assertNull(detector.onSample(impact(300L).copy(rotationMagnitude = 8.0f)))
    }

    @Test
    fun `sustained motion without quiet gap is rejected as shake`() {
        val detector = BackTapDetector(config)
        // 每 40ms 一个持续冲击、中间从不安静，模拟「前后摇」——不应触发。
        assertNull(detector.onSample(impact(100L)))
        assertNull(detector.onSample(impact(140L)))
        assertNull(detector.onSample(impact(180L)))
        assertNull(detector.onSample(impact(220L)))
        assertNull(detector.onSample(impact(260L)))
        assertNull(detector.onSample(impact(300L)))
    }

    private fun impact(timeMs: Long) = MotionSample(
        timestampMs = timeMs,
        linearAccelerationZ = 8.0f,
        totalAcceleration = 9.8f,
        rotationMagnitude = 0.4f,
    )
}
