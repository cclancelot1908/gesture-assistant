package com.orico.gestureassistant.smarthome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MiioProtocolTest {
    private val tokenHex = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun `token hex parses exactly sixteen bytes`() {
        assertArrayEquals((0..15).map(Int::toByte).toByteArray(), MiioProtocol.parseToken(tokenHex))
        assertThrows(IllegalArgumentException::class.java) { MiioProtocol.parseToken("1234") }
        assertThrows(IllegalArgumentException::class.java) { MiioProtocol.parseToken("zz0102030405060708090a0b0c0d0e") }
    }

    @Test
    fun `key and iv follow miio md5 derivation`() {
        val token = MiioProtocol.parseToken(tokenHex)
        val keys = MiioProtocol.deriveKeys(token)
        assertEquals("1ac1ef01e96caf1be0d329331a4fc2a8", keys.key.toHex())
        assertEquals("9ae5383e3c6e0606c15da7847fcd1d42", keys.iv.toHex())
    }

    @Test
    fun `hello and command packet use big endian header and valid checksum`() {
        val hello = MiioProtocol.helloPacket()
        assertEquals(32, hello.size)
        assertEquals("21310020" + "ffffffff".repeat(3) + "ff".repeat(16), hello.toHex())

        val token = MiioProtocol.parseToken(tokenHex)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = MiioProtocol.commandPacket(0x01020304, 0x05060708, token, payload)
        assertEquals(37, packet.size)
        assertEquals("21310025000000000102030405060708", packet.copyOfRange(0, 16).toHex())
        assertArrayEquals(payload, packet.copyOfRange(32, packet.size))
        assertArrayEquals(
            MiioProtocol.md5(packet.copyOfRange(0, 16) + token + payload),
            packet.copyOfRange(16, 32),
        )
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
