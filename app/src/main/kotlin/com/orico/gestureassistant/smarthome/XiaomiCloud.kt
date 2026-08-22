package com.orico.gestureassistant.smarthome

import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CloudDevice(val name: String, val ip: String?, val token: String?, val model: String)

/**
 * 可本地持久化的登录会话，用于「记住此设备」：复用同一 deviceId 让小米信任本机（配合 trust=true 跳过下次邮箱验证），
 * 并缓存令牌以便下次免登录直接拉设备。全部仅存本机 DataStore，可随时清除。
 */
data class XiaomiSession(
    val deviceId: String,
    val username: String,
    val server: String,
    val userId: String,
    val ssecurity: String,
    val serviceToken: String,
    val passToken: String,
)

sealed class CloudResult {
    data class Ok(val devices: List<CloudDevice>, val session: XiaomiSession) : CloudResult()
    data class Err(val message: String) : CloudResult()
}

interface CloudPrompts {
    /** refresh：重新拉取一张新验证码图（同一登录会话内可反复调用），返回新图字节；失败返回 null。 */
    suspend fun onCaptcha(imageBytes: ByteArray, refresh: suspend () -> ByteArray?): String?
    /** title：对话框标题（区分短信/邮箱）；info：发送结果提示（如"短信验证码已发送：1**"），便于确认是否真的发出。 */
    suspend fun on2faCode(title: String, info: String): String?
}

/** 小米云协议中可脱离 Android 运行的加密与签名函数。 */
object XiaomiCloudCrypto {
    fun encryptRc4(passwordB64: String, payload: String): String = Base64.getEncoder().encodeToString(
        rc4Drop1024(Base64.getDecoder().decode(passwordB64), payload.toByteArray(Charsets.UTF_8)),
    )

    fun decryptRc4(passwordB64: String, payloadB64: String): String = rc4Drop1024(
        Base64.getDecoder().decode(passwordB64),
        Base64.getDecoder().decode(payloadB64.trim()),
    ).toString(Charsets.UTF_8)

    fun signedNonce(ssecurity: String, nonceB64: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(
            Base64.getDecoder().decode(ssecurity) + Base64.getDecoder().decode(nonceB64),
        ),
    )

    fun generateNonce(millis: Long, random: Random = Random(System.currentTimeMillis())): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes, 0, 8)
        ByteBuffer.wrap(bytes, 8, 4).putInt((millis / 60_000L).toInt())
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun generateEncSignature(
        url: String,
        method: String,
        signedNonce: String,
        params: Map<String, String>,
    ): String {
        val path = url.substringAfter("com").replace("/app/", "/")
        val parts = mutableListOf(method.uppercase(Locale.US), path)
        params.forEach { (key, value) -> parts += "$key=$value" }
        parts += signedNonce
        return Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(parts.joinToString("&").toByteArray(Charsets.UTF_8)),
        )
    }

    fun generateEncParams(
        url: String,
        method: String,
        signedNonce: String,
        nonce: String,
        source: LinkedHashMap<String, String>,
        ssecurity: String,
    ): LinkedHashMap<String, String> {
        val params = LinkedHashMap(source)
        params["rc4_hash__"] = generateEncSignature(url, method, signedNonce, params)
        params.keys.toList().forEach { key -> params[key] = encryptRc4(signedNonce, params.getValue(key)) }
        params["signature"] = generateEncSignature(url, method, signedNonce, params)
        params["ssecurity"] = ssecurity
        params["_nonce"] = nonce
        return params
    }

    fun generateAgent(random: Random = Random(System.currentTimeMillis())): String {
        val agentId = buildString { repeat(13) { append(('A'.code + random.nextInt(5)).toChar()) } }
        val randomText = buildString { repeat(18) { append(('a'.code + random.nextInt(26)).toChar()) } }
        return "$randomText-$agentId APP/com.xiaomi.mihome APPV/10.5.201"
    }

    fun toJson(text: String): JSONObject = JSONObject(text.removePrefix("&&&START&&&"))

    private fun rc4Drop1024(key: ByteArray, payload: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key 不能为空" }
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val swap = state[i]; state[i] = state[j]; state[j] = swap
        }
        var i = 0
        j = 0
        fun next(): Int {
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val swap = state[i]; state[i] = state[j]; state[j] = swap
            return state[(state[i] + state[j]) and 0xff]
        }
        repeat(1024) { next() }
        return ByteArray(payload.size) { index -> (payload[index].toInt() xor next()).toByte() }
    }
}

/**
 * 直接在手机端完成小米账号登录、加密 API 调用和设备清单拉取。
 * 传入 saved 会话可「记住此设备」：复用同一 deviceId 并优先用缓存令牌免登录。
 */
class XiaomiCloud(private val saved: XiaomiSession? = null) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val agent = XiaomiCloudCrypto.generateAgent()
    // 复用已记住设备的 deviceId，让小米把本机当“同一台”，配合 trust=true 跳过下次邮箱验证。
    private val deviceId = saved?.deviceId?.takeIf { it.isNotBlank() } ?: buildString {
        val random = Random(System.currentTimeMillis())
        repeat(6) { append(('a'.code + random.nextInt(26)).toChar()) }
    }
    private var ssecurity = saved?.ssecurity.orEmpty()
    private var userId = saved?.userId.orEmpty()
    private var cUserId = ""
    private var passToken = saved?.passToken.orEmpty()
    private var location = ""
    private var serviceToken = saved?.serviceToken.orEmpty()
    private var sign = ""
    private var trustDevice = true
    // 记录最近一次加密 API 失败原因，便于把“拉取家庭失败”变成可定位的用户提示。
    private var lastApiError = ""

    suspend fun importDevices(
        username: String,
        password: String,
        server: String,
        prompts: CloudPrompts,
        rememberDevice: Boolean = true,
        onSession: (suspend (XiaomiSession) -> Unit)? = null,
    ): CloudResult = withContext(Dispatchers.IO) {
        try {
            require(username.isNotBlank()) { "请输入小米账号" }
            require(server in SERVERS) { "不支持的服务器：$server" }
            trustDevice = rememberDevice
            installCookieManager()
            seedCookies()
            // 免登录快路径：已有缓存令牌就直接拉设备，成功即返回；失败(令牌过期)再走完整登录。
            if (ssecurity.isNotBlank() && serviceToken.isNotBlank() && userId.isNotBlank()) {
                val cached = runCatching { fetchAllDevices(server) }.getOrNull()
                if (!cached.isNullOrEmpty()) {
                    return@withContext CloudResult.Ok(cached, currentSession(username.trim(), server))
                }
                ssecurity = ""; serviceToken = "" // 缓存失效，清掉后重新登录。
            }
            require(password.isNotEmpty()) { "请输入密码" }
            login(username.trim(), password, prompts)?.let { return@withContext CloudResult.Err(it) }
            // 登录一成功就立刻把会话交出去持久化：后续拉设备即便失败，重试也走缓存快路径，绝不再发短信。
            runCatching { onSession?.invoke(currentSession(username.trim(), server)) }
            val devices = fetchAllDevices(server)
            CloudResult.Ok(devices, currentSession(username.trim(), server))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CloudResult.Err(error.message?.trim().orEmpty().ifBlank { "小米云请求失败" }.take(180))
        }
    }

    private fun currentSession(username: String, server: String) = XiaomiSession(
        deviceId = deviceId,
        username = username,
        server = server,
        userId = userId,
        ssecurity = ssecurity,
        serviceToken = serviceToken,
        passToken = passToken,
    )

    private fun installCookieManager() = synchronized(COOKIE_LOCK) { CookieHandler.setDefault(cookies) }

    private fun seedCookies() {
        listOf("mi.com", "xiaomi.com").forEach { domain ->
            val uri = URI("https://$domain")
            cookies.cookieStore.add(uri, accountCookie("sdkVersion", "accountsdk-18.8.15", domain))
            cookies.cookieStore.add(uri, accountCookie("deviceId", deviceId, domain))
        }
    }

    private fun accountCookie(name: String, value: String, domain: String): HttpCookie = HttpCookie(name, value).apply {
        this.domain = ".$domain"
        path = "/"
        secure = true
    }

    private suspend fun login(username: String, password: String, prompts: CloudPrompts): String? {
        if (!loginStep1(username)) return "小米账号登录初始化失败"
        if (ssecurity.isBlank()) {
            loginStep2(username, password, prompts)?.let { return it }
        }
        if (serviceToken.isBlank() && !loginStep3()) return "未能取得小米云 serviceToken"
        return null
    }

    private fun loginStep1(username: String): Boolean {
        cookies.cookieStore.add(URI("https://xiaomi.com"), accountCookie("userId", username, "xiaomi.com"))
        val response = request("GET", "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true")
        if (response.code != 200) return false
        val json = XiaomiCloudCrypto.toJson(response.text)
        sign = json.optString("_sign")
        rememberLogin(json)
        return sign.isNotBlank() || ssecurity.isNotBlank()
    }

    private suspend fun loginStep2(username: String, password: String, prompts: CloudPrompts): String? {
        val fields = linkedMapOf(
            "sid" to "xiaomiio",
            "hash" to md5(password).uppercase(Locale.US),
            "callback" to "https://sts.api.io.mi.com/sts",
            "qs" to "%3Fsid%3Dxiaomiio%26_json%3Dtrue",
            "user" to username,
            "_sign" to sign,
            "_json" to "true",
        )
        var json = loginAuth(fields)
        val captchaUrl = json.optString("captchaUrl")
        if (captchaUrl.isNotBlank() && captchaUrl != "null") {
            val url = if (captchaUrl.startsWith('/')) "https://account.xiaomi.com$captchaUrl" else captchaUrl
            val image = request("GET", url).bytes
            // 刷新：追加时间戳强制取新图；同一登录会话内可反复刷新。
            val captcha = prompts.onCaptcha(image) {
                val sep = if (url.contains('?')) "&" else "?"
                runCatching { request("GET", "$url${sep}_r=${System.currentTimeMillis()}").bytes }.getOrNull()
            }?.trim().orEmpty()
            if (captcha.isBlank()) return "已取消图片验证码"
            fields["captCode"] = captcha
            json = loginAuth(fields)
            if (json.optInt("code") == 87001) return "图片验证码错误"
        }
        if (json.optString("ssecurity").length > 4) {
            rememberLogin(json)
            return null
        }
        val notificationUrl = json.optString("notificationUrl")
        if (notificationUrl.isNotBlank()) return do2faFlow(notificationUrl, prompts)
        return json.optString("desc").ifBlank { "小米账号或密码错误，登录失败" }
    }

    private fun loginAuth(fields: LinkedHashMap<String, String>): JSONObject {
        val response = request(
            "POST",
            "https://account.xiaomi.com/pass/serviceLoginAuth2?${formEncode(fields)}",
            followRedirects = false,
        )
        return XiaomiCloudCrypto.toJson(response.text)
    }

    private fun loginStep3(): Boolean {
        if (location.isBlank()) return false
        request("GET", location, followRedirects = true)
        serviceToken = findCookie("serviceToken")
        return serviceToken.isNotBlank()
    }

    private suspend fun do2faFlow(notificationUrl: String, prompts: CloudPrompts): String? {
        request("GET", notificationUrl)
        val context = queryValue(notificationUrl, "context")
        if (context.isBlank()) return "两步验证链接缺少 context"
        val common = "sid=xiaomiio&context=${encode(context)}&_locale=en_US"
        val list = request("GET", "https://account.xiaomi.com/identity/list?$common")
        val ick = findCookie("ick")
        // 账号可用的二次验证方式(options)：4=手机短信，8=邮箱。按可用方式自动选接口，不再写死邮箱。
        val listJson = runCatching { XiaomiCloudCrypto.toJson(list.text) }.getOrNull()
        val options = listJson?.optJSONArray("options")
            ?.let { arr -> (0 until arr.length()).map { arr.optInt(it) } }
            ?: listOfNotNull(listJson?.optInt("flag", -1)?.takeIf { it > 0 })
        val useEmail = options.contains(8)
        val usePhone = !useEmail && options.contains(4)
        if (!useEmail && !usePhone) {
            return "该账号的二次验证方式暂不支持（可用代码：${options.joinToString(",")}；本 App 支持 4=短信、8=邮箱）"
        }
        val methodLabel = if (useEmail) "邮箱" else "短信"
        val sendPath = if (useEmail) "sendEmailTicket" else "sendPhoneTicket"
        val verifyPath = if (useEmail) "verifyEmail" else "verifyPhone"
        val flag = if (useEmail) "8" else "4"
        val send = request(
            "POST",
            "https://account.xiaomi.com/identity/auth/$sendPath?_dc=${System.currentTimeMillis()}&sid=xiaomiio&context=${encode(context)}&mask=0&_locale=en_US",
            body = formEncode(linkedMapOf("retry" to "0", "icode" to "", "_json" to "true", "ick" to ick)),
        )
        // 关键：检查发送结果。发完不看结果直接等码，服务器若报错/限流就成了“永远收不到”。
        val sendJson = runCatching { XiaomiCloudCrypto.toJson(send.text) }.getOrNull()
        val sendCode = sendJson?.optInt("code", -1) ?: -1
        if (send.code != 200 || (sendCode != -1 && sendCode != 0)) {
            val desc = listOf("description", "desc", "message")
                .firstNotNullOfOrNull { sendJson?.optString(it)?.takeIf { s -> s.isNotBlank() } }
                ?: send.text.take(140)
            return "${methodLabel}验证码发送失败（HTTP ${send.code}${if (sendCode != -1) " / code $sendCode" else ""}）：$desc（账号可用验证方式代码：${options.joinToString(",")}）"
        }
        val masked = listOf("maskedPhone", "maskedEmail", "maskEmail", "address", "phone", "email", "description")
            .firstNotNullOfOrNull { sendJson?.optString(it)?.takeIf { s -> s.isNotBlank() } }.orEmpty()
        val info = if (masked.isNotBlank()) "${methodLabel}验证码已发送：$masked"
        else "${methodLabel}验证码已发送，请查收" + if (useEmail) "（含垃圾箱）" else ""
        val code = prompts.on2faCode("请输入${methodLabel}验证码", info)?.trim().orEmpty()
        if (code.isBlank()) return "已取消${methodLabel}验证码"
        val verify = request(
            "POST",
            "https://account.xiaomi.com/identity/auth/$verifyPath?_flag=$flag&_json=true&sid=xiaomiio&context=${encode(context)}&mask=0&_locale=en_US",
            body = formEncode(linkedMapOf("_flag" to flag, "ticket" to code, "trust" to if (trustDevice) "true" else "false", "_json" to "true", "ick" to ick)),
            followRedirects = false,
        )
        var finishLocation = runCatching { XiaomiCloudCrypto.toJson(verify.text).optString("location") }.getOrDefault("")
        if (finishLocation.isBlank()) finishLocation = verify.location.orEmpty()
        if (finishLocation.isBlank()) {
            finishLocation = Regex("https://account\\.xiaomi\\.com/identity/result/check\\?[^\\\"']+")
                .find(verify.text)?.value.orEmpty().replace("&amp;", "&")
        }
        if (finishLocation.isBlank()) {
            val fallback = request("GET", "https://account.xiaomi.com/identity/result/check?$common", followRedirects = false)
            finishLocation = fallback.location.orEmpty()
        }
        if (finishLocation.isBlank()) return "两步验证后未取得跳转地址"
        val endUrl = if (finishLocation.contains("identity/result/check")) {
            val rc = request("GET", finishLocation, followRedirects = false)
            rc.location.orEmpty()
        } else finishLocation
        if (endUrl.isBlank()) return "两步验证结束地址为空"
        var end = request("GET", endUrl, followRedirects = false)
        if (end.code == 200 && end.text.contains("Xiaomi Account - Tips")) {
            end = request("GET", endUrl, followRedirects = false)
        }
        val extension = end.header("extension-pragma")
        if (extension.isBlank()) return "两步验证未返回登录安全信息"
        ssecurity = JSONObject(extension).optString("ssecurity")
        if (ssecurity.isBlank()) return "两步验证安全信息无效"
        var stsUrl = end.location.orEmpty()
        if (stsUrl.isBlank()) {
            val start = end.text.indexOf("https://sts.api.io.mi.com/sts")
            if (start >= 0) stsUrl = end.text.substring(start).takeWhile { it != '\"' && it != '\'' && !it.isWhitespace() }.replace("&amp;", "&")
        }
        if (stsUrl.isBlank()) return "两步验证未返回 STS 地址"
        val sts = request("GET", stsUrl, followRedirects = true)
        serviceToken = findCookie("serviceToken", ".sts.api.io.mi.com")
        if (serviceToken.isBlank()) serviceToken = findCookie("serviceToken")
        // 2FA 分支不走 rememberLogin。必须拿“数字 userId”：登录时塞过一个 userId=账号(手机/邮箱)的 cookie，
        // findCookie 可能返回它 → 拉设备鉴权 401。改从跳转 URL 的 userId= 取纯数字，cookie 只作纯数字兜底。
        if (userId.isBlank() || !userId.all { it.isDigit() }) {
            userId = sequenceOf(queryValue(endUrl, "userId"), queryValue(finishLocation, "userId"))
                .firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }
                ?: numericUserIdCookie()
                ?: userId
        }
        if (cUserId.isBlank()) cUserId = findCookie("cUserId")
        return if (serviceToken.isBlank()) "两步验证后未取得 serviceToken" else null
    }

    /** 从 cookie 里挑一个“纯数字”的 userId，避开登录时塞入的账号(手机/邮箱)值。 */
    private fun numericUserIdCookie(): String? = cookies.cookieStore.cookies
        .filter { it.name == "userId" }
        .map { it.value }
        .firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }

    private fun rememberLogin(json: JSONObject) {
        ssecurity = json.optString("ssecurity", ssecurity)
        userId = json.optString("userId", userId)
        cUserId = json.optString("cUserId", cUserId)
        passToken = json.optString("passToken", passToken)
        location = json.optString("location", location)
    }

    private fun fetchAllDevices(country: String): List<CloudDevice> {
        val api = getApiUrl(country)
        val homesResponse = executeApiCallEncrypted(
            "$api/v2/homeroom/gethome",
            JSONObject().put("fg", true).put("fetch_share", true).put("fetch_share_dev", true)
                .put("limit", 300).put("app_ver", 7).toString(),
        ) ?: throw IllegalStateException("拉取家庭列表失败${if (lastApiError.isNotBlank()) "（$lastApiError）" else ""}")
        // result 缺失时，homesResponse 里通常带 code/message/description，直接带出真实原因。
        val result = homesResponse.optJSONObject("result")
            ?: throw IllegalStateException("家庭列表异常：${homesResponse.toString().take(220)}")
        val homes = mutableListOf<Pair<String, String>>()
        collectHomes(result.optJSONArray("homelist"), homes)
        collectHomes(result.optJSONArray("share_home_list"), homes)
        val devices = LinkedHashMap<String, CloudDevice>()
        homes.distinct().forEach { (homeId, ownerId) ->
            val data = JSONObject().put("home_owner", ownerId).put("home_id", homeId).put("limit", 200)
                .put("get_split_device", true).put("support_smart_home", true).toString()
            val response = executeApiCallEncrypted("$api/v2/home/home_device_list", data) ?: return@forEach
            val array = response.optJSONObject("result")?.optJSONArray("device_info") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val did = item.optString("did")
                if (did.isBlank()) continue
                devices[did] = CloudDevice(
                    item.optString("name").ifBlank { item.optString("model").ifBlank { "小米设备" } },
                    item.optString("localip").trim().ifBlank { null },
                    item.optString("token").trim().ifBlank { null },
                    item.optString("model"),
                )
            }
        }
        return devices.values.toList()
    }

    private fun collectHomes(array: JSONArray?, target: MutableList<Pair<String, String>>) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val home = array.optJSONObject(index) ?: continue
            val id = home.opt("id")?.toString().orEmpty()
            val owner = home.opt("uid")?.toString().orEmpty().ifBlank { home.opt("home_owner")?.toString().orEmpty() }
            if (id.isNotBlank() && owner.isNotBlank()) target += id to owner
        }
    }

    private fun executeApiCallEncrypted(url: String, dataJson: String): JSONObject? {
        val millis = System.currentTimeMillis()
        val nonce = XiaomiCloudCrypto.generateNonce(millis)
        val signedNonce = XiaomiCloudCrypto.signedNonce(ssecurity, nonce)
        val fields = XiaomiCloudCrypto.generateEncParams(
            url, "POST", signedNonce, nonce, linkedMapOf("data" to dataJson), ssecurity,
        )
        val cookie = listOf(
            "userId=$userId", "yetAnotherServiceToken=$serviceToken", "serviceToken=$serviceToken",
            "locale=en_GB", "timezone=GMT+02:00", "is_daylight=1", "dst_offset=3600000", "channel=MI_APP_STORE",
        ).joinToString("; ")
        // 与参考实现一致：加密字段放 URL query string(requests 的 params=)，POST 不带 body。
        // 签名在 generateEncParams 内已用不带 query 的基础 url 算好，附加 query 不影响签名。
        val response = request(
            "POST",
            "$url?${formEncode(fields)}",
            headers = mapOf(
                "Accept-Encoding" to "identity",
                "Content-Type" to FORM_CONTENT_TYPE,
                "x-xiaomi-protocal-flag-cli" to "PROTOCAL-HTTP2",
                "MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4",
                "Cookie" to cookie,
            ),
        )
        if (response.code != 200) {
            lastApiError = "HTTP ${response.code}(uid=$userId stLen=${serviceToken.length} ssLen=${ssecurity.length})：${response.text.take(100)}"
            return null
        }
        val decrypted = runCatching {
            XiaomiCloudCrypto.decryptRc4(XiaomiCloudCrypto.signedNonce(ssecurity, fields.getValue("_nonce")), response.text)
        }.getOrNull()
        if (decrypted == null) lastApiError = "响应解密失败"
        return decrypted?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = followRedirects
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", agent)
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                if (!headers.containsKey("Content-Type")) connection.setRequestProperty("Content-Type", FORM_CONTENT_TYPE)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input -> ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray() } ?: ByteArray(0)
            HttpResponse(code, bytes, connection.headerFields.filterKeys { it != null }, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpResponse.header(name: String): String = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull().orEmpty()

    private fun findCookie(name: String, domain: String? = null): String = cookies.cookieStore.cookies
        .firstOrNull { it.name == name && (domain == null || it.domain?.contains(domain.removePrefix("."), true) == true) }?.value.orEmpty()

    private fun queryValue(url: String, key: String): String = URI(url).rawQuery.orEmpty().split('&')
        .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.firstOrNull() == key }?.getOrNull(1) }
        .firstOrNull()?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }.orEmpty()

    private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun formEncode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class HttpResponse(
        val code: Int,
        val bytes: ByteArray,
        val headers: Map<String, List<String>>,
        val location: String?,
    ) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    companion object {
        val SERVERS = listOf("cn", "de", "us", "ru", "tw", "sg", "in", "i2")
        private const val TIMEOUT_MS = 10_000
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        private val COOKIE_LOCK = Any()

        fun getApiUrl(country: String): String = "https://${if (country == "cn") "" else "$country."}api.io.mi.com/app"
    }
}
