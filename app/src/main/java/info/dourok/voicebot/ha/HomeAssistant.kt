package info.dourok.voicebot.ha

import info.dourok.voicebot.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The user's Home Assistant, reached from the speaker itself over its REST API with a long-lived
 * access token (Profile › Security › Long-lived access tokens).
 *
 * It runs on the speaker because the speaker is the one in the house: Home Assistant usually lives
 * at http://homeassistant.local:8123 or a 192.168.x.x address that a server on the internet cannot
 * reach. ".local" names are looked up with [MdnsResolver] (Android 5.1 does not know them).
 *
 * Plain HttpURLConnection on purpose: one request at a time, nothing to share with the panel's
 * client, and the calls run the same off the device.
 */
object HomeAssistant {

    class HaException(message: String) : IOException(message)

    fun configured(): Boolean = Settings.haUrl.isNotBlank() && Settings.haToken.isNotBlank()

    /** A Home Assistant in the house (a .local name, a private address, or a bare host name). */
    fun isLan(base: String): Boolean {
        val host = runCatching { URL(base.trim()).host.lowercase() }.getOrDefault("")
        if (host.isEmpty()) return false
        if (host.endsWith(".local") || !host.contains('.')) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }

    /** GET /api/config: the version and the home's name, which also proves the URL and the token. */
    fun info(base: String = Settings.haUrl, token: String = Settings.haToken): JSONObject =
        JSONObject(request(base, token, "GET", "/api/config", null))

    fun states(base: String = Settings.haUrl, token: String = Settings.haToken): JSONArray =
        JSONArray(request(base, token, "GET", "/api/states", null))

    fun state(entityId: String): JSONObject =
        JSONObject(request(Settings.haUrl, Settings.haToken, "GET", "/api/states/$entityId", null))

    /** POST /api/services/<domain>/<service>; returns the states that changed. */
    fun call(domain: String, service: String, data: JSONObject): JSONArray =
        JSONArray(request(Settings.haUrl, Settings.haToken, "POST", "/api/services/$domain/$service", data.toString()))

    /** The address actually used for [base] ("192.168.1.20" for homeassistant.local), for the panel. */
    fun resolvedHost(base: String): String = runCatching { target(URL(base.trim())).host }.getOrDefault("")

    private fun target(u: URL): URL {
        if (u.protocol == "http" && MdnsResolver.isLocalName(u.host)) {
            val ip: InetAddress = MdnsResolver.resolve(u.host, 2500)
                ?: runCatching { InetAddress.getByName(u.host) }.getOrNull()
                ?: throw HaException(
                    "Không tìm thấy ${u.host} trong mạng. Kiểm tra Home Assistant đang chạy và cùng mạng Wi-Fi " +
                        "với loa, hoặc dùng địa chỉ IP, ví dụ http://192.168.1.10:8123."
                )
            return URL(u.protocol, ip.hostAddress, u.port, u.file)
        }
        return u
    }

    private fun request(base: String, token: String, method: String, path: String, body: String?): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) throw HaException("Chưa nhập địa chỉ Home Assistant (ví dụ http://homeassistant.local:8123).")
        if (token.isBlank()) throw HaException("Chưa nhập token truy cập dài hạn của Home Assistant.")
        val url = try {
            target(URL(b + path))
        } catch (e: java.net.MalformedURLException) {
            throw HaException("Địa chỉ Home Assistant không hợp lệ: $b (cần dạng http://homeassistant.local:8123).")
        }
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 -> return text
                code == 401 -> throw HaException(
                    "Home Assistant từ chối token (401): token sai, hết hạn hoặc đã bị xoá. Tạo token mới ở " +
                        "Hồ sơ › Bảo mật › Token truy cập dài hạn."
                )
                code == 404 && path.startsWith("/api/states/") -> throw HaException("Home Assistant không có thiết bị ${path.substringAfterLast('/')}.")
                code == 404 -> throw HaException("Không thấy API của Home Assistant ở $b (kiểm tra cổng, thường là 8123).")
                code == 400 -> throw HaException("Home Assistant không nhận lệnh: ${text.take(160)}")
                else -> throw HaException("Home Assistant trả lỗi HTTP $code ${text.take(120)}")
            }
        } catch (e: HaException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw HaException("Home Assistant ở $b không trả lời kịp.")
        } catch (e: ConnectException) {
            throw HaException("Không nối được tới Home Assistant ở $b (${url.host}:${if (url.port > 0) url.port else url.defaultPort}). Kiểm tra địa chỉ, cổng và mạng.")
        } catch (e: UnknownHostException) {
            throw HaException("Không tìm thấy máy ${url.host}. Thử dùng địa chỉ IP của Home Assistant.")
        } catch (e: javax.net.ssl.SSLException) {
            throw HaException("Lỗi HTTPS khi nối Home Assistant (${e.message}). Trong mạng nhà hãy dùng http://…:8123.")
        } finally {
            c.disconnect()
        }
    }
}
