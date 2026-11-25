package com.yse.station;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Локальный детектор слова «Джарвис» на самой Station Max (TFLite Micro + ru_jarvis_mww.tflite).
 */
public class LocalJarvis {
    static volatile boolean ready;
    static volatile String note = "не инициализирован";

    static {
        try {
            System.loadLibrary("jarvismww");
        } catch (Throwable t) {
            note = "so: " + t.getClass().getSimpleName();
        }
    }

    static native int nativeInit(byte[] modelBytes, float cutoff);

    static native int nativeFeed(short[] pcm, int offset, boolean music);

    static native void nativeReset();

    static native float[] nativeStats();

    static synchronized boolean init(Context ctx, float cutoff) {
        if (ready) {
            return true;
        }
        InputStream in = null;
        try {
            in = ctx.getAssets().open("ru_jarvis_mww.tflite");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            int rc = nativeInit(out.toByteArray(), cutoff);
            ready = (rc == 0);
            note = ready ? "локальный TFLM ok" : ("init rc=" + rc);
            return ready;
        } catch (Throwable t) {
            ready = false;
            note = "init: " + t.getClass().getSimpleName();
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static synchronized int feed320(short[] mono320, boolean music) {
        if (!ready || mono320 == null || mono320.length < 320) {
            return 0;
        }
        int r1 = nativeFeed(mono320, 0, music);
        if (r1 == 1) {
            return 1;
        }
        int r2 = nativeFeed(mono320, 160, music);
        return r2 == 1 ? 1 : (r1 == 2 || r2 == 2 ? 2 : 0);
    }

    static synchronized void reset() {
        if (ready) {
            try {
                nativeReset();
            } catch (Throwable ignored) {
            }
        }
    }

    static String summary() {
        if (!ready) {
            return note;
        }
        try {
            float[] s = nativeStats();
            if (s != null && s.length >= 4) {
                return String.format(java.util.Locale.US,
                        "ok avg=%.2f max=%.2f hits=%d", s[0], s[1], (int) s[3]);
            }
        } catch (Throwable ignored) {
        }
        return note;
    }
}
