package com.orico.gestureassistant.feedback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.orico.gestureassistant.R
import com.orico.gestureassistant.ui.MainActivity

class NotificationFeedback(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "手势监听服务", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(FEEDBACK_CHANNEL, "手势执行反馈", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "背部双击等手势执行后的即时提示"
                enableVibration(true)
            },
        )
    }

    fun serviceNotification(): Notification = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_gesture)
        .setContentTitle("手势助手正在运行")
        .setContentText("正在监听背部双击")
        .setContentIntent(mainPendingIntent())
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    fun showAction(message: String, successful: Boolean) {
        vibrate(successful)
        val notification = NotificationCompat.Builder(context, FEEDBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_gesture)
            .setContentTitle(if (successful) "手势已执行" else "手势执行失败")
            .setContentText(message)
            .setContentIntent(mainPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(FEEDBACK_NOTIFICATION_ID, notification)
    }

    /** 独立于通知的即时触感：成功“哒哒”两下，失败一记长震；无马达或异常均静默降级。 */
    private fun vibrate(successful: Boolean) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            val effect = if (successful) {
                // 两短促脉冲：timings 与 amplitudes 一一对应，0 为静默间隔。
                VibrationEffect.createWaveform(longArrayOf(0, 30, 80, 30), intArrayOf(0, 200, 0, 200), -1)
            } else {
                VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        }
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
        private const val FEEDBACK_NOTIFICATION_ID = 1002
        private const val SERVICE_CHANNEL = "gesture_service"
        private const val FEEDBACK_CHANNEL = "gesture_feedback"
    }
}
