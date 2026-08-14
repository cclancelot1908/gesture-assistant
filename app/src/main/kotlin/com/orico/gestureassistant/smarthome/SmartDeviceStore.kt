package com.orico.gestureassistant.smarthome

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.smartDeviceDataStore: DataStore<Preferences> by preferencesDataStore("smart_devices")

class SmartDeviceStore(private val context: Context) {
    val devices: Flow<List<SmartDevice>> = context.smartDeviceDataStore.data.map { preferences ->
        preferences[DEVICES_JSON]?.let { stored -> runCatching { SmartDeviceJson.parse(stored) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    /** 导入采用按 id 覆盖、其余保留，方便分批粘贴设备。 */
    suspend fun importJson(text: String): List<SmartDevice> {
        val imported = SmartDeviceJson.parse(text)
        context.smartDeviceDataStore.edit { preferences ->
            val current = preferences[DEVICES_JSON]?.let { runCatching { SmartDeviceJson.parse(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val merged = (current + imported).associateBy { it.id }.values.toList()
            preferences[DEVICES_JSON] = SmartDeviceJson.encode(merged)
        }
        return imported
    }

    suspend fun add(device: SmartDevice) {
        context.smartDeviceDataStore.edit { preferences ->
            val current = preferences[DEVICES_JSON]?.let { runCatching { SmartDeviceJson.parse(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            preferences[DEVICES_JSON] = SmartDeviceJson.encode((current + device).associateBy { it.id }.values.toList())
        }
    }

    suspend fun remove(id: String) {
        context.smartDeviceDataStore.edit { preferences ->
            val current = preferences[DEVICES_JSON]?.let { runCatching { SmartDeviceJson.parse(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            preferences[DEVICES_JSON] = SmartDeviceJson.encode(current.filterNot { it.id == id })
        }
    }

    private companion object {
        val DEVICES_JSON = stringPreferencesKey("devices_json")
    }
}
