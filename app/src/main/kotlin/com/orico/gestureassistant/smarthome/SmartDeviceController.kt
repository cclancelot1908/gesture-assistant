package com.orico.gestureassistant.smarthome

import org.json.JSONArray
import org.json.JSONObject

data class SmartDeviceResult(val successful: Boolean, val message: String)

class SmartDeviceController {
    suspend fun turnOn(device: SmartDevice) = setPower(device, true)
    suspend fun turnOff(device: SmartDevice) = setPower(device, false)

    suspend fun toggle(device: SmartDevice): SmartDeviceResult = safely(device, "切换") { client ->
        val current = readPower(device, client)
        setPowerResponse(device, client, !current)
        "已${if (current) "关闭" else "开启"}"
    }

    private suspend fun setPower(device: SmartDevice, on: Boolean): SmartDeviceResult =
        safely(device, if (on) "开启" else "关闭") { client ->
            setPowerResponse(device, client, on)
            if (on) "已开启" else "已关闭"
        }

    private suspend fun readPower(device: SmartDevice, client: MiioClient): Boolean {
        val response = when (device.protocol) {
            SmartDevice.Protocol.MIIO_POWER -> client.getProp("power")
            SmartDevice.Protocol.MIOT -> client.getProperties(device.siid!!, device.piid!!)
        }
        response.errorOrNull()?.let { throw IllegalStateException(it) }
        val result = response.optJSONArray("result") ?: throw IllegalStateException("设备回包缺少 result")
        return when (device.protocol) {
            SmartDevice.Protocol.MIIO_POWER -> when (result.optString(0).lowercase()) {
                "on", "true", "1" -> true
                "off", "false", "0" -> false
                else -> throw IllegalStateException("无法识别电源状态")
            }
            SmartDevice.Protocol.MIOT -> {
                val property = result.optJSONObject(0) ?: throw IllegalStateException("MIoT 状态回包格式错误")
                if (property.optInt("code", 0) != 0) throw IllegalStateException("MIoT 读取失败 code=${property.optInt("code")}")
                when (val value = property.opt("value")) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> throw IllegalStateException("MIoT 电源状态不是布尔值")
                }
            }
        }
    }

    private suspend fun setPowerResponse(device: SmartDevice, client: MiioClient, on: Boolean) {
        val response = when (device.protocol) {
            SmartDevice.Protocol.MIIO_POWER -> client.setPower(on)
            SmartDevice.Protocol.MIOT -> client.setProperties(device.siid!!, device.piid!!, on)
        }
        response.errorOrNull()?.let { throw IllegalStateException(it) }
        val result = response.optJSONArray("result") ?: throw IllegalStateException("设备回包缺少 result")
        if (device.protocol == SmartDevice.Protocol.MIOT) {
            val property = result.optJSONObject(0) ?: throw IllegalStateException("MIoT 写入回包格式错误")
            if (property.optInt("code", -1) != 0) throw IllegalStateException("MIoT 写入失败 code=${property.optInt("code")}")
        } else if (!legacySetSucceeded(result)) {
            throw IllegalStateException("设备未确认电源操作")
        }
    }

    private fun legacySetSucceeded(result: JSONArray): Boolean = result.length() > 0 &&
        (result.optString(0).equals("ok", true) || result.optBoolean(0, false))

    private suspend fun safely(
        device: SmartDevice,
        operation: String,
        block: suspend (MiioClient) -> String,
    ): SmartDeviceResult = try {
        val message = block(MiioClient(device.ip, device.token))
        SmartDeviceResult(true, "${device.name}：$message")
    } catch (error: Exception) {
        val detail = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }.take(120)
        SmartDeviceResult(false, "${device.name}${operation}失败：$detail")
    }

    private fun JSONObject.errorOrNull(): String? {
        if (!has("error")) return null
        val raw = opt("error")
        return when (raw) {
            is JSONObject -> raw.optString("message").ifBlank { raw.toString() }
            else -> raw?.toString().orEmpty().ifBlank { "未知 miIO 错误" }
        }
    }
}
