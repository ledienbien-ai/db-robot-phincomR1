#pragma once
#include "lvgl.h"

/* Pictures for the answer cards: see art.c (made by tools/gen_art.py). */
#define ART_ICON 72
extern const lv_image_dsc_t art_sun;
extern const lv_image_dsc_t art_moon;
extern const lv_image_dsc_t art_sun_cloud_s;
extern const lv_image_dsc_t art_sun_cloud;
extern const lv_image_dsc_t art_cloud;
extern const lv_image_dsc_t art_fog;
extern const lv_image_dsc_t art_rain;
extern const lv_image_dsc_t art_showers;
extern const lv_image_dsc_t art_snow;
extern const lv_image_dsc_t art_storm;
extern const lv_image_dsc_t art_coin;
extern const lv_image_dsc_t art_fuel;
extern const lv_image_dsc_t art_calendar;
extern const lv_image_dsc_t art_abacus;
extern const lv_image_dsc_t art_apple;
extern const lv_image_dsc_t art_globe;
extern const lv_image_dsc_t art_moon_full;
extern const lv_image_dsc_t art_bulb;
extern const lv_image_dsc_t art_plug;
extern const lv_image_dsc_t art_fan;
extern const lv_image_dsc_t art_snowflake;
extern const lv_image_dsc_t art_window;
extern const lv_image_dsc_t art_lock;
extern const lv_image_dsc_t art_tv;
extern const lv_image_dsc_t art_house;

typedef struct { char cc[3]; uint16_t w, h; uint32_t len; const uint8_t *jpg; } art_flag_t;
extern const art_flag_t ART_FLAGS[];
extern const int ART_FLAG_COUNT;
/* The flag of a country by its ISO 3166 code in lower case ("vn", "jp"), or NULL. */
const art_flag_t *art_flag(const char *cc);
