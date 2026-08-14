package com.orico.gestureassistant.recognizer

import kotlin.math.max

data class BackTapConfig(
    val sensitivity: Float = 0.5f,
    val cooldownMs: Long = 700L,
    val minTapGapMs: Long = 80L,
    val maxTapGapMs: Long = 450L,
)

data class MotionSample(
    val timestampMs: Long,
    val linearAccelerationZ: Float,
    val totalAcceleration: Float,
    val rotationMagnitude: Float,
    // 水平面(X/Y)线性加速度幅值，用于区分「敲后盖(Z主导)」与「摇一摇(水平主导)」。
    val horizontalAcceleration: Float = 0f,
)

/**
 * TapTap 思路的轻量启发式版本：先挑出沿手机 Z 轴的短促冲击，再用时间窗组合成双击。
 * 灵敏度只改变冲击阈值；时间门控和冷却独立，避免调高灵敏度后连续误触。
 */
class BackTapDetector(
    private val config: BackTapConfig,
) : GestureRecognizer<MotionSample> {
    private var firstTapAt = NO_TIME
    private var secondTapAt = NO_TIME
    private var lastTriggerAt = NO_TIME
    private var lastLoudAt = NO_TIME // 最近一次「有明显运动」的时刻，用于判断敲击之间是否真正安静过

    @Synchronized
    override fun onSample(sample: MotionSample): GestureLabel? {
        val expired = confirmPending(sample.timestampMs)
        if (expired != null) return expired
        return detectLabel(sample)
    }

    /** 第二击后留出一个既有 maxTapGapMs 窗口；到期且没有第三击才确认双击。 */
    @Synchronized
    fun confirmPending(nowMs: Long): GestureLabel? {
        if (secondTapAt == NO_TIME || nowMs - secondTapAt < config.maxTapGapMs) return null
        secondTapAt = NO_TIME
        firstTapAt = NO_TIME
        lastTriggerAt = nowMs
        return GestureLabel.BACK_DOUBLE
    }

    @Synchronized fun hasPendingDouble(): Boolean = secondTapAt != NO_TIME
    fun tripleConfirmationMs(): Long = config.maxTapGapMs

    @Deprecated("请使用 onSample/confirmPending，以支持三击与延迟双击确认")
    fun detect(sample: MotionSample): Boolean = onSample(sample) == GestureLabel.BACK_DOUBLE

    private fun detectLabel(sample: MotionSample): GestureLabel? {
        // 先取「本样本之前」最近一次明显运动的时刻，再按当前样本更新。
        val prevLoudAt = lastLoudAt
        if (max(sample.linearAccelerationZ, sample.horizontalAcceleration) > MOTION_LEVEL) {
            lastLoudAt = sample.timestampMs
        }

        if (lastTriggerAt != NO_TIME && sample.timestampMs - lastTriggerAt < config.cooldownMs) {
            return null
        }

        // 防误触门控：大幅旋转通常来自拿起/翻转手机，不当作敲击；
        // 总加速度过低或过高则多为静止噪声、跌落或剧烈运动。
        // 真机校准（OPPO PYC110）：舒适敲击 z 峰值约 5~20，用力可达 30~56；
        // 手持敲击会带来旋转扰动（实测可达 ~5 rad/s）与较大总加速度（可超 22）。
        // 故放宽三道门控，避免「必须很用力才触发」和「用力过猛反被上限挡掉」。
        // 灵敏度 0→1 对应阈值 8.0→3.0 m/s²（默认 0.5 时为 5.5）。
        val impactThreshold = 8.0f - config.sensitivity.coerceIn(0f, 1f) * 5.0f
        // Z 轴主导：敲后盖是垂直冲击，Z 应显著大于水平运动；摇一摇是水平主导，被此门控挡掉。
        val isGatedImpact = sample.linearAccelerationZ >= impactThreshold &&
            sample.linearAccelerationZ >= sample.horizontalAcceleration * Z_DOMINANCE &&
            sample.rotationMagnitude <= 6.0f &&
            sample.totalAcceleration in 5.0f..80.0f
        if (!isGatedImpact) return null

        if (secondTapAt != NO_TIME) {
            val gap = sample.timestampMs - secondTapAt
            if (gap in config.minTapGapMs..config.maxTapGapMs &&
                sample.timestampMs - prevLoudAt >= QUIET_MIN_MS
            ) {
                firstTapAt = NO_TIME
                secondTapAt = NO_TIME
                lastTriggerAt = sample.timestampMs
                return GestureLabel.BACK_TRIPLE
            }
            return null
        }

        if (firstTapAt == NO_TIME || sample.timestampMs - firstTapAt > config.maxTapGapMs) {
            firstTapAt = sample.timestampMs
            return null
        }

        val gap = max(0L, sample.timestampMs - firstTapAt)
        if (gap < config.minTapGapMs) return null

        // 关键判据：两次敲击之间必须真正「安静」过 >= QUIET_MIN_MS。
        // 前后摇是沿 Z 轴的持续运动、没有安静间隙，在此被排除；真正的双击之间有明显停顿。
        if (sample.timestampMs - prevLoudAt < QUIET_MIN_MS) {
            firstTapAt = sample.timestampMs // 视为持续运动，当作新起点，继续等待真正的双击
            return null
        }

        secondTapAt = sample.timestampMs
        return null
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        // Z 轴冲击须至少为水平运动的该倍数，才判定为「敲击」而非「左右摇」。真机校准。
        const val Z_DOMINANCE = 1.3f
        // 高于此值视为「有明显运动」；用于追踪敲击之间是否安静。
        const val MOTION_LEVEL = 2.5f
        // 第二次敲击前须至少安静这么久，才认定是「两次独立敲击」而非「持续前后摇」。
        const val QUIET_MIN_MS = 60L
    }
}
