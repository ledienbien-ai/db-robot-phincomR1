package info.dourok.voicebot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.news.NewsAlarmScheduler

/**
 * Launches the app on device boot (replaces aiboxplus auto-start), and re-derives the News alarm.
 *
 * Also listens for ACTION_TIME_CHANGED, which is the only signal that says the R1's wall clock has
 * become real. It boots on the ROM's own default (measured: 2018-01-20) and NTP corrects it seconds
 * later; an alarm derived from the first clock is years stale by the second, and a stale RTC alarm
 * fires on delivery. So the boot pass defers and this one does the scheduling.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_TIME_CHANGED) {
            Log.i("BootReceiver", "clock changed -> re-deriving the news alarm")
            try {
                Settings.init(context)
                NewsAlarmScheduler.reschedule(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "News alarm reschedule after time change failed", e)
            }
            return
        }
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "Boot completed -> launch MainActivity")
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(launch)
            } catch (e: Exception) {
                Log.e("BootReceiver", "launch failed", e)
            }
            // AlarmManager alarms do NOT survive reboot on their own -- re-derive the News
            // bulletin's daily alarms from the persisted Settings.
            try {
                Settings.init(context)
                NewsAlarmScheduler.reschedule(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "News alarm reschedule failed", e)
            }
        }
    }
}
