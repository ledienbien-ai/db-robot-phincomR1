# R1 Media Player — Unify Web and Voice Playback

Date: 2026-07-19

## Problem

The Media Player tab (`docs/superpowers/specs/2026-07-19-r1-media-player-design.md`) was built as
a fully independent on-device player (ExoPlayer, `MediaPlayerController`), deliberately decoupled
from the voice pipeline's playback (`play_youtube.py` streaming TTS-interleaved Opus frames over
the WebSocket, played by `OpusAudioPlayback`). Real-device testing surfaced three problems that all
trace back to that decoupling, not to bugs in the mutual-exclusion patch applied on top of it:

1. **No equalizer.** `OpusAudioPlayback`/`OpusStreamPlayer` already has a working `Equalizer` audio
   effect wired to `Settings.eqEnabled`/`Settings.eqBands`. `MediaPlayerController`'s ExoPlayer
   instance has none — web-triggered tracks ignore the EQ settings entirely.
2. **Still audible overlap.** Two independent players, coordinated only by best-effort event-bus
   signals (`MediaCoordinator`) and network-latency-bound abort messages, cannot guarantee mutual
   exclusion. The chosen fix isn't "debug the race harder" — it's "there is only one player."
3. **No shared queue.** Asking the assistant to play a song and tapping Play in the web tab produce
   two independent playlists with no relationship to each other, contradicting the actual want:
   one now-playing/queue state visible and controllable from both surfaces.

## Goal

Web-triggered playback and voice-triggered playback become the **same session**, driven by the
same server-side `play_youtube.py` logic and the same client-side `OpusAudioPlayback`. The Media
Player tab becomes a remote control + queue viewer for that one session, not a second player.

## Non-goals

- Search stays exactly as it is today (`ControlServer` proxying to `pytube_api.py`'s `/v3/search`)
  — it's stateless and has no reason to move server-side.
- Download-progress polling (`/v3/download_progress/<id>`, added in the prior pass) is unaffected —
  it polls `pytube_api.py` directly regardless of who triggered the download.
- No changes to `pytube_api.py` in this pass.

## Architecture

```
control.html (Media Player tab)
   │ /api/media/play|next|pause|resume|stop         │ /api/media/search (unchanged)
   ▼                                                  ▼
ControlServer.kt ──sends WS media_* messages──▶ VoiceAssistant/Protocol ──▶ xiaozhi-server
   ▲                                                  │
   └──reads queue + now-playing snapshot──────────────┘ (updated by incoming
      (MediaSessionState, replaces MediaPlayerController.state)   media_queue / media_now_playing)

xiaozhi-server (play_youtube.py)
   - _play_queue(conn, songs) — core loop (search-driven and video-id-driven paths share this)
   - conn._yt_web_paused — new flag, real pause distinct from client_abort (barge-in-to-ask)
   - pushes media_queue (on change) + media_now_playing (on change + every ~3s) over the WS
```

### New WebSocket messages

Client → server (new; sent by `VoiceAssistant`/`Protocol` on `ControlServer`'s behalf):

| Message | Meaning |
|---|---|
| `{"type":"media_play","video_id","title","artist","thumbnail"}` | Play this track now (fresh from search, or an item from the current queue). |
| `{"type":"media_next"}` | Skip to the next queued song. Reuses the existing `_yt_skip` flag — same mechanism `play_youtube_next` already uses. |
| `{"type":"media_pause"}` | Pause without entering "listening for a question" mode. |
| `{"type":"media_resume"}` | Resume from the paused position. |
| `{"type":"media_stop"}` | Stop the session entirely. Reuses `play_youtube_stop`'s session-clear. |

Server → client (new; replaces the old bare `{"type":"music","state":"start"|"stop"}`, which is
removed — `media_now_playing`'s `state` field covers the same LED-driving need):

| Message | Meaning |
|---|---|
| `{"type":"media_queue","items":[{video_id,title,artist,thumbnail,duration}...]}` | Pushed whenever the queue changes: new search-driven session, video-id-driven session, or a related-songs top-up. |
| `{"type":"media_now_playing","video_id","title","artist","thumbnail","duration_s","position_s","state":"playing"\|"paused"\|"stopped"}` | Pushed on every state change and every ~3s while playing (the `_wait_song` loop already ticks once per second — no new timer needed, just throttle the push). |

### Server-side (`play_youtube.py`)

- Refactor `_play(conn, query)`: extract the `while queue:` body into `_play_queue(conn, queue, session=None)`, taking a starting list instead of doing the search itself.
  - `play_youtube()` (voice function-call, unchanged trigger): search → `_play_queue(conn, [results[0]])`.
  - New `play_media_video(conn, video_id, title, artist, thumbnail)` (web-triggered): skip search, `_play_queue(conn, [{"video_id":video_id,"title":title,"artist":artist,"thumbnail":thumbnail}])`.
- New `conn._yt_web_paused` flag. `_wait_song()` gains a branch checking it (alongside the existing `_yt_skip`/`client_abort` checks), returning a new `"paused"` status.
- `_play_queue()`'s main loop, on `"paused"`: **does not** go through `_wait_qa_done` (that's for spoken barge-in — waits for the user to finish asking something). Instead waits in a small loop for either `_yt_web_paused` to clear (→ resume at the saved position, same `_trim_mp3`/`_seek` mechanism the barge-in-resume path already uses) or the session to change/clear (→ stop).
- New incoming-message handler (mirrors `abortMessageHandler.py`'s registration pattern) dispatching `media_play`/`media_next`/`media_pause`/`media_resume`/`media_stop` to the functions above / existing flag-setting.
- `media_queue` is pushed from the same places `queue` is currently mutated (initial construction, each related-songs top-up call). `media_now_playing` is pushed from `_send_music`'s call sites plus a throttled tick inside `_wait_song`'s existing per-second loop.

### Android app

- `Protocol.kt`: add senders `sendMediaPlay(videoId, title, artist, thumbnail)`, `sendMediaNext()`, `sendMediaPause()`, `sendMediaResume()`, `sendMediaStop()` — same shape as the existing `sendAbortSpeaking()`.
- `VoiceAssistant.kt`: `handleServerMessage` gains cases for `media_queue` and `media_now_playing`, publishing into a new `MediaSessionState` (replaces `MediaCoordinator`'s `webPlayRequested`/`voiceMusicActive` — no longer needed, since there's no second player to coordinate with).
- New `MediaSessionState` (plain state holder, StateFlow-based, same shape `MediaPlayerController.state` had) — lives in `domain/voice` alongside `MediaCoordinator` was; holds the latest queue + now-playing snapshot, written by `VoiceAssistant`, read by `ControlServer`.
- `ControlServer.kt`: `/api/media/play|next|pause|resume|stop` now call the new `Protocol` senders (via `VoiceAssistant`, injected the same way as before) instead of driving `MediaPlayerController`. `/api/media/state` is built from `MediaSessionState` instead of `mediaPlayer.state`. `/api/media/search` is unchanged.
- **Removed:** `MediaPlayerController.kt`, `MediaPlayerState.kt`, `MediaCoordinator.kt`, the `media3-exoplayer`/`media3-session` Gradle dependencies, `ControlServer`'s direct `/v3/video/<id>` triggering call (replaced by sending `media_play` and waiting for `media_now_playing`/`media_queue` to arrive).

### `control.html`

- Now Playing card renders directly from the server-pushed `media_now_playing` snapshot (real title/artist/duration/position) — the old "🎵 Đang phát qua giọng nói" placeholder for voice-triggered plays is removed; both trigger sources now produce the same real data.
- The results list renders the server-pushed `media_queue` whenever a session is active (so "next up" is visible regardless of trigger source); manual search results are shown when no session is active, same as today.
- Seek: since a client-driven seek isn't part of `media_pause`/`media_resume` (position is server-tracked in whole seconds via `_wait_song`'s `waited` counter, not frame-accurate), the seek bar becomes **display-only** for this pass — dragging it is removed. Precise seeking would require the server to accept a target position in `media_resume`, which is a reasonable but separate follow-up, not required by the current complaints.
- Download-progress ring: unchanged, still polls `pytube_api.py` directly.

## Decisions

- **ExoPlayer removed entirely**, no fallback path. If the WS to the server is down the whole voice assistant is unusable anyway, so a fallback player buys little for real added complexity.
- **Real pause/resume**, not "pause = stop." Reuses the existing interrupt-and-resume-at-position machinery (`_trim_mp3` + `_seek`) that barge-in questions already exercise, gated by a new flag instead of `client_abort` so tapping Pause on the web doesn't put the mic into listening mode.
- **Search stays client-side** (`ControlServer` → `pytube_api.py` directly) — it's stateless, moving it server-side would add WS round-trips for no benefit.
- **Seek bar becomes display-only** in this pass (see above) — dragging to seek is a follow-up, not a fix for any of the three reported bugs.

## Error handling

- `media_play` for a video that fails to download: same `VideoUnavailableError`/generic-error handling `_play_queue` already has (announces failure via TTS, moves on) — no new client-visible error state needed beyond what `_download`'s existing failure path produces.
- WS disconnected when the web UI sends a `media_*` command: `Protocol`'s send methods already handle a closed channel the same way `sendAbortSpeaking` does today (no special-casing needed for the new senders).
- Server restarts mid-session: `conn._yt_session` is per-connection in-memory state; a reconnect starts fresh with no queue — same as today's behavior for voice-triggered sessions surviving a server restart (i.e., it doesn't; this is pre-existing, not a regression).

## Testing

- Server-side: no existing test suite touches `play_youtube.py` (confirmed absence during the original feature's exploration) — this pass doesn't introduce one either; verified manually on the physical device, same as the original Media Player feature.
- Android-side: no automated test coverage is lost by removing `MediaPlayerController`/`MediaCoordinator` (they had none — see the original design doc's testing rationale). `Protocol`'s new senders and `VoiceAssistant`'s new message handlers follow the same untested-glue precedent as the existing `sendAbortSpeaking`/`handleServerMessage` cases.
- Manual verification (both repos) required for: web Play interrupting nothing else playing, web Play while voice is already playing (should take over, no overlap), voice Play while a web-triggered track is queued (should take over, no overlap), Next from web mid-voice-session, Pause from web then Resume (correct position, no accidental "listening" chime/behavior), equalizer toggle audibly affecting a web-triggered track.

## Open items for the implementation plan

- Exact `Protocol.kt`/`VoiceAssistant.kt` method signatures and where `MediaSessionState` is
  constructed/injected (mirror `TextCommands`/`MediaCoordinator`'s existing patterns).
- Exact refactor boundary inside `_play()` when extracting `_play_queue()` — needs re-reading
  `play_youtube.py` at implementation time to preserve all existing turn-management/announce
  behavior (`_open_turn`/`_close_turn`, `first_turn`, `intent_llm` branching) unchanged for the
  voice-triggered path.
- Where exactly to throttle the `media_now_playing` push inside `_wait_song`'s per-second loop
  (e.g., every 3rd iteration) without adding a second timer.
