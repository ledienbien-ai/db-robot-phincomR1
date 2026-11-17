#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_ili9341.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "display.h"
#include "touch.h"
#include "board.h"
#include "cfg.h"

static const char *TAG = "display";
static esp_lcd_panel_handle_t s_panel;
static lv_display_t *s_disp;
static SemaphoreHandle_t s_lock;

#define BUF_LINES 40

static bool on_trans_done(esp_lcd_panel_io_handle_t io, esp_lcd_panel_io_event_data_t *e, void *ctx)
{
    lv_display_flush_ready((lv_display_t *)ctx);
    return false;
}

static uint8_t *s_rot_buf;

/* LVGL draws the 320x240 landscape picture; the panel stays in its native 240x320 portrait
 * scan (the parameters of the vendor's own board file) and the rotation is done here, in software. */
static void flush_cb(lv_display_t *d, const lv_area_t *a, uint8_t *px)
{
    lv_area_t ra = *a;
    lv_display_rotation_t rot = lv_display_get_rotation(d);
    if (rot != LV_DISPLAY_ROTATION_0) {
        lv_color_format_t cf = lv_display_get_color_format(d);
        lv_display_rotate_area(d, &ra);
        int32_t sw = lv_area_get_width(a), sh = lv_area_get_height(a);
        uint32_t ss = lv_draw_buf_width_to_stride(sw, cf), ds = lv_draw_buf_width_to_stride(lv_area_get_width(&ra), cf);
        lv_draw_sw_rotate(px, s_rot_buf, sw, sh, ss, ds, rot, cf);
        px = s_rot_buf;
    }
    lv_draw_sw_rgb565_swap(px, lv_area_get_size(&ra));   // the panel wants big-endian RGB565
    esp_lcd_panel_draw_bitmap(s_panel, ra.x1, ra.y1, ra.x2 + 1, ra.y2 + 1, px);
}

/* The picture's orientation, in quarter turns from the first landscape one (the one the touch
 * calibration is made in): 0 landscape (LVGL 270 = the picture turned clockwise, as the old
 * swap + mirror_x did), 1 portrait, 2 landscape upside down, 3 portrait the other way. */
static lv_display_rotation_t rot_for(int q) { return (lv_display_rotation_t)((3 + (q & 3)) & 3); }

void display_set_orient(int q)
{
    if (!s_disp) return;
    display_lock(-1);
    lv_display_set_rotation(s_disp, rot_for(q));
    display_unlock();
}

bool display_portrait(void) { return s_disp && lv_display_get_horizontal_resolution(s_disp) < lv_display_get_vertical_resolution(s_disp); }

void display_set_invert(bool on)
{
    if (s_panel) esp_lcd_panel_invert_color(s_panel, on);
}

static volatile bool s_touch_on = true;
void display_touch_enable(bool on) { s_touch_on = on; }

static void touch_cb(lv_indev_t *in, lv_indev_data_t *data)
{
    static int16_t lx, ly;
    int16_t x, y;
    if (s_touch_on && touch_read(&x, &y)) {
        lx = x; ly = y;
        data->state = LV_INDEV_STATE_PRESSED;
    } else {
        data->state = LV_INDEV_STATE_RELEASED;
    }
    // LVGL wants the touch point in the panel's own (portrait, 240x320) frame and turns it by the
    // display rotation itself, for all four orientations. The calibration is always made in the first
    // landscape orientation (a 320x240 picture, 270 for LVGL), so (lx, ly) is turned back to the
    // panel frame the same way for every orientation: the finger stays where it is on the glass,
    // however the picture is turned.
    data->point.x = DISPLAY_WIDTH - 1 - ly;
    data->point.y = lx;
}

static uint32_t tick_cb(void) { return (uint32_t)(esp_timer_get_time() / 1000); }

bool display_lock(int timeout_ms)
{
    return xSemaphoreTakeRecursive(s_lock, timeout_ms < 0 ? portMAX_DELAY : pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
}

void display_unlock(void) { xSemaphoreGiveRecursive(s_lock); }

lv_display_t *display_get(void) { return s_disp; }

void display_backlight(int percent)
{
    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, percent * 1023 / 100);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
}

static void lvgl_task(void *arg)
{
    for (;;) {
        uint32_t wait = 10;
        if (display_lock(-1)) {
            wait = lv_timer_handler();
            display_unlock();
        }
        if (wait < 5) wait = 5;
        if (wait > 30) wait = 30;
        vTaskDelay(pdMS_TO_TICKS(wait));
    }
}

void display_init(void)
{
    s_lock = xSemaphoreCreateRecursiveMutex();

    ledc_timer_config_t lt = { .speed_mode = LEDC_LOW_SPEED_MODE, .duty_resolution = LEDC_TIMER_10_BIT,
                               .timer_num = LEDC_TIMER_0, .freq_hz = 5000, .clk_cfg = LEDC_AUTO_CLK };
    ledc_timer_config(&lt);
    ledc_channel_config_t lc = { .gpio_num = PIN_LCD_BL, .speed_mode = LEDC_LOW_SPEED_MODE,
                                 .channel = LEDC_CHANNEL_0, .timer_sel = LEDC_TIMER_0, .duty = 0,
                                 .flags.output_invert = DISPLAY_BACKLIGHT_OUTPUT_INVERT };
    ledc_channel_config(&lc);

    spi_bus_config_t bc = {
        .mosi_io_num = PIN_SPI_MOSI, .miso_io_num = PIN_SPI_MISO, .sclk_io_num = PIN_SPI_SCLK,
        .quadwp_io_num = -1, .quadhd_io_num = -1,
        .max_transfer_sz = DISPLAY_HEIGHT * BUF_LINES * 2 + 16,
    };
    ESP_ERROR_CHECK(spi_bus_initialize(LCD_SPI_HOST, &bc, SPI_DMA_CH_AUTO));

    lv_init();
    lv_tick_set_cb(tick_cb);
    s_disp = lv_display_create(DISPLAY_WIDTH, DISPLAY_HEIGHT);   // the panel as it is: 240x320 portrait

    esp_lcd_panel_io_handle_t io;
    esp_lcd_panel_io_spi_config_t ic = {
        .dc_gpio_num = PIN_LCD_DC, .cs_gpio_num = PIN_LCD_CS,
        .pclk_hz = LCD_SPI_HZ, .lcd_cmd_bits = 8, .lcd_param_bits = 8,
        .spi_mode = DISPLAY_SPI_MODE, .trans_queue_depth = 10,
        .on_color_trans_done = on_trans_done, .user_ctx = s_disp,
    };
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)LCD_SPI_HOST, &ic, &io));

    esp_lcd_panel_dev_config_t pc = {
        .reset_gpio_num = PIN_LCD_RST,
        .rgb_ele_order = (g_cfg.bgr != (DISPLAY_RGB_ORDER == LCD_RGB_ELEMENT_ORDER_BGR)) ? LCD_RGB_ELEMENT_ORDER_BGR : LCD_RGB_ELEMENT_ORDER_RGB,
        .bits_per_pixel = 16,
    };
    // the Freenove board's chip is known (BOARD_LCD_FIXED_ILI9341 keeps lcd_type at ILI9341 in cfg.c)
    if (BOARD_LCD_FIXED_ILI9341 || g_cfg.lcd_type == LCD_ILI9341) {
        ESP_ERROR_CHECK(esp_lcd_new_panel_ili9341(io, &pc, &s_panel));
    } else {
        ESP_ERROR_CHECK(esp_lcd_new_panel_st7789(io, &pc, &s_panel));
    }
    esp_lcd_panel_reset(s_panel);
    esp_lcd_panel_init(s_panel);
    esp_lcd_panel_invert_color(s_panel, g_cfg.invert);
    // the vendor's parameters for this 2.8" ST7789: no swap, no mirror, no offset
    esp_lcd_panel_swap_xy(s_panel, DISPLAY_SWAP_XY);
    esp_lcd_panel_mirror(s_panel, DISPLAY_MIRROR_X, DISPLAY_MIRROR_Y != (g_cfg.mirror != 0));   // "Lật ngang" flips the landscape picture = the native Y axis
    esp_lcd_panel_set_gap(s_panel, DISPLAY_OFFSET_X, DISPLAY_OFFSET_Y);
    esp_lcd_panel_disp_on_off(s_panel, true);

    size_t sz = LCD_H_RES * BUF_LINES * 2;
    void *b1 = heap_caps_malloc(sz, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    void *b2 = heap_caps_malloc(sz, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    s_rot_buf = heap_caps_malloc(sz, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    assert(b1 && b2 && s_rot_buf);
    lv_display_set_color_format(s_disp, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(s_disp, b1, b2, sz, LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(s_disp, flush_cb);
    lv_display_set_rotation(s_disp, rot_for(g_cfg.orient));   // → 320x240 or 240x320 for LVGL

    touch_init(LCD_SPI_HOST);
    lv_indev_t *in = lv_indev_create();
    lv_indev_set_type(in, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(in, touch_cb);

    xTaskCreatePinnedToCore(lvgl_task, "lvgl", 12288, NULL, 4, NULL, 1);
    ESP_LOGI(TAG, "%s: %s %dx%d (native %dx%d) ready", BOARD_NAME, g_cfg.lcd_type == LCD_ILI9341 ? "ILI9341" : "ST7789", LCD_H_RES, LCD_V_RES, DISPLAY_WIDTH, DISPLAY_HEIGHT);
}
