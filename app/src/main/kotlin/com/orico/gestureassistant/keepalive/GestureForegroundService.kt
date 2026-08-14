package com.orico.gestureassistant.keepalive

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.orico.gestureassistant.action.CompositeActionExecutor
import com.orico.gestureassistant.config.AppSettings
import com.orico.gestureassistant.feedback.NotificationFeedback
import com.orico.gestureassistant.recognizer.BackTapConfig
import com.orico.gestureassistant.recognizer.BackTapDetector
import com.orico.gestureassistant.rules.RuleEngine
import com.orico.gestureassistant.sensor.SensorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class GestureForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var feedback: NotificationFeedback
    private var sensorEngine: SensorEngine? = null
    private var settingsJob: Job? = null
    private var pendingBackTapJob: Job? = null
    private val silentAudioKeepAlive = SilentAudioKeepAlive()

    override fun onCreate() {
        super.onCreate()
        running = true
        feedback = NotificationFeedback(this)
        startForeground(
            NotificationFeedback.SERVICE_NOTIFICATION_ID,
            feedback.serviceNotification(),
        )
        getSharedPreferences(KEEP_ALIVE_PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply()
        observeSettingsAndStartSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        settingsJob?.cancel()
        pendingBackTapJob?.cancel()
        sensorEngine?.stop()
        runCatching { silentAudioKeepAlive.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeSettingsAndStartSensor() {
        settingsJob = scope.launch {
            AppSettings(applicationContext).values.collectLatest { settings ->
                runCatching {
                    if (settings.silentKeepAliveEnabled) silentAudioKeepAlive.start()
                    else silentAudioKeepAlive.stop()
                }
                sensorEngine?.stop()
                pendingBackTapJob?.cancel()
                pendingBackTapJob = null
                // 背部检测总开关关闭时，直接不启动这颗加速度传感器(省电)；前台服务与保活照常运行。
                if (!settings.backTapEnabled) {
                    sensorEngine = null
                    return@collectLatest
                }
                val detector = BackTapDetector(
                    BackTapConfig(
                        sensitivity = settings.sensitivity,
                        cooldownMs = settings.cooldownMs,
                    ),
                )
                val ruleEngine = RuleEngine(settings.bindings)
                val actionExecutor = CompositeActionExecutor(applicationContext)
                fun executeLabel(label: com.orico.gestureassistant.recognizer.GestureLabel) {
                    val action = ruleEngine.resolve(label) ?: return
                    scope.launch {
                        val result = runCatching {
                            actionExecutor.execute(action, ruleEngine.packageFor(label))
                        }.getOrElse {
                            com.orico.gestureassistant.action.ActionResult(false, "动作执行异常")
                        }
                        runCatching { feedback.showAction(result.message, result.successful) }
                    }
                }
                sensorEngine = SensorEngine(
                    applicationContext,
                    onMotionSample = { sample ->
                        detector.onSample(sample)?.let { label ->
                            pendingBackTapJob?.cancel()
                            executeLabel(label)
                        }
                        if (detector.hasPendingDouble() && pendingBackTapJob?.isActive != true) {
                            pendingBackTapJob = scope.launch {
                                delay(detector.tripleConfirmationMs())
                                detector.confirmPending(android.os.SystemClock.elapsedRealtime())?.let(::executeLabel)
                                pendingBackTapJob = null
                            }
                        }
                    },
                )
                if (sensorEngine?.start() != true) {
                    feedback.showAction("未找到加速度传感器，手势监听未启动", false)
                }
            }
        }
    }

    companion object {
        @Volatile var running: Boolean = false
            private set
        const val KEEP_ALIVE_PREFS = "keep_alive"
        const val ENABLED = "service_enabled"

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GestureForegroundService::class.java),
            )
        }

        fun stop(context: android.content.Context) {
            context.getSharedPreferences(KEEP_ALIVE_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, false).apply()
            context.stopService(Intent(context, GestureForegroundService::class.java))
        }
    }
}
