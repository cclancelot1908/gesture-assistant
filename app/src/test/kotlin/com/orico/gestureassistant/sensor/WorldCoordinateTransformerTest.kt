package com.orico.gestureassistant.sensor

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WorldCoordinateTransformerTest {
    @Test
    fun rotateDeviceToWorld_appliesAndroidRowMajorRotationMatrix() {
        val deviceToWorldQuarterTurn = floatArrayOf(
            0f, -1f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f,
        )

        val result = WorldCoordinateTransformer.rotateDeviceToWorld(
            deviceToWorldQuarterTurn,
            x = 2f,
            y = 3f,
            z = 4f,
        )

        assertArrayEquals(floatArrayOf(-3f, 2f, 4f), result, 1e-6f)
    }

    @Test
    fun rotateDeviceToWorld_invalidMatrixFallsBackWithoutThrowing() {
        val result = WorldCoordinateTransformer.rotateDeviceToWorld(
            rotationMatrix = floatArrayOf(1f),
            x = 2f,
            y = 3f,
            z = 4f,
        )

        assertArrayEquals(floatArrayOf(2f, 3f, 4f), result, 1e-6f)
    }
}
