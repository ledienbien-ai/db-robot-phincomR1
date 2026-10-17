/*
 * What the display shows. 320x240 landscape: a top bar (speaker, volume, clock, theme button) and,
 * below it, two tiles you swipe between:
 *
 *   tile 0 (main)  one of these pages, chosen from the speaker's state:
 *     status  – no Wi-Fi yet / looking for the speaker / connection lost
 *     choose  – several speakers on the network, tap one
 *     voice   – the assistant is listening or talking: a face, what was said, "Tắt nghe"
 *     music   – a song, a radio station or a podcast: title, spectrum, progress, buttons
 *     idle    – a big clock, the date, the weather, the next alarm, "Tắt nghe" / "Gọi loa"
 *   tile 1 (apps)  a phone-style launcher: theme, brightness, volume, call, find speaker,
 *                  screen settings, info, touch calibration. Most open a small settings sheet.
 *
 * On top of everything the alarm, while it rings.
 *
 * Two themes, the same two the speaker's control panel has: dark (red accent) and light (the blue
 * of dbrobot.vn). The choice is kept in NVS; switching rebuilds the whole screen, which is simpler
 * and surer than restyling every object.
 */
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <stdlib.h>
#include "lvgl.h"
#include "ui.h"
#include "link.h"
#include "net.h"
#include "cfg.h"
#include "display.h"
#include "touch.h"
#include "video.h"
#include "board.h"
#ifdef ESP_PLATFORM
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "esp_system.h"
#define NOW_US() esp_timer_get_time()
#else
int64_t sim_now_us(void);
void esp_restart(void);
#define NOW_US() sim_now_us()
#endif

LV_FONT_DECLARE(font_vn14);
LV_FONT_DECLARE(font_vn18);
LV_FONT_DECLARE(font_vn24);
LV_FONT_DECLARE(font_flip64);
LV_FONT_DECLARE(font_num52);
LV_FONT_DECLARE(font_icon40);
LV_FONT_DECLARE(font_icon30);

/* FontAwesome code points present in the fonts */
#define I_MUSIC   ""
#define I_PLAY    ""
#define I_PAUSE   ""
#define I_STOP    ""
#define I_NEXT    ""
#define I_VOLUP   ""
#define I_VOLDN   ""
#define I_VOLOFF  ""
#define I_WIFI    ""
#define I_BELL    ""
#define I_MIC     ""
#define I_MICOFF  ""
#define I_SUN     ""
#define I_MOON    ""
#define I_CLOUD   ""
#define I_RAIN    ""
#define I_BOLT    ""
#define I_SNOW    ""
#define I_SMOG    ""
#define I_GLOBE   ""
#define I_SYNC    ""
#define I_USERS   ""
#define I_MINUS   ""
#define I_PLUS    ""
#define I_CHECK   ""
#define I_BULB    ""
#define I_TV      ""
#define I_INFO    ""
#define I_HAND    ""
#define I_POWER   ""
#define I_BCAST   ""
#define I_PODCAST ""
#define I_DISH    ""
#define I_COG     ""
#define I_BACK    ""

// ── themes ───────────────────────────────────────────────────────────────────────
// The colours of the control panel (assets/control.html): dark with the red accent, and the light
// theme in the blue of dbrobot.vn.

typedef struct {
    uint32_t bg, bar, bar_text, card, card2, line, text, muted;
    uint32_t acc;          // accent as text / border / icon colour
    uint32_t a1, a2;       // accent gradient (buttons, spectrum)
    uint32_t on_a;         // text on the gradient
    uint32_t ok, warn, err, sad, pink;
    uint32_t bar2;         // second colour of the top bar gradient (= bar when flat)
    bool light;
} pal_t;

static const pal_t PAL_DARK = {
    .bg = 0x07090E, .bar = 0x0B0D14, .bar_text = 0xCDD3E0, .card = 0x0F1219, .card2 = 0x161B26,
    .line = 0x252C3D, .text = 0xCDD3E0, .muted = 0x7B8AAD,
    .acc = 0xF0423D, .a1 = 0xDB1214, .a2 = 0xFF5A4A, .on_a = 0xFFFFFF,
    .ok = 0x22C55E, .warn = 0xF59E0B, .err = 0xEF4444, .sad = 0x93C5FD, .pink = 0xF472B6,
    .bar2 = 0x0B0D14, .light = false,
};
static const pal_t PAL_LIGHT = {
    .bg = 0xF4F8FF, .bar = 0x0B3FA8, .bar_text = 0xFFFFFF, .card = 0xFFFFFF, .card2 = 0xEEF4FF,
    .line = 0xDCE6F5, .text = 0x06214A, .muted = 0x46596F,
    .acc = 0x1462E8, .a1 = 0x0B3FA8, .a2 = 0x2C7BF2, .on_a = 0xFFFFFF,
    .ok = 0x16A34A, .warn = 0xD97706, .err = 0xDC2626, .sad = 0x2563EB, .pink = 0xDB2777,
    .bar2 = 0x2C7BF2, .light = true,
};
static const pal_t *P = &PAL_DARK;

#define C_BG     (P->bg)
#define C_CARD   (P->card)
#define C_CARD2  (P->card2)
#define C_LINE   (P->line)
#define C_TEXT   (P->text)
#define C_MUTED  (P->muted)
#define C_ACCENT (P->acc)
#define C_GREEN  (P->ok)
#define C_ORANGE (P->warn)
#define C_RED    (P->err)
#define C_PINK   (P->pink)

static void apply_palette(void) { P = g_cfg.light ? &PAL_LIGHT : &PAL_DARK; }

#define TOP_H 28
#define PAGE_H (LCD_V_RES - TOP_H)

typedef enum { P_STATUS, P_CHOOSE, P_VOICE, P_MUSIC, P_IDLE, P_COUNT } page_t;

enum { APP_FIND, APP_WIFI, APP_ALARM, APP_RADIO, APP_MUSIC, APP_PODCAST, APP_SETTINGS, APP_INFO, APP_COUNT };

static spk_state_t st;           // the copy the UI works from
static uint32_t st_seq = 0xFFFFFFFF;
static page_t cur_page = P_COUNT;
static int64_t voice_until;      // keep the voice page a little after the talk ends
static int64_t lock_until;       // after "Tắt nghe": the chat page stays locked while the speaker winds down
static int64_t lock_resend;      // next time to repeat "sleep" while the speaker still talks
static int64_t lock_quiet_since; // since when the speaker has been quiet (unlocks after 1.5 s)

static lv_obj_t *pages[P_COUNT];
static lv_obj_t *top_dot, *top_name, *top_right, *top_theme;
static lv_obj_t *tv, *tile_main, *tile_apps, *dot[2];
static lv_obj_t *app_icon_lbl[APP_COUNT];
/* status */
static lv_obj_t *s_icon, *s_title, *s_text;
/* choose */
static lv_obj_t *c_list;
static char c_ids[LINK_MAX_FOUND][48];
static int c_count = -1;
static uint32_t c_hash;
/* voice */
static lv_obj_t *v_face, *v_eye[2], *v_mouth_arc, *v_mouth_o, *v_state, *v_scroll, *v_user, *v_bot, *v_btn;
static int v_anim;
/* music */
static lv_obj_t *v_btn, *v_layer, *v_img, *v_msg;   // picture of the song (video.c)
static lv_image_dsc_t v_dsc;
static uint32_t v_seq;
static char v_vid[12];
static lv_obj_t *m_title, *m_artist, *m_bars[LINK_BANDS], *m_bar, *m_pos, *m_dur, *m_play;
static uint8_t m_level[LINK_BANDS];
/* idle */
static lv_obj_t *i_date, *i_wicon, *i_temp, *i_wdesc, *i_city, *i_info, *i_mute;
/* alarm */
static lv_obj_t *a_layer, *a_text;
/* settings sheet */
static lv_obj_t *sheet, *sheet_title, *sheet_body;
static int sheet_app = -1;
static lv_obj_t *vol_num, *vol_bar, *bri_num;

static void request_rebuild(void);

static void open_sheet(int app);
static void close_sheet(void);
static void sheet_back(void);
static void menu_reset(void);
static void sheet_list(lv_obj_t *b, int mode);
static void sheet_alarm(lv_obj_t *b);
static void sheet_music(lv_obj_t *b);
static void sheet_wifi(lv_obj_t *b);
static void toast(const char *msg, bool ok);
enum { LM_RADIO, LM_PODCAST, LM_EPISODES };

// ── helpers ──────────────────────────────────────────────────────────────────────

static lv_obj_t *label(lv_obj_t *parent, const lv_font_t *f, uint32_t color, const char *text)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_obj_set_style_text_font(l, f, 0);
    lv_obj_set_style_text_color(l, lv_color_hex(color), 0);
    lv_label_set_text(l, text);
    return l;
}

static lv_obj_t *box(lv_obj_t *parent, int x, int y, int w, int h)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_remove_style_all(o);
    lv_obj_set_pos(o, x, y);
    lv_obj_set_size(o, w, h);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
    return o;
}

static void cmd_cb(lv_event_t *e)
{
    const char *c = (const char *)lv_event_get_user_data(e);
    if (!strcmp(c, "wake")) lock_until = 0;
    link_cmd(c);
}

/* A button drawn from scratch (no theme styles to fight): primary = the accent gradient, otherwise
 * a bordered card. The label is the first child. */
static lv_obj_t *button(lv_obj_t *parent, int x, int y, int w, int h, const char *text,
                        const lv_font_t *f, bool primary, const char *cmd)
{
    lv_obj_t *b = box(parent, x, y, w, h);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_radius(b, 10, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    if (primary) {
        lv_obj_set_style_bg_color(b, lv_color_hex(P->a1), 0);
        lv_obj_set_style_bg_grad_color(b, lv_color_hex(P->a2), 0);
        lv_obj_set_style_bg_grad_dir(b, LV_GRAD_DIR_HOR, 0);
    } else {
        lv_obj_set_style_bg_color(b, lv_color_hex(C_CARD2), 0);
        lv_obj_set_style_border_width(b, 1, 0);
        lv_obj_set_style_border_color(b, lv_color_hex(C_LINE), 0);
    }
    lv_obj_set_style_bg_opa(b, LV_OPA_70, LV_STATE_PRESSED);
    lv_obj_t *l = label(b, f, primary ? P->on_a : C_TEXT, text);
    lv_obj_center(l);
    if (cmd) lv_obj_add_event_cb(b, cmd_cb, LV_EVENT_CLICKED, (void *)cmd);
    return b;
}

static void set_text(lv_obj_t *l, const char *t)
{
    const char *have = lv_label_get_text(l);
    if (!have || strcmp(have, t)) lv_label_set_text(l, t);
}

static void btn_text(lv_obj_t *b, const char *t) { set_text(lv_obj_get_child(b, 0), t); }

static bool clock_ok(void) { return time(NULL) > 1700000000; }

static const char *const WEEKDAY[] = { "Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy" };

static void fmt_time(char *out, int n, int secs)
{
    if (secs < 0) secs = 0;
    if (secs >= 3600) snprintf(out, n, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60);
    else snprintf(out, n, "%d:%02d", secs / 60, secs % 60);
}

static const char *weather_icon(int code, bool day)
{
    if (code == 0) return day ? I_SUN : I_MOON;
    if (code >= 1 && code <= 3) return code == 1 ? (day ? I_SUN : I_MOON) : I_CLOUD;
    if (code == 45 || code == 48) return I_SMOG;
    if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return I_RAIN;
    if ((code >= 71 && code <= 77) || code == 85 || code == 86) return I_SNOW;
    if (code >= 95) return I_BOLT;
    return I_CLOUD;
}

static bool music_active(void)
{
    return st.has_m && (!strcmp(st.ms, "playing") || !strcmp(st.ms, "paused") || !strcmp(st.ms, "downloading"));
}

static bool talking(void)
{
    if (!st.awake && strcmp(st.vs, "LISTENING") && strcmp(st.vs, "SPEAKING")) return false;
    if (!strcmp(st.vs, "LISTENING")) return true;
    // SPEAKING while a local song plays is the song, not the assistant
    if (!strcmp(st.vs, "SPEAKING")) return !(st.has_m && !strcmp(st.ms, "playing"));
    return st.awake && !music_active();
}

static bool apps_tile_active(void) { return tv && lv_tileview_get_tile_active(tv) == tile_apps; }

static void go_main(void)
{
    close_sheet();
    if (tv) lv_tileview_set_tile_by_index(tv, 0, 0, LV_ANIM_ON);
}

// ── backlight ────────────────────────────────────────────────────────────────────

static void backlight_update(void)
{
    int b = g_cfg.bright;
    if (g_cfg.night_dim && clock_ok()) {
        time_t t = time(NULL);
        struct tm tm;
        localtime_r(&t, &tm);
        if (tm.tm_hour >= 22 || tm.tm_hour < 6) b = b * 25 / 100;
    }
    // ringing, talking, or setting the brightness: the real level
    if (st.al_ring[0] || cur_page == P_VOICE || sheet_app == APP_SETTINGS || v_layer) b = g_cfg.bright;
    if (b < 3) b = 3;
    static int last = -1;
    if (b != last) { display_backlight(b); last = b; }
}

// ── top bar ──────────────────────────────────────────────────────────────────────

static void top_long_press(lv_event_t *e)
{
    // switch speaker: forget the choice and look again
    g_cfg.spk_id[0] = 0;
    link_research();
    go_main();
}

static void theme_cb(lv_event_t *e)
{
    g_cfg.light = !g_cfg.light;
    cfg_save();
    request_rebuild();
}

static void build_top(lv_obj_t *scr)
{
    lv_obj_t *bar = box(scr, 0, 0, LCD_H_RES, TOP_H);
    lv_obj_set_style_bg_opa(bar, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(bar, lv_color_hex(P->bar), 0);
    if (P->light) {   // the blue navigation bar of dbrobot.vn
        lv_obj_set_style_bg_grad_color(bar, lv_color_hex(P->bar2), 0);
        lv_obj_set_style_bg_grad_dir(bar, LV_GRAD_DIR_HOR, 0);
    } else {
        lv_obj_set_style_border_side(bar, LV_BORDER_SIDE_BOTTOM, 0);
        lv_obj_set_style_border_width(bar, 1, 0);
        lv_obj_set_style_border_color(bar, lv_color_hex(C_LINE), 0);
    }
    lv_obj_add_flag(bar, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(bar, top_long_press, LV_EVENT_LONG_PRESSED, NULL);
    top_dot = box(bar, 8, 10, 8, 8);
    lv_obj_set_style_radius(top_dot, 4, 0);
    lv_obj_set_style_bg_opa(top_dot, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(top_dot, lv_color_hex(C_MUTED), 0);
    top_name = label(bar, &font_vn14, P->bar_text, "DB-Robot");
    lv_obj_set_pos(top_name, 22, 5);
    lv_obj_set_width(top_name, 112);
    lv_label_set_long_mode(top_name, LV_LABEL_LONG_DOT);
    top_right = label(bar, &font_vn14, P->bar_text, "");
    lv_obj_align(top_right, LV_ALIGN_RIGHT_MID, -38, 0);
    // the sun / moon button, as in the panel's header: shows the mode you are in, tap to switch
    top_theme = box(bar, LCD_H_RES - 34, 0, 34, TOP_H);
    lv_obj_add_flag(top_theme, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_t *ic = label(top_theme, &font_vn18, P->bar_text, P->light ? I_SUN : I_MOON);
    lv_obj_center(ic);
    lv_obj_add_event_cb(top_theme, theme_cb, LV_EVENT_CLICKED, NULL);
}

static void update_top(void)
{
    char right[112] = "", clk[16] = "--:--";
    if (clock_ok()) {
        time_t t = time(NULL);
        struct tm tm;
        localtime_r(&t, &tm);
        snprintf(clk, sizeof clk, "%02d:%02d", tm.tm_hour, tm.tm_min);
    }
    if (st.phase == LINK_CONNECTED) {
        char grp[32] = "", vol[40] = "";
        if (st.group > 0) snprintf(grp, sizeof grp, I_USERS " %d   ", st.group + 1);
        if (st.vol >= 0) snprintf(vol, sizeof vol, "%s %d%%   ", st.vol == 0 ? I_VOLOFF : st.vol < 50 ? I_VOLDN : I_VOLUP, st.vol);
        snprintf(right, sizeof right, "%s%s%s%s", grp, st.mute ? I_MICOFF "   " : "", vol, clk);
        set_text(top_name, st.name[0] ? st.name : "Loa DB-Robot");
        lv_obj_set_style_bg_color(top_dot, lv_color_hex(P->light ? 0x7CF2A8 : C_GREEN), 0);
    } else {
        snprintf(right, sizeof right, "%s", clk);
        set_text(top_name, "DB-Robot");
        lv_obj_set_style_bg_color(top_dot, lv_color_hex(st.phase == LINK_LOST ? C_ORANGE : (P->light ? 0xBBD0F5 : C_MUTED)), 0);
    }
    set_text(top_right, right);
}

// ── status page ──────────────────────────────────────────────────────────────────

static void build_status(lv_obj_t *p)
{
    lv_obj_set_flex_flow(p, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(p, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_row(p, 8, 0);
    lv_obj_set_style_pad_bottom(p, 10, 0);
    s_icon = label(p, &font_vn24, C_ACCENT, I_WIFI);
    s_title = label(p, &font_vn24, C_TEXT, "");
    lv_obj_set_width(s_title, 300);
    lv_label_set_long_mode(s_title, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(s_title, LV_TEXT_ALIGN_CENTER, 0);
    s_text = label(p, &font_vn14, C_MUTED, "");
    lv_obj_set_width(s_text, 296);
    lv_label_set_long_mode(s_text, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(s_text, LV_TEXT_ALIGN_CENTER, 0);
}

static void update_status(void)
{
    char ip[16], t[300];
    net_ip(ip, sizeof ip);
    net_state_t ns = net_state();
    if (ns == NET_SETUP) {
        set_text(s_icon, I_WIFI);
        set_text(s_title, g_cfg.ssid[0] ? "Chưa vào được Wi-Fi" : "Cài đặt Wi-Fi");
        snprintf(t, sizeof t, "Dùng điện thoại vào Wi-Fi\n%s\nrồi mở trình duyệt: 192.168.4.1\n(trang cài đặt thường tự mở ra)", net_ap_ssid());
    } else if (ns == NET_CONNECTING) {
        set_text(s_icon, I_WIFI);
        set_text(s_title, "Đang vào Wi-Fi…");
        snprintf(t, sizeof t, "%s", g_cfg.ssid);
    } else if (st.phase == LINK_LOST) {
        set_text(s_icon, I_SYNC);
        set_text(s_title, "Mất kết nối với loa");
        snprintf(t, sizeof t, "%s — đang tìm lại…\nKiểm tra loa còn bật và chung mạng Wi-Fi.\nCài đặt màn hình: http://%s", st.name, ip);
    } else {
        set_text(s_icon, I_SYNC);
        set_text(s_title, "Đang tìm loa…");
        snprintf(t, sizeof t, "Loa Phicomm R1 (DB-Robot 1.5.15 trở lên) cần bật và chung mạng Wi-Fi với màn hình.\nCài đặt màn hình: http://%s", ip);
    }
    set_text(s_text, t);
}

// ── choose page ──────────────────────────────────────────────────────────────────

static void choose_cb(lv_event_t *e)
{
    int i = (int)(intptr_t)lv_event_get_user_data(e);
    if (i >= 0 && i < c_count) link_choose(c_ids[i]);
}

static void build_choose(lv_obj_t *p)
{
    lv_obj_t *t = label(p, &font_vn18, C_TEXT, "Chọn loa để hiển thị");
    lv_obj_set_pos(t, 12, 6);
    c_list = lv_obj_create(p);
    lv_obj_remove_style_all(c_list);
    lv_obj_set_pos(c_list, 8, 36);
    lv_obj_set_size(c_list, LCD_H_RES - 16, PAGE_H - 48);
    lv_obj_set_style_pad_row(c_list, 6, 0);
    lv_obj_set_flex_flow(c_list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_scrollbar_mode(c_list, LV_SCROLLBAR_MODE_OFF);
}

static void update_choose(void)
{
    speaker_t f[LINK_MAX_FOUND];
    int n = link_found(f, LINK_MAX_FOUND);
    uint32_t h = n;
    for (int i = 0; i < n; i++) for (const char *s = f[i].id; *s; s++) h = h * 31 + (uint8_t)*s;
    for (int i = 0; i < n; i++) for (const char *s = f[i].name; *s; s++) h = h * 31 + (uint8_t)*s;
    if (n == c_count && h == c_hash) return;
    c_count = n;
    c_hash = h;
    lv_obj_clean(c_list);
    for (int i = 0; i < n; i++) {
        strcpy(c_ids[i], f[i].id);
        lv_obj_t *b = lv_obj_create(c_list);
        lv_obj_remove_style_all(b);
        lv_obj_set_size(b, lv_pct(100), 48);
        lv_obj_remove_flag(b, LV_OBJ_FLAG_SCROLLABLE);
        lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(b, lv_color_hex(C_CARD), 0);
        lv_obj_set_style_bg_opa(b, LV_OPA_70, LV_STATE_PRESSED);
        lv_obj_set_style_border_color(b, lv_color_hex(C_LINE), 0);
        lv_obj_set_style_border_width(b, 1, 0);
        lv_obj_set_style_radius(b, 10, 0);
        char t[96];
        snprintf(t, sizeof t, "%s", f[i].name[0] ? f[i].name : "Loa DB-Robot");
        lv_obj_t *l = label(b, &font_vn18, C_TEXT, t);
        lv_obj_align(l, LV_ALIGN_LEFT_MID, 10, -7);
        snprintf(t, sizeof t, "%s · %s%s", f[i].ip, f[i].ver, f[i].playing ? " · " I_MUSIC : "");
        l = label(b, &font_vn14, C_MUTED, t);
        lv_obj_align(l, LV_ALIGN_LEFT_MID, 10, 12);
        lv_obj_add_event_cb(b, choose_cb, LV_EVENT_CLICKED, (void *)(intptr_t)i);
    }
}

// ── voice page ───────────────────────────────────────────────────────────────────

static void voice_btn_cb(lv_event_t *e)
{
    if (st.awake || talking()) {
        // "Tắt nghe": end the conversation (sleep), stop listening for the wake word (mute, speakers
        // from 1.5.16; an older one just ends the conversation), and go back to the main page.
        link_cmd("sleep");
        link_cmd("mute");
        voice_until = 0;
        lock_until = NOW_US() + 20 * 1000000LL;
        lock_resend = NOW_US() + 2000000;
        lock_quiet_since = 0;
    } else {
        lock_until = 0;
        link_cmd("wake");
    }
}

static void build_voice(lv_obj_t *p)
{
    v_face = box(p, 8, 10, 116, 116);
    lv_obj_set_style_radius(v_face, 58, 0);
    lv_obj_set_style_bg_color(v_face, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_opa(v_face, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(v_face, 3, 0);
    lv_obj_set_style_border_color(v_face, lv_color_hex(C_ACCENT), 0);
    for (int i = 0; i < 2; i++) {
        v_eye[i] = box(v_face, 0, 0, 20, 30);
        lv_obj_set_style_radius(v_eye[i], 10, 0);
        lv_obj_set_style_bg_opa(v_eye[i], LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(v_eye[i], lv_color_hex(C_TEXT), 0);
    }
    v_mouth_arc = lv_arc_create(v_face);
    lv_obj_remove_style(v_mouth_arc, NULL, LV_PART_KNOB);
    lv_obj_remove_flag(v_mouth_arc, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_arc_opa(v_mouth_arc, LV_OPA_TRANSP, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(v_mouth_arc, 5, LV_PART_MAIN);
    lv_obj_set_style_arc_rounded(v_mouth_arc, true, LV_PART_MAIN);
    lv_obj_set_style_arc_color(v_mouth_arc, lv_color_hex(C_TEXT), LV_PART_MAIN);
    lv_obj_set_style_pad_all(v_mouth_arc, 0, 0);
    v_mouth_o = box(v_face, 0, 0, 18, 18);
    lv_obj_set_style_radius(v_mouth_o, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(v_mouth_o, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(v_mouth_o, lv_color_hex(C_TEXT), 0);

    v_state = label(p, &font_vn14, C_ACCENT, "");
    lv_obj_set_width(v_state, 132);
    lv_obj_set_style_text_align(v_state, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(v_state, 0, 130);
    v_btn = button(p, 12, 158, 108, 38, I_MICOFF "  Tắt nghe", &font_vn14, false, NULL);
    lv_obj_add_event_cb(v_btn, voice_btn_cb, LV_EVENT_CLICKED, NULL);

    v_scroll = box(p, 134, 4, LCD_H_RES - 140, PAGE_H - 24);
    lv_obj_add_flag(v_scroll, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_bg_color(v_scroll, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_opa(v_scroll, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(v_scroll, 1, 0);
    lv_obj_set_style_border_color(v_scroll, lv_color_hex(C_LINE), 0);
    lv_obj_set_style_radius(v_scroll, 10, 0);
    lv_obj_set_style_pad_all(v_scroll, 8, 0);
    lv_obj_set_style_pad_row(v_scroll, 6, 0);
    lv_obj_set_flex_flow(v_scroll, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_scrollbar_mode(v_scroll, LV_SCROLLBAR_MODE_OFF);
    v_user = label(v_scroll, &font_vn14, C_MUTED, "");
    lv_obj_set_width(v_user, lv_pct(100));
    lv_label_set_long_mode(v_user, LV_LABEL_LONG_WRAP);
    v_bot = label(v_scroll, &font_vn18, C_TEXT, "");
    lv_obj_set_width(v_bot, lv_pct(100));
    lv_label_set_long_mode(v_bot, LV_LABEL_LONG_WRAP);
}

/* The face: eye height, eye lift, mouth (smile > 0, frown < 0, 'o' = 99, flat = 0), colour. */
static void face(int eye_h, int lift, int mouth, uint32_t color, int open)
{
    const int cx = 58 - 3, cy = 58 - 3;   // inside the border
    for (int i = 0; i < 2; i++) {
        int w = eye_h > 30 ? 24 : 20;
        lv_obj_set_size(v_eye[i], w, eye_h);
        lv_obj_set_style_radius(v_eye[i], eye_h < 10 ? 3 : w / 2, 0);
        lv_obj_set_pos(v_eye[i], cx + (i ? 14 : -14 - w), cy - 14 - eye_h / 2 - lift);
        lv_obj_set_style_bg_color(v_eye[i], lv_color_hex(color), 0);
    }
    lv_obj_set_style_border_color(v_face, lv_color_hex(color == C_TEXT ? C_ACCENT : color), 0);
    if (mouth == 99 || open > 0) {
        int d = mouth == 99 ? 18 : 6 + open;
        lv_obj_remove_flag(v_mouth_o, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(v_mouth_arc, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_size(v_mouth_o, mouth == 99 ? 18 : 26, d);
        lv_obj_set_style_radius(v_mouth_o, mouth == 99 ? LV_RADIUS_CIRCLE : 8, 0);
        lv_obj_set_pos(v_mouth_o, cx - (mouth == 99 ? 9 : 13), cy + 22 - d / 2);
        lv_obj_set_style_bg_color(v_mouth_o, lv_color_hex(color), 0);
        return;
    }
    lv_obj_add_flag(v_mouth_o, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(v_mouth_arc, LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_style_arc_color(v_mouth_arc, lv_color_hex(color), LV_PART_MAIN);
    if (mouth > 0) {          // smile: the lower part of a circle
        int r = mouth > 1 ? 22 : 18;
        lv_obj_set_size(v_mouth_arc, r * 2, r * 2);
        lv_obj_set_pos(v_mouth_arc, cx - r, cy + 4 - r + (mouth > 1 ? 0 : 4));
        lv_arc_set_bg_angles(v_mouth_arc, 30, 150);
    } else if (mouth < 0) {   // frown: the upper part
        lv_obj_set_size(v_mouth_arc, 36, 36);
        lv_obj_set_pos(v_mouth_arc, cx - 18, cy + 26);
        lv_arc_set_bg_angles(v_mouth_arc, 215, 325);
    } else {                  // flat: a very wide, shallow smile
        lv_obj_set_size(v_mouth_arc, 80, 80);
        lv_obj_set_pos(v_mouth_arc, cx - 40, cy + 22 - 80 + 4);
        lv_arc_set_bg_angles(v_mouth_arc, 76, 104);
    }
}

static void update_face(void)
{
    const char *e = st.emo;
    bool listening = !strcmp(st.vs, "LISTENING");
    bool speaking = !strcmp(st.vs, "SPEAKING");
    int eye = 30, lift = 0, mouth = 1, open = 0;
    uint32_t col = C_TEXT;
    if (!strcmp(e, "happy") || !strcmp(e, "laughing") || !strcmp(e, "funny") || !strcmp(e, "loving") ||
        !strcmp(e, "delicious") || !strcmp(e, "kissy") || !strcmp(e, "silly")) { eye = 14; mouth = 2; }
    else if (!strcmp(e, "sad") || !strcmp(e, "crying")) { eye = 22; mouth = -1; col = P->sad; }
    else if (!strcmp(e, "angry")) { eye = 16; mouth = -1; col = P->err; }
    else if (!strcmp(e, "surprised") || !strcmp(e, "shocked")) { eye = 36; mouth = 99; }
    else if (!strcmp(e, "thinking") || !strcmp(e, "confused")) { eye = 24; lift = 6; mouth = 0; }
    else if (!strcmp(e, "sleepy") || !strcmp(e, "relaxed")) { eye = 6; mouth = 0; }
    else if (!strcmp(e, "cool") || !strcmp(e, "confident") || !strcmp(e, "winking")) { eye = 12; mouth = 1; }
    else if (!strcmp(e, "embarrassed")) { eye = 20; mouth = 0; col = P->pink; }
    if (listening) { col = P->ok; mouth = mouth == 99 ? 99 : 0; eye = eye < 24 ? 26 : eye; }
    v_anim++;
    if (speaking && mouth != 99) open = (int[]){ 2, 8, 14, 6, 12, 4, 10, 16 }[v_anim % 8];
    // blink now and then
    if (!speaking && (v_anim % 40) == 0) eye = 4;
    face(eye, lift, mouth, col, open);
    set_text(v_state, listening ? "Đang nghe…" : speaking ? "Đang nói…" : st.awake ? "Đang nghĩ…" : "");
    // while awake the button ends the conversation; otherwise it calls the speaker
    btn_text(v_btn, (st.awake || talking()) ? I_MICOFF "  Tắt nghe" : I_MIC "  Gọi loa");
}

static void update_voice_text(void)
{
    char u[920];
    snprintf(u, sizeof u, "%s", st.user[0] ? st.user : "");
    set_text(v_user, u);
    set_text(v_bot, st.bot);
    lv_obj_update_layout(v_scroll);
    int32_t b = lv_obj_get_scroll_bottom(v_scroll);
    if (b > 0) lv_obj_scroll_by(v_scroll, 0, -b, LV_ANIM_OFF);
}

// ── music page ───────────────────────────────────────────────────────────────────

static void play_cb(lv_event_t *e) { link_cmd(!strcmp(st.ms, "playing") ? "pause" : "resume"); }

/* ── the song's picture: a full-screen overlay fed by video.c ─────────────────────── */

static void close_video(void)
{
    if (!v_layer) return;
    video_stop();
    lv_obj_delete(v_layer);
    v_layer = v_img = v_msg = NULL;
}

static void video_tap_cb(lv_event_t *e) { close_video(); }

static void open_video(void)
{
    if (v_layer || !st.has_m || !st.vid[0] || !st.mu[0]) return;
    int pos = st.pos;
    if (!strcmp(st.ms, "playing")) pos += (int)((NOW_US() - st.got_us) / 1000000);
    snprintf(v_vid, sizeof v_vid, "%s", st.vid);
    v_layer = box(lv_layer_top(), 0, 0, LCD_H_RES, LCD_V_RES);
    lv_obj_set_style_bg_opa(v_layer, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(v_layer, lv_color_hex(0x000000), 0);
    lv_obj_add_flag(v_layer, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(v_layer, video_tap_cb, LV_EVENT_CLICKED, NULL);
    v_img = lv_image_create(v_layer);
    lv_obj_set_pos(v_img, 0, 0);
    lv_obj_remove_flag(v_img, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_flag(v_img, LV_OBJ_FLAG_HIDDEN);
    v_msg = label(v_layer, &font_vn18, 0xCBD5E1, "Đang tải hình…");
    lv_obj_set_width(v_msg, 300);
    lv_label_set_long_mode(v_msg, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(v_msg, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(v_msg, LV_ALIGN_CENTER, 0, 0);
    memset(&v_dsc, 0, sizeof v_dsc);
    v_dsc.header.magic = LV_IMAGE_HEADER_MAGIC;
    v_dsc.header.cf = LV_COLOR_FORMAT_RGB565;
    v_dsc.header.w = 320;
    v_dsc.header.h = 240;
    v_dsc.header.stride = 640;
    v_dsc.data_size = 320 * 240 * 2;
    v_seq = 0;
    video_start(st.mu, st.vid, pos + 1);
}

static void video_btn_cb(lv_event_t *e) { open_video(); }

static void update_video(void)
{
    if (v_btn) {
        bool can = st.has_m && st.vid[0] && st.mu[0];
        if (can) lv_obj_remove_flag(v_btn, LV_OBJ_FLAG_HIDDEN); else lv_obj_add_flag(v_btn, LV_OBJ_FLAG_HIDDEN);
    }
    if (!v_layer) return;
    // another song, nothing playing, or the alarm: the picture is no longer the right one
    if (!st.has_m || strcmp(st.vid, v_vid) || st.al_ring[0]) { close_video(); return; }
    char m[80];
    vid_state_t vs = video_state(m, sizeof m);
    const uint16_t *f = video_frame(&v_seq);
    if (f) {
        v_dsc.data = (const uint8_t *)f;
        lv_image_set_src(v_img, &v_dsc);
        lv_obj_remove_flag(v_img, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(v_msg, LV_OBJ_FLAG_HIDDEN);
        lv_image_cache_drop(&v_dsc);
        lv_obj_invalidate(v_img);
    } else if (vs == VID_ERROR) {
        lv_obj_add_flag(v_img, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(v_msg, LV_OBJ_FLAG_HIDDEN);
        set_text(v_msg, m[0] ? m : "Không có hình");
    }
    lv_display_trigger_activity(NULL);   // keep the screen on while the picture is up
}

static void build_music(lv_obj_t *p)
{
    m_title = label(p, &font_vn24, C_TEXT, "");
    lv_obj_set_pos(m_title, 10, 2);
    lv_obj_set_width(m_title, LCD_H_RES - 70);
    lv_label_set_long_mode(m_title, LV_LABEL_LONG_SCROLL_CIRCULAR);
    v_btn = button(p, LCD_H_RES - 56, 4, 48, 26, "Hình", &font_vn14, false, NULL);
    lv_obj_add_event_cb(v_btn, video_btn_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_add_flag(v_btn, LV_OBJ_FLAG_HIDDEN);
    m_artist = label(p, &font_vn14, C_MUTED, "");
    lv_obj_set_pos(m_artist, 10, 36);
    lv_obj_set_width(m_artist, LCD_H_RES - 20);
    lv_label_set_long_mode(m_artist, LV_LABEL_LONG_DOT);
    const int bw = 7, gap = 2, x0 = (LCD_H_RES - LINK_BANDS * (bw + gap) + gap) / 2;
    for (int i = 0; i < LINK_BANDS; i++) {
        m_bars[i] = box(p, x0 + i * (bw + gap), 132, bw, 2);
        lv_obj_set_style_radius(m_bars[i], 2, 0);
        lv_obj_set_style_bg_opa(m_bars[i], LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(m_bars[i],
            lv_color_hsv_to_rgb((uint16_t)(i * 300 / (LINK_BANDS - 1)), 90, P->light ? 90 : 100), 0);   // rainbow, red → violet
    }
    m_pos = label(p, &font_vn14, C_MUTED, "0:00");
    lv_obj_set_pos(m_pos, 10, 136);
    m_dur = label(p, &font_vn14, C_MUTED, "");
    lv_obj_align(m_dur, LV_ALIGN_TOP_RIGHT, -10, 136);
    m_bar = lv_bar_create(p);
    lv_obj_remove_style_all(m_bar);
    lv_obj_set_pos(m_bar, 58, 145);
    lv_obj_set_size(m_bar, LCD_H_RES - 116, 5);
    lv_obj_set_style_radius(m_bar, 3, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(m_bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(m_bar, lv_color_hex(C_LINE), LV_PART_MAIN);
    lv_obj_set_style_radius(m_bar, 3, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(m_bar, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(m_bar, lv_color_hex(P->a1), LV_PART_INDICATOR);
    lv_obj_set_style_bg_grad_color(m_bar, lv_color_hex(P->a2), LV_PART_INDICATOR);
    lv_obj_set_style_bg_grad_dir(m_bar, LV_GRAD_DIR_HOR, LV_PART_INDICATOR);
    lv_bar_set_range(m_bar, 0, 1000);
    const int y = 160, w = 56, g = 6, x = (LCD_H_RES - 5 * w - 4 * g) / 2;
    button(p, x, y, w, 38, I_VOLDN, &font_vn18, false, "vol-");
    m_play = button(p, x + (w + g), y, w, 38, I_PAUSE, &font_vn18, true, NULL);
    lv_obj_add_event_cb(m_play, play_cb, LV_EVENT_CLICKED, NULL);
    button(p, x + 2 * (w + g), y, w, 38, I_NEXT, &font_vn18, false, "next");
    button(p, x + 3 * (w + g), y, w, 38, I_STOP, &font_vn18, false, "stop");
    button(p, x + 4 * (w + g), y, w, 38, I_VOLUP, &font_vn18, false, "vol+");
}

static void update_music(void)
{
    set_text(m_title, st.ti[0] ? st.ti : "…");
    char a[340];
    const char *state = !strcmp(st.ms, "downloading") ? "Đang tải… · " : !strcmp(st.ms, "paused") ? "Tạm dừng · " : "";
    snprintf(a, sizeof a, I_MUSIC " %s%s%s%s", state, st.src, st.ar[0] ? " · " : "", st.ar);
    set_text(m_artist, a);
    btn_text(m_play, !strcmp(st.ms, "playing") ? I_PAUSE : I_PLAY);
}

static void update_progress(void)
{
    int pos = st.pos;
    if (!strcmp(st.ms, "playing")) pos += (int)((NOW_US() - st.got_us) / 1000000);
    if (st.dur > 0 && pos > st.dur) pos = st.dur;
    char t[16];
    fmt_time(t, sizeof t, pos);
    set_text(m_pos, t);
    if (st.dur > 0) {
        fmt_time(t, sizeof t, st.dur);
        set_text(m_dur, t);
        lv_bar_set_value(m_bar, pos * 1000 / st.dur, LV_ANIM_OFF);
        lv_obj_remove_flag(m_bar, LV_OBJ_FLAG_HIDDEN);
    } else {
        set_text(m_dur, "LIVE");
        lv_obj_add_flag(m_bar, LV_OBJ_FLAG_HIDDEN);
    }
}

static void spectrum_timer(lv_timer_t *t)
{
    if (cur_page != P_MUSIC || !m_bars[0]) return;
    uint8_t in[LINK_BANDS];
    link_spectrum(in);
    bool playing = !strcmp(st.ms, "playing");
    for (int i = 0; i < LINK_BANDS; i++) {
        int v = playing ? in[i] : 0;
        // rise at once, fall slowly
        int lv = m_level[i];
        lv = v > lv ? v : (lv > 12 ? lv - 12 : 0);
        if (lv == m_level[i] && t) continue;
        m_level[i] = lv;
        int h = 2 + lv * 80 / 255;
        lv_obj_set_height(m_bars[i], h);
        lv_obj_set_y(m_bars[i], 132 - h);
    }
}

// ── idle page ────────────────────────────────────────────────────────────────────

static void mute_cb(lv_event_t *e) { link_cmd(st.mute ? "unmute" : "mute"); }

/* ── the flip clock: four cards, each with a split line; a digit that changes folds over ── */

#define FW 52
#define FH 70
#define FHALF (FH / 2)
typedef struct { lv_obj_t *top, *bot, *ft, *fb, *lt, *lb, *lft, *lfb; char cur; } flip_t;
static flip_t fc[4];
static lv_obj_t *fc_dot[2];
static int fc_ly;                    // y of a digit label that centres it on a whole card

static void flip_finish(flip_t *f)
{
    if (!f->ft) return;
    lv_obj_add_flag(f->ft, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(f->fb, LV_OBJ_FLAG_HIDDEN);
    char c[2] = { f->cur ? f->cur : '-', 0 };
    set_text(f->lb, c);
}

static void flip_anim_cb(void *var, int32_t v)
{
    flip_t *f = var;
    if (v < 50) {                    // the old top half falls toward the split
        int h = FHALF * (50 - v) / 50;
        lv_obj_remove_flag(f->ft, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(f->fb, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_y(f->ft, FHALF - h);
        lv_obj_set_height(f->ft, h > 0 ? h : 1);
        lv_obj_set_y(f->lft, fc_ly - (FHALF - h));
    } else {                         // the new bottom half unfolds
        int h = FHALF * (v - 50) / 50;
        lv_obj_add_flag(f->ft, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(f->fb, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_height(f->fb, h > 0 ? h : 1);
    }
}

static void flip_done(lv_anim_t *a) { flip_finish((flip_t *)a->var); }

static void flip_set(flip_t *f, char c)
{
    if (f->cur == c) return;
    lv_anim_delete(f, flip_anim_cb);
    flip_finish(f);
    char o[2] = { f->cur ? f->cur : c, 0 }, n[2] = { c, 0 };
    bool anim = f->cur && f->cur != '-' && c != '-';
    f->cur = c;
    set_text(f->lt, n);
    if (!anim) {
        set_text(f->lb, n);
        return;
    }
    set_text(f->lb, o);
    set_text(f->lft, o);
    set_text(f->lfb, n);
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, f);
    lv_anim_set_exec_cb(&a, flip_anim_cb);
    lv_anim_set_values(&a, 0, 100);
    lv_anim_set_duration(&a, 450);
    lv_anim_set_completed_cb(&a, flip_done);
    lv_anim_start(&a);
}

static lv_obj_t *flip_half(lv_obj_t *card, int y, int h, uint32_t bg, int ly, lv_obj_t **lbl)
{
    lv_obj_t *o = box(card, 0, y, FW, h);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(o, lv_color_hex(bg), 0);
    *lbl = label(o, &font_flip64, C_ORANGE, "-");
    lv_obj_set_width(*lbl, FW);
    lv_obj_set_style_text_align(*lbl, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(*lbl, 0, ly);
    return o;
}

static void build_flip(lv_obj_t *p, int y)
{
    fc_ly = (FH - lv_font_get_line_height(&font_flip64)) / 2;
    const int x[4] = { 39, 97, 171, 229 };
    for (int i = 0; i < 4; i++) {
        lv_anim_delete(&fc[i], flip_anim_cb);
        memset(&fc[i], 0, sizeof fc[i]);
        lv_obj_t *card = box(p, x[i], y, FW, FH);
        lv_obj_set_style_radius(card, 9, 0);
        lv_obj_set_style_clip_corner(card, true, 0);
        lv_obj_set_style_bg_opa(card, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(card, lv_color_hex(C_CARD), 0);
        lv_obj_set_style_border_width(card, 1, 0);
        lv_obj_set_style_border_color(card, lv_color_hex(C_LINE), 0);
        flip_t *f = &fc[i];
        f->top = flip_half(card, 0, FHALF, C_CARD, fc_ly, &f->lt);
        f->bot = flip_half(card, FHALF, FHALF, C_CARD, fc_ly - FHALF, &f->lb);
        f->ft = flip_half(card, 0, FHALF, C_CARD2, fc_ly, &f->lft);
        f->fb = flip_half(card, FHALF, FHALF, C_CARD2, fc_ly - FHALF, &f->lfb);
        lv_obj_add_flag(f->ft, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(f->fb, LV_OBJ_FLAG_HIDDEN);
        lv_obj_t *hinge = box(card, 0, FHALF - 1, FW, 2);
        lv_obj_set_style_bg_opa(hinge, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(hinge, lv_color_hex(C_BG), 0);
        f->cur = 0;
        flip_set(f, '-');
    }
    for (int i = 0; i < 2; i++) {
        fc_dot[i] = box(p, 156, y + FH / 2 - 15 + i * 24, 8, 8);
        lv_obj_set_style_radius(fc_dot[i], 4, 0);
        lv_obj_set_style_bg_opa(fc_dot[i], LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(fc_dot[i], lv_color_hex(C_ORANGE), 0);
    }
}

static void build_idle(lv_obj_t *p)
{
    build_flip(p, 0);
    i_date = label(p, &font_vn18, C_MUTED, "");
    lv_obj_align(i_date, LV_ALIGN_TOP_MID, 0, 72);

    lv_obj_t *w = box(p, 0, 98, LCD_H_RES, 44);
    lv_obj_set_flex_flow(w, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(w, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(w, 10, 0);
    i_wicon = label(w, &font_icon40, C_ORANGE, "");
    i_temp = label(w, &font_vn24, C_TEXT, "");
    lv_obj_t *col = box(w, 0, 0, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(col, LV_FLEX_FLOW_COLUMN);
    i_wdesc = label(col, &font_vn14, C_TEXT, "");
    i_city = label(col, &font_vn14, C_MUTED, "");

    i_info = label(p, &font_vn14, C_MUTED, "");
    lv_obj_set_width(i_info, LCD_H_RES - 20);
    lv_obj_set_style_text_align(i_info, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_long_mode(i_info, LV_LABEL_LONG_DOT);
    lv_obj_align(i_info, LV_ALIGN_TOP_MID, 0, 144);

    // the panel's two floating buttons: Tắt nghe / Bật nghe on the left, Gọi loa on the right
    i_mute = button(p, 48, 176, 108, 32, I_MICOFF " Tắt nghe", &font_vn14, false, NULL);
    lv_obj_add_event_cb(i_mute, mute_cb, LV_EVENT_CLICKED, NULL);
    button(p, 164, 176, 108, 32, I_MIC " Gọi loa", &font_vn14, true, "wake");
}

static void update_idle(void)
{
    char t[160];
    if (clock_ok()) {
        time_t now = time(NULL);
        struct tm tm;
        localtime_r(&now, &tm);
        snprintf(t, sizeof t, "%02d%02d", tm.tm_hour, tm.tm_min);
        for (int i = 0; i < 4; i++) flip_set(&fc[i], t[i]);
        for (int i = 0; i < 2; i++) lv_obj_set_style_bg_opa(fc_dot[i], (tm.tm_sec & 1) ? LV_OPA_30 : LV_OPA_COVER, 0);
        snprintf(t, sizeof t, "%s, %d tháng %d, %d", WEEKDAY[tm.tm_wday], tm.tm_mday, tm.tm_mon + 1, tm.tm_year + 1900);
        set_text(i_date, t);
    } else {
        for (int i = 0; i < 4; i++) flip_set(&fc[i], '-');
        set_text(i_date, "");
    }
    if (st.has_wx) {
        set_text(i_wicon, weather_icon(st.wx_c, st.wx_day));
        lv_obj_set_style_text_color(i_wicon, lv_color_hex(st.wx_c <= 1 ? (st.wx_day ? C_ORANGE : (P->light ? 0x6B7FB0 : 0xFDE68A)) : (P->light ? 0x6B7FB0 : 0xCBD5E1)), 0);
        snprintf(t, sizeof t, "%d°C", st.wx_t);
        set_text(i_temp, t);
        set_text(i_wdesc, st.wx_d);
        set_text(i_city, st.wx_city);
    } else {
        set_text(i_wicon, "");
        set_text(i_temp, "");
        set_text(i_wdesc, "Chưa có thời tiết");
        set_text(i_city, "(đặt vị trí trong trang loa)");
    }
    if (st.al_next[0]) snprintf(t, sizeof t, I_BELL "  %s", st.al_next);
    else snprintf(t, sizeof t, I_GLOBE "  %s · DB-Robot %s", st.ip, st.ver);
    set_text(i_info, t);
    btn_text(i_mute, st.mute ? I_MIC " Bật nghe" : I_MICOFF " Tắt nghe");
}

// ── alarm overlay ────────────────────────────────────────────────────────────────

static void build_alarm(void)
{
    a_layer = box(lv_layer_top(), 0, 0, LCD_H_RES, LCD_V_RES);
    lv_obj_set_style_bg_opa(a_layer, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(a_layer, lv_color_hex(0x7C2D12), 0);
    lv_obj_add_flag(a_layer, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_t *ic = label(a_layer, &font_icon40, 0xFDE68A, I_BELL);
    lv_obj_align(ic, LV_ALIGN_TOP_MID, 0, 18);
    lv_obj_t *h = label(a_layer, &font_vn24, 0xFFFFFF, "Báo thức");
    lv_obj_align(h, LV_ALIGN_TOP_MID, 0, 68);
    a_text = label(a_layer, &font_vn18, 0xFED7AA, "");
    lv_obj_set_width(a_text, 300);
    lv_label_set_long_mode(a_text, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(a_text, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(a_text, LV_ALIGN_TOP_MID, 0, 104);
    lv_obj_t *b = box(a_layer, 60, 170, 200, 52);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_radius(b, 26, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(b, lv_color_hex(0xEA580C), 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_70, LV_STATE_PRESSED);
    lv_obj_t *l = label(b, &font_vn18, 0xFFFFFF, "TẮT BÁO THỨC");
    lv_obj_center(l);
    lv_obj_add_event_cb(b, cmd_cb, LV_EVENT_CLICKED, "alarm_stop");
    lv_obj_add_flag(a_layer, LV_OBJ_FLAG_HIDDEN);
}

static void update_alarm(void)
{
    bool ring = st.phase == LINK_CONNECTED && st.al_ring[0];
    if (ring) {
        set_text(a_text, st.al_ring);
        lv_obj_remove_flag(a_layer, LV_OBJ_FLAG_HIDDEN);
        static int k;
        k++;
        lv_obj_set_style_bg_color(a_layer, lv_color_hex(k % 10 < 5 ? 0x7C2D12 : 0x9A3412), 0);
    } else {
        lv_obj_add_flag(a_layer, LV_OBJ_FLAG_HIDDEN);
    }
}

// ── apps: the launcher and the settings sheets ───────────────────────────────────

typedef struct { const char *icon; const char *label; uint32_t color; } app_t;

static const app_t APPS[APP_COUNT] = {
    [APP_FIND]     = { I_DISH,    "Tìm loa",   0x8B5CF6 },
    [APP_WIFI]     = { I_WIFI,    "Wifi",      0x0EA5E9 },
    [APP_ALARM]    = { I_BELL,    "Báo thức",  0xF59E0B },
    [APP_RADIO]    = { I_BCAST,   "Radio",     0xEA580C },
    [APP_MUSIC]    = { I_MUSIC,   "Nhạc",      0xDB2777 },
    [APP_PODCAST]  = { I_PODCAST, "Podcast",   0x7C3AED },
    [APP_SETTINGS] = { I_COG,     "Cài đặt",   0x0891B2 },
    [APP_INFO]     = { I_INFO,    "Thông tin", 0x2563EB },
};

static void app_cb(lv_event_t *e)
{
    int app = (int)(intptr_t)lv_event_get_user_data(e);
    switch (app) {
    case APP_FIND:
        g_cfg.spk_id[0] = 0;
        link_research();
        go_main();
        break;
    default:
        open_sheet(app);
    }
}

static void build_apps(lv_obj_t *p)
{
    const int cw = LCD_H_RES / 4, ch = 100;
    for (int i = 0; i < APP_COUNT; i++) {
        int x = (i % 4) * cw, y = 4 + (i / 4) * ch;
        lv_obj_t *cell = box(p, x + 3, y, cw - 6, ch - 4);
        lv_obj_add_flag(cell, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_set_style_radius(cell, 14, 0);
        lv_obj_set_style_bg_color(cell, lv_color_hex(C_CARD2), 0);
        lv_obj_set_style_bg_opa(cell, LV_OPA_COVER, LV_STATE_PRESSED);
        lv_obj_t *sq = box(cell, (cw - 6 - 54) / 2, 8, 54, 54);
        lv_obj_remove_flag(sq, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_set_style_radius(sq, 15, 0);
        lv_obj_set_style_bg_opa(sq, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(sq, lv_color_hex(APPS[i].color), 0);
        lv_obj_set_style_bg_grad_color(sq, lv_color_mix(lv_color_white(), lv_color_hex(APPS[i].color), 70), 0);
        lv_obj_set_style_bg_grad_dir(sq, LV_GRAD_DIR_VER, 0);
        const char *ic = APPS[i].icon;
        app_icon_lbl[i] = label(sq, &font_icon30, 0xFFFFFF, ic);
        lv_obj_center(app_icon_lbl[i]);
        lv_obj_t *l = label(cell, &font_vn14, C_TEXT, APPS[i].label);
        lv_obj_set_width(l, cw - 6);
        lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_set_pos(l, 0, 68);
        lv_obj_add_event_cb(cell, app_cb, LV_EVENT_CLICKED, (void *)(intptr_t)i);
    }
}

// the sheet: a back arrow and a title above the body

static void back_cb(lv_event_t *e) { sheet_back(); }

static void build_sheet(lv_obj_t *scr)
{
    sheet = box(scr, 0, TOP_H, LCD_H_RES, PAGE_H);
    lv_obj_set_style_bg_opa(sheet, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(sheet, lv_color_hex(C_BG), 0);
    lv_obj_add_flag(sheet, LV_OBJ_FLAG_CLICKABLE);       // nothing underneath hears the touches
    lv_obj_t *back = box(sheet, 4, 2, 52, 34);
    lv_obj_add_flag(back, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_radius(back, 10, 0);
    lv_obj_set_style_bg_color(back, lv_color_hex(C_CARD2), 0);
    lv_obj_set_style_bg_opa(back, LV_OPA_COVER, LV_STATE_PRESSED);
    lv_obj_t *ic = label(back, &font_vn18, C_ACCENT, I_BACK);
    lv_obj_center(ic);
    lv_obj_add_event_cb(back, back_cb, LV_EVENT_CLICKED, NULL);
    sheet_title = label(sheet, &font_vn18, C_TEXT, "");
    lv_obj_set_pos(sheet_title, 66, 8);
    sheet_body = box(sheet, 0, 40, LCD_H_RES, PAGE_H - 40);
    lv_obj_add_flag(sheet, LV_OBJ_FLAG_HIDDEN);
    sheet_app = -1;
}

static void close_sheet(void)
{
    if (!sheet) return;
    lv_obj_add_flag(sheet, LV_OBJ_FLAG_HIDDEN);
    sheet_app = -1;
    vol_num = vol_bar = bri_num = NULL;
    menu_reset();
}

static lv_obj_t *note(lv_obj_t *parent, int x, int y, int w, const char *text, uint32_t color)
{
    lv_obj_t *l = label(parent, &font_vn14, color, text);
    lv_obj_set_pos(l, x, y);
    lv_obj_set_width(l, w);
    lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
    return l;
}

static void style_slider(lv_obj_t *s)
{
    lv_obj_set_style_bg_color(s, lv_color_hex(C_LINE), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s, lv_color_hex(P->a1), LV_PART_INDICATOR);
    lv_obj_set_style_bg_grad_color(s, lv_color_hex(P->a2), LV_PART_INDICATOR);
    lv_obj_set_style_bg_grad_dir(s, LV_GRAD_DIR_HOR, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(s, lv_color_hex(P->light ? 0xFFFFFF : 0xE8ECF6), LV_PART_KNOB);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, LV_PART_KNOB);
    lv_obj_set_style_border_width(s, 3, LV_PART_KNOB);
    lv_obj_set_style_border_color(s, lv_color_hex(C_ACCENT), LV_PART_KNOB);
    lv_obj_set_style_shadow_width(s, 0, LV_PART_KNOB);
    lv_obj_set_style_pad_all(s, 6, LV_PART_KNOB);
}

static void style_switch(lv_obj_t *s)
{
    lv_obj_set_style_bg_color(s, lv_color_hex(C_LINE), LV_PART_MAIN);
    lv_obj_set_style_bg_color(s, lv_color_hex(C_ACCENT), LV_PART_INDICATOR | LV_STATE_CHECKED);
    lv_obj_set_style_bg_color(s, lv_color_hex(0xFFFFFF), LV_PART_KNOB);
}

/* brightness */
static void bri_cb(lv_event_t *e)
{
    lv_obj_t *s = lv_event_get_target(e);
    int v = lv_slider_get_value(s);
    g_cfg.bright = (uint8_t)v;
    display_backlight(v);
    char t[16];
    snprintf(t, sizeof t, "%d%%", v);
    if (bri_num) lv_label_set_text(bri_num, t);
}

static void bri_done_cb(lv_event_t *e) { cfg_save(); }


static void update_volume_sheet(void)
{
    if (!vol_num) return;
    char t[16];
    if (st.vol >= 0) snprintf(t, sizeof t, "%d%%", st.vol); else snprintf(t, sizeof t, "--");
    set_text(vol_num, t);
    lv_bar_set_value(vol_bar, st.vol < 0 ? 0 : st.vol, LV_ANIM_OFF);
}

/* screen */
static lv_obj_t *row_switch(lv_obj_t *parent, int y, const char *text, bool on, lv_event_cb_t cb)
{
    lv_obj_t *r = box(parent, 8, y, LCD_H_RES - 16, 38);
    lv_obj_set_style_radius(r, 10, 0);
    lv_obj_set_style_bg_opa(r, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(r, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_border_width(r, 1, 0);
    lv_obj_set_style_border_color(r, lv_color_hex(C_LINE), 0);
    lv_obj_t *l = label(r, &font_vn14, C_TEXT, text);
    lv_obj_align(l, LV_ALIGN_LEFT_MID, 12, 0);
    lv_obj_t *s = lv_switch_create(r);
    lv_obj_set_size(s, 46, 24);
    lv_obj_align(s, LV_ALIGN_RIGHT_MID, -10, 0);
    style_switch(s);
    if (on) lv_obj_add_state(s, LV_STATE_CHECKED);
    lv_obj_add_event_cb(s, cb, LV_EVENT_VALUE_CHANGED, NULL);
    return s;
}

static void sw_theme_cb(lv_event_t *e)
{
    g_cfg.light = lv_obj_has_state(lv_event_get_target(e), LV_STATE_CHECKED);
    cfg_save();
    request_rebuild();
}

static void sw_flip_cb(lv_event_t *e)
{
    g_cfg.rot180 = lv_obj_has_state(lv_event_get_target(e), LV_STATE_CHECKED);
    cfg_save();
    display_set_flip(g_cfg.rot180);
}

static void sw_invert_cb(lv_event_t *e)
{
    g_cfg.invert = lv_obj_has_state(lv_event_get_target(e), LV_STATE_CHECKED);
    cfg_save();
    display_set_invert(g_cfg.invert);
}

static void sw_night_cb(lv_event_t *e)
{
    g_cfg.night_dim = lv_obj_has_state(lv_event_get_target(e), LV_STATE_CHECKED);
    cfg_save();
}

/* "Màn hình": brightness, theme, rotation, colour inversion, night dimming and touch calibration */
static void calib_cb(lv_event_t *e);
static void sheet_settings(lv_obj_t *b)
{
    lv_obj_add_flag(b, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scroll_dir(b, LV_DIR_VER);
    // volume of the speaker
    lv_obj_t *l1 = label(b, &font_vn14, C_TEXT, "Âm lượng loa");
    lv_obj_set_pos(l1, 14, 8);
    vol_num = label(b, &font_vn18, C_TEXT, "--");
    lv_obj_align(vol_num, LV_ALIGN_TOP_RIGHT, -14, 4);
    button(b, 8, 30, 52, 34, I_MINUS, &font_vn18, false, "vol-");
    button(b, LCD_H_RES - 60, 30, 52, 34, I_PLUS, &font_vn18, true, "vol+");
    vol_bar = lv_bar_create(b);
    lv_obj_remove_style_all(vol_bar);
    lv_obj_set_size(vol_bar, LCD_H_RES - 140, 8);
    lv_obj_set_pos(vol_bar, 70, 43);
    lv_obj_set_style_radius(vol_bar, 4, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(vol_bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(vol_bar, lv_color_hex(C_LINE), LV_PART_MAIN);
    lv_obj_set_style_radius(vol_bar, 4, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(vol_bar, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(vol_bar, lv_color_hex(P->a1), LV_PART_INDICATOR);
    lv_obj_set_style_bg_grad_color(vol_bar, lv_color_hex(P->a2), LV_PART_INDICATOR);
    lv_obj_set_style_bg_grad_dir(vol_bar, LV_GRAD_DIR_HOR, LV_PART_INDICATOR);
    lv_bar_set_range(vol_bar, 0, 100);
    update_volume_sheet();
    // brightness
    const int y0 = 72;
    lv_obj_t *l0 = label(b, &font_vn14, C_TEXT, "Độ sáng màn hình");
    lv_obj_set_pos(l0, 14, y0 + 6);
    char t[16];
    snprintf(t, sizeof t, "%d%%", g_cfg.bright);
    bri_num = label(b, &font_vn18, C_TEXT, t);
    lv_obj_align(bri_num, LV_ALIGN_TOP_RIGHT, -14, y0 + 2);
    lv_obj_t *s = lv_slider_create(b);
    lv_obj_set_size(s, 284, 14);
    lv_obj_set_pos(s, 18, y0 + 32);
    lv_slider_set_range(s, 5, 100);
    lv_slider_set_value(s, g_cfg.bright, LV_ANIM_OFF);
    style_slider(s);
    lv_obj_add_event_cb(s, bri_cb, LV_EVENT_VALUE_CHANGED, NULL);
    lv_obj_add_event_cb(s, bri_done_cb, LV_EVENT_RELEASED, NULL);
    row_switch(b, y0 + 60, "Giao diện sáng (tắt = tối)", g_cfg.light, sw_theme_cb);
    row_switch(b, y0 + 102, "Xoay ngược 180°", g_cfg.rot180, sw_flip_cb);
    row_switch(b, y0 + 144, "Đảo màu (nếu màu bị âm bản)", g_cfg.invert, sw_invert_cb);
    row_switch(b, y0 + 186, "Giảm sáng ban đêm 22:00–6:00", g_cfg.night_dim, sw_night_cb);
    lv_obj_t *g = button(b, 8, y0 + 232, LCD_H_RES - 16, 38, I_HAND "  Hiệu chỉnh cảm ứng (khởi động lại)", &font_vn14, false, NULL);
    lv_obj_add_event_cb(g, calib_cb, LV_EVENT_CLICKED, NULL);
}

/* info */
static void restart_cb(lv_event_t *e) { esp_restart(); }

static void sheet_info(lv_obj_t *b)
{
    char ip[16], t[400];
    net_ip(ip, sizeof ip);
    int n = 0;
    if (st.phase == LINK_CONNECTED)
        n += snprintf(t + n, sizeof t - n, "Loa: %s · %s\nDB-Robot %s\n", st.name, st.ip, st.ver);
    else
        n += snprintf(t + n, sizeof t - n, "Loa: chưa nối\n");
    if (ip[0]) {
        int rssi = net_rssi();
        n += snprintf(t + n, sizeof t - n, "Wi-Fi: %s (%d dBm)\n", g_cfg.ssid, rssi);
        n += snprintf(t + n, sizeof t - n, "Màn hình: %s · v%s\nCài đặt: http://%s", ip, FW_VERSION, ip);
    } else {
        n += snprintf(t + n, sizeof t - n, "Wi-Fi: chưa vào mạng\nMàn hình: v%s", FW_VERSION);
    }
    note(b, 14, 0, 292, t, C_TEXT);
    lv_obj_t *r = button(b, 60, 118, 200, 38, I_POWER "  Khởi động lại", &font_vn14, false, NULL);
    lv_obj_add_event_cb(r, restart_cb, LV_EVENT_CLICKED, NULL);
}

/* touch calibration: runs at start-up, so ask for a restart */
static void calib_cb(lv_event_t *e)
{
    g_cfg.want_cal = 1;
    cfg_save();
    esp_restart();
}


// ── radio, podcasts, music, alarms: the speaker's own lists, asked over the link ─────

static int list_mode;
static lv_obj_t *lst, *lst_note, *mu_ta;
static link_list_t lst_data, rx_list;
static uint32_t rx_seq, res_seq, al_seq;
static char want_kind[10], want_id[40];
static bool lst_wait, play_wait, al_edit, al_have;
static int64_t lst_t0;
static link_alarms_t al_data;

static lv_obj_t *wf_ta;
static bool wf_pw, wf_scanning;

static void menu_reset(void)
{
    lst = lst_note = mu_ta = wf_ta = NULL;
    lst_wait = play_wait = al_edit = wf_pw = wf_scanning = false;
}

static void lst_say(const char *text);

/* Radio, podcasts, music and alarms need the speaker app 1.5.18 or newer (older ones ignore the requests). */
static bool speaker_old(void)
{
    int a = 0, b = 0, c = 0;
    if (sscanf(st.ver, "%d.%d.%d", &a, &b, &c) < 2) return false;     // unknown: just try
    return a * 10000 + b * 100 + c < 10518;
}

static void say_old(void)
{
    char t[200];
    snprintf(t, sizeof t, "Loa đang chạy DB-Robot %s. Cần cập nhật loa lên 1.5.18 trở lên để dùng mục này.", st.ver);
    lst_say(t);
}

static lv_obj_t *scroll_box(lv_obj_t *b)
{
    lv_obj_t *o = box(b, 0, 0, LCD_H_RES, PAGE_H - 40);
    lv_obj_add_flag(o, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scroll_dir(o, LV_DIR_VER);
    return o;
}

static lv_obj_t *card_row(lv_obj_t *parent, int y, int h)
{
    lv_obj_t *r = box(parent, 8, y, LCD_H_RES - 16, h);
    lv_obj_add_flag(r, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_radius(r, 10, 0);
    lv_obj_set_style_bg_opa(r, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(r, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_color(r, lv_color_hex(C_CARD2), LV_STATE_PRESSED);
    lv_obj_set_style_border_width(r, 1, 0);
    lv_obj_set_style_border_color(r, lv_color_hex(C_LINE), 0);
    return r;
}

static void lst_say(const char *text)
{
    if (!lst) return;
    lv_obj_clean(lst);
    lst_note = note(lst, 14, 10, 292, text, C_MUTED);
}

static void list_pick_cb(lv_event_t *e)
{
    int i = (int)(intptr_t)lv_event_get_user_data(e);
    if (i < 0 || i >= lst_data.count) return;
    if (list_mode == LM_RADIO) {
        link_play_radio(lst_data.it[i].id);
        play_wait = true;
        toast("Đang mở radio…", true);
    } else if (list_mode == LM_PODCAST) {
        list_mode = LM_EPISODES;
        lv_label_set_text(sheet_title, lst_data.it[i].n);
        snprintf(want_id, sizeof want_id, "%s", lst_data.it[i].id);
        snprintf(want_kind, sizeof want_kind, "episodes");
        lst_say("Đang tải các tập…");
        lst_wait = true;
        lst_t0 = NOW_US();
        link_request("episodes", want_id);
    } else {
        link_play_episode(atoi(lst_data.it[i].id));
        play_wait = true;
        toast("Đang mở tập podcast…", true);
    }
}

static void lst_fill(void)
{
    lv_obj_clean(lst);
    lst_note = NULL;
    if (lst_data.count == 0) { lst_say("Không có mục nào."); return; }
    for (int i = 0; i < lst_data.count; i++) {
        lv_obj_t *r = card_row(lst, 8, 0);
        lv_obj_set_y(r, 2 + i * 48);
        lv_obj_set_height(r, 44);
        lv_obj_t *l = label(r, &font_vn14, C_TEXT, lst_data.it[i].n);
        lv_obj_set_width(l, LCD_H_RES - 16 - 36);
        lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
        lv_obj_align(l, LV_ALIGN_LEFT_MID, 12, 0);
        lv_obj_t *ch = label(r, &font_vn14, C_ACCENT, list_mode == LM_PODCAST ? ">" : I_PLAY);
        lv_obj_align(ch, LV_ALIGN_RIGHT_MID, -10, 0);
        lv_obj_add_event_cb(r, list_pick_cb, LV_EVENT_CLICKED, (void *)(intptr_t)i);
    }
    lv_obj_t *pad = box(lst, 0, 2 + lst_data.count * 48, 1, 6);
    (void)pad;
}

static void list_load(int mode, const char *id)
{
    list_mode = mode;
    snprintf(want_kind, sizeof want_kind, "%s", mode == LM_RADIO ? "radio" : mode == LM_PODCAST ? "podcast" : "episodes");
    snprintf(want_id, sizeof want_id, "%s", id ? id : "");
    if (speaker_old()) { say_old(); lst_wait = false; return; }
    lst_say("Đang tải…");
    lst_wait = true;
    lst_t0 = NOW_US();
    link_request(want_kind, id);
}

static void sheet_list(lv_obj_t *b, int mode)
{
    lst = scroll_box(b);
    list_load(mode, NULL);
}

// alarms

static const char *DAY[7] = { "T2", "T3", "T4", "T5", "T6", "T7", "CN" };

static void days_text(uint8_t d, char *out, size_t n)
{
    if (d == 0) { snprintf(out, n, "Một lần"); return; }
    if (d == 0x7F) { snprintf(out, n, "Hằng ngày"); return; }
    if (d == 0x1F) { snprintf(out, n, "T2–T6"); return; }
    size_t k = 0;
    out[0] = 0;
    for (int i = 0; i < 7; i++)
        if (d & (1 << i)) k += snprintf(out + k, n - k, "%s%s", k ? " " : "", DAY[i]);
}

static void alarm_editor(int idx);

static void al_sw_cb(lv_event_t *e)
{
    int i = (int)(intptr_t)lv_event_get_user_data(e);
    if (i < 0 || i >= al_data.count) return;
    bool on = lv_obj_has_state(lv_event_get_target(e), LV_STATE_CHECKED);
    al_data.a[i].on = on;
    link_alarm_toggle(al_data.a[i].id, on);
}

static void al_row_cb(lv_event_t *e) { alarm_editor((int)(intptr_t)lv_event_get_user_data(e)); }

static void alarm_fill(void)
{
    if (!lst) return;
    lv_obj_clean(lst);
    lst_note = NULL;
    lv_obj_t *add = button(lst, 8, 2, LCD_H_RES - 16, 38, I_PLUS "  Thêm báo thức", &font_vn14, true, NULL);
    lv_obj_add_event_cb(add, al_row_cb, LV_EVENT_CLICKED, (void *)(intptr_t)-1);
    int y = 46;
    if (al_data.count == 0) note(lst, 14, y + 4, 292, "Chưa có báo thức nào.", C_MUTED);
    for (int i = 0; i < al_data.count; i++, y += 56) {
        const li_alarm_t *a = &al_data.a[i];
        lv_obj_t *r = card_row(lst, y, 52);
        lv_obj_t *tm = label(r, &font_vn24, a->on ? C_TEXT : C_MUTED, a->time);
        lv_obj_align(tm, LV_ALIGN_LEFT_MID, 12, 0);
        char d[40], line[96];
        days_text(a->days, d, sizeof d);
        snprintf(line, sizeof line, "%s%s%s", d, a->label[0] ? " · " : "", a->label);
        lv_obj_t *ds = label(r, &font_vn14, C_MUTED, line);
        lv_obj_set_width(ds, 140);
        lv_label_set_long_mode(ds, LV_LABEL_LONG_DOT);
        lv_obj_align(ds, LV_ALIGN_LEFT_MID, 92, 0);
        lv_obj_t *sw = lv_switch_create(r);
        lv_obj_set_size(sw, 46, 24);
        lv_obj_align(sw, LV_ALIGN_RIGHT_MID, -10, 0);
        style_switch(sw);
        if (a->on) lv_obj_add_state(sw, LV_STATE_CHECKED);
        lv_obj_add_event_cb(sw, al_sw_cb, LV_EVENT_VALUE_CHANGED, (void *)(intptr_t)i);
        lv_obj_add_event_cb(r, al_row_cb, LV_EVENT_CLICKED, (void *)(intptr_t)i);
    }
    box(lst, 0, y, 1, 6);
}

static void sheet_alarm(lv_obj_t *b)
{
    lst = scroll_box(b);
    al_edit = false;
    if (speaker_old()) { say_old(); return; }
    if (al_have) alarm_fill(); else lst_say("Đang tải…");
    link_request("alarms", NULL);
    lst_wait = !al_have;
    lst_t0 = NOW_US();
}

static lv_obj_t *ed_h, *ed_m, *ed_day[7];
static char ed_id[20], ed_label[44];

static void ed_save_cb(lv_event_t *e)
{
    char tm[8];
    snprintf(tm, sizeof tm, "%02d:%02d", (int)lv_roller_get_selected(ed_h), (int)lv_roller_get_selected(ed_m));
    uint8_t days = 0;
    for (int i = 0; i < 7; i++) if (lv_obj_has_state(ed_day[i], LV_STATE_CHECKED)) days |= 1 << i;
    link_alarm_save(ed_id, tm, days, ed_label);
    al_edit = false;
    alarm_fill();
}

static void ed_del_cb(lv_event_t *e)
{
    if (ed_id[0]) link_alarm_del(ed_id);
    al_edit = false;
    alarm_fill();
}

static lv_obj_t *roller(lv_obj_t *b, int x, int n, int sel)
{
    static char opts[200];
    size_t k = 0;
    for (int i = 0; i < n; i++) k += snprintf(opts + k, sizeof opts - k, "%s%02d", i ? "\n" : "", i);
    lv_obj_t *r = lv_roller_create(b);
    lv_roller_set_options(r, opts, LV_ROLLER_MODE_NORMAL);
    lv_roller_set_visible_row_count(r, 3);
    lv_roller_set_selected(r, sel, LV_ANIM_OFF);
    lv_obj_set_pos(r, x, 2);
    lv_obj_set_width(r, 84);
    lv_obj_set_style_text_font(r, &font_vn18, 0);
    lv_obj_set_style_text_color(r, lv_color_hex(C_MUTED), 0);
    lv_obj_set_style_bg_color(r, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_opa(r, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(r, 1, 0);
    lv_obj_set_style_border_color(r, lv_color_hex(C_LINE), 0);
    lv_obj_set_style_radius(r, 12, 0);
    lv_obj_set_style_text_color(r, lv_color_hex(P->on_a), LV_PART_SELECTED);
    lv_obj_set_style_bg_color(r, lv_color_hex(P->a1), LV_PART_SELECTED);
    lv_obj_set_style_bg_opa(r, LV_OPA_COVER, LV_PART_SELECTED);
    return r;
}

static void alarm_editor(int idx)
{
    if (!lst) return;
    al_edit = true;
    lv_obj_t *b = lv_obj_get_parent(lst);
    lv_obj_delete(lst);
    lst = NULL;
    lv_obj_t *v = box(b, 0, 0, LCD_H_RES, PAGE_H - 40);   // not "lst": the list is gone while editing
    int h = 7, m = 0;
    uint8_t days = 0;
    ed_id[0] = ed_label[0] = 0;
    if (idx >= 0 && idx < al_data.count) {
        const li_alarm_t *a = &al_data.a[idx];
        sscanf(a->time, "%d:%d", &h, &m);
        days = a->days;
        snprintf(ed_id, sizeof ed_id, "%s", a->id);
        snprintf(ed_label, sizeof ed_label, "%s", a->label);
    }
    if (h < 0 || h > 23) h = 7;
    if (m < 0 || m > 59) m = 0;
    ed_h = roller(v, 8, 24, h);
    ed_m = roller(v, 108, 60, m);
    lv_obj_t *c = label(v, &font_vn24, C_TEXT, ":");
    lv_obj_set_pos(c, 95, 34);
    for (int i = 0; i < 7; i++) {
        lv_obj_t *d = box(v, 8 + i * 44, 108, 40, 32);
        lv_obj_add_flag(d, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_CHECKABLE);
        lv_obj_set_style_radius(d, 10, 0);
        lv_obj_set_style_bg_opa(d, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(d, lv_color_hex(C_CARD), 0);
        lv_obj_set_style_border_width(d, 1, 0);
        lv_obj_set_style_border_color(d, lv_color_hex(C_LINE), 0);
        lv_obj_set_style_bg_color(d, lv_color_hex(P->a1), LV_STATE_CHECKED);
        lv_obj_set_style_text_color(d, lv_color_hex(C_TEXT), 0);
        lv_obj_set_style_text_color(d, lv_color_hex(P->on_a), LV_STATE_CHECKED);
        lv_obj_t *l = lv_label_create(d);
        lv_obj_set_style_text_font(l, &font_vn14, 0);
        lv_label_set_text(l, DAY[i]);
        lv_obj_center(l);
        if (days & (1 << i)) lv_obj_add_state(d, LV_STATE_CHECKED);
        ed_day[i] = d;
    }
    note(v, 10, 146, 300, "Không chọn thứ nào = chỉ kêu một lần.", C_MUTED);
    lv_obj_t *sv = button(v, 208, 2, 104, 46, I_CHECK "  Lưu", &font_vn18, true, NULL);
    lv_obj_add_event_cb(sv, ed_save_cb, LV_EVENT_CLICKED, NULL);
    if (idx >= 0) {
        lv_obj_t *dl = button(v, 208, 54, 104, 46, "Xoá", &font_vn18, false, NULL);
        lv_obj_add_event_cb(dl, ed_del_cb, LV_EVENT_CLICKED, NULL);
    } else {
        lv_obj_t *cn = button(v, 208, 54, 104, 46, "Huỷ", &font_vn18, false, NULL);
        lv_obj_add_event_cb(cn, ed_del_cb, LV_EVENT_CLICKED, NULL);   // new alarm has no id: just closes
    }
    (void)v;
}

// music: type a name, the speaker finds and plays it

static void music_go(void)
{
    if (!mu_ta) return;
    const char *q = lv_textarea_get_text(mu_ta);
    if (!q[0]) { toast("Gõ tên bài hát hoặc ca sĩ", false); return; }
    if (speaker_old()) { toast("Loa cần DB-Robot 1.5.18 trở lên", false); return; }
    link_play_music(q);
    play_wait = true;
    toast("Đang tìm bài…", true);
}

static void music_go_cb(lv_event_t *e) { music_go(); }

static void sheet_music(lv_obj_t *b)
{
    mu_ta = lv_textarea_create(b);
    lv_obj_set_pos(mu_ta, 8, 4);
    lv_obj_set_size(mu_ta, 232, 38);
    lv_textarea_set_one_line(mu_ta, true);
    lv_textarea_set_max_length(mu_ta, 60);
    lv_textarea_set_placeholder_text(mu_ta, "Tên bài hát (gõ không dấu)");
    lv_obj_set_style_text_font(mu_ta, &font_vn14, 0);
    lv_obj_set_style_text_color(mu_ta, lv_color_hex(C_TEXT), 0);
    lv_obj_set_style_text_color(mu_ta, lv_color_hex(C_MUTED), LV_PART_TEXTAREA_PLACEHOLDER);
    lv_obj_set_style_bg_color(mu_ta, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_opa(mu_ta, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(mu_ta, 1, 0);
    lv_obj_set_style_border_color(mu_ta, lv_color_hex(C_LINE), 0);
    lv_obj_set_style_border_color(mu_ta, lv_color_hex(C_ACCENT), LV_STATE_FOCUSED);
    lv_obj_set_style_radius(mu_ta, 10, 0);
    lv_obj_t *go = button(b, 246, 4, 66, 38, I_PLAY "  Phát", &font_vn14, true, NULL);
    lv_obj_add_event_cb(go, music_go_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *kb = lv_keyboard_create(b);
    lv_obj_set_size(kb, LCD_H_RES, PAGE_H - 40 - 46);
    lv_obj_align(kb, LV_ALIGN_TOP_LEFT, 0, 46);
    lv_keyboard_set_textarea(kb, mu_ta);
    lv_obj_set_style_bg_color(kb, lv_color_hex(C_BG), 0);
    lv_obj_set_style_bg_opa(kb, LV_OPA_COVER, 0);
    lv_obj_set_style_pad_all(kb, 2, 0);
    lv_obj_set_style_pad_row(kb, 3, 0);
    lv_obj_set_style_pad_column(kb, 3, 0);
    lv_obj_set_style_bg_color(kb, lv_color_hex(C_CARD2), LV_PART_ITEMS);
    lv_obj_set_style_bg_opa(kb, LV_OPA_COVER, LV_PART_ITEMS);
    lv_obj_set_style_text_color(kb, lv_color_hex(C_TEXT), LV_PART_ITEMS);
    lv_obj_set_style_border_width(kb, 0, LV_PART_ITEMS);
    lv_obj_set_style_shadow_width(kb, 0, LV_PART_ITEMS);
    lv_obj_set_style_radius(kb, 6, LV_PART_ITEMS);
    lv_obj_set_style_bg_color(kb, lv_color_hex(C_LINE), LV_PART_ITEMS | LV_STATE_CHECKED);   // the control keys
    lv_obj_add_event_cb(kb, music_go_cb, LV_EVENT_READY, NULL);
}


// ── Wi-Fi: join another network ──────────────────────────────────────────────────

static wifi_ap_t wf_ap[16];
static int wf_n;

static void wf_restart_cb(lv_timer_t *t) { esp_restart(); }

static void wf_connect(const char *ssid, const char *pass)
{
    snprintf(g_cfg.ssid, sizeof g_cfg.ssid, "%s", ssid);
    snprintf(g_cfg.pass, sizeof g_cfg.pass, "%s", pass);
    cfg_save();
    toast("Đang kết nối… màn hình sẽ khởi động lại", true);
    lv_timer_t *t = lv_timer_create(wf_restart_cb, 1500, NULL);
    lv_timer_set_repeat_count(t, 1);
}

static void wf_fill(void);
static void wf_password(const char *ssid);

static void wf_rescan_cb(lv_event_t *e)
{
    if (!lst || wf_scanning) return;
    lst_say("Đang quét Wi-Fi…");
    wf_scanning = true;
    net_scan_start();
}

static void wf_pick_cb(lv_event_t *e)
{
    int i = (int)(intptr_t)lv_event_get_user_data(e);
    if (i < 0 || i >= wf_n) return;
    if (wf_ap[i].lock) wf_password(wf_ap[i].ssid);
    else wf_connect(wf_ap[i].ssid, "");
}

static void wf_fill(void)
{
    if (!lst) return;
    lv_obj_clean(lst);
    lst_note = NULL;
    char cur[64];
    snprintf(cur, sizeof cur, "Đang dùng: %s", g_cfg.ssid[0] ? g_cfg.ssid : "(chưa có)");
    lv_obj_t *c = label(lst, &font_vn14, C_MUTED, cur);
    lv_obj_set_width(c, 210);
    lv_label_set_long_mode(c, LV_LABEL_LONG_DOT);
    lv_obj_set_pos(c, 14, 9);
    lv_obj_t *rs = button(lst, LCD_H_RES - 90, 2, 82, 30, I_SYNC " Quét", &font_vn14, false, NULL);
    lv_obj_add_event_cb(rs, wf_rescan_cb, LV_EVENT_CLICKED, NULL);
    int y = 38;
    if (wf_n == 0) note(lst, 14, y + 4, 292, "Không thấy mạng Wi-Fi nào. Bấm Quét để thử lại.", C_MUTED);
    for (int i = 0; i < wf_n; i++, y += 44) {
        lv_obj_t *r = card_row(lst, y, 40);
        lv_obj_t *l = label(r, &font_vn14, C_TEXT, wf_ap[i].ssid);
        lv_obj_set_width(l, 190);
        lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
        lv_obj_align(l, LV_ALIGN_LEFT_MID, 12, 0);
        uint32_t col = wf_ap[i].rssi > -60 ? C_GREEN : wf_ap[i].rssi > -75 ? C_ORANGE : C_RED;
        lv_obj_t *ic = label(r, &font_vn18, col, I_WIFI);
        lv_obj_align(ic, LV_ALIGN_RIGHT_MID, -10, 0);
        if (!wf_ap[i].lock) {
            lv_obj_t *op = label(r, &font_vn14, C_MUTED, "Mở");
            lv_obj_align(op, LV_ALIGN_RIGHT_MID, -40, 0);
        }
        lv_obj_add_event_cb(r, wf_pick_cb, LV_EVENT_CLICKED, (void *)(intptr_t)i);
    }
    box(lst, 0, y, 1, 6);
}

static void wf_go(void)
{
    if (!wf_ta) return;
    const char *pw = lv_textarea_get_text(wf_ta);
    if (strlen(pw) < 8) { toast("Mật khẩu Wi-Fi cần từ 8 ký tự", false); return; }
    wf_connect(lv_label_get_text(sheet_title), pw);
}

static void wf_go_cb(lv_event_t *e) { wf_go(); }

static void wf_password(const char *ssid)
{
    if (!lst) return;
    lv_obj_delete(lst);
    lst = NULL;
    wf_pw = true;
    lv_label_set_text(sheet_title, ssid);
    lv_obj_t *b = sheet_body;
    wf_ta = lv_textarea_create(b);
    lv_obj_set_pos(wf_ta, 8, 4);
    lv_obj_set_size(wf_ta, 232, 38);
    lv_textarea_set_one_line(wf_ta, true);
    lv_textarea_set_max_length(wf_ta, 63);
    lv_textarea_set_password_mode(wf_ta, true);
    lv_textarea_set_placeholder_text(wf_ta, "Mật khẩu Wi-Fi");
    lv_obj_set_style_text_font(wf_ta, &font_vn14, 0);
    lv_obj_set_style_text_color(wf_ta, lv_color_hex(C_TEXT), 0);
    lv_obj_set_style_text_color(wf_ta, lv_color_hex(C_MUTED), LV_PART_TEXTAREA_PLACEHOLDER);
    lv_obj_set_style_bg_color(wf_ta, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_opa(wf_ta, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(wf_ta, 1, 0);
    lv_obj_set_style_border_color(wf_ta, lv_color_hex(C_LINE), 0);
    lv_obj_set_style_border_color(wf_ta, lv_color_hex(C_ACCENT), LV_STATE_FOCUSED);
    lv_obj_set_style_radius(wf_ta, 10, 0);
    lv_obj_t *go = button(b, 246, 4, 66, 38, I_CHECK " Nối", &font_vn14, true, NULL);
    lv_obj_add_event_cb(go, wf_go_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *kb = lv_keyboard_create(b);
    lv_obj_set_size(kb, LCD_H_RES, PAGE_H - 40 - 46);
    lv_obj_align(kb, LV_ALIGN_TOP_LEFT, 0, 46);
    lv_keyboard_set_textarea(kb, wf_ta);
    lv_obj_set_style_bg_color(kb, lv_color_hex(C_BG), 0);
    lv_obj_set_style_bg_opa(kb, LV_OPA_COVER, 0);
    lv_obj_set_style_pad_all(kb, 2, 0);
    lv_obj_set_style_pad_row(kb, 3, 0);
    lv_obj_set_style_pad_column(kb, 3, 0);
    lv_obj_set_style_bg_color(kb, lv_color_hex(C_CARD2), LV_PART_ITEMS);
    lv_obj_set_style_bg_opa(kb, LV_OPA_COVER, LV_PART_ITEMS);
    lv_obj_set_style_text_color(kb, lv_color_hex(C_TEXT), LV_PART_ITEMS);
    lv_obj_set_style_border_width(kb, 0, LV_PART_ITEMS);
    lv_obj_set_style_shadow_width(kb, 0, LV_PART_ITEMS);
    lv_obj_set_style_radius(kb, 6, LV_PART_ITEMS);
    lv_obj_set_style_bg_color(kb, lv_color_hex(C_LINE), LV_PART_ITEMS | LV_STATE_CHECKED);
    lv_obj_add_event_cb(kb, wf_go_cb, LV_EVENT_READY, NULL);
}

static void sheet_wifi(lv_obj_t *b)
{
    lst = scroll_box(b);
    wf_pw = false;
    lst_say("Đang quét Wi-Fi…");
    wf_scanning = true;
    net_scan_start();
}

// the answer to a request, and the lists arriving

static lv_obj_t *toast_o;
static lv_timer_t *toast_t;

static void toast_gone(lv_timer_t *t)
{
    if (toast_o) lv_obj_delete(toast_o);
    toast_o = NULL;
    toast_t = NULL;
}

static void toast(const char *msg, bool ok)
{
    if (toast_t) lv_timer_delete(toast_t);
    if (toast_o) lv_obj_delete(toast_o);
    toast_o = lv_obj_create(lv_layer_top());
    lv_obj_remove_style_all(toast_o);
    lv_obj_set_style_bg_opa(toast_o, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(toast_o, lv_color_hex(ok ? 0x1F2937 : 0xB91C1C), 0);
    lv_obj_set_style_radius(toast_o, 12, 0);
    lv_obj_set_style_pad_all(toast_o, 10, 0);
    lv_obj_set_size(toast_o, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_remove_flag(toast_o, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_t *l = label(toast_o, &font_vn14, 0xFFFFFF, msg);
    lv_obj_set_style_max_width(l, LCD_H_RES - 60, 0);
    lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
    lv_obj_align(toast_o, LV_ALIGN_BOTTOM_MID, 0, -12);
    toast_t = lv_timer_create(toast_gone, 2800, NULL);
    lv_timer_set_repeat_count(toast_t, 1);
}

static void poll_menu(void)
{
    if (link_get_list(&rx_list, &rx_seq) && lst && lst_wait && !strcmp(rx_list.kind, want_kind) &&
        (list_mode != LM_EPISODES || !strcmp(rx_list.id, want_id))) {
        memcpy(&lst_data, &rx_list, sizeof lst_data);
        lst_wait = false;
        lst_fill();
    }
    if (link_get_alarms(&al_data, &al_seq)) {
        al_have = true;
        if (sheet_app == APP_ALARM) lst_wait = false;
        if (sheet_app == APP_ALARM && !al_edit && lst) alarm_fill();
    }
    if (wf_scanning && lst && !wf_pw && sheet_app == APP_WIFI && net_scan_get(wf_ap, &wf_n, 16)) {
        wf_scanning = false;
        wf_fill();
    }
    link_res_t r;
    if (link_get_res(&r, &res_seq)) {
        toast(r.msg, r.ok);
        if (!r.ok && lst && lst_wait) { lst_wait = false; lst_say(r.msg); }
        if (r.ok && play_wait) go_main();
        play_wait = false;
    }
    if (lst && lst_wait && NOW_US() - lst_t0 > 8000000) { lst_wait = false; char t[200]; snprintf(t, sizeof t, "Loa (DB-Robot %s) không trả lời. Cần bản 1.5.18 trở lên; kiểm tra loa đang bật và cùng mạng.", st.ver); lst_say(t); }
}

static void sheet_back(void)
{
    if (sheet_app == APP_PODCAST && list_mode == LM_EPISODES) {
        lv_label_set_text(sheet_title, APPS[APP_PODCAST].label);
        list_load(LM_PODCAST, NULL);
        return;
    }
    if (sheet_app == APP_WIFI && wf_pw) {
        wf_pw = false;
        wf_ta = NULL;
        lv_label_set_text(sheet_title, APPS[APP_WIFI].label);
        lv_obj_clean(sheet_body);
        lst = scroll_box(sheet_body);
        wf_fill();
        return;
    }
    if (sheet_app == APP_ALARM && al_edit) {
        al_edit = false;
        lv_obj_clean(sheet_body);
        lst = scroll_box(sheet_body);
        alarm_fill();
        link_request("alarms", NULL);
        return;
    }
    close_sheet();
}

static void open_sheet(int app)
{
    if (!sheet) return;
    lv_obj_clean(sheet_body);
    menu_reset();
    vol_num = vol_bar = bri_num = NULL;
    sheet_app = app;
    lv_label_set_text(sheet_title, APPS[app].label);
    switch (app) {
    case APP_SETTINGS: sheet_settings(sheet_body); break;
    case APP_WIFI:    sheet_wifi(sheet_body); break;
    case APP_ALARM:   sheet_alarm(sheet_body); break;
    case APP_RADIO:   sheet_list(sheet_body, LM_RADIO); break;
    case APP_PODCAST: sheet_list(sheet_body, LM_PODCAST); break;
    case APP_MUSIC:   sheet_music(sheet_body); break;
    case APP_INFO:    sheet_info(sheet_body); break;
    default: break;
    }
    lv_obj_remove_flag(sheet, LV_OBJ_FLAG_HIDDEN);
}

// ── page choice, timers, rebuild ─────────────────────────────────────────────────

static void show(page_t p)
{
    if (p == cur_page) return;
    for (int i = 0; i < P_COUNT; i++) {
        if (i == p) lv_obj_remove_flag(pages[i], LV_OBJ_FLAG_HIDDEN);
        else lv_obj_add_flag(pages[i], LV_OBJ_FLAG_HIDDEN);
    }
    cur_page = p;
    if (p == P_MUSIC) memset(m_level, 0, sizeof m_level), spectrum_timer(NULL);
}

/* "Tắt nghe" locks the chat: the voice page stays away until the speaker has really stopped
 * (quiet for 1.5 s), at most 20 s; "sleep" is repeated meanwhile in case a datagram was lost. */
static bool chat_locked(void)
{
    int64_t now = NOW_US();
    if (now >= lock_until) return false;
    if (talking()) {
        lock_quiet_since = 0;
        if (now >= lock_resend) { link_cmd("sleep"); lock_resend = now + 2000000; }
    } else {
        if (!lock_quiet_since) lock_quiet_since = now;
        if (now - lock_quiet_since > 1500000) { lock_until = 0; return false; }
    }
    return true;
}

static page_t choose_page(void)
{
    if (net_state() != NET_ONLINE) return P_STATUS;
    if (st.phase == LINK_CHOOSE) return P_CHOOSE;
    if (st.phase != LINK_CONNECTED) return P_STATUS;
    int64_t now = NOW_US();
    bool quiet = chat_locked();     // just told to stop listening
    if (!quiet && talking()) {
        voice_until = now + 6 * 1000000LL;
        return P_VOICE;
    }
    if (!quiet && now < voice_until && !music_active()) return P_VOICE;
    if (music_active()) return P_MUSIC;
    return P_IDLE;
}

static void update_dots(void)
{
    bool apps = apps_tile_active();
    for (int i = 0; i < 2; i++) {
        bool on = (i == 1) == apps;
        lv_obj_set_size(dot[i], on ? 16 : 6, 6);
        lv_obj_set_x(dot[i], i == 0 ? (on ? LCD_H_RES / 2 - 16 : LCD_H_RES / 2 - 10) : (on ? LCD_H_RES / 2 + 1 : LCD_H_RES / 2 + 4));
        lv_obj_set_style_bg_color(dot[i], lv_color_hex(on ? C_ACCENT : C_LINE), 0);
    }
}

static void tick_timer(lv_timer_t *t)
{
    static int n;
    bool fresh = link_state(&st, &st_seq);
    page_t p = choose_page();
    show(p);
    update_top();
    switch (p) {
    case P_STATUS: update_status(); break;
    case P_CHOOSE: update_choose(); break;
    case P_VOICE:
        update_face();
        if (fresh || (n % 10) == 0) update_voice_text();
        break;
    case P_MUSIC:
        if (fresh) update_music();
        update_progress();
        break;
    case P_IDLE: if (fresh || (n % 10) == 0) update_idle(); break;
    default: break;
    }
    if (p != P_CHOOSE) c_count = -1;
    poll_menu();
    update_video();
    update_alarm();
    update_dots();
    if (sheet_app == APP_SETTINGS) update_volume_sheet();

    // someone starts talking to the speaker, or nobody has touched the screen for a while:
    // leave the launcher and its sheets and go back to the main page
    static bool was_talking;
    bool tk = st.phase == LINK_CONNECTED && talking() && !chat_locked();
    if (tk && !was_talking && (apps_tile_active() || sheet_app >= 0)) go_main();
    was_talking = tk;
    if ((apps_tile_active() || sheet_app >= 0) && lv_display_get_inactive_time(NULL) > 45000) go_main();

    if ((n++ % 10) == 0) backlight_update();
}

static void build_all(void)
{
    lv_obj_t *scr = lv_screen_active();
    lv_obj_set_style_bg_color(scr, lv_color_hex(C_BG), 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);
    cur_page = P_COUNT;
    c_count = -1;
    st_seq = 0xFFFFFFFF;          // the new widgets get the whole state again
    memset(m_level, 0, sizeof m_level);

    build_top(scr);
    tv = lv_tileview_create(scr);
    lv_obj_remove_style_all(tv);
    lv_obj_set_pos(tv, 0, TOP_H);
    lv_obj_set_size(tv, LCD_H_RES, PAGE_H);
    lv_obj_set_scrollbar_mode(tv, LV_SCROLLBAR_MODE_OFF);
    tile_main = lv_tileview_add_tile(tv, 0, 0, LV_DIR_RIGHT);
    tile_apps = lv_tileview_add_tile(tv, 1, 0, LV_DIR_LEFT);
    for (int i = 0; i < P_COUNT; i++) {
        pages[i] = box(tile_main, 0, 0, LCD_H_RES, PAGE_H);
        lv_obj_add_flag(pages[i], LV_OBJ_FLAG_HIDDEN);
    }
    build_status(pages[P_STATUS]);
    build_choose(pages[P_CHOOSE]);
    build_voice(pages[P_VOICE]);
    build_music(pages[P_MUSIC]);
    build_idle(pages[P_IDLE]);
    build_apps(tile_apps);
    for (int i = 0; i < 2; i++) {
        dot[i] = box(scr, LCD_H_RES / 2, LCD_V_RES - 9, 6, 6);
        lv_obj_remove_flag(dot[i], LV_OBJ_FLAG_CLICKABLE);
        lv_obj_set_style_radius(dot[i], 3, 0);
        lv_obj_set_style_bg_opa(dot[i], LV_OPA_COVER, 0);
    }
    build_sheet(scr);              // after the dots: it covers them while it is open
    a_layer = NULL;
    build_alarm();
    tick_timer(NULL);
}

static bool rb_pending;
static int rb_tile, rb_sheet;

static void rebuild_cb(void *arg)
{
    rb_pending = false;
    apply_palette();
    close_video();
    if (a_layer) lv_obj_delete(a_layer);
    a_layer = NULL;
    v_btn = NULL;
    lv_obj_clean(lv_screen_active());
    build_all();
    if (rb_tile) lv_tileview_set_tile_by_index(tv, 1, 0, LV_ANIM_OFF);
    if (rb_sheet >= 0) open_sheet(rb_sheet);
    lv_obj_invalidate(lv_screen_active());
}

/* Switching the theme: let the object that was tapped finish its event, then rebuild everything. */
static void request_rebuild(void)
{
    if (rb_pending) return;
    rb_pending = true;
    rb_tile = apps_tile_active();
    rb_sheet = sheet_app;
    lv_async_call(rebuild_cb, NULL);
}

void ui_init(void)
{
    apply_palette();
    build_all();
    lv_timer_create(tick_timer, 100, NULL);
    lv_timer_create(spectrum_timer, 40, (void *)1);
}

// ── touch calibration ────────────────────────────────────────────────────────────

#ifdef ESP_PLATFORM
static lv_obj_t *cal_cross, *cal_msg;

static void cal_show(int x, int y, const char *msg)
{
    display_lock(-1);
    lv_obj_set_pos(cal_cross, x - 15, y - 15);
    lv_label_set_text(cal_msg, msg);
    display_unlock();
}

/* Wait for a press, average it, wait for release. false on timeout. */
static bool cal_point(int *rx, int *ry, int64_t deadline)
{
    int x, y, quiet = 0;
    while (quiet < 15) {                         // released for 150 ms first
        if (esp_timer_get_time() > deadline) return false;

        display_lock(-1);
        bool touched = touch_read_raw(&x, &y);
        display_unlock();

        quiet = touched ? 0 : quiet + 1;
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    long sx = 0, sy = 0;
    int n = 0;
    while (n < 12) {
        if (esp_timer_get_time() > deadline) return false;

        display_lock(-1);
        bool touched = touch_read_raw(&x, &y);
        display_unlock();

        if (touched) { sx += x; sy += y; n++; }
        else if (n) n = 0, sx = sy = 0;          // lifted too soon: start again
        vTaskDelay(pdMS_TO_TICKS(15));
    }
    *rx = sx / n;
    *ry = sy / n;
    return true;
}

void ui_calibrate(void)
{
    apply_palette();
    display_touch_enable(false);
    display_lock(-1);
    lv_obj_t *scr = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(scr, lv_color_hex(C_BG), 0);
    cal_msg = label(scr, &font_vn18, C_TEXT, "");
    lv_obj_set_width(cal_msg, 260);
    lv_obj_set_style_text_align(cal_msg, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_long_mode(cal_msg, LV_LABEL_LONG_WRAP);
    lv_obj_align(cal_msg, LV_ALIGN_CENTER, 0, 10);
    cal_cross = label(scr, &font_vn24, C_ORANGE, I_PLUS);
    lv_obj_set_size(cal_cross, 30, 30);
    lv_obj_set_style_text_align(cal_cross, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_t *old = lv_screen_active();
    lv_screen_load(scr);
    display_unlock();
    display_backlight(g_cfg.bright);

    const int px[3] = { CAL_A, CAL_B, CAL_A }, py[3] = { CAL_A, CAL_A, CAL_C };
    int rx[3], ry[3];
    bool ok = true;
    int64_t deadline = esp_timer_get_time() + 40 * 1000000LL;
    for (int tries = 0; tries < 3; tries++) {
        ok = true;
        for (int i = 0; i < 3 && ok; i++) {
            char m[96];
            snprintf(m, sizeof m, "Hiệu chỉnh cảm ứng\nChạm vào dấu + (%d/3)", i + 1);
            cal_show(px[i], py[i], m);
            ok = cal_point(&rx[i], &ry[i], deadline);
        }
        if (!ok) break;
        // which raw axis moved when the finger moved left→right?
        bool swap = abs(ry[1] - ry[0]) > abs(rx[1] - rx[0]);
        int x0 = swap ? ry[0] : rx[0], x1 = swap ? ry[1] : rx[1];
        int y0 = swap ? rx[0] : ry[0], y1 = swap ? rx[2] : ry[2];
        if (abs(x1 - x0) > 600 && abs(y1 - y0) > 400) {
            g_cfg.cal_swap = swap;
            g_cfg.cal_x0 = x0; g_cfg.cal_x1 = x1; g_cfg.cal_y0 = y0; g_cfg.cal_y1 = y1;
            g_cfg.cal_ok = 1;
            cal_show(-100, -100, I_CHECK " Đã lưu hiệu chỉnh");
            vTaskDelay(pdMS_TO_TICKS(900));
            break;
        }
        ok = false;
        cal_show(-100, -100, "Chưa khớp, làm lại nhé");
        vTaskDelay(pdMS_TO_TICKS(1200));
    }
    if (!ok) {
        cal_show(-100, -100, "Bỏ qua hiệu chỉnh\n(chạy lại trong Ứng dụng › Cảm ứng)");
        vTaskDelay(pdMS_TO_TICKS(1500));
    }
    g_cfg.want_cal = 0;
    cfg_save();
    display_lock(-1);
    lv_screen_load(old);
    lv_obj_delete(scr);
    display_unlock();
    display_touch_enable(true);
}
#else
void ui_calibrate(void) {}
#endif
