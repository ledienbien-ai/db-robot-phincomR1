#pragma once
/*
 * Pictures from the music server for the song that is playing: GET <server>/video_mjpeg?id=&ss=
 * answers with a multipart stream of 320x240 baseline JPEGs (about 10 per second, no sound — the
 * speaker plays that). A task reads it and decodes each frame with the ROM TJpgDec into RGB565.
 */
#include <stdbool.h>
#include <stdint.h>

typedef enum { VID_IDLE, VID_LOADING, VID_PLAYING, VID_ERROR } vid_state_t;

void video_start(const char *server, const char *video_id, int start_s);
void video_stop(void);
vid_state_t video_state(char *msg, int n);             // msg: why it failed, when it did
/* The newest finished frame (RGB565, 320x240, LVGL byte order); NULL until one has arrived or
   when it is the same one as *seq already saw. Valid until the next call. */
const uint16_t *video_frame(uint32_t *seq);
