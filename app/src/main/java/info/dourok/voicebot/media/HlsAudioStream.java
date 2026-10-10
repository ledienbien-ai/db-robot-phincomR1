package info.dourok.voicebot.media;

import info.dourok.voicebot.net.Https;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * A live HLS audio broadcast, read as one continuous audio stream.
 *
 * Radio stations publish HLS: a playlist that is re-read every few seconds and names a handful of
 * short audio files. This class does the re-reading and the fetching and hands out the audio of
 * those files joined end to end, so that the device's player sees what it sees from the music
 * server -- one never-ending stream over plain http from the speaker itself -- instead of having
 * to speak HLS (and https with today's certificates) on a 2015 Android.
 *
 * Two segment formats are in use by the stations in RadioStations, and both become raw AAC frames
 * (ADTS) here:
 *  - "packed audio": AAC frames behind an ID3 tag that carries a timestamp -- the tag is dropped;
 *  - MPEG transport stream: the audio is cut out of its 188-byte packets.
 *
 * Reading blocks while it waits for the station to publish the next segment. Plain Java on the
 * JDK, so it can be run against a test broadcast off the device.
 */
public final class HlsAudioStream extends InputStream {
    /** How many of the newest segments to begin with: enough to start at once, close to live. */
    private static final int START_SEGMENTS = 3;
    private static final int MAX_PLAYLIST_DEPTH = 3;
    private static final int PLAYLIST_LIMIT = 256 * 1024;
    private static final int SEGMENT_LIMIT = 8 * 1024 * 1024;
    /** Give up when the station has published nothing new for this long. */
    private static final long STALL_LIMIT_MS = 40_000;
    private static final int TS_PACKET = 188;

    private final String userAgent;
    private String mediaUrl;
    private long nextSequence = -1;
    private long targetMs = 4000;
    private boolean ended;
    private boolean closed;
    private final ArrayDeque<String> pending = new ArrayDeque<String>();
    private byte[] current = new byte[0];
    private int position;

    private HlsAudioStream(String userAgent) {
        this.userAgent = userAgent;
    }

    /**
     * Start reading a broadcast. The addresses are alternatives for the same station, tried in
     * order; the first one that yields audio is used. Returns with the first segment already
     * loaded, so a caller can tell a dead station from a live one before answering its own client.
     */
    public static HlsAudioStream open(String[] playlistUrls, String userAgent) throws IOException {
        IOException last = new IOException("no stream address");
        for (String url : playlistUrls) {
            HlsAudioStream s = new HlsAudioStream(userAgent);
            try {
                s.start(url);
                return s;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    /** "audio/aac" or "audio/mpeg", from the first frame. */
    public String contentType() {
        if (current.length >= 2 && (current[0] & 0xff) == 0xff && (current[1] & 0xe0) == 0xe0
                && (current[1] & 0x06) != 0) {
            return "audio/mpeg";   // an MPEG audio layer is set: MP3, not AAC
        }
        return "audio/aac";
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (position >= current.length) {
            if (closed || !advance()) {
                return -1;
            }
        }
        int n = Math.min(len, current.length - position);
        System.arraycopy(current, position, b, off, n);
        position += n;
        return n;
    }

    @Override
    public void close() {
        closed = true;
    }

    // ── Playlist ─────────────────────────────────────────────────────────

    private void start(String url) throws IOException {
        // A master playlist only points at the real one; follow it down.
        String at = url;
        for (int depth = 0; depth < MAX_PLAYLIST_DEPTH; depth++) {
            String text = fetchText(at);
            if (!text.contains("#EXTM3U")) {
                throw new IOException("not an HLS playlist: " + at);
            }
            if (!text.contains("#EXT-X-STREAM-INF")) {
                mediaUrl = at;
                parse(text);
                if (!advance()) {
                    throw new IOException("playlist has no audio: " + at);
                }
                return;
            }
            String variant = null;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    variant = t;
                    break;
                }
            }
            if (variant == null) {
                throw new IOException("master playlist names no stream: " + at);
            }
            at = new URL(new URL(at), variant).toString();
        }
        throw new IOException("playlist nested too deep: " + url);
    }

    /** Take from the media playlist every segment not played yet. */
    private void parse(String text) throws IOException {
        long sequence = 0;
        java.util.List<String> uris = new java.util.ArrayList<String>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = number(t.substring(t.indexOf(':') + 1), 0);
            } else if (t.startsWith("#EXT-X-TARGETDURATION:")) {
                targetMs = Math.max(1000, number(t.substring(t.indexOf(':') + 1), 4) * 1000);
            } else if (t.startsWith("#EXT-X-ENDLIST")) {
                ended = true;
            } else if (!t.isEmpty() && !t.startsWith("#")) {
                uris.add(t);
            }
        }
        if (nextSequence < 0) {
            // First look: begin a few segments back from the newest (all of them, if it has ended).
            nextSequence = ended ? sequence : Math.max(sequence, sequence + uris.size() - START_SEGMENTS);
        } else if (nextSequence < sequence) {
            nextSequence = sequence;   // we fell behind and the station dropped what we missed
        }
        URL base = new URL(mediaUrl);
        for (int i = 0; i < uris.size(); i++) {
            if (sequence + i >= nextSequence) {
                pending.add(new URL(base, uris.get(i)).toString());
                nextSequence = sequence + i + 1;
            }
        }
    }

    /** Load the next segment into {@link #current}; false once the broadcast has ended. */
    private boolean advance() throws IOException {
        long waitingSince = System.currentTimeMillis();
        while (true) {
            String segment = pending.poll();
            if (segment != null) {
                byte[] audio = toFrames(fetchBytes(segment, SEGMENT_LIMIT));
                if (audio.length == 0) {
                    continue;
                }
                current = audio;
                position = 0;
                return true;
            }
            if (ended || closed) {
                return false;
            }
            if (System.currentTimeMillis() - waitingSince > STALL_LIMIT_MS) {
                throw new IOException("station stopped publishing");
            }
            try {
                Thread.sleep(Math.max(500, targetMs / 2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            try {
                parse(fetchText(mediaUrl));
            } catch (IOException e) {
                // One failed refresh is weather, not an outage: the stall limit decides that.
            }
        }
    }

    // ── Segment formats ──────────────────────────────────────────────────

    /** The audio frames inside one segment, whatever it is wrapped in. */
    static byte[] toFrames(byte[] data) throws IOException {
        int start = 0;
        // ID3v2 tag(s) in front: "ID3", two version bytes, flags, then a 28-bit "syncsafe" size.
        while (data.length - start >= 10 && data[start] == 'I' && data[start + 1] == 'D' && data[start + 2] == '3') {
            int size = ((data[start + 6] & 0x7f) << 21) | ((data[start + 7] & 0x7f) << 14)
                    | ((data[start + 8] & 0x7f) << 7) | (data[start + 9] & 0x7f);
            int footer = (data[start + 5] & 0x10) != 0 ? 10 : 0;
            start += 10 + size + footer;
            if (start > data.length) {
                throw new IOException("truncated ID3 tag");
            }
        }
        if (data.length - start >= 2 * TS_PACKET && data[start] == 0x47 && data[start + TS_PACKET] == 0x47) {
            return fromTransportStream(data, start);
        }
        byte[] out = new byte[data.length - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    /**
     * Cut the audio out of an MPEG transport stream. The audio is the first program stream whose
     * packets open with a PES header carrying an audio stream id (0xC0-0xDF); every packet of that
     * PID then contributes its payload, minus the PES header where one starts.
     */
    private static byte[] fromTransportStream(byte[] d, int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        int audioPid = -1;
        for (int p = start; p + TS_PACKET <= d.length; p += TS_PACKET) {
            if (d[p] != 0x47) {
                continue;   // lost alignment; packets are fixed-size, so the next slot may be fine
            }
            int pid = ((d[p + 1] & 0x1f) << 8) | (d[p + 2] & 0xff);
            boolean unitStart = (d[p + 1] & 0x40) != 0;
            int adaptation = (d[p + 3] >> 4) & 0x3;
            if ((adaptation & 0x1) == 0) {
                continue;   // no payload in this packet
            }
            int off = p + 4;
            if ((adaptation & 0x2) != 0) {
                off += 1 + (d[off] & 0xff);
            }
            int end = p + TS_PACKET;
            if (off >= end) {
                continue;
            }
            if (unitStart) {
                boolean pes = end - off >= 9 && d[off] == 0 && d[off + 1] == 0 && d[off + 2] == 1;
                int streamId = pes ? d[off + 3] & 0xff : -1;
                if (audioPid < 0 && streamId >= 0xc0 && streamId <= 0xdf) {
                    audioPid = pid;
                }
                if (pid != audioPid) {
                    continue;
                }
                if (pes) {
                    off += 9 + (d[off + 8] & 0xff);   // fixed PES header + its optional fields
                }
            } else if (pid != audioPid) {
                continue;
            }
            if (off < end) {
                out.write(d, off, end - off);
            }
        }
        return out.toByteArray();
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private String fetchText(String url) throws IOException {
        return new String(fetchBytes(url, PLAYLIST_LIMIT), StandardCharsets.UTF_8);
    }

    private byte[] fetchBytes(String url, int limit) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", userAgent);
        HttpURLConnection c = Https.get(url, headers);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) {
                        throw new IOException("response too large");
                    }
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static long number(String s, long fallback) {
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
