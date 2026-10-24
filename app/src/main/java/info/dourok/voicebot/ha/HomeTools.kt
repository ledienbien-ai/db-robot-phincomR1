package info.dourok.voicebot.ha

import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the assistant's Home Assistant tools do (self.home.control, self.home.get_state): find the
 * device(s) the user named among the ones saved on the panel, ask Home Assistant, and put the
 * outcome into one sentence for the assistant to say.
 */
object HomeTools {

    /** Domains worth offering when nothing was picked on the panel. */
    private val CONTROLLABLE = setOf(
        "light", "switch", "fan", "cover", "climate", "media_player", "lock", "scene", "script",
        "input_boolean", "vacuum", "valve", "button", "humidifier", "water_heater",
    )

    /** The devices picked on the panel; when none were, every controllable one Home Assistant has. */
    fun devices(): List<HaDevices.Device> {
        val saved = HaDevices.parse(Settings.haDevices)
        if (saved.isNotEmpty()) return saved
        val out = ArrayList<HaDevices.Device>()
        val arr = HomeAssistant.states()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("entity_id")
            if (id.substringBefore('.') !in CONTROLLABLE) continue
            val name = e.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
            out.add(HaDevices.Device("", name.ifBlank { id }, id))
        }
        return out
    }

    /** The saved devices for the tool's description, so the assistant knows the names and ids. */
    fun describe(): String {
        if (!HomeAssistant.configured()) {
            return "\n(Chưa cài Home Assistant trên loa: nếu người dùng hỏi, bảo họ mở trang điều khiển của loa, " +
                "tab System, thẻ Home Assistant, nhập địa chỉ và token.)"
        }
        val saved = HaDevices.parse(Settings.haDevices)
        if (saved.isEmpty()) return "\nThiết bị: chưa chọn trên trang loa; dùng tên người dùng gọi, loa sẽ tìm trong Home Assistant."
        val sb = StringBuilder("\nThiết bị trong nhà (phòng / tên / entity_id):")
        for (d in saved.take(80)) {
            sb.append("\n- ").append(d.location.ifBlank { "—" }).append(" / ").append(d.name).append(" / ").append(d.entityId)
        }
        return sb.toString()
    }

    private fun need() {
        if (!HomeAssistant.configured()) {
            throw IllegalStateException(
                "Loa chưa được nối với Home Assistant. Mở trang điều khiển của loa, tab System, thẻ Home Assistant, " +
                    "nhập địa chỉ (ví dụ http://homeassistant.local:8123) và token truy cập dài hạn."
            )
        }
    }

    private fun verb(domain: String, action: String, value: Double): String {
        val v = if (value.isNaN()) "" else (if (value == Math.floor(value)) value.toLong().toString() else value.toString())
        return when (HaActions.normalize(action)) {
            "turn_on" -> if (domain == "cover" || domain == "valve") "mở" else if (domain == "scene" || domain == "script") "chạy" else "bật"
            "turn_off" -> if (domain == "cover" || domain == "valve") "đóng" else "tắt"
            "toggle" -> "đổi trạng thái"
            "open" -> "mở"
            "close" -> "đóng"
            "stop" -> "dừng"
            "set_brightness" -> "đặt độ sáng $v% cho"
            "set_temperature" -> "đặt $v°C cho"
            "set_position" -> "đặt vị trí $v% cho"
            "set_volume" -> "đặt âm lượng $v% cho"
            "set_speed" -> "đặt tốc độ $v% cho"
            "lock" -> "khoá"
            "unlock" -> "mở khoá"
            "press" -> "nhấn"
            "play" -> "phát"
            "pause" -> "tạm dừng"
            else -> action
        }
    }

    /** self.home.control */
    fun control(device: String, action: String, value: Double): String {
        need()
        if (device.isBlank()) throw IllegalArgumentException("Thiếu tên thiết bị (tham số device).")
        if (action.isBlank()) throw IllegalArgumentException("Thiếu thao tác (tham số action), ví dụ turn_on hoặc turn_off.")
        val all = devices()
        val found = HaDevices.find(device, all)
        if (found.devices.isEmpty()) {
            val names = all.take(30).joinToString(", ") { it.label() }
            throw IllegalArgumentException("Không thấy thiết bị \"$device\". Các thiết bị có: $names.")
        }
        if (found.ambiguous) {
            val names = found.devices.joinToString(", ") { it.label() }
            return JSONObject().put("success", false)
                .put("message", "Có nhiều thiết bị khớp \"$device\": $names. Hỏi lại người dùng muốn thiết bị nào, hoặc gọi lại với device = \"tất cả ...\".")
                .toString()
        }
        val done = ArrayList<String>()
        val failed = ArrayList<String>()
        for (d in found.devices) {
            try {
                val plan = HaActions.plan(d.entityId, action, value)
                val data = JSONObject().put("entity_id", d.entityId)
                for ((k, v) in plan.data) data.put(k, v)
                HomeAssistant.call(plan.domain, plan.service, data)
                done.add(d.label())
            } catch (e: Exception) {
                failed.add("${d.label()}: ${e.message}")
            }
        }
        val dom = found.devices.first().domain()
        val v = verb(dom, action, value)
        AppLog.i("Home Assistant: $v ${done.joinToString(", ")}${if (failed.isEmpty()) "" else " | lỗi: ${failed.joinToString("; ")}"}")
        if (done.isEmpty()) throw IllegalStateException(failed.joinToString("; "))
        val msg = StringBuilder("Đã $v ")
        msg.append(if (done.size == 1) done[0] else "${done.size} thiết bị: ${done.joinToString(", ")}").append('.')
        if (failed.isNotEmpty()) msg.append(" Không làm được: ").append(failed.joinToString("; ")).append('.')
        return JSONObject().put("success", true).put("message", msg.toString()).toString()
    }

    /** self.home.get_state: one device, a room, or (blank) everything saved. */
    fun status(device: String): String {
        need()
        val all = devices()
        val list = if (device.isBlank()) all.take(40) else HaDevices.find(device, all).devices
        if (list.isEmpty()) throw IllegalArgumentException("Không thấy thiết bị \"$device\".")
        val states = HomeAssistant.states()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until states.length()) states.optJSONObject(i)?.let { byId[it.optString("entity_id")] = it }
        val lines = JSONArray()
        val words = ArrayList<String>()
        for (d in list) {
            val s = byId[d.entityId]
            val text = if (s == null) "không có trong Home Assistant" else describeState(d.domain(), s)
            lines.put(JSONObject().put("device", d.label()).put("entity_id", d.entityId).put("state", text))
            words.add("${d.label()}: $text")
        }
        return JSONObject().put("success", true).put("devices", lines).put("message", words.joinToString("; ") + ".").toString()
    }

    /** "đang bật, độ sáng 40%", "26.5 °C", "đang mở, vị trí 70%"… */
    fun describeState(domain: String, s: JSONObject): String {
        val state = s.optString("state")
        val a = s.optJSONObject("attributes") ?: JSONObject()
        val unit = a.optString("unit_of_measurement", "")
        val sb = StringBuilder()
        when (domain) {
            "sensor" -> sb.append(state).append(if (unit.isNotEmpty()) " $unit" else "")
            else -> sb.append(HaActions.stateWords(domain, state))
        }
        if (domain == "light" && state == "on" && a.has("brightness")) {
            sb.append(", độ sáng ").append(Math.round(a.optDouble("brightness", 0.0) * 100 / 255)).append('%')
        }
        if (domain == "climate") {
            if (a.has("current_temperature")) sb.append(", trong phòng ").append(a.opt("current_temperature")).append("°C")
            if (a.has("temperature") && !a.isNull("temperature")) sb.append(", đặt ").append(a.opt("temperature")).append("°C")
        }
        if (domain == "cover" && a.has("current_position")) sb.append(", vị trí ").append(a.opt("current_position")).append('%')
        if (domain == "fan" && a.has("percentage") && !a.isNull("percentage")) sb.append(", tốc độ ").append(a.opt("percentage")).append('%')
        if (domain == "media_player" && a.has("volume_level")) sb.append(", âm lượng ").append(Math.round(a.optDouble("volume_level", 0.0) * 100)).append('%')
        return sb.toString()
    }
}
