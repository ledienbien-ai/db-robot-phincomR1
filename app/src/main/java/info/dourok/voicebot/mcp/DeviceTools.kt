package info.dourok.voicebot.mcp

import info.dourok.voicebot.weather.LocationManager
import org.json.JSONObject

/**
 * The tools this speaker offers to the assistant over MCP (see [DeviceMcp] for the channel).
 *
 * Why these two live on the device: the server knows neither where a given speaker stands nor what
 * time it is there -- it answers with its own clock, which for a server in another time zone is
 * simply the wrong hour. The owner sets the city once in the control panel, and from then on the
 * language model can ask the speaker itself.
 *
 * The descriptions are written for the language model, not for people: they are what makes it call
 * the tool instead of answering from the time printed in its system prompt.
 */
object DeviceTools {

    fun build(appVersion: String): DeviceMcp = DeviceMcp("db-robot-r1", appVersion)
        .add(object : DeviceMcp.Tool {
            override fun name() = "self.get_local_time"

            override fun description() =
                "Trả về ngày giờ địa phương chính xác tại nơi đặt loa của người dùng (đúng múi giờ " +
                    "của họ). LUÔN gọi công cụ này khi người dùng hỏi bây giờ là mấy giờ, hôm nay " +
                    "là ngày mấy, thứ mấy, hoặc khi cần biết thời gian hiện tại. Không dùng thời " +
                    "gian có sẵn trong ngữ cảnh hệ thống vì có thể lệch múi giờ. " +
                    "Returns the user's accurate local date and time; always use it for time questions."

            override fun inputSchema(): JSONObject =
                JSONObject().put("type", "object").put("properties", JSONObject())

            override fun call(arguments: JSONObject): String = LocationManager.localTimeText()
        })
        .add(object : DeviceMcp.Tool {
            override fun name() = "self.get_weather"

            override fun description() =
                "Trả về thời tiết hiện tại và dự báo 3 ngày. Bỏ trống 'city' để lấy thời tiết tại " +
                    "nơi đặt loa của người dùng (dùng khi họ hỏi thời tiết mà không nêu địa điểm); " +
                    "hoặc điền tên thành phố họ hỏi. " +
                    "Returns current weather and a 3-day forecast for the user's location or a named city."

            override fun inputSchema(): JSONObject = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "city",
                        JSONObject().put("type", "string").put(
                            "description",
                            "Tên thành phố cần xem thời tiết; bỏ trống để dùng vị trí của loa."
                        )
                    )
                )

            override fun call(arguments: JSONObject): String =
                LocationManager.weatherText(arguments.optString("city", "").trim())
        })
}
