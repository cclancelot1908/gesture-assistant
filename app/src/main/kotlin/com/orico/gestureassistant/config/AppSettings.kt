package com.orico.gestureassistant.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orico.gestureassistant.recognizer.GestureLabel
import com.orico.gestureassistant.rules.ActionId
import com.orico.gestureassistant.rules.GestureBindings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("gesture_settings")

data class GestureSettings(
    // 真机(OPPO PYC110)校准后的开箱默认：灵敏度偏高、冷却较短，减少漏敲与连续操作被吃掉。
    val sensitivity: Float = 0.85f,
    val cooldownMs: Long = 700L,
    val trajectoryThreshold: Float = 0.75f,
    // 录制模式：开启时，按住音量键画的轨迹会被【录制为模板】而非识别。
    val trajectoryRecordMode: Boolean = false,
    // 背部双击/三击检测总开关。部分手机(如自带"背部双击")会冲突，可整体关掉本 App 的背部检测。
    val backTapEnabled: Boolean = true,
    // 空中手势识别子开关；只有前台总服务运行时才真正生效。
    val airGestureEnabled: Boolean = true,
    // 首次启动说明是否已由用户选择不再显示。
    val onboardingDone: Boolean = false,
    // 可选辅助保活层，默认关闭，避免用户未授权时占用音频前台服务能力。
    val silentKeepAliveEnabled: Boolean = false,
    // 录制模式下，音量键录的轨迹存到这个名字下；改名即可录多个不同手势各绑不同动作。
    val recordingGestureName: String = "手势1",
    val bindings: GestureBindings = GestureBindings.defaults(),
)

class AppSettings(private val context: Context) {
    val values: Flow<GestureSettings> = context.settingsDataStore.data.map { preferences ->
        GestureSettings(
            sensitivity = preferences[SENSITIVITY] ?: 0.85f,
            cooldownMs = preferences[COOLDOWN] ?: 700L,
            trajectoryThreshold = preferences[TRAJECTORY_THRESHOLD] ?: 0.75f,
            trajectoryRecordMode = preferences[TRAJECTORY_RECORD_MODE] ?: false,
            backTapEnabled = preferences[BACK_TAP_ENABLED] ?: true,
            airGestureEnabled = preferences[AIR_GESTURE_ENABLED] ?: true,
            onboardingDone = preferences[ONBOARDING_DONE] ?: false,
            silentKeepAliveEnabled = preferences[SILENT_KEEP_ALIVE_ENABLED] ?: false,
            recordingGestureName = preferences[RECORDING_GESTURE_NAME]?.takeIf { it.isNotBlank() } ?: "手势1",
            bindings = GestureBindings(
                backDoubleAction = preferences[BACK_DOUBLE_ACTION].toActionId(ActionId.TOGGLE_FLASHLIGHT),
                backTripleAction = preferences[BACK_TRIPLE_ACTION].toActionId(ActionId.MEDIA_PLAY_PAUSE),
                backDoublePackage = preferences[BACK_DOUBLE_PACKAGE],
                backTriplePackage = preferences[BACK_TRIPLE_PACKAGE],
            ),
        )
    }

    suspend fun setSensitivity(value: Float) {
        context.settingsDataStore.edit { it[SENSITIVITY] = value.coerceIn(0.1f, 1f) }
    }

    suspend fun setCooldown(value: Long) {
        context.settingsDataStore.edit { it[COOLDOWN] = value.coerceIn(300L, 3_000L) }
    }

    suspend fun setTrajectoryThreshold(value: Float) {
        context.settingsDataStore.edit { it[TRAJECTORY_THRESHOLD] = value.coerceIn(0.2f, 2f) }
    }

    suspend fun setTrajectoryRecordMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[TRAJECTORY_RECORD_MODE] = enabled }
    }

    suspend fun setBackTapEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[BACK_TAP_ENABLED] = enabled }
    }

    suspend fun setAirGestureEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AIR_GESTURE_ENABLED] = enabled }
    }

    suspend fun setOnboardingDone(value: Boolean) {
        context.settingsDataStore.edit { it[ONBOARDING_DONE] = value }
    }

    suspend fun setSilentKeepAliveEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SILENT_KEEP_ALIVE_ENABLED] = enabled }
    }

    suspend fun setRecordingGestureName(name: String) {
        val clean = name.trim().ifBlank { "手势1" }
        context.settingsDataStore.edit { it[RECORDING_GESTURE_NAME] = clean }
    }

    suspend fun setAction(label: GestureLabel, action: ActionId) {
        val key = when (label) {
            GestureLabel.BACK_DOUBLE -> BACK_DOUBLE_ACTION
            GestureLabel.BACK_TRIPLE -> BACK_TRIPLE_ACTION
            GestureLabel.SHAKE, GestureLabel.TRAJECTORY -> return
        }
        context.settingsDataStore.edit { preferences ->
            preferences[key] = action.name
        }
    }

    suspend fun setAppPackage(label: GestureLabel, packageName: String) {
        val key = when (label) {
            GestureLabel.BACK_DOUBLE -> BACK_DOUBLE_PACKAGE
            GestureLabel.BACK_TRIPLE -> BACK_TRIPLE_PACKAGE
            GestureLabel.SHAKE, GestureLabel.TRAJECTORY -> return
        }
        context.settingsDataStore.edit { preferences ->
            preferences[key] = packageName
        }
    }

    private companion object {
        val SENSITIVITY = floatPreferencesKey("back_tap_sensitivity")
        val COOLDOWN = longPreferencesKey("back_tap_cooldown_ms")
        val TRAJECTORY_THRESHOLD = floatPreferencesKey("trajectory_dtw_threshold")
        val TRAJECTORY_RECORD_MODE = booleanPreferencesKey("trajectory_record_mode")
        val BACK_TAP_ENABLED = booleanPreferencesKey("back_tap_enabled")
        val AIR_GESTURE_ENABLED = booleanPreferencesKey("air_gesture_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SILENT_KEEP_ALIVE_ENABLED = booleanPreferencesKey("silent_keep_alive_enabled")
        val RECORDING_GESTURE_NAME = stringPreferencesKey("recording_gesture_name")
        val BACK_DOUBLE_ACTION = stringPreferencesKey("rule_back_double_action")
        val BACK_TRIPLE_ACTION = stringPreferencesKey("rule_back_triple_action")
        val BACK_DOUBLE_PACKAGE = stringPreferencesKey("rule_back_double_package")
        val BACK_TRIPLE_PACKAGE = stringPreferencesKey("rule_back_triple_package")
    }
}

private fun String?.toActionId(default: ActionId): ActionId = when (this) {
    "OPEN_APP" -> ActionId.LAUNCH_APP // 兼容旧版本已持久化名称。
    else -> this?.let { stored -> ActionId.entries.firstOrNull { it.name == stored } } ?: default
}
