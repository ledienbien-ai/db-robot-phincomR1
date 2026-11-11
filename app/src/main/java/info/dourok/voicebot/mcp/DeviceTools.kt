package info.dourok.voicebot.mcp

import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.ha.HaActions
import info.dourok.voicebot.ha.HomeTools
import info.dourok.voicebot.link.DisplayCard
import info.dourok.voicebot.lunar.LunarAnswers
import info.dourok.voicebot.lunar.LunarCalendar
import info.dourok.voicebot.market.MarketPrices
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.media.LocalMusicPlayer
import info.dourok.voicebot.media.MusicService
import info.dourok.voicebot.media.RadioStations
import info.dourok.voicebot.net.SpeakerAddress
import info.dourok.voicebot.weather.LocationManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools this speaker offers to the assistant over MCP (see [DeviceMcp] for the channel).
 *
 * The names and arguments of the volume, music and radio tools are the ones xiaozhi ESP32 devices
 * use (78/xiaozhi-esp32 and its music/radio forks), so a server and a prompt written for those
 * devices drive this speaker the same way. Time and weather are this app's own: the server knows
 * neither where a given speaker stands nor what time it is there. Gold and fuel prices are too
 * (see [MarketPrices]): a model asked for today's price otherwise answers from its training data.
 *
 * The descriptions are written for the language model, not for people: they are what makes it pick
 * the right tool -- and call it at all, rather than answer from its own guess.
 */
object DeviceTools {

    /** The MCP servers added in the panel; their tools follow this device's own. */
    val remoteServers: RemoteMcpServers by lazy {
        RemoteMcpServers { AppLog.i(it) }.also { it.configure(Settings.mcpServers) }
    }

    fun build(appVersion: String): DeviceMcp = DeviceMcp("db-robot-r1", appVersion)
        .addSource(remoteServers)
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
            "self.video.play",
            "Mở một VIDEO YouTube: tiếng phát trên loa, hình hiện trên màn hình phụ của loa.\n" +
                "Dùng khi người dùng nói 'mở video', 'xem video', 'bật video', 'mở clip', 'cho xem', " +
                "'mở phim hoạt hình', 'mở MV', 'xem trên YouTube', 'mở video ... trên màn hình'. " +
                "Chỉ muốn NGHE nhạc thì dùng self.music.play_song.\n" +
                "Args:\n" +
                "  query: Tên video cần tìm trên YouTube (bắt buộc), ví dụ 'hoạt hình Doraemon tập mới'\n" +
                "Return:\n" +
                "  Video được mở ngay sau khi trợ lý nói xong; chỉ cần báo ngắn gọn tên video. " +
                "Dừng video bằng self.music.stop.",
            arguments(JSONObject().put("query", JSONObject().put("type", "string")), "query"),
        ) { args ->
            val title = MusicService.playVideo(args.optString("query", ""))
            val note = if (info.dourok.voicebot.link.SpeakerLink.displays().length() == 0)
                " Loa chưa nối màn hình phụ nên chỉ nghe được tiếng." else ""
            success("Đang mở video: $title.$note")
        })
        .add(tool(
            "self.music.stop",
            "Stop the music, radio or video that is playing on the speaker. Dùng khi người dùng nói " +
                "'dừng nhạc', 'tắt nhạc', 'tắt radio', 'tắt video', 'stop music'.",
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
            "self.network.get_ip_address",
            "Trả về địa chỉ IP của loa trong mạng Wi-Fi và địa chỉ trang điều khiển của loa. " +
                "LUÔN gọi công cụ này khi người dùng hỏi địa chỉ IP ('địa chỉ IP của loa là gì', " +
                "'đọc địa chỉ IP', 'IP của bạn', 'địa chỉ mạng', 'what is your IP address') hoặc hỏi " +
                "cách mở trang điều khiển, trang cài đặt của loa. Không bao giờ tự đoán địa chỉ. " +
                "Kết quả có trường `speak`: hãy đọc nguyên văn trường đó cho người dùng, giữ " +
                "nguyên từng chữ số. " +
                "Returns the speaker's IP address and control panel URL; read the `speak` field " +
                "aloud verbatim.",
            noArguments(),
        ) {
            val ip = SpeakerAddress.current()
            // Told on request: no need to read the same address out unasked afterwards.
            if (SpeakerAddress.isAddress(ip)) Settings.lastAnnouncedIp = ip
            SpeakerAddress.toolReply(ip)
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
        // Home Assistant, from the speaker itself (it is the one inside the house). The device list
        // in the description is read each time the server asks for the tools, i.e. every session.
        .add(liveTool(
            "self.home.control",
            {
                "Điều khiển thiết bị nhà thông minh (Home Assistant) của người dùng: bật/tắt đèn, quạt, " +
                    "ổ cắm, công tắc, điều hoà, rèm, cửa cuốn, khoá, tivi, kịch bản/ngữ cảnh… LUÔN dùng công cụ " +
                    "này khi người dùng muốn bật, tắt, mở, đóng, chỉnh độ sáng, nhiệt độ, tốc độ quạt, vị trí rèm " +
                    "của một thiết bị trong nhà. Không tự nói là đã làm nếu chưa gọi công cụ.\n" +
                    "Args:\n" +
                    "  device: thiết bị như người dùng gọi ('đèn phòng khách', 'quạt'), tên phòng ('phòng ngủ' = " +
                    "mọi thiết bị trong phòng), 'tất cả đèn', hoặc entity_id trong danh sách dưới (có thể nhiều, " +
                    "cách nhau bằng dấu phẩy). Nên dùng entity_id khi biết.\n" +
                    "  action: turn_on (bật/mở/chạy), turn_off (tắt/đóng), toggle, open, close, stop, " +
                    "set_brightness, set_temperature, set_position, set_volume, set_speed, lock, unlock, press, play, pause.\n" +
                    "  value: số cho set_brightness / set_position / set_volume / set_speed (0-100 %) và " +
                    "set_temperature (°C).\n" +
                    "Return: câu báo kết quả; đọc lại ngắn gọn cho người dùng. Nếu kết quả nói có nhiều thiết bị " +
                    "khớp, hỏi lại người dùng." + HomeTools.describe()
            },
            arguments(JSONObject()
                .put("device", JSONObject().put("type", "string"))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(HaActions.ACTIONS.toList())))
                .put("value", JSONObject().put("type", "number")), "device", "action"),
        ) { args ->
            HomeTools.control(
                args.optString("device", "").trim(),
                args.optString("action", "").trim(),
                if (args.has("value") && !args.isNull("value")) args.optDouble("value", Double.NaN) else Double.NaN,
            )
        })
        .add(liveTool(
            "self.home.assist",
            {
                "Chuyển nguyên câu yêu cầu về nhà thông minh của người dùng cho trợ lý Home Assistant (Assist) " +
                    "của chính họ xử lý, rồi đọc lại câu trả lời của Home Assistant. Dùng khi yêu cầu phức tạp hoặc " +
                    "không có trong danh sách thiết bị (ngữ cảnh, kịch bản, khu vực, hỏi nhiều thiết bị cùng lúc, " +
                    "đặt hẹn giờ trong nhà), hoặc khi self.home.control không tìm được thiết bị." +
                    (if (Settings.haAgentId.isNotBlank() || Settings.haPipeline.isNotBlank())
                        " Người dùng đã chọn trợ lý Home Assistant riêng: ưu tiên công cụ này cho MỌI lệnh nhà thông minh."
                    else "") +
                    "\nArgs:\n  text: câu người dùng nói, giữ nguyên ý (tiếng Việt)." +
                    "\nReturn: câu trả lời của Home Assistant; đọc lại cho người dùng."
            },
            arguments(JSONObject().put("text", JSONObject().put("type", "string")), "text"),
        ) { args -> HomeTools.assist(args.optString("text", "").trim()) })
        .add(liveTool(
            "self.home.get_state",
            {
                "Đọc trạng thái thiết bị và cảm biến nhà thông minh (Home Assistant): đèn đang bật hay tắt, " +
                    "nhiệt độ, độ ẩm trong phòng, điều hoà đang đặt bao nhiêu độ, rèm đang mở hay đóng, cửa có " +
                    "khoá chưa. Dùng khi người dùng hỏi về tình trạng thiết bị trong nhà. Bỏ trống device để đọc " +
                    "tất cả thiết bị đã chọn; hoặc điền tên thiết bị, tên phòng hay entity_id." + HomeTools.describe()
            },
            arguments(JSONObject().put("device", JSONObject().put("type", "string"))),
        ) { args -> HomeTools.status(args.optString("device", "").trim()) })
        .add(tool(
            "self.market.get_gold_price",
            "Trả về giá vàng hôm nay tại Việt Nam (mua vào, bán ra) theo bảng giá PNJ: vàng miếng " +
                "SJC, nhẫn trơn 999.9, vàng nữ trang 24K, 18K, 14K… LUÔN gọi công cụ này khi người " +
                "dùng hỏi giá vàng, giá vàng SJC, vàng nhẫn, vàng 9999; không tự đoán giá. Đọc giá " +
                "bằng triệu đồng một lượng (hoặc một chỉ nếu họ hỏi theo chỉ). " +
                "Returns today's Vietnamese gold buy/sell prices; always use it for gold price questions.",
            noArguments(),
        ) {
            val text = MarketPrices.goldText()
            card { DisplayCard.table("gold", "Giá vàng", MarketPrices.goldUpdated(), MarketPrices.goldTable()) }
            text
        })
        .add(tool(
            "self.market.get_fuel_price",
            "Trả về giá bán lẻ xăng dầu hiện hành tại Việt Nam của Petrolimex (vùng 1, vùng 2) và " +
                "PVOIL: xăng E5 RON 92, xăng RON 95 (E10), dầu diesel DO, dầu hỏa, kèm mức tăng giảm " +
                "kỳ điều chỉnh gần nhất. LUÔN gọi công cụ này khi người dùng hỏi giá xăng, giá dầu, " +
                "xăng tăng hay giảm; không tự đoán giá. Nếu không nói vùng thì đọc giá vùng 1. " +
                "Returns current Vietnamese retail fuel prices; always use it for fuel price questions.",
            noArguments(),
        ) {
            val text = MarketPrices.fuelText()
            card { DisplayCard.table("fuel", "Giá xăng dầu", "Petrolimex · vùng 1", MarketPrices.fuelTable()) }
            text
        })
        .add(tool(
            "self.calendar.get_lunar_date",
            "Đổi ngày dương lịch sang âm lịch Việt Nam: ngày tháng âm, tháng nhuận, can chi của ngày, " +
                "tháng, năm và giờ hoàng đạo. Bỏ trống 'date' để lấy hôm nay tại nơi đặt loa. LUÔN " +
                "gọi công cụ này khi người dùng hỏi hôm nay là ngày bao nhiêu âm lịch, mùng mấy, ngày " +
                "can chi, giờ hoàng đạo; không tự tính. " +
                "Converts a solar date (default today) to the Vietnamese lunar calendar.",
            arguments(JSONObject().put(
                "date",
                JSONObject().put("type", "string")
                    .put("description", "Ngày dương lịch dạng dd/mm/yyyy; bỏ trống là hôm nay.")
            )),
        ) { args ->
            val day = LunarAnswers.parseDate(args.optString("date", ""), today())
            val text = LunarAnswers.lunarDate(day, today())
            card { lunarCard(day) }
            text
        })
        .add(tool(
            "self.calendar.lunar_to_solar",
            "Đổi ngày âm lịch sang dương lịch và cho biết còn bao nhiêu ngày. Dùng khi người dùng " +
                "hỏi Tết, rằm, mùng một, giỗ, Trung thu, Vu Lan… rơi vào ngày nào dương lịch (Tết là " +
                "1/1 âm, rằm tháng giêng 15/1, Trung thu 15/8, Vu Lan 15/7, ông Công ông Táo 23/12). " +
                "Bỏ trống 'year' để dùng năm âm lịch hiện tại. " +
                "Converts a Vietnamese lunar date to the solar calendar.",
            arguments(
                JSONObject()
                    .put("day", JSONObject().put("type", "integer").put("description", "Ngày âm lịch, 1-30."))
                    .put("month", JSONObject().put("type", "integer").put("description", "Tháng âm lịch, 1-12."))
                    .put("year", JSONObject().put("type", "integer").put("description", "Năm âm lịch, ví dụ 2027; bỏ trống là năm nay."))
                    .put("leap", JSONObject().put("type", "boolean").put("description", "true nếu là tháng nhuận.")),
                "day", "month",
            ),
        ) { args ->
            val text = LunarAnswers.solarDate(
                args.getInt("day"), args.getInt("month"), args.optInt("year", 0),
                args.optBoolean("leap", false), today(),
            )
            card {
                val t = today()
                val y = args.optInt("year", 0).takeIf { it > 0 } ?: LunarCalendar.solarToLunar(t[0], t[1], t[2]).year
                LunarCalendar.lunarToSolar(args.getInt("day"), args.getInt("month"), y, args.optBoolean("leap", false))
                    ?.let { lunarCard(it) }
            }
            text
        })
        .add(tool(
            "self.calendar.get_horoscope_facts",
            "Trả về dữ kiện để xem tử vi hôm nay cho một người theo ngày sinh dương lịch: ngày sinh " +
                "âm lịch, tuổi can chi, mệnh ngũ hành, cung hoàng đạo, can chi hôm nay, quan hệ hợp/" +
                "xung giữa ngày hôm nay và tuổi, giờ hoàng đạo. Gọi khi người dùng hỏi tử vi, vận hạn, " +
                "hôm nay của tuổi mình thế nào. Dựa vào dữ kiện này mà luận ngắn gọn, vui vẻ về công " +
                "việc, tài lộc, tình cảm, sức khỏe; nói rõ chỉ để tham khảo, đừng bịa dữ kiện khác. " +
                "Returns the calendar facts for today's Vietnamese horoscope of a birth date.",
            arguments(
                JSONObject().put(
                    "birth_date",
                    JSONObject().put("type", "string").put("description", "Ngày sinh dương lịch dạng dd/mm/yyyy.")
                ),
                "birth_date",
            ),
        ) { args ->
            val today = today()
            LunarAnswers.horoscopeFacts(LunarAnswers.parseDate(args.getString("birth_date"), today), today)
        })

    /** What the ESP32 devices return for `self.get_device_status`, with this speaker's own facts. */
    private fun deviceStatus(appVersion: String): String {
        val music = try {
            JSONObject(LocalMusicPlayer.snapshot())
        } catch (e: Exception) {
            JSONObject()
        }
        val ip = SpeakerAddress.current()
        return JSONObject()
            .put("audio_speaker", JSONObject().put("volume", DeviceActions.getVolume?.invoke() ?: -1))
            .put(
                "music",
                JSONObject()
                    .put("state", music.optString("state", "idle"))
                    .put("title", music.optString("title", ""))
                    .put("artist", music.optString("artist", ""))
            )
            .put(
                "network",
                JSONObject().put("type", "wifi").put("ip", ip)
                    .put("control_panel", if (ip.isEmpty()) "" else SpeakerAddress.panelUrl(ip))
            )
            .put(
                "location",
                JSONObject().put("city", Settings.locName).put("timezone", LocationManager.zone.id)
            )
            .put("application", JSONObject().put("name", "DB-Robot Phicomm R1").put("version", appVersion))
            .toString()
    }

    /** A picture for the display (link/DisplayCard); never lets a drawing problem fail the tool. */
    private inline fun card(block: () -> Unit) {
        try { block() } catch (e: Exception) { AppLog.w("Thẻ màn hình: ${e.message}") }
    }

    private val WEEKDAY = arrayOf("Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy")

    private fun lunarCard(s: IntArray) {
        val jd = LunarCalendar.jdFromDate(s[0], s[1], s[2])
        val l = LunarCalendar.solarToLunar(s[0], s[1], s[2])
        DisplayCard.lunar(
            s[0], s[1], s[2], WEEKDAY[(jd + 1) % 7], l.day, l.month, l.leap, LunarCalendar.yearCanChi(l.year),
            "Ngày ${LunarCalendar.dayCanChi(jd)} · tháng ${LunarCalendar.monthCanChi(l.month, l.year)}",
            LunarCalendar.gioHoangDao(jd).replace(Regex(" \\([^)]*\\)"), ""),
        )
    }

    /** Today in the speaker's own time zone, which is the owner's, not the server's. */
    private fun today(): IntArray = LunarAnswers.today(LocationManager.zone, System.currentTimeMillis())

    private fun success(message: String): String =
        JSONObject().put("success", true).put("message", message).toString()

    private fun noArguments(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    private fun arguments(properties: JSONObject, vararg required: String): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
        return schema
    }

    /** A tool whose description is written anew each time it is listed (it carries live settings). */
    private fun liveTool(
        name: String,
        describe: () -> String,
        schema: JSONObject,
        run: (JSONObject) -> String,
    ): DeviceMcp.Tool = object : DeviceMcp.Tool {
        override fun name() = name
        override fun description() = describe()
        override fun inputSchema() = schema
        override fun call(arguments: JSONObject): String = run(arguments)
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
