package info.dourok.voicebot.media

import android.media.audiofx.Visualizer
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
    /** A group follower that never hears "go" starts by itself after this long. */
    private const val HOLD_MAX_MS = 12_000L
    private const val STOPPED = "stopped"
    private const val ERROR = "error"

    private const val TICK_MS = 500L

    /**
     * Bars the panel draws. The window below has 512 bins, about 368 of them drawn, so 64 bars
     * are all real readings (the lowest ~39 are one bin each); many more would only repeat bins.
     */
    private const val BANDS = 64
    /** FFT window asked of the platform (clamped to what it supports); 1024 gives ~43 Hz bins. */
    private const val FFT_SIZE = 1024
    /** Share of the spectrum drawn: above this there is little but hiss in a 128k MP3. */
    private const val SPECTRUM_SHARE = 0.72
    /** Level, in dB below full scale, that maps to an empty bar. */
    private const val FLOOR_DB = 48.0

    private val main = Handler(Looper.getMainLooper())
    private var player: PlayerEngine? = null  // main thread only
    /** The video playing now, for the display's pictures (ControlServer /video_mjpeg). */
    @Volatile var video: YtVideoEngine? = null
        private set

    /** A YouTube video (sound here, pictures on the display) rather than a song: see MusicService.videoTrack. */
    fun isVideo(track: Track): Boolean = track.id.startsWith(VIDEO_PREFIX)
    const val VIDEO_PREFIX = "ytv:"
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
    /** Why the last track failed, when its player could say (a video can). */
    @Volatile var lastError = ""
        private set

    // The platform's own analyser, attached to the playing song's audio session. Created and
    // released on the main thread with the player; read from HTTP worker threads, hence the lock.
    private val vizLock = Any()
    private var visualizer: Visualizer? = null
    private var fftBuf: ByteArray? = null

    /**
     * A song was asked for by voice and is waiting for the conversation to end (see
     * [playWhenVoiceEnds]). VoiceAssistant reads it to end the session once the reply is spoken.
     */
    @Volatile var pendingForVoice = false
        private set

    /**
     * Something is loaded here -- playing, paused, or waiting for a voice session to end. The
     * panel's transport buttons are routed here then, whatever the music-server setting says: a
     * radio station or a podcast plays on this player even with no music server configured.
     */
    val isLoaded: Boolean
        get() = (state == PLAYING || state == LOADING || state == PAUSED) && index >= 0 && index < queue.size

    /** A song is coming out of the speaker, or is about to. */
    val isActive: Boolean
        get() = state == PLAYING || state == LOADING

    fun play(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        main.post {
            queue = tracks
            heldForVoice = false
            groupFollower = false
            startAt(startIndex.coerceIn(0, tracks.size - 1))
        }
    }

    // ── Group playback (link/GroupSync) ──────────────────────────────────────────
    // Speakers in a group start each track together: every one prepares it, holds it once
    // prepared, and starts when the leader says "go" (after all are ready, or a timeout). Without
    // the hold, each would start as soon as its own stream had buffered, seconds apart.

    /** Told when THIS speaker, leading a group, starts a track or is paused/resumed/stopped. */
    interface GroupLeader {
        /** A track is about to play here; [token] names this start for [goHeld]. */
        fun onTrack(track: Track, token: String)
        fun onCommand(command: String, arg: Int)
    }

    /** Set by GroupSync while this speaker leads a group with "Phát cùng" on. */
    @Volatile var groupLeader: GroupLeader? = null

    /** The current track was sent by a group leader (so nothing here is forwarded again). */
    @Volatile var groupFollower = false
        private set

    @Volatile private var holdToken = ""        // a prepared track waits for goHeld(holdToken)
    @Volatile private var holdReady = false     // ... and it is prepared
    private val autoGo = Runnable { goNow() }   // the leader never said go: start anyway

    /**
     * Play one track for a group leader: prepare it and hold it until [goHeld] with [token].
     * Starts by itself after [HOLD_MAX_MS] should the go never come.
     */
    fun playForGroup(track: Track, token: String) {
        main.post {
            queue = listOf(track)
            heldForVoice = false
            groupFollower = true
            startAt(0, token)
        }
    }

    /** Whether the held track [token] has finished preparing (polled by GroupSync). */
    fun isReady(token: String): Boolean = holdToken == token && holdReady

    /** Start the held track [token] in [delayMs] (the same delay on every speaker of the group). */
    fun goHeld(token: String, delayMs: Long) {
        main.post {
            if (holdToken != token) return@post
            main.removeCallbacks(autoGo)
            if (delayMs > 0) main.postDelayed(autoGo, delayMs) else goNow()
        }
    }

    /** A follower pausing/resuming/stopping because its leader did; never forwarded. */
    fun groupCommand(command: String, arg: Int) {
        if (!groupFollower) return
        when (command) {
            "pause" -> main.post { pauseNow() }
            "resume" -> main.post { resumeNow() }
            "stop" -> main.post { stopNow(STOPPED) }
            "seek" -> seekTo(arg)
        }
    }

    private fun goNow() {
        main.removeCallbacks(autoGo)
        holdToken = ""
        val p = player ?: return
        if (!prepared) return            // onPrepared sees no hold and starts by itself
        if (wantPlaying) {
            try {
                p.start()
                state = PLAYING
                scheduleTick()
            } catch (e: Exception) {
                stopNow(ERROR)
            }
        } else {
            state = PAUSED
        }
    }

    private fun leaderCommand(command: String, arg: Int = 0) {
        if (groupFollower) return
        try { groupLeader?.onCommand(command, arg) } catch (_: Exception) { }
    }

    /**
     * Queue [tracks] without starting them: the assistant is in the middle of a conversation and
     * its reply has to be heard first. [releaseAfterVoice] starts them when the session is over.
     */
    fun playWhenVoiceEnds(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        pendingForVoice = true
        main.post {
            releasePlayer()
            queue = tracks
            index = startIndex.coerceIn(0, tracks.size - 1)
            positionMs = 0
            durationMs = 0
            wantPlaying = false
            state = PAUSED
            groupFollower = false
            heldForVoice = true   // resumeNow() finds no player and starts the queued track
        }
    }

    fun pause() {
        main.post { heldForVoice = false; pauseNow(); leaderCommand("pause") }
    }

    fun resume() {
        main.post { heldForVoice = false; resumeNow(); leaderCommand("resume") }
    }

    fun next() {
        main.post {
            heldForVoice = false
            if (index + 1 < queue.size) startAt(index + 1) else stopNow(STOPPED)
        }
    }

    fun stop() {
        pendingForVoice = false
        main.post { heldForVoice = false; stopNow(STOPPED); leaderCommand("stop") }
    }

    fun seekTo(positionS: Int) {
        main.post {
            // A live stream reports no duration and cannot be seeked; asking anyway makes some
            // MediaPlayer builds raise an error and stop the song.
            val p = player
            if (p != null && prepared && durationMs > 0 && p.canSeek) {
                try {
                    p.seekTo(positionS * 1000)
                    leaderCommand("seek", positionS)
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
        pendingForVoice = false
        main.post {
            if (heldForVoice) {
                heldForVoice = false
                resumeNow()
            }
        }
    }

    /**
     * Current spectrum as [BANDS] levels 0..255, low frequencies first, as a JSON reply. All zero
     * while nothing is playing; `{"ok":false}` when the platform gave us no analyser at all, so the
     * panel can stop asking.
     */
    fun spectrumJson(): String {
        val levels = IntArray(BANDS)
        synchronized(vizLock) {
            val v = visualizer ?: return """{"ok":false}"""
            val buf = fftBuf ?: return """{"ok":false}"""
            if (state == PLAYING) {
                try {
                    if (v.getFft(buf) == Visualizer.SUCCESS) fillBands(buf, levels)
                } catch (e: Throwable) {
                    // released underneath us between songs -- an empty frame is the right answer
                }
            }
        }
        val sb = StringBuilder(BANDS * 4 + 24)
        sb.append("""{"ok":true,"bands":[""")
        for (i in 0 until BANDS) {
            if (i > 0) sb.append(',')
            sb.append(levels[i])
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Fold the platform's FFT into [BANDS] bars. [fft] is its packed layout: two real values first
     * (DC, Nyquist), then (real, imaginary) byte pairs for bins 1..n/2-1. Bars are spaced
     * logarithmically -- music is, and linear bars would spend most of the width on treble -- and
     * each takes the peak of its bins on a dB scale, which is how loudness is heard.
     */
    private fun fillBands(fft: ByteArray, out: IntArray) {
        val bins = fft.size / 2
        val top = (bins * SPECTRUM_SHARE).toInt().coerceIn(2, bins - 1)
        var lo = 1
        for (b in 0 until out.size) {
            val edge = Math.pow(top.toDouble(), (b + 1).toDouble() / out.size).toInt()
            val hi = edge.coerceIn(lo + 1, top + 1)
            var peak = 0.0
            for (k in lo until hi) {
                if (k >= bins) break
                val re = fft[2 * k].toDouble()
                val im = fft[2 * k + 1].toDouble()
                val mag = Math.sqrt(re * re + im * im)
                if (mag > peak) peak = mag
            }
            // 181 = |(-128, -128)|, the largest magnitude a byte pair can hold.
            val db = 20.0 * Math.log10(peak / 181.0 + 1e-6)
            out[b] = (((db + FLOOR_DB) / FLOOR_DB).coerceIn(0.0, 1.0) * 255.0).toInt()
            lo = hi
            if (lo > top) break
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

    private fun startAt(i: Int, followToken: String = "") {
        releasePlayer()
        main.removeCallbacks(autoGo)
        index = i
        positionMs = 0
        durationMs = 0
        val track = queue[i]
        state = LOADING
        wantPlaying = true
        holdReady = false
        holdToken = followToken
        // A video is not shared with a group: the music server makes at most two video streams.
        if (followToken.isEmpty() && !groupFollower && !isVideo(track)) {
            // leading a group: the others prepare the same track and everyone starts on "go"
            val leader = groupLeader
            if (leader != null) {
                val token = java.lang.Long.toHexString(System.nanoTime()) + i
                holdToken = token
                try { leader.onTrack(track, token) } catch (_: Exception) { holdToken = "" }
            }
        }
        if (holdToken.isNotEmpty()) main.postDelayed(autoGo, HOLD_MAX_MS)
        AppLog.i("Phát nhạc: ${track.title}")
        try {
            val v = if (isVideo(track)) YtVideoEngine(track.url) else null
            val p: PlayerEngine = v ?: MediaPlayerEngine(track.url)
            player = p
            video = v
            p.setListeners(onPrepared = {
                val mp = p
                if (mp === player) {
                    prepared = true
                    durationMs = try { mp.duration.coerceAtLeast(0) } catch (e: Exception) { 0 }
                    attachVisualizer(mp.audioSessionId)
                    if (holdToken.isNotEmpty()) {
                        holdReady = true   // waiting for the group's "go" (goHeld / autoGo)
                    } else if (wantPlaying) {
                        mp.start()
                        state = PLAYING
                        scheduleTick()
                    } else {
                        state = PAUSED
                    }
                }
            }, onCompletion = {
                if (p === player) {
                    if (index + 1 < queue.size) startAt(index + 1)
                    else {
                        stopNow(STOPPED)
                        leaderCommand("stop")
                    }
                }
            }, onError = { what, extra ->
                // handled: MediaPlayerEngine does not also deliver onCompletion for a song that failed
                if (p === player) {
                    val why = p.errorText
                    if (why.isNotEmpty()) AppLog.e("Lỗi phát video: $why (${track.title})")
                    else AppLog.e("Lỗi phát nhạc ($what/$extra): ${track.title}")
                    lastError = why
                    stopNow(ERROR)
                }
            })
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
        main.removeCallbacks(autoGo)
        holdToken = ""
        releasePlayer()
        wantPlaying = false
        positionMs = 0
        state = endState
    }

    /**
     * Attach the spectrum analyser to this song's audio session. Purely decorative, so any failure
     * (the engine refusing to initialise is a known per-ROM hazard) costs the bars and nothing else.
     */
    private fun attachVisualizer(sessionId: Int) {
        detachVisualizer()
        try {
            val v = Visualizer(sessionId)
            v.setEnabled(false)
            val range = Visualizer.getCaptureSizeRange()
            v.setCaptureSize(FFT_SIZE.coerceIn(range[0], range[1]))
            // Normalised: otherwise the levels follow the volume slider and a quiet room shows
            // an empty display for a song that is plainly playing.
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            v.setEnabled(true)
            synchronized(vizLock) {
                fftBuf = ByteArray(v.getCaptureSize())
                visualizer = v
            }
        } catch (e: Throwable) {
            AppLog.w("Không bật được quang phổ nhạc: ${e.message}")
        }
    }

    private fun detachVisualizer() {
        val v = synchronized(vizLock) {
            val current = visualizer
            visualizer = null
            fftBuf = null
            current
        }
        if (v != null) {
            try {
                v.setEnabled(false)
                v.release()
            } catch (e: Throwable) {
                // already gone with its audio session
            }
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(tick)
        detachVisualizer()
        val p = player
        player = null
        video = null
        prepared = false
        if (p != null) {
            try {
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

    /** Bars for a display (link/SpeakerLink): [out] filled, 0..255, low first; false when silent. */
    fun spectrum(out: IntArray): Boolean {
        val levels = IntArray(BANDS)
        synchronized(vizLock) {
            val v = visualizer ?: return false
            val buf = fftBuf ?: return false
            if (state != PLAYING) return false
            try {
                if (v.getFft(buf) != Visualizer.SUCCESS) return false
                fillBands(buf, levels)
            } catch (e: Throwable) {
                return false
            }
        }
        val per = BANDS / out.size
        for (k in out.indices) {
            var m = 0
            for (j in 0 until per) m = maxOf(m, levels[k * per + j])
            out[k] = m
        }
        return true
    }

    /** What the display shows for the song: state, title, artist, position, length. */
    fun nowPlayingBrief(): Array<Any>? {
        val q = queue
        val i = index
        if (i < 0 || i >= q.size || state == IDLE) return null
        val t = q[i]
        return arrayOf(state, t.title, t.artist, t.id, positionMs / 1000, durationMs / 1000)
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }
}
