package com.orico.gestureassistant.recognizer.trajectory

/** 世界坐标系轨迹点；前三维为东、北、上，后三维仅为旧六维 JSON 格式兼容位。 */
data class ImuPoint(
    val e: Double,
    val n: Double,
    val u: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
) {
    operator fun get(index: Int): Double = when (index) {
        0 -> e
        1 -> n
        2 -> u
        3 -> gx
        4 -> gy
        5 -> gz
        else -> throw IndexOutOfBoundsException("六轴索引必须在 0..5，实际为 $index")
    }

    fun values(): List<Double> = listOf(e, n, u, gx, gy, gz)

    companion object {
        const val DIMENSIONS = 6

        fun from(values: DoubleArray): ImuPoint {
            require(values.size == DIMENSIONS) { "六轴采样必须正好包含 6 个值" }
            return ImuPoint(values[0], values[1], values[2], values[3], values[4], values[5])
        }
    }
}

data class TrajectoryTemplate(
    val gestureId: String,
    val name: String,
    val points: List<ImuPoint>,
)

data class TrajectoryMatch(
    val gestureId: String,
    val name: String,
    val distance: Double,
)
