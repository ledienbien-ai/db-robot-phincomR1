package info.dourok.voicebot.mcp

import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.media.LocalMusicPlayer
import info.dourok.voicebot.media.MusicService
import info.dourok.voicebot.media.RadioStations
import info.dourok.voicebot.weather.LocationManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools this speaker offers to the assistant over MCP (see [DeviceMcp] for the channel).
 *
 * The names and arguments of the volume, music and radio tools are the ones xiaozhi ESP32 devices
 * use (78/xiaozhi-esp32 and its music/radio forks), so a server and a prompt written for those
 * devices drive this speaker the same way. Time and weather are this app's own: the server knows
 * neither where a given speaker stands nor what time it is there.
 *
 * The descriptions are written for the language model, not for people: they are what makes it pick
 * the right tool -- and call it at all, rather than answer from its own guess.
 */
object DeviceTools {

    fun build(appVersion: String): DeviceMcp = DeviceMcp("db-robot-r1", appVersion)
        .add(tool(
            "self.get_device_status",
            "Provides the real-time information of the device, including the current status of " +
                "the audio speaker (volume), the music or radio being played, the network, and " +
                "the configured location.\nUse this tool for:\n" +
                "1. Answering questions about current condition (e.g. what is the current volume " +
                "of the audio speaker?)\n" +
                "2. As the first step to control the device (e.g. turn up / down the volume of " +
                "the audio speaker, etc.)",
            noArguments(),
        ) { deviceStatus(appVersion) })
        .add(tool(
            "self.audio_speaker.set_volume",
            "Set the volume of the audio speaker. If the current volume is unknown, you must " +
                "call `self.get_device_status` tool first and then call this tool. " +
                "Dùng khi người dùng bảo tăng, giảm hoặc đặt âm lượng.",
            arguments(JSONObject().put(
                "volume",
                JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)
            ), "volume"),
        ) { args ->
            if (!args.has("volume")) throw IllegalArgumentException("Thiếu tham số volume (0-100).")
            val volume = args.optInt("volume", -1).coerceIn(0, 100)
            val set = DeviceActions.setVolume ?: throw IllegalStateException("Loa chưa sẵn sàng.")
            set(volume)
            success("Đã đặt âm lượng ${DeviceActions.getVolume?.invoke() ?: volume}%")
        })
        .add(tool(
            "self.music.play_song",
            "Play a specified song ONLINE. Đây là công cụ PHÁT NHẠC MẶC ĐỊNH.\n" +
                "Khi người dùng nói: 'phát nhạc', 'mở nhạc', 'phát bài hát', 'play music', " +
                "'play song', 'mở bài ...', hãy dùng công cụ này.\n" +
                "Args:\n" +
                "  song_name: Tên bài hát (bắt buộc)\n" +
                "  artist_name: Tên ca sĩ (tùy chọn)\n" +
                "Return:\n" +
                "  Bài hát được phát ngay sau khi trợ lý nói xong; chỉ cần báo ngắn gọn tên bài.",
            arguments(JSONObject()
                .put("song_name", JSONObject().put("type", "string"))
                .put("artist_name", JSONObject().put("type", "string")), "song_name"),
        ) { args ->
            val found = MusicService.playSong(args.optString("song_name", ""), args.optString("artist_name", ""))
            success("Đang mở bài: $found")
        })
        .add(tool(
            "self.music.stop",
            "Stop the music or radio that is playing on the speaker. Dùng khi người dùng nói " +
                "'dừng nhạc', 'tắt nhạc', 'tắt radio', 'stop music'.",
            noArguments(),
        ) {
            LocalMusicPlayer.stop()
            success("Đã dừng phát")
        })
        .add(tool(
            "self.music.set_display_mode",
            "Set the display mode for music playback ('spectrum' or 'lyrics'). This device is a " +
                "screenless speaker, so the call reports that there is nothing to switch.",
            arguments(JSONObject().put("mode", JSONObject().put("type", "string")), "mode"),
        ) {
            // Kept so that servers and prompts written for the ESP32 devices find the tool they
            // expect; the truthful answer for a speaker with no screen is that it cannot.
            JSONObject().put("success", false).put(
                "message",
                "Loa Phicomm R1 không có màn hình nên không có chế độ hiển thị. Quang phổ nhạc " +
                    "xem được trên trang điều khiển của loa, tab Media."
            ).toString()
        })
        .add(tool(
            "self.radio.get_stations",
            "Get the list of available radio stations.\nReturn:\n  JSON array of available radio stations.",
            noArguments(),
        ) {
            val names = JSONArray()
            RadioStations.all().forEach { names.put(it.name) }
            JSONObject().put("success", true).put("stations", names).toString()
        })
        .add(tool(
            "self.radio.play_station",
            "Play a radio station by name. Use this tool when user requests to play radio or " +
                "listen to a specific station. VOV mộc/mốc/mốt/một means the VOV1 channel.\n" +
                "Args:\n" +
                "  `station_name`: The name of the radio station to play (e.g. 'VOV1', " +
                "'VOV Giao thông Hà Nội').\n" +
                "Return:\n" +
                "  Playback status information. The station starts right after the assistant " +
                "finishes speaking.",
            arguments(JSONObject().put("station_name", JSONObject().put("type", "string")), "station_name"),
        ) { args ->
            val wanted = args.optString("station_name", "")
            val station = RadioStations.find(wanted)
            if (station == null) {
                val known = RadioStations.all().joinToString(", ") { it.name }
                throw IllegalStateException("Không có kênh \"$wanted\". Các kênh hiện có: $known.")
            }
            MusicService.playStation(station)
            success("Đang mở kênh ${station.name}")
        })
        .add(tool(
            "self.get_local_time",
            "Trả về ngày giờ địa phương chính xác tại nơi đặt loa của người dùng (đúng múi giờ " +
                "của họ). LUÔN gọi công cụ này khi người dùng hỏi bây giờ là mấy giờ, hôm nay " +
                "là ngày mấy, thứ mấy, hoặc khi cần biết thời gian hiện tại. Không dùng thời " +
                "gian có sẵn trong ngữ cảnh hệ thống vì có thể lệch múi giờ. " +
                "Returns the user's accurate local date and time; always use it for time questions.",
            noArguments(),
        ) { LocationManager.localTimeText() })
        .add(tool(
            "self.get_weather",
            "Trả về thời tiết hiện tại và dự báo 3 ngày. Bỏ trống 'city' để lấy thời tiết tại " +
                "nơi đặt loa của người dùng (dùng khi họ hỏi thời tiết mà không nêu địa điểm); " +
                "hoặc điền tên thành phố họ hỏi. " +
                "Returns current weather and a 3-day forecast for the user's location or a named city.",
            arguments(JSONObject().put(
                "city",
                JSONObject().put("type", "string").put(
                    "description",
                    "Tên thành phố cần xem thời tiết; bỏ trống để dùng vị trí của loa."
                )
            )),
        ) { args -> LocationManager.weatherText(args.optString("city", "").trim()) })

    /** What the ESP32 devices return for `self.get_device_status`, with this speaker's own facts. */
    private fun deviceStatus(appVersion: String): String {
        val music = try {
            JSONObject(LocalMusicPlayer.snapshot())
        } catch (e: Exception) {
            JSONObject()
        }
        return JSONObject()
            .put("audio_speaker", JSONObject().put("volume", DeviceActions.getVolume?.invoke() ?: -1))
            .put(
                "music",
                JSONObject()
                    .put("state", music.optString("state", "idle"))
                    .put("title", music.optString("title", ""))
                    .put("artist", music.optString("artist", ""))
            )
            .put("network", JSONObject().put("type", "wifi"))
            .put(
                "location",
                JSONObject().put("city", Settings.locName).put("timezone", LocationManager.zone.id)
            )
            .put("application", JSONObject().put("name", "DB-Robot Phicomm R1").put("version", appVersion))
            .toString()
    }

    private fun success(message: String): String =
        JSONObject().put("success", true).put("message", message).toString()

    private fun noArguments(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    private fun arguments(properties: JSONObject, vararg required: String): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
        return schema
    }

    private fun tool(
        name: String,
        description: String,
        schema: JSONObject,
        run: (JSONObject) -> String,
    ): DeviceMcp.Tool = object : DeviceMcp.Tool {
        override fun name() = name
        override fun description() = description
        override fun inputSchema() = schema
        override fun call(arguments: JSONObject): String = run(arguments)
    }
}
