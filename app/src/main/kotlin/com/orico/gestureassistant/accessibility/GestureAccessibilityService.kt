package com.orico.gestureassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.orico.gestureassistant.action.ActionResult
import com.orico.gestureassistant.action.CompositeActionExecutor
import com.orico.gestureassistant.config.AppSettings
import com.orico.gestureassistant.config.GestureSettings
import com.orico.gestureassistant.feedback.NotificationFeedback
import com.orico.gestureassistant.keepalive.GestureForegroundService
import com.orico.gestureassistant.recognizer.trajectory.ImuPoint
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryGesture
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryGestureStore
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryRecognizer
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryTemplate
import com.orico.gestureassistant.sensor.RawImuSample
import com.orico.gestureassistant.sensor.SensorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 按住音量减键时采集轨迹；松开后复用 App 内同一套 DTW 模板识别与动作执行链路。 */
class GestureAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appSettings by lazy { AppSettings(applicationContext) }
    private val gestureStore by lazy { TrajectoryGestureStore(applicationContext) }
    private val recognizer = TrajectoryRecognizer()
    private val executor by lazy {
        CompositeActionExecutor(
            applicationContext,
            globalPerformer = { action -> runCatching { performGlobalAction(action) }.getOrDefault(false) },
            globalAvailable = { true },
        )
    }
    private val feedback by lazy { NotificationFeedback(applicationContext) }

    @Volatile
    private var latestSettings = GestureSettings()

    @Volatile
    private var latestGestures: List<TrajectoryGesture> = emptyList()

    private var sensorEngine: SensorEngine? = null
    private val capturedPoints = mutableListOf<ImuPoint>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyDownAt = NO_TIME
    @Volatile private var longPressReached = false
    private val markLongPress = Runnable { longPressReached = keyDownAt != NO_TIME }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceScope.launch {
            appSettings.values.collectLatest { settings ->
                latestSettings = settings
            }
        }
        serviceScope.launch {
            gestureStore.gestures.collectLatest { latestGestures = it }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GlobalGestureKeyPolicy.shouldConsume(event.keyCode, CONFIGURED_KEY_CODE)) return false
        return runCatching {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    keyDownAt = SystemClock.elapsedRealtime()
                    longPressReached = false
                    mainHandler.removeCallbacks(markLongPress)
                    mainHandler.postDelayed(markLongPress, LONG_PRESS_MS)
                    startCapture()
                }
                KeyEvent.ACTION_UP -> finishKeyPress(SystemClock.elapsedRealtime())
            }
            true
        }.getOrElse {
            Log.w(TAG, "处理音量减键失败", it)
            stopCapture()
            true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopCapture()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mainHandler.removeCallbacks(markLongPress)
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun finishKeyPress(nowMs: Long) {
        mainHandler.removeCallbacks(markLongPress)
        val downAt = keyDownAt
        keyDownAt = NO_TIME
        val duration = if (downAt == NO_TIME) 0L else (nowMs - downAt).coerceAtLeast(0L)
        if (GlobalGestureKeyPolicy.isShortPress(duration, LONG_PRESS_MS)) {
            stopCapture()
            capturedPoints.clear()
            runCatching {
                getSystemService(AudioManager::class.java)?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
            }.onFailure { Log.w(TAG, "补发系统音量减失败", it) }
            return
        }
        finishCapture()
    }

    private fun startCapture() {
        if (sensorEngine != null) return
        capturedPoints.clear()
        val engine = runCatching {
            SensorEngine(
                context = applicationContext,
                onMotionSample = { },
                onRawImuSample = { sample -> capturedPoints += sample.toPoint() },
            )
        }.getOrElse {
            showFeedback("无法初始化运动传感器", false)
            return
        }
        sensorEngine = engine
        val started = runCatching { engine.start() }.getOrDefault(false)
        if (!started) {
            stopCapture()
            showFeedback("无法启动加速度传感器", false)
        }
    }

    private fun finishCapture() {
        if (sensorEngine == null) return
        // 必须先停传感器，再复制稳定快照并识别，避免泄漏和回调继续写入。
        stopCapture()
        val points = capturedPoints.toList()
        if (points.size < TrajectoryRecognizer.MIN_SAMPLE_COUNT) {
            showFeedback("轨迹太短，未识别", false)
            return
        }

        // 录制模式：用音量键的同一握姿录模板，保证模板与触发姿态一致（否则识别距离偏大）。
        if (latestSettings.trajectoryRecordMode) {
            serviceScope.launch(Dispatchers.Default) { recordTemplate(points) }
        } else {
            serviceScope.launch(Dispatchers.Default) { recognizeAndExecute(points) }
        }
    }

    private suspend fun recordTemplate(points: List<ImuPoint>) {
        // 手势名由 App 的「录制手势名」输入框决定，改名即可录多个不同手势。
        val name = latestSettings.recordingGestureName.ifBlank { VOLUME_KEY_GESTURE_NAME }
        val saved = runCatching {
            gestureStore.addTemplate(name, points)
            gestureStore.gestures.first()
        }.getOrNull()
        val count = saved?.firstOrNull { it.name == name }?.templates?.size
        Log.d(TAG, "音量键录制：保存'$name' ${points.size}点 共${count}条模板")
        showFeedback("已录制'$name'（${points.size}点，共${count ?: 0}条）", true)
    }

    private suspend fun recognizeAndExecute(points: List<ImuPoint>) {
        // 总开关和空中手势子开关必须同时开启，才允许识别并触发动作。
        if (!GestureForegroundService.running || !latestSettings.airGestureEnabled) {
            showFeedback("总开关或空中手势未开启", false)
            return
        }
        val gestures = latestGestures.filter { it.enabled }
        val templates = gestures.flatMap { gesture ->
            gesture.templates.map { points -> TrajectoryTemplate(gesture.id, gesture.name, points) }
        }
        val match = runCatching {
            recognizer.recognize(
                candidate = points,
                templates = templates,
                threshold = latestSettings.trajectoryThreshold.toDouble(),
            ) { Log.d(TAG, "全局轨迹识别：$it") }
        }.getOrNull()
        if (match == null) {
            showFeedback("未识别到已录手势", false)
            return
        }

        val gesture = gestures.firstOrNull { it.id == match.gestureId }
        if (gesture == null) {
            showFeedback("模板绑定已失效", false)
            return
        }
        val parameter = when (gesture.action) {
            com.orico.gestureassistant.rules.ActionId.HTTP_WEBHOOK -> gesture.webhookUrl
            com.orico.gestureassistant.rules.ActionId.LAUNCH_APP -> gesture.appPackage
            com.orico.gestureassistant.rules.ActionId.SMART_DEVICE ->
                "${gesture.smartDeviceId.orEmpty()}|${gesture.smartOp ?: "toggle"}"
            else -> null
        }
        val result = runCatching { executor.execute(gesture.action, parameter) }
            .getOrElse { ActionResult(false, "动作执行异常") }
        Log.d(TAG, "执行 ${gesture.name} action=${gesture.action} param=$parameter -> ok=${result.successful} msg=${result.message}")
        showFeedback("${gesture.name}：${result.message}", result.successful)
    }

    private fun stopCapture() {
        val engine = sensorEngine
        sensorEngine = null
        runCatching { engine?.stop() }
    }

    private fun showFeedback(message: String, successful: Boolean) {
        runCatching { feedback.showAction(message, successful) }
            .onFailure { Log.w(TAG, "无法显示手势反馈", it) }
    }

    private fun RawImuSample.toPoint() = ImuPoint(
        worldE.toDouble(), worldN.toDouble(), worldU.toDouble(), gx.toDouble(), gy.toDouble(), gz.toDouble(),
    )

    companion object {
        const val TAG = "GestureAccessibility"
        const val CONFIGURED_KEY_CODE = KeyEvent.KEYCODE_VOLUME_DOWN
        // 音量键录制的模板统一存到这个手势名下（默认动作=手电筒），保证握姿一致。
        const val VOLUME_KEY_GESTURE_NAME = "音量键手势"
        const val LONG_PRESS_MS = 350L
        const val NO_TIME = Long.MIN_VALUE
        @Volatile private var instance: GestureAccessibilityService? = null

        /** 前台背部轻点通过此桥调用当前运行中的无障碍服务。 */
        fun performGlobal(action: Int): Boolean =
            instance?.let { service -> runCatching { service.performGlobalAction(action) }.getOrDefault(false) }
                ?: false
        fun isRunning(): Boolean = instance != null
    }
}
