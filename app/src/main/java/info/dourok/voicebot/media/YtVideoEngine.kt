package info.dourok.voicebot.media

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.net.Https
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A YouTube video from the music server's robot stream (`/yt/play`, format "DBYT"): its sound plays
 * on this speaker, its pictures are kept here, each with the time it belongs to, for the display to
 * fetch (`/video_mjpeg` of ControlServer, see [VideoRelay]). One stream from the server feeds both,
 * so they stay together; a picture is handed out once the speaker has played up to it.
 *
 * DBYT, little-endian: header "DBYT" ver:u8 w:u16 h:u16 fps:u8 rate:u32 ch:u8 title_len:u16 title;
 * then packets type:u8 ('A' PCM s16le, 'V' one JPEG, 'E' end) len:u32 data. The server sends the
 * sound for each picture just before it.
 *
 * Threads: a reader (network → audio queue + pictures) and a writer (audio queue → AudioTrack, which
 * blocks while the track is full or paused -- that is what paces the whole thing). The queue holds a
 * few seconds of sound, the margin against a Wi-Fi hiccup. A stream that breaks after it has started
 * is opened again from where the sound had got to.
 */
class YtVideoEngine(url: String) : PlayerEngine {
    companion object {
        const val RATE = 44100
        const val FPS = 10
        /** Asked of the server; it fits them to 16:9 (320×176) for the display's 320×240. */
        const val W = 320
        const val H = 240
        private const val QUEUE_CHUNKS = 48           // ≈ 4.8 s at FPS chunks a second
        private const val PREPARED_MS = 1200           // sound in hand before "prepared"
        private const val MAX_RETRIES = 4
        private const val MAX_PACKET = 2 * 1024 * 1024
        private const val MAX_FRAMES = 120

        /** The address of a video for this engine: the first id, and others to try should it be blocked. */
        fun url(base: String, ids: List<String>, durationS: Int): String {
            val b = base.trim().trimEnd('/')
            val alt = ids.drop(1).take(4).joinToString(",")
            return "$b/yt/play?id=${ids.first()}" + (if (alt.isNotEmpty()) "&alt=$alt" else "") +
                (if (durationS > 0) "&dur=$durationS" else "")
        }
    }

    class Frame(val n: Int, val tsMs: Long, val jpeg: ByteArray)

    private val main = Handler(Looper.getMainLooper())
    private val base: String
    private val ids: List<String>
    /** The video's length from the search, 0 when unknown (a live stream). */
    private val durS: Int

    init {
        val q = url.indexOf('?')
        base = if (q > 0) url.substring(0, q).removeSuffix("/yt/play") else url
        val params = if (q > 0) url.substring(q + 1).split('&') else emptyList()
        val first = params.firstOrNull { it.startsWith("id=") }?.removePrefix("id=").orEmpty()
        val alt = params.firstOrNull { it.startsWith("alt=") }?.removePrefix("alt=").orEmpty()
        ids = (listOf(first) + alt.split(',')).filter { it.isNotEmpty() }
        durS = params.firstOrNull { it.startsWith("dur=") }?.removePrefix("dur=")?.toIntOrNull() ?: 0
    }

    private var onPrepared: () -> Unit = {}
    private var onCompletion: () -> Unit = {}
    private var onError: (Int, Int) -> Unit = { _, _ -> }

    @Volatile private var released = false
    @Volatile private var playing = false
    @Volatile private var preparedSent = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile override var errorText: String = ""
        private set
    /** The id that is actually playing (the first one may have been refused). */
    @Volatile var videoId: String = ""
        private set
    @Volatile var title: String = ""
        private set

    private val audio = LinkedBlockingQueue<ByteArray>(QUEUE_CHUNKS)
    private val END = ByteArray(0)
    @Volatile private var samplesRead = 0L      // sound taken from the server, on the playing timeline
    @Volatile private var samplesWritten = 0L   // ... handed to the AudioTrack

    private val frames = ArrayDeque<Frame>()
    private var frameN = 0

    override fun setListeners(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: (Int, Int) -> Unit) {
        this.onPrepared = onPrepared
        this.onCompletion = onCompletion
        this.onError = onError
    }

    override fun prepareAsync() {
        if (ids.isEmpty()) {
            errorText = "Thiếu mã video"
            main.post { onError(1, 0) }
            return
        }
        Thread({ readLoop() }, "ytv-read").start()
    }

    override fun start() {
        val t = track ?: return
        playing = true
        t.play()
    }

    override fun pause() {
        playing = false
        try { track?.pause() } catch (_: Exception) { }
    }

    override val isPlaying: Boolean get() = playing
    override val canSeek: Boolean get() = false
    override fun seekTo(ms: Int) { }

    override val currentPosition: Int
        get() = (positionMs()).toInt()

    override val duration: Int get() = durS * 1000

    override val audioSessionId: Int get() = track?.audioSessionId ?: 0

    /** Where the sound coming out of the speaker has got to, in ms of the video. */
    fun positionMs(): Long {
        val t = track ?: return 0
        return try {
            (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * 1000L / RATE
        } catch (_: Exception) {
            0
        }
    }

    override fun release() {
        if (released) return
        released = true
        playing = false
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) { }
        audio.clear()
        audio.offer(END)
        val c = conn
        // closing the socket unblocks the reader; never on the main thread (it may touch the network)
        if (c != null) Thread({ try { c.disconnect() } catch (_: Exception) { } }, "ytv-close").start()
        synchronized(frames) { frames.clear() }
    }

    val isReleased: Boolean get() = released

    /** The newest picture whose time has come, or null; older ones are dropped. */
    fun dueFrame(): Frame? {
        val pos = positionMs()
        synchronized(frames) {
            var due: Frame? = null
            while (frames.isNotEmpty() && frames.peekFirst()!!.tsMs <= pos) {
                due = frames.pollFirst()
            }
            // keep the one on screen at the front, for a display that arrives now
            if (due != null) frames.addFirst(due)
            return due
        }
    }

    // ── reader ───────────────────────────────────────────────────────────────

    private fun readLoop() {
        var attempt = 0
        var first = true
        try {
            while (!released) {
                val startSec = (samplesRead / RATE).toInt()
                try {
                    if (open(startSec, first)) break      // the stream ended
                    first = false
                } catch (e: IOException) {
                    if (released) return
                    if (!preparedSent) {
                        fail(e.message ?: "Không đọc được video")
                        return
                    }
                    if (++attempt > MAX_RETRIES) {
                        fail("Mất kết nối máy chủ nhạc nhiều lần")
                        return
                    }
                    try { conn?.disconnect() } catch (_: Exception) { }
                    AppLog.w("Video: mất kết nối (${e.message}), nối lại từ giây ${samplesRead / RATE}")
                    try { Thread.sleep(800L * attempt) } catch (_: InterruptedException) { }
                    first = false
                    continue
                }
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
        if (released) return
        // the server said it is over: let the sound already queued play out
        while (!released && !audio.offer(END, 200, TimeUnit.MILLISECONDS)) { }
        if (!preparedSent) prepared()
    }

    /**
     * One connection. Returns true when the stream ended, false never (it throws when it breaks).
     * The ids are tried in turn on the very first connection only: once one plays, a reconnect asks
     * for the same one.
     */
    private fun open(startSec: Int, first: Boolean): Boolean {
        val tryIds = if (first) ids else listOf(videoId)
        var lastErr = ""
        for (id in tryIds) {
            if (released) return true
            val url = "$base/yt/play?id=$id&w=$W&h=$H&fps=$FPS&rate=$RATE" + if (startSec > 0) "&t=$startSec" else ""
            val c = Https.get(url, null)
            conn = c
            val code = c.responseCode
            if (code != 200) {
                lastErr = when (code) {
                    503 -> "Máy chủ nhạc đang bận phát 2 video, thử lại sau"
                    404 -> "YouTube không cho xem video này"
                    else -> "Máy chủ nhạc báo lỗi $code"
                }
                try { lastErr = serverError(c) ?: lastErr } catch (_: Exception) { }
                c.disconnect()
                if (code == 503) break
                continue
            }
            videoId = id
            return stream(c.inputStream)
        }
        throw IOException(lastErr.ifEmpty { "Không mở được video" })
    }

    private fun serverError(c: HttpURLConnection): String? {
        val s = c.errorStream ?: return null
        val txt = s.use { it.readBytes().toString(Charsets.UTF_8) }
        return org.json.JSONObject(txt).optString("error").takeIf { it.isNotEmpty() }
    }

    private fun stream(input: InputStream): Boolean {
        val hdr = readFully(input, 15)
        if (hdr[0] != 'D'.code.toByte() || hdr[1] != 'B'.code.toByte() || hdr[2] != 'Y'.code.toByte() || hdr[3] != 'T'.code.toByte())
            throw IOException("Máy chủ nhạc chưa có chức năng video (cần bản youtube-music có /yt/play)")
        val fps = hdr[9].toInt() and 0xFF
        val rate = u32(hdr, 10).toInt()
        val ch = hdr[14].toInt() and 0xFF
        val tl = u16(readFully(input, 2), 0)
        val t = String(readFully(input, tl), Charsets.UTF_8)
        if (title.isEmpty()) title = t
        if (rate != RATE || ch != 1) throw IOException("Máy chủ gửi tiếng ${rate}Hz/$ch kênh, loa cần ${RATE}Hz/1 kênh")
        ensureTrack()
        val connStartMs = samplesRead * 1000L / RATE
        var k = 0
        val f = if (fps > 0) fps else FPS
        while (!released) {
            val type = input.read()
            if (type < 0) throw EOFException("hết dữ liệu giữa chừng")
            val len = u32(readFully(input, 4), 0)
            if (len < 0 || len > MAX_PACKET) throw IOException("gói video hỏng ($len byte)")
            val data = readFully(input, len.toInt())
            when (type) {
                'A'.code -> {
                    while (!released && !audio.offer(data, 200, TimeUnit.MILLISECONDS)) { }
                    samplesRead += data.size / 2
                    if (!preparedSent && samplesRead * 1000L / RATE - connStartMs >= PREPARED_MS) prepared()
                }
                'V'.code -> {
                    val ts = connStartMs + k * 1000L / f
                    k++
                    synchronized(frames) {
                        frames.addLast(Frame(++frameN, ts, data))
                        while (frames.size > MAX_FRAMES) frames.pollFirst()
                    }
                }
                'E'.code -> {
                    // The server also ends a stream whose source broke (a long pause can do that):
                    // well short of the known length, it is a break, not the end.
                    if (durS > 30 && samplesRead / RATE < durS - 15) throw IOException("máy chủ dừng ở giây ${samplesRead / RATE}")
                    return true
                }
            }
        }
        return true
    }

    private fun prepared() {
        preparedSent = true
        main.post { if (!released) onPrepared() }
    }

    private fun fail(msg: String) {
        errorText = msg
        main.post { if (!released) onError(1, 0) }
    }

    private fun ensureTrack() {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, RATE * 2 / 2)       // ~0.5 s; the queue in front of it holds the rest
        @Suppress("DEPRECATION")
        val t = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IOException("Không mở được đường tiếng của loa")
        }
        track = t
        Thread({ writeLoop(t) }, "ytv-write").start()
    }

    // ── writer ───────────────────────────────────────────────────────────────

    private fun writeLoop(t: AudioTrack) {
        try {
            while (!released) {
                val chunk = audio.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (chunk === END) break
                var off = 0
                while (off < chunk.size && !released) {
                    val n = t.write(chunk, off, chunk.size - off)
                    if (n < 0) throw IOException("AudioTrack.write $n")
                    if (n == 0) Thread.sleep(20)          // paused with a full buffer
                    off += n
                }
                samplesWritten += chunk.size / 2
            }
            if (!released) {
                // the end: wait for the sound already written to come out, then report it
                val endFrames = samplesWritten
                while (!released && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < endFrames - RATE / 20) {
                    Thread.sleep(100)
                }
                if (!released) main.post { if (!released) onCompletion() }
            }
        } catch (e: Exception) {
            if (!released) fail("Lỗi phát tiếng video: ${e.message}")
        } finally {
            // the writer owns the track: release it only when nothing writes to it any more
            if (released) {
                try { t.stop() } catch (_: Exception) { }
                try { t.release() } catch (_: Exception) { }
            } else {
                // completion or failure: released() will come from LocalMusicPlayer; free it then
                while (!released) try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                try { t.stop() } catch (_: Exception) { }
                try { t.release() } catch (_: Exception) { }
            }
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("hết dữ liệu giữa chừng")
            off += r
        }
        return b
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
