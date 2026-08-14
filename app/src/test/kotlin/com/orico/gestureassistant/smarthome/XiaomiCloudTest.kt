package com.orico.gestureassistant.smarthome

import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiCloudTest {
    @Test
    fun `rc4 drop 1024 encrypt decrypt round trip`() {
        val key = Base64.getEncoder().encodeToString("cloud-secret".toByteArray())
        val encrypted = XiaomiCloudCrypto.encryptRc4(key, "小米云 payload")

        assertFalse(encrypted.contains("payload"))
        assertEquals("小米云 payload", XiaomiCloudCrypto.decryptRc4(key, encrypted))
    }

    @Test
    fun `signed nonce hashes decoded security and nonce`() {
        val security = Base64.getEncoder().encodeToString("security".toByteArray())
        val nonce = Base64.getEncoder().encodeToString("nonce".toByteArray())

        assertEquals("tNfoaeifqEL840oNVDy1wWxcSezI23TbtXCTt8FH1Rs=", XiaomiCloudCrypto.signedNonce(security, nonce))
    }

    @Test
    fun `encrypted signature preserves parameter insertion order`() {
        val params = linkedMapOf("data" to "{\"fg\":true}", "other" to "value")

        assertEquals(
            "2CEAc6WThjotYwj5HivoyJcYg1g=",
            XiaomiCloudCrypto.generateEncSignature(
                "https://api.io.mi.com/app/v2/homeroom/gethome",
                "POST",
                "signed-nonce",
                params,
            ),
        )
    }

    @Test
    fun `generated agent follows mihome format`() {
        val agent = XiaomiCloudCrypto.generateAgent(Random(7))

        assertTrue(agent.matches(Regex("[a-z]{18}-[A-E]{13} APP/com\\.xiaomi\\.mihome APPV/10\\.5\\.201")))
    }

    @Test
    fun `xiaomi json prefix is removed`() {
        assertEquals(0, XiaomiCloudCrypto.toJson("&&&START&&&{\"code\":0}").getInt("code"))
    }
}
