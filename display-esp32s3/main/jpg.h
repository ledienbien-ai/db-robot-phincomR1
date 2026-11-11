#pragma once
#include <stdbool.h>
#include <stddef.h>
#include "lvgl.h"

/* A small JPEG (a flag from art.c) decoded into a new RGB565 image; jpg_free() gives the pixels back. */
bool jpg_image(const uint8_t *jpg, size_t n, lv_image_dsc_t *out);
void jpg_free(lv_image_dsc_t *img);
