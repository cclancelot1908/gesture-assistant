package com.orico.gestureassistant.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldRun = context.getSharedPreferences(
            GestureForegroundService.KEEP_ALIVE_PREFS,
            Context.MODE_PRIVATE,
        ).getBoolean(GestureForegroundService.ENABLED, false)
        if (shouldRun) GestureForegroundService.start(context)
    }
}
