/* The settings page: on the setup Wi-Fi at 192.168.4.1, and on the home network at the display's IP. */
#include <string.h>
#include <stdlib.h>
#include <ctype.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_http_server.h"
#include "esp_system.h"
#include "esp_log.h"
#include "cJSON.h"
#include "portal.h"
#include "portal_page.h"
#include "net.h"
#include "link.h"
#include "cfg.h"
#include "board.h"

static void restart_task(void *a) { vTaskDelay(pdMS_TO_TICKS(1200)); esp_restart(); }
static void restart_soon(void) { xTaskCreate(restart_task, "rst", 2048, NULL, 5, NULL); }

static esp_err_t page(httpd_req_t *r)
{
    httpd_resp_set_type(r, "text/html; charset=utf-8");
    httpd_resp_set_hdr(r, "Cache-Control", "no-store");
    return httpd_resp_send(r, PORTAL_HTML, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t get_cfg(httpd_req_t *r)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "ssid", g_cfg.ssid);
    cJSON_AddStringToObject(o, "name", g_cfg.name);
    cJSON_AddStringToObject(o, "spk_id", g_cfg.spk_id);
    cJSON_AddStringToObject(o, "spk_ip", g_cfg.spk_ip);
    cJSON_AddNumberToObject(o, "lcd", g_cfg.lcd_type);
    cJSON_AddNumberToObject(o, "br", g_cfg.bright);
    cJSON_AddBoolToObject(o, "inv", g_cfg.invert);
    cJSON_AddBoolToObject(o, "bgr", g_cfg.bgr);
    cJSON_AddBoolToObject(o, "r180", g_cfg.orient == 2);
    cJSON_AddBoolToObject(o, "mir", g_cfg.mirror);
    cJSON_AddBoolToObject(o, "night", g_cfg.night_dim);
    char ip[16], st[96];
    net_ip(ip, sizeof ip);
    if (ip[0]) snprintf(st, sizeof st, "Đã vào Wi-Fi %s · IP %s", g_cfg.ssid, ip);
    else if (g_cfg.ssid[0]) snprintf(st, sizeof st, "Chưa vào được Wi-Fi %s", g_cfg.ssid);
    else snprintf(st, sizeof st, "Chưa cài Wi-Fi");
    cJSON_AddStringToObject(o, "status", st);
    cJSON *a = cJSON_AddArrayToObject(o, "found");
    speaker_t f[LINK_MAX_FOUND];
    int n = link_found(f, LINK_MAX_FOUND);
    for (int i = 0; i < n; i++) {
        cJSON *e = cJSON_CreateObject();
        cJSON_AddStringToObject(e, "id", f[i].id);
        cJSON_AddStringToObject(e, "name", f[i].name);
        cJSON_AddStringToObject(e, "ip", f[i].ip);
        cJSON_AddItemToArray(a, e);
    }
    char *t = cJSON_PrintUnformatted(o);
    httpd_resp_set_type(r, "application/json; charset=utf-8");
    httpd_resp_send(r, t, HTTPD_RESP_USE_STRLEN);
    cJSON_free(t);
    cJSON_Delete(o);
    return ESP_OK;
}

static esp_err_t get_scan(httpd_req_t *r)
{
    char *b = malloc(2600);
    if (!b) return httpd_resp_send_500(r);
    net_scan_json(b, 2600);
    httpd_resp_set_type(r, "application/json; charset=utf-8");
    httpd_resp_send(r, b, HTTPD_RESP_USE_STRLEN);
    free(b);
    return ESP_OK;
}

static void url_decode(char *s)
{
    char *o = s;
    for (; *s; s++) {
        if (*s == '+') *o++ = ' ';
        else if (*s == '%' && isxdigit((unsigned char)s[1]) && isxdigit((unsigned char)s[2])) {
            char h[3] = { s[1], s[2], 0 };
            *o++ = (char)strtol(h, NULL, 16);
            s += 2;
        } else *o++ = *s;
    }
    *o = 0;
}

/* Field `key` of an urlencoded body, decoded; false when missing. */
static bool field(const char *body, const char *key, char *out, size_t n)
{
    size_t kl = strlen(key);
    const char *p = body;
    while (p && *p) {
        if (!strncmp(p, key, kl) && p[kl] == '=') {
            p += kl + 1;
            const char *e = strchr(p, '&');
            size_t l = e ? (size_t)(e - p) : strlen(p);
            char tmp[200];
            if (l >= sizeof tmp) l = sizeof tmp - 1;
            memcpy(tmp, p, l);
            tmp[l] = 0;
            url_decode(tmp);
            strlcpy(out, tmp, n - 1);
            out[n - 1] = 0;
            return true;
        }
        p = strchr(p, '&');
        if (p) p++;
    }
    return false;
}

/* A page on another site can post a form here but cannot add a header without asking first. */
static bool from_page(httpd_req_t *r)
{
    char v[4];
    return httpd_req_get_hdr_value_str(r, "X-DBR", v, sizeof v) == ESP_OK;
}

static esp_err_t post_save(httpd_req_t *r)
{
    if (!from_page(r)) return httpd_resp_send_err(r, HTTPD_403_FORBIDDEN, "forbidden");
    if (r->content_len <= 0 || r->content_len > 1500) return httpd_resp_send_err(r, HTTPD_400_BAD_REQUEST, "bad");
    char body[1501];
    int got = 0;
    while (got < r->content_len) {
        int n = httpd_req_recv(r, body + got, r->content_len - got);
        if (n <= 0) return ESP_FAIL;
        got += n;
    }
    body[got] = 0;
    char v[100];
    if (field(body, "ssid", v, sizeof v) && strcmp(v, g_cfg.ssid)) {
        strlcpy(g_cfg.ssid, v, sizeof g_cfg.ssid - 1);
        g_cfg.pass[0] = 0;                       // a new network wants its own password
    }
    if (field(body, "pass", v, sizeof v) && v[0]) strlcpy(g_cfg.pass, v, sizeof g_cfg.pass - 1);
    if (field(body, "name", v, sizeof v) && v[0]) strlcpy(g_cfg.name, v, sizeof g_cfg.name - 1);
    if (field(body, "spk", v, sizeof v)) strlcpy(g_cfg.spk_id, v, sizeof g_cfg.spk_id - 1);
    if (field(body, "spkip", v, sizeof v)) strlcpy(g_cfg.spk_ip, v, sizeof g_cfg.spk_ip - 1);
#if !BOARD_LCD_FIXED_ILI9341
    if (field(body, "lcd", v, sizeof v)) g_cfg.lcd_type = atoi(v) == 1 ? LCD_ILI9341 : LCD_ST7789;
#endif
    if (field(body, "br", v, sizeof v)) { int b = atoi(v); g_cfg.bright = b < 5 ? 5 : b > 100 ? 100 : b; }
    g_cfg.invert = field(body, "inv", v, sizeof v);
    g_cfg.bgr = field(body, "bgr", v, sizeof v);
    {   // the page only knows "upside down": leave a portrait orientation alone unless the box was changed
        bool up = field(body, "r180", v, sizeof v);
        if (up != (g_cfg.orient == 2)) g_cfg.orient = up ? 2 : 0;
    }
    g_cfg.mirror = field(body, "mir", v, sizeof v);
    g_cfg.night_dim = field(body, "night", v, sizeof v);
    cfg_save();
    httpd_resp_set_type(r, "text/plain; charset=utf-8");
    httpd_resp_sendstr(r, "Đã lưu. Màn hình khởi động lại…");
    restart_soon();
    return ESP_OK;
}

static esp_err_t post_cal(httpd_req_t *r)
{
    if (!from_page(r)) return httpd_resp_send_err(r, HTTPD_403_FORBIDDEN, "forbidden");
    g_cfg.want_cal = 1;
    cfg_save();
    httpd_resp_set_type(r, "text/plain; charset=utf-8");
    httpd_resp_sendstr(r, "Màn hình khởi động lại và hiện các dấu + để chạm.");
    restart_soon();
    return ESP_OK;
}

/* Anything else on the setup network goes to the page (phones probe all sorts of URLs). */
static esp_err_t not_found(httpd_req_t *r, httpd_err_code_t e)
{
    if (!net_setup_ap_on()) return httpd_resp_send_err(r, HTTPD_404_NOT_FOUND, "not found");
    httpd_resp_set_status(r, "302 Found");
    httpd_resp_set_hdr(r, "Location", "http://192.168.4.1/");
    return httpd_resp_send(r, NULL, 0);
}

void portal_start(void)
{
    httpd_config_t c = HTTPD_DEFAULT_CONFIG();
    c.stack_size = 8192;
    c.max_uri_handlers = 8;
    c.lru_purge_enable = true;
    httpd_handle_t h;
    if (httpd_start(&h, &c) != ESP_OK) return;
    const httpd_uri_t u[] = {
        { .uri = "/", .method = HTTP_GET, .handler = page },
        { .uri = "/cfg", .method = HTTP_GET, .handler = get_cfg },
        { .uri = "/scan", .method = HTTP_GET, .handler = get_scan },
        { .uri = "/save", .method = HTTP_POST, .handler = post_save },
        { .uri = "/cal", .method = HTTP_POST, .handler = post_cal },
    };
    for (int i = 0; i < sizeof u / sizeof u[0]; i++) httpd_register_uri_handler(h, &u[i]);
    httpd_register_err_handler(h, HTTPD_404_NOT_FOUND, not_found);
}
