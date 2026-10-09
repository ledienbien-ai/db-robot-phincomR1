package info.dourok.voicebot.media

import org.json.JSONObject

/**
 * Parses the DB-Robot music server's `/stream_pcm?song=` reply:
 * `{"success":true,"title":..,"artist":..,"thumbnail":..,"audio_url":"/stream_mp3?id=.."}`.
 *
 * That endpoint answers with ONE song -- its best match -- so the result is a single item or null
 * (not found, an error object, or anything that is not the JSON above). The song's `audio_url` is
 * kept verbatim as [MediaSearchResult.videoId]: it is the only handle the server gives out, and
 * [musicStreamUrl] turns it back into an address to play.
 */
fun parseMusicServerSong(json: String): MediaSearchResult? {
    val obj = try { JSONObject(json) } catch (e: Exception) { return null }
    if (!obj.optBoolean("success", false)) return null
    val audioUrl = obj.optString("audio_url", "")
    if (audioUrl.isEmpty()) return null
    return MediaSearchResult(
        videoId = audioUrl,
        title = obj.optString("title", ""),
        artist = obj.optString("artist", ""),
        duration = "",
        thumbnailUrl = obj.optString("thumbnail", ""),
    )
}

/** Absolute stream address for a song handle: relative ones are resolved against [base]. */
fun musicStreamUrl(base: String, audioUrl: String): String = when {
    audioUrl.startsWith("http://") || audioUrl.startsWith("https://") -> audioUrl
    audioUrl.startsWith("/") -> base.trimEnd('/') + audioUrl
    else -> base.trimEnd('/') + "/" + audioUrl
}
