package info.dourok.voicebot.ha;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What to ask Home Assistant for, given a device's domain and what the user wants done:
 * {@code turn_on} on a cover is {@code cover.open_cover}, {@code set_brightness 30} on a light is
 * {@code light.turn_on} with {@code brightness_pct: 30}, and so on. Plain Java, so it runs off the device.
 */
public final class HaActions {
    private HaActions() {}

    public static final String[] ACTIONS = {
        "turn_on", "turn_off", "toggle", "open", "close", "stop", "set_brightness", "set_temperature",
        "set_position", "set_volume", "set_speed", "lock", "unlock", "press", "play", "pause",
    };

    /** One Home Assistant service call. */
    public static final class Call {
        public final String domain, service;
        public final Map<String, Object> data = new LinkedHashMap<>();

        Call(String domain, String service) {
            this.domain = domain;
            this.service = service;
        }

        Call with(String k, Object v) {
            data.put(k, v);
            return this;
        }
    }

    /** Spoken or loose action names → the ones above. */
    public static String normalize(String action) {
        String a = HaDevices.norm(action).replace(' ', '_');
        switch (a) {
            case "on": case "bat": case "mo_len": case "turnon": case "enable": return "turn_on";
            case "off": case "tat": case "turnoff": case "disable": return "turn_off";
            case "mo": return "open";
            case "dong": return "close";
            case "dung": return "stop";
            case "brightness": case "do_sang": return "set_brightness";
            case "temperature": case "nhiet_do": return "set_temperature";
            case "position": return "set_position";
            case "volume": case "am_luong": return "set_volume";
            case "speed": case "toc_do": return "set_speed";
            case "khoa": return "lock";
            case "mo_khoa": return "unlock";
            case "nhan": return "press";
            case "phat": return "play";
            case "tam_dung": return "pause";
            default: return a;
        }
    }

    /**
     * @param value the number some actions need (brightness %, temperature °C, position %, volume %,
     *              speed %); NaN when none was given
     * @throws IllegalArgumentException with a sentence for the assistant when it cannot be done
     */
    public static Call plan(String entityId, String action, double value) {
        String domain = entityId.contains(".") ? entityId.substring(0, entityId.indexOf('.')) : "";
        String a = normalize(action);
        boolean has = !Double.isNaN(value);
        switch (a) {
            case "turn_on":
                switch (domain) {
                    case "cover": return new Call(domain, "open_cover");
                    case "lock": return new Call(domain, "unlock");
                    case "valve": return new Call(domain, "open_valve");
                    case "button": case "input_button": return new Call(domain, "press");
                    case "vacuum": return new Call(domain, "start");
                    case "scene": case "script": return new Call(domain, "turn_on");
                    case "automation": return new Call(domain, "trigger");
                    case "sensor": case "binary_sensor": throw readOnly(entityId);
                    default: return new Call(domain, "turn_on");
                }
            case "turn_off":
                switch (domain) {
                    case "cover": return new Call(domain, "close_cover");
                    case "lock": return new Call(domain, "lock");
                    case "valve": return new Call(domain, "close_valve");
                    case "vacuum": return new Call(domain, "return_to_base");
                    case "scene": case "button": case "input_button":
                        throw new IllegalArgumentException(entityId + " chỉ chạy được, không tắt được.");
                    case "sensor": case "binary_sensor": throw readOnly(entityId);
                    default: return new Call(domain, "turn_off");
                }
            case "toggle":
                if (domain.equals("sensor") || domain.equals("binary_sensor")) throw readOnly(entityId);
                if (domain.equals("scene") || domain.equals("script")) return new Call(domain, "turn_on");
                return new Call(domain, "toggle");
            case "open":
                if (domain.equals("cover")) return new Call(domain, "open_cover");
                if (domain.equals("lock")) return new Call(domain, "unlock");
                if (domain.equals("valve")) return new Call(domain, "open_valve");
                return plan(entityId, "turn_on", value);
            case "close":
                if (domain.equals("cover")) return new Call(domain, "close_cover");
                if (domain.equals("lock")) return new Call(domain, "lock");
                if (domain.equals("valve")) return new Call(domain, "close_valve");
                return plan(entityId, "turn_off", value);
            case "stop":
                if (domain.equals("cover")) return new Call(domain, "stop_cover");
                if (domain.equals("media_player")) return new Call(domain, "media_stop");
                if (domain.equals("vacuum")) return new Call(domain, "stop");
                return plan(entityId, "turn_off", value);
            case "set_brightness":
                need(has, "độ sáng (0–100%)");
                if (!domain.equals("light")) throw new IllegalArgumentException(entityId + " không chỉnh được độ sáng.");
                if (value <= 0) return new Call(domain, "turn_off");
                return new Call(domain, "turn_on").with("brightness_pct", clamp(value, 1, 100));
            case "set_temperature":
                need(has, "nhiệt độ (°C)");
                if (!domain.equals("climate") && !domain.equals("water_heater"))
                    throw new IllegalArgumentException(entityId + " không đặt được nhiệt độ.");
                return new Call(domain, "set_temperature").with("temperature", value);
            case "set_position":
                need(has, "vị trí (0–100%)");
                if (!domain.equals("cover")) throw new IllegalArgumentException(entityId + " không có vị trí để đặt.");
                return new Call(domain, "set_cover_position").with("position", clamp(value, 0, 100));
            case "set_volume":
                need(has, "âm lượng (0–100%)");
                if (!domain.equals("media_player")) throw new IllegalArgumentException(entityId + " không chỉnh được âm lượng.");
                return new Call(domain, "volume_set").with("volume_level", clamp(value, 0, 100) / 100.0);
            case "set_speed":
                need(has, "tốc độ (0–100%)");
                if (!domain.equals("fan")) throw new IllegalArgumentException(entityId + " không chỉnh được tốc độ.");
                if (value <= 0) return new Call(domain, "turn_off");
                return new Call(domain, "set_percentage").with("percentage", clamp(value, 1, 100));
            case "lock":
                if (!domain.equals("lock")) return plan(entityId, "turn_off", value);
                return new Call(domain, "lock");
            case "unlock":
                if (!domain.equals("lock")) return plan(entityId, "turn_on", value);
                return new Call(domain, "unlock");
            case "press":
                if (domain.equals("button") || domain.equals("input_button")) return new Call(domain, "press");
                return plan(entityId, "turn_on", value);
            case "play":
                if (!domain.equals("media_player")) return plan(entityId, "turn_on", value);
                return new Call(domain, "media_play");
            case "pause":
                if (!domain.equals("media_player")) throw new IllegalArgumentException(entityId + " không tạm dừng được.");
                return new Call(domain, "media_pause");
            default:
                StringBuilder list = new StringBuilder();
                for (String x : ACTIONS) list.append(list.length() > 0 ? ", " : "").append(x);
                throw new IllegalArgumentException("Không hiểu thao tác \"" + action + "\". Dùng một trong: " + list + ".");
        }
    }

    private static IllegalArgumentException readOnly(String id) {
        return new IllegalArgumentException(id + " là cảm biến, chỉ đọc được trạng thái.");
    }

    private static void need(boolean has, String what) {
        if (!has) throw new IllegalArgumentException("Thiếu giá trị " + what + " trong tham số value.");
    }

    private static int clamp(double v, int lo, int hi) {
        return (int) Math.round(Math.max(lo, Math.min(hi, v)));
    }

    /** "on" → "đang bật", for reading a state out. */
    public static String stateWords(String domain, String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        switch (s) {
            case "on": return "đang bật";
            case "off": return "đang tắt";
            case "open": return "đang mở";
            case "closed": return "đang đóng";
            case "opening": return "đang mở ra";
            case "closing": return "đang đóng lại";
            case "locked": return "đã khoá";
            case "unlocked": return "đang mở khoá";
            case "playing": return "đang phát";
            case "paused": return "đang tạm dừng";
            case "idle": return "đang chờ";
            case "unavailable": return "không kết nối được";
            case "unknown": return "chưa rõ trạng thái";
            case "heat": return "đang sưởi";
            case "cool": return "đang làm lạnh";
            case "auto": return "chế độ tự động";
            case "dry": return "đang hút ẩm";
            case "fan_only": return "chỉ quạt";
            default: return state;
        }
    }
}
