package info.dourok.voicebot.media

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import info.dourok.voicebot.domain.voice.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plays songs from the DB-Robot music server (an HTTP MP3 stream per song) on the device itself.
 *
 * The upstream Media tab has no player of its own: it asks the xiaozhi server to fetch a song and
 * send it down the voice channel as Opus. A stock xiaozhi server does not speak those messages,
 * so with a music server configured ([info.dourok.voicebot.data.Settings.musicUrl]) the panel's
 * media endpoints are routed here instead and the voice channel is left alone.
 *
 * Every MediaPlayer call happens on the main thread ([main]): the player needs a Looper for its
 * callbacks, and the callers are NanoHTTPD worker threads and the voice runtime. What the panel
 * reads back ([snapshot]) comes from volatile fields, never from the player.
 *
 * Plain object rather than DI, like MediaSessionState: ControlServer and VoiceAssistant both need
 * it and neither holds a reference to the other.
 */
object LocalMusicPlayer {

    /** [url] is the absolute stream address; [id] is what the panel knows the song by. */
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val thumbnail: String,
        val duration: String,
        val url: String,
    )

    // States are the strings the panel already understands from /api/media/state.
    private const val IDLE = "idle"
    private const val LOADING = "downloading"   // connecting + buffering; drawn as a spinner
    private const val PLAYING = "playing"
    private const val PAUSED = "paused"
    private const val STOPPED = "stopped"
    private const val ERROR = "error"

    private const val TICK_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null   // main thread only
    private var prepared = false              // main thread only
    private var wantPlaying = false           // main thread only: start as soon as it is prepared

    @Volatile private var queue: List<Track> = emptyList()
    @Volatile private var index = -1
    @Volatile private var state = IDLE
    @Volatile private var positionMs = 0
    @Volatile private var durationMs = 0
    // Set when a voice session interrupted a song that was playing, so only that song is resumed
    // afterwards -- one the user paused by hand stays paused.
    @Volatile private var heldForVoice = false

    /** A song is coming out of the speaker, or is about to. */
    val isActive: Boolean
        get() = state == PLAYING || state == LOADING

    fun play(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        main.post {
            queue = tracks
            heldForVoice = false
            startAt(startIndex.coerceIn(0, tracks.size - 1))
        }
    }

    fun pause() {
        main.post { heldForVoice = false; pauseNow() }
    }

    fun resume() {
        main.post { heldForVoice = false; resumeNow() }
    }

    fun next() {
        main.post {
            heldForVoice = false
            if (index + 1 < queue.size) startAt(index + 1) else stopNow(STOPPED)
        }
    }

    fun stop() {
        main.post { heldForVoice = false; stopNow(STOPPED) }
    }

    fun seekTo(positionS: Int) {
        main.post {
            // A live stream reports no duration and cannot be seeked; asking anyway makes some
            // MediaPlayer builds raise an error and stop the song.
            val p = player
            if (p != null && prepared && durationMs > 0) {
                try {
                    p.seekTo(positionS * 1000)
                } catch (e: Exception) {
                    AppLog.w("Không tua được: ${e.message}")
                }
            }
        }
    }

    /** The assistant is about to listen or speak: get the music out of the microphone's way. */
    fun holdForVoice() {
        main.post {
            if (isActive) {
                heldForVoice = true
                pauseNow()
            }
        }
    }

    /** The voice session is over: carry on with the song it interrupted, if there was one. */
    fun releaseAfterVoice() {
        main.post {
            if (heldForVoice) {
                heldForVoice = false
                resumeNow()
            }
        }
    }

    /** Same shape as the server-driven media state, so the panel needs no second code path. */
    fun snapshot(): String {
        val q = queue
        val i = index
        val current = if (i >= 0 && i < q.size) q[i] else null
        val items = JSONArray()
        q.forEach {
            items.put(
                JSONObject()
                    .put("video_id", it.id).put("title", it.title)
                    .put("artist", it.artist).put("thumbnail", it.thumbnail)
                    .put("duration", it.duration)
            )
        }
        return JSONObject()
            .put("state", state)
            .put("video_id", current?.id ?: JSONObject.NULL)
            .put("title", current?.title ?: "")
            .put("artist", current?.artist ?: "")
            .put("cover_url", current?.thumbnail ?: "")
            .put("duration_s", durationMs / 1000)
            .put("position_s", positionMs / 1000)
            .put("download_percent", -1)
            .put("queue", items)
            .toString()
    }

    // ── main thread only below ──────────────────────────────────────────────

    private fun startAt(i: Int) {
        releasePlayer()
        index = i
        positionMs = 0
        durationMs = 0
        val track = queue[i]
        state = LOADING
        wantPlaying = true
        AppLog.i("Phát nhạc: ${track.title}")
        try {
            val p = MediaPlayer()
            player = p
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
            p.setOnPreparedListener { mp ->
                if (mp === player) {
                    prepared = true
                    durationMs = try { mp.duration.coerceAtLeast(0) } catch (e: Exception) { 0 }
                    if (wantPlaying) {
                        mp.start()
                        state = PLAYING
                        scheduleTick()
                    } else {
                        state = PAUSED
                    }
                }
            }
            p.setOnCompletionListener { mp ->
                if (mp === player) {
                    if (index + 1 < queue.size) startAt(index + 1) else stopNow(STOPPED)
                }
            }
            p.setOnErrorListener { mp, what, extra ->
                if (mp === player) {
                    AppLog.e("Lỗi phát nhạc ($what/$extra): ${track.title}")
                    stopNow(ERROR)
                }
                true   // handled: do not also deliver onCompletion for a song that failed
            }
            p.setDataSource(track.url)
            p.prepareAsync()
        } catch (e: Exception) {
            AppLog.e("Không mở được luồng nhạc: ${e.message}")
            stopNow(ERROR)
        }
    }

    private fun pauseNow() {
        wantPlaying = false
        val p = player ?: return
        if (prepared) {
            try {
                if (p.isPlaying) p.pause()
            } catch (e: Exception) {
                // already stopped underneath us -- the state below is still the truth
            }
            state = PAUSED
        }
        // Not prepared yet: onPrepared sees wantPlaying=false and lands on PAUSED by itself.
    }

    private fun resumeNow() {
        val p = player
        if (p == null) {
            // Nothing loaded (stopped, or the stream failed): start the current song again.
            if (index >= 0 && index < queue.size) startAt(index)
            return
        }
        wantPlaying = true
        if (prepared) {
            try {
                p.start()
                state = PLAYING
                scheduleTick()
            } catch (e: Exception) {
                AppLog.e("Không tiếp tục được: ${e.message}")
                stopNow(ERROR)
            }
        }
    }

    private fun stopNow(endState: String) {
        releasePlayer()
        wantPlaying = false
        positionMs = 0
        state = endState
    }

    private fun releasePlayer() {
        main.removeCallbacks(tick)
        val p = player
        player = null
        prepared = false
        if (p != null) {
            try {
                p.reset()
                p.release()
            } catch (e: Exception) {
                // releasing a player that never finished preparing can throw; it is gone either way
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p == null || !prepared || state != PLAYING) return
            try {
                positionMs = p.currentPosition
            } catch (e: Exception) {
                // keep the last known position
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }
}
