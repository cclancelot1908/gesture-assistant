package com.orico.gestureassistant.recognizer.trajectory

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object DynamicTimeWarping {
    /**
     * 经典 DTW 累积代价。Sakoe-Chiba 带限制路径不能离对角线太远，既防止不合理拉伸，
     * 也把复杂度从 O(n*m) 压到 O(n*band)。返回按路径长度近似归一化的平均距离。
     */
    fun distance(first: List<ImuPoint>, second: List<ImuPoint>, bandRadius: Int): Double {
        require(first.isNotEmpty() && second.isNotEmpty()) { "DTW 输入不能为空" }
        require(bandRadius >= 0) { "DTW 带宽不能为负数" }
        val effectiveBand = max(bandRadius, kotlin.math.abs(first.size - second.size))
        var previous = DoubleArray(second.size + 1) { Double.POSITIVE_INFINITY }
        var previousSteps = IntArray(second.size + 1)
        previous[0] = 0.0

        for (i in 1..first.size) {
            val current = DoubleArray(second.size + 1) { Double.POSITIVE_INFINITY }
            val currentSteps = IntArray(second.size + 1)
            val from = max(1, i - effectiveBand)
            val to = min(second.size, i + effectiveBand)
            for (j in from..to) {
                val local = euclidean(first[i - 1], second[j - 1])
                val predecessors = doubleArrayOf(previous[j], current[j - 1], previous[j - 1])
                val bestIndex = predecessors.indices.minBy { predecessors[it] }
                current[j] = local + predecessors[bestIndex]
                currentSteps[j] = 1 + when (bestIndex) {
                    0 -> previousSteps[j]
                    1 -> currentSteps[j - 1]
                    else -> previousSteps[j - 1]
                }
            }
            previous = current
            previousSteps = currentSteps
        }
        return previous[second.size] / previousSteps[second.size].coerceAtLeast(1)
    }

    private fun euclidean(first: ImuPoint, second: ImuPoint): Double = sqrt(
        (0 until ImuPoint.DIMENSIONS).sumOf { axis ->
            val delta = first[axis] - second[axis]
            delta * delta
        },
    )
}
