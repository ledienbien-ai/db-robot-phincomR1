package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaPlaybackState { IDLE, DOWNLOADING, PLAYING, PAUSED, STOPPED }

data class MediaQueueItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration: String,
)

data class MediaNowPlaying(
    val state: MediaPlaybackState = MediaPlaybackState.IDLE,
    val videoId: String? = null,
    val title: String = "",
    val artist: String = "",
    val thumbnail: String = "",
    val durationS: Int = 0,
    val positionS: Int = 0,
)

/**
 * Single source of truth for the unified media session. Server-driven (play_youtube.py pushes
 * media_queue / media_now_playing over the WS) -- written by [VoiceAssistant]'s
 * handleServerMessage, read by ControlServer for /api/media/state. Replaces the old on-device
 * MediaPlayerController + MediaCoordinator now that there's only one playback engine (the voice
 * pipeline's), so there's nothing left to coordinate between two players.
 */
object MediaSessionState {
    private val _queue = MutableStateFlow<List<MediaQueueItem>>(emptyList())
    val queue: StateFlow<List<MediaQueueItem>> = _queue.asStateFlow()

    private val _nowPlaying = MutableStateFlow(MediaNowPlaying())
    val nowPlaying: StateFlow<MediaNowPlaying> = _nowPlaying.asStateFlow()

    /**
     * When the session last ENTERED the paused state, or 0 when it is not paused.
     *
     * Needed because "is a song paused" and "was a song just paused" are different questions and
     * only the second one says anything about the `tts stop` frame arriving now -- see
     * [SessionEnd.ttsStopEndsReply]. Elapsed-realtime rather than wall clock: this is a duration
     * between two local events, and a clock the user can set backwards would make it negative.
     */
    @Volatile
    var pausedAtMs: Long = 0L
        private set

    fun updateQueue(items: List<MediaQueueItem>) { _queue.value = items }

    fun updateNowPlaying(np: MediaNowPlaying, nowMs: Long = System.nanoTime() / 1_000_000) {
        val wasPaused = _nowPlaying.value.state == MediaPlaybackState.PAUSED
        val isPaused = np.state == MediaPlaybackState.PAUSED
        // Only a transition INTO paused restarts the clock. A paused session re-pushed by the
        // server (a position update, say) must not keep pretending the pause is fresh.
        pausedAtMs = when {
            isPaused && !wasPaused -> nowMs
            isPaused -> pausedAtMs
            else -> 0L
        }
        _nowPlaying.value = np
    }

    /** How long the session has been paused, or [Long.MAX_VALUE] when it is not paused at all. */
    fun msSincePause(nowMs: Long = System.nanoTime() / 1_000_000): Long =
        if (pausedAtMs == 0L) Long.MAX_VALUE else nowMs - pausedAtMs

    /**
     * Drop everything when the channel to the server closes. This state is only ever pushed BY the
     * server, so a disconnect (server restart, wifi drop) would otherwise freeze the last snapshot
     * on screen -- the panel would keep showing a song as "playing" with nothing coming out of the
     * speaker, which is indistinguishable from a real playback bug.
     */
    fun clear() {
        _queue.value = emptyList()
        _nowPlaying.value = MediaNowPlaying()
        pausedAtMs = 0L
    }
}
