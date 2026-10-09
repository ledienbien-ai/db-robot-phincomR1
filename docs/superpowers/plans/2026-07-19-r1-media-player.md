# R1 Control — Media Player Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third "Media Player" tab to the on-device R1 control panel (`control.html`, served by `ControlServer.kt` on `:8088`) that can search songs (via the existing `pytube_api.py` service), download/cache them on demand, and play them back locally through a new ExoPlayer-based `MediaPlayerController`, fully independent of the voice pipeline.

**Architecture:** `control.html` gets a new tab (search bar + result list + a docked Now Playing card). `ControlServer.kt` gains a `/api/media/*` route family that proxies search/download-trigger calls to `pytube_api.py` (base URL becomes a new device Setting) and forwards playback commands to a new `MediaPlayerController` (ExoPlayer + `MediaSession`, wired via Hilt exactly like the existing `AudioPlayback`/`LedIndicator`).

**Tech Stack:** Kotlin, Hilt DI, NanoHTTPD, OkHttp, `androidx.media3` (ExoPlayer + Session), vanilla JS/HTML/CSS (no framework, matching the existing `control.html`), `kotlin.test` for JVM unit tests.

Spec: `docs/superpowers/specs/2026-07-19-r1-media-player-design.md`

## Global Constraints

- Do not modify `play_youtube.py` or anything in the xiaozhi-server voice/LLM pipeline (different repo: `robot-esp32`) — this feature is fully independent of voice-triggered playback.
- No auto-queue / "endless radio" behavior — "Next" only ever advances within the currently-displayed search-result list, computed client-side in `control.html`'s JS.
- `pytube_api.py` (in `robot-esp32/services/`) is **not modified** in this plan — reused exactly as it exists today (`/v3/search`, `/v3/video/<id>`, `/v3/mp3/<id>`).
- Download progress is an **indeterminate spinner** in v1 — no real percentage (would require adding a job-status API to `pytube_api.py`, out of scope).
- When the currently-playing track is not present in the currently-displayed search-result list, the Next button is **disabled** (not a silent no-op).
- Only one download in flight at a time — a new Play tap replaces (not queues behind) an in-flight download.
- `compileSdk`/`targetSdk` = 35, `minSdk` = 22, `jvmTarget` = 11 (from `app/build.gradle.kts` — unchanged by this plan).

---

## Task 1: Add ExoPlayer (Media3) dependencies

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `androidx.media3:media3-exoplayer:1.4.1` and `androidx.media3:media3-session:1.4.1` on the `app` module's compile classpath, for Task 3 to consume (`ExoPlayer`, `MediaSession`, `MediaItem`, `MediaMetadata`, `Player`, `PlaybackException`).

- [ ] **Step 1: Add the dependencies**

In `app/build.gradle.kts`, find:

```kotlin
    // Embedded HTTP server for the on-device control panel (EQ / settings / chat).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
```

Replace with:

```kotlin
    // Embedded HTTP server for the on-device control panel (EQ / settings / chat).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Local playback engine for the Media Player tab (independent of the voice pipeline's
    // TTS-interleaved audio queue). media3-session registers a MediaSession so playback also
    // surfaces as Android's own quick-settings media card.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
```

- [ ] **Step 2: Verify it resolves and the module still compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add media3 ExoPlayer + session dependencies for Media Player tab"
```

---

## Task 2: Pure JSON parsers for pytube_api responses (TDD)

These are the only pieces of this feature with meaningful, mockless unit-test value (pure functions over JSON strings — same shape as the existing `maskApiKey`/`extractModelId` pattern in this codebase). `ControlServer.kt` (Task 4) calls these instead of hand-parsing JSON inline.

**Files:**
- Create: `app/src/main/java/info/dourok/voicebot/media/MediaSearchResult.kt`
- Create: `app/src/main/java/info/dourok/voicebot/media/PytubeVideoResult.kt`
- Test: `app/src/test/java/info/dourok/voicebot/media/MediaSearchResultTest.kt`
- Test: `app/src/test/java/info/dourok/voicebot/media/PytubeVideoResultTest.kt`

**Interfaces:**
- Produces: `data class MediaSearchResult(videoId: String, title: String, artist: String, duration: String, thumbnailUrl: String)`, `fun parseSearchResults(json: String): List<MediaSearchResult>`, `sealed class PytubeVideoResult { data class Ready(mp3Path: String, title: String, durationMs: Long); data class Unavailable(message: String); data class Error(message: String) }`, `fun parseVideoResponse(json: String, httpStatus: Int): PytubeVideoResult` — all consumed by Task 4.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/info/dourok/voicebot/media/MediaSearchResultTest.kt`:

```kotlin
package info.dourok.voicebot.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSearchResultTest {
    @Test fun parsesResults() {
        val json = """
            {"query":"mot nha","count":1,"results":[
              {"video_id":"S8sYD-2yco0","title":"Một Nhà","artist":"Da LAB","duration":"4:12","thumbnail":"https://x/y.jpg"}
            ]}
        """.trimIndent()
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals(MediaSearchResult("S8sYD-2yco0", "Một Nhà", "Da LAB", "4:12", "https://x/y.jpg"), results[0])
    }

    @Test fun emptyResultsArray() {
        assertEquals(emptyList(), parseSearchResults("""{"query":"x","count":0,"results":[]}"""))
    }

    @Test fun skipsItemsMissingVideoId() {
        val json = """{"results":[{"title":"no id here"},{"video_id":"abc","title":"ok","artist":"","duration":"","thumbnail":""}]}"""
        assertEquals(1, parseSearchResults(json).size)
    }

    @Test fun malformedJsonReturnsEmptyList() {
        assertEquals(emptyList(), parseSearchResults("not json"))
    }

    @Test fun missingResultsKeyReturnsEmptyList() {
        assertEquals(emptyList(), parseSearchResults("""{"error":"missing q"}"""))
    }
}
```

Create `app/src/test/java/info/dourok/voicebot/media/PytubeVideoResultTest.kt`:

```kotlin
package info.dourok.voicebot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PytubeVideoResultTest {
    @Test fun readyFromCachedResponse() {
        val json = """
            {"video_title":"Một Nhà","video_thumbnail_url":"https://x/y.jpg","video_id":"S8sYD-2yco0",
             "video_url":"https://youtube.com/watch?v=S8sYD-2yco0","video_duration":"252",
             "mp3_url":"/v3/mp3/S8sYD-2yco0?device=abc","is_loaded_from_cache":true}
        """.trimIndent()
        val result = parseVideoResponse(json, 200)
        assertIs<PytubeVideoResult.Ready>(result)
        assertEquals("/v3/mp3/S8sYD-2yco0?device=abc", result.mp3Path)
        assertEquals("Một Nhà", result.title)
        assertEquals(252_000L, result.durationMs)
    }

    @Test fun unavailableOn404WithUnavailableFlag() {
        val json = """{"error":"Video unavailable","message":"private video","video_id":"x","unavailable":true}"""
        val result = parseVideoResponse(json, 404)
        assertIs<PytubeVideoResult.Unavailable>(result)
        assertEquals("private video", result.message)
    }

    @Test fun errorOnDownloadFailure500() {
        val json = """{"error":"Download failed","message":"yt-dlp exploded","video_id":"x"}"""
        val result = parseVideoResponse(json, 500)
        assertIs<PytubeVideoResult.Error>(result)
        assertEquals("yt-dlp exploded", result.message)
    }

    @Test fun errorOnMalformedJson() {
        val result = parseVideoResponse("not json", 200)
        assertIs<PytubeVideoResult.Error>(result)
    }

    @Test fun errorWhenMp3UrlMissing() {
        val result = parseVideoResponse("""{"video_title":"x"}""", 200)
        assertIs<PytubeVideoResult.Error>(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:testDebugUnitTest --tests "info.dourok.voicebot.media.*"`
Expected: FAIL — `Unresolved reference: parseSearchResults` / `parseVideoResponse` (files don't exist yet)

- [ ] **Step 3: Implement `MediaSearchResult.kt`**

Create `app/src/main/java/info/dourok/voicebot/media/MediaSearchResult.kt`:

```kotlin
package info.dourok.voicebot.media

import org.json.JSONObject

/** One song from pytube_api's `/v3/search` (ytmusicapi-backed). */
data class MediaSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val thumbnailUrl: String,
)

/** Parses pytube_api's `/v3/search` response body. Returns an empty list on any malformed input
 *  (missing `results`, bad JSON, items without a `video_id`) rather than throwing — this is UI
 *  data, a bad search result should render as "no results", not crash the panel. */
fun parseSearchResults(json: String): List<MediaSearchResult> {
    val obj = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
    val results = obj.optJSONArray("results") ?: return emptyList()
    val out = mutableListOf<MediaSearchResult>()
    for (i in 0 until results.length()) {
        val r = results.optJSONObject(i) ?: continue
        val videoId = r.optString("video_id", "")
        if (videoId.isEmpty()) continue
        out.add(
            MediaSearchResult(
                videoId = videoId,
                title = r.optString("title", ""),
                artist = r.optString("artist", ""),
                duration = r.optString("duration", ""),
                thumbnailUrl = r.optString("thumbnail", ""),
            )
        )
    }
    return out
}
```

- [ ] **Step 4: Implement `PytubeVideoResult.kt`**

Create `app/src/main/java/info/dourok/voicebot/media/PytubeVideoResult.kt`:

```kotlin
package info.dourok.voicebot.media

import org.json.JSONObject

/** Outcome of asking pytube_api's `/v3/video/<id>` to ensure a track is downloaded/cached. */
sealed class PytubeVideoResult {
    /** [mp3Path] is the path pytube_api returned in `mp3_url` (already includes `?device=...`) —
     *  relative to pytube_api's base URL unless it's already an absolute URL. */
    data class Ready(val mp3Path: String, val title: String, val durationMs: Long) : PytubeVideoResult()
    /** Video is private/deleted/restricted (pytube_api returns 404 + `unavailable:true`) — not
     *  worth retrying. */
    data class Unavailable(val message: String) : PytubeVideoResult()
    /** Network error, malformed response, or a download failure worth retrying. */
    data class Error(val message: String) : PytubeVideoResult()
}

/** Parses pytube_api's `/v3/video/<id>` response. [httpStatus] disambiguates the 404-unavailable
 *  case from other errors, since both can carry an `error`/`message` pair in the body. */
fun parseVideoResponse(json: String, httpStatus: Int): PytubeVideoResult {
    val obj = try { JSONObject(json) } catch (e: Exception) {
        return PytubeVideoResult.Error("bad response: ${e.message}")
    }
    if (httpStatus == 404 && obj.optBoolean("unavailable", false)) {
        return PytubeVideoResult.Unavailable(obj.optString("message", "video unavailable"))
    }
    if (httpStatus != 200) {
        return PytubeVideoResult.Error(obj.optString("message", obj.optString("error", "HTTP $httpStatus")))
    }
    val mp3Path = obj.optString("mp3_url", "")
    if (mp3Path.isEmpty()) return PytubeVideoResult.Error("missing mp3_url in response")
    val title = obj.optString("video_title", "")
    val durationSec = obj.optString("video_duration", "0").toDoubleOrNull() ?: 0.0
    return PytubeVideoResult.Ready(mp3Path, title, (durationSec * 1000).toLong())
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:testDebugUnitTest --tests "info.dourok.voicebot.media.*"`
Expected: `BUILD SUCCESSFUL`, 10 tests passed

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/info/dourok/voicebot/media/MediaSearchResult.kt \
        app/src/main/java/info/dourok/voicebot/media/PytubeVideoResult.kt \
        app/src/test/java/info/dourok/voicebot/media/MediaSearchResultTest.kt \
        app/src/test/java/info/dourok/voicebot/media/PytubeVideoResultTest.kt
git commit -m "feat(media): pure parsers for pytube_api search/video responses"
```

---

## Task 3: `MediaPlayerController` (ExoPlayer + MediaSession)

**Files:**
- Create: `app/src/main/java/info/dourok/voicebot/media/MediaPlayerState.kt`
- Create: `app/src/main/java/info/dourok/voicebot/media/MediaPlayerController.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `enum class DownloadState { IDLE, DOWNLOADING, READY, ERROR }`; `data class MediaPlayerState(playing: Boolean, videoId: String?, title: String, artist: String, coverUrl: String, positionMs: Long, durationMs: Long, downloadState: DownloadState, errorMessage: String?)`; `class MediaPlayerController` (Hilt `@Singleton`, `@Inject constructor(@ApplicationContext context: Context)`) exposing `val state: StateFlow<MediaPlayerState>`, `fun markDownloading(videoId, title, artist, coverUrl)`, `fun markError(videoId, message)`, `fun play(videoId, streamUrl, title, artist, coverUrl)`, `fun pause()`, `fun resume()`, `fun seekTo(positionMs: Long)` — all consumed by Task 4 (`ControlServer.kt`).

**No automated test for this file.** It's a thin wrapper around Android framework classes (`ExoPlayer`, `MediaSession`, `Handler`/`Looper`) with no mocking library in this project (`app/build.gradle.kts` has no MockK/Mockito/Robolectric) — a "unit test" here would just be re-asserting ExoPlayer's own documented behavior through hand-rolled fakes, which is the "fake test" anti-pattern, not real coverage. It's verified manually at the end of Task 5 (the only place it can actually be exercised — playing real audio through a real device speaker). This matches the project's existing convention: none of `ControlServer.kt`'s own network-proxy methods (`handleLlmModels`, `handleHaDevices`, `handleSetupServer`, etc.) have automated tests either, for the same reason.

- [ ] **Step 1: Create `MediaPlayerState.kt`**

```kotlin
package info.dourok.voicebot.media

enum class DownloadState { IDLE, DOWNLOADING, READY, ERROR }

/** Snapshot of the Media Player tab's playback state, polled by ControlServer's
 *  `/api/media/state` and rendered by control.html's Now Playing card. */
data class MediaPlayerState(
    val playing: Boolean = false,
    val videoId: String? = null,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val downloadState: DownloadState = DownloadState.IDLE,
    val errorMessage: String? = null,
)
```

- [ ] **Step 2: Create `MediaPlayerController.kt`**

```kotlin
package info.dourok.voicebot.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device playback engine for the Media Player tab. Wraps ExoPlayer + a MediaSession so
 * playback also surfaces as Android's own quick-settings media card, fully independent from the
 * voice pipeline's TTS-interleaved audio queue (play_youtube.py streams over the xiaozhi
 * WebSocket; this streams straight from pytube_api's /v3/mp3/<id> via HTTP).
 *
 * ExoPlayer requires all calls to happen on the thread that created it (normally main). This
 * class is Hilt-constructed from VApplication.onCreate() (main thread) alongside ControlServer,
 * so construction is safe; every call coming from ControlServer's NanoHTTPD worker threads is
 * hopped onto the main thread internally so callers never need to think about it.
 */
@Singleton
class MediaPlayerController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(context).build()
    private val session = MediaSession.Builder(context, player).build()

    private val _state = MutableStateFlow(MediaPlayerState())
    val state: StateFlow<MediaPlayerState> = _state.asStateFlow()

    // ExoPlayer doesn't push position changes; poll it on the main thread every 500ms while
    // playing so /api/media/state (read from _state, never from `player` directly) stays fresh.
    // Declared before `init` since Kotlin initializes properties in declaration order and `init`
    // references it.
    private val positionTicker = object : Runnable {
        override fun run() {
            if (_state.value.playing) {
                _state.value = _state.value.copy(positionMs = player.currentPosition.coerceAtLeast(0))
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.value = _state.value.copy(
                        downloadState = DownloadState.READY,
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(
                    downloadState = DownloadState.ERROR,
                    errorMessage = error.message,
                    playing = false,
                )
            }
        })
        mainHandler.post(positionTicker)
    }

    /** Called by ControlServer right before it asks pytube_api to ensure [videoId] is cached. */
    fun markDownloading(videoId: String, title: String, artist: String, coverUrl: String) {
        _state.value = MediaPlayerState(
            videoId = videoId, title = title, artist = artist, coverUrl = coverUrl,
            downloadState = DownloadState.DOWNLOADING,
        )
    }

    /** Called by ControlServer if pytube_api's lookup/download for [videoId] failed. */
    fun markError(videoId: String, message: String) {
        val cur = _state.value
        if (cur.videoId != videoId) return  // a newer play() already superseded this one
        _state.value = cur.copy(downloadState = DownloadState.ERROR, errorMessage = message, playing = false)
    }

    /** Starts playback once pytube_api has confirmed [streamUrl] (its /v3/mp3/<id> URL) is ready. */
    fun play(videoId: String, streamUrl: String, title: String, artist: String, coverUrl: String) {
        mainHandler.post {
            val item = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
    }

    fun pause() { mainHandler.post { player.pause() } }
    fun resume() { mainHandler.post { player.play() } }

    fun seekTo(positionMs: Long) {
        mainHandler.post { player.seekTo(positionMs) }
        _state.value = _state.value.copy(positionMs = positionMs)  // optimistic, avoids a stale poll flash
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/info/dourok/voicebot/media/MediaPlayerState.kt \
        app/src/main/java/info/dourok/voicebot/media/MediaPlayerController.kt
git commit -m "feat(media): add MediaPlayerController (ExoPlayer + MediaSession)"
```

---

## Task 4: `Settings.pytubeBaseUrl` + `ControlServer.kt` media routes

**Files:**
- Modify: `app/src/main/java/info/dourok/voicebot/data/Settings.kt`
- Modify: `app/src/main/java/info/dourok/voicebot/control/ControlServer.kt`

**Interfaces:**
- Consumes: `MediaPlayerController` (Task 3), `parseSearchResults`/`parseVideoResponse`/`MediaSearchResult`/`PytubeVideoResult` (Task 2).
- Produces: `Settings.pytubeBaseUrl: String`; new NanoHTTPD routes `GET /api/media/search?q=`, `POST /api/media/play?video_id=&title=&artist=&thumbnail=`, `POST /api/media/pause`, `POST /api/media/resume`, `POST /api/media/seek?position_ms=`, `GET /api/media/state` — consumed by Task 5 (`control.html`).

Note on scope vs. the spec: the spec's route table lists a separate `POST /api/media/next` that behaves identically to `/api/media/play`. Since there is zero behavioral difference between them, this task does not add a duplicate route — `control.html`'s Next button (Task 5) calls `/api/media/play` again with the next item's id/title/artist/thumbnail. Same user-facing behavior, one less route to maintain.

**No automated test for this task** — same reasoning as Task 3: it's HTTP-proxy glue (OkHttp calls out to `pytube_api`), and none of `ControlServer.kt`'s existing proxy methods (`handleLlmModels`, `handleHaDevices`, `handleSetupServer`) have tests either. The parsing logic it depends on (Task 2) is unit-tested; the wiring itself is verified manually in Task 5.

- [ ] **Step 1: Add the setting**

In `app/src/main/java/info/dourok/voicebot/data/Settings.kt`, find:

```kotlin
    // ── Home Assistant (Setup tab) ──────────────────────────────────────────
    var haUrl: String
```

Replace with:

```kotlin
    // ── Media Player (Setup tab) ─────────────────────────────────────────────
    /** Base URL of the pytube_api search/download service, e.g. "http://192.168.1.20:114". */
    var pytubeBaseUrl: String
        get() = prefs.getString("pytube_base_url", "")!!
        set(v) = prefs.edit().putString("pytube_base_url", v).apply()

    // ── Home Assistant (Setup tab) ──────────────────────────────────────────
    var haUrl: String
```

- [ ] **Step 2: Wire the setting into `handleSet()` and `buildState()`**

In `app/src/main/java/info/dourok/voicebot/control/ControlServer.kt`, find:

```kotlin
            "ota_url" -> Settings.otaUrl = v
            "ha_url" -> Settings.haUrl = v
```

Replace with:

```kotlin
            "ota_url" -> Settings.otaUrl = v
            "pytube_base_url" -> Settings.pytubeBaseUrl = v
            "ha_url" -> Settings.haUrl = v
```

Find:

```kotlin
        o.put("ota_url", Settings.otaUrl)
        o.put("ws_url", Settings.wsUrl)
```

Replace with:

```kotlin
        o.put("ota_url", Settings.otaUrl)
        o.put("pytube_base_url", Settings.pytubeBaseUrl)
        o.put("ws_url", Settings.wsUrl)
```

- [ ] **Step 3: Inject `MediaPlayerController` and add the route table entries**

Find:

```kotlin
@Singleton
class ControlServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: AudioPlayback,
    private val led: LedIndicator,
) : NanoHTTPD(PORT) {
```

Replace with:

```kotlin
@Singleton
class ControlServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: AudioPlayback,
    private val led: LedIndicator,
    private val mediaPlayer: info.dourok.voicebot.media.MediaPlayerController,
) : NanoHTTPD(PORT) {
```

Find:

```kotlin
            "/api/ha/devices" -> json(handleHaDevices(session))
            "/api/ha/test" -> json(handleHaTest(session))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
```

Replace with:

```kotlin
            "/api/ha/devices" -> json(handleHaDevices(session))
            "/api/ha/test" -> json(handleHaTest(session))
            "/api/media/search" -> json(handleMediaSearch(param(session, "q")))
            "/api/media/play" -> { handleMediaPlay(session); json("""{"ok":true}""") }
            "/api/media/pause" -> { mediaPlayer.pause(); json("""{"ok":true}""") }
            "/api/media/resume" -> { mediaPlayer.resume(); json("""{"ok":true}""") }
            "/api/media/seek" -> {
                param(session, "position_ms").toLongOrNull()?.let { mediaPlayer.seekTo(it) }
                json("""{"ok":true}""")
            }
            "/api/media/state" -> json(buildMediaState())
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
```

- [ ] **Step 4: Add the handler methods**

Find:

```kotlin
    /** GET {ha_url}/api/ → HA ping (validates URL + token). */
    private fun handleHaTest(session: IHTTPSession): String {
```

Insert immediately **before** that block (keeping `handleHaTest` itself unchanged, right after it in the file):

```kotlin
    // ── Media Player ──────────────────────────────────────────────────────
    // Proxies to pytube_api (search/download-on-demand); playback itself is delegated to
    // MediaPlayerController. Follows the same OkHttp-proxy pattern as handleLlmModels/handleHaDevices.

    /** GET {pytube_base_url}/v3/search?q=&limit= → reshaped as {"ok":true,"results":[...]}. */
    private fun handleMediaSearch(query: String): String {
        val base = Settings.pytubeBaseUrl.trimEnd('/')
        if (base.isBlank()) return """{"ok":false,"error":"pytube base URL not configured (Setup tab)"}"""
        if (query.isBlank()) return """{"ok":true,"results":[]}"""
        return try {
            val url = "$base/v3/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=15"
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { it.body?.string().orEmpty() }
            val results = info.dourok.voicebot.media.parseSearchResults(body)
            val arr = JSONArray()
            results.forEach {
                arr.put(
                    JSONObject()
                        .put("video_id", it.videoId).put("title", it.title)
                        .put("artist", it.artist).put("duration", it.duration)
                        .put("thumbnail", it.thumbnailUrl)
                )
            }
            """{"ok":true,"results":$arr}"""
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "network error")}}"""
        }
    }

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
            }
        }.start()
    }

    private fun buildMediaState(): String {
        val s = mediaPlayer.state.value
        return JSONObject()
            .put("playing", s.playing)
            .put("video_id", s.videoId)
            .put("title", s.title)
            .put("artist", s.artist)
            .put("cover_url", s.coverUrl)
            .put("position_ms", s.positionMs)
            .put("duration_ms", s.durationMs)
            .put("download_state", s.downloadState.name.lowercase())
            .put("error_message", s.errorMessage)
            .toString()
    }

    /** GET {ha_url}/api/ → HA ping (validates URL + token). */
    private fun handleHaTest(session: IHTTPSession): String {
```

- [ ] **Step 5: Verify it compiles**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Run the full unit test suite to make sure nothing regressed**

Run: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/info/dourok/voicebot/data/Settings.kt \
        app/src/main/java/info/dourok/voicebot/control/ControlServer.kt
git commit -m "feat(media): proxy pytube_api through ControlServer's /api/media/* routes"
```

---

## Task 5: `control.html` — Media Player tab UI

**Files:**
- Modify: `app/src/main/assets/control.html`

**Interfaces:**
- Consumes: `/api/media/search`, `/api/media/play`, `/api/media/pause`, `/api/media/resume`, `/api/media/seek`, `/api/media/state`, `/api/set` with `key=pytube_base_url` (all from Task 4).

**No automated test** — this repo has no JS test framework (`control.html` is a single hand-rolled asset with zero existing JS tests: HA device picker, wake-engine switching, theme toggle are all manually verified today). Step 7 below is a concrete manual verification checklist using the app's existing fast-iteration path (`/sdcard/control.html` override, see `ControlServer.serveAsset()`), not a placeholder.

- [ ] **Step 1: Add the third tab button**

In `app/src/main/assets/control.html`, find:

```html
<div class="seg">
  <button id="tset" class="on" onclick="tab('set')"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="10" cy="10" r="3"/><circle cx="10" cy="10" r="1" fill="currentColor" stroke="none"/><line x1="10" y1="2" x2="10" y2="4.2"/><line x1="10" y1="15.8" x2="10" y2="18"/><line x1="2" y1="10" x2="4.2" y2="10"/><line x1="15.8" y1="10" x2="18" y2="10"/><line x1="4.6" y1="4.6" x2="6.1" y2="6.1"/><line x1="13.9" y1="13.9" x2="15.4" y2="15.4"/><line x1="15.4" y1="4.6" x2="13.9" y2="6.1"/><line x1="6.1" y1="13.9" x2="4.6" y2="15.4"/></svg>Settings</button>
  <button id="tsetup" onclick="tab('setup')"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M10 3v4M10 13v4M3 10h4M13 10h4"/><circle cx="10" cy="10" r="2.2"/></svg>Setup</button>
</div>
```

Replace with:

```html
<div class="seg">
  <button id="tset" class="on" onclick="tab('set')"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="10" cy="10" r="3"/><circle cx="10" cy="10" r="1" fill="currentColor" stroke="none"/><line x1="10" y1="2" x2="10" y2="4.2"/><line x1="10" y1="15.8" x2="10" y2="18"/><line x1="2" y1="10" x2="4.2" y2="10"/><line x1="15.8" y1="10" x2="18" y2="10"/><line x1="4.6" y1="4.6" x2="6.1" y2="6.1"/><line x1="13.9" y1="13.9" x2="15.4" y2="15.4"/><line x1="15.4" y1="4.6" x2="13.9" y2="6.1"/><line x1="6.1" y1="13.9" x2="4.6" y2="15.4"/></svg>Settings</button>
  <button id="tsetup" onclick="tab('setup')"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M10 3v4M10 13v4M3 10h4M13 10h4"/><circle cx="10" cy="10" r="2.2"/></svg>Setup</button>
  <button id="tmedia" onclick="tab('media')"><svg viewBox="0 0 20 20" fill="currentColor"><path d="M8 3v9.2a3 3 0 101.5 2.6V6.9l6-1.2v6.5a3 3 0 101.5 2.6V3l-9 1.8V3z"/></svg>Media Player</button>
</div>
```

- [ ] **Step 2: Add the Media Player pane**

Find:

```html
    <div class="card">
      <div class="ch"><span class="icon-badge ib-sys"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M16 6a7 7 0 10.8 6"/><path d="M16 2.5v4h-4"/></svg></span><h2>Ứng dụng</h2></div>
      <button class="btn warn" onclick="restart()">↻ Restart app</button>
      <div class="hint" style="margin-top:8px">Khởi động lại app để áp dụng các thay đổi cần restart (engine, nguồn mic, sample rate).</div>
    </div>
  </div>
</div>
</div><!--panes-->
```

Replace with:

```html
    <div class="card">
      <div class="ch"><span class="icon-badge ib-sys"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M16 6a7 7 0 10.8 6"/><path d="M16 2.5v4h-4"/></svg></span><h2>Ứng dụng</h2></div>
      <button class="btn warn" onclick="restart()">↻ Restart app</button>
      <div class="hint" style="margin-top:8px">Khởi động lại app để áp dụng các thay đổi cần restart (engine, nguồn mic, sample rate).</div>
    </div>
  </div>
</div>
<!-- MEDIA PLAYER -->
<div id="pmedia" class="page">
  <div class="settings" style="padding-bottom:100px">
    <div class="card">
      <div class="row" style="margin:0"><input class="codein" id="mediaSearch" placeholder="🔍 Tìm bài hát…" oninput="onMediaSearchInput()" style="flex:1"></div>
      <div id="mediaResults" style="margin-top:8px"></div>
    </div>
  </div>
  <div id="nowPlaying" class="now-playing" hidden>
    <img id="npCover" class="np-cover" src="" alt="">
    <div class="np-info">
      <div class="np-title" id="npTitle"></div>
      <div class="np-artist" id="npArtist"></div>
      <input type="range" id="npSeek" min="0" max="0" value="0">
    </div>
    <div class="np-ctrl">
      <button id="npPrev" onclick="mediaPrev()">⏮</button>
      <button id="npPlay" onclick="mediaToggle()">▶</button>
      <button id="npNext" onclick="mediaNext()">⏭</button>
    </div>
  </div>
</div>
</div><!--panes-->
```

- [ ] **Step 3: Add the pytube base URL row to the Setup tab's Server card**

Find:

```html
      <div class="row"><label>OTA URL</label><input class="codein" id="ota" placeholder="http://host:8003/xiaozhi/ota/"></div>
      <div class="row" style="gap:8px"><button class="btn rec" style="flex:1;width:auto" onclick="connectServer()">Connect</button><span class="hint" id="srvStat" style="flex:1"></span></div>
    </div>
```

Replace with:

```html
      <div class="row"><label>OTA URL</label><input class="codein" id="ota" placeholder="http://host:8003/xiaozhi/ota/"></div>
      <div class="row" style="gap:8px"><button class="btn rec" style="flex:1;width:auto" onclick="connectServer()">Connect</button><span class="hint" id="srvStat" style="flex:1"></span></div>
      <div class="row"><label>Pytube API</label><input class="codein" id="pytubeUrl" placeholder="http://host:114" onchange="set('pytube_base_url',this.value)"></div>
      <div class="hint">Backend tìm/tải bài hát cho tab Media Player.</div>
    </div>
```

- [ ] **Step 4: Add CSS for the list items, download spinner, and Now Playing card**

Find:

```css
.inbar button:active{transform:scale(.9);box-shadow:0 2px 8px rgba(232,69,60,.2);}
</style>
```

Replace with:

```css
.inbar button:active{transform:scale(.9);box-shadow:0 2px 8px rgba(232,69,60,.2);}

/* ── MEDIA PLAYER ──────────────────────────────── */
.list-item{display:flex;align-items:center;gap:10px;padding:9px 4px;border-bottom:1px solid var(--line);}
.list-item:last-child{border-bottom:0;}
.li-cover{width:44px;height:44px;border-radius:8px;object-fit:cover;background:var(--card2);flex-shrink:0;}
.li-info{flex:1;min-width:0;}
.li-title{font-size:13.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.li-sub{font-size:11.5px;color:var(--mut);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.li-play{flex-shrink:0;width:36px;height:36px;border-radius:50%;border:0;background:var(--grad);color:#fff;
  font-size:14px;cursor:pointer;box-shadow:0 3px 10px rgba(232,69,60,.3);}
.li-play.downloading{background:var(--card2);color:var(--a1);border:2px solid var(--line);
  border-top-color:var(--a1);animation:li-spin 0.8s linear infinite;box-shadow:none;}
.li-play.error{background:rgba(239,68,68,.12);color:var(--err);border:1px solid rgba(239,68,68,.3);box-shadow:none;}
@keyframes li-spin{to{transform:rotate(360deg);}}

#pmedia{position:relative;}
.now-playing{position:absolute;left:0;right:0;bottom:0;display:flex;align-items:center;gap:10px;
  padding:10px 13px;background:var(--card);border-top:1px solid var(--line);
  backdrop-filter:blur(12px);}
.now-playing[hidden]{display:none;}
.np-cover{width:40px;height:40px;border-radius:8px;object-fit:cover;background:var(--card2);flex-shrink:0;}
.np-info{flex:1;min-width:0;}
.np-title{font-size:12.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.np-artist{font-size:10.5px;color:var(--mut);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.np-info input[type=range]{width:100%;margin-top:3px;height:3px;}
.np-ctrl{display:flex;align-items:center;gap:2px;flex-shrink:0;}
.np-ctrl button{border:0;background:none;color:var(--fg);font-size:19px;padding:5px 6px;cursor:pointer;}
.np-ctrl button:disabled{opacity:.3;}
</style>
```

- [ ] **Step 5: Wire `tab()` to start/stop media polling, and populate `pytubeUrl` from state**

Find:

```javascript
function tab(t){['set','setup'].forEach(x=>{$('p'+x).classList.toggle('on',x===t);$('t'+x).classList.toggle('on',x===t);});}
```

Replace with:

```javascript
function tab(t){['set','setup','media'].forEach(x=>{$('p'+x).classList.toggle('on',x===t);$('t'+x).classList.toggle('on',x===t);});
  if(t==='media')mediaPollStart();else mediaPollStop();}
```

Find:

```javascript
    $('haUrl').value=s.ha_url||'';
```

Replace with:

```javascript
    $('pytubeUrl').value=s.pytube_base_url||'';
    $('haUrl').value=s.ha_url||'';
```

- [ ] **Step 6: Add the Media Player JS**

Find:

```javascript
poll();setInterval(poll,1500);
// loadVoices() now runs from render() once tts_host arrives (de-hardcoded server host).
```

Replace with:

```javascript
// ── Media Player ──────────────────────────────────
let mediaResultsCache=[];
let mediaSearchTimer=null;
let mediaState={playing:false,video_id:null,download_state:'idle'};
let mediaPollTimer=null;
let npSeekDragging=false;

function onMediaSearchInput(){
  clearTimeout(mediaSearchTimer);
  const q=$('mediaSearch').value.trim();
  mediaSearchTimer=setTimeout(()=>mediaSearch(q),400);
}
async function mediaSearch(q){
  if(!q){mediaResultsCache=[];renderMediaResults();return;}
  try{
    const j=await(await fetch('/api/media/search?q='+encodeURIComponent(q))).json();
    mediaResultsCache=j.ok?j.results:[];
    renderMediaResults(j.ok?null:j.error);
  }catch(e){mediaResultsCache=[];renderMediaResults('Lỗi tìm kiếm');}
}
function renderMediaResults(err){
  const el=$('mediaResults');
  if(err){el.innerHTML=`<div class="empty">✕ ${err}</div>`;return;}
  if(!mediaResultsCache.length){el.innerHTML='<div class="empty">🎵 Tìm bài hát để bắt đầu</div>';return;}
  el.innerHTML=mediaResultsCache.map(r=>`
    <div class="list-item">
      <img class="li-cover" src="${r.thumbnail}" alt="">
      <div class="li-info">
        <div class="li-title">${r.title.replace(/</g,'&lt;')}</div>
        <div class="li-sub">${r.artist}${r.duration?(' · '+r.duration):''}</div>
      </div>
      <button class="li-play" data-vid="${r.video_id}" onclick="mediaPlay('${r.video_id}')">▶</button>
    </div>`).join('');
  applyMediaButtonStates();
}
function applyMediaButtonStates(){
  document.querySelectorAll('.li-play').forEach(b=>{
    const active=b.dataset.vid===mediaState.video_id;
    b.classList.toggle('downloading',active&&mediaState.download_state==='downloading');
    b.classList.toggle('error',active&&mediaState.download_state==='error');
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
  const np=$('nowPlaying');
  if(!s.video_id){np.hidden=true;applyMediaButtonStates();return;}
  np.hidden=false;
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

poll();setInterval(poll,1500);
// loadVoices() now runs from render() once tts_host arrives (de-hardcoded server host).
```

- [ ] **Step 7: Manual verification**

This is a UI feature with no automated test coverage possible in this repo (see rationale above) — verify it for real:

1. Build and install: `cd /Users/lucnguyen/Documents/git/xiaozhi-android && ./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or push straight to the R1 per the project's existing device workflow).
2. **Fast-iteration path** (no reinstall needed to tweak HTML/JS): `adb push app/src/main/assets/control.html /sdcard/control.html` — `ControlServer.serveAsset()` prefers this file over the bundled asset.
3. On a phone/PC on the same wifi as the R1, open `http://<r1-ip>:8088/`.
4. Confirm the "Media Player" tab appears after Setup and switches panes correctly; confirm Settings/Setup still work unchanged.
5. In Setup, set **Pytube API** to the running `pytube_api.py` instance's URL (e.g. `http://<mac-mini-ip>:114`) and confirm it persists across a reload.
6. In Media Player, type a query — confirm results appear within ~400ms of the last keystroke (not on every keystroke — watch the Network tab), each with cover/title/artist/duration.
7. Tap Play on a track not yet cached — confirm the spinner ring shows on that item's button, the Now Playing card appears once ready, and audio plays through the R1's speaker.
8. Drag the seek bar — confirm the position jumps and playback continues from there.
9. Tap Next repeatedly to the end of the list — confirm Next disables on the last item, Prev disables on the first.
10. Search a different query while a track is playing — confirm Next disables (playing track no longer in the displayed list) per the Global Constraints.
11. Tap Play on a second track while the first is still downloading — confirm the first download's spinner is abandoned and only the second track ends up playing (no crash, no stuck spinner).
12. Confirm the Settings/Setup tabs' existing polling and controls (volume, LED test, HA device picker, etc.) still work — this feature must not regress them.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/assets/control.html
git commit -m "feat(media): add Media Player tab UI to control.html"
```

---

## Self-Review Notes

- **Spec coverage:** Reference screenshot's Cast-style card → MediaSession in Task 3. Search bar/results/cover/title/artist/duration/Play button → Task 5 Steps 1-2, 6. Download-progress ring → Task 5 Step 4 (`.li-play.downloading`) + Task 3/4 (`DownloadState`). Empty-list message → `renderMediaResults()`'s `.empty` branch. Drag-to-seek → `#npSeek` + Task 4's `/api/media/seek`. Next = next item in list → `mediaStep()`. "Config in server or not" → `Settings.pytubeBaseUrl`, Task 4 Step 1, surfaced in the Setup tab (Task 5 Step 3). Anti-spam (debounce, single in-flight download, reused TTL cache) → Task 5 Step 6 (`onMediaSearchInput` debounce) + Task 4's `markDownloading`/`play` replace-not-queue semantics + unmodified `pytube_api.py` cache.
- **Placeholder scan:** no TBD/TODO; every step has complete, runnable code.
- **Type consistency:** `MediaPlayerState`/`DownloadState` (Task 3) match the field names `buildMediaState()` reads (Task 4) match the JSON keys `renderNowPlaying()`/`applyMediaButtonStates()` read (Task 5) — `playing`, `video_id`, `title`, `artist`, `cover_url`, `position_ms`, `duration_ms`, `download_state` used identically end to end.
