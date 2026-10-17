#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "rom/tjpgd.h"
#include "video.h"

#define W 320
#define H 240
#define BUF_CAP (64 * 1024)

static SemaphoreHandle_t s_mx;
static volatile int s_gen;            // bumped by start/stop: the running task quits when it changes
static volatile bool s_running;
static vid_state_t s_state = VID_IDLE;
static char s_msg[80];
static char s_url[200];
static uint16_t *s_fb[3];             // decoded frames; s_front is the one the UI may read
static int s_front;
static uint32_t s_seq;

typedef struct { const uint8_t *p; size_t left; uint16_t *out; } dec_t;

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
        if (y >= H) { s += (r->right - r->left + 1) * 3; continue; }
        uint16_t *o = d->out + y * W + r->left;
        for (int x = r->left; x <= r->right; x++, s += 3, o++) {
            if (x < W) *o = (uint16_t)(((s[0] & 0xF8) << 8) | ((s[1] & 0xFC) << 3) | (s[2] >> 3));
        }
    }
    return 1;
}

static bool decode(const uint8_t *jpg, size_t n, uint16_t *out, void *pool)
{
    JDEC jd;
    dec_t d = { jpg, n, out };
    if (jd_prepare(&jd, in_cb, pool, 8192, &d) != JDR_OK) return false;
    if (jd.width > W || jd.height > H) return false;
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

static void run(int gen)
{
    uint8_t *buf = heap_caps_malloc(BUF_CAP, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    void *pool = heap_caps_malloc(8192, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    if (!buf || !pool) { fail(gen, "Hết bộ nhớ"); goto done; }

    esp_http_client_config_t cfg = { .url = s_url, .timeout_ms = 10000, .buffer_size = 1024, .buffer_size_tx = 512 };
    if (!strncmp(s_url, "https://", 8)) cfg.crt_bundle_attach = esp_crt_bundle_attach;
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    if (!c) { fail(gen, "Không mở được kết nối"); goto done; }
    if (esp_http_client_open(c, 0) != ESP_OK) { fail(gen, "Không nối được máy chủ nhạc"); esp_http_client_cleanup(c); goto done; }
    esp_http_client_fetch_headers(c);
    int code = esp_http_client_get_status_code(c);
    if (code != 200) {
        char m[48];
        snprintf(m, sizeof m, code == 503 ? "Máy chủ đang bận" : "Máy chủ báo lỗi %d", code);
        fail(gen, m);
        esp_http_client_close(c); esp_http_client_cleanup(c);
        goto done;
    }
    size_t len = 0;
    while (gen == s_gen) {
        int r = esp_http_client_read(c, (char *)buf + len, BUF_CAP - len);
        if (r < 0) { fail(gen, "Mất kết nối"); break; }
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
            }
            size_t used = hdr + n;
            memmove(buf, buf + used, len - used);
            len -= used;
        }
        if (len >= BUF_CAP) len = 0;
    }
    esp_http_client_close(c);
    esp_http_client_cleanup(c);
done:
    free(buf);
    free(pool);
}

static void task(void *arg)
{
    int gen = (int)(intptr_t)arg;
    run(gen);
    if (gen == s_gen) { /* ended by itself: leave the error text */ }
    s_running = false;
    vTaskDelete(NULL);
}

void video_start(const char *server, const char *video_id, int start_s)
{
    if (!s_mx) s_mx = xSemaphoreCreateMutex();
    if (!s_fb[0]) {
        for (int i = 0; i < 3; i++) s_fb[i] = heap_caps_calloc(W * H, 2, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (!s_fb[0] || !s_fb[1] || !s_fb[2]) { setst(VID_ERROR, "Hết bộ nhớ"); return; }
    }
    s_gen++;
    for (int i = 0; i < 50 && s_running; i++) vTaskDelay(pdMS_TO_TICKS(20));   // the old task leaves first
    if (s_running) { setst(VID_ERROR, "Đang bận, thử lại"); return; }
    snprintf(s_url, sizeof s_url, "%s/video_mjpeg?id=%s&ss=%d", server, video_id, start_s < 0 ? 0 : start_s);
    xSemaphoreTake(s_mx, portMAX_DELAY);
    s_seq = 0;
    xSemaphoreGive(s_mx);
    setst(VID_LOADING, "");
    s_running = true;
    if (xTaskCreate(task, "video", 10240, (void *)(intptr_t)s_gen, 3, NULL) != pdPASS) {
        s_running = false;
        setst(VID_ERROR, "Không tạo được luồng");
    }
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
