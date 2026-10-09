package info.dourok.voicebot.data

import info.dourok.voicebot.data.model.DeviceInfo
import info.dourok.voicebot.data.model.toJson
import info.dourok.voicebot.domain.voice.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asks the OTA endpoint where this device should connect, and remembers the answer.
 *
 * The server replies with the WebSocket url/token to use and, while the device has not been added
 * to an account yet, an activation code the owner types into the server's web console. Both ends
 * key that on the Device-Id header, so this MUST send the same identity the WebSocket handshake
 * sends ([DeviceInfo.mac_address] / [DeviceInfo.uuid]) -- a code issued for one id does nothing
 * for a connection that announces another.
 *
 * Plain object rather than DI, like [info.dourok.voicebot.domain.voice.ServerAudioParams]: the
 * control panel, the application start-up and the protocol all need it, and none of them holds a
 * reference to the others.
 */
object ServerProvisioner {

    data class Outcome(val ok: Boolean, val wsUrl: String, val source: String, val error: String)

    /** Activation code from the last OTA reply; "" once the device is bound (or never asked). */
    @Volatile var activationCode: String = ""
    /** Human-readable instruction the server sent with the code. */
    @Volatile var activationMessage: String = ""
    /** Wall-clock ms of the last OTA reply that parsed; 0 = none yet. */
    @Volatile var lastCheckMs: Long = 0L
    /** versionName of the installed build, set once at start-up and reported to the server. */
    @Volatile var appVersion: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * POST the device report to [otaUrl] and store what comes back.
     *
     * @param deriveOnFailure when the OTA endpoint does not answer with a websocket block, guess
     *   the WebSocket url from the OTA host. Wanted when somebody presses Connect in the panel
     *   (they need *something* to try); not wanted for the automatic check at start-up, where a
     *   guess would overwrite a url that was working before the network blinked.
     */
    @Synchronized
    fun provision(
        deviceInfo: DeviceInfo,
        otaUrl: String = Settings.otaUrl,
        deriveOnFailure: Boolean = false,
    ): Outcome {
        val ota = otaUrl.trim()
        if (ota.isBlank()) return Outcome(false, Settings.wsUrl, "", "no ota url")
        val uri = try { java.net.URI(ota) } catch (e: Exception) { null }
        val host = uri?.host
        if (uri == null || host == null) {
            return Outcome(false, Settings.wsUrl, "", "bad ota url (expect https://host/xiaozhi/ota/)")
        }
        val otaReq = if (ota.endsWith("/")) ota else "$ota/"   // trailing slash optional for the user

        var error = ""
        val reply: JSONObject? = try {
            val req = Request.Builder().url(otaReq)
                .post(reportBody(deviceInfo).toRequestBody("application/json".toMediaType()))
                .header("Device-Id", deviceInfo.mac_address)
                .header("Client-Id", deviceInfo.uuid)
                .header("Accept-Language", "vi-VN")
                .header("User-Agent", "$BOARD_NAME/$appVersion")
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error = "HTTP ${resp.code}"
                runCatching { JSONObject(txt) }.getOrNull()
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            null
        }

        if (reply != null) {
            lastCheckMs = System.currentTimeMillis()
            val activation = reply.optJSONObject("activation")
            activationCode = activation?.optString("code", "").orEmpty()
            activationMessage = activation?.optString("message", "").orEmpty()
        }

        val ws = reply?.optJSONObject("websocket")
        val wsUrl = ws?.optString("url", "").orEmpty()
        if (wsUrl.isNotBlank()) {
            if (wsUrl != Settings.wsUrl) AppLog.i("Server: $wsUrl (từ OTA)")
            Settings.wsUrl = wsUrl
            Settings.wsToken = ws?.optString("token", "").orEmpty()
            if (activationCode.isNotBlank()) AppLog.w("Thiết bị chưa kích hoạt, mã: $activationCode")
            return Outcome(true, wsUrl, "ota", "")
        }

        if (error.isBlank()) error = "OTA không trả về địa chỉ websocket"
        if (deriveOnFailure) {
            val derived = deriveWsUrl(uri, host)
            Settings.wsUrl = derived
            Settings.wsToken = ""
            AppLog.w("OTA không trả lời ($error), tự suy ra server: $derived")
            return Outcome(true, derived, "derived", error)
        }
        AppLog.w("Kiểm tra OTA thất bại: $error")
        return Outcome(false, Settings.wsUrl, "", error)
    }

    /**
     * Best guess when the OTA endpoint is silent. An https OTA url means the server sits behind a
     * TLS reverse proxy, where the WebSocket shares the host and port; a plain http one is the
     * stock self-hosted layout with the WebSocket on :8000.
     */
    private fun deriveWsUrl(uri: java.net.URI, host: String): String =
        if (uri.scheme.equals("https", ignoreCase = true)) {
            val port = if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""
            "wss://$host$port/xiaozhi/v1/"
        } else {
            "ws://$host:8000/xiaozhi/v1/"
        }

    /**
     * The device report the xiaozhi OTA route expects. Identity comes from the stored [DeviceInfo];
     * the application and board blocks are overwritten because the stored ones are placeholder
     * values generated once at first launch, and the server console shows them to the owner.
     */
    private fun reportBody(deviceInfo: DeviceInfo): String {
        val o = JSONObject(deviceInfo.toJson())
        o.optJSONObject("application")?.apply {
            put("name", BOARD_NAME)
            put("version", appVersion)
        }
        o.optJSONObject("board")?.apply {
            put("type", BOARD_NAME)
            put("name", BOARD_NAME)
            put("manufacturer", "DB-Robot")
            put("mac", deviceInfo.mac_address)
        }
        return o.toString()
    }

    private const val BOARD_NAME = "db-robot-r1"
}
