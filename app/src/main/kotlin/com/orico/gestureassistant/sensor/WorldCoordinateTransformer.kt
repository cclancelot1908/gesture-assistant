package com.orico.gestureassistant.sensor

/** 纯 Kotlin 坐标变换，便于脱离 Android 传感器运行单元测试。 */
object WorldCoordinateTransformer {
    /**
     * Android 旋转矩阵为行主序，含义是设备坐标到世界 ENU（东、北、上）的映射。
     * 矩阵不可用时保守回退设备三轴，保证传感器刚启动或设备不支持旋转向量时不崩溃。
     */
    fun rotateDeviceToWorld(
        rotationMatrix: FloatArray,
        x: Float,
        y: Float,
        z: Float,
    ): FloatArray {
        if (rotationMatrix.size < MATRIX_SIZE) return floatArrayOf(x, y, z)
        return runCatching {
            floatArrayOf(
                rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z,
                rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z,
                rotationMatrix[6] * x + rotationMatrix[7] * y + rotationMatrix[8] * z,
            )
        }.getOrElse { floatArrayOf(x, y, z) }
    }

    private const val MATRIX_SIZE = 9
}
