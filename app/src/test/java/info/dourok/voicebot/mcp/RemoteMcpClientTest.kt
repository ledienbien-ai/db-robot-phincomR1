package info.dourok.voicebot.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The client against small fake servers of both MCP HTTP transports. */
class RemoteMcpClientTest {

    private val server = FakeHttpServer()
    private val base get() = server.base

    @AfterTest
    fun stop() = server.close()

    private fun result(id: Any, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun tool(name: String) = JSONObject().put("name", name).put("description", "does $name")
        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))

    /** A Streamable HTTP server: session ids, two pages of tools, SSE for tool calls. */
    private fun streamable(forgetSessionOnce: Boolean = false): MutableList<String> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        var sessions = 0
        var forgot = !forgetSessionOnce
        server.route("/mcp") { req, res ->
            val msg = JSONObject(req.body)
            val method = msg.optString("method")
            val session = req.header("Mcp-Session-Id")
            seen += "$method:${session ?: "-"}:${req.header("Authorization") ?: "-"}"
            when {
                method == "initialize" -> {
                    sessions++
                    res.send(200, result(msg.get("id"), JSONObject().put("protocolVersion", "2025-03-26")
                        .put("capabilities", JSONObject()).put("serverInfo", JSONObject().put("name", "fake"))),
                        extra = mapOf("Mcp-Session-Id" to "s$sessions"))
                }
                session != "s$sessions" -> res.send(400)
                !forgot && method == "tools/list" -> { forgot = true; res.send(404) }
                method == "notifications/initialized" -> res.send(202)
                method == "tools/list" -> {
                    val second = msg.getJSONObject("params").optString("cursor") == "p2"
                    val page = JSONObject().put("tools", JSONArray().put(tool(if (second) "b" else "a")))
                    if (!second) page.put("nextCursor", "p2")
                    res.send(200, result(msg.get("id"), page))
                }
                method == "tools/call" -> {
                    val text = "hello " + msg.getJSONObject("params").getJSONObject("arguments").optString("who")
                    val note = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/progress")
                    val answer = result(msg.get("id"), JSONObject().put("content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", text))))
                    res.send(200, "event: message\ndata: $note\n\n: keep-alive\n\nevent: message\ndata: $answer\n\n",
                        type = "text/event-stream")
                }
                else -> res.send(400)
            }
        }
        return seen
    }

    @Test
    fun `streamable HTTP keeps the session and follows pagination`() {
        val seen = streamable()
        val client = RemoteMcpClient("$base/mcp", "secret")
        assertEquals(listOf("a", "b"), client.listTools().map { it.name })
        val text = RemoteMcpServers.text(client.callTool("greet", JSONObject().put("who", "R1")))
        assertEquals("hello R1", text)
        assertEquals("initialize:-:Bearer secret", seen.first())
        assertTrue(seen.drop(1).all { it.contains(":s1:") }, "session id not sent back: $seen")
    }

    @Test
    fun `a forgotten session is started again once`() {
        val seen = streamable(forgetSessionOnce = true)
        val client = RemoteMcpClient("$base/mcp", "")
        assertEquals(listOf("a", "b"), client.listTools().map { it.name })
        assertEquals(2, seen.count { it.startsWith("initialize") })
    }

    @Test
    fun `the older HTTP+SSE transport answers on the event stream`() {
        val outbox = LinkedBlockingQueue<String>()
        server.route("/sse") { _, res ->
            res.startEvents()
            res.event("event: endpoint\ndata: /messages?sessionId=1\n\n")
            while (true) {
                val event = outbox.poll(5, TimeUnit.SECONDS) ?: break
                res.event(event)
                if (event.contains("\"tools\"")) break
            }
        }
        server.route("/messages") { req, res ->
            val msg = JSONObject(req.body)
            res.send(202)
            val answer = when (msg.optString("method")) {
                "initialize" -> result(msg.get("id"), JSONObject().put("protocolVersion", "2024-11-05"))
                "tools/list" -> result(msg.get("id"), JSONObject().put("tools", JSONArray().put(tool("legacy"))))
                else -> null
            }
            answer?.let { outbox.put("event: message\ndata: $it\n\n") }
        }
        assertEquals(listOf("legacy"), RemoteMcpClient("$base/sse", "").listTools().map { it.name })
    }

    @Test
    fun `a rejected token is reported as such`() {
        server.route("/mcp") { _, res -> res.send(401) }
        val e = assertFailsWith<java.io.IOException> { RemoteMcpClient("$base/mcp", "bad").listTools() }
        assertContains(e.message!!, "token")
    }

    @Test
    fun `only http and https addresses are accepted`() {
        assertFailsWith<IllegalArgumentException> { RemoteMcpClient("ftp://x/mcp", "") }
    }
}

class RemoteMcpServersTest {

    @Test
    fun `saving without a token keeps the one saved for that address`() {
        val saved = """[{"name":"web","url":"https://a/mcp","token":"t1","enabled":true}]"""
        val merged = JSONArray(RemoteMcpServers.merge("""[{"name":"Web","url":"https://a/mcp","token":""},{"url":"https://b/sse"}]""", saved))
        assertEquals("t1", merged.getJSONObject(0).getString("token"))
        assertEquals("", merged.getJSONObject(1).getString("token"))
        val cleared = JSONArray(RemoteMcpServers.merge("""[{"url":"https://a/mcp","token":"","clear_token":true}]""", saved))
        assertEquals("", cleared.getJSONObject(0).getString("token"))
    }

    @Test
    fun `server names become safe tool prefixes`() {
        assertEquals("tim_kiem_web", RemoteMcpServers.prefix(" Tìm kiếm WEB! "))
        assertEquals("mcp", RemoteMcpServers.prefix(""))
        assertEquals("mcp", RemoteMcpServers.prefix("self"))
    }

    @Test
    fun `an error result becomes an error the assistant can read`() {
        val r = JSONObject().put("isError", true)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "quota exceeded")))
        val e = assertFailsWith<IllegalStateException> { RemoteMcpServers.text(r) }
        assertEquals("quota exceeded", e.message)
    }

    @Test
    fun `remote tools are listed after the device's own`() {
        val mcp = DeviceMcp("t", "1").addSource(object : DeviceMcp.ToolSource {
            override fun tools(): List<DeviceMcp.Tool> = listOf(object : DeviceMcp.Tool {
                override fun name() = "web.search"
                override fun description() = ""
                override fun inputSchema() = JSONObject().put("type", "object")
                override fun call(arguments: JSONObject) = "found"
            })
        })
        val list = mcp.handle(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list"))!!
        assertEquals("web.search", list.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))
        val call = mcp.handle(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
            .put("params", JSONObject().put("name", "web.search").put("arguments", JSONObject())))!!
        assertEquals("found", call.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
    }
}
