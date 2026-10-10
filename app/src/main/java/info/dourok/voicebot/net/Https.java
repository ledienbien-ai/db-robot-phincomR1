package info.dourok.voicebot.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP(S) GET for a device whose own list of trusted authorities is ten years old.
 *
 * The R1 runs Android 5.1. Its certificate store has never been updated, and a good part of
 * today's web is signed by authorities that did not exist when it was built: on the speaker,
 * github.com fails with "Trust anchor for certification path not found", which is what stopped
 * the over-the-air update from ever seeing a new release.
 *
 * So the app carries its own copy of the current Mozilla root list (assets/cacert.pem, taken from
 * the certifi package) and accepts a server if EITHER the system store or that list vouches for
 * it. Nothing else is relaxed: the chain is still validated, the host name is still checked.
 *
 * Redirects are followed here rather than by the platform, so that every hop is made with the
 * same trust settings and an https address can never be redirected down to plain http.
 *
 * Plain Java on the JDK, so it can be run off the device.
 */
public final class Https {
    private static final int MAX_REDIRECTS = 6;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final Object LOCK = new Object();
    private static Callable<InputStream> bundleSource;
    private static SSLSocketFactory factory;
    private static boolean loaded;

    private Https() {
    }

    /**
     * Say where the extra root certificates (a PEM file) come from. They are read on first use,
     * not here: parsing a hundred-odd certificates takes a slow box a moment, and start-up is not
     * the time for it.
     */
    public static void setBundle(Callable<InputStream> source) {
        synchronized (LOCK) {
            bundleSource = source;
            factory = null;
            loaded = false;
        }
    }

    /**
     * Open {@code url} for reading, following redirects. The caller checks the response code and
     * must {@code disconnect()} the result.
     *
     * @param headers request headers, or null
     */
    public static HttpURLConnection get(String url, Map<String, String> headers) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = socketFactory();
                if (f != null) {
                    ((HttpsURLConnection) c).setSSLSocketFactory(f);
                }
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                return c;
            }
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (location == null || location.isEmpty()) {
                throw new IOException("chuyển hướng không có địa chỉ đích");
            }
            String next = new URL(new URL(current), location).toString();
            if (current.regionMatches(true, 0, "https:", 0, 6) && !next.regionMatches(true, 0, "https:", 0, 6)) {
                throw new IOException("bị chuyển hướng từ https sang kết nối không bảo mật");
            }
            current = next;
        }
        throw new IOException("chuyển hướng quá nhiều lần");
    }

    /** The factory that trusts system roots plus the bundle; null if there is no usable bundle. */
    static SSLSocketFactory socketFactory() {
        synchronized (LOCK) {
            if (!loaded) {
                loaded = true;
                try {
                    factory = build();
                } catch (Exception e) {
                    factory = null;   // fall back to the platform's own trust, as before
                }
            }
            return factory;
        }
    }

    private static SSLSocketFactory build() throws Exception {
        if (bundleSource == null) {
            return null;
        }
        KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
        extra.load(null, null);
        int count = 0;
        InputStream in = bundleSource.call();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            StringBuilder block = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("-----BEGIN CERTIFICATE-----")) {
                    block = new StringBuilder();
                }
                if (block != null) {
                    block.append(line).append('\n');
                    if (line.startsWith("-----END CERTIFICATE-----")) {
                        try {
                            // One at a time: a certificate this platform cannot parse (an
                            // algorithm newer than it) must cost that one root, not the list.
                            extra.setCertificateEntry("ca" + count, cf.generateCertificate(
                                    new ByteArrayInputStream(block.toString().getBytes(StandardCharsets.US_ASCII))));
                            count++;
                        } catch (Exception skipped) {
                            // unreadable here; carry on with the rest
                        }
                        block = null;
                    }
                }
            }
        } finally {
            in.close();
        }
        if (count == 0) {
            return null;
        }
        X509TrustManager system = trustManager(null);
        X509TrustManager bundled = trustManager(extra);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {new Either(system, bundled)}, null);
        return ctx.getSocketFactory();
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);   // null = the platform's own store
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** Trusts a server chain if the first manager does, or failing that the second. */
    private static final class Either implements X509TrustManager {
        private final X509TrustManager first;
        private final X509TrustManager second;

        Either(X509TrustManager first, X509TrustManager second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            first.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return first.getAcceptedIssuers();
        }
    }
}
