package info.dourok.voicebot.domain.voice

import info.dourok.voicebot.data.maskApiKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * The app's own event log, shown by the control panel's Log drawer.
 *
 * Deliberately NOT android.util.Log: release builds strip every Log call
 * (`-assumenosideeffects` in proguard-rules.pro), and on the R1 logcat is useless anyway -- the
 * 4-mic driver floods it continuously and evicts app lines within seconds. So the events worth
 * keeping get written here instead, where they survive both.
 *
 * Callers must never pay for logging: [add] only appends to an in-memory deque and hands the line
 * to a queue. A single daemon thread does the disk I/O, batching whatever has accumulated, so the
 * caller never blocks on the filesystem. Keep it to discrete events -- connects, config changes,
 * errors -- never per-audio-frame paths.
 */
object AppLog {
    enum class Level { I, W, E }

    data class Entry(
        val seq: Long,
        val level: Level,
        val time: Long,
        val msg: String,
    )

    private const val MAX_ENTRIES = 400          // what the panel can show
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val LOG_PATH = "/sdcard/voicebot-app.log"

    private val entries = ArrayDeque<Entry>()
    private var seqCounter = 0L
    private val pending = LinkedBlockingQueue<String>(2000)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private val writer = Thread {
        val batch = ArrayList<String>(128)
        while (true) {
            try {
                // Block for the first line, then sweep up anything else already queued: a burst of
                // events becomes one write instead of one write each.
                batch.add(pending.take())
                pending.drainTo(batch, 127)
                appendToFile(batch.joinToString("\n", postfix = "\n"))
                batch.clear()
            } catch (e: InterruptedException) {
                return@Thread
            } catch (e: Throwable) {
                batch.clear()   // never let a bad line kill the writer
            }
        }
    }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    fun i(msg: String) = add(Level.I, msg)
    fun w(msg: String) = add(Level.W, msg)
    fun e(msg: String) = add(Level.E, msg)

    fun add(level: Level, msg: String) {
        val clean = scrub(msg)
        val entry: Entry
        synchronized(this) {
            entry = Entry(++seqCounter, level, System.currentTimeMillis(), clean)
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        // offer, not put: if the writer ever falls behind, drop the disk copy rather than stall
        // the caller -- the in-memory buffer (what the panel reads) already has it.
        pending.offer("${stamp.format(Date(entry.time))} ${level.name} ${entry.msg}")
    }

    /** Entries newer than [since]; pass 0 for everything currently buffered. */
    @Synchronized
    fun since(since: Long): List<Entry> = entries.filter { it.seq > since }

    @Synchronized
    fun lastSeq(): Long = seqCounter

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /**
     * The panel is served over plain HTTP with no auth, so a token that wanders into a log line
     * would be readable by anyone on the wifi. Mask anything that looks like a credential.
     */
    private fun scrub(msg: String): String {
        var s = msg
        Regex("""(sk-[A-Za-z0-9_\-]{8,})""").findAll(s).forEach {
            s = s.replace(it.value, maskApiKey(it.value))
        }
        Regex("""(eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-.]+)""").findAll(s).forEach {
            s = s.replace(it.value, maskApiKey(it.value))
        }
        return s
    }

    private fun appendToFile(text: String) {
        val f = File(LOG_PATH)
        if (f.length() > MAX_FILE_BYTES) {
            // Halve it rather than delete: keeps the recent tail across a rotation, and one
            // rewrite every few hundred KB costs nothing on this device.
            runCatching {
                val keep = f.readText().takeLast(MAX_FILE_BYTES / 2).substringAfter('\n')
                f.writeText(keep)
            }
        }
        runCatching { f.appendText(text) }
    }
}
