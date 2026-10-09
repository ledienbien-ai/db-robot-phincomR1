# R1 Media Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route the Media Player tab's playback through the same server-side session
(`play_youtube.py`) the voice pipeline already uses, instead of a separate on-device ExoPlayer —
fixing missing equalizer, audible double-playback, and no shared queue, all three of which trace
back to having built two independent playback engines.

**Architecture:** New WebSocket message types (`media` client→server with an `action` field;
`media_queue`/`media_now_playing` server→client) let the web control panel drive and observe the
exact same `play_youtube.py` session a voice command would. `play_youtube.py`'s `_play()` is
refactored into a shared `_play_queue()` core used by both the search-driven voice path and a new
video-id-driven web path. The Android app's `ControlServer` sends/reads through `VoiceAssistant`
instead of a standalone `MediaPlayerController`, which is deleted along with the ExoPlayer
dependency and the `MediaCoordinator` event-bus (no longer needed — one player, nothing to
coordinate).

**Tech Stack:** Python (xiaozhi-server, `robot-esp32` repo), Kotlin/Hilt (xiaozhi-android repo),
existing xiaozhi WebSocket protocol (no new transport).

Spec: `docs/superpowers/specs/2026-07-19-r1-media-unification-design.md`

## Global Constraints

- No changes to `pytube_api.py` in this pass (search/download/progress-tracking already correct).
- Search stays client-side (`ControlServer` → `pytube_api.py` `/v3/search` directly) — not routed
  through the WS/server.
- Seek is display-only in this pass — no drag-to-seek (server only supports resume-at-paused-position, not arbitrary seek).
- No "previous" — `play_youtube.py` has no playback history, only forward/related-song queueing. The Prev button stays in the UI (visual parity with the reference design) but is permanently disabled.
- The two repos (`robot-esp32`, `xiaozhi-android`) change together; deploy both together at the end (restarting `xiaozhi-server` mid-plan would break the Android build's manual verification against it).

---

## Task 1: Refactor `play_youtube.py` — shared queue core, video-id play, real pause/resume

**Files:**
- Modify: `xiaozhi-esp32-server/main/xiaozhi-server/plugins_func/functions/play_youtube.py`

**Interfaces:**
- Produces: `async def _play_queue(conn, initial_queue: list[dict]) -> None` (core loop), `async def play_media_video(conn, video_id: str, title: str, artist: str, thumbnail: str) -> None` (web-triggered entry point, fire-and-forget task like `play_youtube`), `async def _send_now_playing(conn, song: dict | None, state: str, duration_s: int = 0, position_s: int = 0) -> None`, `async def _push_queue(conn, queue: list[dict]) -> None`, `conn._yt_web_paused` (new per-connection flag, mirrors `conn._yt_skip`) — consumed by Task 2's WS handler.

No automated test — confirmed in the original Media Player feature's exploration that this repo has
no test suite touching `play_youtube.py`; verified manually (device deployment) at the end, same as
before.

- [ ] **Step 1: Replace `_play`'s body with the extracted `_play_queue` + thin wrappers**

In `xiaozhi-esp32-server/main/xiaozhi-server/plugins_func/functions/play_youtube.py`, find the
entire `_play` function (from `async def _play(conn, query):` through its closing `finally` block,
i.e. lines 253-391 as of this plan) and replace it with:

```python
async def _play_queue(conn, initial_queue):
    """Core playback loop: session setup, endless-queue top-up (related songs), per-song
    play/wait/interrupt/pause handling. Shared by the voice-triggered search path (play_youtube)
    and the web-triggered specific-video path (play_media_video)."""
    my_session = time.time()
    try:
        conn._yt_session = my_session
        conn._yt_skip = 0
        conn._yt_web_paused = False
        cache_dir = _pytube_cache_dir(conn)

        queue = list(initial_queue)
        played = set()
        intent_llm = conn.intent_type == "intent_llm"
        turn_open = False

        def _open_turn():
            if intent_llm:
                conn.tts.tts_text_queue.put(
                    TTSMessageDTO(sentence_id=conn.sentence_id, sentence_type=SentenceType.FIRST, content_type=ContentType.ACTION)
                )

        def _close_turn():
            if intent_llm:
                conn.tts.tts_text_queue.put(
                    TTSMessageDTO(sentence_id=conn.sentence_id, sentence_type=SentenceType.LAST, content_type=ContentType.ACTION)
                )

        idx = 0
        first_turn = True
        resume = False  # True = replay the current song (after the user interrupted to ask something)
        await _send_music(conn, "start")   # -> tells the R1 app to turn on the music LED
        await _push_queue(conn, queue)
        while queue:
            if getattr(conn, "_yt_session", None) != my_session:
                break
            if idx >= MAX_SONGS:   # reached MAX_SONGS -> stop (don't play forever)
                logger.bind(tag=TAG).info(f"play_youtube: đủ {MAX_SONGS} bài -> dừng playlist")
                break
            song = queue.pop(0)
            vid = song.get("video_id")
            if not resume:
                if not vid or vid in played:
                    continue
                played.add(vid)
                # Top up when the queue is about to run dry -> plays UNLIMITED (a chain of related songs).
                if len(queue) < 2:
                    have = played | {q.get("video_id") for q in queue}
                    for r in await asyncio.to_thread(_related, vid, RELATED_COUNT):
                        if r.get("video_id") and r.get("video_id") not in have:
                            queue.append(r)
                    await _push_queue(conn, queue)

            title = song.get("title") or vid or ""
            artist = song.get("artist", "")

            # Open a turn if not already open (start of the playlist OR after being interrupted). After
            # being interrupted, do NOT create a new sentence_id (the old id already had LAST -> the
            # device would ignore a FIRST with a duplicate id).
            if not turn_open:
                if not first_turn:
                    # Do NOT create a new sentence_id (races with the stream that just finished answering
                    # -> FIRST gets dropped -> audio tagged with the old sid -> sendAudioMessage drops it).
                    # Just reuse the current conn.sentence_id for consistency.
                    conn.client_abort = False  # resume = a fresh play -> clear the abort flag, otherwise the pipeline drops it
                first_turn = False
                _open_turn()
                turn_open = True

            # Announce "loading" BEFORE downloading (the trailing period -> synth + play RIGHT AWAY, no wait for the download).
            if resume:
                announce = f"Phát tiếp bài {title} nha."
            elif idx == 0:
                announce = f"Đang tải bài {title}" + (f" của {artist}" if artist else "") + "."
            else:
                announce = f"Tiếp theo nha, đang tải bài {title}."
            resume = False
            conn.tts.store_tts_text(conn.sentence_id, announce)
            _say(conn, announce)
            logger.bind(tag=TAG).info(f"play_youtube: {announce}")
            await _send_now_playing(conn, song, state="downloading")

            path = await asyncio.to_thread(_download, vid, cache_dir)
            if getattr(conn, "_yt_session", None) != my_session:
                break
            if not path:
                _say(conn, f"Bài {title} tải lỗi, tao bỏ qua nha.")
                continue

            seek = song.get("_seek", 0)
            play_path = await asyncio.to_thread(_trim_mp3, path, seek) if seek > 0 else path
            _queue_file(conn, play_path)  # download done -> auto-plays (from second 'seek' if this is a resume)
            idx += 1
            dur = _mp3_duration(play_path) or _parse_dur(song.get("duration")) or DEFAULT_DUR
            await _send_now_playing(conn, song, state="playing", duration_s=dur, position_s=seek)
            result, waited = await _wait_song(conn, my_session, dur, song)
            if result == "stop":
                break
            if result == "skip":
                # "skip" via the tool has NO client_abort like a barge-in does -> the R1 keeps playing the
                # old song's already-buffered audio. Interrupt manually: clear the queue (stop feeding the
                # old song's audio frames) + tell the device to stop playing. The next song gets announced
                # + queued right after (reusing the current turn) -> plays over the old one. Do NOT
                # close/open a turn here (changing sentence_id here tends to make the device drop FIRST
                # -> losing the next song's audio).
                conn.clear_queues()
                try:
                    await conn.websocket.send(
                        json.dumps({"type": "tts", "state": "stop", "session_id": conn.session_id})
                    )
                except Exception:
                    pass
            if result == "paused":
                # Web UI paused (not a spoken interruption) -> wait for resume/next/stop without ever
                # entering the "listening for a question" flow _wait_qa_done drives.
                pos = max(0, seek + waited)
                cont = await _wait_paused(conn, my_session)
                if cont == "stop":
                    break
                if cont == "resume":
                    queue.insert(0, dict(song, _seek=pos))
                    resume = True
                # cont == "next" -> move to the next song (loop continues; a new turn is already open)
            if result == "interrupt":
                # User interrupted the music to ask/say something -> the abort already closed the music turn (sentence_id will change).
                turn_open = False
                cont = await _wait_qa_done(conn, my_session)
                if cont == "stop":
                    break
                if cont == "resume":
                    pos = max(0, seek + waited - 5)  # resume at the EXACT spot (minus 5s to offset announce/buffer delay)
                    queue.insert(0, dict(song, _seek=pos))
                    resume = True
                # cont == "next" -> move to the next song (a new turn opens on the next loop)

        # Close out the playback turn.
        if turn_open and getattr(conn, "_yt_session", None) == my_session:
            _close_turn()
    except Exception as e:
        logger.bind(tag=TAG).error(f"_play_queue: {e}")
    finally:
        # Turn off the music LED when this playlist stops (ran out of songs / "stop the music"). Do NOT
        # turn it off if a NEW music command has already taken over the session (that session turns its
        # own LED on) -> avoids turning it off by mistake.
        cur = getattr(conn, "_yt_session", None)
        if cur is None or cur == my_session:
            await _send_music(conn, "stop")
            await _send_now_playing(conn, None, state="stopped")


async def _play(conn, query):
    results = await asyncio.to_thread(_search, query, 3)
    if not results:
        _say(conn, f"Tao không tìm thấy bài {query} trên YouTube.")
        return
    await _play_queue(conn, [results[0]])


async def play_media_video(conn, video_id, title, artist, thumbnail):
    """Web-triggered play (Media Player tab): skip search, start the queue directly with this one
    video -- still tops up with related songs afterward, same endless-queue behavior a voice-triggered
    play gets."""
    await _play_queue(conn, [{
        "video_id": video_id, "title": title, "artist": artist, "thumbnail": thumbnail,
    }])
```

- [ ] **Step 2: `_wait_song` gains a "paused" branch and pushes periodic position updates**

Find:

```python
async def _wait_song(conn, session, dur):
    """Wait out the song (dur seconds). Returns (status, waited) — status: 'stop'/'next'/'interrupt';
    waited = seconds already played (to resume at the right spot)."""
    waited = 0
    while waited < dur:
        await asyncio.sleep(1)
        waited += 1
        if getattr(conn, "_yt_session", None) != session:
            return "stop", waited
        if getattr(conn, "_yt_skip", 0):
            conn._yt_skip = 0
            return "skip", waited        # "skip" via the tool -> must INTERRUPT the currently-playing audio
        if getattr(conn, "client_abort", False):
            return "interrupt", waited  # user interrupted the music to ask something
    return "next", waited                # song ended naturally -> move to the next one smoothly (no interrupt)
```

Replace with:

```python
async def _wait_song(conn, session, dur, song):
    """Wait out the song (dur seconds). Returns (status, waited) — status: 'stop'/'next'/'skip'/
    'interrupt'/'paused'; waited = seconds already played (to resume at the right spot)."""
    waited = 0
    while waited < dur:
        await asyncio.sleep(1)
        waited += 1
        if getattr(conn, "_yt_session", None) != session:
            return "stop", waited
        if getattr(conn, "_yt_skip", 0):
            conn._yt_skip = 0
            return "skip", waited        # "skip" via the tool -> must INTERRUPT the currently-playing audio
        if getattr(conn, "_yt_web_paused", False):
            return "paused", waited      # web UI paused -> distinct from client_abort, no "listening" flow
        if getattr(conn, "client_abort", False):
            return "interrupt", waited  # user interrupted the music to ask something
        if waited % 3 == 0:
            await _send_now_playing(conn, song, state="playing", duration_s=dur, position_s=waited)
    return "next", waited                # song ended naturally -> move to the next one smoothly (no interrupt)
```

- [ ] **Step 3: Add `_wait_paused`, `_send_now_playing`, `_push_queue`**

Find:

```python
async def _send_music(conn, state):
    """Tell the R1 app to change the LED when play_youtube starts/stops (type=music, state=start|stop)."""
    try:
        await conn.websocket.send(json.dumps({"type": "music", "state": state}))
    except Exception:
        pass
```

Replace with:

```python
async def _send_music(conn, state):
    """Tell the R1 app to change the LED when play_youtube starts/stops (type=music, state=start|stop)."""
    try:
        await conn.websocket.send(json.dumps({"type": "music", "state": state}))
    except Exception:
        pass


async def _wait_paused(conn, session):
    """After the web UI pauses (media_pause): wait for media_resume/media_next/media_stop or a new
    session, without ever entering the spoken-interruption flow _wait_qa_done drives -- pausing from
    a web page must not make the robot start listening. Returns 'stop'/'next'/'resume'."""
    for _ in range(3600):  # up to 1 hour paused before giving up
        await asyncio.sleep(1)
        if getattr(conn, "_yt_session", None) != session:
            return "stop"
        if getattr(conn, "_yt_skip", 0):
            conn._yt_skip = 0
            return "next"
        if not getattr(conn, "_yt_web_paused", False):
            return "resume"
    return "stop"


async def _send_now_playing(conn, song, state, duration_s=0, position_s=0):
    """Push the current now-playing snapshot to the client (Media Player tab's Now Playing card).
    state: 'downloading' | 'playing' | 'stopped'. song=None when state='stopped'."""
    try:
        await conn.websocket.send(json.dumps({
            "type": "media_now_playing",
            "state": state,
            "video_id": song.get("video_id") if song else None,
            "title": song.get("title", "") if song else "",
            "artist": song.get("artist", "") if song else "",
            "thumbnail": song.get("thumbnail", "") if song else "",
            "duration_s": duration_s,
            "position_s": position_s,
        }))
    except Exception:
        pass


async def _push_queue(conn, queue):
    """Push the current (upcoming) queue to the client (Media Player tab's list). Related-song
    top-ups only carry video_id/title/artist (see pytube_api's /v3/related) -- thumbnail/duration
    are blank for those until/unless the client looks them up, which control.html doesn't need to."""
    try:
        items = [{
            "video_id": s.get("video_id"),
            "title": s.get("title", ""),
            "artist": s.get("artist", ""),
            "thumbnail": s.get("thumbnail", ""),
            "duration": s.get("duration", ""),
        } for s in queue]
        await conn.websocket.send(json.dumps({"type": "media_queue", "items": items}))
    except Exception:
        pass
```

- [ ] **Step 4: Verify the file still parses**

Run: `cd /Users/lucnguyen/Documents/git/robot-esp32 && python3 -m py_compile xiaozhi-esp32-server/main/xiaozhi-server/plugins_func/functions/play_youtube.py`
Expected: no output, exit code 0.

- [ ] **Step 5: Commit**

```bash
cd /Users/lucnguyen/Documents/git/robot-esp32
git add xiaozhi-esp32-server/main/xiaozhi-server/plugins_func/functions/play_youtube.py
git commit -m "feat(play_youtube): shared _play_queue core, video-id play, real pause/resume"
```

---

## Task 2: New `media` WebSocket message type (server-side routing)

**Files:**
- Create: `xiaozhi-esp32-server/main/xiaozhi-server/core/handle/mediaHandle.py`
- Create: `xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textHandler/mediaMessageHandler.py`
- Modify: `xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageType.py`
- Modify: `xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageHandlerRegistry.py`

**Interfaces:**
- Consumes: `play_media_video` (Task 1).
- Produces: incoming WS `{"type":"media","action":"play"|"next"|"pause"|"resume"|"stop",...}` now
  dispatches to real behavior — consumed by Task 4's Android-side senders.

No automated test — same rationale as Task 1.

- [ ] **Step 1: Add the `MEDIA` message type**

In `xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageType.py`, find:

```python
class TextMessageType(Enum):
    """消息类型枚举"""
    HELLO = "hello"
    ABORT = "abort"
    LISTEN = "listen"
    IOT = "iot"
    MCP = "mcp"
    SERVER = "server"
    PING = "ping"
```

Replace with:

```python
class TextMessageType(Enum):
    """消息类型枚举"""
    HELLO = "hello"
    ABORT = "abort"
    LISTEN = "listen"
    IOT = "iot"
    MCP = "mcp"
    SERVER = "server"
    PING = "ping"
    MEDIA = "media"
```

- [ ] **Step 2: Create `mediaHandle.py`**

```python
"""Media Player tab (web control panel) play/next/pause/resume/stop -- routes web-triggered
playback through the same play_youtube.py session a voice command would use."""
from typing import TYPE_CHECKING

from plugins_func.functions.play_youtube import play_media_video

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__


async def handle_media_play(conn: "ConnectionHandler", video_id: str, title: str, artist: str, thumbnail: str):
    if not video_id or not conn.loop.is_running():
        return
    conn.loop.create_task(play_media_video(conn, video_id, title, artist, thumbnail))


def handle_media_next(conn: "ConnectionHandler"):
    if not getattr(conn, "_yt_session", None):
        return
    conn._yt_skip = 1


def handle_media_pause(conn: "ConnectionHandler"):
    if not getattr(conn, "_yt_session", None):
        return
    conn._yt_web_paused = True


def handle_media_resume(conn: "ConnectionHandler"):
    conn._yt_web_paused = False


def handle_media_stop(conn: "ConnectionHandler"):
    conn._yt_session = None
```

- [ ] **Step 3: Create `mediaMessageHandler.py`**

```python
from typing import Dict, Any

from core.handle.mediaHandle import (
    handle_media_play,
    handle_media_next,
    handle_media_pause,
    handle_media_resume,
    handle_media_stop,
)
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType


class MediaTextMessageHandler(TextMessageHandler):
    """Media消息处理器 -- Media Player tab (web control panel) play/next/pause/resume/stop."""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.MEDIA

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        action = msg_json.get("action")
        if action == "play":
            await handle_media_play(
                conn,
                msg_json.get("video_id", ""),
                msg_json.get("title", ""),
                msg_json.get("artist", ""),
                msg_json.get("thumbnail", ""),
            )
        elif action == "next":
            handle_media_next(conn)
        elif action == "pause":
            handle_media_pause(conn)
        elif action == "resume":
            handle_media_resume(conn)
        elif action == "stop":
            handle_media_stop(conn)
```

- [ ] **Step 4: Register the handler**

In `xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageHandlerRegistry.py`, find:

```python
from core.handle.textHandler.abortMessageHandler import AbortTextMessageHandler
from core.handle.textHandler.helloMessageHandler import HelloTextMessageHandler
from core.handle.textHandler.iotMessageHandler import IotTextMessageHandler
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.handle.textHandler.mcpMessageHandler import McpTextMessageHandler
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textHandler.serverMessageHandler import ServerTextMessageHandler
from core.handle.textHandler.pingMessageHandler import PingMessageHandler
```

Replace with:

```python
from core.handle.textHandler.abortMessageHandler import AbortTextMessageHandler
from core.handle.textHandler.helloMessageHandler import HelloTextMessageHandler
from core.handle.textHandler.iotMessageHandler import IotTextMessageHandler
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.handle.textHandler.mcpMessageHandler import McpTextMessageHandler
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textHandler.serverMessageHandler import ServerTextMessageHandler
from core.handle.textHandler.pingMessageHandler import PingMessageHandler
from core.handle.textHandler.mediaMessageHandler import MediaTextMessageHandler
```

Find:

```python
        handlers = [
            HelloTextMessageHandler(),
            AbortTextMessageHandler(),
            ListenTextMessageHandler(),
            IotTextMessageHandler(),
            McpTextMessageHandler(),
            ServerTextMessageHandler(),
            PingMessageHandler(),
        ]
```

Replace with:

```python
        handlers = [
            HelloTextMessageHandler(),
            AbortTextMessageHandler(),
            ListenTextMessageHandler(),
            IotTextMessageHandler(),
            McpTextMessageHandler(),
            ServerTextMessageHandler(),
            PingMessageHandler(),
            MediaTextMessageHandler(),
        ]
```

- [ ] **Step 5: Verify both new files parse and the registry still imports cleanly**

Run:
```bash
cd /Users/lucnguyen/Documents/git/robot-esp32
python3 -m py_compile xiaozhi-esp32-server/main/xiaozhi-server/core/handle/mediaHandle.py \
  xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textHandler/mediaMessageHandler.py \
  xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageType.py \
  xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageHandlerRegistry.py
```
Expected: no output, exit code 0.

- [ ] **Step 6: Commit**

```bash
cd /Users/lucnguyen/Documents/git/robot-esp32
git add xiaozhi-esp32-server/main/xiaozhi-server/core/handle/mediaHandle.py \
        xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textHandler/mediaMessageHandler.py \
        xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageType.py \
        xiaozhi-esp32-server/main/xiaozhi-server/core/handle/textMessageHandlerRegistry.py
git commit -m "feat(ws): route media play/next/pause/resume/stop messages to play_youtube session"
```

---

## Task 3: `Protocol.kt` — outgoing `media` senders

**Files:**
- Modify: `app/src/main/java/info/dourok/voicebot/protocol/Protocol.kt`

**Interfaces:**
- Produces: `suspend fun sendMediaPlay(videoId, title, artist, thumbnail)`, `suspend fun sendMediaNext()`, `suspend fun sendMediaPause()`, `suspend fun sendMediaResume()`, `suspend fun sendMediaStop()` — consumed by Task 5 (`VoiceAssistant.kt`).

- [ ] **Step 1: Add the senders**

In `app/src/main/java/info/dourok/voicebot/protocol/Protocol.kt`, find:

```kotlin
    suspend fun sendIotStates(states: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("states", JSONObject(states))
        }
        sendText(json.toString())
    }

    abstract fun dispose()
```

Replace with:

```kotlin
    suspend fun sendIotStates(states: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("states", JSONObject(states))
        }
        sendText(json.toString())
    }

    /** Media Player tab (web control panel) commands -- routed through the same play_youtube
     *  session a voice command would use (see xiaozhi-server's core/handle/mediaHandle.py). */
    suspend fun sendMediaPlay(videoId: String, title: String, artist: String, thumbnail: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "play")
            put("video_id", videoId)
            put("title", title)
            put("artist", artist)
            put("thumbnail", thumbnail)
        }
        sendText(json.toString())
    }

    suspend fun sendMediaNext() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "next")
        }
        sendText(json.toString())
    }

    suspend fun sendMediaPause() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "pause")
        }
        sendText(json.toString())
    }

    suspend fun sendMediaResume() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "resume")
        }
        sendText(json.toString())
    }

    suspend fun sendMediaStop() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "stop")
        }
        sendText(json.toString())
    }

    abstract fun dispose()
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
git add app/src/main/java/info/dourok/voicebot/protocol/Protocol.kt
git commit -m "feat(protocol): add media play/next/pause/resume/stop senders"
```

---

## Task 4: `MediaSessionState.kt` (new state holder)

**Files:**
- Create: `app/src/main/java/info/dourok/voicebot/domain/voice/MediaSessionState.kt`

**Interfaces:**
- Produces: `enum class MediaPlaybackState { IDLE, DOWNLOADING, PLAYING, STOPPED }`, `data class MediaQueueItem(videoId, title, artist, thumbnail, duration)`, `data class MediaNowPlaying(state, videoId, title, artist, thumbnail, durationS, positionS)`, `object MediaSessionState { val queue: StateFlow<List<MediaQueueItem>>; val nowPlaying: StateFlow<MediaNowPlaying>; fun updateQueue(...); fun updateNowPlaying(...) }` — consumed by Task 5 (`VoiceAssistant.kt`, writer) and Task 6 (`ControlServer.kt`, reader).

- [ ] **Step 1: Create the file**

```kotlin
package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaPlaybackState { IDLE, DOWNLOADING, PLAYING, STOPPED }

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

    fun updateQueue(items: List<MediaQueueItem>) { _queue.value = items }
    fun updateNowPlaying(np: MediaNowPlaying) { _nowPlaying.value = np }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
git add app/src/main/java/info/dourok/voicebot/domain/voice/MediaSessionState.kt
git commit -m "feat(media): add MediaSessionState (queue + now-playing state holder)"
```

---

## Task 5: `VoiceAssistant.kt` — handle incoming media messages, remove `MediaCoordinator`, add public senders

**Files:**
- Modify: `app/src/main/java/info/dourok/voicebot/domain/voice/VoiceAssistant.kt`

**Interfaces:**
- Consumes: `MediaSessionState` (Task 4), `Protocol.sendMedia*` (Task 3).
- Produces: `fun sendMediaPlay(videoId, title, artist, thumbnail)`, `fun sendMediaNext()`, `fun sendMediaPause()`, `fun sendMediaResume()`, `fun sendMediaStop()` (all plain, thread-safe-to-call-from-anywhere) — consumed by Task 6 (`ControlServer.kt`).

- [ ] **Step 1: Remove the `MediaCoordinator`-based web-play interrupt wiring**

Find:

```kotlin
            launch { TextCommands.flow.collect { onTextCommand(it) } }
            // A web-triggered play (Media Player tab) must win over whatever the voice pipeline is
            // doing -- same interrupt path as a wake-word barge-in.
            launch { MediaCoordinator.webPlayRequested.collect {
                Log.i(TAG, "webPlayRequested -> interruptPlayback t=${System.currentTimeMillis()}")
                interruptPlayback()
            } }
            launch { state.collect { refreshLed() } }   // LED bám theo trạng thái
```

Replace with:

```kotlin
            launch { TextCommands.flow.collect { onTextCommand(it) } }
            launch { state.collect { refreshLed() } }   // LED bám theo trạng thái
```

- [ ] **Step 2: Remove the `MediaCoordinator` reset in `backToWake()`**

Find:

```kotlin
        isAwake = false
        isMusic = false
        MediaCoordinator.voiceMusicActive.value = false
        chimeGuard(sounds.playStop(), STOP_CHIME_MARGIN_MS)   // chuông kết thúc phiên (timeout / tạm biệt) — server không gọi AI chào nữa
```

Replace with:

```kotlin
        isAwake = false
        isMusic = false
        chimeGuard(sounds.playStop(), STOP_CHIME_MARGIN_MS)   // chuông kết thúc phiên (timeout / tạm biệt) — server không gọi AI chào nữa
```

- [ ] **Step 3: Replace the `"music"` handler with `"media_queue"` / `"media_now_playing"`**

Find:

```kotlin
            "llm" -> json.optString("emotion").takeIf { it.isNotEmpty() }?.let { emotion.value = it }
            "music" -> {
                isMusic = json.optString("state") == "start"
                Log.i(TAG, "music state=${json.optString("state")} isMusic=$isMusic t=${System.currentTimeMillis()}")
                MediaCoordinator.voiceMusicActive.value = isMusic  // pause the web player + reflect it there
                refreshLed()
            }  // play_youtube -> LED nhạc
        }
    }
```

Replace with:

```kotlin
            "llm" -> json.optString("emotion").takeIf { it.isNotEmpty() }?.let { emotion.value = it }
            "media_queue" -> {
                val items = json.optJSONArray("items")
                val list = mutableListOf<MediaQueueItem>()
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val it = items.optJSONObject(i) ?: continue
                        list.add(MediaQueueItem(
                            videoId = it.optString("video_id", ""),
                            title = it.optString("title", ""),
                            artist = it.optString("artist", ""),
                            thumbnail = it.optString("thumbnail", ""),
                            duration = it.optString("duration", ""),
                        ))
                    }
                }
                MediaSessionState.updateQueue(list)
            }
            "media_now_playing" -> {
                val playbackState = when (json.optString("state")) {
                    "downloading" -> MediaPlaybackState.DOWNLOADING
                    "playing" -> MediaPlaybackState.PLAYING
                    else -> MediaPlaybackState.STOPPED
                }
                isMusic = playbackState == MediaPlaybackState.DOWNLOADING || playbackState == MediaPlaybackState.PLAYING
                refreshLed()  // play_youtube (voice OR web-triggered) -> LED nhạc
                val vid = json.optString("video_id", "")
                MediaSessionState.updateNowPlaying(MediaNowPlaying(
                    state = playbackState,
                    videoId = vid.ifEmpty { null },
                    title = json.optString("title", ""),
                    artist = json.optString("artist", ""),
                    thumbnail = json.optString("thumbnail", ""),
                    durationS = json.optInt("duration_s", 0),
                    positionS = json.optInt("position_s", 0),
                ))
            }
        }
    }

    /**
     * Media Player tab (web control panel) commands. Plain functions, safe to call from any
     * thread (ControlServer calls these from NanoHTTPD worker threads) -- no-ops if the voice
     * pipeline hasn't started yet (e.g. before the first app launch reaches ChatScreen).
     */
    fun sendMediaPlay(videoId: String, title: String, artist: String, thumbnail: String) {
        if (!::protocol.isInitialized || !::scope.isInitialized) return
        scope.launch { protocol.sendMediaPlay(videoId, title, artist, thumbnail) }
    }

    fun sendMediaNext() {
        if (!::protocol.isInitialized || !::scope.isInitialized) return
        scope.launch { protocol.sendMediaNext() }
    }

    fun sendMediaPause() {
        if (!::protocol.isInitialized || !::scope.isInitialized) return
        scope.launch { protocol.sendMediaPause() }
    }

    fun sendMediaResume() {
        if (!::protocol.isInitialized || !::scope.isInitialized) return
        scope.launch { protocol.sendMediaResume() }
    }

    fun sendMediaStop() {
        if (!::protocol.isInitialized || !::scope.isInitialized) return
        scope.launch { protocol.sendMediaStop() }
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileDebugKotlin`
Expected: FAIL — `MediaCoordinator` (deleted in Task 8) is still referenced by `MediaPlayerController.kt`/`MediaCoordinator.kt` themselves, and `ControlServer.kt` still references the old `mediaPlayer` field. This is expected at this point in the plan; Task 6-8 resolve it. Confirm the *specific* errors are only in those not-yet-touched files, not in `VoiceAssistant.kt` itself (no unresolved references to `MediaQueueItem`/`MediaNowPlaying`/`MediaPlaybackState`/`MediaSessionState` — those come from Task 4 and are already in place).

- [ ] **Step 5: Commit**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
git add app/src/main/java/info/dourok/voicebot/domain/voice/VoiceAssistant.kt
git commit -m "feat(voice): handle media_queue/media_now_playing, add media command senders"
```

---

## Task 6: `ControlServer.kt` — rewire `/api/media/*` through `VoiceAssistant`

**Files:**
- Modify: `app/src/main/java/info/dourok/voicebot/control/ControlServer.kt`

**Interfaces:**
- Consumes: `VoiceAssistant.sendMedia*` (Task 5), `MediaSessionState` (Task 4).
- Produces: same `/api/media/*` HTTP routes as before, now built on the unified session; new `/api/media/stop`.

- [ ] **Step 1: Inject `VoiceAssistant` instead of `MediaPlayerController`**

Find:

```kotlin
import info.dourok.voicebot.domain.voice.LedState
import info.dourok.voicebot.domain.voice.MediaCoordinator
import info.dourok.voicebot.domain.voice.MicTest
import info.dourok.voicebot.domain.voice.TextCommands
```

Replace with:

```kotlin
import info.dourok.voicebot.domain.voice.LedState
import info.dourok.voicebot.domain.voice.MediaPlaybackState
import info.dourok.voicebot.domain.voice.MediaSessionState
import info.dourok.voicebot.domain.voice.MicTest
import info.dourok.voicebot.domain.voice.TextCommands
import info.dourok.voicebot.domain.voice.VoiceAssistant
```

Find:

```kotlin
@Singleton
class ControlServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: AudioPlayback,
    private val led: LedIndicator,
    private val mediaPlayer: info.dourok.voicebot.media.MediaPlayerController,
) : NanoHTTPD(PORT) {
```

Replace with:

```kotlin
@Singleton
class ControlServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: AudioPlayback,
    private val led: LedIndicator,
    private val voiceAssistant: VoiceAssistant,
) : NanoHTTPD(PORT) {
```

- [ ] **Step 2: Rewire the media routes**

Find:

```kotlin
            "/api/media/search" -> json(handleMediaSearch(param(session, "q")))
            "/api/media/play" -> { handleMediaPlay(session); json("""{"ok":true}""") }
            "/api/media/pause" -> { mediaPlayer.pause(); json("""{"ok":true}""") }
            "/api/media/resume" -> { mediaPlayer.resume(); json("""{"ok":true}""") }
            "/api/media/seek" -> {
                param(session, "position_ms").toLongOrNull()?.let { mediaPlayer.seekTo(it) }
                json("""{"ok":true}""")
            }
            "/api/media/state" -> json(buildMediaState())
```

Replace with:

```kotlin
            "/api/media/search" -> json(handleMediaSearch(param(session, "q")))
            "/api/media/play" -> {
                val videoId = param(session, "video_id")
                if (videoId.isNotBlank()) {
                    voiceAssistant.sendMediaPlay(
                        videoId, param(session, "title"), param(session, "artist"), param(session, "thumbnail"),
                    )
                }
                json("""{"ok":true}""")
            }
            "/api/media/next" -> { voiceAssistant.sendMediaNext(); json("""{"ok":true}""") }
            "/api/media/pause" -> { voiceAssistant.sendMediaPause(); json("""{"ok":true}""") }
            "/api/media/resume" -> { voiceAssistant.sendMediaResume(); json("""{"ok":true}""") }
            "/api/media/stop" -> { voiceAssistant.sendMediaStop(); json("""{"ok":true}""") }
            "/api/media/state" -> json(buildMediaState())
```

- [ ] **Step 3: Replace `handleMediaPlay`/`buildMediaState` with the unified versions, add the download-progress watcher**

Find the entire `handleMediaPlay` function, the `startDownloadProgressPolling` function right after it, and `buildMediaState` right after that (from `private fun handleMediaPlay(session: IHTTPSession) {` through the end of the old `buildMediaState`'s closing `}`) and replace all three with:

```python
```

Actually replace with (this is the full block — find the exact boundaries below):

Find:

```kotlin
    /**
     * Ensures [video_id] is downloaded/cached via pytube_api's /v3/video/<id> (which blocks
     * synchronously on the download, potentially many seconds for a long song), then starts
     * playback. Runs on a background thread so the HTTP response to /api/media/play returns
     * immediately — the client discovers download/playback progress by polling /api/media/state,
     * not by waiting on this request.
     */
    private fun handleMediaPlay(session: IHTTPSession) {
        val videoId = param(session, "video_id")
        if (videoId.isBlank()) return
        val title = param(session, "title")
        val artist = param(session, "artist")
        val thumbnail = param(session, "thumbnail")
        mediaPlayer.markDownloading(videoId, title, artist, thumbnail)
        Thread {
            val base = Settings.pytubeBaseUrl.trimEnd('/')
            if (base.isBlank()) {
                mediaPlayer.markError(videoId, "pytube base URL not configured (Setup tab)")
                return@Thread
            }
            val progressThread = startDownloadProgressPolling(base, videoId)
            try {
                val url = "$base/v3/video/$videoId?device=${deviceMac()}"
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    when (val result = info.dourok.voicebot.media.parseVideoResponse(body, resp.code)) {
                        is info.dourok.voicebot.media.PytubeVideoResult.Ready -> {
                            val streamUrl = if (result.mp3Path.startsWith("http")) result.mp3Path
                                else "$base${result.mp3Path}"
                            mediaPlayer.play(videoId, streamUrl, title, artist, thumbnail)
                        }
                        is info.dourok.voicebot.media.PytubeVideoResult.Unavailable ->
                            mediaPlayer.markError(videoId, result.message)
                        is info.dourok.voicebot.media.PytubeVideoResult.Error ->
                            mediaPlayer.markError(videoId, result.message)
                    }
                }
            } catch (e: Exception) {
                mediaPlayer.markError(videoId, e.message ?: "network error")
            } finally {
                progressThread.interrupt()
            }
        }.start()
    }

    /**
     * Polls pytube_api's /v3/download_progress/<id> on a separate thread while the blocking
     * /v3/video/<id> download call (above) is in flight, so the UI can show a real percentage
     * instead of just an indeterminate spinner. pytube_api runs threaded=True, so this concurrent
     * request doesn't block the main download call. Best-effort: network hiccups are ignored, the
     * next tick just retries: this thread only ever improves the UI, never gates playback.
     */
    private fun startDownloadProgressPolling(base: String, videoId: String): Thread {
        val t = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(700)
                    val req = Request.Builder().url("$base/v3/download_progress/$videoId").get().build()
                    http.newCall(req).execute().use { resp ->
                        val percent = try {
                            JSONObject(resp.body?.string().orEmpty()).optInt("percent", -1)
                        } catch (e: Exception) { -1 }
                        if (percent >= 0) mediaPlayer.updateDownloadPercent(videoId, percent)
                    }
                }
            } catch (e: InterruptedException) {
                // normal shutdown once the main download call (above) finishes
            } catch (e: Exception) {
                // best-effort polling -- ignore and let the next tick retry
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun buildMediaState(): String {
        val s = mediaPlayer.state.value
        val voiceActive = MediaCoordinator.voiceMusicActive.value
        return JSONObject()
            .put("playing", s.playing || voiceActive)
            .put("video_id", s.videoId)
            // No title/artist is available for voice-triggered plays (play_youtube.py doesn't send
            // track metadata over the WS) -- show a generic label instead of leaving it blank.
            .put("title", if (voiceActive && s.videoId == null) "Đang phát qua giọng nói" else s.title)
            .put("artist", s.artist)
            .put("cover_url", s.coverUrl)
            .put("position_ms", s.positionMs)
            .put("duration_ms", s.durationMs)
            .put("download_state", s.downloadState.name.lowercase())
            .put("download_percent", s.downloadPercent)
            .put("error_message", s.errorMessage)
            .put("ended", s.ended)
            .put("voice_active", voiceActive)
            .toString()
    }
```

Replace with:

```kotlin
    /**
     * Best-effort download-percentage watcher: polls pytube_api's /v3/download_progress/<id>
     * whenever MediaSessionState's now-playing snapshot says a track is downloading. The download
     * itself is now triggered server-side (play_youtube.py's _download), not by this app -- this
     * just observes pytube_api's progress_hooks state, same endpoint as before, different trigger.
     * Runs for the lifetime of the app (started once from init{} below), not per-play.
     */
    @Volatile private var downloadPercent = -1
    @Volatile private var downloadPercentVideoId: String? = null

    init {
        Thread {
            while (true) {
                try {
                    Thread.sleep(700)
                    val np = MediaSessionState.nowPlaying.value
                    if (np.state == MediaPlaybackState.DOWNLOADING && np.videoId != null) {
                        if (np.videoId != downloadPercentVideoId) {
                            downloadPercentVideoId = np.videoId
                            downloadPercent = -1
                        }
                        val base = Settings.pytubeBaseUrl.trimEnd('/')
                        if (base.isNotBlank()) {
                            val req = Request.Builder().url("$base/v3/download_progress/${np.videoId}").get().build()
                            http.newCall(req).execute().use { resp ->
                                val percent = try {
                                    JSONObject(resp.body?.string().orEmpty()).optInt("percent", -1)
                                } catch (e: Exception) { -1 }
                                if (percent >= 0) downloadPercent = percent
                            }
                        }
                    } else {
                        downloadPercentVideoId = null
                        downloadPercent = -1
                    }
                } catch (e: Exception) {
                    // best-effort watcher -- ignore and let the next tick retry
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun buildMediaState(): String {
        val np = MediaSessionState.nowPlaying.value
        val queueArr = JSONArray()
        MediaSessionState.queue.value.forEach {
            queueArr.put(
                JSONObject()
                    .put("video_id", it.videoId).put("title", it.title)
                    .put("artist", it.artist).put("thumbnail", it.thumbnail)
                    .put("duration", it.duration)
            )
        }
        return JSONObject()
            .put("state", np.state.name.lowercase())
            .put("video_id", np.videoId)
            .put("title", np.title)
            .put("artist", np.artist)
            .put("cover_url", np.thumbnail)
            .put("duration_s", np.durationS)
            .put("position_s", np.positionS)
            .put("download_percent", if (np.state == MediaPlaybackState.DOWNLOADING) downloadPercent else -1)
            .put("queue", queueArr)
            .toString()
    }
```

- [ ] **Step 4: Remove the now-unused `handleMediaSearch` reference to `info.dourok.voicebot.media.parseSearchResults`?**

No change needed here — `handleMediaSearch` (search proxy to pytube_api) is unaffected by this plan
and still uses `info.dourok.voicebot.media.parseSearchResults`/`MediaSearchResult`, which are **not**
deleted (only `MediaPlayerController`/`MediaPlayerState`/`MediaCoordinator` are — see Task 8). Confirm
`handleMediaSearch` still compiles unchanged; it does not reference anything removed in this plan.

- [ ] **Step 5: Verify it compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileDebugKotlin`
Expected: FAIL — `MediaPlayerController`/`MediaCoordinator` still exist as files but are now
unreferenced by `ControlServer.kt`; compile errors, if any at this point, should only come from
`MediaPlayerController.kt`/`MediaCoordinator.kt` themselves no longer being wired anywhere, which
is fine (they're deleted in Task 8). Confirm no errors remain in `ControlServer.kt` itself.

- [ ] **Step 6: Commit**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
git add app/src/main/java/info/dourok/voicebot/control/ControlServer.kt
git commit -m "feat(control): rewire /api/media/* through the unified voice-pipeline session"
```

---

## Task 7: Delete `MediaPlayerController`, `MediaPlayerState`, `MediaCoordinator`, media3 dependency

**Files:**
- Delete: `app/src/main/java/info/dourok/voicebot/media/MediaPlayerController.kt`
- Delete: `app/src/main/java/info/dourok/voicebot/media/MediaPlayerState.kt`
- Delete: `app/src/main/java/info/dourok/voicebot/domain/voice/MediaCoordinator.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:** none produced; this task only removes now-unused code the earlier tasks stopped
referencing.

- [ ] **Step 1: Delete the three files**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
rm app/src/main/java/info/dourok/voicebot/media/MediaPlayerController.kt
rm app/src/main/java/info/dourok/voicebot/media/MediaPlayerState.kt
rm app/src/main/java/info/dourok/voicebot/domain/voice/MediaCoordinator.kt
```

(`app/src/main/java/info/dourok/voicebot/media/MediaSearchResult.kt` and `PytubeVideoResult.kt` —
and their tests — are **not** deleted; search still uses them.)

- [ ] **Step 2: Remove the media3 Gradle dependencies**

In `app/build.gradle.kts`, find:

```kotlin
    // Embedded HTTP server for the on-device control panel (EQ / settings / chat).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Local playback engine for the Media Player tab (independent of the voice pipeline's
    // TTS-interleaved audio queue). media3-session registers a MediaSession so playback also
    // surfaces as Android's own quick-settings media card.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
```

Replace with:

```kotlin
    // Embedded HTTP server for the on-device control panel (EQ / settings / chat).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
```

- [ ] **Step 3: Verify the full test suite + compile**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` or the same pre-existing 6 `DeviceInfoKtTest` failures as every prior
pass in this project (unrelated, present since the initial commit) — no new failures, and no
compile errors anywhere (this confirms nothing outside the files touched in Tasks 5-6 still
referenced the deleted classes).

- [ ] **Step 4: Commit**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
git add -A app/build.gradle.kts app/src/main/java/info/dourok/voicebot/media/ app/src/main/java/info/dourok/voicebot/domain/voice/
git commit -m "refactor(media): remove ExoPlayer-based player + media3 dep, now unified with voice pipeline"
```

---

## Task 8: `control.html` — unified Now Playing / queue rendering

**Files:**
- Modify: `app/src/main/assets/control.html`

**Interfaces:**
- Consumes: `/api/media/state`'s new shape (Task 6): `{state, video_id, title, artist, cover_url, duration_s, position_s, download_percent, queue: [...]}`.

No automated test — same rationale as the original Media Player feature (no JS test framework in
this repo). Step 5 below is manual verification on the physical device.

- [ ] **Step 1: Replace the entire Media Player JS block**

Find (the whole block from the `// ── Media Player ──` comment through the line right before
`restoreMediaSearchState();`):

```javascript
// ── Media Player ──────────────────────────────────
let mediaResultsCache=[];
let mediaSearchTimer=null;
let mediaState={playing:false,video_id:null,download_state:'idle'};
let mediaPollTimer=null;
let npSeekDragging=false;
let lastAutoAdvanced=null;

// Persist the last search (query + results) across page reloads -- searching is a network call,
// losing it on every refresh is annoying and pointless since the results themselves don't change
// from one reload to the next.
function saveMediaSearchState(){
  try{localStorage.setItem('r1-media-search',JSON.stringify({q:$('mediaSearch').value,results:mediaResultsCache}));}catch(e){}
}
function restoreMediaSearchState(){
  try{
    const raw=localStorage.getItem('r1-media-search');
    if(!raw)return;
    const s=JSON.parse(raw);
    $('mediaSearch').value=s.q||'';
    mediaResultsCache=s.results||[];
    renderMediaResults();
  }catch(e){}
}

function onMediaSearchInput(){
  clearTimeout(mediaSearchTimer);
  const q=$('mediaSearch').value.trim();
  mediaSearchTimer=setTimeout(()=>mediaSearch(q),400);
}
async function mediaSearch(q){
  if(!q){mediaResultsCache=[];renderMediaResults();saveMediaSearchState();return;}
  try{
    const j=await(await fetch('/api/media/search?q='+encodeURIComponent(q))).json();
    mediaResultsCache=j.ok?j.results:[];
    renderMediaResults(j.ok?null:j.error);
    saveMediaSearchState();
  }catch(e){mediaResultsCache=[];renderMediaResults('Lỗi tìm kiếm');}
}
function renderMediaResults(err){
  const el=$('mediaResults');
  if(err){el.innerHTML=`<div class="empty">✕ ${err}</div>`;return;}
  if(!mediaResultsCache.length){el.innerHTML='<div class="empty">🎵 Tìm bài hát để bắt đầu</div>';return;}
  el.innerHTML=mediaResultsCache.map(r=>`
    <div class="list-item" data-vid="${r.video_id}">
      <img class="li-cover" src="${r.thumbnail}" alt="">
      <div class="li-info">
        <div class="li-title">${r.title.replace(/</g,'&lt;')}</div>
        <div class="li-sub">${r.artist}${r.duration?(' · '+r.duration):''}</div>
      </div>
      <div class="li-play-wrap" data-vid="${r.video_id}">
        <svg class="li-ring" viewBox="0 0 40 40"><circle cx="20" cy="20" r="17"/></svg>
        <button class="li-play" onclick="mediaItemTap('${r.video_id}')">▶</button>
      </div>
    </div>`).join('');
  applyMediaButtonStates();
}
// Tapping the currently-playing (and ready) item's button toggles play/pause in place; tapping any
// other item starts it. Keeps the button meaningful without moving the item around in the list.
function mediaItemTap(videoId){
  if(videoId===mediaState.video_id&&mediaState.download_state==='ready')mediaToggle();
  else mediaPlay(videoId);
}
const LI_RING_CIRC=2*Math.PI*17;  // matches the r=17 <circle> in the .li-ring SVG markup
function applyMediaButtonStates(){
  document.querySelectorAll('.li-play-wrap').forEach(w=>{
    const active=w.dataset.vid===mediaState.video_id;
    const downloading=active&&mediaState.download_state==='downloading';
    const pct=mediaState.download_percent;
    const determinate=downloading&&pct>=0&&pct<=100;
    w.classList.toggle('downloading',downloading);
    w.classList.toggle('determinate',determinate);
    w.classList.toggle('error',active&&mediaState.download_state==='error');
    const circle=w.querySelector('.li-ring circle');
    if(determinate){
      circle.style.strokeDasharray=LI_RING_CIRC+' '+LI_RING_CIRC;
      circle.style.strokeDashoffset=String(LI_RING_CIRC*(1-pct/100));
    }else{
      circle.style.strokeDasharray='';circle.style.strokeDashoffset='';
    }
    w.querySelector('.li-play').textContent=(active&&mediaState.playing)?'⏸':'▶';
  });
  document.querySelectorAll('.list-item').forEach(row=>{
    row.classList.toggle('playing',row.dataset.vid===mediaState.video_id&&mediaState.playing);
  });
}
function mediaPlay(videoId){
  const r=mediaResultsCache.find(x=>x.video_id===videoId);
  if(!r)return;
  const p=`video_id=${encodeURIComponent(videoId)}&title=${encodeURIComponent(r.title)}&artist=${encodeURIComponent(r.artist)}&thumbnail=${encodeURIComponent(r.thumbnailUrl||r.thumbnail||'')}`;
  fetch('/api/media/play?'+p,{method:'POST'});
}
function mediaToggle(){fetch(mediaState.playing?'/api/media/pause':'/api/media/resume',{method:'POST'});}
function mediaPrev(){mediaStep(-1);}
function mediaNext(){mediaStep(1);}
function mediaStep(dir){
  const i=mediaResultsCache.findIndex(x=>x.video_id===mediaState.video_id);
  if(i<0)return;
  const j=i+dir;
  if(j<0||j>=mediaResultsCache.length)return;
  mediaPlay(mediaResultsCache[j].video_id);
}
function renderNowPlaying(s){
  mediaState=s;
  // Track finished on its own -> advance to the next item in the currently displayed list (once
  // per finish; lastAutoAdvanced guards against re-triggering on every poll tick while ended stays true).
  if(s.ended&&s.video_id&&s.video_id!==lastAutoAdvanced){
    lastAutoAdvanced=s.video_id;
    mediaStep(1);
  }
  const np=$('nowPlaying');
  if(!s.video_id&&!s.voice_active){np.hidden=true;applyMediaButtonStates();return;}
  np.hidden=false;
  if(s.voice_active&&!s.video_id){
    // Playing via voice chat (play_youtube) -- no per-track metadata or seek/prev/next available,
    // just reflect that something is playing so it isn't a silent gap in the panel.
    $('npCover').src='';
    $('npTitle').textContent=s.title||'🎵 Đang phát qua giọng nói';
    $('npArtist').textContent='';
    $('npPlay').textContent='⏸';
    $('npSeek').max=0;$('npSeek').value=0;
    $('npPrev').disabled=true;$('npNext').disabled=true;
    applyMediaButtonStates();
    return;
  }
  $('npCover').src=s.cover_url||'';
  $('npTitle').textContent=s.title||'';
  $('npArtist').textContent=s.artist||'';
  $('npPlay').textContent=s.playing?'⏸':'▶';
  if(!npSeekDragging){
    $('npSeek').max=s.duration_ms||0;
    $('npSeek').value=s.position_ms||0;
  }
  const i=mediaResultsCache.findIndex(x=>x.video_id===s.video_id);
  $('npPrev').disabled=(i<=0);
  $('npNext').disabled=(i<0||i>=mediaResultsCache.length-1);
  applyMediaButtonStates();
}
$('npSeek').addEventListener('pointerdown',()=>npSeekDragging=true);
$('npSeek').addEventListener('change',e=>{
  fetch('/api/media/seek?position_ms='+Math.round(e.target.value),{method:'POST'});
  npSeekDragging=false;
});
function mediaPollStart(){if(mediaPollTimer)return;mediaPollTick();mediaPollTimer=setInterval(mediaPollTick,1000);}
function mediaPollStop(){clearInterval(mediaPollTimer);mediaPollTimer=null;}
async function mediaPollTick(){
  try{renderNowPlaying(await(await fetch('/api/media/state')).json());}catch(e){}
}
restoreMediaSearchState();
```

Replace with:

```javascript
// ── Media Player ──────────────────────────────────
let mediaSearchResultsCache=[];  // last manual search results (persisted across reloads)
let mediaResultsCache=[];        // whichever list is CURRENTLY displayed (search results OR the live server queue)
let mediaSearchTimer=null;
let mediaState={state:'idle',video_id:null,download_percent:-1};
let mediaPollTimer=null;
let lastDisplayedSig='';

// Persist the last search (query + results) across page reloads -- searching is a network call,
// losing it on every refresh is annoying and pointless since the results themselves don't change
// from one reload to the next.
function saveMediaSearchState(){
  try{localStorage.setItem('r1-media-search',JSON.stringify({q:$('mediaSearch').value,results:mediaSearchResultsCache}));}catch(e){}
}
function restoreMediaSearchState(){
  try{
    const raw=localStorage.getItem('r1-media-search');
    if(!raw)return;
    const s=JSON.parse(raw);
    $('mediaSearch').value=s.q||'';
    mediaSearchResultsCache=s.results||[];
    mediaResultsCache=mediaSearchResultsCache;
    lastDisplayedSig=JSON.stringify(mediaResultsCache);
    renderMediaResults();
  }catch(e){}
}

function onMediaSearchInput(){
  clearTimeout(mediaSearchTimer);
  const q=$('mediaSearch').value.trim();
  mediaSearchTimer=setTimeout(()=>mediaSearch(q),400);
}
async function mediaSearch(q){
  if(!q){mediaSearchResultsCache=[];mediaResultsCache=[];lastDisplayedSig=JSON.stringify(mediaResultsCache);renderMediaResults();saveMediaSearchState();return;}
  try{
    const j=await(await fetch('/api/media/search?q='+encodeURIComponent(q))).json();
    mediaSearchResultsCache=j.ok?j.results:[];
    // Show immediately -- if a session happens to be active, the next poll tick (within 1s) swaps
    // the list back to the live queue, which is a fine, self-correcting, rare-case blip.
    mediaResultsCache=mediaSearchResultsCache;
    lastDisplayedSig=JSON.stringify(mediaResultsCache);
    renderMediaResults(j.ok?null:j.error);
    saveMediaSearchState();
  }catch(e){mediaSearchResultsCache=[];mediaResultsCache=[];renderMediaResults('Lỗi tìm kiếm');}
}
function renderMediaResults(err){
  const el=$('mediaResults');
  if(err){el.innerHTML=`<div class="empty">✕ ${err}</div>`;return;}
  if(!mediaResultsCache.length){el.innerHTML='<div class="empty">🎵 Tìm bài hát để bắt đầu</div>';return;}
  el.innerHTML=mediaResultsCache.map(r=>`
    <div class="list-item" data-vid="${r.video_id}">
      <img class="li-cover" src="${r.thumbnail}" alt="">
      <div class="li-info">
        <div class="li-title">${(r.title||'').replace(/</g,'&lt;')}</div>
        <div class="li-sub">${r.artist||''}${r.duration?(' · '+r.duration):''}</div>
      </div>
      <div class="li-play-wrap" data-vid="${r.video_id}">
        <svg class="li-ring" viewBox="0 0 40 40"><circle cx="20" cy="20" r="17"/></svg>
        <button class="li-play" onclick="mediaItemTap('${r.video_id}')">▶</button>
      </div>
    </div>`).join('');
  applyMediaButtonStates();
}
// Tapping the currently-playing item's button toggles play/pause in place; tapping any other item
// (from search results or the live queue) starts it.
function mediaItemTap(videoId){
  if(videoId===mediaState.video_id&&(mediaState.state==='playing'||mediaState.state==='downloading'))mediaToggle();
  else mediaPlay(videoId);
}
const LI_RING_CIRC=2*Math.PI*17;  // matches the r=17 <circle> in the .li-ring SVG markup
function applyMediaButtonStates(){
  document.querySelectorAll('.li-play-wrap').forEach(w=>{
    const active=w.dataset.vid===mediaState.video_id;
    const downloading=active&&mediaState.state==='downloading';
    const pct=mediaState.download_percent;
    const determinate=downloading&&pct>=0&&pct<=100;
    w.classList.toggle('downloading',downloading);
    w.classList.toggle('determinate',determinate);
    const circle=w.querySelector('.li-ring circle');
    if(determinate){
      circle.style.strokeDasharray=LI_RING_CIRC+' '+LI_RING_CIRC;
      circle.style.strokeDashoffset=String(LI_RING_CIRC*(1-pct/100));
    }else{
      circle.style.strokeDasharray='';circle.style.strokeDashoffset='';
    }
    w.querySelector('.li-play').textContent=(active&&mediaState.state==='playing')?'⏸':'▶';
  });
  document.querySelectorAll('.list-item').forEach(row=>{
    row.classList.toggle('playing',row.dataset.vid===mediaState.video_id&&mediaState.state==='playing');
  });
}
function mediaPlay(videoId){
  const r=mediaResultsCache.find(x=>x.video_id===videoId);
  if(!r)return;
  const p=`video_id=${encodeURIComponent(videoId)}&title=${encodeURIComponent(r.title||'')}&artist=${encodeURIComponent(r.artist||'')}&thumbnail=${encodeURIComponent(r.thumbnail||'')}`;
  fetch('/api/media/play?'+p,{method:'POST'});
}
function mediaToggle(){fetch(mediaState.state==='playing'?'/api/media/pause':'/api/media/resume',{method:'POST'});}
function mediaNext(){fetch('/api/media/next',{method:'POST'});}
function renderNowPlaying(s){
  mediaState=s;
  // A session is active -> show the live server-pushed queue as "the list" (so "what's next" is
  // visible regardless of whether it started from voice or the web); otherwise show the last
  // manual search results. Only re-render the list when it actually changes (not every poll tick)
  // to avoid flicker/scroll-jank; button/ring states still refresh every tick via applyMediaButtonStates.
  const sessionActive=(s.state==='playing'||s.state==='downloading');
  const list=sessionActive?(s.queue||[]):mediaSearchResultsCache;
  const sig=JSON.stringify(list);
  if(sig!==lastDisplayedSig){lastDisplayedSig=sig;mediaResultsCache=list;renderMediaResults();}
  else{applyMediaButtonStates();}
  const np=$('nowPlaying');
  if(!sessionActive){np.hidden=true;return;}
  np.hidden=false;
  $('npCover').src=s.cover_url||'';
  $('npTitle').textContent=s.title||'';
  $('npArtist').textContent=s.artist||'';
  $('npPlay').textContent=s.state==='playing'?'⏸':'▶';
  $('npSeek').max=s.duration_s||0;
  $('npSeek').value=s.position_s||0;
  $('npPrev').disabled=true;  // no "previous" concept server-side (only forward/related-song queue)
  $('npNext').disabled=!sessionActive;
}
function mediaPollStart(){if(mediaPollTimer)return;mediaPollTick();mediaPollTimer=setInterval(mediaPollTick,1000);}
function mediaPollStop(){clearInterval(mediaPollTimer);mediaPollTimer=null;}
async function mediaPollTick(){
  try{renderNowPlaying(await(await fetch('/api/media/state')).json());}catch(e){}
}
restoreMediaSearchState();
```

- [ ] **Step 2: Make the seek bar display-only**

Find:

```html
      <input type="range" id="npSeek" min="0" max="0" value="0">
```

Replace with:

```html
      <input type="range" id="npSeek" min="0" max="0" value="0" disabled>
```

- [ ] **Step 3: Verify JS syntax, HTML tag balance, and every `$()` id reference resolves**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
python3 -c "
import re
html = open('app/src/main/assets/control.html', encoding='utf-8').read()
blocks = re.findall(r'<script>(.*?)</script>', html, re.S)
open('/tmp/control_script_check.js', 'w', encoding='utf-8').write(blocks[-1])
"
node --check /tmp/control_script_check.js && echo "JS SYNTAX OK"
python3 - <<'PYEOF'
from html.parser import HTMLParser
class Checker(HTMLParser):
    def __init__(self):
        super().__init__(); self.stack=[]; self.void={'meta','link','input','img','br','hr'}
    def handle_starttag(self, tag, attrs):
        if tag in self.void: return
        self.stack.append((tag, self.getpos()))
    def handle_endtag(self, tag):
        if not self.stack: print(f"MISMATCH: closing </{tag}> at {self.getpos()} stack empty"); return
        top,pos=self.stack[-1]
        if top!=tag: print(f"MISMATCH: closing </{tag}> at {self.getpos()} top is <{top}> opened {pos}")
        else: self.stack.pop()
html=open('app/src/main/assets/control.html',encoding='utf-8').read()
c=Checker(); c.feed(html)
print("UNCLOSED:",c.stack) if c.stack else print("ALL TAGS BALANCED")
PYEOF
python3 - <<'PYEOF'
import re
html = open('/Users/lucnguyen/Documents/git/xiaozhi-android/app/src/main/assets/control.html', encoding='utf-8').read()
ids_defined = set(re.findall(r'id="([^"]+)"', html))
ids_used = set(re.findall(r"\$\('([^']+)'\)", html))
missing = sorted(x for x in ids_used if x not in ids_defined)
print("defined:", len(ids_defined), "used via $():", len(ids_used), "MISSING:", missing)
PYEOF
```
Expected: `JS SYNTAX OK`, `ALL TAGS BALANCED`, `MISSING: []`.

- [ ] **Step 4: Verify the app still builds**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (this exercises `mergeDebugAssets`/`compressDebugAssets` picking up the
edited `control.html` without error, alongside the full Kotlin/native build from Task 7).

- [ ] **Step 5: Commit**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
git add app/src/main/assets/control.html
git commit -m "feat(media): render the unified session (real now-playing, live queue, display-only seek)"
```

---

## Task 9: Deploy and manually verify (both repos)

**Files:** none (deployment + verification only).

- [ ] **Step 1: Restart `xiaozhi-server`**

This drops the R1's live WebSocket connection momentarily (it reconnects on its own) — matches how
`pytube_api`'s restart was already handled earlier in this project.

```bash
launchctl kickstart -k gui/$(id -u)/com.user.robot-xiaozhi
sleep 2
ps aux | grep -i xiaozhi | grep -v grep
```
Expected: a new PID for `app.py` running.

- [ ] **Step 2: Build and install the updated APK on the R1**

```bash
cd /Users/lucnguyen/Documents/git/xiaozhi-android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
ADB=/Users/lucnguyen/Library/Android/sdk/platform-tools/adb
$ADB connect 10.25.113.209:5555
$ADB -s 10.25.113.209:5555 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/rc.apk
```

Then install over the R1's WebSocket shell (plain `adb shell pm install` has repeatedly hung on
this device mid-`dex2oat` in this project — the WebSocket-shell path is the proven reliable one):

```bash
cd /Users/lucnguyen/Documents/git/robot-esp32
R1_IP=10.25.113.209 services/.venv/bin/python services/r1sh.py 'pm install -r /data/local/tmp/rc.apk' 180
```
Expected: `Success`. Then:
```bash
R1_IP=10.25.113.209 services/.venv/bin/python services/r1sh.py \
  'dumpsys package info.dourok.voicebot.dev | grep lastUpdateTime; rm -f /data/local/tmp/rc.apk; am start -n info.dourok.voicebot.dev/info.dourok.voicebot.MainActivity' 8
curl -s -m 8 -o /dev/null -w "HTTP %{http_code}\n" http://10.25.113.209:8088/
```
Expected: a fresh `lastUpdateTime` and `HTTP 200`.

- [ ] **Step 3: Manual verification checklist**

1. Ask the assistant to play a song via voice ("mở nhạc X"). Open the Media Player tab: the Now
   Playing card shows the real title/artist (not a placeholder), and the queue list shows the
   song + related songs queued after it.
2. While that's playing, tap Play on a different song in the web tab's search results. Confirm the
   voice-triggered song **stops** (not overlapping) and the new one starts.
3. Confirm the equalizer (Settings tab) audibly affects whichever track is playing, regardless of
   whether it was started from voice or the web.
4. Tap Pause in the web UI. Confirm playback stops **without** the robot's LED/behavior indicating
   it's now listening for a question. Tap Resume: confirm it picks up at roughly the paused
   position, not from the start.
5. Tap Next in the web UI mid-song (started either via voice or web). Confirm it advances to the
   next queued song.
6. Ask the assistant a question while a web-triggered song is playing (voice barge-in). Confirm
   this still works as it did before this change (music pauses/interrupts, question gets answered,
   music resumes) — this exercises `_wait_qa_done`, untouched by this plan, but now reachable from
   a web-triggered session too.
7. Confirm Settings/Setup tabs and the chat drawer still work unchanged (regression check).

---

## Self-Review Notes

- **Spec coverage:** New WS messages (client→server `media`+action, server→client
  `media_queue`/`media_now_playing`) → Tasks 1-3. `play_youtube.py` refactor + real pause/resume →
  Task 1. Android senders/state/routing → Tasks 3-6. ExoPlayer/MediaCoordinator removal → Task 7.
  `control.html` unified rendering + display-only seek → Task 8. Manual verification of all three
  reported bugs (equalizer, overlap, shared queue) → Task 9 Step 3.
- **Placeholder scan:** no TBD/TODO; every step has complete, runnable code.
- **Type consistency:** `MediaPlaybackState`/`MediaQueueItem`/`MediaNowPlaying` (Task 4) match the
  field names `VoiceAssistant.kt`'s message handlers populate (Task 5) match what `ControlServer`'s
  `buildMediaState()` reads (Task 6) match the JSON keys `control.html` reads (Task 8) — `state`,
  `video_id`, `title`, `artist`, `thumbnail`/`cover_url`, `duration_s`, `position_s`,
  `download_percent`, `queue` used identically end to end. Server-side `media_now_playing`/
  `media_queue` payload keys (Task 1) match what `VoiceAssistant.kt` parses (Task 5) exactly.
- **Deletion safety:** Task 7 confirms via full test-suite run that nothing outside the files
  touched in Tasks 5-6 still references `MediaPlayerController`/`MediaPlayerState`/
  `MediaCoordinator` before they're deleted — `MediaSearchResult`/`PytubeVideoResult` (search-only,
  untouched) are explicitly called out as **not** deleted.
