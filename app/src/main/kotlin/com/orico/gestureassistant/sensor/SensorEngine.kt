package com.orico.gestureassistant.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.orico.gestureassistant.recognizer.MotionSample
import kotlin.math.sqrt

/** 轨迹录制采样；前三轴是世界 ENU 线性加速度，后三轴保留兼容位且固定为零。 */
data class RawImuSample(
    val timestampNs: Long,
    val worldE: Float,
    val worldN: Float,
    val worldU: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
)

/** 只负责把 Android 传感器事件归一化；不包含手势规则。 */
class SensorEngine(
    context: Context,
    private val onMotionSample: (MotionSample) -> Unit,
    private val onRawImuSample: ((RawImuSample) -> Unit)? = null,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravity = FloatArray(3)
    private val rotation = FloatArray(3)
    private val deviceToWorld = FloatArray(9)
    @Volatile private var deviceToWorldReady = false
    private var rotationMagnitude = 0f

    fun start(): Boolean {
        // 敲击是极短的瞬时尖峰，用 FASTEST 尽量不漏采样点。
        val accelerometerRegistered = runCatching {
            accelerometer != null && manager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_FASTEST,
            )
        }.getOrDefault(false)
        runCatching {
            gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            rotationVector?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        return accelerometerRegistered
    }

    fun stop() {
        runCatching { manager.unregisterListener(this) }
        deviceToWorldReady = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                event.values.copyInto(rotation, endIndex = 3)
                rotationMagnitude = magnitude(event.values)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                // TYPE_ROTATION_VECTOR 给出设备到世界 ENU 的姿态；先写临时数组，成功后再发布完整矩阵。
                runCatching {
                    val next = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(next, event.values)
                    next.copyInto(deviceToWorld)
                    deviceToWorldReady = true
                }.onFailure { deviceToWorldReady = false }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 低通估算重力，再取各轴高通分量作为线性加速度。
                for (index in 0..2) {
                    gravity[index] = GRAVITY_ALPHA * gravity[index] +
                        (1f - GRAVITY_ALPHA) * event.values[index]
                }
                val lx = event.values[0] - gravity[0]
                val ly = event.values[1] - gravity[1]
                val lz = event.values[2] - gravity[2]
                val world = if (deviceToWorldReady) {
                    WorldCoordinateTransformer.rotateDeviceToWorld(deviceToWorld, lx, ly, lz)
                } else {
                    // 旋转向量尚未到达或硬件缺失时回退设备轴，本样本仍可用且不会阻塞采集。
                    floatArrayOf(lx, ly, lz)
                }
                onRawImuSample?.invoke(
                    RawImuSample(
                        timestampNs = event.timestamp,
                        worldE = world[0],
                        worldN = world[1],
                        worldU = world[2],
                        // 世界系加速度是 DTW 主特征；兼容的后三维置零，避免设备系陀螺重新引入握姿依赖。
                        gx = 0f,
                        gy = 0f,
                        gz = 0f,
                    ),
                )
                onMotionSample(
                    MotionSample(
                        timestampMs = event.timestamp / 1_000_000L,
                        linearAccelerationZ = kotlin.math.abs(lz),
                        totalAcceleration = magnitude(event.values),
                        rotationMagnitude = rotationMagnitude,
                        horizontalAcceleration = sqrt(lx * lx + ly * ly),
                    ),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(values: FloatArray): Float =
        sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])

    private companion object {
        const val GRAVITY_ALPHA = 0.8f
    }
}
