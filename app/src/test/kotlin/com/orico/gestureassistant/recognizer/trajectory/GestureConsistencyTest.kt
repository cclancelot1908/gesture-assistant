package com.orico.gestureassistant.recognizer.trajectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureConsistencyTest {
    @Test
    fun `不足两条模板时没有平均距离`() {
        assertEquals(null, GestureConsistency.averagePairwiseDistance(emptyList()))
        assertEquals(null, GestureConsistency.averagePairwiseDistance(listOf(lineTemplate())))
    }

    @Test
    fun `相同模板距离小而明显不同模板距离大`() {
        val original = lineTemplate()
        val nearCopy = original.mapIndexed { index, point ->
            point.copy(u = point.u + if (index % 2 == 0) 0.005 else -0.005)
        }
        val different = waveTemplate()

        val sameDistance = GestureConsistency.averagePairwiseDistance(listOf(original, nearCopy))!!
        val differentDistance = GestureConsistency.averagePairwiseDistance(listOf(original, different))!!

        assertTrue("相似距离=$sameDistance", sameDistance < 0.1)
        assertTrue("不同距离=$differentDistance", differentDistance > sameDistance + 0.5)
    }

    private fun lineTemplate(): List<ImuPoint> = List(24) { index ->
        val t = index / 23.0
        ImuPoint(t, t * 0.2, t * 0.05, 0.0, 0.0, 0.0)
    }

    private fun waveTemplate(): List<ImuPoint> = List(24) { index ->
        val t = index / 23.0
        ImuPoint(
            kotlin.math.sin(t * Math.PI * 4.0),
            kotlin.math.cos(t * Math.PI * 3.0),
            if (index % 2 == 0) 1.0 else -1.0,
            0.0,
            0.0,
            0.0,
        )
    }
}
