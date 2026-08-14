package com.orico.gestureassistant.action

import com.orico.gestureassistant.rules.ActionId
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 在 IO 线程发送 webhook；所有网络异常均转换为失败结果，不向手势线程抛出。 */
class WebhookActionExecutor : ActionExecutor {
    override suspend fun execute(action: ActionId, packageName: String?): ActionResult {
        if (action != ActionId.HTTP_WEBHOOK) return ActionResult(false, "不支持的 webhook 动作")
        return executeWebhook(url = packageName)
    }

    suspend fun executeWebhook(
        url: String?,
        method: String? = null,
        body: String? = null,
        contentType: String? = null,
    ): ActionResult = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(url)
        if (!isValidUrl(cleanUrl)) {
            return@withContext ActionResult(false, "未配置 webhook URL")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = URL(cleanUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = parseMethod(method)
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            if (connection.requestMethod == "POST" && body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType?.takeIf { it.isNotBlank() }
                    ?: "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val statusCode = connection.responseCode
            ActionResult(
                successful = isSuccessfulStatus(statusCode),
                message = if (isSuccessfulStatus(statusCode)) {
                    "Webhook 成功（HTTP $statusCode）"
                } else {
                    "Webhook 失败（HTTP $statusCode）"
                },
            )
        } catch (error: Exception) {
            val summary = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: error.javaClass.simpleName
            ActionResult(false, "Webhook 异常：${summary.take(MAX_ERROR_LENGTH)}")
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
                // disconnect 也不能影响手势执行链。
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 3_000
        private const val MAX_ERROR_LENGTH = 120

        /**
         * 容错修复常见 URL 输入问题：
         * - scheme 后缺 // ：`http:192.168.1.2` → `http://192.168.1.2`
         * - 完全没 scheme ：`192.168.1.2:8765/x` → `http://192.168.1.2:8765/x`
         */
        fun normalizeUrl(value: String?): String? {
            var v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            v = v.replace(Regex("^(https?):/*", RegexOption.IGNORE_CASE)) { m ->
                m.groupValues[1].lowercase() + "://"
            }
            if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) {
                v = "http://$v"
            }
            // 局域网设备(私有IP/localhost)几乎都是明文 HTTP，https 连不上；自动降级为 http，省得用户踩坑。
            if (v.startsWith("https://", true)) {
                val host = runCatching { URI(v).host }.getOrNull().orEmpty()
                val isLan = host == "localhost" ||
                    host.matches(Regex("^(192\\.168\\.|10\\.|172\\.(1[6-9]|2\\d|3[01])\\.).*"))
                if (isLan) v = "http://" + v.substring("https://".length)
            }
            return v
        }

        fun isValidUrl(value: String?): Boolean = try {
            if (value.isNullOrBlank()) false else {
                val uri = URI(value.trim())
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank()
            }
        } catch (_: Exception) {
            false
        }

        fun parseMethod(value: String?): String =
            if (value?.trim().equals("POST", ignoreCase = true)) "POST" else "GET"

        fun isSuccessfulStatus(statusCode: Int): Boolean = statusCode in 200..299
    }
}
