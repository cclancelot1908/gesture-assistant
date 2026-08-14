package com.orico.gestureassistant.smarthome

import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SmartDevice(
    val id: String,
    val name: String,
    val ip: String,
    val token: String,
    val protocol: Protocol,
    val siid: Int? = null,
    val piid: Int? = null,
) {
    enum class Protocol(val wireName: String) {
        MIIO_POWER("miio_power"),
        MIOT("miot");

        companion object {
            fun fromWireName(value: String): Protocol = entries.firstOrNull { it.wireName == value.trim().lowercase() }
                ?: throw IllegalArgumentException("不支持的 proto：$value")
        }
    }
}

/** 设备导入与持久化共用同一套严格解析，防止无效配置进入控制链。 */
object SmartDeviceJson {
    fun parse(text: String): List<SmartDevice> {
        val array = try {
            JSONArray(text.trim())
        } catch (error: Exception) {
            throw IllegalArgumentException("设备 JSON 必须是数组：${error.message.orEmpty().take(80)}")
        }
        return List(array.length()) { index -> parseDevice(array.getJSONObject(index), index) }
            .also { devices -> require(devices.map { it.id }.distinct().size == devices.size) { "设备 id 不能重复" } }
    }

    fun encode(devices: List<SmartDevice>): String = JSONArray().also { array ->
        devices.forEach { device ->
            array.put(JSONObject().apply {
                put("id", device.id)
                put("name", device.name)
                put("ip", device.ip)
                put("token", device.token)
                put("proto", device.protocol.wireName)
                device.siid?.let { put("siid", it) }
                device.piid?.let { put("piid", it) }
            })
        }
    }.toString()

    private fun parseDevice(json: JSONObject, index: Int): SmartDevice {
        fun required(key: String): String = json.optString(key).trim()
            .takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("第 ${index + 1} 项缺少 $key")
        val name = required("name")
        val ip = required("ip")
        require(isPrivateIpv4(ip)) { "第 ${index + 1} 项 ip 必须是局域网 IPv4 地址" }
        val token = required("token").lowercase()
        MiioProtocol.parseToken(token)
        val protocol = SmartDevice.Protocol.fromWireName(required("proto"))
        val siid = json.optInt("siid", 0).takeIf { it > 0 }
        val piid = json.optInt("piid", 0).takeIf { it > 0 }
        if (protocol == SmartDevice.Protocol.MIOT) {
            require(siid != null && piid != null) { "第 ${index + 1} 项 MIoT 设备必须提供正整数 siid/piid" }
        }
        val id = json.optString("id").trim().ifBlank {
            UUID.nameUUIDFromBytes("$name|$ip".toByteArray(StandardCharsets.UTF_8)).toString()
        }
        return SmartDevice(id, name, ip, token, protocol, siid, piid)
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }
}
