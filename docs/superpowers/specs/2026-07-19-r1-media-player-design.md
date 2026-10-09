# R1 Control — Media Player tab

Date: 2026-07-19

## Problem

The on-device control panel (`ControlServer.kt`, NanoHTTPD on `:8088`, serving
`assets/control.html` to a phone/PC browser on the same wifi) has two tabs today:
Settings and Setup. There is no way to search for and play a song from that panel.
We already have a working YouTube search/download backend (`services/pytube_api.py`
in the `robot-esp32` repo) and a voice-triggered playback engine (`play_youtube.py`),
but neither is wired to any UI a human can tap on directly.

## Goal

Add a third "Media Player" tab to `control.html` that looks like an Android/Chromecast
media control (cover-tinted Now Playing card, drag-to-seek, prev/pause/next), backed by
a search form (search bar + result list with cover/title/artist/duration/Play button,
optional download-progress ring, empty-state message), without spamming YouTube or
touching the existing voice pipeline.

## Non-goals

- No changes to `play_youtube.py` or the voice/LLM playback pipeline — this feature is
  fully independent of voice-triggered playback.
- No "endless radio" / auto-queue behavior. "Next" only ever advances within the
  currently-displayed search-result list.
- No true download-percentage reporting in v1 (see Decisions).

## Architecture

```
control.html (Media Player tab)
   │  fetch /api/media/*
   ▼
ControlServer.kt (NanoHTTPD, on-device, :8088)
   │  OkHttp proxy (same pattern as /api/llm/* and /api/ha/*)
   ▼
pytube_api.py (Flask, LAN, :114)  ──unchanged──  search / cache / download-on-demand

ControlServer.kt
   │  direct calls (in-process, like AudioPlayback/LedIndicator today)
   ▼
MediaPlayerController.kt (new) — wraps ExoPlayer + a MediaSession
```

`pytube_api`'s base URL becomes a new device Setting (same tier as `ha_url`,
`ota_url`) — config lives on-device, in `ControlServer.kt`/`Settings.kt`, exactly like
every other integration this app already has. `pytube_api.py` itself is not modified
in v1 beyond what's noted under Decisions.

### New Kotlin: `MediaPlayerController.kt`

- Wraps ExoPlayer (new dependency — `androidx.media3:media3-exoplayer` +
  `media3-session`; not present in `app/build.gradle.kts` today).
- Registers a `MediaSession` (Media3) with cover art + title/artist metadata, so
  Android's own quick-settings "Cast-style" card (the reference screenshot) shows up
  for free, plus lock-screen/notification transport controls.
- API surface: `play(videoId, streamUrl, title, artist, coverUrl)`, `pause()`,
  `resume()`, `seekTo(positionMs)`, and a `StateFlow<MediaPlayerState>` that
  `ControlServer.kt` reads for `/api/media/state`.
- Wired via Hilt the same way `AudioPlayback`/`LedIndicator` are today (see
  `VoiceModule.kt` for the existing pattern to follow).
- Playback streams progressively from `pytube_api`'s `/v3/mp3/<video_id>` URL — no
  need to copy the file onto the device first.

### `ControlServer.kt` additions

New routes, following the exact `session.uri` `when` branch + OkHttp-proxy pattern
already used for `/api/llm/models`, `/api/llm/test`, `/api/ha/devices`, `/api/ha/test`:

| Route | Behavior |
|---|---|
| `GET /api/media/search?q=` | Proxies to `pytube_api` `GET /v3/search?q=`. Returns the video list as-is (video_id, title, artist, duration, thumbnail). |
| `POST /api/media/play?video_id=` | Calls `pytube_api` `GET /v3/video/<id>` to ensure it's downloaded/cached (blocking on this service's existing on-demand download), sets `download_state=downloading` in local state while waiting, then on success calls `MediaPlayerController.play(...)` with the `/v3/mp3/<id>` stream URL. On failure sets `download_state=error`. |
| `POST /api/media/pause` | `MediaPlayerController.pause()` |
| `POST /api/media/resume` | `MediaPlayerController.resume()` |
| `POST /api/media/seek?position_ms=` | `MediaPlayerController.seekTo(...)` |
| `POST /api/media/next?video_id=` | Same as `/play`, called by the client with the next id from its own list — the server has no queue concept. |
| `GET /api/media/state` | `{playing, video_id, title, artist, cover_url, position_ms, duration_ms, download_state}` — `download_state` ∈ `idle|downloading|ready|error`. |

A second concurrent `/api/media/play` while one is still downloading cancels/replaces
the in-flight one (last tap wins) — matches "I changed my mind" intent and keeps the
server-side state machine to a single in-flight download at a time (also satisfies
"don't spam the downloader").

### `control.html` additions

- Third `.seg` button "Media Player" (music-note icon, same SVG style as the existing
  two), added after Setup in both the tab bar and the `tab()` JS function's list.
- Search bar: reuses `.codein` input styling, placeholder "Tìm bài hát…", 400ms debounce
  before firing `/api/media/search` (prevents spamming `pytube_api`/YouTube on every
  keystroke).
- Result list: new flat `.list-item` style (lighter than `.card`) — 44×44 rounded cover,
  title (bold, 1-line ellipsis), artist + duration as `.hint`-style muted subtext, and a
  circular Play button on the right. While `download_state==downloading` for that item,
  a conic-gradient spinner ring (reusing the `--grad` accent) animates around the Play
  button; `error` state shows a small red badge, tap-to-retry.
- Empty state: reuses the existing `.empty` treatment (already used by the chat drawer's
  "Chưa có tin nhắn") — "Tìm bài hát để bắt đầu" before any search, "Không tìm thấy bài
  hát" after a search with zero results.
- Now Playing card: docked at the bottom of the Media Player pane (visible only on this
  tab, only while `video_id` is non-null), styled like the reference screenshot —
  cover-tinted background, title/artist, prev/play-pause/next buttons, and a full-width
  `input[type=range]` reusing the existing thumb styling for drag-to-seek (local drag
  updates the UI immediately; release posts `/api/media/seek`).
- State sync: extends the existing `poll()` (1.5s interval) to also fetch
  `/api/media/state` while the Media Player tab is active, and render the Now Playing
  card + list item spinner/error states from it. Not polled when another tab is active,
  to avoid needless work.
- Next button: computed purely client-side from the last-rendered search-result array —
  finds the currently-playing `video_id` in that array and calls `/api/media/next` with
  the following item's id. If the currently-playing id isn't present in the currently
  displayed list, the Next button is disabled (greyed out, non-interactive) until a
  search makes it resolvable again.

## Decisions

- **Download progress: indeterminate spinner for v1.** `pytube_api.py`'s download is
  synchronous today (no percentage available without adding a background-job +
  status-polling API to that service, which is shared with the voice pipeline). Ship
  with a spinning ring (no percentage) now; a real percentage is a follow-up if it turns
  out to matter in practice, not part of this pass.
- **Next when playing track isn't in the displayed list: disabled**, not silent no-op —
  clearer affordance than a button that does nothing when tapped.

## Error handling

- Search request fails (network / `pytube_api` down): inline error message in place of
  the result list (not a silent empty state).
- Download fails (`pytube_api` returns an error, e.g. `VideoUnavailableError`): that
  item's `download_state` flips to `error`, ring replaced by a small retry-on-tap badge.
- Playback error from ExoPlayer: `MediaPlayerController` resets to idle state; Now
  Playing card shows a brief inline status message (same convention as `$('llmStat')`
  etc. elsewhere in `control.html`), not a crash/silent stop.

## Testing

- Kotlin unit tests for `MediaPlayerController` state transitions (play → pause →
  resume → seek; the "next not in list" edge case at the `ControlServer`/client-state
  level).
- No automated test for the actual on-device audio output or the OS-level MediaSession
  card — that requires manual verification on the physical R1 device (screen recording
  or in-person check), called out explicitly rather than claimed as covered by tests.

## Open items for the implementation plan

- Confirm `pytube_api`'s `/v3/video/<id>` response shape (fields available for
  title/artist/cover/duration) so `ControlServer.kt`'s proxy can map them 1:1 into
  `/api/media/state` without guessing field names — check at implementation time.
- Add `media3-exoplayer` + `media3-session` to `app/build.gradle.kts`.
- Add `pytube_base_url` to `Settings.kt` (mirrors `ha_url`/`ota_url`) plus a small
  input row in the Setup tab so it's configurable without a rebuild.
