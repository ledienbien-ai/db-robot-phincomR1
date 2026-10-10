package info.dourok.voicebot

import android.app.Application
import android.util.Log
import info.dourok.voicebot.domain.voice.AppLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import info.dourok.voicebot.control.ControlServer
import info.dourok.voicebot.data.ServerProvisioner
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.data.model.DeviceInfo
import info.dourok.voicebot.domain.bluetooth.BtController
import info.dourok.voicebot.update.UpdateManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

@HiltAndroidApp
class VApplication : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ControlEntryPoint {
        fun controlServer(): ControlServer
        fun btController(): BtController
        fun deviceInfo(): DeviceInfo
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        // Start the on-device control panel web server (http://<r1-ip>:8088).
        try {
            val entry = EntryPointAccessors.fromApplication(this, ControlEntryPoint::class.java)
            entry.controlServer().startServer()
            // Registers the Bluetooth receivers and reaches for the remembered speaker once. Here
            // rather than inside the panel: the panel is its only caller today, but reconnecting
            // after a power cut has to happen whether or not anybody opens a browser.
            entry.btController().start()
            ServerProvisioner.appVersion = entry.controlServer().appVersion
            checkOtaInBackground(entry.deviceInfo())
            // Picks up the result of an install that replaced the previous process, then keeps
            // looking for newer builds in the background.
            UpdateManager.init(this)
        } catch (e: Exception) {
            Log.e("VApplication", "control server start failed: ${e.message}")
        }
        // Log every Java/Kotlin crash to /sdcard/voicebot-crash.log for easy tracing (see tools/crashlog.sh).
        // (Native crashes inside .so produce a tombstone and are not caught here.)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File("/sdcard/voicebot-crash.log").appendText(
                    "\n===== CRASH $ts (thread=${thread.name}) =====\n$sw\n"
                )
                Log.e("VApplication", "CRASH", e)
                // Also into the app log the panel shows, so a crash is visible without pulling files.
                AppLog.e("CRASH (${thread.name}): ${e.javaClass.simpleName}: ${e.message}")
            } catch (_: Exception) {
            }
            prev?.uncaughtException(thread, e)  // let the system handle it (app still crashes as usual)
        }
    }

    /**
     * Ask the OTA endpoint for the WebSocket url and the activation code, off the main thread.
     * Retried because this runs at boot, when the R1 has usually not joined wifi yet; it stops at
     * the first reply. Nothing waits on it: if somebody says the wake word before it lands,
     * WebsocketProtocol asks the endpoint itself.
     */
    private fun checkOtaInBackground(deviceInfo: DeviceInfo) {
        if (Settings.otaUrl.isBlank()) return
        thread(name = "ota-check", isDaemon = true) {
            for (attempt in 1..OTA_ATTEMPTS) {
                val ok = try {
                    ServerProvisioner.provision(deviceInfo).ok
                } catch (e: Exception) {
                    false
                }
                if (ok) return@thread
                try {
                    Thread.sleep(OTA_RETRY_MS)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private companion object {
        const val OTA_ATTEMPTS = 20
        const val OTA_RETRY_MS = 15_000L
    }
}
