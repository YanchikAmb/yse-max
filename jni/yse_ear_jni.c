#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define TAG "yse_ear"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define OUT_CAP 8192

typedef int (*cfg_create_fn)(const char *name, void **out);
typedef int (*cfg_int_fn)(void *cfg);
typedef int (*eng_create_fn)(void *cfg, const char *path, int, void *, void **);
typedef int (*process_fn)(void *eng, void *mic, int mic_n, void *spk, int spk_n);
typedef int (*output_fn)(void *eng, int type, int channel, void **data, int *count);

static void *vlib;
static void *vcfg;
static void *veng;
static float *vmic;
static float *vspk;
static int vmics;
static int vspeakers;
static int vchunk;
static int vfill;
static process_fn vprocess;
static output_fn voutput;
static int vtype = 6;
static int vchan;
static jshort vout[OUT_CAP];
static int vout_n;

static int try_dlopen(void) {
    const char *paths[] = {
        "/vendor/lib64/libyandex_vqe_64.so",
        "/vendor/lib/libyandex_vqe.so",
        "/system/vendor/quasar/libs/libyandex_evil.so",
        "/system/vendor/quasar/libs/libyandex_vqe.so",
        0
    };
    int i;
    for (i = 0; paths[i]; i++) {
        vlib = dlopen(paths[i], RTLD_NOW);
        if (vlib) {
            LOGI("vqe lib %s", paths[i]);
            return 0;
        }
    }
    LOGI("vqe dlopen fail");
    return -1;
}

static void *sym(const char *name) {
    return dlsym(vlib, name);
}

JNIEXPORT jint JNICALL
Java_com_yse_station_Ear_nativeOpen(JNIEnv *env, jclass cls) {
    const char *presets[] = {
        "station_2_16kHz", "station_2_rev5", "station_2_rev4", "station_2_rev3",
        "station_2_rev2", "station_2_rev1", "yandexmidi", 0
    };
    const char *models[] = {
        "/system/vendor/quasar/vqe_models",
        "/vendor/etc/quasar/vqe_models",
        "",
        0
    };
    cfg_create_fn create;
    cfg_int_fn mics, speakers, chunk;
    eng_create_fn make;
    int pi;
    int mi;
    if (veng) {
        return 0;
    }
    vout_n = 0;
    if (try_dlopen() != 0) {
        return -1;
    }
    create = sym("YandexVqeC_Config_CreateFromPreset");
    mics = sym("YandexVqeC_Config_GetMicsCount");
    speakers = sym("YandexVqeC_Config_GetSpeakersCount");
    chunk = sym("YandexVqeC_Config_GetInputChunkSize");
    make = sym("YandexVqeC_Create");
    vprocess = sym("YandexVqeC_Process");
    voutput = sym("YandexVqeC_GetOutputChannelData");
    if (!create || !mics || !speakers || !chunk || !make || !vprocess || !voutput) {
        return -2;
    }
    vcfg = 0;
    for (pi = 0; presets[pi]; pi++) {
        if (create(presets[pi], &vcfg) == 0 && vcfg) {
            LOGI("preset %s", presets[pi]);
            break;
        }
    }
    if (!vcfg) {
        return -3;
    }
    vmics = mics(vcfg);
    vspeakers = speakers(vcfg);
    vchunk = chunk(vcfg);
    if (vmics < 1 || vmics > 8 || vchunk < 1 || vchunk > 8192) {
        return -4;
    }
    for (mi = 0; models[mi]; mi++) {
        if (make(vcfg, models[mi], 0, 0, &veng) == 0 && veng) {
            LOGI("models %s", models[mi]);
            break;
        }
    }
    if (!veng) {
        return -5;
    }
    vmic = calloc((size_t) vmics * (size_t) vchunk, sizeof(float));
    vspk = calloc((size_t) (vspeakers > 0 ? vspeakers : 1) * (size_t) vchunk, sizeof(float));
    vfill = 0;
    if (!vmic || !vspk) {
        return -6;
    }
    LOGI("ready mics=%d spk=%d chunk=%d", vmics, vspeakers, vchunk);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_yse_station_Ear_nativeClose(JNIEnv *env, jclass cls) {
    free(vmic);
    free(vspk);
    vmic = vspk = 0;
    veng = 0;
    vcfg = 0;
    if (vlib) {
        dlclose(vlib);
        vlib = 0;
    }
    vfill = 0;
    vout_n = 0;
}

static int pull_output(void) {
    void *data = 0;
    int count = 0;
    int i;
    float *samples;
    if (!veng || voutput(veng, vtype, vchan, &data, &count) != 0 || !data || count <= 0) {
        return 0;
    }
    if (count > OUT_CAP) {
        count = OUT_CAP;
    }
    samples = (float *) data;
    for (i = 0; i < count; i++) {
        float x = samples[i] * 32768.0f;
        if (x > 32767.f) {
            x = 32767.f;
        } else if (x < -32768.f) {
            x = -32768.f;
        }
        vout[i] = (jshort) x;
    }
    vout_n = count;
    return count;
}

static void feed_frame(const int16_t *frame, int ch) {
    int c;
    if (!veng) {
        return;
    }
    for (c = 0; c < vmics && c < ch; c++) {
        vmic[(size_t) vfill * (size_t) vmics + (size_t) c] = (float) frame[c] / 32768.0f;
    }
    if (vspeakers >= 1 && ch > 7) {
        vspk[vfill] = (float) frame[7] / 32768.0f;
    }
    if (vspeakers >= 2 && ch > 8) {
        vspk[vchunk + vfill] = (float) frame[8] / 32768.0f;
    }
    vfill++;
    if (vfill < vchunk) {
        return;
    }
    vfill = 0;
    vprocess(veng, vmic, vmics * vchunk, vspeakers ? vspk : 0, vspeakers * vchunk);
    pull_output();
}

JNIEXPORT jint JNICALL
Java_com_yse_station_Ear_nativeFeed(JNIEnv *env, jclass cls, jshortArray in) {
    jshort *frame = (*env)->GetShortArrayElements(env, in, 0);
    jsize n = (*env)->GetArrayLength(env, in);
    int ch = n > 16 ? 16 : (int) n;
    if (!frame) {
        return 0;
    }
    vout_n = 0;
    feed_frame(frame, ch);
    (*env)->ReleaseShortArrayElements(env, in, frame, JNI_ABORT);
    return vout_n;
}

JNIEXPORT jint JNICALL
Java_com_yse_station_Ear_nativeCopy(JNIEnv *env, jclass cls, jshortArray dst) {
    jshort *buf = (*env)->GetShortArrayElements(env, dst, 0);
    jsize cap = (*env)->GetArrayLength(env, dst);
    int n = vout_n;
    if (!buf || n <= 0) {
        if (buf) {
            (*env)->ReleaseShortArrayElements(env, dst, buf, 0);
        }
        return 0;
    }
    if (n > cap) {
        n = (int) cap;
    }
    memcpy(buf, vout, (size_t) n * sizeof(jshort));
    (*env)->ReleaseShortArrayElements(env, dst, buf, 0);
    vout_n = 0;
    return n;
}