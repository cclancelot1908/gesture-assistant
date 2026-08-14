package com.orico.gestureassistant.recognizer.trajectory

/**
 * 聚类感知的录入引导。识别是「取最近模板」，故同一手势允许多种握法/画法共存；每种握法应各自
 * 录够若干条才稳。这里按 DTW 距离把同名模板聚成「握法簇」（单链并查集，簇内两两足够近即同一种），
 * 逐簇检查条数：有簇不足就催补该种握法，全部够了才建议保存，并提示用户可继续录别的手势或补精度。
 */
object EnrollmentGuide {
    /**
     * 两条模板距离小于此值视为同一种握法/画法。默认贴合出厂识别阈值，但实际应传入当前识别阈值：
     * 识别都会认成同一手势（距离<阈值）的两条，本就该算同一簇，否则同握法的正常抖动会被拆成多簇。
     */
    const val DEFAULT_MERGE_DISTANCE = 1.5
    /** 每种握法建议的最少条数（低于此值该种就不算稳）。 */
    const val MIN_PER_CLUSTER = 2
    private const val BAND_RADIUS = 8

    data class Advice(val ready: Boolean, val clusterCount: Int, val message: String)

    /** 把同名模板聚成握法簇，返回每簇条数（降序）。mergeDistance 建议传当前识别阈值。 */
    fun clusterSizes(templates: List<List<ImuPoint>>, mergeDistance: Double = DEFAULT_MERGE_DISTANCE): List<Int> = runCatching {
        val valid = templates.filter { it.size >= TrajectoryRecognizer.MIN_SAMPLE_COUNT }
        if (valid.isEmpty()) return@runCatching emptyList()
        val prepared = valid.map { TrajectoryPreprocessor.prepare(it) }
        val parent = IntArray(prepared.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }
        for (i in prepared.indices) {
            for (j in i + 1 until prepared.size) {
                if (DynamicTimeWarping.distance(prepared[i], prepared[j], BAND_RADIUS) < mergeDistance) {
                    parent[find(i)] = find(j)
                }
            }
        }
        prepared.indices.groupingBy { find(it) }.eachCount().values.sortedDescending()
    }.getOrDefault(emptyList())

    fun advise(templates: List<List<ImuPoint>>, mergeDistance: Double = DEFAULT_MERGE_DISTANCE): Advice = runCatching {
        val sizes = clusterSizes(templates, mergeDistance)
        val total = sizes.sum()
        when {
            total == 0 -> Advice(false, 0, "长按开始，先画一次这个手势")
            // 只有一种握法且还没凑够：常规「继续补」路径。
            sizes.size == 1 && sizes[0] < MIN_PER_CLUSTER ->
                Advice(false, 1, "这种握法再画 ${MIN_PER_CLUSTER - sizes[0]} 次（每种握法各≥$MIN_PER_CLUSTER 条再保存）")
            sizes.size == 1 ->
                Advice(true, 1, "✓ 这种握法够了。可换个握法（正着/倒着）各画 2 条让它都能用；也可去录别的手势。")
            // 多种握法：有簇没凑够就催补，这一条常是刚画出的「新类型」单条簇。
            sizes.any { it < MIN_PER_CLUSTER } -> {
                val short = sizes.count { it < MIN_PER_CLUSTER }
                Advice(false, sizes.size, "识别到 ${sizes.size} 种握法，其中 $short 种只录了 1 条；请把这几种各补到 $MIN_PER_CLUSTER 条再保存")
            }
            else ->
                Advice(true, sizes.size, "✓ 已录 ${sizes.size} 种握法、各≥$MIN_PER_CLUSTER 条，可保存。想更准就给某种握法多补几条；也可继续录别的手势。")
        }
    }.getOrDefault(Advice(false, 0, "录入引导暂不可用"))
}
