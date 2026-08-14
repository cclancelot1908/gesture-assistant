package com.orico.gestureassistant.recognizer.trajectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryPathReconstructorTest {
    @Test
    fun reconstruct_straightTrajectory_preservesPointCountAndNormalizesRange() {
        val positions = (0 until 40).map { index -> doubleArrayOf(index.toDouble(), 0.0, 0.0) }

        val result = TrajectoryPathReconstructor.reconstruct(accelerationFor(positions))

        assertEquals(positions.size, result.size)
        assertInUnitSquare(result)
        val width = result.maxOf { it.first } - result.minOf { it.first }
        val height = result.maxOf { it.second } - result.minOf { it.second }
        assertTrue("width=$width height=$height", maxOf(width, height) > 0.9f)
        assertTrue("width=$width height=$height", minOf(width, height) < 0.05f)
    }

    @Test
    fun reconstruct_squareTrajectory_preservesPointCountAndBothDimensions() {
        val positions = buildList {
            for (i in 0 until 12) add(doubleArrayOf(i.toDouble(), 0.0, 0.0))
            for (i in 1 until 12) add(doubleArrayOf(11.0, i.toDouble(), 0.0))
            for (i in 10 downTo 0) add(doubleArrayOf(i.toDouble(), 11.0, 0.0))
            for (i in 10 downTo 0) add(doubleArrayOf(0.0, i.toDouble(), 0.0))
        }

        val result = TrajectoryPathReconstructor.reconstruct(accelerationFor(positions))

        assertEquals(positions.size, result.size)
        assertInUnitSquare(result)
        assertTrue(result.maxOf { it.first } - result.minOf { it.first } > 0.55f)
        assertTrue(result.maxOf { it.second } - result.minOf { it.second } > 0.55f)
    }

    @Test
    fun reconstruct_constantAcceleration_isTreatedAsStaticAndEmpty() {
        // 常值加速度=去偏置后只剩 0=近乎静止(无手势)，应返回空，不把噪声拉伸成线。
        val result = TrajectoryPathReconstructor.reconstruct(
            List(32) { ImuPoint(2.0, -1.0, 0.5, 0.0, 0.0, 0.0) },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun reconstruct_tooFewPoints_returnsEmpty() {
        assertTrue(TrajectoryPathReconstructor.reconstruct(List(2) { point(0.0, 0.0, 0.0) }).isEmpty())
    }

    private fun accelerationFor(positions: List<DoubleArray>): List<ImuPoint> =
        positions.indices.map { index ->
            val previous = positions[(index - 1).coerceAtLeast(0)]
            val previousPrevious = positions[(index - 2).coerceAtLeast(0)]
            point(
                positions[index][0] - 2.0 * previous[0] + previousPrevious[0],
                positions[index][1] - 2.0 * previous[1] + previousPrevious[1],
                positions[index][2] - 2.0 * previous[2] + previousPrevious[2],
            )
        }

    private fun assertInUnitSquare(points: List<Pair<Float, Float>>) {
        assertTrue(points.all { (x, y) -> x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f })
    }

    private fun point(e: Double, n: Double, u: Double) = ImuPoint(e, n, u, 0.0, 0.0, 0.0)
}
