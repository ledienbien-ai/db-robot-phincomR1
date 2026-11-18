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

/** Where this device's own web server relays a music-server song from (ControlServer `/music/stream`). */
const val MUSIC_RELAY = "http://127.0.0.1:8088/music/stream?u="

/**
 * The address the on-device player should open for [url]. MediaPlayer checks https certificates
 * against the system's roots only, and Android 5.1 lacks newer ones (Let's Encrypt's among them),
 * so an https song from the music server at [base] goes through the local relay, which trusts the
 * app's own roots too. Anything else is opened as it is.
 */
fun playableMusicUrl(url: String, base: String): String {
    val b = base.trim().trimEnd('/')
    if (b.isEmpty() || !url.startsWith("https://", ignoreCase = true)) return url
    if (!url.startsWith("$b/", ignoreCase = true)) return url
    return MUSIC_RELAY + java.net.URLEncoder.encode(url, "UTF-8")
}
