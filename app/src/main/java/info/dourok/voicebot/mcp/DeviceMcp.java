package info.dourok.voicebot.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The device side of xiaozhi's MCP channel: tools this speaker offers to the assistant.
 *
 * A server that supports it (the client says so with {@code "features":{"mcp":true}} in its hello)
 * sends JSON-RPC 2.0 requests wrapped as {@code {"type":"mcp","payload":{...}}}: first
 * {@code initialize}, then {@code tools/list}, and later a {@code tools/call} each time the
 * language model decides one of the tools answers what was asked. This class turns one such
 * payload into the payload to send back.
 *
 * It knows nothing about the transport or about any particular tool -- see DeviceTools for those --
 * and is plain Java on org.json so that the exchange can be run off the device.
 */
public final class DeviceMcp {

    /** One thing the assistant may ask this device to do. */
    public interface Tool {
        String name();

        /** Read by the language model: says what the tool returns and when to use it. */
        String description();

        /** JSON Schema of the arguments; {@code {"type":"object","properties":{}}} for none. */
        JSONObject inputSchema() throws JSONException;

        /** @return the text handed to the language model as the tool's result. */
        String call(JSONObject arguments) throws Exception;
    }

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    private final String name;
    private final String version;
    private final List<Tool> tools = new ArrayList<Tool>();

    public DeviceMcp(String name, String version) {
        this.name = name;
        this.version = version;
    }

    public DeviceMcp add(Tool tool) {
        tools.add(tool);
        return this;
    }

    /**
     * @return the JSON-RPC response for {@code request}, or null when none is due (a notification,
     *         or something that is not a request at all).
     */
    public JSONObject handle(JSONObject request) {
        String method = request.optString("method", "");
        Object id = request.opt("id");
        if (method.isEmpty() || id == null || id == JSONObject.NULL) {
            return null;   // notifications carry no id and get no answer
        }
        try {
            if (method.equals("initialize")) {
                return result(id, new JSONObject()
                        .put("protocolVersion", PROTOCOL_VERSION)
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", name).put("version", version)));
            }
            if (method.equals("ping")) {
                return result(id, new JSONObject());
            }
            if (method.equals("tools/list")) {
                JSONArray list = new JSONArray();
                for (Tool t : tools) {
                    list.put(new JSONObject()
                            .put("name", t.name())
                            .put("description", t.description())
                            .put("inputSchema", t.inputSchema()));
                }
                // Everything fits in one page, so there is no nextCursor to follow.
                return result(id, new JSONObject().put("tools", list));
            }
            if (method.equals("tools/call")) {
                JSONObject params = request.optJSONObject("params");
                String wanted = params == null ? "" : params.optString("name", "");
                JSONObject arguments = params == null ? null : params.optJSONObject("arguments");
                if (arguments == null) {
                    arguments = new JSONObject();
                }
                for (Tool t : tools) {
                    if (t.name().equals(wanted)) {
                        return result(id, callTool(t, arguments));
                    }
                }
                return error(id, INVALID_PARAMS, "Unknown tool: " + wanted);
            }
            return error(id, METHOD_NOT_FOUND, "Method not found: " + method);
        } catch (JSONException e) {
            return null;   // could not even build a reply; nothing sensible to send
        }
    }

    /** A tool that fails still answers: the model is told what went wrong, in words. */
    private static JSONObject callTool(Tool tool, JSONObject arguments) throws JSONException {
        String text;
        boolean failed = false;
        try {
            text = tool.call(arguments);
        } catch (Exception e) {
            failed = true;
            text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        JSONObject out = new JSONObject()
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)))
                .put("isError", failed);
        if (failed) {
            out.put("error", text);   // the reference server reads the reason from here
        }
        return out;
    }

    private static JSONObject result(Object id, JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject error(Object id, int code, String message) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }
}
