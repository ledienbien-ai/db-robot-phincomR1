#pragma once
/*
 * ESP32-S3-DevKitC-1 N16R8 + 2.8" SPI TFT 240x320 (ST7789 or ILI9341) with XPT2046 touch.
 * The LCD and the touch controller share one SPI bus. GPIO 35..37 are taken by the
 * octal PSRAM on the N16R8 and must not be used; GPIO 0/3/45/46 are strapping pins.
 */
#define PIN_SPI_MOSI   11   // LCD SDI(MOSI)  + T_DIN
#define PIN_SPI_SCLK   12   // LCD SCK        + T_CLK
#define PIN_SPI_MISO   13   // T_DO           (leave the LCD's SDO(MISO) unconnected)
#define PIN_LCD_CS     10
#define PIN_LCD_DC      9
#define PIN_LCD_RST    14
#define PIN_LCD_BL     21   // LED / BL (PWM)
#define PIN_TOUCH_CS    1   // T_CS
#define PIN_TOUCH_IRQ   2   // T_IRQ (optional)

#define LCD_SPI_HOST   SPI2_HOST
#define LCD_SPI_HZ     (40 * 1000 * 1000)
#define TOUCH_SPI_HZ   (2 * 1000 * 1000)

#define LCD_H_RES      320  // landscape
#define LCD_V_RES      240

#define SPEAKER_PORT   8089

#define FW_VERSION     "1.5.34"

/*
 * The 2.8" ST7789 touch module, with the vendor's own board parameters:
 *   native 240x320 portrait, no mirror, no swap, colours inverted, RGB order, no offset, SPI mode 0.
 * The landscape picture (LCD_H_RES x LCD_V_RES) is made by LVGL's software rotation.
 */
#ifdef CONFIG_LCD_ST7789_240X320
#define LCD_TYPE_ST7789_SERIAL
#endif
#define DISPLAY_WIDTH   240
#define DISPLAY_HEIGHT  320
#define DISPLAY_MIRROR_X false
#define DISPLAY_MIRROR_Y false
#define DISPLAY_SWAP_XY false
#define DISPLAY_INVERT_COLOR    true
#define DISPLAY_RGB_ORDER  LCD_RGB_ELEMENT_ORDER_RGB
#define DISPLAY_OFFSET_X  0
#define DISPLAY_OFFSET_Y  0
#define DISPLAY_BACKLIGHT_OUTPUT_INVERT false
#define DISPLAY_SPI_MODE 0
