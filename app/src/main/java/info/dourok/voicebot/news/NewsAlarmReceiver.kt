package info.dourok.voicebot.news

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import info.dourok.voicebot.control.ControlServer
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.domain.voice.TextCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires for the News-bulletin daily alarm (see NewsAlarmScheduler). Sends the bulletin request as
 * a typed query -- the exact path the "Phát thử" button and /api/say use -- so the server's
 * get_news_bulletin tool produces and plays it. Self-reschedules for tomorrow after handling it,
 * so no persistent foreground service is needed to keep the daily cycle alive. */
class NewsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val appContext = context.applicationContext
        Settings.init(appContext)   // a broadcast can reach us before the app process is up
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (kind) {
                    "trigger" -> {
                        // An alarm firing is not on its own evidence that the scheduled minute has
                        // arrived: the R1 boots with the ROM's 2018 clock, the alarm is scheduled
                        // against it, and the moment NTP corrects the clock AlarmManager delivers
                        // that years-late RTC alarm at once -- which is the speaker reading the
                        // news to an empty room after a power cut. Ask what time it actually is.
                        val hm = parseNewsTime(Settings.newsTime)
                        if (hm == null) {
                            Log.w(TAG, "bad news_time '${Settings.newsTime}' -> not running")
                        } else if (!NewsSchedule.shouldRun(System.currentTimeMillis(), hm.first, hm.second)) {
                            Log.i(TAG, "news alarm ignored: fired away from ${Settings.newsTime}")
                            AppLog.i("Bỏ qua báo thức bản tin: chưa tới giờ ${Settings.newsTime} (đồng hồ máy vừa nhảy)")
                        } else {
                            Log.i(TAG, "news alarm -> sending '${ControlServer.NEWS_PHRASE}'")
                            AppLog.i("Tới giờ hẹn -> yêu cầu đọc bản tin")
                            TextCommands.flow.tryEmit(ControlServer.NEWS_PHRASE)
                        }
                    }
                    else -> Log.w(TAG, "unknown alarm kind: $kind")
                }
            } catch (e: Exception) {
                Log.e(TAG, "handling '$kind' failed", e)
                AppLog.e("Xử lý hẹn giờ '$kind' lỗi: ${e.message}")
            } finally {
                NewsAlarmScheduler.rescheduleOne(appContext, kind)
                pending.finish()
            }
        }
    }

    /** Same shape as NewsAlarmScheduler's own parse; kept here so the guard needs no scheduler. */
    private fun parseNewsTime(s: String): Pair<Int, Int>? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    companion object {
        private const val TAG = "NewsAlarmReceiver"
    }
}
