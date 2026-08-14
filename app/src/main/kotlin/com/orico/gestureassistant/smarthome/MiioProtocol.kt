package com.orico.gestureassistant.smarthome

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** miIO 二进制协议的纯函数部分，独立于 Android，便于用固定向量验证。 */
object MiioProtocol {
    const val HEADER_SIZE = 32
    private const val MAGIC = 0x2131

    data class Keys(val key: ByteArray, val iv: ByteArray)
    data class Handshake(val deviceId: Int, val stamp: Long)

    fun parseToken(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length == 32 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "token 必须是 32 位十六进制字符串"
        }
        return ByteArray(16) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun deriveKeys(token: ByteArray): Keys {
        require(token.size == 16) { "token 必须是 16 字节" }
        val key = md5(token)
        return Keys(key, md5(key + token))
    }

    fun helloPacket(): ByteArray = ByteArray(HEADER_SIZE) { 0xff.toByte() }.also { packet ->
        packet[0] = 0x21
        packet[1] = 0x31
        packet[2] = 0x00
        packet[3] = 0x20
    }

    fun parseHandshake(packet: ByteArray): Handshake {
        require(packet.size >= HEADER_SIZE) { "miIO 握手回包不足 32 字节" }
        require(readUnsignedShort(packet, 0) == MAGIC) { "miIO 握手 magic 错误" }
        require(readUnsignedShort(packet, 2) == HEADER_SIZE) { "miIO 握手长度错误" }
        return Handshake(readInt(packet, 8), readInt(packet, 12).toLong() and 0xffffffffL)
    }

    fun commandPacket(deviceId: Int, stamp: Long, token: ByteArray, encryptedPayload: ByteArray): ByteArray {
        require(token.size == 16) { "token 必须是 16 字节" }
        val totalLength = HEADER_SIZE + encryptedPayload.size
        require(totalLength <= 0xffff) { "miIO payload 过长" }
        val packet = ByteArray(totalLength)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        header.putShort(MAGIC.toShort())
        header.putShort(totalLength.toShort())
        header.putInt(0)
        header.putInt(deviceId)
        header.putInt(stamp.toInt())
        encryptedPayload.copyInto(packet, HEADER_SIZE)
        md5(packet.copyOfRange(0, 16) + token + encryptedPayload).copyInto(packet, 16)
        return packet
    }

    fun encryptedPayload(packet: ByteArray, token: ByteArray): ByteArray {
        require(packet.size >= HEADER_SIZE) { "miIO 回包不足 32 字节" }
        require(readUnsignedShort(packet, 0) == MAGIC) { "miIO 回包 magic 错误" }
        val declaredLength = readUnsignedShort(packet, 2)
        require(declaredLength in HEADER_SIZE..packet.size) { "miIO 回包长度错误" }
        val encrypted = packet.copyOfRange(HEADER_SIZE, declaredLength)
        val expected = md5(packet.copyOfRange(0, 16) + token + encrypted)
        require(MessageDigest.isEqual(expected, packet.copyOfRange(16, 32))) { "miIO 回包校验失败" }
        return encrypted
    }

    fun encrypt(plain: ByteArray, keys: Keys): ByteArray = cipher(Cipher.ENCRYPT_MODE, keys).doFinal(plain)
    fun decrypt(encrypted: ByteArray, keys: Keys): ByteArray = cipher(Cipher.DECRYPT_MODE, keys).doFinal(encrypted)
    fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun cipher(mode: Int, keys: Keys): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(keys.key, "AES"), IvParameterSpec(keys.iv))
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff

    private fun readInt(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
}
