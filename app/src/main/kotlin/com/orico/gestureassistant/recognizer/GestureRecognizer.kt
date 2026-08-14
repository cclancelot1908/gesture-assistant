package com.orico.gestureassistant.recognizer

enum class GestureLabel {
    BACK_DOUBLE,
    BACK_TRIPLE,
    SHAKE,
    TRAJECTORY,  // TODO(Phase 6): 接入轨迹识别器。
}

fun interface GestureRecognizer<T> {
    fun onSample(sample: T): GestureLabel?
}
