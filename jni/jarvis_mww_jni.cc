/* Локальный детектор «Джарвис» (TFLite Micro + microfrontend) для Station Max arm64-v8a.
 * Алгоритм 1-в-1 повторяет /tmp/yse-build/src/jarvisd.cc (ru_jarvis_mww.tflite). */
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <new>

#include "tensorflow/lite/micro/micro_allocator.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/micro/micro_mutable_op_resolver.h"
#include "tensorflow/lite/micro/micro_resource_variable.h"
#include "tensorflow/lite/micro/schema/schema_generated.h"

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

#define CHUNK 160
#define STRIDE 3
#define WINDOW 10
#define HIST 6400
#define RMS_MUSIC 8.0f
#define REFRACTORY 1.2f
#define FEATURE_SCALE 0.0390625f

static uint8_t *g_model = nullptr;
alignas(16) static uint8_t g_arena[64 * 1024];
alignas(tflite::MicroInterpreter) static uint8_t g_interp_mem[sizeof(tflite::MicroInterpreter)];

class LocalEar {
public:
    float cutoff = 0.85f;
    float last_avg = 0.0f;
    float last_rms = 0.0f;
    float max_avg = 0.0f;
    int hits = 0;
    bool ready = false;

    int open() {
        if (!ops_ready_) {
            ops_.AddCallOnce();
            ops_.AddVarHandle();
            ops_.AddReadVariable();
            ops_.AddAssignVariable();
            ops_.AddReshape();
            ops_.AddConcatenation();
            ops_.AddStridedSlice();
            ops_.AddConv2D();
            ops_.AddDepthwiseConv2D();
            ops_.AddAdd();
            ops_.AddSplitV();
            ops_.AddAveragePool2D();
            ops_.AddFullyConnected();
            ops_.AddLogistic();
            ops_.AddQuantize();
            ops_ready_ = true;
        }
        FrontendConfig c;
        c.window.size_ms = 30;
        c.window.step_size_ms = 10;
        c.filterbank.num_channels = 40;
        c.filterbank.lower_band_limit = 125.0f;
        c.filterbank.upper_band_limit = 7500.0f;
        c.noise_reduction.smoothing_bits = 10;
        c.noise_reduction.even_smoothing = 0.025f;
        c.noise_reduction.odd_smoothing = 0.06f;
        c.noise_reduction.min_signal_remaining = 0.05f;
        c.pcan_gain_control.enable_pcan = 1;
        c.pcan_gain_control.strength = 0.95f;
        c.pcan_gain_control.offset = 80.0f;
        c.pcan_gain_control.gain_bits = 21;
        c.log_scale.enable_log = 1;
        c.log_scale.scale_shift = 6;
        if (fe_ready_) {
            FrontendFreeStateContents(&fe_);
            fe_ready_ = false;
        }
        if (!FrontendPopulateState(&c, &fe_, 16000)) {
            return -1;
        }
        fe_ready_ = true;
        return reset();
    }

    int reset() {
        ready = false;
        if (!g_model) {
            return -2;
        }
        if (it_) {
            it_->~MicroInterpreter();
            it_ = nullptr;
        }
        tflite::MicroAllocator *ma = tflite::MicroAllocator::Create(g_arena, sizeof g_arena);
        tflite::MicroResourceVariables *mrv = tflite::MicroResourceVariables::Create(ma, 20);
        it_ = new (g_interp_mem) tflite::MicroInterpreter(tflite::GetModel(g_model), ops_, ma, mrv);
        if (it_->AllocateTensors() != kTfLiteOk) {
            return -3;
        }
        in_ = it_->input(0);
        out_ = it_->output(0);
        FrontendReset(&fe_);
        memset(hist_, 0, sizeof hist_);
        hist_pos_ = hist_len_ = 0;
        energy_ = 0;
        probs_n_ = 0;
        buf_n_ = 0;
        frame_ = 0;
        hot_ = false;
        consec_ = 0;
        noise_floor_ = 30.0f;
        last_fire_ = last_note_ = -10.0f;
        ready = true;
        return 0;
    }

    int feed(const int16_t *s, bool music) {
        if (!ready || !it_) {
            return -1;
        }
        for (int i = 0; i < CHUNK; i++) {
            int64_t v = s[i];
            if (hist_len_ == HIST) {
                int64_t old = hist_[hist_pos_];
                energy_ -= old * old;
            } else {
                hist_len_++;
            }
            hist_[hist_pos_] = (int16_t)v;
            hist_pos_ = (hist_pos_ + 1) % HIST;
            energy_ += v * v;
        }
        size_t read = 0;
        FrontendOutput fo = FrontendProcessSamples(&fe_, s, CHUNK, &read);
        if (fo.size == 0) {
            return 0;
        }
        int8_t *dst = in_->data.int8 + buf_n_ * 40;
        for (size_t k = 0; k < fo.size && k < 40; k++) {
            float x = (float)fo.values[k] * FEATURE_SCALE;
            long q = lrintf(x / in_->params.scale + (float)in_->params.zero_point);
            dst[k] = (int8_t)(q > 127 ? 127 : q < -128 ? -128 : q);
        }
        frame_++;
        if (++buf_n_ < STRIDE) {
            return 0;
        }
        buf_n_ = 0;
        if (it_->Invoke() != kTfLiteOk) {
            return 0;
        }
        float p = ((float)out_->data.uint8[0] - (float)out_->params.zero_point) * out_->params.scale;
        p = p < 0.0f ? 0.0f : p > 1.0f ? 1.0f : p;
        probs_[probs_n_ % WINDOW] = p;
        probs_n_++;
        int cnt = probs_n_ < WINDOW ? probs_n_ : WINDOW;
        float avg = 0.0f;
        for (int k = 0; k < cnt; k++) {
            avg += probs_[k];
        }
        avg /= (float)cnt;
        float rms = hist_len_ ? sqrtf((float)energy_ / (float)hist_len_) : 0.0f;
        float t = (float)frame_ * 0.01f;
        last_avg = avg;
        last_rms = rms;
        if (avg > max_avg) {
            max_avg = avg;
        }
        /* Адаптивный шумовой порог для VQE Station Max: быстро снижается в тишине,
         * медленно поднимается на постоянном фоне (телевизор/вытяжка), не реагирует на речь. */
        if (rms < noise_floor_) {
            noise_floor_ = noise_floor_ * 0.94f + rms * 0.06f;
        } else if (avg < 0.40f) {
            noise_floor_ = noise_floor_ * 0.996f + rms * 0.004f;
        }
        /* Пол не задираем до 20: с двух метров речь тише этого, и слово отсекалось,
         * хотя оценка модели уже была высокой. */
        if (noise_floor_ < 8.0f) {
            noise_floor_ = 8.0f;
        }
        float floor = music ? RMS_MUSIC : fmaxf(6.0f, noise_floor_ * 0.7f);
        bool loud = rms >= floor || (avg >= cutoff && rms >= 4.0f);
        bool was_hot = hot_;
        if (avg < cutoff - 0.04f) {
            hot_ = false;
        } else if (avg >= cutoff) {
            hot_ = true;
        }
        bool crossed = hot_ && !was_hot;
        if (crossed && loud && t >= 1.2f && t - last_fire_ >= REFRACTORY) {
            last_fire_ = t;
            hits++;
            return 1;
        }
        if (avg >= cutoff && !loud && t - last_note_ >= REFRACTORY) {
            last_note_ = t;
            return 2;
        }
        return 0;
    }

private:
    tflite::MicroMutableOpResolver<16> ops_;
    bool ops_ready_ = false;
    bool fe_ready_ = false;
    tflite::MicroInterpreter *it_ = nullptr;
    TfLiteTensor *in_ = nullptr;
    TfLiteTensor *out_ = nullptr;
    FrontendState fe_;
    int16_t hist_[HIST];
    int hist_pos_ = 0;
    int hist_len_ = 0;
    int64_t energy_ = 0;
    float probs_[WINDOW];
    int probs_n_ = 0;
    int buf_n_ = 0;
    int frame_ = 0;
    bool hot_ = false;
    int consec_ = 0;
    float noise_floor_ = 30.0f;
    float last_fire_ = -10.0f;
    float last_note_ = -10.0f;
};

static LocalEar g_ear;

extern "C" JNIEXPORT jint JNICALL
Java_com_yse_station_LocalJarvis_nativeInit(JNIEnv *env, jclass, jbyteArray modelBytes, jfloat cutoff) {
    if (!modelBytes) {
        return -10;
    }
    jsize len = env->GetArrayLength(modelBytes);
    if (len < 1024) {
        return -11;
    }
    if (g_model) {
        free(g_model);
        g_model = nullptr;
    }
    size_t aligned = ((size_t)len + 15) & ~(size_t)15;
    if (posix_memalign((void **)&g_model, 16, aligned) != 0 || !g_model) {
        return -12;
    }
    env->GetByteArrayRegion(modelBytes, 0, len, (jbyte *)g_model);
    if (cutoff >= 0.5f && cutoff <= 0.99f) {
        g_ear.cutoff = cutoff;
    }
    return g_ear.open();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yse_station_LocalJarvis_nativeFeed(JNIEnv *env, jclass, jshortArray pcm, jint offset, jboolean music) {
    if (!g_ear.ready || !pcm) {
        return 0;
    }
    jsize len = env->GetArrayLength(pcm);
    if (offset < 0 || offset + CHUNK > len) {
        return 0;
    }
    int16_t buf[CHUNK];
    env->GetShortArrayRegion(pcm, offset, CHUNK, (jshort *)buf);
    return g_ear.feed(buf, music == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yse_station_LocalJarvis_nativeReset(JNIEnv *, jclass) {
    if (g_ear.ready) {
        g_ear.reset();
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_yse_station_LocalJarvis_nativeStats(JNIEnv *env, jclass) {
    jfloatArray arr = env->NewFloatArray(4);
    if (!arr) {
        return nullptr;
    }
    jfloat out[4] = {g_ear.last_avg, g_ear.max_avg, g_ear.last_rms, (jfloat)g_ear.hits};
    env->SetFloatArrayRegion(arr, 0, 4, out);
    return arr;
}
