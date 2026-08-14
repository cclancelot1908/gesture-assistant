package com.orico.gestureassistant.smarthome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmartDeviceJsonTest {
    @Test
    fun `device json parser accepts legacy and miot devices`() {
        val devices = SmartDeviceJson.parse(
            """[
              {"name":"台灯","ip":"192.168.1.20","token":"000102030405060708090a0b0c0d0e0f","proto":"miio_power"},
              {"id":"road","name":"路灯","ip":"192.168.1.21","token":"101112131415161718191a1b1c1d1e1f","proto":"miot","siid":2,"piid":1}
            ]""",
        )
        assertEquals(2, devices.size)
        assertEquals("台灯", devices[0].name)
        assertEquals(SmartDevice.Protocol.MIIO_POWER, devices[0].protocol)
        assertEquals("road", devices[1].id)
        assertEquals(2, devices[1].siid)
        assertEquals(1, devices[1].piid)
    }

    @Test
    fun `device json parser rejects unsafe or incomplete entries`() {
        assertThrows(IllegalArgumentException::class.java) { SmartDeviceJson.parse("{}") }
        assertThrows(IllegalArgumentException::class.java) {
            SmartDeviceJson.parse("""[{"name":"坏设备","ip":"8.8.8.8","token":"000102030405060708090a0b0c0d0e0f","proto":"miio_power"}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartDeviceJson.parse("""[{"name":"缺属性","ip":"192.168.1.2","token":"000102030405060708090a0b0c0d0e0f","proto":"miot"}]""")
        }
    }

    @Test
    fun `device json round trip preserves fields`() {
        val source = listOf(
            SmartDevice("a", "空调", "192.168.50.8", "000102030405060708090a0b0c0d0e0f", SmartDevice.Protocol.MIOT, 2, 1),
        )
        assertEquals(source, SmartDeviceJson.parse(SmartDeviceJson.encode(source)))
    }
}
