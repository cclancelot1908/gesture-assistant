package com.orico.gestureassistant.smarthome

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.xiaomiSessionDataStore: DataStore<Preferences> by preferencesDataStore("xiaomi_session")

/**
 * 「记住此设备」的本机会话存储：仅存本机 DataStore，供下次免验证/免登录复用；可随时清除。
 * 存的是登录令牌(等价凭据)，因此只保存在应用私有存储，不外传、不打日志。
 */
class XiaomiSessionStore(private val context: Context) {

    suspend fun load(): XiaomiSession? = runCatching {
        val raw = context.xiaomiSessionDataStore.data.first()[SESSION_JSON] ?: return null
        val json = JSONObject(raw)
        XiaomiSession(
            deviceId = json.optString("deviceId"),
            username = json.optString("username"),
            server = json.optString("server"),
            userId = json.optString("userId"),
            ssecurity = json.optString("ssecurity"),
            serviceToken = json.optString("serviceToken"),
            passToken = json.optString("passToken"),
        ).takeIf { it.deviceId.isNotBlank() }
    }.getOrNull()

    suspend fun save(session: XiaomiSession) {
        val json = JSONObject()
            .put("deviceId", session.deviceId)
            .put("username", session.username)
            .put("server", session.server)
            .put("userId", session.userId)
            .put("ssecurity", session.ssecurity)
            .put("serviceToken", session.serviceToken)
            .put("passToken", session.passToken)
            .toString()
        context.xiaomiSessionDataStore.edit { it[SESSION_JSON] = json }
    }

    suspend fun clear() {
        context.xiaomiSessionDataStore.edit { it.remove(SESSION_JSON) }
    }

    private companion object {
        val SESSION_JSON = stringPreferencesKey("session_json")
    }
}
