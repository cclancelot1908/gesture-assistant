package com.orico.gestureassistant.recognizer.trajectory

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 把世界坐标线性加速度粗略重建为二维路径，仅用于模板外形预览。
 * 采样索引视为均匀时间：两次积分之间分别去线性趋势，再用 3D PCA 投影。
 */
object TrajectoryPathReconstructor {
    private const val EPSILON = 1e-10
    private const val JACOBI_ITERATIONS = 24
    // 低于此峰值(m/s²)视为近乎静止，不重建(只有噪声)。
    private const val STATIC_ACC_THRESHOLD = 0.8

    /** 对归一化 2D 轨迹做小窗滑动平均，压掉双积分噪声抖动，让形状更干净。 */
    private fun smoothPath(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pts.size < 5) return pts
        val win = (pts.size / 16).coerceIn(1, 5)
        return pts.indices.map { i ->
            var sx = 0f; var sy = 0f; var cnt = 0
            for (j in (i - win)..(i + win)) if (j in pts.indices) { sx += pts[j].first; sy += pts[j].second; cnt++ }
            (if (cnt > 0) sx / cnt else pts[i].first) to (if (cnt > 0) sy / cnt else pts[i].second)
        }
    }

    fun reconstruct(points: List<ImuPoint>): List<Pair<Float, Float>> {
        if (points.size < 3) return emptyList()
        return try {
            val acceleration = Array(3) { axis ->
                DoubleArray(points.size) { index -> points[index][axis].finiteOrZero() }
            }
            // 去加速度常值偏置：残余重力/零偏是常值，双积分会变抛物线漂移，先减掉均值。
            val unbiased = Array(3) { axis -> removeMean(acceleration[axis]) }
            // 近乎静止(只有噪声)：不要把噪声拉伸铺满框，直接返回空→显示占位，避免误导。
            val peak = unbiased.maxOf { ax -> ax.maxOf { abs(it) } }
            if (peak < STATIC_ACC_THRESHOLD) return emptyList()
            val position = Array(3) { axis ->
                // 积分得速度并去趋势(手势起止静止→端点归零)，消除线性漂移；再积分得位置、仅居中(不强制闭合)。
                val velocity = detrend(integrate(unbiased[axis]))
                removeMean(integrate(velocity))
            }
            smoothPath(projectAndNormalize(position))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 去均值：减掉序列平均值。用于压掉加速度常值偏置(残余重力)与位置居中。 */
    private fun removeMean(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values.copyOf()
        val mean = values.average().finiteOrZero()
        return DoubleArray(values.size) { index -> (values[index].finiteOrZero() - mean).finiteOrZero() }
    }

    private fun integrate(values: DoubleArray): DoubleArray {
        var sum = 0.0
        return DoubleArray(values.size) { index ->
            sum += values[index]
            if (!sum.isFinite()) sum = 0.0
            sum
        }
    }

    /** 减去首末值的线性插值，使积分漂移的两个端点归零。 */
    private fun detrend(values: DoubleArray): DoubleArray {
        if (values.size < 2) return values.copyOf()
        val first = values.first().finiteOrZero()
        val last = values.last().finiteOrZero()
        val denominator = values.lastIndex.toDouble()
        return DoubleArray(values.size) { index ->
            val ratio = index / denominator
            (values[index].finiteOrZero() - (first + (last - first) * ratio)).finiteOrZero()
        }
    }

    private fun projectAndNormalize(position: Array<DoubleArray>): List<Pair<Float, Float>> {
        val count = position.first().size
        val means = DoubleArray(3) { axis -> position[axis].average().finiteOrZero() }
        val centered = Array(count) { index ->
            DoubleArray(3) { axis -> (position[axis][index] - means[axis]).finiteOrZero() }
        }
        val covariance = Array(3) { DoubleArray(3) }
        centered.forEach { sample ->
            for (row in 0..2) for (column in row..2) {
                covariance[row][column] += sample[row] * sample[column]
            }
        }
        for (row in 0..2) for (column in 0 until row) {
            covariance[row][column] = covariance[column][row]
        }

        val axes = principalAxes(covariance)
        val projected = centered.map { sample ->
            doubleArrayOf(dot(sample, axes[0]), dot(sample, axes[1]))
        }
        val minX = projected.minOf { it[0] }
        val maxX = projected.maxOf { it[0] }
        val minY = projected.minOf { it[1] }
        val maxY = projected.maxOf { it[1] }
        val width = (maxX - minX).finiteOrZero()
        val height = (maxY - minY).finiteOrZero()
        val scale = max(width, height)
        if (scale < EPSILON) return List(count) { 0.5f to 0.5f }

        val offsetX = (scale - width) / 2.0
        val offsetY = (scale - height) / 2.0
        return projected.map { point ->
            ((point[0] - minX + offsetX) / scale).toUnitFloat() to
                ((point[1] - minY + offsetY) / scale).toUnitFloat()
        }
    }

    /** Jacobi 旋转求实对称 3x3 协方差矩阵的特征向量，返回最大两个主轴。 */
    private fun principalAxes(input: Array<DoubleArray>): Array<DoubleArray> {
        val matrix = Array(3) { row -> input[row].copyOf() }
        val vectors = Array(3) { row -> DoubleArray(3) { column -> if (row == column) 1.0 else 0.0 } }
        repeat(JACOBI_ITERATIONS) {
            var p = 0
            var q = 1
            var largest = abs(matrix[p][q])
            for (row in 0..2) for (column in row + 1..2) {
                val candidate = abs(matrix[row][column])
                if (candidate > largest) {
                    largest = candidate
                    p = row
                    q = column
                }
            }
            if (largest < EPSILON) return@repeat
            val angle = 0.5 * atan2(2.0 * matrix[p][q], matrix[q][q] - matrix[p][p])
            val c = cos(angle)
            val s = sin(angle)

            for (row in 0..2) {
                val mp = matrix[row][p]
                val mq = matrix[row][q]
                matrix[row][p] = c * mp - s * mq
                matrix[row][q] = s * mp + c * mq
            }
            for (column in 0..2) {
                val mp = matrix[p][column]
                val mq = matrix[q][column]
                matrix[p][column] = c * mp - s * mq
                matrix[q][column] = s * mp + c * mq
            }
            for (row in 0..2) {
                val vp = vectors[row][p]
                val vq = vectors[row][q]
                vectors[row][p] = c * vp - s * vq
                vectors[row][q] = s * vp + c * vq
            }
        }
        val order = (0..2).sortedByDescending { matrix[it][it].finiteOrZero() }
        return Array(2) { rank -> DoubleArray(3) { axis -> vectors[axis][order[rank]].finiteOrZero() } }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { index -> a[index] * b[index] }.finiteOrZero()

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

    private fun Double.toUnitFloat(): Float = finiteOrZero().coerceIn(0.0, 1.0).toFloat()
}
