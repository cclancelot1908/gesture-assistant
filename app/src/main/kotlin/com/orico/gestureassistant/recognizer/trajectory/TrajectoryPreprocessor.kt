package com.orico.gestureassistant.recognizer.trajectory

import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object TrajectoryPreprocessor {
    const val DEFAULT_POINT_COUNT = 48
    private const val EPSILON = 1e-9

    fun prepare(points: List<ImuPoint>, pointCount: Int = DEFAULT_POINT_COUNT): List<ImuPoint> =
        normalizeHorizontalHeading(normalize(resample(points, pointCount)))

    /**
     * 以原始采样索引为时间轴做线性插值，统一点数以降低绘制快慢和采样率差异。
     * 采集端以固定传感器速率工作，因此索引可视为等间隔时间。
     */
    fun resample(points: List<ImuPoint>, pointCount: Int): List<ImuPoint> {
        require(points.isNotEmpty()) { "轨迹不能为空" }
        require(pointCount >= 2) { "重采样点数至少为 2" }
        if (points.size == 1) return List(pointCount) { points.first() }

        val maxSourceIndex = points.lastIndex.toDouble()
        return List(pointCount) { targetIndex ->
            val sourcePosition = targetIndex * maxSourceIndex / (pointCount - 1)
            val left = sourcePosition.toInt().coerceAtMost(points.lastIndex)
            val right = (left + 1).coerceAtMost(points.lastIndex)
            val ratio = sourcePosition - left
            ImuPoint.from(DoubleArray(ImuPoint.DIMENSIONS) { axis ->
                points[left][axis] + (points[right][axis] - points[left][axis]) * ratio
            })
        }
    }

    /**
     * 六轴分别去均值，再以全部轴的整体 RMS 缩放。去均值消除静态偏置，统一幅值降低力度差异；
     * 保留各轴之间的相对幅度，避免逐轴缩放把弱轴噪声放大成有效形状。
     */
    fun normalize(points: List<ImuPoint>): List<ImuPoint> {
        require(points.isNotEmpty()) { "轨迹不能为空" }
        val means = DoubleArray(ImuPoint.DIMENSIONS) { axis -> points.map { it[axis] }.average() }
        val centered = points.map { point ->
            DoubleArray(ImuPoint.DIMENSIONS) { axis -> point[axis] - means[axis] }
        }
        val rms = sqrt(centered.flatMap { it.asList() }.map { it * it }.average())
        val scale = if (rms < EPSILON) 1.0 else rms
        return centered.map { values -> ImuPoint.from(DoubleArray(values.size) { values[it] / scale }) }
    }

    /**
     * 对世界系水平面 E/N 做 PCA，把最大方差主轴旋转到 +E；U 不参与旋转、保持重力对齐。
     * PCA 主轴天然有 180° 歧义，优先用首尾净位移定向；闭合轨迹则令主轴绝对值最大的首个样本为正。
     */
    fun normalizeHorizontalHeading(points: List<ImuPoint>): List<ImuPoint> {
        require(points.isNotEmpty()) { "轨迹不能为空" }
        val meanE = points.map { it.e }.average()
        val meanN = points.map { it.n }.average()
        var covarianceEE = 0.0
        var covarianceNN = 0.0
        var covarianceEN = 0.0
        points.forEach { point ->
            val e = point.e - meanE
            val n = point.n - meanN
            covarianceEE += e * e
            covarianceNN += n * n
            covarianceEN += e * n
        }
        if (covarianceEE + covarianceNN < EPSILON) return points

        val theta = 0.5 * atan2(2.0 * covarianceEN, covarianceEE - covarianceNN)
        val cosine = cos(theta)
        val sine = sin(theta)
        var rotated = points.map { point ->
            point.copy(
                e = cosine * point.e + sine * point.n,
                n = -sine * point.e + cosine * point.n,
            )
        }

        val displacement = rotated.last().e - rotated.first().e
        val directionProbe = if (abs(displacement) >= EPSILON) {
            displacement
        } else {
            rotated.maxByOrNull { abs(it.e) }?.e ?: 0.0
        }
        if (directionProbe < 0.0) {
            rotated = rotated.map { point -> point.copy(e = -point.e, n = -point.n) }
        }
        return rotated
    }
}
