package info.dourok.voicebot.mcp;

import info.dourok.voicebot.net.Https;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client side of MCP, for one server the owner added in the panel: this speaker asks that
 * server for its tools and calls them on behalf of the assistant.
 *
 * Both HTTP transports are spoken:
 * <ul>
 *   <li>Streamable HTTP (MCP 2025-03-26 and later): every message is a POST to one URL, answered
 *   with JSON or with a short SSE stream; the server may hand out an {@code Mcp-Session-Id} that is
 *   sent back on every later request.</li>
 *   <li>The older HTTP+SSE transport (2024-11-05), chosen when the URL ends in {@code /sse}: a GET
 *   opens an event stream whose first event names the URL to POST to, and the answers arrive on
 *   that stream. This is what gateways such as supergateway and mcp-proxy put in front of stdio
 *   servers.</li>
 * </ul>
 * Over HTTP+SSE each operation opens its own stream and initializes on it; it costs a round trip
 * or two, and nothing has to be kept open on a box that sleeps between conversations.
 *
 * Plain Java on org.json, so it runs on the JDK against a fake server in tests.
 */
public final class RemoteMcpClient {

    /** One tool as the remote server describes it. */
    public static final class RemoteTool {
        public final String name;
        public final String description;
        public final JSONObject inputSchema;

        RemoteTool(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final int BODY_LIMIT = 1024 * 1024;
    private static final int MAX_PAGES = 10;

    private final String url;
    private final String token;
    private final boolean legacySse;
    private final AtomicInteger nextId = new AtomicInteger(1);

    // Streamable HTTP session: kept between calls, dropped when the server forgets it (HTTP 404).
    private String sessionId;
    private String protocolVersion;

    /** @param token sent as {@code Authorization: Bearer <token>}; blank for none. */
    public RemoteMcpClient(String url, String token) {
        String u = url == null ? "" : url.trim();
        if (!u.regionMatches(true, 0, "http://", 0, 7) && !u.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalArgumentException("địa chỉ máy chủ MCP phải bắt đầu bằng http:// hoặc https://");
        }
        this.url = u;
        this.token = token == null ? "" : token.trim();
        String path = u.replaceAll("[?#].*$", "");
        this.legacySse = path.endsWith("/sse");
    }

    /** Every tool the server offers, following pagination. */
    public synchronized List<RemoteTool> listTools() throws IOException, JSONException {
        List<RemoteTool> out = new ArrayList<RemoteTool>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject params = new JSONObject();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JSONObject result = request("tools/list", params);
            JSONArray tools = result.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.getJSONObject(i);
                    String name = t.optString("name", "");
                    if (name.isEmpty()) {
                        continue;
                    }
                    JSONObject schema = t.optJSONObject("inputSchema");
                    if (schema == null) {
                        schema = new JSONObject().put("type", "object").put("properties", new JSONObject());
                    }
                    out.add(new RemoteTool(name, t.optString("description", ""), schema));
                }
            }
            cursor = result.optString("nextCursor", "");
            if (cursor.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /** The {@code result} of tools/call: {@code content[]} and {@code isError}. */
    public synchronized JSONObject callTool(String name, JSONObject arguments) throws IOException, JSONException {
        return request("tools/call", new JSONObject().put("name", name).put("arguments", arguments));
    }

    private JSONObject request(String method, JSONObject params) throws IOException, JSONException {
        if (legacySse) {
            return sseExchange(method, params);
        }
        if (sessionId == null && protocolVersion == null) {
            initialize();
        }
        try {
            return post(method, params);
        } catch (SessionExpired e) {
            // The server restarted or dropped the session: start a new one, once.
            sessionId = null;
            protocolVersion = null;
            initialize();
            return post(method, params);
        }
    }

    // ── Streamable HTTP ──────────────────────────────────────────────────────────────────────────

    private static final class SessionExpired extends IOException {
        SessionExpired() {
            super("phiên MCP đã hết hạn");
        }
    }

    private void initialize() throws IOException, JSONException {
        JSONObject result = post("initialize", initializeParams());
        protocolVersion = result.optString("protocolVersion", PROTOCOL_VERSION);
        notifyInitialized();
    }

    private void notifyInitialized() throws IOException, JSONException {
        JSONObject note = new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized");
        HttpURLConnection c = Https.post(url, headers(true), note.toString().getBytes(StandardCharsets.UTF_8));
        try {
            int code = c.getResponseCode();
            if (code >= 400 && code != 405) {
                throw new IOException("máy chủ MCP từ chối notifications/initialized: HTTP " + code);
            }
        } finally {
            c.disconnect();
        }
    }

    private JSONObject post(String method, JSONObject params) throws IOException, JSONException {
        int id = nextId.getAndIncrement();
        JSONObject message = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
                .put("params", params);
        HttpURLConnection c = Https.post(url, headers(!method.equals("initialize")),
                message.toString().getBytes(StandardCharsets.UTF_8));
        try {
            int code = c.getResponseCode();
            if (code == 404 && sessionId != null) {
                throw new SessionExpired();
            }
            if (code == 401 || code == 403) {
                throw new IOException("máy chủ MCP từ chối truy cập (HTTP " + code + "): kiểm tra token");
            }
            if (code < 200 || code > 299) {
                throw new IOException("máy chủ MCP trả về HTTP " + code);
            }
            String session = c.getHeaderField("Mcp-Session-Id");
            if (session != null && !session.isEmpty()) {
                sessionId = session;
            }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            JSONObject reply;
            InputStream in = c.getInputStream();
            try {
                reply = type.startsWith("text/event-stream")
                        ? readSseReply(in, id)
                        : new JSONObject(readAll(in));
            } finally {
                in.close();
            }
            return resultOf(reply);
        } finally {
            c.disconnect();
        }
    }

    private Map<String, String> headers(boolean inSession) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        if (!token.isEmpty()) {
            h.put("Authorization", "Bearer " + token);
        }
        if (inSession && sessionId != null) {
            h.put("Mcp-Session-Id", sessionId);
        }
        if (inSession && protocolVersion != null) {
            h.put("MCP-Protocol-Version", protocolVersion);
        }
        return h;
    }

    // ── HTTP+SSE (2024-11-05) ────────────────────────────────────────────────────────────────────

    private JSONObject sseExchange(String method, JSONObject params) throws IOException, JSONException {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "text/event-stream");
        if (!token.isEmpty()) {
            h.put("Authorization", "Bearer " + token);
        }
        HttpURLConnection stream = Https.get(url, h);
        try {
            int code = stream.getResponseCode();
            if (code == 401 || code == 403) {
                throw new IOException("máy chủ MCP từ chối truy cập (HTTP " + code + "): kiểm tra token");
            }
            if (code != 200) {
                throw new IOException("máy chủ MCP trả về HTTP " + code);
            }
            SseReader events = new SseReader(stream.getInputStream());
            String endpoint = null;
            String[] event;
            while ((event = events.next()) != null) {
                if ("endpoint".equals(event[0])) {
                    endpoint = new URL(new URL(url), event[1].trim()).toString();
                    break;
                }
            }
            if (endpoint == null) {
                throw new IOException("máy chủ MCP không gửi địa chỉ endpoint");
            }
            int initId = nextId.getAndIncrement();
            ssePost(endpoint, new JSONObject().put("jsonrpc", "2.0").put("id", initId)
                    .put("method", "initialize").put("params", initializeParams()));
            resultOf(awaitReply(events, initId));
            ssePost(endpoint, new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
            int id = nextId.getAndIncrement();
            ssePost(endpoint, new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
                    .put("params", params));
            return resultOf(awaitReply(events, id));
        } finally {
            stream.disconnect();
        }
    }

    private void ssePost(String endpoint, JSONObject message) throws IOException {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        if (!token.isEmpty()) {
            h.put("Authorization", "Bearer " + token);
        }
        HttpURLConnection c = Https.post(endpoint, h, message.toString().getBytes(StandardCharsets.UTF_8));
        try {
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("máy chủ MCP trả về HTTP " + code);
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject awaitReply(SseReader events, int id) throws IOException {
        String[] event;
        while ((event = events.next()) != null) {
            JSONObject reply = matching(event, id);
            if (reply != null) {
                return reply;
            }
        }
        throw new IOException("máy chủ MCP đóng kết nối trước khi trả lời");
    }

    // ── Shared ───────────────────────────────────────────────────────────────────────────────────

    private static JSONObject initializeParams() throws JSONException {
        return new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "db-robot-r1").put("version", "1"));
    }

    /** The reply to request {@code id} on an SSE response; other messages on it are skipped. */
    static JSONObject readSseReply(InputStream in, int id) throws IOException {
        SseReader events = new SseReader(in);
        String[] event;
        while ((event = events.next()) != null) {
            JSONObject reply = matching(event, id);
            if (reply != null) {
                return reply;
            }
        }
        throw new IOException("máy chủ MCP không trả lời yêu cầu " + id);
    }

    private static JSONObject matching(String[] event, int id) {
        if (!"message".equals(event[0])) {
            return null;
        }
        try {
            JSONObject m = new JSONObject(event[1]);
            return m.has("id") && m.optInt("id", -1) == id && (m.has("result") || m.has("error")) ? m : null;
        } catch (JSONException e) {
            return null;   // not JSON-RPC: a keep-alive or a server-side log line
        }
    }

    private static JSONObject resultOf(JSONObject reply) throws IOException, JSONException {
        JSONObject error = reply.optJSONObject("error");
        if (error != null) {
            throw new IOException("máy chủ MCP báo lỗi: " + error.optString("message", error.toString()));
        }
        JSONObject result = reply.optJSONObject("result");
        if (result == null) {
            throw new IOException("máy chủ MCP trả lời không có result");
        }
        return result;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > BODY_LIMIT) {
                throw new IOException("phản hồi của máy chủ MCP quá lớn");
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Server-sent events, one {event, data} pair at a time; event defaults to "message". */
    static final class SseReader {
        private final BufferedReader reader;
        private int read;

        SseReader(InputStream in) {
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        /** @return {event, data}, or null at the end of the stream. */
        String[] next() throws IOException {
            String event = "message";
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                read += line.length();
                if (read > BODY_LIMIT) {
                    throw new IOException("phản hồi của máy chủ MCP quá lớn");
                }
                if (line.isEmpty()) {
                    if (data != null) {
                        return new String[]{event, data.toString()};
                    }
                    event = "message";
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (field.equals("event")) {
                    event = value;
                } else if (field.equals("data")) {
                    if (data == null) {
                        data = new StringBuilder(value);
                    } else {
                        data.append('\n').append(value);
                    }
                }
            }
            return data == null ? null : new String[]{event, data.toString()};
        }
    }
}
