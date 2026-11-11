package info.dourok.voicebot.media

import android.media.AudioManager
import android.media.MediaPlayer

/**
 * What [LocalMusicPlayer] needs of a player: the few MediaPlayer calls it makes. Two kinds sit
 * behind it -- the platform MediaPlayer for songs, radio and podcasts ([MediaPlayerEngine]), and
 * [YtVideoEngine] for a YouTube video whose sound plays here while its pictures go to the display.
 *
 * Callbacks arrive on the main thread, as MediaPlayer's do.
 */
interface PlayerEngine {
    fun setListeners(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: (what: Int, extra: Int) -> Unit)
    fun prepareAsync()
    fun start()
    fun pause()
    val isPlaying: Boolean
    /** Whether [seekTo] does anything. */
    val canSeek: Boolean
    fun seekTo(ms: Int)
    val currentPosition: Int
    val duration: Int
    val audioSessionId: Int
    /** Why it failed, in words fit for the log, when it has. */
    val errorText: String
    fun release()
}

class MediaPlayerEngine(private val url: String) : PlayerEngine {
    private val p = MediaPlayer()

    init {
        p.setAudioStreamType(AudioManager.STREAM_MUSIC)
    }

    override fun setListeners(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: (Int, Int) -> Unit) {
        p.setOnPreparedListener { onPrepared() }
        p.setOnCompletionListener { onCompletion() }
        p.setOnErrorListener { _, what, extra -> onError(what, extra); true }
    }

    override fun prepareAsync() {
        p.setDataSource(url)
        p.prepareAsync()
    }

    override fun start() = p.start()
    override fun pause() = p.pause()
    override val isPlaying: Boolean get() = p.isPlaying
    override val canSeek: Boolean get() = true
    override fun seekTo(ms: Int) = p.seekTo(ms)
    override val currentPosition: Int get() = p.currentPosition
    override val duration: Int get() = p.duration
    override val audioSessionId: Int get() = p.audioSessionId
    override val errorText: String get() = ""

    override fun release() {
        p.reset()
        p.release()
    }
}
