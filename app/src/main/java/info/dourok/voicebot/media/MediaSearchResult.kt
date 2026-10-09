package info.dourok.voicebot.media

import org.json.JSONObject

/** One song from pytube_api's `/v3/search` (ytmusicapi-backed). */
data class MediaSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val thumbnailUrl: String,
)

/** Parses pytube_api's `/v3/search` response body. Returns an empty list on any malformed input
 *  (missing `results`, bad JSON, items without a `video_id`) rather than throwing — this is UI
 *  data, a bad search result should render as "no results", not crash the panel. */
fun parseSearchResults(json: String): List<MediaSearchResult> {
    val obj = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
    val results = obj.optJSONArray("results") ?: return emptyList()
    val out = mutableListOf<MediaSearchResult>()
    for (i in 0 until results.length()) {
        val r = results.optJSONObject(i) ?: continue
        val videoId = r.optString("video_id", "")
        if (videoId.isEmpty()) continue
        out.add(
            MediaSearchResult(
                videoId = videoId,
                title = r.optString("title", ""),
                artist = r.optString("artist", ""),
                duration = r.optString("duration", ""),
                thumbnailUrl = r.optString("thumbnail", ""),
            )
        )
    }
    return out
}
