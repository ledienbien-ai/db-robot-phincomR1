package info.dourok.voicebot.media

import org.json.JSONObject

/** Outcome of asking pytube_api's `/v3/video/<id>` to ensure a track is downloaded/cached. */
sealed class PytubeVideoResult {
    /** [mp3Path] is the path pytube_api returned in `mp3_url` (already includes `?device=...`) —
     *  relative to pytube_api's base URL unless it's already an absolute URL. */
    data class Ready(val mp3Path: String, val title: String, val durationMs: Long) : PytubeVideoResult()
    /** Video is private/deleted/restricted (pytube_api returns 404 + `unavailable:true`) — not
     *  worth retrying. */
    data class Unavailable(val message: String) : PytubeVideoResult()
    /** Network error, malformed response, or a download failure worth retrying. */
    data class Error(val message: String) : PytubeVideoResult()
}

/** Parses pytube_api's `/v3/video/<id>` response. [httpStatus] disambiguates the 404-unavailable
 *  case from other errors, since both can carry an `error`/`message` pair in the body. */
fun parseVideoResponse(json: String, httpStatus: Int): PytubeVideoResult {
    val obj = try { JSONObject(json) } catch (e: Exception) {
        return PytubeVideoResult.Error("bad response: ${e.message}")
    }
    if (httpStatus == 404 && obj.optBoolean("unavailable", false)) {
        return PytubeVideoResult.Unavailable(obj.optString("message", "video unavailable"))
    }
    if (httpStatus != 200) {
        return PytubeVideoResult.Error(obj.optString("message", obj.optString("error", "HTTP $httpStatus")))
    }
    val mp3Path = obj.optString("mp3_url", "")
    if (mp3Path.isEmpty()) return PytubeVideoResult.Error("missing mp3_url in response")
    val title = obj.optString("video_title", "")
    val durationSec = obj.optString("video_duration", "0").toDoubleOrNull() ?: 0.0
    return PytubeVideoResult.Ready(mp3Path, title, (durationSec * 1000).toLong())
}
