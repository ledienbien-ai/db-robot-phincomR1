package info.dourok.voicebot.data.model

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtaResultWebsocketTest {
    @Test
    fun parsesWebsocketUrlAndToken() {
        val json = JSONObject(
            """{"mqtt":{},"websocket":{"url":"ws://host:8000/xiaozhi/v1/","token":"abc"}}"""
        )
        val r = fromJsonToOtaResult(json)
        assertEquals("ws://host:8000/xiaozhi/v1/", r.websocket?.url)
        assertEquals("abc", r.websocket?.token)
    }

    @Test
    fun websocketAbsentIsNull() {
        val r = fromJsonToOtaResult(JSONObject("""{"mqtt":{}}"""))
        assertNull(r.websocket)
    }

    @Test
    fun mqttAbsentDoesNotThrow() {
        val r = fromJsonToOtaResult(JSONObject("""{"websocket":{"url":"ws://h/v1/"}}"""))
        assertEquals("ws://h/v1/", r.websocket?.url)
    }
}
