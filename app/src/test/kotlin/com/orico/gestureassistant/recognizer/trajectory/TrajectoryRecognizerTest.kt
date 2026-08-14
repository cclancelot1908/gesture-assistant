package com.orico.gestureassistant.recognizer.trajectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.cos
import kotlin.math.sin

class TrajectoryRecognizerTest {
    @Test
    fun resample_keepsEndpointsAndProducesRequestedCount() {
        val input = listOf(point(0.0), point(10.0))

        val result = TrajectoryPreprocessor.resample(input, 5)

        assertEquals(5, result.size)
        assertEquals(0.0, result.first().e, 1e-9)
        assertEquals(10.0, result.last().e, 1e-9)
        assertEquals(5.0, result[2].e, 1e-9)
    }

    @Test
    fun normalize_centersEveryAxisAndRemovesOverallScale() {
        val input = (1..6).map { value ->
            ImuPoint(value.toDouble(), value * 2.0, -value.toDouble(), value * 3.0, 4.0, value * -2.0)
        }

        val result = TrajectoryPreprocessor.normalize(input)

        for (axis in 0 until ImuPoint.DIMENSIONS) {
            assertEquals(0.0, result.map { it[axis] }.average(), 1e-9)
        }
        val rms = kotlin.math.sqrt(result.flatMap { it.values() }.map { it * it }.average())
        assertEquals(1.0, rms, 1e-9)
    }

    @Test
    fun dtw_sameShapeAtDifferentSpeedAndStrength_hasSmallDistance() {
        val base = curve(64, strength = 1.0, timePower = 1.0)
        val changed = curve(91, strength = 3.5, timePower = 1.7)
        val a = TrajectoryPreprocessor.prepare(base, 48)
        val b = TrajectoryPreprocessor.prepare(changed, 48)

        val distance = DynamicTimeWarping.distance(a, b, bandRadius = 12)

        assertTrue("distance=$distance", distance < 0.70)
    }

    @Test
    fun dtw_differentShapes_hasLargerDistance() {
        val curve = TrajectoryPreprocessor.prepare(curve(64, 1.0, 1.0), 48)
        val opposite = TrajectoryPreprocessor.prepare(oppositeCurve(64), 48)

        val distance = DynamicTimeWarping.distance(curve, opposite, bandRadius = 12)

        assertTrue("distance=$distance", distance > 0.8)
    }

    @Test
    fun prepare_sameHorizontalShapeAtDifferentHeadings_makesDtwDistanceSignificantlySmaller() {
        val base = headingCurve(72)
        val rotated = rotateHorizontal(base, Math.toRadians(73.0))
        val withoutHeadingNormalization = DynamicTimeWarping.distance(
            TrajectoryPreprocessor.normalize(TrajectoryPreprocessor.resample(base, 48)),
            TrajectoryPreprocessor.normalize(TrajectoryPreprocessor.resample(rotated, 48)),
            bandRadius = 12,
        )

        val normalizedDistance = DynamicTimeWarping.distance(
            TrajectoryPreprocessor.prepare(base, 48),
            TrajectoryPreprocessor.prepare(rotated, 48),
            bandRadius = 12,
        )

        assertTrue(
            "before=$withoutHeadingNormalization after=$normalizedDistance",
            normalizedDistance < withoutHeadingNormalization * 0.2,
        )
        assertTrue("distance=$normalizedDistance", normalizedDistance < 1e-6)
    }

    @Test
    fun normalizeHorizontalHeading_resolvesPrincipalAxisHalfTurnAmbiguity() {
        val base = headingCurve(72)
        val halfTurn = rotateHorizontal(base, Math.PI)

        val first = TrajectoryPreprocessor.prepare(base, 48)
        val second = TrajectoryPreprocessor.prepare(halfTurn, 48)

        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.e, b.e, 1e-8)
            assertEquals(a.n, b.n, 1e-8)
            assertEquals(a.u, b.u, 1e-8)
        }
    }

    @Test
    fun recognize_returnsBestTemplateOnlyBelowThreshold() {
        val templates = listOf(
            TrajectoryTemplate("z-id", "Z", curve(64, 1.0, 1.0)),
            TrajectoryTemplate("other-id", "反向", oppositeCurve(64)),
        )
        val recognizer = TrajectoryRecognizer(pointCount = 48, bandRadius = 12)

        val accepted = recognizer.recognize(curve(80, 2.0, 1.2), templates, threshold = 0.65)
        val rejected = recognizer.recognize(noiseLike(80), templates, threshold = 0.25)

        assertNotNull(accepted)
        assertEquals("Z", accepted?.name)
        assertNull(rejected)
    }

    private fun point(value: Double) = ImuPoint(value, value, value, value, value, value)

    private fun curve(count: Int, strength: Double, timePower: Double): List<ImuPoint> =
        (0 until count).map { index ->
            val t = (index.toDouble() / (count - 1)).pow(timePower)
            ImuPoint(
                e = strength * kotlin.math.sin(t * Math.PI * 2),
                n = strength * kotlin.math.cos(t * Math.PI),
                u = strength * (t - 0.5),
                gx = strength * kotlin.math.sin(t * Math.PI),
                gy = strength * t * t,
                gz = strength * kotlin.math.cos(t * Math.PI * 2),
            )
        }

    private fun oppositeCurve(count: Int): List<ImuPoint> = (0 until count).map { index ->
        val t = index.toDouble() / (count - 1)
        ImuPoint(t, t * t, kotlin.math.sin(t * Math.PI * 4), -t, kotlin.math.cos(t * Math.PI * 3), t)
    }

    private fun noiseLike(count: Int): List<ImuPoint> = (0 until count).map { index ->
        val t = index.toDouble()
        ImuPoint(
            kotlin.math.sin(t * 1.71), kotlin.math.cos(t * 2.13), kotlin.math.sin(t * 2.77),
            kotlin.math.cos(t * 3.11), kotlin.math.sin(t * 3.73), kotlin.math.cos(t * 4.19),
        )
    }

    private fun headingCurve(count: Int): List<ImuPoint> = (0 until count).map { index ->
        val t = index.toDouble() / (count - 1)
        ImuPoint(
            e = 2.0 * t - 1.0,
            n = 0.45 * sin(t * Math.PI * 2) + 0.2 * t,
            u = cos(t * Math.PI) * 0.35,
            gx = 0.0,
            gy = 0.0,
            gz = 0.0,
        )
    }

    private fun rotateHorizontal(points: List<ImuPoint>, angle: Double): List<ImuPoint> =
        points.map { point ->
            ImuPoint(
                e = cos(angle) * point.e - sin(angle) * point.n,
                n = sin(angle) * point.e + cos(angle) * point.n,
                u = point.u,
                gx = point.gx,
                gy = point.gy,
                gz = point.gz,
            )
        }
}
