package info.dourok.voicebot.link

import info.dourok.voicebot.domain.voice.ConversationLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * The picture the display draws for the current conversation turn (1.5.38): weather, gold or fuel
 * prices, a lunar date, a Home Assistant device -- set by the tool that fetched them -- or a sum or
 * a country found in the words themselves ([CardText]). The display shows it over the voice page,
 * with the reply as a subtitle; a turn without one shows the subtitle alone.
 *
 * A card belongs to one turn: a new question clears it, unless a tool set it after that question
 * was heard. It goes to the display as its own small datagram ([SpeakerLink], `{"t":"card"}`).
 */
object DisplayCard {
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private var card: JSONObject? = null
    private var seq = 0
    private var setAt = 0L
    private var turn: ConversationLog.Entry? = null
    private var triedReply = ""

    /** A card from a tool; [kind] is what the display draws ("wx", "gold", "fuel", "lunar", "ha"). */
    @Synchronized
    fun show(kind: String, data: JSONObject) {
        data.put("k", kind)
        card = data
        seq++
        setAt = System.currentTimeMillis()
    }

    @Synchronized
    private fun clear() {
        if (card != null) {
            card = null
            seq++
        }
    }

    /** `{"t":"card","cs":seq,"k":…}`, k "" when there is nothing to draw. Called by SpeakerLink's push loop. */
    @Synchronized
    fun json(): String {
        follow(ConversationLog.recent())
        val c = card
        if (c != null && System.currentTimeMillis() - setAt > MAX_AGE_MS) clear()
        val o = JSONObject().put("t", "card").put("cs", seq)
        val now = card
        if (now == null) o.put("k", "") else {
            val it = now.keys()
            // "t" and "cs" are the message's own: a card field of that name would hide the card
            while (it.hasNext()) { val k = it.next(); if (k != "t" && k != "cs") o.put(k, now.get(k)) }
        }
        return o.toString()
    }

    /** A new question clears the last card; the words of the turn may then give one. */
    private fun follow(log: List<ConversationLog.Entry>) {
        val i = log.indexOfLast { it.sender == "user" }
        if (i < 0) return
        val q = log[i]
        if (q !== turn) {
            turn = q
            triedReply = ""
            // a tool card made after this question was heard is this turn's
            if (card != null && setAt < q.time - 1000) clear()
            if (card == null) CardText.findSum(q.text)?.let { sum(it) }
        }
        if (card != null) return
        val reply = log.drop(i + 1).filter { it.sender != "user" }.joinToString(" ") { it.text }
        if (reply.isEmpty() || reply == triedReply) return
        triedReply = reply
        val s = CardText.findSum(reply)
        if (s != null) { sum(s); return }
        CardText.findCountry(q.text, reply)?.let { c ->
            show("flag", JSONObject().put("cc", c.code).put("nm", c.name))
        }
    }

    private fun sum(s: CardText.Sum) {
        val o = JSONObject().put("e", s.expr).put("r", s.result)
        // the little pictures (apples) only for one small sum a child might ask
        if (s.a in 0..10 && s.b in 0..10 && (s.op == '+' || s.op == '−' || (s.op == '×' && s.a * s.b <= 30))) {
            o.put("a", s.a).put("b", s.b).put("op", s.op.toString())
        }
        show("math", o)
    }

    // ── cards from the tools ────────────────────────────────────────────────────────────────

    /** From Weather.panel(): now and the coming days. */
    fun weather(panel: JSONObject, place: String) {
        val f = JSONArray()
        val days = panel.optJSONArray("days")
        if (days != null) for (i in 0 until minOf(3, days.length())) {
            val d = days.getJSONObject(i)
            f.put(JSONArray().put(d.optString("label").replaceFirstChar { it.uppercase() })
                .put(d.optInt("code")).put(d.optInt("min")).put(d.optInt("max")).put(d.optInt("rain", -1)))
        }
        show("wx", JSONObject().put("pl", place.take(40)).put("tp", panel.optInt("temp")).put("c", panel.optInt("code"))
            .put("dy", if (panel.optBoolean("is_day", true)) 1 else 0).put("d", panel.optString("desc"))
            .put("h", panel.optInt("humidity", -1)).put("w", panel.optInt("wind", -1)).put("f", f))
    }

    /** Rows of [name, buy, sell] or [name, price, change], already in words the display shows as is. */
    fun table(kind: String, title: String, updated: String, rows: List<Array<String>>) {
        val r = JSONArray()
        var bytes = 0
        for (row in rows) {
            val a = JSONArray()
            row.forEach { a.put(it.take(32)) }
            bytes += a.toString().toByteArray().size
            if (bytes > 700 || r.length() >= 7) break
            r.put(a)
        }
        show(kind, JSONObject().put("ti", title).put("u", updated.take(40)).put("r", r))
    }

    /** A solar date and its lunar date. */
    fun lunar(d: Int, m: Int, y: Int, weekday: String, ld: Int, lm: Int, leap: Boolean, yearCanChi: String,
              dayCanChi: String, good: String) {
        show("lunar", JSONObject().put("sd", d).put("sm", m).put("sy", y).put("wd", weekday)
            .put("ld", ld).put("lm", lm).put("lp", if (leap) 1 else 0).put("ly", yearCanChi)
            .put("cc", dayCanChi).put("gh", good.take(120)))
    }

    /** A Home Assistant command that went through: the device kind ("light", "fan"...), on/off, and what was done. */
    fun home(domain: String, on: Boolean?, text: String) {
        val o = JSONObject().put("dm", domain).put("tx", text.take(120))
        if (on != null) o.put("on", if (on) 1 else 0)
        show("ha", o)
    }
}
