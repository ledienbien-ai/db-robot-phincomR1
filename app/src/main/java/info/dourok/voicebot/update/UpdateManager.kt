package info.dourok.voicebot.update

import android.content.Context
import android.util.Log
import info.dourok.voicebot.data.AppConfig
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.domain.voice.VoiceDebugState
import info.dourok.voicebot.media.LocalMusicPlayer
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The app's side of over-the-air updates: owns the one [Updater], tells it who we are, and keeps
 * asking in the background whether a newer build has been published.
 *
 * Finding a newer build only lights a notice in the control panel; installing waits for somebody to
 * press the button there -- unless "tự động cập nhật" is switched on, in which case it is installed
 * the next time the speaker is neither in a conversation nor playing music.
 *
 * Plain object, like [info.dourok.voicebot.data.ServerProvisioner]: the control server and the
 * application both reach it and neither should have to hand the other a reference.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"

    /** Readable by the adb shell user that performs the install, hence not app-private storage. */
    private const val APK_PATH = "/sdcard/dbrobot-update.apk"
    private const val LOG_PATH = "/sdcard/dbrobot-update.log"

    /** The R1 boots without wifi and on a 2018 clock; https cannot work until both are right. */
    private const val FIRST_CHECK_DELAY_MS = 90_000L
    private const val RETRY_MS = 10 * 60_000L
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L
    /** How often to look for a quiet moment once an automatic install is waiting for one. */
    private const val IDLE_POLL_MS = 2 * 60_000L
    /**
     * No automatic attempt this soon after any attempt, the owner's included. Without it a failed
     * press of the button was followed within the minute by the scheduler trying the same thing.
     */
    private const val AUTO_COOLDOWN_MS = 30 * 60_000L
    /** Automatic attempts per published version; see [mayAutoInstall]. */
    private const val MAX_AUTO_ATTEMPTS = 2

    @Volatile private var updater: Updater? = null

    val isUpdateAvailable: Boolean get() = updater?.isUpdateAvailable ?: false
    val latestName: String get() = updater?.latestName() ?: ""

    /** Call once from the application. Safe to call again; later calls do nothing. */
    @Synchronized
    fun init(context: Context) {
        if (updater != null) return
        val cfg = Updater.Config()
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            cfg.currentCode = info.versionCode
            cfg.currentName = info.versionName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "cannot read own version: ${e.message}")
        }
        cfg.manifestUrl = AppConfig.UPDATE_URL
        cfg.apkFile = File(APK_PATH)
        cfg.logFile = File(LOG_PATH)
        cfg.packageName = context.packageName
        cfg.launchComponent = context.packageName + "/info.dourok.voicebot.MainActivity"
        cfg.userAgent = "db-robot-r1/" + cfg.currentName
        val u = Updater(cfg)
        u.readLastInstallLog()
        updater = u
        thread(name = "update-schedule", isDaemon = true) { schedule(u) }
    }

    fun stateJson(): String {
        val u = updater ?: return """{"state":"idle","available":false}"""
        return try {
            JSONObject(u.toJson()).put("auto", Settings.autoUpdate).toString()
        } catch (e: Exception) {
            u.toJson()
        }
    }

    /** @return false if a check or an install is already running. */
    fun check(): Boolean = updater?.checkAsync() ?: false

    /** @return false if a check or an install is already running. */
    fun install(): Boolean {
        val u = updater ?: return false
        AppLog.i("Cập nhật phần mềm: bắt đầu tải và cài bản mới")
        return u.installAsync()
    }

    private fun schedule(u: Updater) {
        if (!sleep(FIRST_CHECK_DELAY_MS)) return
        while (true) {
            val due = System.currentTimeMillis() - u.lastCheckMs() >= CHECK_INTERVAL_MS
            if (due && !u.isBusy) {
                if (u.check()) AppLog.i("Có bản cập nhật mới: v${u.latestName()}")
            }
            val cooledDown = System.currentTimeMillis() - u.lastAttemptMs() >= AUTO_COOLDOWN_MS
            if (u.isUpdateAvailable && mayAutoInstall(u) && cooledDown && isQuiet() && !u.isBusy) {
                noteAutoAttempt(u)
                AppLog.i("Tự động cập nhật lên v${u.latestName()}")
                u.install()   // returns only if the install failed; success replaces this process
            }
            // Short naps only while an automatic install is waiting for a quiet moment; otherwise
            // this wakes every RETRY_MS, which is also what retries a check that has never worked.
            val waiting = u.isUpdateAvailable && mayAutoInstall(u)
            if (!sleep(if (waiting) IDLE_POLL_MS else RETRY_MS)) return
        }
    }

    /** Nobody is talking to the speaker and it is not playing music from the Media tab. */
    private fun isQuiet(): Boolean = !VoiceDebugState.awake && !LocalMusicPlayer.isActive

    /**
     * Automatic installs are counted per published version, and the count is persisted. Without
     * it a release whose update.json promises a higher version than its APK really carries would
     * install "successfully", come back up still older than the manifest, and install again --
     * for ever, restarting the speaker every few minutes. The button in the panel is not limited.
     */
    private fun mayAutoInstall(u: Updater): Boolean {
        if (!Settings.autoUpdate) return false
        val (code, count) = autoAttempts()
        return code != latestCode(u) || count < MAX_AUTO_ATTEMPTS
    }

    private fun noteAutoAttempt(u: Updater) {
        val latest = latestCode(u)
        val (code, count) = autoAttempts()
        Settings.autoUpdateTried = "$latest:${if (code == latest) count + 1 else 1}"
    }

    private fun autoAttempts(): Pair<Int, Int> {
        val parts = Settings.autoUpdateTried.split(":")
        return Pair(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun latestCode(u: Updater): Int = try {
        JSONObject(u.toJson()).optInt("latest_code", 0)
    } catch (e: Exception) {
        0
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }
}
