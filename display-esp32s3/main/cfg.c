#include <string.h>
#include <stddef.h>
#include "nvs_flash.h"
#include "nvs.h"
#include "cfg.h"
#include "board.h"

cfg_t g_cfg;

#define CFG_VER 1
typedef struct { uint8_t ver; cfg_t c; } blob_t;

static void defaults(void)
{
    memset(&g_cfg, 0, sizeof g_cfg);
    strcpy(g_cfg.name, "Màn hình phụ");
#if BOARD_LCD_FIXED_ILI9341
    g_cfg.lcd_type = LCD_ILI9341;
#else
    g_cfg.lcd_type = LCD_ST7789;
#endif
    g_cfg.invert = DISPLAY_INVERT_COLOR;
    g_cfg.bright = 80;
    g_cfg.night_dim = 1;
    g_cfg.want_cal = !BOARD_TOUCH_CAPACITIVE;   // offer the calibration on the first start (resistive touch only)
}

void cfg_load(void)
{
    defaults();
    nvs_handle_t h;
    if (nvs_open("dbr1", NVS_READONLY, &h) != ESP_OK) return;
    // A blob saved by an older firmware is shorter: copy what it has, the newer fields keep defaults.
    blob_t b;
    memset(&b, 0, sizeof b);
    size_t n = sizeof b;
    if (nvs_get_blob(h, "cfg", &b, &n) == ESP_OK && n >= offsetof(blob_t, c) + offsetof(cfg_t, light) && b.ver == CFG_VER) {
        cfg_t keep = g_cfg;
        g_cfg = b.c;
        if (n < sizeof b) {
            if (n <= offsetof(blob_t, c) + offsetof(cfg_t, light)) g_cfg.light = keep.light;   // before 1.5.16
            // saved by a firmware before 1.5.27: the old "upside down" switch becomes a landscape orientation
            g_cfg.orient = g_cfg.rot180 ? 2 : 0;
        }
    }
    if (g_cfg.orient > 3) g_cfg.orient = 0;
#if BOARD_LCD_FIXED_ILI9341
    g_cfg.lcd_type = LCD_ILI9341;   // the chip of this board is known
#endif
    nvs_close(h);
    if (g_cfg.bright < 5 || g_cfg.bright > 100) g_cfg.bright = 80;
}

void cfg_save(void)
{
    nvs_handle_t h;
    if (nvs_open("dbr1", NVS_READWRITE, &h) != ESP_OK) return;
    blob_t b = { .ver = CFG_VER, .c = g_cfg };
    nvs_set_blob(h, "cfg", &b, sizeof b);
    nvs_commit(h);
    nvs_close(h);
}
