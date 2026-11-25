package com.yse.station;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class Ring {
    static final int N = 24;
    static final String FB5 = "/dev/graphics/fb5";
    static volatile String note = "";
    static volatile String mode = "слушаю";
    static volatile boolean lampOn;
    static volatile boolean ringEnabled = true;
    static volatile int lampColor = 0xffd8a8;
    static volatile int lampBright = 180;
    static volatile int ringColor = 0x3aa0ff;
    static volatile int ringBright = 180;
    static volatile int screenBright = 200;
    static volatile int volPercent = -1;
    static volatile long volUntilMs = 0;
    static volatile long aliceUntilMs = 0;
    static volatile AudioManager audioMgr;
    static final int[] red = new int[N];
    static final int[] green = new int[N];
    static final int[] blue = new int[N];
    static boolean started;
    static boolean dirty = true;
    static boolean wasLit;
    static File tmpBmp;

    static void init(Context ctx) {
        if (ctx != null) {
            tmpBmp = new File(ctx.getFilesDir(), "ring.bmp");
            audioMgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        }
        ensure();
    }

    static void apply(JSONObject o) {
        lampOn = "1".equals(o.optString("lamp", "0"));
        ringEnabled = !"0".equals(o.optString("ring", "1"));
        lampColor = color(o.optString("lamp_color", "ffd8a8"));
        lampBright = number(o.optString("lamp_bright", "180"), 180);
        ringColor = color(o.optString("ring_color", "3aa0ff"));
        ringBright = number(o.optString("ring_bright", "180"), 180);
        dirty = true;
        ensure();
    }

    static void setMode(String face) {
        String next = face == null ? "слушаю" : face;
        if (!next.equals(mode)) {
            mode = next;
            dirty = true;
        }
        ensure();
    }

    static void showVolume(int percent) {
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }
        volPercent = percent;
        volUntilMs = System.currentTimeMillis() + 2500L;
        dirty = true;
        ensure();
    }

    static void pulseAlice(long durationMs) {
        long until = System.currentTimeMillis() + Math.max(250L, durationMs);
        if (until > aliceUntilMs) {
            aliceUntilMs = until;
        }
        dirty = true;
        ensure();
    }

    static boolean isActiveMode(String m) {
        return "говорите".equals(m) || "думаю".equals(m) || "говорю".equals(m) || "услышал".equals(m);
    }

    static void setScreenBright(int value) {
        if (value < 0) {
            value = 0;
        }
        if (value > 200) {
            value = 200;
        }
        screenBright = value;
        writeNote("/sys/class/backlight/fb4/brightness", value);
    }

    static int readVolumePercent() {
        AudioManager am = audioMgr;
        if (am == null) {
            return -1;
        }
        try {
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.max(0, Math.min(100, (int) Math.round(cur * 100.0 / max)));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static void ensure() {
        if (started) {
            return;
        }
        started = true;
        new Thread(new Runnable() {
            public void run() {
                int step = 0;
                long lastClockKeep = 0;
                int lastSeenVol = readVolumePercent();
                while (true) {
                    try {
                        long now = System.currentTimeMillis();
                        if (now - lastClockKeep >= 2000) {
                            writeNote("/sys/class/backlight/fb4/brightness", screenBright);
                            lastClockKeep = now;
                        }
                        int curVol = readVolumePercent();
                        if (curVol >= 0) {
                            if (lastSeenVol >= 0 && curVol != lastSeenVol) {
                                volPercent = curVol;
                                volUntilMs = now + 2500L;
                                dirty = true;
                            }
                            lastSeenVol = curVol;
                        }
                        String m = mode;
                        boolean active = ringEnabled && isActiveMode(m);
                        boolean showVol = now < volUntilMs && volPercent >= 0;
                        boolean showAlice = now < aliceUntilMs;

                        if (showVol) {
                            long rem = volUntilMs - now;
                            renderVolume(volPercent, rem);
                            writeBacklight(255);
                            paintFb5("vol=" + volPercent + "%");
                            wasLit = true;
                            dirty = true;
                            Thread.sleep(50);
                        } else if (active) {
                            render(m, step++);
                            writeBacklight(255);
                            paintFb5(m);
                            wasLit = true;
                            dirty = false;
                            Thread.sleep(60);
                        } else if (showAlice) {
                            long rem = aliceUntilMs - now;
                            renderAlicePulse(step++, rem);
                            writeBacklight(255);
                            paintFb5("alice");
                            wasLit = true;
                            dirty = true;
                            Thread.sleep(50);
                        } else if (lampOn) {
                            if (dirty || !wasLit) {
                                fillSolid(lampColor, lampBright);
                                writeBacklight(255);
                                paintFb5("lamp");
                                wasLit = true;
                                dirty = false;
                            }
                            Thread.sleep(80);
                        } else {
                            if (wasLit || dirty) {
                                fillSolid(0, 0);
                                paintFb5("слушаю");
                                writeBacklight(0);
                                wasLit = false;
                                dirty = false;
                            }
                            Thread.sleep(80);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        note = t.getClass().getSimpleName();
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }).start();
    }

    static void renderVolume(int pct, long remMs) {
        int fade = remMs < 500 ? (int) Math.max(0, Math.min(255, remMs * 255 / 500)) : 255;
        int br = Math.max(ringBright, 200) * fade / 255;
        for (int i = 0; i < N; i++) {
            red[i] = 0;
            green[i] = 0;
            blue[i] = 0;
        }
        if (pct <= 0) {
            int r = scale(255, br);
            int g = scale(32, br);
            int b = scale(32, br);
            red[0] = r;
            green[0] = g;
            blue[0] = b;
            red[N - 1] = r;
            green[N - 1] = g;
            blue[N - 1] = b;
            return;
        }
        int lit = Math.max(1, Math.min(N, (pct * N + 50) / 100));
        for (int i = 0; i < lit; i++) {
            int cr;
            int cg;
            int cb;
            if (i == lit - 1) {
                // Ведущий диод — яркий белый маркер текущего уровня
                cr = 255;
                cg = 255;
                cb = 255;
            } else if (i >= 18) {
                // Высокая громкость (>75%) — тёплый янтарный
                cr = 255;
                cg = 165;
                cb = 40;
            } else {
                // Основная дуга — чистый голубо-белый
                cr = 90;
                cg = 210;
                cb = 255;
            }
            red[i] = scale(cr, br);
            green[i] = scale(cg, br);
            blue[i] = scale(cb, br);
        }
    }

    static void renderAlicePulse(int step, long remMs) {
        int fade = remMs < 300 ? (int) Math.max(40, Math.min(255, remMs * 255 / 300)) : 255;
        int br = Math.max(ringBright, 220) * fade / 255;
        int head = (step * 2) % N;
        for (int i = 0; i < N; i++) {
            int d = (head - i + N) % N;
            // Фиолетово-бирюзовое сияние кнопки/кольца Алисы
            if (d < 10) {
                red[i] = scale(125, br);
                green[i] = scale(90, br);
                blue[i] = scale(255, br);
            } else {
                red[i] = scale(0, br);
                green[i] = scale(216, br);
                blue[i] = scale(255, br);
            }
        }
    }

    static void fillSolid(int rgb, int bright) {
        int r = scale((rgb >> 16) & 255, bright);
        int g = scale((rgb >> 8) & 255, bright);
        int b = scale(rgb & 255, bright);
        for (int i = 0; i < N; i++) {
            red[i] = r;
            green[i] = g;
            blue[i] = b;
        }
    }

    static void render(String m, int step) {
        int c;
        int br = Math.max(ringBright, 160);
        if ("говорите".equals(m)) {
            // Голубое яркое вращающееся кольцо — слушаю команду
            c = 0x00d8ff;
            int head = step % N;
            for (int i = 0; i < N; i++) {
                int d = (head - i + N) % N;
                int lv = d < 8 ? (255 - d * 26) : 35;
                red[i] = scale(((c >> 16) & 255) * lv / 255, br);
                green[i] = scale(((c >> 8) & 255) * lv / 255, br);
                blue[i] = scale((c & 255) * lv / 255, br);
            }
        } else if ("думаю".equals(m) || "услышал".equals(m)) {
            // Фиолетово-синяя пульсация — думаю
            c = 0x7b61ff;
            int ph = step % 28;
            int lv = ph < 14 ? (60 + ph * 14) : (60 + (27 - ph) * 14);
            fillSolid(c, br * lv / 255);
        } else if ("говорю".equals(m)) {
            // Бирюзовая мягкая волна — отвечаю
            c = 0x00ffaa;
            int head = step % N;
            for (int i = 0; i < N; i++) {
                int d = Math.abs(i - head);
                if (d > N / 2) {
                    d = N - d;
                }
                int lv = Math.max(55, 255 - d * 28);
                red[i] = scale(((c >> 16) & 255) * lv / 255, br);
                green[i] = scale(((c >> 8) & 255) * lv / 255, br);
                blue[i] = scale((c & 255) * lv / 255, br);
            }
        } else {
            fillSolid(ringColor, br);
        }
    }

    static void writeBacklight(int value) {
        writeNote("/sys/class/backlight/fb5/brightness", value);
    }

    static void paintFb5(String label) {
        byte[] raw = new byte[N * 3];
        for (int i = 0; i < N; i++) {
            raw[i * 3] = (byte) (red[i] & 255);
            raw[i * 3 + 1] = (byte) (green[i] & 255);
            raw[i * 3 + 2] = (byte) (blue[i] & 255);
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(FB5);
            out.write(raw);
            out.flush();
            note = "fb5 ok (" + label + ")";
            return;
        } catch (Throwable directErr) {
            note = "fb5: " + directErr.getClass().getSimpleName();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (tmpBmp != null) {
            try {
                writeBmp24(tmpBmp);
                Process p = new ProcessBuilder("/system/bin/sh", "-c",
                        "/system/bin/led-show < '" + tmpBmp.getAbsolutePath() + "' > " + FB5)
                        .redirectErrorStream(true).start();
                InputStream in = p.getInputStream();
                byte[] buf = new byte[128];
                while (in.read(buf) > 0) {
                }
                int rc = p.waitFor();
                if (rc == 0) {
                    note = "led-show fb5 ok (" + label + ")";
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static void writeBmp24(File bmp) throws Exception {
        int row = (N * 3 + 3) & ~3;
        int size = 54 + row;
        byte[] data = new byte[size];
        data[0] = 'B';
        data[1] = 'M';
        putInt(data, 2, size);
        putInt(data, 10, 54);
        putInt(data, 14, 40);
        putInt(data, 18, N);
        putInt(data, 22, 1);
        data[26] = 1;
        data[28] = 24;
        putInt(data, 34, row);
        int dst = 54;
        for (int i = 0; i < N; i++) {
            data[dst++] = (byte) (blue[i] & 255);
            data[dst++] = (byte) (green[i] & 255);
            data[dst++] = (byte) (red[i] & 255);
        }
        FileOutputStream out = new FileOutputStream(bmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    static void putInt(byte[] d, int at, int v) {
        d[at] = (byte) v;
        d[at + 1] = (byte) (v >> 8);
        d[at + 2] = (byte) (v >> 16);
        d[at + 3] = (byte) (v >> 24);
    }

    static String writeNote(String path, int value) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(path);
            out.write(Integer.toString(value).getBytes());
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return e.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static int scale(int channel, int bright) {
        if (channel < 0) {
            channel = 0;
        }
        if (bright < 0) {
            bright = 0;
        }
        if (bright > 255) {
            bright = 255;
        }
        return channel * bright / 255;
    }

    static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    static int color(String text) {
        try {
            return Integer.parseInt(text.replace("#", "").trim(), 16) & 0xFFFFFF;
        } catch (Exception e) {
            return 0x3aa0ff;
        }
    }
}
