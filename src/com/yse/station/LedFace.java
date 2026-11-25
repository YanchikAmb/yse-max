package com.yse.station;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class LedFace {
    static final int W = 25;
    static final int H = 16;
    static final String[] DIGIT = {
            "111101101101111",
            "010110010010111",
            "111001111100111",
            "111001111001111",
            "101101111001001",
            "111100111001111",
            "111100111101111",
            "111001001001001",
            "111101111101111",
            "111101111001111"
    };
    static final String SDK_APK = "/system/priv-app/ServicesIosdk/ServicesIosdk.apk";
    static volatile String note = "матрица ещё не рисовалась";
    static volatile String scene = "clock";
    static volatile boolean started;
    static long face;
    static java.io.FileDescriptor fbFd;
    static Method poke;
    static int strideBytes = 75;
    static boolean ownClock;
    static Movie movie;
    static String movieKey = "";
    static String movieErr = "";
    static Bitmap movieBmp;
    static Canvas movieCanvas;
    static int[] moviePix;
    static long movieT0;
    static final String FB = "/sys/devices/platform/soc/ffd00000.cbus/ffd15000.spi/spi_master/spi32766/spi32766.0/graphics/fb4/";

    static void sync(String mode, boolean music) {
        if ("говорите".equals(mode)) {
            scene = "listen";
        } else if ("думаю".equals(mode) || "услышал".equals(mode)) {
            scene = "think";
        } else if ("говорю".equals(mode)) {
            scene = "speak";
        } else if (music) {
            scene = "music";
        } else {
            scene = "clock";
        }
    }

    static void start(Context context) {
        if (started) {
            return;
        }
        started = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                File bmp = new File(app.getFilesDir(), "clock.bmp");
                String opened = openFace();
                ownClock = false;
                note = opened;
                boolean live = false;
                int step = 0;
                int shownMinute = -1;
                boolean shownColon = false;
                while (true) {
                    sync(Web.face, Web.musicPlaying());
                    String now = scene;
                    boolean active = !"clock".equals(now);
                    if (active) {
                        int[] pix = sceneFrame(now, step);
                        if (litCount(pix) < 8) {
                            movieKey = "";
                            pix = anim(now, step);
                        }
                        step++;
                        show(app, bmp, pix);
                        live = true;
                        note = now + (movieKey.length() == 0 ? " " + movieErr : " gif");
                        try {
                            Thread.sleep(40);
                        } catch (InterruptedException e) {
                            return;
                        }
                    } else if (ownClock) {
                        Calendar cal = Calendar.getInstance();
                        int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                        boolean colon = (System.currentTimeMillis() / 500) % 2 == 0;
                        if (live || minute != shownMinute || colon != shownColon) {
                            show(app, bmp, frame(colon));
                            shownMinute = minute;
                            shownColon = colon;
                            live = false;
                            note = "часы";
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    } else {
                        if (live) {
                            live = false;
                            note = "часы";
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }).start();
    }

    static void show(Context app, File bmp, int[] pix) {
        if (face != 0) {
            paint(pix);
            return;
        }
        write(bmp, pix);
        String err = play(bmp);
        if (err != null && !err.startsWith("ok") && face == 0) {
            openFace();
            if (face != 0) {
                paint(pix);
            } else {
                note = err;
            }
        }
    }

    static String gifFor(String mode) {
        if ("listen".equals(mode)) {
            return "assets/quark/frontal_animation/25x16/oknix/03-listening-loop.gif";
        }
        if ("think".equals(mode)) {
            return "assets/quark/frontal_animation/25x16/oknix/05-thinking-loop.gif";
        }
        if ("speak".equals(mode)) {
            return "assets/quark/frontal_animation/25x16/oknix/06-answer-loop.gif";
        }
        return null;
    }

    static int[] sceneFrame(String mode, int step) {
        String gif = gifFor(mode);
        if (gif != null && openMovie(gif)) {
            int[] pix = gifFrame();
            if (pix != null) {
                return pix;
            }
        }
        movieKey = "";
        if (gif == null) {
            movieErr = "";
        }
        return anim(mode, step);
    }

    static boolean openMovie(String key) {
        if (key.equals(movieKey) && movie != null) {
            return true;
        }
        ZipFile zip = null;
        InputStream in = null;
        try {
            zip = new ZipFile(SDK_APK);
            ZipEntry entry = zip.getEntry(key);
            if (entry == null) {
                movieErr = "нет кадра";
                return false;
            }
            in = zip.getInputStream(entry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(entry.getSize(), 0));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            Movie next = Movie.decodeByteArray(data, 0, data.length);
            if (next == null || next.width() < 1 || next.height() < 1) {
                movieErr = "gif пустой";
                return false;
            }
            movie = next;
            movieKey = key;
            movieErr = "";
            movieT0 = System.currentTimeMillis();
            movieBmp = Bitmap.createBitmap(next.width(), next.height(), Bitmap.Config.ARGB_8888);
            movieCanvas = new Canvas(movieBmp);
            moviePix = new int[next.width() * next.height()];
            return true;
        } catch (Exception e) {
            String msg = e.getMessage();
            movieErr = e.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
            if (movieErr.length() > 80) {
                movieErr = movieErr.substring(0, 80);
            }
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static int[] gifFrame() {
        if (movie == null || movieBmp == null) {
            return null;
        }
        int dur = movie.duration();
        if (dur < 1) {
            dur = 1000;
        }
        int rel = (int) ((System.currentTimeMillis() - movieT0) % dur);
        movie.setTime(rel);
        movieBmp.eraseColor(0xFF000000);
        movie.draw(movieCanvas, 0, 0);
        int w = movieBmp.getWidth();
        int h = movieBmp.getHeight();
        movieBmp.getPixels(moviePix, 0, w, 0, 0, w, h);
        if (w == W && h == H) {
            return moviePix;
        }
        int[] out = new int[W * H];
        for (int y = 0; y < H; y++) {
            int sy = y * h / H;
            for (int x = 0; x < W; x++) {
                out[y * W + x] = moviePix[sy * w + (x * w / W)];
            }
        }
        return out;
    }

    static int litCount(int[] pix) {
        int lit = 0;
        if (pix == null) {
            return 0;
        }
        for (int i = 0; i < pix.length; i++) {
            if ((pix[i] & 0x00FFFFFF) != 0) {
                lit++;
            }
        }
        return lit;
    }

    static int[] anim(String mode, int step) {
        if ("music".equals(mode)) {
            return music(step);
        }
        if ("speak".equals(mode)) {
            return wave(step, 0x40E0FF, 6);
        }
        if ("think".equals(mode)) {
            return think(step);
        }
        return wave(step, 0xFFFFFF, 4);
    }

    static int[] music(int step) {
        int[] pix = new int[W * H];
        int level = Math.max(8, Math.min(100, Web.level));
        for (int bar = 0; bar < 8; bar++) {
            int x0 = 1 + bar * 3;
            int wobble = (int) (Math.abs(Math.sin((step + bar * 3) * 0.45)) * 10);
            int h = 3 + (level * (6 + wobble)) / 140;
            if (h > 14) {
                h = 14;
            }
            for (int y = H - 2; y > H - 2 - h && y >= 1; y--) {
                int color = y > 6 ? 0xFFFFFF : 0x40C0FF;
                pix[y * W + x0] = color;
                pix[y * W + x0 + 1] = color;
            }
        }
        return pix;
    }

    static int[] wave(int step, int color, int thick) {
        int[] pix = new int[W * H];
        for (int x = 0; x < W; x++) {
            int y = 8 + (int) Math.round(Math.sin((x + step) * 0.55) * thick);
            if (y < 1) {
                y = 1;
            }
            if (y > H - 2) {
                y = H - 2;
            }
            pix[y * W + x] = color;
            if (y + 1 < H) {
                pix[(y + 1) * W + x] = color;
            }
        }
        return pix;
    }

    static int[] think(int step) {
        int[] pix = new int[W * H];
        int cx = 12;
        int cy = 8;
        for (int i = 0; i < 8; i++) {
            double a = (step * 0.35) + i * Math.PI / 4;
            int x = cx + (int) Math.round(Math.cos(a) * 5);
            int y = cy + (int) Math.round(Math.sin(a) * 4);
            if (x >= 0 && x < W && y >= 0 && y < H) {
                pix[y * W + x] = i == (step / 2) % 8 ? 0xFFFFFF : 0x4040FF;
            }
        }
        return pix;
    }

    static String play(File bmp) {
        try {
            Process process = new ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    "/system/bin/led-show < '" + bmp.getAbsolutePath() + "' > /dev/graphics/fb4")
                    .redirectErrorStream(true)
                    .start();
            InputStream in = process.getInputStream();
            byte[] buf = new byte[240];
            int got = 0;
            int n;
            while (got < buf.length && (n = in.read(buf, got, buf.length - got)) > 0) {
                got += n;
            }
            int code = process.waitFor();
            if (code == 0) {
                return "ok";
            }
            String err = new String(buf, 0, got).trim().replace('\n', ' ');
            if (err.length() == 0) {
                err = "код " + code;
            }
            if (err.length() > 120) {
                err = err.substring(0, 120);
            }
            return err;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    static String openFace() {
        String info = "name=" + readText(FB + "name")
                + " size=" + readText(FB + "virtual_size")
                + " bpp=" + readText(FB + "bits_per_pixel")
                + " stride=" + readText(FB + "stride");
        try {
            strideBytes = Integer.parseInt(readText(FB + "stride").trim());
        } catch (Exception ignored) {
            strideBytes = W * 3;
        }
        try {
            poke = Class.forName("libcore.io.Memory").getDeclaredMethod(
                    "pokeByteArray", long.class, byte[].class, int.class, int.class);
            fbFd = Os.open("/dev/graphics/fb4", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
            face = Os.mmap(0, 4096, OsConstants.PROT_READ | OsConstants.PROT_WRITE, OsConstants.MAP_SHARED, fbFd, 0);
            return info + " mmap";
        } catch (ErrnoException e) {
            return info + " errno " + e.errno;
        } catch (Exception e) {
            String msg = e.getMessage();
            return info + " " + e.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
        }
    }

    static String readText(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) {
                return "empty";
            }
            return new String(buf, 0, n).trim();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static void paint(int[] pix) {
        if (face == 0 || poke == null) {
            return;
        }
        byte[] buf = new byte[strideBytes * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int color = pix[y * W + x];
                int at = y * strideBytes + x * 3;
                buf[at] = (byte) ((color >> 16) & 255);
                buf[at + 1] = (byte) ((color >> 8) & 255);
                buf[at + 2] = (byte) (color & 255);
            }
        }
        try {
            if (fbFd != null) {
                Os.lseek(fbFd, 0, OsConstants.SEEK_SET);
                ByteBuffer view = ByteBuffer.wrap(buf);
                while (view.hasRemaining()) {
                    Os.write(fbFd, view);
                }
            }
            if (face != 0 && poke != null) {
                poke.invoke(null, face, buf, 0, buf.length);
                Os.msync(face, buf.length, OsConstants.MS_SYNC);
            }
        } catch (Exception e) {
            note = e.getClass().getSimpleName();
        }
    }

    static int[] frame(boolean colon) {
        int[] pix = new int[W * H];
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int[] digits = {hour / 10, hour % 10, minute / 10, minute % 10};
        int[] xs = {4, 8, 14, 18};
        for (int d = 0; d < 4; d++) {
            String bits = DIGIT[digits[d]];
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (bits.charAt(row * 3 + col) == '1') {
                        pix[(5 + row) * W + xs[d] + col] = 0xFFFFFF;
                    }
                }
            }
        }
        if (colon) {
            pix[6 * W + 12] = 0xFFFFFF;
            pix[8 * W + 12] = 0xFFFFFF;
        }
        return pix;
    }

    static void write(File bmp, int[] pix) {
        int row = (W * 3 + 3) & ~3;
        int pixels = row * H;
        int size = 54 + pixels;
        byte[] data = new byte[size];
        data[0] = 'B';
        data[1] = 'M';
        putInt(data, 2, size);
        putInt(data, 10, 54);
        putInt(data, 14, 40);
        putInt(data, 18, W);
        putInt(data, 22, H);
        data[26] = 1;
        data[28] = 24;
        putInt(data, 34, pixels);
        for (int y = 0; y < H; y++) {
            int dst = 54 + (H - 1 - y) * row;
            for (int x = 0; x < W; x++) {
                int color = pix[y * W + x];
                data[dst++] = (byte) (color & 255);
                data[dst++] = (byte) ((color >> 8) & 255);
                data[dst++] = (byte) ((color >> 16) & 255);
            }
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(bmp);
            out.write(data);
        } catch (Exception e) {
            note = e.getClass().getSimpleName();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static void putInt(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >> 8);
        data[at + 2] = (byte) (value >> 16);
        data[at + 3] = (byte) (value >> 24);
    }

    static String shell(String cmd, String arg) {
        try {
            Process process = new ProcessBuilder(cmd, arg).redirectErrorStream(true).start();
            InputStream in = process.getInputStream();
            byte[] buf = new byte[400];
            int got = 0;
            int n;
            while (got < buf.length && (n = in.read(buf, got, buf.length - got)) > 0) {
                got += n;
            }
            process.waitFor();
            return new String(buf, 0, got).trim().replace('\n', ' ');
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    static String show(File bmp, String dev) {
        try {
            Process process = new ProcessBuilder("/system/bin/led-show", bmp.getAbsolutePath(), dev)
                    .redirectErrorStream(true)
                    .start();
            InputStream in = process.getInputStream();
            byte[] buf = new byte[240];
            int got = 0;
            int n;
            while (got < buf.length && (n = in.read(buf, got, buf.length - got)) > 0) {
                got += n;
            }
            int code = process.waitFor();
            if (code == 0) {
                return null;
            }
            String err = new String(buf, 0, got).trim();
            if (err.length() == 0) {
                err = "код " + code;
            }
            if (err.length() > 80) {
                err = err.substring(0, 80);
            }
            return err;
        } catch (Exception e) {
            String msg = e.getMessage();
            return e.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
        }
    }
}
