#include <jni.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>

#define SIG_DFL ((sighandler_t)0)

/* Адреса штатных функций внутри libquasar_daemons.so прошивки Station Max.
   В таблице имён их нет. Смещение от начала файла: первый LOAD начинается с нуля,
   и эти адреса лежат в нём (проверено по прологу sub sp на 2026-11-24). */
#define OFF_CONF_READ 0x37a0f24
#define OFF_MCREATE   0x37a3e88
#define OFF_ATTACH    0x37a4190
#define OFF_CREATE    0x37a4aa8
#define OFF_SUBMIT    0x37a36c0
#define OFF_EVENT     0x37a3a90
#define OFF_PHRASE    0x37a2730

typedef void *(*read_fn)(const char *, void **);
typedef void *(*mcreate_fn)(const char *, long, void **);
typedef void *(*attach_fn)(void *, void *, void **);
typedef void *(*from_fn)(void *, long, long, int *, long, long, void **);
typedef int (*submit_fn)(void *, const void *, int, void **);
typedef int (*event_fn)(void *, void **);
typedef const char *(*text_fn)(void *, void **);

static read_fn conf_read;
static mcreate_fn mcreate;
static attach_fn attach;
static from_fn create;
static submit_fn submit;
static event_fn next_event;
static text_fn phrase;
static int bound;
static char last_err[240];
static __thread sigjmp_buf trap;
static __thread int trapping;
static sighandler_t old_segv;
static sighandler_t old_bus;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;

struct Ear {
    void *spotter;
    volatile int armed;
    unsigned char pending[16384];
    int pending_n;
};

static struct Ear command_ear;
static struct Ear wake_ear;
static int wake_feeds;
static int wake_submits;
static int wake_held;
static int wake_bad;
static int wake_events;
static int wake_last_ate;
static char wake_last[80];

static void oncrash(int sig) {
    if (trapping) {
        siglongjmp(trap, 1);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static int guard(void) {
    trapping = 1;
    old_segv = signal(SIGSEGV, oncrash);
    old_bus = signal(SIGBUS, oncrash);
    return sigsetjmp(trap, 1);
}

static void unguard(void) {
    trapping = 0;
    signal(SIGSEGV, old_segv);
    signal(SIGBUS, old_bus);
}

static int library_base(unsigned long long *base) {
    FILE *fp;
    char line[512];
    int found = 0;
    unsigned long long best_off = 0;
    unsigned long long best_start = 0;
    fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        return 0;
    }
    while (fgets(line, sizeof line, fp)) {
        unsigned long long start;
        unsigned long long off;
        if (!strstr(line, "libquasar_daemons.so")) {
            continue;
        }
        if (sscanf(line, "%llx-%*llx %*s %llx", &start, &off) != 2) {
            continue;
        }
        if (!found || off < best_off) {
            best_start = start;
            best_off = off;
            found = 1;
        }
    }
    fclose(fp);
    if (!found) {
        return 0;
    }
    *base = best_start - best_off;
    return 1;
}

static int bind_fns(void) {
    unsigned long long base = 0;
    if (bound) {
        return 1;
    }
    if (!library_base(&base)) {
        snprintf(last_err, sizeof last_err, "библиотека споттера не загружена");
        return 0;
    }
    conf_read = (read_fn)(base + OFF_CONF_READ);
    mcreate = (mcreate_fn)(base + OFF_MCREATE);
    attach = (attach_fn)(base + OFF_ATTACH);
    create = (from_fn)(base + OFF_CREATE);
    submit = (submit_fn)(base + OFF_SUBMIT);
    next_event = (event_fn)(base + OFF_EVENT);
    phrase = (text_fn)(base + OFF_PHRASE);
    bound = 1;
    return 1;
}

static jstring open_ear(JNIEnv *env, struct Ear *ear, jstring path, const char *ok) {
    static const char *names[] = {
        "Yandex Station_2",
        "Yandex yandexstation_2",
        "Yandex yandexmidi",
        "Yandex yandexmini_2",
    };
    const char *model;
    void *err;
    void *conf;
    int i;
    pthread_mutex_lock(&mu);
    ear->armed = 0;
    ear->spotter = NULL;
    ear->pending_n = 0;
    model = (*env)->GetStringUTFChars(env, path, NULL);
    if (!bind_fns()) {
        (*env)->ReleaseStringUTFChars(env, path, model);
        pthread_mutex_unlock(&mu);
        return (*env)->NewStringUTF(env, last_err);
    }
    if (guard() != 0) {
        unguard();
        ear->spotter = NULL;
        snprintf(last_err, sizeof last_err, "споттер упал");
        (*env)->ReleaseStringUTFChars(env, path, model);
        pthread_mutex_unlock(&mu);
        return (*env)->NewStringUTF(env, last_err);
    }
    err = NULL;
    conf = conf_read(model, &err);
    if (!conf) {
        unguard();
        snprintf(last_err, sizeof last_err, "модель не открылась");
        (*env)->ReleaseStringUTFChars(env, path, model);
        pthread_mutex_unlock(&mu);
        return (*env)->NewStringUTF(env, last_err);
    }
    snprintf(last_err, sizeof last_err, "споттер не собрался");
    for (i = 0; i < 4; i++) {
        void *multi;
        int types[1];
        err = NULL;
        multi = mcreate(names[i], 1, &err);
        if (!multi) {
            continue;
        }
        err = NULL;
        attach(multi, conf, &err);
        if (err) {
            continue;
        }
        types[0] = 0;
        err = NULL;
        ear->spotter = create(multi, 16000, 1, types, 1, 0, &err);
        if (ear->spotter && !err) {
            ear->pending_n = 0;
            ear->armed = 1;
            snprintf(last_err, sizeof last_err, "%s %s", ok, names[i]);
            break;
        }
        ear->spotter = NULL;
    }
    unguard();
    (*env)->ReleaseStringUTFChars(env, path, model);
    pthread_mutex_unlock(&mu);
    return (*env)->NewStringUTF(env, last_err);
}

static jstring feed_ear(JNIEnv *env, struct Ear *ear, jbyteArray pcm) {
    jsize n;
    jbyte *bytes;
    char out[512];
    size_t used = 0;
    out[0] = 0;
    if (!ear->armed || !ear->spotter) {
        return (*env)->NewStringUTF(env, "");
    }
    n = (*env)->GetArrayLength(env, pcm);
    bytes = (*env)->GetByteArrayElements(env, pcm, NULL);
    if (!bytes) {
        return (*env)->NewStringUTF(env, "");
    }
    pthread_mutex_lock(&mu);
    if (!ear->armed || !ear->spotter) {
        (*env)->ReleaseByteArrayElements(env, pcm, bytes, JNI_ABORT);
        pthread_mutex_unlock(&mu);
        return (*env)->NewStringUTF(env, "");
    }
    if (ear->pending_n + (int)n > (int)sizeof ear->pending) {
        ear->pending_n = 0;
    }
    memcpy(ear->pending + ear->pending_n, bytes, (size_t)n);
    ear->pending_n += (int)n;
    (*env)->ReleaseByteArrayElements(env, pcm, bytes, JNI_ABORT);
    if (guard() != 0) {
        unguard();
        if (ear == &wake_ear) {
            wake_bad++;
        }
        ear->armed = 0;
        ear->spotter = NULL;
        pthread_mutex_unlock(&mu);
        return (*env)->NewStringUTF(env, "");
    }
    /* У модели Алисы флаг new-unhandled-nbytes: возврат — сколько байт НЕ забрано.
     * 0 значит «кусок принят целиком». Раньше ноль считался отказом, события не читались,
     * и одно и то же начало звука подавалось снова. */
    while (ear->pending_n >= 640) {
        void *err = NULL;
        int give = ear->pending_n > 3072 ? 3072 : ear->pending_n;
        int ate = submit(ear->spotter, ear->pending, give, &err);
        int consumed;
        if (ear == &wake_ear) {
            wake_feeds++;
            wake_last_ate = ate;
        }
        if (err) {
            if (ear == &wake_ear) {
                wake_bad++;
            }
            break;
        }
        if (ate <= 0 || ate > give) {
            consumed = give;
        } else {
            consumed = give - ate;
        }
        if (consumed <= 0) {
            if (ear == &wake_ear) {
                wake_held++;
            }
            break;
        }
        if (consumed > ear->pending_n) {
            consumed = ear->pending_n;
        }
        if (ear == &wake_ear) {
            wake_submits++;
        }
        memmove(ear->pending, ear->pending + consumed, (size_t)(ear->pending_n - consumed));
        ear->pending_n -= consumed;
        for (;;) {
            const char *text;
            size_t len;
            int event;
            err = NULL;
            event = next_event(ear->spotter, &err);
            if (err || event == 0) {
                break;
            }
            if (event != 2) {
                continue;
            }
            err = NULL;
            text = phrase(ear->spotter, &err);
            if (!text || !text[0] || text[0] == '<') {
                continue;
            }
            if (ear == &wake_ear) {
                wake_events++;
                snprintf(wake_last, sizeof wake_last, "%s", text);
            }
            len = strlen(text);
            if (used + len + 2 >= sizeof out) {
                continue;
            }
            if (used) {
                out[used++] = '\n';
            }
            memcpy(out + used, text, len);
            used += len;
            out[used] = 0;
        }
    }
    unguard();
    pthread_mutex_unlock(&mu);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jstring JNICALL Java_com_yse_station_Spotter_open(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    return open_ear(env, &command_ear, path, "команды");
}

JNIEXPORT jstring JNICALL Java_com_yse_station_Spotter_feed(JNIEnv *env, jclass cls, jbyteArray pcm) {
    (void)cls;
    return feed_ear(env, &command_ear, pcm);
}

JNIEXPORT jstring JNICALL Java_com_yse_station_Spotter_openWake(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    return open_ear(env, &wake_ear, path, "штатное");
}

JNIEXPORT jstring JNICALL Java_com_yse_station_Spotter_feedWake(JNIEnv *env, jclass cls, jbyteArray pcm) {
    (void)cls;
    return feed_ear(env, &wake_ear, pcm);
}

JNIEXPORT jstring JNICALL Java_com_yse_station_Spotter_trace(JNIEnv *env, jclass cls) {
    char buf[240];
    (void)cls;
    snprintf(buf, sizeof buf, "feed=%d take=%d hold=%d bad=%d events=%d ate=%d armed=%d last=%s",
             wake_feeds, wake_submits, wake_held, wake_bad, wake_events, wake_last_ate,
             wake_ear.armed, wake_last[0] ? wake_last : "-");
    return (*env)->NewStringUTF(env, buf);
}
