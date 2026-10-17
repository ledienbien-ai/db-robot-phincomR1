/*
 * The speaker side of this is SpeakerLink in the DB-Robot app (UDP port 8089):
 * broadcast {"t":"find"} → {"t":"here",…}; then {"t":"sub","name"} every few seconds and the
 * speaker pushes {"t":"st",…} on every change plus the spectrum ('S','P', 32 bytes).
 */
#include <string.h>
#include <stdio.h>
#include <sys/time.h>
#include <time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "lwip/sockets.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "cJSON.h"
#include "link.h"
#include "net.h"
#include "cfg.h"
#include "board.h"

static const char *TAG = "link";
static SemaphoreHandle_t s_mx;
static spk_state_t s_st;
static uint8_t s_spec[LINK_BANDS];
static int64_t s_spec_us;
static speaker_t s_found[LINK_MAX_FOUND];
static int s_nfound;
static int s_sock = -1;
static struct sockaddr_in s_peer;    // the speaker we follow
static volatile bool s_research;
static char s_cur_id[48];
static link_list_t s_list;
static link_alarms_t s_alarms;
static link_res_t s_res;

#define now_us() esp_timer_get_time()

static void copy(char *dst, size_t n, const cJSON *o, const char *key)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    if (cJSON_IsString(v) && v->valuestring) {
        strncpy(dst, v->valuestring, n - 1);
        dst[n - 1] = 0;
        // never cut a UTF-8 character in half
        size_t l = strlen(dst);
        if (l == n - 1) {
            while (l > 0 && ((unsigned char)dst[l - 1] & 0xC0) == 0x80) l--;
            if (l > 0 && ((unsigned char)dst[l - 1] & 0xC0) == 0xC0) l--;
            dst[l] = 0;
        }
    } else {
        dst[0] = 0;
    }
}

static int num(const cJSON *o, const char *key, int def)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsNumber(v) ? (int)v->valuedouble : def;
}

static bool flag(const cJSON *o, const char *key)
{
    return cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(o, key));
}

static void set_clock(const cJSON *j)
{
    const cJSON *tm = cJSON_GetObjectItemCaseSensitive(j, "tm");
    if (!cJSON_IsNumber(tm)) return;
    static int last_tzo = 99999;
    int tzo = num(j, "tzo", 420);
    if (tzo != last_tzo) {
        // POSIX TZ has the sign the other way round: UTC+7 is "<+07>-7"
        char tz[32];
        int a = tzo < 0 ? -tzo : tzo;
        snprintf(tz, sizeof tz, "<%c%02d%02d>%c%d:%02d", tzo < 0 ? '-' : '+', a / 60, a % 60,
                 tzo < 0 ? '+' : '-', a / 60, a % 60);
        setenv("TZ", tz, 1);
        tzset();
        last_tzo = tzo;
    }
    struct timeval now;
    gettimeofday(&now, NULL);
    double t = tm->valuedouble;
    if (now.tv_sec < 1600000000 || (now.tv_sec - (time_t)t) > 2 || ((time_t)t - now.tv_sec) > 2) {
        struct timeval tv = { .tv_sec = (time_t)t, .tv_usec = 500000 };
        settimeofday(&tv, NULL);
    }
}

static void parse_state(cJSON *j)
{
    spk_state_t *s = &s_st;
    copy(s->name, sizeof s->name, j, "name");
    copy(s->ip, sizeof s->ip, j, "ip");
    copy(s->ver, sizeof s->ver, j, "ver");
    copy(s->vs, sizeof s->vs, j, "vs");
    copy(s->emo, sizeof s->emo, j, "emo");
    s->awake = flag(j, "awake");
    s->mute = flag(j, "mute");
    s->vol = num(j, "vol", -1);
    copy(s->user, sizeof s->user, j, "user");
    copy(s->bot, sizeof s->bot, j, "bot");
    const cJSON *m = cJSON_GetObjectItemCaseSensitive(j, "m");
    s->has_m = cJSON_IsObject(m);
    if (s->has_m) {
        copy(s->ms, sizeof s->ms, m, "s");
        copy(s->ti, sizeof s->ti, m, "ti");
        copy(s->ar, sizeof s->ar, m, "ar");
        copy(s->src, sizeof s->src, m, "src");
        s->pos = num(m, "pos", 0);
        s->dur = num(m, "dur", 0);
        copy(s->vid, sizeof s->vid, m, "vid");
        copy(s->mu, sizeof s->mu, j, "mu");
    }
    const cJSON *w = cJSON_GetObjectItemCaseSensitive(j, "wx");
    s->has_wx = cJSON_IsObject(w);
    if (s->has_wx) {
        s->wx_t = num(w, "t", 0);
        s->wx_c = num(w, "c", -1);
        s->wx_day = cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(w, "day")) ||
                    !cJSON_GetObjectItemCaseSensitive(w, "day");
        copy(s->wx_d, sizeof s->wx_d, w, "d");
        copy(s->wx_city, sizeof s->wx_city, w, "city");
    }
    const cJSON *al = cJSON_GetObjectItemCaseSensitive(j, "al");
    s->al_next[0] = s->al_ring[0] = 0;
    if (cJSON_IsObject(al)) {
        copy(s->al_next, sizeof s->al_next, al, "next");
        copy(s->al_ring, sizeof s->al_ring, al, "ring");
    }
    s->group = num(j, "group", 0);
    s->got_us = now_us();
    s->phase = LINK_CONNECTED;
    s->seq++;
}

static void parse_list(const cJSON *j)
{
    link_list_t *l = &s_list;
    copy(l->kind, sizeof l->kind, j, "k");
    copy(l->id, sizeof l->id, j, "id");
    l->count = 0;
    const cJSON *a = cJSON_GetObjectItemCaseSensitive(j, "items"), *o;
    cJSON_ArrayForEach(o, a) {
        if (l->count >= LINK_LIST_MAX) break;
        copy(l->it[l->count].id, sizeof l->it[0].id, o, "id");
        copy(l->it[l->count].n, sizeof l->it[0].n, o, "n");
        l->count++;
    }
    l->seq++;
}

static void parse_alarms(const cJSON *j)
{
    link_alarms_t *l = &s_alarms;
    l->count = 0;
    const cJSON *a = cJSON_GetObjectItemCaseSensitive(j, "alarms"), *o;
    cJSON_ArrayForEach(o, a) {
        if (l->count >= LINK_ALARM_MAX) break;
        li_alarm_t *x = &l->a[l->count++];
        copy(x->id, sizeof x->id, o, "id");
        copy(x->time, sizeof x->time, o, "time");
        copy(x->label, sizeof x->label, o, "label");
        x->on = flag(o, "on");
        x->days = 0;
        const cJSON *d = cJSON_GetObjectItemCaseSensitive(o, "days"), *v;
        cJSON_ArrayForEach(v, d) if (cJSON_IsNumber(v) && v->valueint >= 0 && v->valueint < 7) x->days |= 1 << v->valueint;
    }
    copy(l->next, sizeof l->next, j, "next");
    l->seq++;
}

static void set_phase(link_phase_t p)
{
    xSemaphoreTake(s_mx, portMAX_DELAY);
    if (s_st.phase != p) { s_st.phase = p; s_st.seq++; }
    xSemaphoreGive(s_mx);
}

static void send_to(const struct sockaddr_in *to, const char *text)
{
    if (s_sock >= 0) sendto(s_sock, text, strlen(text), 0, (const struct sockaddr *)to, sizeof *to);
}

static void send_find(void)
{
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(SPEAKER_PORT) };
    const char *q = "{\"t\":\"find\"}";
    a.sin_addr.s_addr = htonl(INADDR_BROADCAST);
    send_to(&a, q);
    uint32_t bc = net_broadcast();
    if (bc) { a.sin_addr.s_addr = bc; send_to(&a, q); }
    if (g_cfg.spk_ip[0] && inet_aton(g_cfg.spk_ip, &a.sin_addr)) send_to(&a, q);
}

static void send_sub(void)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "t", "sub");
    cJSON_AddStringToObject(o, "name", g_cfg.name);
    char *t = cJSON_PrintUnformatted(o);
    send_to(&s_peer, t);
    cJSON_free(t);
    cJSON_Delete(o);
}

static void on_here(cJSON *j, const struct sockaddr_in *from)
{
    speaker_t sp = { 0 };
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(j, "id");
    if (!cJSON_IsString(id) || !id->valuestring[0]) return;
    copy(sp.id, sizeof sp.id, j, "id");
    copy(sp.name, sizeof sp.name, j, "name");
    copy(sp.ver, sizeof sp.ver, j, "ver");
    inet_ntoa_r(from->sin_addr, sp.ip, sizeof sp.ip);    // where it answered from is what works
    const cJSON *pl = cJSON_GetObjectItemCaseSensitive(j, "playing");
    sp.playing = cJSON_IsString(pl) && pl->valuestring[0];
    xSemaphoreTake(s_mx, portMAX_DELAY);
    int i;
    for (i = 0; i < s_nfound; i++) if (!strcmp(s_found[i].id, sp.id)) break;
    if (i < LINK_MAX_FOUND) {
        s_found[i] = sp;
        if (i == s_nfound) s_nfound++;
    }
    xSemaphoreGive(s_mx);
}

static bool pick(void)
{
    // the saved speaker, else the fixed address, else the only one there is
    int sel = -1;
    xSemaphoreTake(s_mx, portMAX_DELAY);
    for (int i = 0; i < s_nfound && sel < 0; i++) if (g_cfg.spk_id[0] && !strcmp(s_found[i].id, g_cfg.spk_id)) sel = i;
    for (int i = 0; i < s_nfound && sel < 0; i++) if (g_cfg.spk_ip[0] && !strcmp(s_found[i].ip, g_cfg.spk_ip)) sel = i;
    if (sel < 0 && !g_cfg.spk_id[0] && s_nfound == 1) sel = 0;
    if (sel >= 0) {
        memset(&s_peer, 0, sizeof s_peer);
        s_peer.sin_family = AF_INET;
        s_peer.sin_port = htons(SPEAKER_PORT);
        inet_aton(s_found[sel].ip, &s_peer.sin_addr);
        strcpy(s_cur_id, s_found[sel].id);
    }
    xSemaphoreGive(s_mx);
    if (sel >= 0) ESP_LOGI(TAG, "following %s", s_cur_id);
    return sel >= 0;
}

static void link_task(void *arg)
{
    static char buf[4096];
    enum { SEARCH, FOLLOW } mode = SEARCH;
    int64_t search_start = 0, last_find = 0, last_sub = 0, last_st = 0;
    for (;;) {
        if (net_state() != NET_ONLINE) {
            set_phase(LINK_OFFLINE);
            mode = SEARCH;
            search_start = 0;
            vTaskDelay(pdMS_TO_TICKS(500));
            continue;
        }
        if (s_sock < 0) {
            s_sock = socket(AF_INET, SOCK_DGRAM, 0);
            int yes = 1;
            setsockopt(s_sock, SOL_SOCKET, SO_BROADCAST, &yes, sizeof yes);
            struct timeval tv = { .tv_sec = 0, .tv_usec = 100000 };
            setsockopt(s_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
            struct sockaddr_in me = { .sin_family = AF_INET, .sin_port = 0, .sin_addr.s_addr = htonl(INADDR_ANY) };
            bind(s_sock, (struct sockaddr *)&me, sizeof me);
        }
        int64_t t = now_us();
        if (s_research) {
            s_research = false;
            mode = SEARCH;
            search_start = 0;
            if (s_peer.sin_addr.s_addr) send_to(&s_peer, "{\"t\":\"bye\"}");
            memset(&s_peer, 0, sizeof s_peer);
        }
        if (mode == SEARCH) {
            if (!search_start) {
                search_start = t;
                xSemaphoreTake(s_mx, portMAX_DELAY);
                s_nfound = 0;
                xSemaphoreGive(s_mx);
                set_phase(s_st.phase == LINK_CONNECTED || s_st.phase == LINK_LOST ? LINK_LOST : LINK_SEARCHING);
            }
            if (t - last_find > 1000000) { send_find(); last_find = t; }
            if (t - search_start > 2500000) {
                if (pick()) {
                    mode = FOLLOW;
                    last_sub = 0;
                    last_st = t;
                } else {
                    xSemaphoreTake(s_mx, portMAX_DELAY);
                    int n = s_nfound;
                    xSemaphoreGive(s_mx);
                    // several and none saved, or the saved one is not there: let the user choose
                    set_phase(n > 0 ? LINK_CHOOSE : (s_st.phase == LINK_LOST ? LINK_LOST : LINK_SEARCHING));
                    if (t - search_start > 6000000) search_start = 0;   // ask again, keep the list fresh
                }
            }
        } else {
            if (t - last_sub > 8000000) { send_sub(); last_sub = t; }
            if (t - last_st > 12000000) {
                ESP_LOGW(TAG, "speaker quiet, searching again");
                mode = SEARCH;
                search_start = 0;
                xSemaphoreTake(s_mx, portMAX_DELAY);
                s_st.phase = LINK_LOST;
                s_st.seq++;
                xSemaphoreGive(s_mx);
            }
        }

        struct sockaddr_in from;
        socklen_t fl = sizeof from;
        int n = recvfrom(s_sock, buf, sizeof buf - 1, 0, (struct sockaddr *)&from, &fl);
        if (n <= 0) continue;
        if (n == 2 + LINK_BANDS && buf[0] == 'S' && buf[1] == 'P') {
            if (mode == FOLLOW && from.sin_addr.s_addr == s_peer.sin_addr.s_addr) {
                xSemaphoreTake(s_mx, portMAX_DELAY);
                memcpy(s_spec, buf + 2, LINK_BANDS);
                s_spec_us = now_us();
                xSemaphoreGive(s_mx);
            }
            continue;
        }
        buf[n] = 0;
        cJSON *j = cJSON_Parse(buf);
        if (!j) continue;
        const cJSON *ty = cJSON_GetObjectItemCaseSensitive(j, "t");
        if (cJSON_IsString(ty)) {
            if (!strcmp(ty->valuestring, "here")) {
                on_here(j, &from);
            } else if (!strcmp(ty->valuestring, "st") && mode == FOLLOW &&
                       from.sin_addr.s_addr == s_peer.sin_addr.s_addr) {
                last_st = now_us();
                set_clock(j);
                xSemaphoreTake(s_mx, portMAX_DELAY);
                parse_state(j);
                xSemaphoreGive(s_mx);
            } else if (mode == FOLLOW && from.sin_addr.s_addr == s_peer.sin_addr.s_addr) {
                xSemaphoreTake(s_mx, portMAX_DELAY);
                if (!strcmp(ty->valuestring, "list")) parse_list(j);
                else if (!strcmp(ty->valuestring, "alarms")) parse_alarms(j);
                else if (!strcmp(ty->valuestring, "res")) {
                    s_res.ok = flag(j, "ok");
                    copy(s_res.msg, sizeof s_res.msg, j, "msg");
                    s_res.seq++;
                }
                xSemaphoreGive(s_mx);
            }
        }
        cJSON_Delete(j);
    }
}

void link_init(void)
{
    s_mx = xSemaphoreCreateMutex();
    s_st.phase = LINK_OFFLINE;
    xTaskCreatePinnedToCore(link_task, "link", 6144, NULL, 5, NULL, 0);
}

bool link_state(spk_state_t *out, uint32_t *seq)
{
    bool changed = false;
    xSemaphoreTake(s_mx, portMAX_DELAY);
    if (s_st.seq != *seq) {
        *out = s_st;
        *seq = s_st.seq;
        changed = true;
    }
    xSemaphoreGive(s_mx);
    return changed;
}

bool link_spectrum(uint8_t out[LINK_BANDS])
{
    xSemaphoreTake(s_mx, portMAX_DELAY);
    bool live = s_spec_us && now_us() - s_spec_us < 500000;
    if (live) memcpy(out, s_spec, LINK_BANDS);
    xSemaphoreGive(s_mx);
    if (!live) memset(out, 0, LINK_BANDS);
    return live;
}

int link_found(speaker_t *out, int max)
{
    xSemaphoreTake(s_mx, portMAX_DELAY);
    int n = s_nfound < max ? s_nfound : max;
    memcpy(out, s_found, n * sizeof *out);
    xSemaphoreGive(s_mx);
    return n;
}

void link_choose(const char *id)
{
    strncpy(g_cfg.spk_id, id, sizeof g_cfg.spk_id - 1);
    cfg_save();
    s_research = true;
}

void link_research(void) { s_research = true; }

void link_cmd(const char *c)
{
    if (!s_peer.sin_addr.s_addr) return;
    char t[64];
    snprintf(t, sizeof t, "{\"t\":\"cmd\",\"c\":\"%s\"}", c);
    send_to(&s_peer, t);
}

/* ── the menu's requests ── */

static void send_obj(cJSON *o)
{
    char *t = cJSON_PrintUnformatted(o);
    if (s_peer.sin_addr.s_addr && t) send_to(&s_peer, t);
    cJSON_free(t);
    cJSON_Delete(o);
}

static cJSON *msg(const char *t, const char *k)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "t", t);
    if (k) cJSON_AddStringToObject(o, "k", k);
    return o;
}

void link_request(const char *kind, const char *id)
{
    cJSON *o = msg("req", kind);
    if (id) cJSON_AddStringToObject(o, "id", id);
    send_obj(o);
}

void link_play_radio(const char *id)
{
    cJSON *o = msg("play", "radio");
    cJSON_AddStringToObject(o, "id", id);
    send_obj(o);
}

void link_play_episode(int n)
{
    cJSON *o = msg("play", "episode");
    cJSON_AddNumberToObject(o, "n", n);
    send_obj(o);
}

void link_play_music(const char *query)
{
    cJSON *o = msg("play", "music");
    cJSON_AddStringToObject(o, "q", query);
    send_obj(o);
}

static cJSON *al_msg(const char *op, const char *id)
{
    cJSON *o = msg("al", NULL);
    cJSON_AddStringToObject(o, "op", op);
    cJSON_AddStringToObject(o, "id", id ? id : "");
    return o;
}

void link_alarm_toggle(const char *id, bool on)
{
    cJSON *o = al_msg("toggle", id);
    cJSON_AddBoolToObject(o, "on", on);
    send_obj(o);
}

void link_alarm_save(const char *id, const char *time, uint8_t days, const char *label)
{
    cJSON *o = al_msg("save", id);
    cJSON_AddStringToObject(o, "time", time);
    cJSON *d = cJSON_AddArrayToObject(o, "days");
    for (int i = 0; i < 7; i++) if (days & (1 << i)) cJSON_AddItemToArray(d, cJSON_CreateNumber(i));
    cJSON_AddStringToObject(o, "label", label ? label : "");
    send_obj(o);
}

void link_alarm_del(const char *id) { send_obj(al_msg("del", id)); }

static bool take(const void *src, void *dst, size_t n, const uint32_t *srcseq, uint32_t *seq)
{
    bool ch = false;
    xSemaphoreTake(s_mx, portMAX_DELAY);
    if (*srcseq != *seq) { memcpy(dst, src, n); *seq = *srcseq; ch = true; }
    xSemaphoreGive(s_mx);
    return ch;
}

bool link_get_list(link_list_t *out, uint32_t *seq) { return take(&s_list, out, sizeof *out, &s_list.seq, seq); }
bool link_get_alarms(link_alarms_t *out, uint32_t *seq) { return take(&s_alarms, out, sizeof *out, &s_alarms.seq, seq); }
bool link_get_res(link_res_t *out, uint32_t *seq) { return take(&s_res, out, sizeof *out, &s_res.seq, seq); }
