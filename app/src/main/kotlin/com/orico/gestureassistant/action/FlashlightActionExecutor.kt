package com.orico.gestureassistant.action

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.orico.gestureassistant.rules.ActionId

class FlashlightActionExecutor(context: Context) : ActionExecutor {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    private var enabled = false

    override suspend fun execute(action: ActionId, packageName: String?): ActionResult {
        if (action != ActionId.TOGGLE_FLASHLIGHT || cameraId == null) {
            return ActionResult(false, "设备没有可用的手电筒")
        }
        return try {
            enabled = !enabled
            cameraManager.setTorchMode(cameraId, enabled)
            ActionResult(true, if (enabled) "已开启手电筒" else "已关闭手电筒")
        } catch (_: Exception) {
            enabled = false
            ActionResult(false, "手电筒操作失败，请关闭占用相机的应用后重试")
        }
    }
}
