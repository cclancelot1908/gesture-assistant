package com.orico.gestureassistant.recognizer.trajectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentGuideTest {
    @Test
    fun `空模板不建议保存`() {
        val advice = EnrollmentGuide.advise(emptyList())
        assertFalse(advice.ready)
        assertEquals(0, advice.clusterCount)
    }

    @Test
    fun `单条模板不建议保存`() {
        val advice = EnrollmentGuide.advise(listOf(lineTemplate()))
        assertFalse(advice.ready)
    }

    @Test
    fun `同一握法两条即可保存且只算一种`() {
        val advice = EnrollmentGuide.advise(listOf(lineTemplate(), lineTemplate(0.006)), MERGE)
        assertTrue(advice.message, advice.ready)
        assertEquals(1, advice.clusterCount)
    }

    @Test
    fun `已够的单簇再加一个新类型单条会重新提示补录`() {
        val advice = EnrollmentGuide.advise(listOf(lineTemplate(), lineTemplate(0.006), waveTemplate()), MERGE)
        assertFalse("新类型只有1条应提示补录：${advice.message}", advice.ready)
        assertEquals(2, advice.clusterCount)
    }

    @Test
    fun `两种握法各两条建议保存`() {
        val advice = EnrollmentGuide.advise(
            listOf(lineTemplate(), lineTemplate(0.006), waveTemplate(), waveTemplate(0.006)),
            MERGE,
        )
        assertTrue(advice.message, advice.ready)
        assertEquals(2, advice.clusterCount)
    }

    @Test
    fun `合并距离放大后原本分开的两条应并成一簇`() {
        // 合并距离过紧→两条各成一簇；放大到远超其间距→并成一簇。证明聚类随识别阈值走。
        val pair = listOf(lineTemplate(), waveTemplate())
        assertEquals("过紧应各自成簇", 2, EnrollmentGuide.advise(pair, 0.001).clusterCount)
        assertEquals("放大后应并为一簇", 1, EnrollmentGuide.advise(pair, 100.0).clusterCount)
    }

    private companion object {
        // 介于「同握法抖动(≈0.02)」与「line↔wave 明显差异(>0.5)」之间，用于多簇用例。
        const val MERGE = 0.3
    }

    private fun lineTemplate(jitter: Double = 0.0): List<ImuPoint> = List(24) { index ->
        val t = index / 23.0
        ImuPoint(t, t * 0.2, t * 0.05 + if (index % 2 == 0) jitter else -jitter, 0.0, 0.0, 0.0)
    }

    private fun waveTemplate(jitter: Double = 0.0): List<ImuPoint> = List(24) { index ->
        val t = index / 23.0
        ImuPoint(
            kotlin.math.sin(t * Math.PI * 4.0),
            kotlin.math.cos(t * Math.PI * 3.0),
            (if (index % 2 == 0) 1.0 else -1.0) + jitter,
            0.0,
            0.0,
            0.0,
        )
    }
}
