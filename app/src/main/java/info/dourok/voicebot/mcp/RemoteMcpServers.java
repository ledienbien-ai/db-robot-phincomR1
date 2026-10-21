package info.dourok.voicebot.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The MCP servers the owner added in the panel, offered to the assistant next to this speaker's
 * own tools (see {@link DeviceMcp#addSource}). A server's tool "search" shows up as
 * {@code <server name>.search}, the way the device's own tools are {@code self.*}.
 *
 * The assistant's server asks for the tool list at the start of every conversation, and the
 * speaker connects only when woken -- so the list is served from a cache and refreshed in the
 * background, never fetched while someone is waiting for an answer. A server that cannot be
 * reached keeps the tools it last listed; calling one then reports the error in words.
 *
 * Configuration is a JSON array of {@code {name, url, token, enabled}}, kept by the caller.
 * Plain Java, so it runs on the JDK in tests.
 */
public final class RemoteMcpServers implements DeviceMcp.ToolSource {

    /** Where status lines go: the app's log in production, nowhere in tests. */
    public interface Log {
        void line(String message);
    }

    private static final long STALE_MS = 10 * 60 * 1000L;
    private static final int NAME_LIMIT = 64;

    private static final class Entry {
        final String name;
        final String url;
        final String token;
        final boolean enabled;
        final RemoteMcpClient client;
        volatile List<RemoteMcpClient.RemoteTool> tools = Collections.emptyList();
        volatile String error = "";
        volatile long checkedMs;
        volatile boolean refreshing;

        Entry(String name, String url, String token, boolean enabled) {
            this.name = name;
            this.url = url;
            this.token = token;
            this.enabled = enabled;
            RemoteMcpClient c = null;
            try {
                c = new RemoteMcpClient(url, token);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
            this.client = c;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Log log;
    private volatile List<Entry> entries = Collections.emptyList();
    private String configured;

    public RemoteMcpServers(Log log) {
        this.log = log;
    }

    /** Replace the server list with {@code json} and fetch every enabled server's tools. */
    public synchronized void configure(String json) {
        if (json != null && json.equals(configured)) {
            return;   // the same list again: keep the tools already fetched
        }
        configured = json;
        List<Entry> next = new ArrayList<Entry>();
        Set<String> names = new HashSet<String>();
        JSONArray list = parse(json);
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null || s.optString("url", "").trim().isEmpty()) {
                continue;
            }
            String name = uniqueName(prefix(s.optString("name", "")), names);
            next.add(new Entry(name, s.optString("url", "").trim(), s.optString("token", ""),
                    s.optBoolean("enabled", true)));
        }
        entries = next;
        for (Entry e : next) {
            refresh(e);
        }
    }

    @Override
    public List<DeviceMcp.Tool> tools() {
        List<DeviceMcp.Tool> out = new ArrayList<DeviceMcp.Tool>();
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            if (!e.enabled || e.client == null) {
                continue;
            }
            if (now - e.checkedMs > STALE_MS) {
                refresh(e);
            }
            for (RemoteMcpClient.RemoteTool t : e.tools) {
                out.add(wrap(e, t));
            }
        }
        return out;
    }

    /** What the panel shows: each server, whether it answered, and its tools. Tokens are not included. */
    public String stateJson() {
        JSONArray servers = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONArray names = new JSONArray();
                for (RemoteMcpClient.RemoteTool t : e.tools) {
                    names.put(t.name);
                }
                servers.put(new JSONObject()
                        .put("name", e.name)
                        .put("url", e.url)
                        .put("token_set", !e.token.isEmpty())
                        .put("enabled", e.enabled)
                        .put("ok", e.error.isEmpty() && e.checkedMs > 0)
                        .put("error", e.error)
                        .put("refreshing", e.refreshing)
                        .put("checked_ms", e.checkedMs)
                        .put("tools", names));
            }
            return new JSONObject().put("ok", true).put("servers", servers).toString();
        } catch (JSONException ex) {
            return "{\"ok\":false}";
        }
    }

    /** Fetch every server's tools again, now. */
    public void refreshAll() {
        for (Entry e : entries) {
            refresh(e);
        }
    }

    /** Connect to one server and list its tools, for the panel's Test button. Never throws. */
    public static String test(String url, String token) {
        try {
            List<RemoteMcpClient.RemoteTool> tools = new RemoteMcpClient(url, token).listTools();
            JSONArray names = new JSONArray();
            for (RemoteMcpClient.RemoteTool t : tools) {
                names.put(t.name);
            }
            return new JSONObject().put("ok", true).put("tools", names).toString();
        } catch (Exception e) {
            return failure(e);
        }
    }

    /**
     * The list to store when the panel saves {@code incoming}: a server sent without a token keeps
     * the one already saved under the same address, so the panel never has to hold tokens.
     */
    public static String merge(String incoming, String saved) {
        JSONArray in = parse(incoming);
        JSONArray old = parse(saved);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            if (s == null) {
                continue;
            }
            String url = s.optString("url", "").trim();
            if (url.isEmpty()) {
                continue;
            }
            String token = s.optString("token", "");
            if (token.isEmpty() && !s.optBoolean("clear_token", false)) {
                for (int j = 0; j < old.length(); j++) {
                    JSONObject o = old.optJSONObject(j);
                    if (o != null && url.equals(o.optString("url", "").trim())) {
                        token = o.optString("token", "");
                        break;
                    }
                }
            }
            try {
                out.put(new JSONObject()
                        .put("name", s.optString("name", "").trim())
                        .put("url", url)
                        .put("token", token)
                        .put("enabled", s.optBoolean("enabled", true)));
            } catch (JSONException e) {
                // cannot happen with strings and booleans
            }
        }
        return out.toString();
    }

    /** A server name as a tool-name prefix: lower case ASCII letters, digits and underscores ("Tìm kiếm" -> tim_kiem). */
    static String prefix(String name) {
        String ascii = java.text.Normalizer.normalize(name.trim().toLowerCase().replace('đ', 'd'), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String p = ascii.replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (p.isEmpty() || p.equals("self")) {
            p = "mcp";
        }
        return p.length() > 20 ? p.substring(0, 20) : p;
    }

    private static String uniqueName(String base, Set<String> taken) {
        String name = base;
        for (int n = 2; taken.contains(name); n++) {
            name = base + n;
        }
        taken.add(name);
        return name;
    }

    /** The text the assistant reads from a tools/call result; an isError result becomes an exception. */
    static String text(JSONObject result) {
        StringBuilder out = new StringBuilder();
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject part = content.optJSONObject(i);
                if (part == null) {
                    continue;
                }
                String type = part.optString("type", "");
                String piece;
                if (type.equals("text")) {
                    piece = part.optString("text", "");
                } else if (type.equals("resource")) {
                    JSONObject r = part.optJSONObject("resource");
                    piece = r == null ? "" : r.optString("text", r.optString("uri", ""));
                } else {
                    piece = "[" + type + "]";   // images and audio cannot be read aloud
                }
                if (!piece.isEmpty()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(piece);
                }
            }
        }
        JSONObject structured = result.optJSONObject("structuredContent");
        if (out.length() == 0 && structured != null) {
            out.append(structured.toString());
        }
        if (result.optBoolean("isError", false)) {
            throw new IllegalStateException(out.length() == 0 ? "công cụ báo lỗi" : out.toString());
        }
        return out.toString();
    }

    private void refresh(final Entry e) {
        if (e.client == null || !e.enabled || e.refreshing) {
            return;
        }
        e.refreshing = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    e.tools = e.client.listTools();
                    e.error = "";
                    log.line("MCP " + e.name + ": " + e.tools.size() + " công cụ");
                } catch (Exception ex) {
                    e.error = describe(ex);
                    log.line("MCP " + e.name + ": " + e.error);
                } finally {
                    e.checkedMs = System.currentTimeMillis();
                    e.refreshing = false;
                }
            }
        });
    }

    private static DeviceMcp.Tool wrap(final Entry e, final RemoteMcpClient.RemoteTool t) {
        String full = e.name + "." + t.name;
        final String name = full.length() > NAME_LIMIT ? full.substring(0, NAME_LIMIT) : full;
        return new DeviceMcp.Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return t.description;
            }

            @Override
            public JSONObject inputSchema() {
                return t.inputSchema;
            }

            @Override
            public String call(JSONObject arguments) throws Exception {
                return text(e.client.callTool(t.name, arguments));
            }
        };
    }

    private static JSONArray parse(String json) {
        try {
            return new JSONArray(json == null || json.trim().isEmpty() ? "[]" : json);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) {
            return "không tìm thấy máy chủ " + (m == null ? "" : m);
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "máy chủ không trả lời kịp";
        }
        if (e instanceof java.net.ConnectException) {
            return "không kết nối được tới máy chủ";
        }
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static String failure(Exception e) {
        try {
            return new JSONObject().put("ok", false).put("error", describe(e)).toString();
        } catch (JSONException ex) {
            return "{\"ok\":false}";
        }
    }
}
