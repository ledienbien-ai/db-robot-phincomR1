/*
 * DB-Robot display: an ESP32-S3 with a 2.8" touch screen that shows what a DB-Robot speaker
 * (Phicomm R1) is doing — the conversation, the song and its spectrum, the clock, the weather,
 * the alarm — and has a few buttons for it.
 */
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_log.h"
#include "cfg.h"
#include "board.h"
#include "display.h"
#include "net.h"
#include "portal.h"
#include "link.h"
#include "ui.h"

void app_main(void)
{
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
    ESP_LOGI("main", "DB-Robot display started");
}
