package com.orico.gestureassistant.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookActionExecutorTest {
    @Test
    fun `only http and https URLs with a host are valid`() {
        assertTrue(WebhookActionExecutor.isValidUrl("http://192.168.1.10:8080/light/on"))
        assertTrue(WebhookActionExecutor.isValidUrl("https://example.com/hook"))
        assertFalse(WebhookActionExecutor.isValidUrl("ftp://example.com/hook"))
        assertFalse(WebhookActionExecutor.isValidUrl("https:///missing-host"))
        assertFalse(WebhookActionExecutor.isValidUrl("not a url"))
        assertFalse(WebhookActionExecutor.isValidUrl(null))
    }

    @Test
    fun `method parsing defaults to GET and accepts POST case insensitively`() {
        assertEquals("GET", WebhookActionExecutor.parseMethod(null))
        assertEquals("GET", WebhookActionExecutor.parseMethod(""))
        assertEquals("GET", WebhookActionExecutor.parseMethod("get"))
        assertEquals("POST", WebhookActionExecutor.parseMethod(" post "))
        assertEquals("GET", WebhookActionExecutor.parseMethod("PUT"))
    }

    @Test
    fun `only 2xx status codes are successful`() {
        assertFalse(WebhookActionExecutor.isSuccessfulStatus(199))
        assertTrue(WebhookActionExecutor.isSuccessfulStatus(200))
        assertTrue(WebhookActionExecutor.isSuccessfulStatus(204))
        assertTrue(WebhookActionExecutor.isSuccessfulStatus(299))
        assertFalse(WebhookActionExecutor.isSuccessfulStatus(300))
        assertFalse(WebhookActionExecutor.isSuccessfulStatus(500))
    }
}
