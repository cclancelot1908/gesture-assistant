package com.orico.gestureassistant.recognizer.trajectory

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 仅用于引导绘制的标准参考笔画；真正识别仍使用用户录制的世界系模板。 */
data class GesturePreset(
    val id: String,
    val displayName: String,
    val stroke: List<Pair<Float, Float>>,
) {
    /** 引导界面使用的参考轨迹；保留 stroke 以兼容现有调用和测试。 */
    val referenceStroke: List<Pair<Float, Float>> get() = stroke
}

object GesturePresets {
    val all: List<GesturePreset> = listOf(
        GesturePreset("z", "Z", polyline(listOf(0.12f to 0.86f, 0.88f to 0.86f, 0.12f to 0.14f, 0.88f to 0.14f), 28)),
        GesturePreset("l", "L", polyline(listOf(0.25f to 0.88f, 0.25f to 0.14f, 0.86f to 0.14f), 24)),
        GesturePreset("check", "V（对勾）", polyline(listOf(0.12f to 0.55f, 0.40f to 0.18f, 0.88f to 0.84f), 26)),
        GesturePreset("o_cw", "O（顺时针）", circle(clockwise = true)),
        GesturePreset("o_ccw", "O（逆时针）", circle(clockwise = false)),
        GesturePreset("swipe_up", "上划", polyline(listOf(0.50f to 0.12f, 0.50f to 0.88f), 24)),
        GesturePreset("swipe_down", "下划", polyline(listOf(0.50f to 0.88f, 0.50f to 0.12f), 24)),
    )

    private fun polyline(anchors: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
        require(anchors.size >= 2 && count >= 2)
        val lengths = anchors.zipWithNext { first, second ->
            kotlin.math.hypot((second.first - first.first).toDouble(), (second.second - first.second).toDouble())
        }
        val total = lengths.sum().coerceAtLeast(0.0001)
        return List(count) { index ->
            val target = total * index / (count - 1)
            var accumulated = 0.0
            var segment = lengths.lastIndex
            for (candidate in lengths.indices) {
                if (target <= accumulated + lengths[candidate]) {
                    segment = candidate
                    break
                }
                accumulated += lengths[candidate]
            }
            val ratio = ((target - accumulated) / lengths[segment].coerceAtLeast(0.0001)).coerceIn(0.0, 1.0).toFloat()
            val start = anchors[segment]
            val end = anchors[segment + 1]
            start.first + (end.first - start.first) * ratio to
                start.second + (end.second - start.second) * ratio
        }
    }

    private fun circle(clockwise: Boolean): List<Pair<Float, Float>> = List(32) { index ->
        // 从顶部开始，方向差异在起点标记与笔画时序上可见。
        val direction = if (clockwise) 1.0 else -1.0
        val angle = PI / 2.0 - direction * (2.0 * PI * index / 31.0)
        (0.5 + 0.37 * cos(angle)).toFloat() to (0.5 + 0.37 * sin(angle)).toFloat()
    }
}
