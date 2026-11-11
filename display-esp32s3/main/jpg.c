/*
 * Small JPEGs to LVGL images with TJpgDec: the one in the ESP32-S3 ROM on the device (as video.c),
 * LVGL's copy in the desktop preview (UI_SIM).
 */
#include <string.h>
#include <stdlib.h>
#include "jpg.h"
#ifdef UI_SIM
#include "src/libs/tjpgd/tjpgd.h"
#define IN_RET size_t
#define IN_N size_t
#define IN_BUF uint8_t
#define OUT_RET int
#else
#include "esp_heap_caps.h"
#include "rom/tjpgd.h"
#define IN_RET UINT
#define IN_N UINT
#define IN_BUF BYTE
#define OUT_RET UINT
#endif

typedef struct { const uint8_t *p; size_t left; uint16_t *out; int w; } src_t;

static IN_RET in_cb(JDEC *jd, IN_BUF *buf, IN_N n)
{
    src_t *s = (src_t *)jd->device;
    if (n > s->left) n = s->left;
    if (buf) memcpy(buf, s->p, n);
    s->p += n;
    s->left -= n;
    return n;
}

static OUT_RET out_cb(JDEC *jd, void *bitmap, JRECT *r)
{
    src_t *s = (src_t *)jd->device;
    const uint8_t *px = (const uint8_t *)bitmap;      // RGB888
    for (int y = r->top; y <= r->bottom; y++) {
        uint16_t *o = s->out + y * s->w + r->left;
        for (int x = r->left; x <= r->right; x++, px += 3, o++) {
#ifdef UI_SIM   // LVGL's copy of TJpgDec writes B, G, R; the ROM one R, G, B
            *o = (uint16_t)(((px[2] & 0xF8) << 8) | ((px[1] & 0xFC) << 3) | (px[0] >> 3));
#else
            *o = (uint16_t)(((px[0] & 0xF8) << 8) | ((px[1] & 0xFC) << 3) | (px[2] >> 3));
#endif
        }
    }
    return 1;
}

bool jpg_image(const uint8_t *jpg, size_t n, lv_image_dsc_t *out)
{
    memset(out, 0, sizeof *out);
    void *pool = malloc(8192);
    if (!pool) return false;
    JDEC jd;
    src_t s = { jpg, n, NULL, 0 };
    bool ok = false;
    if (jd_prepare(&jd, in_cb, pool, 8192, &s) == JDR_OK && jd.width <= 320 && jd.height <= 320) {
        size_t bytes = (size_t)jd.width * jd.height * 2;
#ifdef UI_SIM
        s.out = malloc(bytes);
#else
        s.out = heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (!s.out) s.out = malloc(bytes);
#endif
        s.w = jd.width;
        if (s.out && jd_decomp(&jd, out_cb, 0) == JDR_OK) {
            out->header.magic = LV_IMAGE_HEADER_MAGIC;
            out->header.cf = LV_COLOR_FORMAT_RGB565;
            out->header.w = jd.width;
            out->header.h = jd.height;
            out->header.stride = jd.width * 2;
            out->data_size = bytes;
            out->data = (const uint8_t *)s.out;
            ok = true;
        } else {
            free(s.out);
        }
    }
    free(pool);
    return ok;
}

void jpg_free(lv_image_dsc_t *img)
{
    if (img->data) free((void *)img->data);
    memset(img, 0, sizeof *img);
}
