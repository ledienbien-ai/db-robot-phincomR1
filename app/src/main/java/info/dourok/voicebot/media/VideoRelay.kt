package info.dourok.voicebot.media

import java.io.InputStream

/**
 * The display's side of a video playing here: `GET /video_mjpeg?id=` on the speaker answers with
 * the same multipart JPEG stream the music server's `/video_mjpeg` gives for a song (the display's
 * video.c reads either), but each picture leaves only when the speaker's sound has reached it. While
 * the video is paused the current picture is sent again every couple of seconds so the display's
 * connection does not time out. The stream ends when this video stops or another starts.
 *
 * One of these per display connection; read on a NanoHTTPD worker thread (blocking is fine there).
 */
class VideoRelay(private val engine: YtVideoEngine) : InputStream() {
    companion object {
        const val BOUNDARY = "frame"
        const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"
        private const val POLL_MS = 15L
        private const val KEEPALIVE_MS = 2000L

        /** A relay for [videoId] if it is the video playing now, else null. */
        fun forVideo(videoId: String): VideoRelay? {
            val e = LocalMusicPlayer.video ?: return null
            if (e.isReleased) return null
            val cur = currentId().removePrefix(LocalMusicPlayer.VIDEO_PREFIX)
            if (videoId.isNotEmpty() && videoId != e.videoId && videoId != cur) return null
            return VideoRelay(e)
        }

        private fun currentId(): String = (LocalMusicPlayer.nowPlayingBrief()?.get(3) as? String).orEmpty()
    }

    private var buf = ByteArray(0)
    private var pos = 0
    private var lastN = -1
    private var lastSent = 0L
    private var ended = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buf.size) {
            if (ended || !next()) { ended = true; return -1 }
        }
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    /** Wait for the next picture to send (or a keep-alive repeat); false when the video is over. */
    private fun next(): Boolean {
        while (true) {
            if (engine.isReleased || LocalMusicPlayer.video !== engine) return false
            val f = engine.dueFrame()
            val now = System.currentTimeMillis()
            if (f != null && (f.n != lastN || now - lastSent >= KEEPALIVE_MS)) {
                lastN = f.n
                lastSent = now
                val head = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.jpeg.size}\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
                val out = ByteArray(head.size + f.jpeg.size + 2)
                System.arraycopy(head, 0, out, 0, head.size)
                System.arraycopy(f.jpeg, 0, out, head.size, f.jpeg.size)
                out[out.size - 2] = '\r'.code.toByte()
                out[out.size - 1] = '\n'.code.toByte()
                buf = out
                pos = 0
                return true
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return false }
        }
    }
}
