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

    // The music server resolves a song through SoundCloud before it answers, hence the long read --
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
        // Not the station's own address: the speaker's Android cannot verify today's https
        // certificates by itself, so the stream is relayed by ControlServer (/radio/stream), which
        // fetches it with the app's current root list.
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
