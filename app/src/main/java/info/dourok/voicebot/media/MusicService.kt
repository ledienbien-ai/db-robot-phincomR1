package info.dourok.voicebot.media

import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.VoiceDebugState
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * "Play this" as the assistant asks for it: a song by name from the music server, or a radio
 * station. The control panel's Media tab has its own path for the same things (ControlServer); this
 * one exists because a request made by voice arrives in the middle of a conversation, and music
 * must not start under the assistant's own reply -- it is queued and begins when the session ends.
 */
object MusicService {
    /** Where the speaker's own web server listens; the radio stream is fetched through it. */
    private const val LOCAL = "http://127.0.0.1:8088"
    private const val RADIO_PREFIX = "radio:"

    // The music server resolves a song through YouTube (yt-dlp) before it answers, hence the long read --
    // but shorter than the 30 s a xiaozhi server waits for a tool before giving up on it.
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Find [song] (optionally "by [artist]") on the music server and queue it.
     * @return what was found, as "title - artist".
     * @throws IllegalStateException with a message fit to be read out when nothing can be played.
     */
    fun playSong(song: String, artist: String): String {
        val base = Settings.musicUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IllegalStateException("Chưa cài máy chủ nhạc cho loa.")
        val query = listOf(song.trim(), artist.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (query.isEmpty()) throw IllegalStateException("Thiếu tên bài hát.")
        val url = "$base/stream_pcm?song=${URLEncoder.encode(query, "UTF-8")}"
        val body = try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw IllegalStateException("Không kết nối được máy chủ nhạc.")
        }
        val found = parseMusicServerSong(body)
            ?: throw IllegalStateException("Không tìm thấy bài \"$query\".")
        start(
            LocalMusicPlayer.Track(
                id = found.videoId,
                title = found.title,
                artist = found.artist,
                thumbnail = found.thumbnailUrl,
                duration = found.duration,
                url = musicStreamUrl(base, found.videoId),
            )
        )
        return if (found.artist.isBlank()) found.title else "${found.title} - ${found.artist}"
    }

    /**
     * Find a YouTube video for [query] on the music server (`/yt/search`) and queue it: its sound
     * plays here and its pictures go to the display (see [YtVideoEngine]). The other results are
     * kept as fallbacks for a video YouTube refuses to serve.
     * @return the title of what was found.
     */
    fun playVideo(query: String): String {
        val base = Settings.musicUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IllegalStateException("Chưa cài máy chủ nhạc cho loa.")
        val q = query.trim()
        if (q.isEmpty()) throw IllegalStateException("Thiếu tên video.")
        val body = try {
            http.newCall(Request.Builder().url("$base/yt/search?q=${URLEncoder.encode(q, "UTF-8")}").get().build())
                .execute().use { r ->
                    if (r.code == 404) throw IllegalStateException(
                        "Máy chủ nhạc chưa có chức năng video. Cần cập nhật youtube-music có /yt/play."
                    )
                    r.body?.string().orEmpty()
                }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Không kết nối được máy chủ nhạc.")
        }
        val found = parseVideoSearch(body)
        if (found.isEmpty()) throw IllegalStateException("Không tìm thấy video \"$q\".")
        val first = found.first()
        start(videoTrack(base, found.map { it.id }, first.title, first.channel, first.durationS))
        return first.title
    }

    /** One search result from `/yt/search`. */
    data class VideoHit(val id: String, val title: String, val channel: String, val durationS: Int)

    /** `/yt/search` answers a JSON array of {id,title,duration,channel}; live streams last. */
    fun parseVideoSearch(body: String): List<VideoHit> {
        val arr = try { org.json.JSONArray(body) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<VideoHit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) continue
            val d = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
            out.add(VideoHit(id, o.optString("title", "").ifEmpty { "YouTube" },
                o.optString("channel", "").let { if (it == "null") "" else it }, d))
        }
        return out.sortedBy { if (it.durationS > 0) 0 else 1 }
    }

    /** The queue item for a video: [ids] the one to play first, then fallbacks. */
    fun videoTrack(base: String, ids: List<String>, title: String, channel: String, durationS: Int) =
        LocalMusicPlayer.Track(
            id = LocalMusicPlayer.VIDEO_PREFIX + ids.first(),
            title = title,
            artist = channel.ifEmpty { "YouTube" },
            thumbnail = "https://i.ytimg.com/vi/${ids.first()}/mqdefault.jpg",
            duration = if (durationS > 0) "%d:%02d".format(durationS / 60, durationS % 60) else "",
            url = YtVideoEngine.url(base, ids, durationS),
        )

    /** Queue a radio station. */
    fun playStation(station: RadioStations.Station) {
        start(radioTrack(station))
    }

    /** The track for a station, as both this object and the panel's play route build it. */
    fun radioTrack(station: RadioStations.Station) = LocalMusicPlayer.Track(
        id = RADIO_PREFIX + station.key,
        title = station.name,
        artist = "Radio",
        thumbnail = "",
        duration = "",
        // Not the station's own address: stations broadcast as HLS over https, which this
        // Android's player cannot be trusted with. ControlServer (/radio/stream) reads the
        // broadcast itself and serves it here as one plain audio stream -- see HlsAudioStream.
        url = "$LOCAL/radio/stream?id=${station.key}",
    )

    /** The station a queue item id names, or null if it is an ordinary song. */
    fun stationOf(trackId: String): RadioStations.Station? =
        if (trackId.startsWith(RADIO_PREFIX)) RadioStations.byKey(trackId.removePrefix(RADIO_PREFIX)) else null

    private fun start(track: LocalMusicPlayer.Track) {
        if (VoiceDebugState.awake) LocalMusicPlayer.playWhenVoiceEnds(listOf(track), 0)
        else LocalMusicPlayer.play(listOf(track), 0)
    }
}
