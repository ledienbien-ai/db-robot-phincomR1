package info.dourok.voicebot.control;

import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The picture behind the panel's chat. It is kept on the speaker rather than in one browser, so
 * the phone it was chosen on and every other device in the house show the same one.
 *
 * The panel shrinks the picture before sending it (see control.html), so what arrives is a JPEG of
 * a few hundred kilobytes; this only checks that it is a picture and of a sane size, and stores it.
 */
public final class ChatBackground {

    /** Decoded size limit. The panel sends far less; this stops anything else filling the disk. */
    static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final String NAME = "chat_bg.img";

    private ChatBackground() {}

    public static File file(File dir) {
        return new File(dir, NAME);
    }

    /** Changes whenever the picture does; 0 when there is none. The panel uses it as a cache key. */
    public static long stamp(File dir) {
        File f = file(dir);
        return f.isFile() ? f.lastModified() : 0;
    }

    /** "image/jpeg", "image/png" or "image/webp", from the stored file's first bytes. */
    public static String mime(File f) {
        byte[] head = new byte[12];
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            int n = in.read(head);
            return n < 12 ? "" : mimeOf(head);
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * @param body the picture as base64, with or without a {@code data:image/…;base64,} prefix
     * @return JSON for the panel: {@code {"ok":true,"stamp":…}} or {@code {"ok":false,"error":…}}
     */
    public static String save(File dir, String body) {
        if (body == null || body.isEmpty()) return error("Không nhận được ảnh.");
        String b64 = body;
        int comma = body.indexOf(',');
        if (body.startsWith("data:") && comma > 0) b64 = body.substring(comma + 1);
        if (b64.length() > MAX_BYTES / 3 * 4 + 8) return error("Ảnh quá lớn.");
        byte[] bytes;
        try {
            bytes = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return error("Dữ liệu ảnh không hợp lệ.");
        }
        if (bytes.length < 12 || mimeOf(bytes).isEmpty()) return error("Tệp này không phải ảnh JPEG, PNG hay WebP.");
        if (bytes.length > MAX_BYTES) return error("Ảnh quá lớn.");

        // Written beside the real file and renamed over it, so a browser fetching the picture
        // at this moment gets the old one or the new one, never half of each.
        File tmp = new File(dir, NAME + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(bytes);
            out.close();
            out = null;
            File dst = file(dir);
            if (!tmp.renameTo(dst)) {
                dst.delete();
                if (!tmp.renameTo(dst)) return error("Loa không lưu được ảnh.");
            }
            return new JSONObject().put("ok", true).put("stamp", stamp(dir)).toString();
        } catch (Exception e) {
            tmp.delete();
            return error("Loa không lưu được ảnh: " + e.getMessage());
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    public static String clear(File dir) {
        File f = file(dir);
        if (f.exists() && !f.delete()) return error("Loa không xoá được ảnh.");
        return "{\"ok\":true,\"stamp\":0}";
    }

    /** The type the first bytes say this is, or "" when it is none of the three accepted. */
    static String mimeOf(byte[] b) {
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return "";
    }

    private static String error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}
