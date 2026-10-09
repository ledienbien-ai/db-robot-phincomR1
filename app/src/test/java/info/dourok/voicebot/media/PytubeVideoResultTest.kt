package info.dourok.voicebot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PytubeVideoResultTest {
    @Test fun readyFromCachedResponse() {
        val json = """
            {"video_title":"Một Nhà","video_thumbnail_url":"https://x/y.jpg","video_id":"S8sYD-2yco0",
             "video_url":"https://youtube.com/watch?v=S8sYD-2yco0","video_duration":"252",
             "mp3_url":"/v3/mp3/S8sYD-2yco0?device=abc","is_loaded_from_cache":true}
        """.trimIndent()
        val result = parseVideoResponse(json, 200)
        assertIs<PytubeVideoResult.Ready>(result)
        assertEquals("/v3/mp3/S8sYD-2yco0?device=abc", result.mp3Path)
        assertEquals("Một Nhà", result.title)
        assertEquals(252_000L, result.durationMs)
    }

    @Test fun unavailableOn404WithUnavailableFlag() {
        val json = """{"error":"Video unavailable","message":"private video","video_id":"x","unavailable":true}"""
        val result = parseVideoResponse(json, 404)
        assertIs<PytubeVideoResult.Unavailable>(result)
        assertEquals("private video", result.message)
    }

    @Test fun errorOnDownloadFailure500() {
        val json = """{"error":"Download failed","message":"yt-dlp exploded","video_id":"x"}"""
        val result = parseVideoResponse(json, 500)
        assertIs<PytubeVideoResult.Error>(result)
        assertEquals("yt-dlp exploded", result.message)
    }

    @Test fun errorOnMalformedJson() {
        val result = parseVideoResponse("not json", 200)
        assertIs<PytubeVideoResult.Error>(result)
    }

    @Test fun errorWhenMp3UrlMissing() {
        val result = parseVideoResponse("""{"video_title":"x"}""", 200)
        assertIs<PytubeVideoResult.Error>(result)
    }
}
