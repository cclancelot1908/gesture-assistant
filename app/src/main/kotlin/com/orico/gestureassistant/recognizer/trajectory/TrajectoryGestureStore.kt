package com.orico.gestureassistant.recognizer.trajectory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orico.gestureassistant.rules.ActionId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.trajectoryDataStore: DataStore<Preferences> by preferencesDataStore("trajectory_gestures")

data class TrajectoryGesture(
    val id: String,
    val name: String,
    val templates: List<List<ImuPoint>>,
    val action: ActionId,
    val webhookUrl: String? = null,
    val appPackage: String? = null,
    val smartDeviceId: String? = null,
    // 智能家居动作：on / off / toggle。
    val smartOp: String? = null,
    val enabled: Boolean = true,
)

/** 用户轨迹库：单一 JSON 快照保证一次编辑原子落盘，全程本地存储。 */
class TrajectoryGestureStore(private val context: Context) {
    val gestures: Flow<List<TrajectoryGesture>> = context.trajectoryDataStore.data.map { preferences ->
        decode(preferences[GESTURES_JSON])
    }

    suspend fun addTemplate(name: String, points: List<ImuPoint>) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "手势名称不能为空" }
        require(points.size >= TrajectoryRecognizer.MIN_SAMPLE_COUNT) { "轨迹采样点太少" }
        context.trajectoryDataStore.edit { preferences ->
            val current = decode(preferences[GESTURES_JSON]).toMutableList()
            val existingIndex = current.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
            if (existingIndex >= 0) {
                val existing = current[existingIndex]
                current[existingIndex] = existing.copy(templates = existing.templates + listOf(points))
            } else {
                current += TrajectoryGesture(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    templates = listOf(points),
                    action = ActionId.TOGGLE_FLASHLIGHT,
                )
            }
            preferences[GESTURES_JSON] = encode(current)
        }
    }

    suspend fun setAction(id: String, action: ActionId) = update { gestures ->
        gestures.map { if (it.id == id) it.copy(action = action) else it }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = update { gestures ->
        setEnabledIn(gestures, id, enabled)
    }

    suspend fun setWebhookUrl(id: String, webhookUrl: String?) = update { gestures ->
        val cleanUrl = webhookUrl?.trim()?.takeIf { it.isNotEmpty() }
        gestures.map { if (it.id == id) it.copy(webhookUrl = cleanUrl) else it }
    }

    suspend fun setAppPackage(id: String, packageName: String?) = update { gestures ->
        val cleanPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
        gestures.map { if (it.id == id) it.copy(appPackage = cleanPackage) else it }
    }

    /** 绑定智能家居设备与动作（on/off/toggle），并把动作切到 SMART_DEVICE。 */
    suspend fun setSmartDevice(id: String, deviceId: String?, op: String?) = update { gestures ->
        val cleanDevice = deviceId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanOp = op?.trim()?.lowercase()?.takeIf { it in setOf("on", "off", "toggle") }
        gestures.map {
            if (it.id == id) it.copy(action = ActionId.SMART_DEVICE, smartDeviceId = cleanDevice, smartOp = cleanOp) else it
        }
    }

    suspend fun delete(id: String) = update { gestures -> gestures.filterNot { it.id == id } }

    /** 在同一次 DataStore edit 中删除指定模板；无效手势或索引保持原快照不变。 */
    suspend fun deleteTemplate(gestureId: String, templateIndex: Int) = update { gestures ->
        deleteTemplateFrom(gestures, gestureId, templateIndex)
    }

    private suspend fun update(transform: (List<TrajectoryGesture>) -> List<TrajectoryGesture>) {
        context.trajectoryDataStore.edit { preferences ->
            preferences[GESTURES_JSON] = encode(transform(decode(preferences[GESTURES_JSON])))
        }
    }

    private fun decode(json: String?): List<TrajectoryGesture> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONArray(json)
            buildList {
                for (gestureIndex in 0 until root.length()) {
                    val item = root.getJSONObject(gestureIndex)
                    val templatesJson = item.getJSONArray("templates")
                    val templates = buildList {
                        for (templateIndex in 0 until templatesJson.length()) {
                            val pointsJson = templatesJson.getJSONArray(templateIndex)
                            add(buildList {
                                for (pointIndex in 0 until pointsJson.length()) {
                                    // 历史模板仍是六元设备轴数组：保持可解析；缺少兼容维时补零，坏点单独跳过。
                                    runCatching {
                                        val values = pointsJson.getJSONArray(pointIndex)
                                        if (values.length() >= WORLD_DIMENSIONS) {
                                            add(ImuPoint.from(DoubleArray(ImuPoint.DIMENSIONS) { axis ->
                                                if (axis < values.length()) values.optDouble(axis, 0.0) else 0.0
                                            }))
                                        }
                                    }
                                }
                            })
                        }
                    }
                    val storedAction = item.getString("action")
                    val action = runCatching {
                        if (storedAction == "OPEN_APP") ActionId.LAUNCH_APP else ActionId.valueOf(storedAction)
                    }
                        .getOrDefault(ActionId.TOGGLE_FLASHLIGHT)
                    val webhookUrl = item.optString("webhookUrl").trim().takeIf { it.isNotEmpty() }
                    val appPackage = item.optString("appPackage").trim().takeIf { it.isNotEmpty() }
                    val smartDeviceId = item.optString("smartDeviceId").trim().takeIf { it.isNotEmpty() }
                    val smartOp = item.optString("smartOp").trim().takeIf { it.isNotEmpty() }
                    val enabled = item.optBoolean("enabled", true)
                    add(TrajectoryGesture(item.getString("id"), item.getString("name"), templates, action, webhookUrl, appPackage, smartDeviceId, smartOp, enabled))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(gestures: List<TrajectoryGesture>): String = JSONArray().apply {
        gestures.forEach { gesture ->
            put(JSONObject().apply {
                put("id", gesture.id)
                put("name", gesture.name)
                put("action", gesture.action.name)
                put("enabled", gesture.enabled)
                gesture.webhookUrl?.let { put("webhookUrl", it) }
                gesture.appPackage?.let { put("appPackage", it) }
                gesture.smartDeviceId?.let { put("smartDeviceId", it) }
                gesture.smartOp?.let { put("smartOp", it) }
                put("templates", JSONArray().apply {
                    gesture.templates.forEach { template ->
                        put(JSONArray().apply {
                            template.forEach { point -> put(JSONArray(point.values())) }
                        })
                    }
                })
            })
        }
    }.toString()

    companion object {
        const val WORLD_DIMENSIONS = 3
        private val GESTURES_JSON = stringPreferencesKey("gesture_library_json")

        /** 独立的不可变变换便于单元测试，也避免越界删除抛异常。 */
        internal fun deleteTemplateFrom(
            gestures: List<TrajectoryGesture>,
            gestureId: String,
            templateIndex: Int,
        ): List<TrajectoryGesture> {
            if (templateIndex < 0) return gestures
            val gestureIndex = gestures.indexOfFirst { it.id == gestureId }
            if (gestureIndex < 0) return gestures
            val gesture = gestures[gestureIndex]
            if (templateIndex !in gesture.templates.indices) return gestures

            val remaining = gesture.templates.filterIndexed { index, _ -> index != templateIndex }
            return if (remaining.isEmpty()) {
                gestures.filterIndexed { index, _ -> index != gestureIndex }
            } else {
                gestures.mapIndexed { index, item ->
                    if (index == gestureIndex) item.copy(templates = remaining) else item
                }
            }
        }

        /** 独立变换便于验证单个手势开关不会影响其他手势。 */
        internal fun setEnabledIn(
            gestures: List<TrajectoryGesture>,
            gestureId: String,
            enabled: Boolean,
        ): List<TrajectoryGesture> = gestures.map { gesture ->
            if (gesture.id == gestureId) gesture.copy(enabled = enabled) else gesture
        }
    }
}
