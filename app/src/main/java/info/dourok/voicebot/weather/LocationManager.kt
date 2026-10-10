package info.dourok.voicebot.weather

import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * The speaker's place in the world as the owner set it in the control panel (Setup tab, "Vị trí &
 * thời tiết"): a city, its coordinates and its time zone. Everything that needs to know "what time
 * is it here" or "what is the weather here" asks this object -- the panel's card, and the two tools
 * the assistant can call (see mcp/DeviceTools).
 *
 * Until a city is chosen the time zone is the build's default (Vietnam) and there is no weather.
 *
 * Plain object, like [info.dourok.voicebot.update.UpdateManager]: the control server and the
 * protocol both reach it and neither should have to hand the other a reference.
 */
object LocationManager {
    /** A forecast is reused for this long; the panel and the assistant may ask many times a minute. */
    private const val FORECAST_TTL_MS = 10 * 60_000L

    private val lock = Any()
    private var cached: JSONObject? = null
    private var cachedAtMs = 0L
    private var cachedFor = ""

    val hasPlace: Boolean get() = Settings.locName.isNotBlank()

    val zone: TimeZone
        get() = TimeZone.getTimeZone(Settings.locTimeZone.ifBlank { "UTC" })

    /**
     * Make the chosen zone this process's default, so the activity log and anything else that
     * formats a time shows the owner's clock rather than the ROM's (the R1 ships set to China).
     * Call at start-up and whenever the place changes.
     */
    fun applyTimeZone() {
        TimeZone.setDefault(zone)
    }

    /** Local time where the speaker is, in words: what the assistant says when asked the time. */
    fun localTimeText(): String {
        val now = System.currentTimeMillis()
        val z = zone
        val where = if (hasPlace) " tại ${Settings.locName}" else ""
        return "Bây giờ là ${Weather.localTime(z, now)}$where " +
            "(múi giờ ${z.id}, ${Weather.utcOffset(z, now)})."
    }

    /**
     * The weather in words. With [city] blank it is the weather where the speaker is; otherwise
     * that city is looked up first, so "thời tiết ở Hà Nội thế nào" works from anywhere.
     */
    fun weatherText(city: String): String {
        if (city.isBlank()) {
            if (!hasPlace) {
                throw IllegalStateException(
                    "Chưa cài vị trí cho loa. Hãy mở trang điều khiển của loa, tab Setup, " +
                        "thẻ Vị trí và thời tiết, rồi nhập thành phố."
                )
            }
            return Weather.speak(forecast(), Settings.locName)
        }
        val place = Weather.search(city, 1).firstOrNull()
            ?: throw IllegalStateException("Không tìm thấy địa điểm \"$city\".")
        return Weather.speak(Weather.forecast(place.lat, place.lon), place.label())
    }

    /** Places matching what was typed, for the panel to offer. */
    fun search(query: String): String = try {
        val results = JSONArray()
        Weather.search(query, 6).forEach { p ->
            results.put(
                JSONObject().put("label", p.label()).put("name", p.name)
                    .put("lat", p.lat).put("lon", p.lon).put("tz", p.tz)
            )
        }
        JSONObject().put("ok", true).put("results", results).toString()
    } catch (e: Exception) {
        failure(e)
    }

    fun setPlace(label: String, lat: Double, lon: Double, tz: String) {
        Settings.locName = label
        Settings.locLat = lat.toString()
        Settings.locLon = lon.toString()
        // An id this platform does not know would silently become GMT; keep the old zone then.
        if (tz.isNotBlank() && TimeZone.getTimeZone(tz).id == tz) Settings.locTimeZone = tz
        synchronized(lock) { cached = null }
        applyTimeZone()
        AppLog.i("Vị trí: $label (${Settings.locTimeZone})")
    }

    fun clearPlace() {
        Settings.locName = ""
        Settings.locLat = ""
        Settings.locLon = ""
        synchronized(lock) { cached = null }
        AppLog.i("Đã xoá vị trí")
    }

    /** Everything the panel's card shows. Never throws: a weather failure is reported in-band. */
    fun stateJson(): String {
        val now = System.currentTimeMillis()
        val z = zone
        val o = JSONObject()
            .put("ok", true)
            .put("configured", hasPlace)
            .put("name", Settings.locName)
            .put("tz", z.id)
            .put("utc_offset", Weather.utcOffset(z, now))
            .put("local_time", Weather.localTime(z, now))
        if (hasPlace) {
            try {
                o.put("weather", Weather.panel(forecast()))
            } catch (e: Exception) {
                o.put("weather_error", describe(e))
            }
        }
        return o.toString()
    }

    private fun forecast(): JSONObject {
        val key = Settings.locLat + "," + Settings.locLon
        synchronized(lock) {
            val have = cached
            if (have != null && cachedFor == key && System.currentTimeMillis() - cachedAtMs < FORECAST_TTL_MS) {
                return have
            }
        }
        val lat = Settings.locLat.toDoubleOrNull()
        val lon = Settings.locLon.toDoubleOrNull()
        if (lat == null || lon == null) throw IllegalStateException("Vị trí đã lưu không hợp lệ.")
        val fresh = Weather.forecast(lat, lon)   // network, outside the lock
        synchronized(lock) {
            cached = fresh
            cachedAtMs = System.currentTimeMillis()
            cachedFor = key
        }
        return fresh
    }

    private fun failure(e: Exception): String =
        JSONObject().put("ok", false).put("error", describe(e)).toString()

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "loa chưa vào được Internet"
        is java.net.SocketTimeoutException -> "máy chủ thời tiết không trả lời"
        is java.net.ConnectException -> "không kết nối được máy chủ thời tiết"
        else -> e.message ?: e.javaClass.simpleName
    }
}
