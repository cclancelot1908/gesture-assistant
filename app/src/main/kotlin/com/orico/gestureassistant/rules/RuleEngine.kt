package com.orico.gestureassistant.rules

import com.orico.gestureassistant.recognizer.GestureLabel

enum class ActionId {
    TOGGLE_FLASHLIGHT,
    MEDIA_PLAY_PAUSE,
    LAUNCH_APP,
    HTTP_WEBHOOK,
    TAKE_SCREENSHOT,
    LOCK_SCREEN,
    OPEN_NOTIFICATIONS,
    GLOBAL_BACK,
    GLOBAL_HOME,
    GLOBAL_RECENTS,
    SMART_DEVICE,
}

data class GestureBindings(
    val backDoubleAction: ActionId,
    val backTripleAction: ActionId,
    val backDoublePackage: String? = null,
    val backTriplePackage: String? = null,
) {
    companion object {
        fun defaults() = GestureBindings(
            backDoubleAction = ActionId.TOGGLE_FLASHLIGHT,
            backTripleAction = ActionId.MEDIA_PLAY_PAUSE,
        )
    }
}

/** 使用 DataStore 提供的不可变快照查表，识别线程无需阻塞读取磁盘。 */
class RuleEngine(bindings: GestureBindings) {
    private var currentBindings = bindings

    fun resolve(label: GestureLabel): ActionId? = when (label) {
        GestureLabel.BACK_DOUBLE -> currentBindings.backDoubleAction
        GestureLabel.BACK_TRIPLE -> currentBindings.backTripleAction
        GestureLabel.SHAKE -> null
        GestureLabel.TRAJECTORY -> null
    }

    fun packageFor(label: GestureLabel): String? = when (label) {
        GestureLabel.BACK_DOUBLE -> currentBindings.backDoublePackage
        GestureLabel.BACK_TRIPLE -> currentBindings.backTriplePackage
        GestureLabel.SHAKE -> null
        GestureLabel.TRAJECTORY -> null
    }

    fun bind(label: GestureLabel, action: ActionId, packageName: String? = null) {
        currentBindings = when (label) {
            GestureLabel.BACK_DOUBLE -> currentBindings.copy(
                backDoubleAction = action,
                backDoublePackage = packageName ?: currentBindings.backDoublePackage,
            )
            GestureLabel.BACK_TRIPLE -> currentBindings.copy(
                backTripleAction = action,
                backTriplePackage = packageName ?: currentBindings.backTriplePackage,
            )
            GestureLabel.SHAKE, GestureLabel.TRAJECTORY -> currentBindings
        }
    }
}
