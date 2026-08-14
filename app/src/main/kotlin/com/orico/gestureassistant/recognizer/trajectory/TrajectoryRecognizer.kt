package com.orico.gestureassistant.recognizer.trajectory

class TrajectoryRecognizer(
    private val pointCount: Int = TrajectoryPreprocessor.DEFAULT_POINT_COUNT,
    private val bandRadius: Int = 8,
) {
    fun recognize(
        candidate: List<ImuPoint>,
        templates: List<TrajectoryTemplate>,
        threshold: Double,
        onDebug: ((String) -> Unit)? = null,
    ): TrajectoryMatch? {
        if (candidate.size < MIN_SAMPLE_COUNT || templates.isEmpty()) {
            onDebug?.invoke("样本=${candidate.size} 模板=${templates.size} → 数据不足")
            return null
        }
        val preparedCandidate = TrajectoryPreprocessor.prepare(candidate, pointCount)
        val matches = templates.asSequence()
            .filter { it.points.size >= MIN_SAMPLE_COUNT }
            .mapNotNull { template ->
                runCatching {
                    val preparedTemplate = TrajectoryPreprocessor.prepare(template.points, pointCount)
                    TrajectoryMatch(
                        gestureId = template.gestureId,
                        name = template.name,
                        distance = DynamicTimeWarping.distance(preparedCandidate, preparedTemplate, bandRadius),
                    )
                }.getOrNull()
            }
            .toList()
        val best = matches.minByOrNull { it.distance }
        onDebug?.invoke(
            "样本=${candidate.size} 阈值=$threshold 各模板距离=[" +
                matches.joinToString { "${it.name}:${"%.3f".format(it.distance)}" } +
                "] 最优=" + (best?.let { "${it.name}:${"%.3f".format(it.distance)}" } ?: "无"),
        )
        // 阈值越小越严格；只返回明确低于阈值的最优模板。
        return best?.takeIf { it.distance < threshold }
    }

    companion object {
        const val MIN_SAMPLE_COUNT = 8
    }
}
