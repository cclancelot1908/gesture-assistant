package com.orico.gestureassistant.action

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.orico.gestureassistant.rules.ActionId

class MediaActionExecutor(context: Context) : ActionExecutor {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override suspend fun execute(action: ActionId, packageName: String?): ActionResult {
        if (action != ActionId.MEDIA_PLAY_PAUSE) return ActionResult(false, "不支持的媒体动作")
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            ActionResult(true, "已切换媒体播放 / 暂停")
        } catch (_: Exception) {
            ActionResult(false, "媒体播放 / 暂停操作失败")
        }
    }
}
