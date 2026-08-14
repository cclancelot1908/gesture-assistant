package com.orico.gestureassistant.smarthome

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 直接通过局域网 UDP 54321 调用小米设备；所有套接字工作都固定在 IO 线程。 */
class MiioClient(private val ip: String, tokenHex: String) {
    private val token = MiioProtocol.parseToken(tokenHex)
    private val keys = MiioProtocol.deriveKeys(token)

    suspend fun send(method: String, params: Any): JSONObject = withContext(Dispatchers.IO) {
        try {
            require(method.isNotBlank()) { "method 不能为空" }
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.connect(InetSocketAddress(InetAddress.getByName(ip), PORT))
                val hello = MiioProtocol.helloPacket()
                socket.send(DatagramPacket(hello, hello.size))
                val helloReply = receive(socket)
                val handshake = MiioProtocol.parseHandshake(helloReply)
                val receivedAtNs = System.nanoTime()

                val request = JSONObject()
                    .put("id", nextRequestId())
                    .put("method", method)
                    .put("params", jsonValue(params))
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val encrypted = MiioProtocol.encrypt(request, keys)
                val elapsedSeconds = ((System.nanoTime() - receivedAtNs) / 1_000_000_000L).coerceAtLeast(0L)
                val packet = MiioProtocol.commandPacket(
                    handshake.deviceId,
                    (handshake.stamp + elapsedSeconds) and 0xffffffffL,
                    token,
                    encrypted,
                )
                socket.send(DatagramPacket(packet, packet.size))
                val reply = receive(socket)
                val json = MiioProtocol.decrypt(MiioProtocol.encryptedPayload(reply, token), keys)
                    .toString(Charsets.UTF_8)
                    .trimEnd('\u0000')
                JSONObject(json)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            JSONObject().put("error", safeError(error))
        }
    }

    suspend fun getProp(name: String): JSONObject = send("get_prop", listOf(name))
    suspend fun setPower(on: Boolean): JSONObject = send("set_power", listOf(if (on) "on" else "off"))
    suspend fun getProperties(siid: Int, piid: Int): JSONObject = send(
        "get_properties",
        listOf(mapOf("siid" to siid, "piid" to piid)),
    )
    suspend fun setProperties(siid: Int, piid: Int, value: Boolean): JSONObject = send(
        "set_properties",
        listOf(mapOf("siid" to siid, "piid" to piid, "value" to value)),
    )

    private fun receive(socket: DatagramSocket): ByteArray {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        return packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
    }

    private fun safeError(error: Exception): String {
        val detail = when (error) {
            is SocketTimeoutException -> "设备响应超时"
            else -> error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
        }
        return detail.take(MAX_ERROR_LENGTH)
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Map<*, *> -> JSONObject().also { result ->
            value.forEach { (key, item) -> result.put(key.toString(), jsonValue(item)) }
        }
        is Iterable<*> -> JSONArray().also { result -> value.forEach { result.put(jsonValue(it)) } }
        is Array<*> -> JSONArray().also { result -> value.forEach { result.put(jsonValue(it)) } }
        else -> value.toString()
    }

    private fun nextRequestId(): Int = REQUEST_ID.updateAndGet { current ->
        if (current >= Int.MAX_VALUE - 1) 1 else current + 1
    }

    companion object {
        private const val PORT = 54321
        private const val TIMEOUT_MS = 2_500
        private const val MAX_PACKET_SIZE = 65_535
        private const val MAX_ERROR_LENGTH = 160
        private val REQUEST_ID = AtomicInteger((System.currentTimeMillis() % 10_000).toInt())
    }
}
