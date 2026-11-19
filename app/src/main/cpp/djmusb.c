// Userspace isochronous capture for Pioneer DJM mixers via usbfs.
// Android's UsbDeviceConnection has no isochronous API, so we drive the
// raw device fd with USBDEVFS ioctls. Interface claim, altsetting and the
// vendor control requests are done on the Kotlin side before nativeArm().
//
// Two lifetimes live in here:
//   engine   (nativeArm .. nativeDisarm): fd, URBs, capture thread, meters, wave ring,
//            gap detection and the pre-roll rings. Runs "armed": audio flows, nothing is on disk.
//   session  (nativeRecordStart .. nativeRecordStop): the WAV files and a writer thread.
//            The capture thread only pushes into lock-free byte queues; every write(), header
//            refresh, RF64 switch and ENOSPC decision happens on the writer thread.
//            A session survives an engine restart (mixer unplugged and plugged back in).
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <poll.h>
#include <pthread.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdatomic.h>
#include <math.h>
#include <android/log.h>

#define TAG "djmusb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define IN_URBS 8
#define OUT_URBS 16
#define PKTS 32          // iso packets per URB
#define MAX_CH 16
#define BYTES 3          // S24_3LE
#define WAVE_RING 4096   // 10 ms entries of (low, mid, high, left, right)
#define WAVE_STRIDE 5
#define MON_RING 16384   // monitor ring, stereo float frames
#define EV_RING 256
#define CTR_RING 64
#define HDR_LEN 80       // RIFF/RF64 header + 36 byte JUNK/ds64 + fmt + data header
#define STEM_PRE_CAP_BYTES (32u * 1024u * 1024u)
#define Q_STEREO_BYTES (8u * 1024u * 1024u)
#define Q_MULTI_BYTES (16u * 1024u * 1024u)
#define RF64_DEFAULT_LIMIT 0xFFFFFF00ull
#define WALL_WIN_S 0.25

enum { MODE_ARMED = 0, MODE_REC = 1 };
enum { RS_IDLE = 0, RS_ACTIVE = 1 };
enum { CMD_NONE = 0, CMD_REC_START = 1, CMD_REC_STOP = 2 };

typedef struct {
    struct usbdevfs_urb *urb;
    int is_out;
    int busy;
} slot_t;

// ---------------------------------------------------------------- record session

typedef struct {
    int fd;
    int fsz;                     // bytes per frame
    uint32_t rate;
    uint16_t channels;
    // writer-thread owned
    uint64_t data_bytes;
    int rf64;
    atomic_int pre_done;
    // published for stats
    atomic_ullong data_pub;
    atomic_int err;              // errno once a write failed (ENOSPC...), then the file is frozen
    // lock-free byte ring, producer = capture thread, consumer = writer thread
    uint8_t *q;
    uint64_t cap;
    atomic_ullong qw, qr;
    // producer-owned backlog when the ring was full: frames dropped, replaced by silence later
    uint64_t debt;               // bytes
    uint64_t debt_pos;           // record position (frames) where the loss began
    uint64_t debt_frames;
    // pre-roll hand-over (set by capture thread, then pre_go released)
    const uint8_t *pre;
    uint64_t pre_start, pre_n, pre_cap, pre_pad;   // frames
    atomic_int pre_go;
    // sparse gap (silence for the time the mixer was unplugged), executed by writer when idle
    atomic_ullong gap_req;       // bytes
} rfile_t;

static struct {
    atomic_int state;
    rfile_t stereo, multi;
    int has_multi;
    pthread_t writer;
    int writer_on;
    atomic_int stop;
    atomic_ullong rec_frames;
    atomic_ullong lost_frames;
    uint64_t rf64_limit;
} rs;

// ---------------------------------------------------------------- engine

static struct {
    int fd;
    int ep_in, ep_out;
    int maxpkt_in, maxpkt_out;
    int ch_in, ch_out;
    int rate;
    int stereo_pair;
    slot_t in[IN_URBS], out[OUT_URBS];
    pthread_t thread;
    atomic_int running;
    atomic_int thread_alive;
    atomic_int dev_gone;
    int started;
    int mode;                    // capture thread only (read elsewhere via stats)
    atomic_int mode_pub;
    atomic_llong frames, urbs, pkt_err, odd_pkts, out_urbs, out_err;
    atomic_int last_errno;
    atomic_int peak[MAX_CH];
    // 3-band waveform of the stereo pair (capture thread writes, JNI reader drains)
    float lp_lo, lp_hi, a_lo, a_hi;
    int wave_win, wave_n;
    float pk_lo, pk_mid, pk_hi, pk_l, pk_r;
    uint8_t wave[WAVE_RING * WAVE_STRIDE];
    atomic_llong wave_w;
    long long wave_r;
    // live monitoring tap of the stereo pair (capture thread writes, the monitor thread reads)
    atomic_int mon_on;
    atomic_llong mon_w, mon_r;
    float mon[MON_RING * 2];
    // command mailbox (API thread -> capture thread)
    atomic_int cmd;
    int cmd_req_pre;
    int64_t cmd_res[4];
    // pre-roll rings (overwriting, only filled while armed)
    uint8_t *pre_s, *pre_m;
    uint64_t pre_s_cap, pre_m_cap;     // frames
    uint64_t pre_s_wr, pre_m_wr;       // frames written since reset
    atomic_ullong pre_s_pub, pre_m_pub;
    // gap detection
    int streaming;
    uint64_t good_pkts, good_frames, pkt_seen, empty_seen;
    int empty_normal;                  // empty packets are part of the normal stream: don't treat as loss
    uint64_t gap_pkts;                 // missing packets not yet turned into silence
    atomic_llong gap_events, gap_frames;
    int sf_have, sf_prev, sf_ok, sf_exp, sf_cand, sf_eq;
    atomic_llong sf_jumps, sf_rejected;
    int wc_init;
    double wc_t0, wc_win_start, wc_min, wc_base, wc_cand;
    int wc_cand_on;
    // dropout events handed to the app: (record position in frames, missing frames)
    int64_t ev[EV_RING][2];
    atomic_llong ev_w;
    long long ev_r;
    // diagnostics only: sample counter on capture ch 11/12 (the Teensy fake mixer has one)
    int ctr_have, ctr_valid;
    uint32_t ctr_prev, ctr_run;
    uint64_t real_frames;
    atomic_llong ctr_missing, ctr_mismatch, ctr_valid_pub;
    int64_t ctr[CTR_RING][3];
    atomic_llong ctr_w;
    long long ctr_r;
} g;

static uint8_t zeros[512 * MAX_CH * BYTES];
static pthread_mutex_t api_mu = PTHREAD_MUTEX_INITIALIZER;

static void put32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static void put16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
static void put64(uint8_t *p, uint64_t v) { put32(p, (uint32_t) v); put32(p + 4, (uint32_t) (v >> 32)); }

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

static void sleep_ms(int ms) {
    struct timespec ts = {ms / 1000, (long) (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

// ---------------------------------------------------------------- WAV / RF64 writer

// Layout (always 80 bytes, so the data offset never moves):
//   RIFF <size> WAVE | JUNK 28 <28 zero bytes> | fmt 16 <16> | data <size>
// At 4 GB the same bytes are rewritten as:
//   RF64 0xFFFFFFFF WAVE | ds64 28 <riffSize u64, dataSize u64, sampleCount u64, tableLen u32=0> | fmt | data 0xFFFFFFFF
static void rf_build_header(const rfile_t *f, uint8_t h[HDR_LEN]) {
    memset(h, 0, HDR_LEN);
    uint64_t d = f->data_bytes;
    d -= d % (uint64_t) f->fsz;
    if (f->rf64) {
        memcpy(h, "RF64", 4); put32(h + 4, 0xFFFFFFFFu);
        memcpy(h + 8, "WAVE", 4);
        memcpy(h + 12, "ds64", 4); put32(h + 16, 28);
        put64(h + 20, 72 + d);                 // RIFF size = file size - 8
        put64(h + 28, d);                      // data size
        put64(h + 36, d / (uint64_t) f->fsz);  // sample count (frames)
        put32(h + 44, 0);                      // table length
    } else {
        memcpy(h, "RIFF", 4); put32(h + 4, (uint32_t) (72 + d));
        memcpy(h + 8, "WAVE", 4);
        memcpy(h + 12, "JUNK", 4); put32(h + 16, 28);
    }
    memcpy(h + 48, "fmt ", 4); put32(h + 52, 16);
    put16(h + 56, 1); put16(h + 58, f->channels);
    put32(h + 60, f->rate); put32(h + 64, f->rate * f->channels * BYTES);
    put16(h + 68, f->channels * BYTES); put16(h + 70, 24);
    memcpy(h + 72, "data", 4); put32(h + 76, f->rf64 ? 0xFFFFFFFFu : (uint32_t) d);
}

static void rf_write_header(rfile_t *f) {
    if (f->fd < 0) return;
    uint8_t h[HDR_LEN];
    rf_build_header(f, h);
    if (pwrite(f->fd, h, HDR_LEN, 0) != HDR_LEN) { /* header area already exists on disk; best effort */ }
}

static void rf_to_rf64(rfile_t *f) {
    f->rf64 = 1;
    rf_write_header(f);
    LOGI("file fd %d switched to RF64 at %llu data bytes", f->fd, (unsigned long long) f->data_bytes);
}

static int rf_open(rfile_t *f, const char *path, int rate, int ch, uint64_t qbytes) {
    memset(f, 0, sizeof(*f));
    f->fd = -1;
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return -errno;
    f->fsz = ch * BYTES; f->rate = rate; f->channels = ch;
    f->cap = qbytes - qbytes % (uint64_t) f->fsz;
    f->q = malloc(f->cap);
    if (!f->q) { close(fd); return -ENOMEM; }
    f->fd = fd;
    rf_write_header(f);
    if (lseek(fd, HDR_LEN, SEEK_SET) < 0) { int e = errno; close(fd); free(f->q); f->q = NULL; f->fd = -1; return -e; }
    return 0;
}

static void rf_fail(rfile_t *f, int e) {
    if (!atomic_load(&f->err)) { atomic_store(&f->err, e); LOGE("write failed on fd %d: errno %d (file frozen)", f->fd, e); }
}

static void rf_publish(rfile_t *f) { atomic_store_explicit(&f->data_pub, f->data_bytes, memory_order_relaxed); }

// Appends n bytes (multiple of the frame size) to the file. Switches to RF64 before crossing the 32-bit limit.
static int rf_write_data(rfile_t *f, const uint8_t *p, size_t n) {
    if (atomic_load(&f->err)) return -1;
    if (!f->rf64 && f->data_bytes + n > rs.rf64_limit) rf_to_rf64(f);
    while (n) {
        ssize_t w = write(f->fd, p, n);
        if (w < 0) { if (errno == EINTR) continue; rf_fail(f, errno); return -1; }
        p += w; n -= (size_t) w; f->data_bytes += (uint64_t) w;
    }
    rf_publish(f);
    return 0;
}

// Silence without writing it: extend the file (sparse hole reads back as zeros).
static void rf_skip(rfile_t *f, uint64_t bytes) {
    if (atomic_load(&f->err) || !bytes) return;
    if (!f->rf64 && f->data_bytes + bytes > rs.rf64_limit) rf_to_rf64(f);
    uint64_t pos = HDR_LEN + f->data_bytes + bytes;
    if (ftruncate(f->fd, (off_t) pos) < 0) { rf_fail(f, errno); return; }
    if (lseek(f->fd, (off_t) pos, SEEK_SET) < 0) { rf_fail(f, errno); return; }
    f->data_bytes += bytes;
    rf_publish(f);
}

// One slice of work for one file. Returns 1 if it did something.
static int rf_service(rfile_t *f) {
    if (f->fd < 0) return 0;
    if (!atomic_load(&f->pre_done)) {
        if (!atomic_load_explicit(&f->pre_go, memory_order_acquire)) return 0;
        if (f->pre_pad) { rf_skip(f, f->pre_pad * (uint64_t) f->fsz); f->pre_pad = 0; }
        if (f->pre_n) {
            uint64_t idx = f->pre_start % f->pre_cap;
            uint64_t n = f->pre_n;
            uint64_t lim = (1u << 20) / (uint64_t) f->fsz;
            if (n > lim) n = lim;
            if (n > f->pre_cap - idx) n = f->pre_cap - idx;
            rf_write_data(f, f->pre + idx * (uint64_t) f->fsz, (size_t) (n * (uint64_t) f->fsz));
            f->pre_start += n; f->pre_n -= n;
            if (f->pre_n) return 1;
        }
        atomic_store(&f->pre_done, 1);
        return 1;
    }
    uint64_t r = atomic_load_explicit(&f->qr, memory_order_relaxed);
    uint64_t w = atomic_load_explicit(&f->qw, memory_order_acquire);
    if (r != w) {
        uint64_t idx = r % f->cap;
        uint64_t n = w - r;
        if (n > (1u << 20)) n = 1u << 20;
        if (n > f->cap - idx) n = f->cap - idx;
        n -= n % (uint64_t) f->fsz;
        if (n) rf_write_data(f, f->q + idx, (size_t) n);   // on error: data is discarded, queue keeps draining
        atomic_store_explicit(&f->qr, r + (n ? n : (w - r)), memory_order_release);
        return 1;
    }
    uint64_t gr = atomic_load(&f->gap_req);
    if (gr) { rf_skip(f, gr); atomic_store(&f->gap_req, 0); return 1; }
    return 0;
}

static void rf_finalize(rfile_t *f) {
    if (f->fd < 0) return;
    // a failed write may have left a partial frame: cut back to a frame boundary so the file stays valid
    uint64_t d = f->data_bytes - f->data_bytes % (uint64_t) f->fsz;
    if (atomic_load(&f->err)) { f->data_bytes = d; if (ftruncate(f->fd, (off_t) (HDR_LEN + d)) < 0) { } }
    rf_write_header(f);
    fsync(f->fd);
    close(f->fd);
    f->fd = -1;
    rf_publish(f);
    free(f->q); f->q = NULL;
}

static int rf_idle(rfile_t *f) {
    if (f->fd < 0) return 1;
    return atomic_load(&f->pre_done) && atomic_load(&f->qr) == atomic_load(&f->qw) && !atomic_load(&f->gap_req);
}

static void *writer_main(void *arg) {
    (void) arg;
    double last_hdr = now_s();
    for (;;) {
        int did = rf_service(&rs.stereo);
        if (rs.has_multi) did |= rf_service(&rs.multi);
        double t = now_s();
        if (t - last_hdr >= 1.0) {          // keep headers valid about once a second
            last_hdr = t;
            rf_write_header(&rs.stereo);
            if (rs.has_multi) rf_write_header(&rs.multi);
        }
        if (atomic_load(&rs.stop) && rf_idle(&rs.stereo) && (!rs.has_multi || rf_idle(&rs.multi))) break;
        if (!did) sleep_ms(4);
    }
    rf_finalize(&rs.stereo);
    if (rs.has_multi) rf_finalize(&rs.multi);
    return NULL;
}

// ---------------------------------------------------------------- events

static void ev_push(uint64_t pos, uint64_t frames) {
    long long w = atomic_load_explicit(&g.ev_w, memory_order_relaxed);
    g.ev[w % EV_RING][0] = (int64_t) pos;
    g.ev[w % EV_RING][1] = (int64_t) frames;
    atomic_store_explicit(&g.ev_w, w + 1, memory_order_release);
}

// ---------------------------------------------------------------- queue producer (capture thread)

static void q_zero(rfile_t *f, uint64_t w, uint64_t n) {
    uint64_t idx = w % f->cap;
    uint64_t first = n < f->cap - idx ? n : f->cap - idx;
    memset(f->q + idx, 0, (size_t) first);
    if (first < n) memset(f->q, 0, (size_t) (n - first));
}

static void q_copy(rfile_t *f, uint64_t w, const uint8_t *d, uint64_t n) {
    uint64_t idx = w % f->cap;
    uint64_t first = n < f->cap - idx ? n : f->cap - idx;
    memcpy(f->q + idx, d, (size_t) first);
    if (first < n) memcpy(f->q, d + first, (size_t) (n - first));
}

// n bytes = whole frames. If the writer cannot keep up the frames are not lost silently:
// they become a "debt" that is paid back as silence as soon as the queue has room, so the file keeps its timeline.
static void q_push(rfile_t *f, const uint8_t *d, uint64_t n, int primary) {
    uint64_t w = atomic_load_explicit(&f->qw, memory_order_relaxed);
    uint64_t r = atomic_load_explicit(&f->qr, memory_order_acquire);
    uint64_t room = f->cap - (w - r);
    uint64_t fsz = (uint64_t) f->fsz;
    if (f->debt) {
        uint64_t z = f->debt < room ? f->debt : room;
        z -= z % fsz;
        if (z) { q_zero(f, w, z); w += z; room -= z; f->debt -= z; atomic_store_explicit(&f->qw, w, memory_order_release); }
        if (f->debt) { f->debt += n; f->debt_frames += n / fsz; atomic_fetch_add(&rs.lost_frames, n / fsz); return; }
        if (primary) ev_push(f->debt_pos, f->debt_frames);
        LOGE("queue overrun recovered: %llu frames replaced by silence", (unsigned long long) f->debt_frames);
        f->debt_frames = 0;
    }
    if (room < n) {
        f->debt = n; f->debt_frames = n / fsz; f->debt_pos = atomic_load(&rs.rec_frames);
        atomic_fetch_add(&rs.lost_frames, n / fsz);
        return;
    }
    q_copy(f, w, d, n);
    atomic_store_explicit(&f->qw, w + n, memory_order_release);
}

// ---------------------------------------------------------------- audio path (capture thread)

static inline uint8_t wave_enc(float pk) {
    if (pk > 1.0f) pk = 1.0f;
    return (uint8_t) (sqrtf(pk) * 255.0f + 0.5f);
}

// m = (L+R)/2 in -1..1. low <~200 Hz, high >~2.5 kHz, mid = remainder (one-pole filters).
static inline void wave_feed(float m, float l, float r) {
    g.lp_lo += g.a_lo * (m - g.lp_lo);
    { float al = fabsf(l), ar = fabsf(r); if (al > g.pk_l) g.pk_l = al; if (ar > g.pk_r) g.pk_r = ar; }
    g.lp_hi += g.a_hi * (m - g.lp_hi);
    float lo = fabsf(g.lp_lo), hi = fabsf(m - g.lp_hi), mi = fabsf(g.lp_hi - g.lp_lo);
    if (lo > g.pk_lo) g.pk_lo = lo;
    if (mi > g.pk_mid) g.pk_mid = mi;
    if (hi > g.pk_hi) g.pk_hi = hi;
    if (++g.wave_n >= g.wave_win) {
        long long w = atomic_load_explicit(&g.wave_w, memory_order_relaxed);
        uint8_t *e = g.wave + (size_t) (w % WAVE_RING) * WAVE_STRIDE;
        e[0] = wave_enc(g.pk_lo); e[1] = wave_enc(g.pk_mid); e[2] = wave_enc(g.pk_hi);
        e[3] = wave_enc(g.pk_l); e[4] = wave_enc(g.pk_r);
        atomic_store_explicit(&g.wave_w, w + 1, memory_order_release);
        g.wave_n = 0; g.pk_lo = g.pk_mid = g.pk_hi = g.pk_l = g.pk_r = 0.0f;
    }
}

static inline uint32_t u24(const uint8_t *p) { return p[0] | (p[1] << 8) | ((uint32_t) p[2] << 16); }

// Diagnostics only. Looks for the sample counter the fake mixer puts on ch 11/12 (ch11 = bits 0-23,
// ch12 = bits 24-31). The real mixer has none, so this stays dormant unless 256 frames in a row count up.
static void counter_check(const uint8_t *p) {
    uint32_t c = u24(p + 30) | ((u24(p + 33) & 0xFF) << 24);
    if (g.ctr_have) {
        if (c == g.ctr_prev + 1) { if (g.ctr_run < 0xFFFFFFF0u) g.ctr_run++; }
        else {
            if (g.ctr_valid) {
                long long k = atomic_load_explicit(&g.ctr_w, memory_order_relaxed);
                g.ctr[k % CTR_RING][0] = (int64_t) g.real_frames;
                g.ctr[k % CTR_RING][1] = (int64_t) (g.ctr_prev + 1);
                g.ctr[k % CTR_RING][2] = (int64_t) c;
                atomic_store_explicit(&g.ctr_w, k + 1, memory_order_release);
                atomic_fetch_add(&g.ctr_mismatch, 1);
                int32_t miss = (int32_t) (c - g.ctr_prev - 1);
                if (miss > 0) atomic_fetch_add(&g.ctr_missing, miss);
            }
            g.ctr_run = 0;
        }
    }
    if (!g.ctr_valid && g.ctr_run >= 256) { g.ctr_valid = 1; atomic_store(&g.ctr_valid_pub, 1); }
    g.ctr_prev = c; g.ctr_have = 1;
}

// The pre-roll rings may be overwritten unless a session is still reading them (between hand-over and pre_done).
static int ring_free(void) {
    if (atomic_load(&rs.state) == RS_IDLE) return 1;
    if (!atomic_load(&rs.stereo.pre_go)) return 1;          // session opened but the command was not taken yet
    return atomic_load(&rs.stereo.pre_done) && (!rs.has_multi || atomic_load(&rs.multi.pre_done));
}

static void sink(const uint8_t *d, const uint8_t *st, int frames, int fs) {
    if (g.mode == MODE_REC) {
        q_push(&rs.stereo, st, (uint64_t) frames * 6, 1);
        if (rs.has_multi) q_push(&rs.multi, d, (uint64_t) frames * fs, 0);
        atomic_fetch_add(&rs.rec_frames, frames);
        return;
    }
    // armed: keep the last N seconds in the overwriting rings (only while no session is still reading them)
    if (!ring_free()) { g.pre_s_wr = 0; g.pre_m_wr = 0; return; }
    if (g.pre_s) {
        uint64_t idx = g.pre_s_wr % g.pre_s_cap;
        uint64_t first = (uint64_t) frames < g.pre_s_cap - idx ? (uint64_t) frames : g.pre_s_cap - idx;
        memcpy(g.pre_s + idx * 6, st, (size_t) first * 6);
        if (first < (uint64_t) frames) memcpy(g.pre_s, st + first * 6, ((size_t) frames - first) * 6);
        g.pre_s_wr += frames;
        atomic_store_explicit(&g.pre_s_pub, g.pre_s_wr, memory_order_relaxed);
    }
    if (g.pre_m) {
        uint64_t idx = g.pre_m_wr % g.pre_m_cap;
        uint64_t first = (uint64_t) frames < g.pre_m_cap - idx ? (uint64_t) frames : g.pre_m_cap - idx;
        memcpy(g.pre_m + idx * (uint64_t) fs, d, (size_t) first * fs);
        if (first < (uint64_t) frames) memcpy(g.pre_m, d + first * fs, ((size_t) frames - first) * fs);
        g.pre_m_wr += frames;
        atomic_store_explicit(&g.pre_m_pub, g.pre_m_wr, memory_order_relaxed);
    }
}

// real = 0: silence standing in for missing time (same path, so meters/wave/rings/files stay on the timeline)
static void process(const uint8_t *d, int frames, int real) {
    int fs = g.ch_in * BYTES;
    uint8_t st[2 * BYTES * 512];
    int so = g.stereo_pair * 2 * BYTES;
    int mon = atomic_load_explicit(&g.mon_on, memory_order_relaxed);
    long long mw = atomic_load_explicit(&g.mon_w, memory_order_relaxed);
    for (int f = 0; f < frames; f++) {
        const uint8_t *p = d + (size_t) f * fs;
        if (real) {
            for (int c = 0; c < g.ch_in; c++) {
                int32_t v = p[c * 3] | (p[c * 3 + 1] << 8) | ((int8_t) p[c * 3 + 2] << 16);
                if (v < 0) v = -v;
                if (v > atomic_load_explicit(&g.peak[c], memory_order_relaxed))
                    atomic_store_explicit(&g.peak[c], v, memory_order_relaxed);
            }
            if (g.ch_in >= 12) counter_check(p);
            g.real_frames++;
        }
        const uint8_t *q = p + so;
        int32_t wl = q[0] | (q[1] << 8) | ((int8_t) q[2] << 16);
        int32_t wr = q[3] | (q[4] << 8) | ((int8_t) q[5] << 16);
        float fl = (float) wl * (1.0f / 8388608.0f), fr = (float) wr * (1.0f / 8388608.0f);
        wave_feed((float) (wl + wr) * (0.5f / 8388608.0f), fl, fr);
        if (mon) { float *mp = g.mon + (size_t) (mw % MON_RING) * 2; mp[0] = fl; mp[1] = fr; mw++; }
        memcpy(st + f * 6, q, 6);
    }
    if (mon) atomic_store_explicit(&g.mon_w, mw, memory_order_release);
    sink(d, st, frames, fs);
    atomic_fetch_add(&g.frames, frames);
}

// ---------------------------------------------------------------- gaps

static double fp_est(void) {
    if (g.good_pkts >= 64) return (double) g.good_frames / (double) g.good_pkts;
    return g.rate / 8000.0;
}

// Fills n frames of silence at the current position and tells the app (events only matter while recording).
static void inject_gap(long n, const char *why) {
    if (n <= 0) return;
    if (g.mode == MODE_REC) ev_push(atomic_load(&rs.rec_frames), (uint64_t) n);
    atomic_fetch_add(&g.gap_events, 1);
    atomic_fetch_add(&g.gap_frames, n);
    if (atomic_load(&g.gap_events) <= 50) LOGE("gap (%s): %ld frames of silence at frame %lld", why, n, (long long) atomic_load(&g.frames));
    long left = n;
    while (left > 0) {
        int c = left > 512 ? 512 : (int) left;
        process(zeros, c, 0);
        left -= c;
    }
}

static void gap_flush(void) {
    if (!g.gap_pkts) return;
    long n = (long) ((double) g.gap_pkts * fp_est() + 0.5);
    g.gap_pkts = 0;
    inject_gap(n, "missing packets");
}

// start_frame bookkeeping. Units differ between host controllers (micro-frames on xhci/ehci at high speed),
// so the expected step between two consecutive URBs is learned from 16 equal steps first. A jump is only
// trusted when the wall clock agrees that the time really went missing.
static void sf_check(struct usbdevfs_urb *u, long url_frames) {
    int sf = u->start_frame;
    if (!g.sf_have) { g.sf_prev = sf; g.sf_have = 1; return; }
    int d = (sf - g.sf_prev) & 1023;
    g.sf_prev = sf;
    if (!g.sf_ok) {
        if (d > 0 && d == g.sf_cand) {
            if (++g.sf_eq >= 16) { g.sf_ok = 1; g.sf_exp = d; LOGI("start_frame step learned: %d units per URB", d); }
        } else { g.sf_cand = d; g.sf_eq = 1; }
        return;
    }
    if (d <= g.sf_exp) return;
    int miss_units = d - g.sf_exp;
    double per_unit = fp_est() * PKTS / (double) g.sf_exp;       // frames per start_frame unit
    long M = (long) ((double) miss_units * per_unit + 0.5);
    if (M <= 0) return;
    if (!g.wc_init) { atomic_fetch_add(&g.sf_rejected, 1); return; }
    double recv = (double) atomic_load(&g.frames) + (double) g.gap_pkts * fp_est();
    double def = (now_s() - g.wc_t0) * g.rate - recv - (double) url_frames - g.wc_base;
    if (def >= 0.5 * (double) M) {
        atomic_fetch_add(&g.sf_jumps, 1);
        gap_flush();
        inject_gap(M, "start_frame jump");
    } else atomic_fetch_add(&g.sf_rejected, 1);
}

// Sliding-window check against CLOCK_MONOTONIC, evaluated once the reap loop drained everything that completed.
// The minimum deficit of a 250 ms window is robust against late wake-ups; drift is followed by re-basing
// every window that did not lose time. A loss must show in two consecutive windows before silence is added.
static void wall_check(void) {
    if (!g.streaming) return;
    double t = now_s();
    double recv = (double) atomic_load(&g.frames) + (double) g.gap_pkts * fp_est();
    if (!g.wc_init) {
        g.wc_t0 = t - recv / g.rate; g.wc_init = 1; g.wc_win_start = t; g.wc_min = 1e18; g.wc_base = 0; g.wc_cand_on = 0;
        return;
    }
    double def = (t - g.wc_t0) * g.rate - recv;
    if (def < g.wc_min) g.wc_min = def;
    if (t - g.wc_win_start < WALL_WIN_S) return;
    double rel = g.wc_min - g.wc_base;
    g.wc_win_start = t; g.wc_min = 1e18;
    double thr = g.rate * 0.001;                       // 1 ms of audio
    if (rel <= thr) { g.wc_base = g.wc_base + rel; g.wc_cand_on = 0; return; }
    if (g.wc_cand_on) {
        double loss = rel < g.wc_cand ? rel : g.wc_cand;
        g.wc_cand_on = 0;
        gap_flush();
        inject_gap((long) (loss + 0.5), "wall clock");
    } else { g.wc_cand = rel; g.wc_cand_on = 1; }
}

// ---------------------------------------------------------------- URB handling

static struct usbdevfs_urb *urb_alloc(int ep, int maxpkt, slot_t *slot) {
    struct usbdevfs_urb *u = calloc(1, sizeof(*u) + PKTS * sizeof(struct usbdevfs_iso_packet_desc));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = ep;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = calloc(PKTS, maxpkt);
    u->buffer_length = PKTS * maxpkt;
    u->number_of_packets = PKTS;
    u->usercontext = slot;
    for (int i = 0; i < PKTS; i++) u->iso_frame_desc[i].length = maxpkt;
    return u;
}

static int submit(slot_t *s) {
    if (ioctl(g.fd, USBDEVFS_SUBMITURB, s->urb) < 0) {
        atomic_store(&g.last_errno, errno);
        if (errno == ENODEV) atomic_store(&g.dev_gone, 1);
        s->busy = 0;
        return -errno;
    }
    s->busy = 1;
    return 0;
}

static slot_t *free_out(void) {
    for (int i = 0; i < OUT_URBS; i++) if (!g.out[i].busy) return &g.out[i];
    return NULL;
}

static void handle_in(slot_t *s) {
    struct usbdevfs_urb *u = s->urb;
    int fs = g.ch_in * BYTES;
    int nframes[PKTS];     // frames received per packet (0 for empty or failed packets)
    int missing[PKTS];     // 1 = this packet slot carried no usable audio
    const uint8_t *buf = u->buffer;
    long total = 0;
    if (u->status == -ENODEV || u->status == -ESHUTDOWN) atomic_store(&g.dev_gone, 1);
    for (int i = 0; i < PKTS; i++) {
        struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
        nframes[i] = 0; missing[i] = 0;
        if (d->status != 0) { atomic_fetch_add(&g.pkt_err, 1); missing[i] = 2; continue; }
        int len = d->actual_length;
        if (len % fs) atomic_fetch_add(&g.odd_pkts, 1);
        nframes[i] = len / fs;
        if (nframes[i] > 512) nframes[i] = 512;
        if (!nframes[i]) missing[i] = 1;
        total += nframes[i];
    }
    if (u->status != -ENODEV && u->status != -ESHUTDOWN) sf_check(u, total);

    for (int i = 0; i < PKTS; i++) {
        if (nframes[i]) {
            gap_flush();
            process(buf + (size_t) i * g.maxpkt_in, nframes[i], 1);
            g.good_pkts++; g.good_frames += (uint64_t) nframes[i];
            g.streaming = 1;
        }
        if (!g.streaming) continue;
        g.pkt_seen++;
        if (missing[i]) g.empty_seen++;
        // 2000 packets (~250 ms at high speed) decide whether empty packets are normal for this device
        if (g.pkt_seen == 2000) {
            g.empty_normal = g.empty_seen * 50 > g.pkt_seen;
            if (g.empty_normal) LOGI("empty packets are normal on this stream; empty-packet gap detection off");
        }
        // failed packets always count as lost time; zero-length ones only once the stream proved they are abnormal
        if (missing[i] == 2 || (missing[i] == 1 && g.pkt_seen > 2000 && !g.empty_normal)) g.gap_pkts++;
    }
    atomic_fetch_add(&g.urbs, 1);

    if (atomic_load(&g.running)) {
        for (int i = 0; i < PKTS; i++) u->iso_frame_desc[i].length = g.maxpkt_in;
        submit(s);
    } else s->busy = 0;

    // Implicit feedback: send back exactly as many (silent) frames as we got.
    if (g.ep_out && atomic_load(&g.running)) {
        slot_t *o = free_out();
        if (o) {
            int ofs = g.ch_out * BYTES, tot = 0;
            for (int i = 0; i < PKTS; i++) {
                int l = nframes[i] * ofs;
                if (l > g.maxpkt_out) l = g.maxpkt_out - (g.maxpkt_out % ofs);
                o->urb->iso_frame_desc[i].length = l;
                tot += l;
            }
            o->urb->buffer_length = tot;
            if (submit(o) == 0) atomic_fetch_add(&g.out_urbs, 1);
            else atomic_fetch_add(&g.out_err, 1);
        }
    }
}

// Commands from the API thread, executed here so the capture thread never needs a lock.
static void handle_cmd(void) {
    int c = atomic_load_explicit(&g.cmd, memory_order_acquire);
    if (!c) return;
    if (c == CMD_REC_START) {
        int64_t res = -EBUSY, ps = 0, pm = 0;
        if (g.mode == MODE_ARMED && atomic_load(&rs.state) == RS_ACTIVE) {
            uint64_t req = (uint64_t) g.cmd_req_pre;
            uint64_t vs = g.pre_s ? (g.pre_s_wr < g.pre_s_cap ? g.pre_s_wr : g.pre_s_cap) : 0;
            if (vs > req) vs = req;
            uint64_t vm = (g.pre_m && rs.has_multi) ? (g.pre_m_wr < g.pre_m_cap ? g.pre_m_wr : g.pre_m_cap) : 0;
            if (vm > vs) vm = vs;
            rs.stereo.pre = g.pre_s; rs.stereo.pre_cap = g.pre_s_cap;
            rs.stereo.pre_n = vs; rs.stereo.pre_start = g.pre_s_wr - vs; rs.stereo.pre_pad = 0;
            if (rs.has_multi) {
                rs.multi.pre = g.pre_m; rs.multi.pre_cap = g.pre_m_cap;
                rs.multi.pre_n = vm; rs.multi.pre_start = g.pre_m_wr - vm; rs.multi.pre_pad = vs - vm;   // keep both files on one timeline
            }
            atomic_store(&rs.rec_frames, vs);
            g.mode = MODE_REC; atomic_store(&g.mode_pub, MODE_REC);
            atomic_store_explicit(&rs.stereo.pre_go, 1, memory_order_release);
            if (rs.has_multi) atomic_store_explicit(&rs.multi.pre_go, 1, memory_order_release);
            res = 0; ps = (int64_t) vs; pm = (int64_t) vm;
        }
        g.cmd_res[0] = res; g.cmd_res[1] = ps; g.cmd_res[2] = (int64_t) atomic_load(&g.frames); g.cmd_res[3] = pm;
    } else if (c == CMD_REC_STOP) {
        g.mode = MODE_ARMED; atomic_store(&g.mode_pub, MODE_ARMED);
        g.pre_s_wr = 0; g.pre_m_wr = 0;
        atomic_store(&g.pre_s_pub, 0); atomic_store(&g.pre_m_pub, 0);
    }
    atomic_store_explicit(&g.cmd, CMD_NONE, memory_order_release);
}

static void *loop(void *arg) {
    (void) arg;
    // keep the capture thread ahead of the UI; fails silently if the OS refuses
    if (setpriority(PRIO_PROCESS, (id_t) syscall(SYS_gettid), -19) != 0) LOGI("setpriority(-19) failed: errno %d", errno);
    else LOGI("capture thread priority -19");
    atomic_store(&g.thread_alive, 1);
    for (int i = 0; i < IN_URBS; i++) if (submit(&g.in[i]) < 0) LOGE("submit in %d: errno %d", i, atomic_load(&g.last_errno));
    int pending_exit = 0;
    while (1) {
        handle_cmd();
        int run = atomic_load(&g.running);
        if (!run && !pending_exit) {
            for (int i = 0; i < IN_URBS; i++) if (g.in[i].busy) ioctl(g.fd, USBDEVFS_DISCARDURB, g.in[i].urb);
            for (int i = 0; i < OUT_URBS; i++) if (g.out[i].busy) ioctl(g.fd, USBDEVFS_DISCARDURB, g.out[i].urb);
            pending_exit = 1;
        }
        struct pollfd p = {.fd = g.fd, .events = POLLOUT};
        int pr = poll(&p, 1, 100);
        int gone = 0, reaped = 0;
        for (;;) {
            struct usbdevfs_urb *u = NULL;
            if (ioctl(g.fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
                if (errno != EAGAIN) { atomic_store(&g.last_errno, errno); gone = (errno == ENODEV); }
                break;
            }
            slot_t *s = u->usercontext;
            if (s->is_out) { s->busy = 0; if (u->status) atomic_fetch_add(&g.out_err, 1); }
            else { handle_in(s); reaped++; }
        }
        if (reaped && !pending_exit) wall_check();
        if (gone || atomic_load(&g.dev_gone) || (pr > 0 && (p.revents & (POLLHUP | POLLERR)))) {
            if (gone || (pr > 0 && (p.revents & POLLHUP))) atomic_store(&g.dev_gone, 1);
            atomic_store(&g.running, 0); break;
        }
        if (pending_exit) {
            int busy = 0;
            for (int i = 0; i < IN_URBS; i++) busy += g.in[i].busy;
            for (int i = 0; i < OUT_URBS; i++) busy += g.out[i].busy;
            if (!busy || pending_exit++ > 10) break;
        } else {
            int busy = 0;
            for (int i = 0; i < IN_URBS; i++) busy += g.in[i].busy;
            if (!busy) { LOGE("no IN urbs in flight, stopping"); atomic_store(&g.running, 0); break; }
        }
    }
    atomic_store(&g.thread_alive, 0);
    return NULL;
}

// ---------------------------------------------------------------- session control

static int engine_alive(void) { return g.started && atomic_load(&g.thread_alive); }

static int post_cmd(int c) {
    atomic_store_explicit(&g.cmd, c, memory_order_release);
    for (int i = 0; i < 2000; i++) {                 // up to ~2 s
        if (atomic_load_explicit(&g.cmd, memory_order_acquire) == CMD_NONE) return 0;
        if (!engine_alive()) break;
        sleep_ms(1);
    }
    if (atomic_load(&g.cmd) == CMD_NONE) return 0;
    // capture thread is gone or stuck: take the command back (it is not running, so executing inline is safe)
    atomic_store(&g.cmd, CMD_NONE);
    return -ETIMEDOUT;
}

static void rs_teardown(void) {
    atomic_store(&rs.stop, 1);
    if (rs.writer_on) { pthread_join(rs.writer, NULL); rs.writer_on = 0; }
    rf_finalize(&rs.stereo);
    if (rs.has_multi) rf_finalize(&rs.multi);
    rs.has_multi = 0;
    atomic_store(&rs.state, RS_IDLE);
}

JNIEXPORT jint JNICALL
Java_app_djmrecorder_Native_nativeArm(JNIEnv *env, jobject thiz, jint fd, jint epIn, jint maxPktIn,
                                      jint chIn, jint epOut, jint maxPktOut, jint chOut, jint rate,
                                      jint stereoPair, jint preSec, jint stemsPre, jint resume, jlong gapFrames) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&api_mu);
    int ret = 0;
    if (g.started) { ret = -EBUSY; goto out; }
    if (chIn < 2 || chIn > MAX_CH || maxPktIn <= 0 || stereoPair * 2 + 2 > chIn || rate < 8000) { ret = -EINVAL; goto out; }
    if (resume && atomic_load(&rs.state) != RS_ACTIVE) { ret = -ENOTCONN; goto out; }
    memset(&g, 0, sizeof(g));
    g.fd = fd; g.ep_in = epIn; g.maxpkt_in = maxPktIn; g.ch_in = chIn;
    g.ep_out = epOut; g.maxpkt_out = maxPktOut; g.ch_out = chOut;
    g.rate = rate; g.stereo_pair = stereoPair;
    g.wave_win = rate / 100; if (g.wave_win < 1) g.wave_win = 1;
    g.a_lo = 1.0f - expf(-6.2831853f * 200.0f / (float) rate);
    g.a_hi = 1.0f - expf(-6.2831853f * 2500.0f / (float) rate);

    if (!resume && preSec > 0) {
        g.pre_s_cap = (uint64_t) preSec * (uint64_t) rate;
        g.pre_s = malloc((size_t) g.pre_s_cap * 6);
        if (!g.pre_s) { LOGE("no memory for stereo pre-roll"); g.pre_s_cap = 0; }
        if (stemsPre && g.pre_s) {
            uint64_t cap = STEM_PRE_CAP_BYTES / (uint64_t) (chIn * BYTES);
            g.pre_m_cap = g.pre_s_cap < cap ? g.pre_s_cap : cap;
            g.pre_m = malloc((size_t) g.pre_m_cap * (size_t) (chIn * BYTES));
            if (!g.pre_m) { LOGE("no memory for stems pre-roll"); g.pre_m_cap = 0; }
        }
    }

    if (resume) {
        // reconnect: the session is still open. Optionally fill the unplugged time with silence first.
        g.mode = MODE_REC; atomic_store(&g.mode_pub, MODE_REC);
        if (gapFrames > 0) {
            atomic_store(&rs.stereo.gap_req, (uint64_t) gapFrames * (uint64_t) rs.stereo.fsz);
            if (rs.has_multi) atomic_store(&rs.multi.gap_req, (uint64_t) gapFrames * (uint64_t) rs.multi.fsz);
            for (int i = 0; i < 10000; i++) {
                if (!atomic_load(&rs.stereo.gap_req) && (!rs.has_multi || !atomic_load(&rs.multi.gap_req))) break;
                sleep_ms(1);
            }
            atomic_fetch_add(&rs.rec_frames, (uint64_t) gapFrames);
        }
    }

    for (int i = 0; i < IN_URBS; i++) { g.in[i].is_out = 0; g.in[i].urb = urb_alloc(epIn, maxPktIn, &g.in[i]); }
    if (epOut) for (int i = 0; i < OUT_URBS; i++) { g.out[i].is_out = 1; g.out[i].urb = urb_alloc(epOut, maxPktOut, &g.out[i]); }

    atomic_store(&g.running, 1);
    g.started = 1;
    atomic_store(&g.thread_alive, 1);
    if (pthread_create(&g.thread, NULL, loop, NULL) != 0) {
        atomic_store(&g.thread_alive, 0); g.started = 0; ret = -EAGAIN;
        free(g.pre_s); free(g.pre_m); g.pre_s = g.pre_m = NULL;
        for (int i = 0; i < IN_URBS; i++) if (g.in[i].urb) { free(g.in[i].urb->buffer); free(g.in[i].urb); g.in[i].urb = NULL; }
        for (int i = 0; i < OUT_URBS; i++) if (g.out[i].urb) { free(g.out[i].urb->buffer); free(g.out[i].urb); g.out[i].urb = NULL; }
        goto out;
    }
    LOGI("armed ep=0x%02x maxpkt=%d ch=%d rate=%d feedback=%d preroll=%ds resume=%d",
         epIn, maxPktIn, chIn, rate, epOut != 0, preSec, resume);
out:
    pthread_mutex_unlock(&api_mu);
    return ret;
}

JNIEXPORT void JNICALL
Java_app_djmrecorder_Native_nativeDisarm(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&api_mu);
    if (g.started) {
        atomic_store(&g.running, 0);
        pthread_join(g.thread, NULL);
        // a session may still be reading the pre-roll rings: let it finish before they are freed
        for (int i = 0; i < 20000 && atomic_load(&rs.state) == RS_ACTIVE; i++) {
            if (atomic_load(&rs.stereo.pre_done) && (!rs.has_multi || atomic_load(&rs.multi.pre_done))) break;
            if (!atomic_load(&rs.stereo.pre_go)) break;   // never handed over
            sleep_ms(1);
        }
        for (int i = 0; i < IN_URBS; i++) if (g.in[i].urb) { free(g.in[i].urb->buffer); free(g.in[i].urb); g.in[i].urb = NULL; }
        for (int i = 0; i < OUT_URBS; i++) if (g.out[i].urb) { free(g.out[i].urb->buffer); free(g.out[i].urb); g.out[i].urb = NULL; }
        free(g.pre_s); free(g.pre_m); g.pre_s = g.pre_m = NULL;
        g.started = 0;
    }
    pthread_mutex_unlock(&api_mu);
}

// -> [result (0 or -errno), preroll frames (stereo), engine frame count at the start, preroll frames (stems)]
JNIEXPORT jlongArray JNICALL
Java_app_djmrecorder_Native_nativeRecordStart(JNIEnv *env, jobject thiz, jstring multiPath, jstring stereoPath,
                                              jint reqPreFrames, jlong rf64Limit) {
    (void) thiz;
    jlong out[4] = {0, 0, 0, 0};
    pthread_mutex_lock(&api_mu);
    int res = 0;
    const char *mp = multiPath ? (*env)->GetStringUTFChars(env, multiPath, NULL) : NULL;
    const char *sp = (*env)->GetStringUTFChars(env, stereoPath, NULL);
    if (!engine_alive()) res = -ENOTCONN;
    else if (atomic_load(&rs.state) != RS_IDLE) res = -EBUSY;
    else {
        memset(&rs, 0, sizeof(rs));
        rs.rf64_limit = rf64Limit > 0 ? (uint64_t) rf64Limit : RF64_DEFAULT_LIMIT;
        rs.stereo.fd = -1; rs.multi.fd = -1;
        res = rf_open(&rs.stereo, sp, g.rate, 2, Q_STEREO_BYTES);
        if (res == 0 && mp) {
            res = rf_open(&rs.multi, mp, g.rate, g.ch_in, Q_MULTI_BYTES);
            if (res == 0) rs.has_multi = 1;
            else { rf_finalize(&rs.stereo); }
        }
        if (res == 0) {
            atomic_store(&rs.state, RS_ACTIVE);
            if (pthread_create(&rs.writer, NULL, writer_main, NULL) != 0) { res = -EAGAIN; }
            else {
                rs.writer_on = 1;
                g.cmd_req_pre = reqPreFrames < 0 ? 0 : reqPreFrames;
                int pc = post_cmd(CMD_REC_START);
                if (pc != 0) res = pc;
                else if (g.cmd_res[0] != 0) res = (int) g.cmd_res[0];
                else { out[1] = g.cmd_res[1]; out[2] = g.cmd_res[2]; out[3] = g.cmd_res[3]; }
            }
            if (res != 0) {
                // the capture thread did not take the session: nothing was pushed, finish the empty files
                atomic_store(&rs.stereo.pre_done, 1); atomic_store(&rs.multi.pre_done, 1);
                atomic_store(&rs.stereo.pre_go, 1); atomic_store(&rs.multi.pre_go, 1);
                rs_teardown();
            }
        }
    }
    if (mp) (*env)->ReleaseStringUTFChars(env, multiPath, mp);
    (*env)->ReleaseStringUTFChars(env, stereoPath, sp);
    pthread_mutex_unlock(&api_mu);
    out[0] = res;
    jlongArray a = (*env)->NewLongArray(env, 4);
    (*env)->SetLongArrayRegion(env, a, 0, 4, out);
    return a;
}

// Finishes the session: stops the capture thread pushing, drains the queues, writes the final headers, closes the files.
// -> [record frames, stereo file bytes, stems file bytes, errno of a failed write (0 = fine)]
JNIEXPORT jlongArray JNICALL
Java_app_djmrecorder_Native_nativeRecordStop(JNIEnv *env, jobject thiz) {
    (void) thiz;
    jlong out[4] = {0, 0, 0, 0};
    pthread_mutex_lock(&api_mu);
    if (atomic_load(&rs.state) == RS_ACTIVE) {
        if (engine_alive()) post_cmd(CMD_REC_STOP);
        atomic_store(&rs.stop, 1);
        if (rs.writer_on) { pthread_join(rs.writer, NULL); rs.writer_on = 0; }
        out[0] = (jlong) atomic_load(&rs.rec_frames);
        out[1] = (jlong) (rs.stereo.data_bytes + HDR_LEN);
        out[2] = rs.has_multi ? (jlong) (rs.multi.data_bytes + HDR_LEN) : 0;
        out[3] = atomic_load(&rs.stereo.err) ? atomic_load(&rs.stereo.err) : (rs.has_multi ? atomic_load(&rs.multi.err) : 0);
        rf_finalize(&rs.stereo);
        if (rs.has_multi) rf_finalize(&rs.multi);
        rs.has_multi = 0;
        atomic_store(&rs.state, RS_IDLE);
    }
    pthread_mutex_unlock(&api_mu);
    jlongArray a = (*env)->NewLongArray(env, 4);
    (*env)->SetLongArrayRegion(env, a, 0, 4, out);
    return a;
}

JNIEXPORT jlong JNICALL
Java_app_djmrecorder_Native_nativeRecFrames(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jlong) atomic_load(&rs.rec_frames);
}

#define STATS_N 42
// [0 frames, 1 urbs, 2 pktErr, 3 oddPkts, 4 outUrbs, 5 outErr, 6 lastErrno, 7 running, 8..23 peaks,
//  24 recFrames, 25 recActive, 26 recErr, 27 gapEvents, 28 gapFrames, 29 devGone, 30 sfJumps, 31 sfRejected,
//  32 queueLostFrames, 33 stereoBytes, 34 stemsBytes, 35 preStereoFrames, 36 preStemsFrames,
//  37 ctrValid, 38 ctrMissing, 39 ctrMismatch, 40 mode, 41 sfLearned]
JNIEXPORT jlongArray JNICALL
Java_app_djmrecorder_Native_nativeStats(JNIEnv *env, jobject thiz) {
    (void) thiz;
    jlong v[STATS_N];
    memset(v, 0, sizeof(v));
    v[0] = atomic_load(&g.frames); v[1] = atomic_load(&g.urbs);
    v[2] = atomic_load(&g.pkt_err); v[3] = atomic_load(&g.odd_pkts);
    v[4] = atomic_load(&g.out_urbs); v[5] = atomic_load(&g.out_err);
    v[6] = atomic_load(&g.last_errno); v[7] = g.started && atomic_load(&g.running) && atomic_load(&g.thread_alive);
    for (int c = 0; c < MAX_CH; c++) v[8 + c] = atomic_exchange(&g.peak[c], 0);
    v[24] = (jlong) atomic_load(&rs.rec_frames);
    v[25] = atomic_load(&rs.state) == RS_ACTIVE;
    int e1 = atomic_load(&rs.stereo.err), e2 = atomic_load(&rs.multi.err);
    v[26] = e1 ? e1 : e2;
    v[27] = atomic_load(&g.gap_events); v[28] = atomic_load(&g.gap_frames);
    v[29] = atomic_load(&g.dev_gone); v[30] = atomic_load(&g.sf_jumps); v[31] = atomic_load(&g.sf_rejected);
    v[32] = (jlong) atomic_load(&rs.lost_frames);
    if (v[25]) {
        v[33] = (jlong) (atomic_load(&rs.stereo.data_pub) + HDR_LEN);
        v[34] = rs.has_multi ? (jlong) (atomic_load(&rs.multi.data_pub) + HDR_LEN) : 0;
    }
    uint64_t ps = atomic_load(&g.pre_s_pub), pm = atomic_load(&g.pre_m_pub);
    v[35] = (jlong) (ps < g.pre_s_cap ? ps : g.pre_s_cap);
    v[36] = (jlong) (pm < g.pre_m_cap ? pm : g.pre_m_cap);
    v[37] = atomic_load(&g.ctr_valid_pub); v[38] = atomic_load(&g.ctr_missing); v[39] = atomic_load(&g.ctr_mismatch);
    v[40] = atomic_load(&g.mode_pub); v[41] = g.sf_ok;
    jlongArray a = (*env)->NewLongArray(env, STATS_N);
    (*env)->SetLongArrayRegion(env, a, 0, STATS_N, v);
    return a;
}

// Dropouts since the last call: (record position in frames, missing frames) pairs. One reader thread.
JNIEXPORT jlongArray JNICALL
Java_app_djmrecorder_Native_nativeEvents(JNIEnv *env, jobject thiz) {
    (void) thiz;
    long long w = atomic_load_explicit(&g.ev_w, memory_order_acquire);
    long long r = g.ev_r;
    if (w - r > EV_RING) r = w - EV_RING;
    int n = (int) (w - r);
    jlongArray a = (*env)->NewLongArray(env, n * 2);
    if (!a || n <= 0) { g.ev_r = w; return a; }
    jlong tmp[EV_RING * 2];
    for (int i = 0; i < n; i++) { tmp[i * 2] = g.ev[(r + i) % EV_RING][0]; tmp[i * 2 + 1] = g.ev[(r + i) % EV_RING][1]; }
    (*env)->SetLongArrayRegion(env, a, 0, n * 2, tmp);
    g.ev_r = w;
    return a;
}

// Diagnostics: sample-counter discontinuities (stream frame index, expected, got) triplets.
JNIEXPORT jlongArray JNICALL
Java_app_djmrecorder_Native_nativeCounterLog(JNIEnv *env, jobject thiz) {
    (void) thiz;
    long long w = atomic_load_explicit(&g.ctr_w, memory_order_acquire);
    long long r = g.ctr_r;
    if (w - r > CTR_RING) r = w - CTR_RING;
    int n = (int) (w - r);
    jlongArray a = (*env)->NewLongArray(env, n * 3);
    if (!a || n <= 0) { g.ctr_r = w; return a; }
    jlong tmp[CTR_RING * 3];
    for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) tmp[i * 3 + k] = g.ctr[(r + i) % CTR_RING][k];
    (*env)->SetLongArrayRegion(env, a, 0, n * 3, tmp);
    g.ctr_r = w;
    return a;
}

// Returns (and drains) the new 5-byte (low, mid, high, left, right) 10 ms entries since the last call.
// Values are sqrt-encoded peaks: linear = (b/255)^2. Called from one (UI) thread only.
JNIEXPORT jbyteArray JNICALL
Java_app_djmrecorder_Native_nativeWave(JNIEnv *env, jobject thiz) {
    (void) thiz;
    long long w = atomic_load_explicit(&g.wave_w, memory_order_acquire);
    long long r = g.wave_r;
    if (w - r > WAVE_RING - 64) r = w - (WAVE_RING - 64);   // reader too slow: skip oldest
    if (r < 0) r = 0;
    int n = (int) (w - r);
    jbyteArray a = (*env)->NewByteArray(env, n * WAVE_STRIDE);
    if (!a || n <= 0) { g.wave_r = w; return a; }
    int start = (int) (r % WAVE_RING);
    int first = n < WAVE_RING - start ? n : WAVE_RING - start;
    (*env)->SetByteArrayRegion(env, a, 0, first * WAVE_STRIDE, (const jbyte *) (g.wave + (size_t) start * WAVE_STRIDE));
    if (first < n) (*env)->SetByteArrayRegion(env, a, first * WAVE_STRIDE, (n - first) * WAVE_STRIDE, (const jbyte *) g.wave);
    g.wave_r = w;
    return a;
}

// Live monitoring: on = 1 starts feeding the ring (reader position jumps to "now"), 0 stops.
JNIEXPORT void JNICALL
Java_app_djmrecorder_Native_nativeMonitor(JNIEnv *env, jobject thiz, jint on) {
    (void) env; (void) thiz;
    if (on) atomic_store(&g.mon_r, atomic_load(&g.mon_w));
    atomic_store(&g.mon_on, on ? 1 : 0);
}

// Copies up to maxFrames stereo float frames into out (interleaved). If more than maxBacklog frames are queued
// the oldest are skipped so the monitor never lags behind by more than that. Returns the frame count. One reader thread.
JNIEXPORT jint JNICALL
Java_app_djmrecorder_Native_nativeMonitorRead(JNIEnv *env, jobject thiz, jfloatArray out, jint maxFrames, jint maxBacklog) {
    (void) thiz;
    long long w = atomic_load_explicit(&g.mon_w, memory_order_acquire);
    long long r = atomic_load(&g.mon_r);
    if (r > w) r = w;
    if (w - r > MON_RING - 512) r = w - (MON_RING - 512);          // overrun
    if (maxBacklog > 0 && w - r > maxBacklog) r = w - maxBacklog / 2;
    jsize cap = (*env)->GetArrayLength(env, out) / 2;
    long long n = w - r;
    if (n > maxFrames) n = maxFrames;
    if (n > cap) n = cap;
    if (n <= 0) { atomic_store(&g.mon_r, r); return 0; }
    int start = (int) (r % MON_RING);
    int first = (int) (n < MON_RING - start ? n : MON_RING - start);
    (*env)->SetFloatArrayRegion(env, out, 0, first * 2, g.mon + (size_t) start * 2);
    if (first < n) (*env)->SetFloatArrayRegion(env, out, first * 2, (jsize) (n - first) * 2, g.mon);
    atomic_store(&g.mon_r, r + n);
    return (jint) n;
}
