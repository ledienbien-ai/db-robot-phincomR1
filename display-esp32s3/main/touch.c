/* Touch input, one driver per board (board.h):
 *  - DevKit + SPI module: a small XPT2046 (ADS7843-compatible) driver on the LCD's SPI bus, calibrated once.
 *  - Freenove ES3N28P: the FT6336 capacitive controller on I2C; it reports the panel's own pixels,
 *    so there is nothing to calibrate. */
#include <stdlib.h>
#include "driver/gpio.h"
#include "esp_log.h"
#include "touch.h"
#include "board.h"
#include "cfg.h"

#if BOARD_TOUCH_CAPACITIVE
#include "driver/i2c_master.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "touch";
static i2c_master_dev_handle_t s_ft;

void touch_init(spi_host_device_t host)
{
    (void)host;
    // the board's speaker amplifier shares nothing with us: keep it off (no hiss)
    gpio_config_t pa = { .pin_bit_mask = 1ULL << PIN_AUDIO_PA, .mode = GPIO_MODE_OUTPUT };
    gpio_config(&pa);
    gpio_set_level(PIN_AUDIO_PA, 0);
    // reset the controller: low 10 ms, then 200 ms for it to boot (the driver's own 10 ms is too short)
    gpio_config_t rst = { .pin_bit_mask = 1ULL << PIN_TOUCH_RST, .mode = GPIO_MODE_OUTPUT };
    gpio_config(&rst);
    gpio_set_level(PIN_TOUCH_RST, 0);
    vTaskDelay(pdMS_TO_TICKS(10));
    gpio_set_level(PIN_TOUCH_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(200));
    gpio_config_t in = { .pin_bit_mask = 1ULL << PIN_TOUCH_INT, .mode = GPIO_MODE_INPUT, .pull_up_en = 1 };
    gpio_config(&in);

    i2c_master_bus_config_t bc = {
        .i2c_port = I2C_NUM_0, .sda_io_num = PIN_TOUCH_SDA, .scl_io_num = PIN_TOUCH_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT, .glitch_ignore_cnt = 7, .flags.enable_internal_pullup = 1,
    };
    i2c_master_bus_handle_t bus;
    if (i2c_new_master_bus(&bc, &bus) != ESP_OK) { ESP_LOGE(TAG, "I2C bus failed"); return; }
    i2c_device_config_t dc = { .dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = TOUCH_I2C_ADDR, .scl_speed_hz = 400000 };
    if (i2c_master_bus_add_device(bus, &dc, &s_ft) != ESP_OK) { ESP_LOGE(TAG, "FT6336 not added"); s_ft = NULL; return; }
    uint8_t reg = 0xA3, id = 0;   // chip id: 0x64 FT6336U, 0x36 FT6236, 0x06 FT6206
    if (i2c_master_transmit_receive(s_ft, &reg, 1, &id, 1, 100) == ESP_OK) ESP_LOGI(TAG, "FT6x36 chip id 0x%02x", id);
    else ESP_LOGW(TAG, "FT6336 does not answer at 0x%02x", TOUCH_I2C_ADDR);
    uint8_t th[2] = { 0x80, 64 };   // touch threshold (the vendor's value)
    i2c_master_transmit(s_ft, th, 2, 100);
}

/* The panel's own pixel (x 0..239 across, y 0..319 down, as the panel is driven); false when not pressed. */
bool touch_read_raw(int *rx, int *ry)
{
    if (!s_ft) return false;
    uint8_t reg = 0x02, d[5];
    if (i2c_master_transmit_receive(s_ft, &reg, 1, d, 5, 20) != ESP_OK) return false;
    int n = d[0] & 0x0F;
    if (n < 1 || n > 2) return false;
    int x = ((d[1] & 0x0F) << 8) | d[2], y = ((d[3] & 0x0F) << 8) | d[4];
    if (x >= DISPLAY_WIDTH) x = DISPLAY_WIDTH - 1;
    if (y >= DISPLAY_HEIGHT) y = DISPLAY_HEIGHT - 1;
    *rx = x;
    *ry = y;
    return true;
}

/* In the frame the rest of the firmware uses for the touch (the first landscape picture, 320x240;
 * display.c turns it back to the panel's frame for LVGL). "Lật ngang" mirrors the panel's Y axis. */
bool touch_read(int16_t *x, int16_t *y)
{
    int px, py;
    if (!touch_read_raw(&px, &py)) return false;
    if (g_cfg.mirror) py = DISPLAY_HEIGHT - 1 - py;
    *x = py;                          // display.c: panel x = 239 - ly, panel y = lx
    *y = DISPLAY_WIDTH - 1 - px;
    return true;
}

#else   // XPT2046 on the LCD's SPI bus
static spi_device_handle_t s_dev;

void touch_init(spi_host_device_t host)
{
    spi_device_interface_config_t dc = {
        .clock_speed_hz = TOUCH_SPI_HZ,
        .mode = 0,
        .spics_io_num = PIN_TOUCH_CS,
        .queue_size = 1,
    };
    ESP_ERROR_CHECK(spi_bus_add_device(host, &dc, &s_dev));
    gpio_config_t io = { .pin_bit_mask = 1ULL << PIN_TOUCH_IRQ, .mode = GPIO_MODE_INPUT, .pull_up_en = 1 };
    gpio_config(&io);
}

static int xfer(uint8_t cmd)
{
    spi_transaction_t t = { .flags = SPI_TRANS_USE_TXDATA | SPI_TRANS_USE_RXDATA, .length = 24 };
    t.tx_data[0] = cmd;
    if (spi_device_polling_transmit(s_dev, &t) != ESP_OK) return -1;
    return ((t.rx_data[1] << 8) | t.rx_data[2]) >> 3;
}

static int cmp(const void *a, const void *b) { return *(const int *)a - *(const int *)b; }

bool touch_read_raw(int *rx, int *ry)
{
    if (!s_dev) return false;
    int z1 = xfer(0xB1), z2 = xfer(0xC1);
    if (z1 < 0 || z2 < 0) return false;
    int z = z1 + 4095 - z2;
    if (z < 400) { xfer(0xD0); return false; }      // re-arm PENIRQ, power down
    int xs[7], ys[7];
    for (int i = 0; i < 7; i++) { xs[i] = xfer(0xD1); ys[i] = xfer(0x91); }
    xfer(0xD0);
    qsort(xs, 7, sizeof(int), cmp);
    qsort(ys, 7, sizeof(int), cmp);
    if (xs[5] - xs[1] > 120 || ys[5] - ys[1] > 120) return false;   // finger moving / noise
    *rx = xs[3];
    *ry = ys[3];
    return *rx > 50 && *rx < 4050 && *ry > 50 && *ry < 4050;
}

bool touch_read(int16_t *x, int16_t *y)
{
    int rx, ry;
    if (!touch_read_raw(&rx, &ry)) return false;
    int a = g_cfg.cal_swap ? ry : rx;   // drives screen x
    int b = g_cfg.cal_swap ? rx : ry;   // drives screen y
    int x0 = g_cfg.cal_x0, x1 = g_cfg.cal_x1, y0 = g_cfg.cal_y0, y1 = g_cfg.cal_y1;
    if (!g_cfg.cal_ok || x1 == x0 || y1 == y0) {   // a usable guess for the usual modules
        x0 = 3700; x1 = 400; y0 = 3600; y1 = 500;
    }
    int sx = CAL_A + (a - x0) * (CAL_B - CAL_A) / (x1 - x0);
    int sy = CAL_A + (b - y0) * (CAL_C - CAL_A) / (y1 - y0);
    sx = sx < 0 ? 0 : sx >= LCD_H_RES ? LCD_H_RES - 1 : sx;
    sy = sy < 0 ? 0 : sy >= LCD_V_RES ? LCD_V_RES - 1 : sy;
    *x = sx;
    *y = sy;
    return true;
}

#endif
