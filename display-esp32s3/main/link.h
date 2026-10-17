#pragma once
#include <stdbool.h>
#include <stdint.h>

#define LINK_BANDS 32
#define LINK_MAX_FOUND 8

typedef struct {
    char id[48], name[40], ip[16], ver[16];
    bool playing;
} speaker_t;

typedef enum { LINK_OFFLINE, LINK_SEARCHING, LINK_CHOOSE, LINK_CONNECTED, LINK_LOST } link_phase_t;

typedef struct {
    uint32_t seq;          // bumped on every new state
    link_phase_t phase;
    char name[40], ip[16], ver[16];
    char vs[12], emo[16];
    bool awake, mute;
    int vol;
    char user[900], bot[900];
    bool has_m;
    char ms[14], ti[400], ar[260], src[12];
    char vid[12], mu[80];  // a song from the music server: its video id and the server's address
    int pos, dur;
    bool has_wx, wx_day;
    int wx_t, wx_c;
    char wx_d[64], wx_city[80];
    char al_next[80], al_ring[120];
    int group;
    int64_t got_us;        // esp_timer time of this state
} spk_state_t;

void link_init(void);
/* Copy the latest state when it changed since *seq (true) — cheap to call often. */
bool link_state(spk_state_t *out, uint32_t *seq);
/* The latest spectrum, 0..255 per band; false when nothing is playing. */
bool link_spectrum(uint8_t out[LINK_BANDS]);
/* Speakers found by the last search. */
int link_found(speaker_t *out, int max);
void link_choose(const char *id);     // follow this speaker (saved)
void link_research(void);             // forget the choice and search again
void link_cmd(const char *c);         // wake, sleep, pause, resume, next, stop, vol+, vol-, alarm_stop

/* ── the menu's data: radio, podcasts, episodes, alarms (the speaker answers over the same link) ── */
#define LINK_LIST_MAX 20
#define LINK_ALARM_MAX 12
typedef struct { char id[32], n[128]; } li_item_t;
typedef struct {
    uint32_t seq;
    char kind[10];         // radio, podcast, episodes
    char id[40];           // the show, for episodes
    int count;
    li_item_t it[LINK_LIST_MAX];
} link_list_t;
typedef struct { char id[20]; bool on; char time[8]; uint8_t days; char label[44]; } li_alarm_t;   // days: bit0 = Monday
typedef struct {
    uint32_t seq;
    int count;
    li_alarm_t a[LINK_ALARM_MAX];
    char next[80];
} link_alarms_t;
typedef struct { uint32_t seq; bool ok; char msg[100]; } link_res_t;

void link_request(const char *kind, const char *id);          // "radio" | "podcast" | "alarms" | "episodes" (id = show)
void link_play_radio(const char *id);
void link_play_episode(int n);                                 // n-th of the last episodes list
void link_play_music(const char *query);
void link_alarm_toggle(const char *id, bool on);
void link_alarm_save(const char *id, const char *time, uint8_t days, const char *label);   // id "" = new
void link_alarm_del(const char *id);
bool link_get_list(link_list_t *out, uint32_t *seq);
bool link_get_alarms(link_alarms_t *out, uint32_t *seq);
bool link_get_res(link_res_t *out, uint32_t *seq);
