/*
 * DB-Robot display: an ESP32-S3 with a 2.8" touch screen that shows what a DB-Robot speaker
 * (Phicomm R1) is doing — the conversation, the song and its spectrum, the clock, the weather,
 * the alarm — and has a few buttons for it.
 */
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "cfg.h"
#include "board.h"
#include "display.h"
#include "net.h"
#include "portal.h"
#include "link.h"
#include "ui.h"

void app_main(void)
{
    // Put all but the smallest allocations in PSRAM: LVGL's objects (~1400 of them, ~60 KB), cJSON and
    // the web pages. The internal RAM is needed by Wi-Fi -- in setup mode the access point runs too --
    // the draw buffers, task stacks and FreeRTOS. With the default 4 KB threshold the idle page filled
    // it and a fresh board aborted at boot (no memory left for a mutex: abort() in lock_init_generic).
    if (heap_caps_get_total_size(MALLOC_CAP_SPIRAM) > 0) heap_caps_malloc_extmem_enable(64);
    esp_err_t r = nvs_flash_init();
    if (r == ESP_ERR_NVS_NO_FREE_PAGES || r == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }
    cfg_load();
    display_init();
    if (g_cfg.want_cal && !BOARD_TOUCH_CAPACITIVE) ui_calibrate();
    net_init();
    portal_start();
    link_init();            // before the UI: the UI reads its state
    display_lock(-1);
    ui_init();
    display_unlock();
    display_backlight(g_cfg.bright);
    ESP_LOGI("main", "DB-Robot display started (RAM trong còn %u KB, PSRAM còn %u KB)",
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024));
}
