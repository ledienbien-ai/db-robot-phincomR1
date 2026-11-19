#pragma once
/*
 * Two boards, chosen in menuconfig ("DB-Robot màn hình phụ" → "Mạch màn hình", main/Kconfig.projbuild):
 *
 *  - CONFIG_DBR_BOARD_DEVKIT_SPI28 (default): ESP32-S3-DevKitC-1 N16R8 wired to a 2.8" SPI TFT 240x320
 *    (ST7789 or ILI9341) with XPT2046 resistive touch. The LCD and the touch controller share one SPI bus.
 *  - CONFIG_DBR_BOARD_FREENOVE_ES3N28P: the Freenove 2.8" board with its own ESP32-S3 (N16R8): ILI9341 on
 *    SPI, FT6336 capacitive touch on I2C (shared with the board's ES8311 codec, which is not used here).
 *
 * GPIO 35..37 are taken by the octal PSRAM on N16R8 modules and must not be used; GPIO 0/3/45/46 are
 * strapping pins (45/46 are fine as outputs after boot, which is how the Freenove board uses them).
 */
#include "sdkconfig.h"

#if CONFIG_DBR_BOARD_FREENOVE_ES3N28P

#define BOARD_NAME     "Freenove ES3N28P"
#define BOARD_TOUCH_CAPACITIVE 1      // FT6336: no calibration
#define BOARD_LCD_FIXED_ILI9341 1     // the chip is known: the "Chip màn hình" choice is ignored

#define PIN_SPI_MOSI   11
#define PIN_SPI_SCLK   12
#define PIN_SPI_MISO   (-1)  // the LCD is only written to
#define PIN_LCD_CS     10
#define PIN_LCD_DC     46
#define PIN_LCD_RST    (-1)  // not wired: software reset
#define PIN_LCD_BL     45

#define PIN_TOUCH_SDA  16
#define PIN_TOUCH_SCL  15
#define PIN_TOUCH_RST  18
#define PIN_TOUCH_INT  17
#define TOUCH_I2C_ADDR 0x38
#define PIN_AUDIO_PA    1    // the board's speaker amplifier: held off

#define LCD_SPI_HOST   SPI2_HOST
#define LCD_SPI_HZ     (40 * 1000 * 1000)

/* Freenove's own parameters for this panel at 0°: native 240x320 portrait, mirrored in X, colours
 * inverted, BGR. Touch points then come in the panel's frame as they are. */
#define DISPLAY_MIRROR_X true
#define DISPLAY_INVERT_COLOR    true
#define DISPLAY_RGB_ORDER  LCD_RGB_ELEMENT_ORDER_BGR

#else   // CONFIG_DBR_BOARD_DEVKIT_SPI28

#define BOARD_NAME     "ESP32-S3 + 2.8\" SPI"
#define BOARD_TOUCH_CAPACITIVE 0
#define BOARD_LCD_FIXED_ILI9341 0

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

/* The 2.8" ST7789 touch module, with the vendor's own board parameters:
 *   native 240x320 portrait, no mirror, no swap, colours inverted, RGB order, no offset, SPI mode 0. */
#define DISPLAY_MIRROR_X false
#define DISPLAY_INVERT_COLOR    true
#define DISPLAY_RGB_ORDER  LCD_RGB_ELEMENT_ORDER_RGB

#endif

#define LCD_H_RES      320  // landscape
#define LCD_V_RES      240

#define SPEAKER_PORT   8089

#define FW_VERSION     "1.5.42"

/* The landscape picture (LCD_H_RES x LCD_V_RES) is made by LVGL's software rotation. */
#define DISPLAY_WIDTH   240
#define DISPLAY_HEIGHT  320
#define DISPLAY_MIRROR_Y false
#define DISPLAY_SWAP_XY false
#define DISPLAY_OFFSET_X  0
#define DISPLAY_OFFSET_Y  0
#define DISPLAY_BACKLIGHT_OUTPUT_INVERT false
#define DISPLAY_SPI_MODE 0
