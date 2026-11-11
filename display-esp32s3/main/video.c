#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/idf_additions.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "esp_tls.h"
#include "esp_log.h"
#include "rom/tjpgd.h"
#include "video.h"

#define W 320
#define H 240
#define BUF_CAP (64 * 1024)

static const char *TAG = "video";
static SemaphoreHandle_t s_mx;
static volatile int s_gen;            // bumped by start/stop: the running task quits when it changes
static volatile bool s_running;
static vid_state_t s_state = VID_IDLE;
static char s_msg[128];
static char s_server[96], s_vid[16];
static int s_ss0;
static uint16_t *s_fb[3];             // decoded frames; s_front is the one the UI may read
static int s_front;
static uint32_t s_seq;

typedef struct { const uint8_t *p; size_t left; uint16_t *out; int x0, y0; } dec_t;   // x0/y0: where a smaller picture sits

static UINT in_cb(JDEC *jd, BYTE *buf, UINT n)
{
    dec_t *d = (dec_t *)jd->device;
    if (n > d->left) n = d->left;
    if (buf) memcpy(buf, d->p, n);
    d->p += n;
    d->left -= n;
    return n;
}

static UINT out_cb(JDEC *jd, void *bitmap, JRECT *r)
{
    dec_t *d = (dec_t *)jd->device;
    const uint8_t *s = (const uint8_t *)bitmap;      // RGB888
    for (int y = r->top; y <= r->bottom; y++) {
        int oy = y + d->y0;
        if (oy >= H) { s += (r->right - r->left + 1) * 3; continue; }
        uint16_t *o = d->out + oy * W + r->left + d->x0;
        for (int x = r->left; x <= r->right; x++, s += 3, o++) {
            if (x + d->x0 < W) *o = (uint16_t)(((s[0] & 0xF8) << 8) | ((s[1] & 0xFC) << 3) | (s[2] >> 3));
        }
    }
    return 1;
}

static bool decode(const uint8_t *jpg, size_t n, uint16_t *out, void *pool)
{
    JDEC jd;
    dec_t d = { jpg, n, out, 0, 0 };
    if (jd_prepare(&jd, in_cb, pool, 8192, &d) != JDR_OK) return false;
    if (jd.width > W || jd.height > H) return false;
    // A 16:9 video from the speaker is 320x176: centre it, black above and below (the buffer may
    // still hold a taller picture from before).
    d.x0 = (W - jd.width) / 2;
    d.y0 = (H - jd.height) / 2;
    if (d.y0 > 0 || d.x0 > 0) {
        memset(out, 0, (size_t)d.y0 * W * 2);
        memset(out + (d.y0 + jd.height) * W, 0, (size_t)(H - d.y0 - jd.height) * W * 2);
        if (d.x0 > 0) for (int y = d.y0; y < d.y0 + jd.height; y++) {
            memset(out + y * W, 0, (size_t)d.x0 * 2);
            memset(out + y * W + d.x0 + jd.width, 0, (size_t)(W - d.x0 - jd.width) * 2);
        }
    }
    return jd_decomp(&jd, out_cb, 0) == JDR_OK;
}

static void setst(vid_state_t s, const char *msg)
{
    xSemaphoreTake(s_mx, portMAX_DELAY);
    s_state = s;
    snprintf(s_msg, sizeof s_msg, "%s", msg ? msg : "");
    xSemaphoreGive(s_mx);
}

/* Find "Content-length: N" in the part header that ends at hdr_end. -1 when absent. */
static int part_len(const char *h, int hdr_end)
{
    for (int i = 0; i + 15 < hdr_end; i++) {
        if (!strncasecmp(h + i, "content-length:", 15)) return atoi(h + i + 15);
    }
    return -1;
}

static void fail(int gen, const char *m) { if (gen == s_gen) setst(VID_ERROR, m); }

/* Why does the secure connection fail? Repeat the handshake alone and read the TLS error:
   code = mbedtls error (hex), flags = certificate problems (1 expired, 2 revoked, 4 name, 8 not trusted). */
static void tls_diag(const char *url, int *code, int *flags)
{
    *code = *flags = 0;
    const char *h = strstr(url, "://");
    if (!h) return;
    h += 3;
    char host[64];
    int n = 0, port = 443;
    while (h[n] && h[n] != '/' && h[n] != ':' && n < (int)sizeof host - 1) { host[n] = h[n]; n++; }
    host[n] = 0;
    if (h[n] == ':') port = atoi(h + n + 1);
    esp_tls_cfg_t tc = { .crt_bundle_attach = esp_crt_bundle_attach, .timeout_ms = 8000 };
    esp_tls_t *t = esp_tls_init();
    if (!t) return;
    esp_tls_conn_new_sync(host, n, port, &tc, t);
    esp_tls_error_handle_t eh = NULL;
    if (esp_tls_get_error_handle(t, &eh) == ESP_OK && eh) esp_tls_get_and_clear_last_error(eh, code, flags);
    esp_tls_conn_destroy(t);
}

enum { SES_STOP = 0, SES_RETRY = 1, SES_FATAL = 2 };

/* One connection. SES_RETRY: it broke in the middle of the stream (reconnect from where it was);
   SES_FATAL: it never worked (message already set); SES_STOP: it ended or the user closed it. */
static int session(int gen, const char *url, uint8_t *buf, void *pool, bool *got_frame)
{
    esp_http_client_config_t cfg = { .url = url, .timeout_ms = 15000, .buffer_size = 1024, .buffer_size_tx = 512 };
    if (!strncmp(url, "https://", 8)) cfg.crt_bundle_attach = esp_crt_bundle_attach;
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    if (!c) { fail(gen, "Không mở được kết nối"); return SES_FATAL; }
    esp_err_t oe = esp_http_client_open(c, 0);
    if (oe != ESP_OK) {
        const char *hostpart = strstr(url, "://");
        char m[128];
        int tc = 0, tf = 0;
        if (!strncmp(url, "https://", 8)) tls_diag(url, &tc, &tf);
        snprintf(m, sizeof m, "Không nối được %.26s: %s (mã %d, TLS -0x%x, chứng chỉ 0x%x)",
                 hostpart ? hostpart + 3 : url, esp_err_to_name(oe), esp_http_client_get_errno(c), tc < 0 ? -tc : tc, tf);
        ESP_LOGE(TAG, "%s (url %s)", m, url);
        esp_http_client_cleanup(c);
        if (*got_frame) return SES_RETRY;
        fail(gen, m);
        return SES_FATAL;
    }
    esp_http_client_fetch_headers(c);
    int code = esp_http_client_get_status_code(c);
    if (code != 200) {
        char m[48];
        snprintf(m, sizeof m, code == 503 ? "Máy chủ đang bận" : "Máy chủ báo lỗi %d", code);
        fail(gen, m);
        esp_http_client_close(c); esp_http_client_cleanup(c);
        return SES_FATAL;
    }
    int result = SES_STOP;
    size_t len = 0;
    int idle = 0;
    while (gen == s_gen) {
        int r = esp_http_client_read(c, (char *)buf + len, BUF_CAP - len);
        if (r == -ESP_ERR_HTTP_EAGAIN) {
            // nothing yet: the server is still preparing the video (or a proxy holds the first bytes) — wait, up to a minute
            if (++idle > 4) { fail(gen, "Máy chủ quá lâu không gửi hình (proxy có thể đang giữ đệm)"); result = SES_FATAL; break; }
            continue;
        }
        if (r < 0) {
            ESP_LOGW(TAG, "read %d errno %d after %u bytes", r, esp_http_client_get_errno(c), (unsigned)len);
            if (*got_frame) { result = SES_RETRY; }
            else {
                char em[64];
                snprintf(em, sizeof em, "Mất kết nối (đọc %d, mã %d)", r, esp_http_client_get_errno(c));
                fail(gen, em);
                result = SES_FATAL;
            }
            break;
        }
        idle = 0;
        if (r == 0) {
            if (esp_http_client_is_complete_data_received(c)) { fail(gen, "Hết video"); break; }
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        len += r;
        for (;;) {                                           // take every whole part in the buffer
            int hdr = -1;
            for (size_t i = 0; i + 3 < len; i++) if (!memcmp(buf + i, "\r\n\r\n", 4)) { hdr = (int)i + 4; break; }
            if (hdr < 0) break;
            int n = part_len((const char *)buf, hdr);
            if (n <= 0 || n > BUF_CAP - hdr) { len = 0; break; }      // not what we expect: resync
            if ((size_t)(hdr + n) > len) break;                       // wait for the rest
            uint16_t *back = s_fb[(s_front + 1) % 3];
            if (decode(buf + hdr, n, back, pool)) {
                xSemaphoreTake(s_mx, portMAX_DELAY);
                s_front = (s_front + 1) % 3;
                s_seq++;
                s_state = VID_PLAYING;
                xSemaphoreGive(s_mx);
                *got_frame = true;
            }
            size_t used = hdr + n;
            memmove(buf, buf + used, len - used);
            len -= used;
        }
        if (len >= BUF_CAP) len = 0;
    }
    esp_http_client_close(c);
    esp_http_client_cleanup(c);
    return result;
}

static void run(int gen)
{
    // kept between sessions: allocated once, so the heap does not fragment
    static uint8_t *buf;
    static void *pool;
    if (!buf) buf = heap_caps_malloc(BUF_CAP, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!pool) pool = heap_caps_malloc(8192, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    if (!pool) pool = heap_caps_malloc(8192, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);   // slower, but it works
    if (!buf || !pool) {
        char m[80];
        snprintf(m, sizeof m, "Hết bộ nhớ (%s; trống %d KB, PSRAM %d KB)", !buf ? "đệm" : "bảng giải mã",
                 (int)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
                 (int)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024));
        fail(gen, m);
        return;
    }
    // A stream that breaks after it has been playing is picked up again where it was (the last picture stays on
    // the screen meanwhile); one that never started is reported.
    bool got_frame = false;
    int64_t t0 = esp_timer_get_time();
    for (int attempt = 0; attempt < 8 && gen == s_gen; attempt++) {
        char url[260];
        int ss = s_ss0 + (int)((esp_timer_get_time() - t0) / 1000000);
        snprintf(url, sizeof url, "%s/video_mjpeg?id=%s&ss=%d", s_server, s_vid, ss);
        int r = session(gen, url, buf, pool, &got_frame);
        if (r != SES_RETRY) return;
        ESP_LOGW(TAG, "stream broke, reconnecting (%d)", attempt + 1);
        vTaskDelay(pdMS_TO_TICKS(300));
    }
    fail(gen, "Mất kết nối nhiều lần, thử lại sau");
}

static TaskHandle_t s_task;

/* One long-lived task: a picture session is a notification to it (so no stack is made and freed each time). */
static void task(void *arg)
{
    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        run(s_gen);
        s_running = false;
    }
}

void video_start(const char *server, const char *video_id, int start_s)
{
    if (!s_mx) s_mx = xSemaphoreCreateMutex();
    if (!s_fb[0]) {
        for (int i = 0; i < 3; i++) s_fb[i] = heap_caps_calloc(W * H, 2, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (!s_fb[0] || !s_fb[1] || !s_fb[2]) {
            char m[80];
            snprintf(m, sizeof m, "Hết bộ nhớ (khung hình; PSRAM trống %d KB)",
                     (int)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024));
            for (int i = 0; i < 3; i++) { free(s_fb[i]); s_fb[i] = NULL; }
            setst(VID_ERROR, m);
            return;
        }
    }
    s_gen++;
    for (int i = 0; i < 50 && s_running; i++) vTaskDelay(pdMS_TO_TICKS(20));   // the old task leaves first
    if (s_running) { setst(VID_ERROR, "Đang bận, thử lại"); return; }
    snprintf(s_server, sizeof s_server, "%s", server);
    snprintf(s_vid, sizeof s_vid, "%s", video_id);
    s_ss0 = start_s < 0 ? 0 : start_s;
    xSemaphoreTake(s_mx, portMAX_DELAY);
    s_seq = 0;
    xSemaphoreGive(s_mx);
    setst(VID_LOADING, "");
    s_running = true;
    if (!s_task) {
        // the stack goes to PSRAM: TLS needs ~10 KB and the internal RAM is shared with Wi-Fi and the display
        BaseType_t ok = xTaskCreateWithCaps(task, "video", 12288, NULL, 3, &s_task, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (ok != pdPASS) ok = xTaskCreate(task, "video", 8192, NULL, 3, &s_task);
        if (ok != pdPASS) {
            s_task = NULL;
            s_running = false;
            char m[80];
            snprintf(m, sizeof m, "Không tạo được luồng (RAM trống %d KB)",
                     (int)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024));
            setst(VID_ERROR, m);
            return;
        }
    }
    xTaskNotifyGive(s_task);
}

void video_stop(void)
{
    s_gen++;
    if (s_mx) setst(VID_IDLE, "");
}

vid_state_t video_state(char *msg, int n)
{
    if (!s_mx) { if (msg && n) msg[0] = 0; return VID_IDLE; }
    xSemaphoreTake(s_mx, portMAX_DELAY);
    vid_state_t s = s_state;
    if (msg && n) snprintf(msg, n, "%s", s_msg);
    xSemaphoreGive(s_mx);
    return s;
}

const uint16_t *video_frame(uint32_t *seq)
{
    if (!s_mx || !s_seq || *seq == s_seq) return NULL;
    xSemaphoreTake(s_mx, portMAX_DELAY);
    *seq = s_seq;
    const uint16_t *f = s_fb[s_front];
    xSemaphoreGive(s_mx);
    return f;
}
