package com.orico.gestureassistant.recognizer.trajectory

/**
 * 使用与识别器完全相同的预处理和 DTW 参数，对同名模板做录制质检。
 *
 * 识别是「取最近模板」(TrajectoryRecognizer 用 minByOrNull)，不是求平均，所以同一手势允许
 * 故意录多种握法/画面（正着几条、倒着几条）——只要触发时能命中其中一条即可。因此质检不应
 * 要求「所有模板都长一个样」，而应检查「每条模板是否至少有一条相近的同类兄弟」：
 * 对每条模板取它到其余模板的最近距离，再对全部取平均。多握法各自成簇时该值很低(判好)；
 * 只有某种握法孤零零只录了一条、附近没兄弟时才会被拉高(提醒补录)。
 */
object GestureConsistency {
    const val GOOD_MAX_DISTANCE = 1.0
    const val FAIR_MAX_DISTANCE = 1.5
    private const val BAND_RADIUS = 8

    /** 每条模板到其最近同类兄弟距离的平均；不足两条返回 null。 */
    fun nearestNeighborDistance(templates: List<List<ImuPoint>>): Double? = runCatching {
        val valid = templates.filter { it.size >= TrajectoryRecognizer.MIN_SAMPLE_COUNT }
        if (valid.size < 2) return@runCatching null
        val prepared = valid.map { TrajectoryPreprocessor.prepare(it) }
        val perTemplateNearest = prepared.indices.map { i ->
            prepared.indices.asSequence()
                .filter { it != i }
                .map { j -> DynamicTimeWarping.distance(prepared[i], prepared[j], BAND_RADIUS) }
                .min()
        }
        perTemplateNearest.average().takeIf { it.isFinite() }
    }.getOrNull()

    /** 两两平均距离；保留供既有测试与对比用，label 已改走最近邻度量。 */
    fun averagePairwiseDistance(templates: List<List<ImuPoint>>): Double? = runCatching {
        val valid = templates.filter { it.size >= TrajectoryRecognizer.MIN_SAMPLE_COUNT }
        if (valid.size < 2) return@runCatching null
        val prepared = valid.map { TrajectoryPreprocessor.prepare(it) }
        var total = 0.0
        var pairs = 0
        for (first in 0 until prepared.lastIndex) {
            for (second in first + 1 until prepared.size) {
                total += DynamicTimeWarping.distance(prepared[first], prepared[second], BAND_RADIUS)
                pairs += 1
            }
        }
        if (pairs == 0) null else (total / pairs).takeIf { it.isFinite() }
    }.getOrNull()

    fun label(templates: List<List<ImuPoint>>): String = runCatching {
        val distance = nearestNeighborDistance(templates)
        when {
            distance == null -> "需多录几次"
            distance < GOOD_MAX_DISTANCE -> "一致性：好"
            distance < FAIR_MAX_DISTANCE -> "一致性：一般"
            else -> "一致性：差（某种握法可再补一条）"
        }
    }.getOrDefault("一致性：暂不可用")
}
