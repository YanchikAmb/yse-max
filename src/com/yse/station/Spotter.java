package com.yse.station;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Штатный споттер колонки из ServicesIosdk.apk, внутри нашего процесса. */
public class Spotter {
    static final String SDK_APK = "/system/priv-app/ServicesIosdk/ServicesIosdk.apk";
    static boolean ready;
    static String note = "команды не загружены";
    static volatile boolean wakeReady;
    static volatile String wakeNote = "слово не выбрано";
    static volatile int armGen;

    static native String open(String modelDir);

    static native String feed(byte[] pcm);

    static native String openWake(String modelDir);

    static native String feedWake(byte[] pcm);

    static native String trace();

    static synchronized int beginArm(String word) {
        wakeReady = false;
        wakeNote = word.length() == 0 ? "выключено" : "копирую штатный споттер";
        return ++armGen;
    }

    /** Собрать заводскую модель «алиса» или «яндекс». Пока идёт сборка, wakeReady ложен. */
    static String armWake(Context ctx, String word, int gen) {
        String folder = "алиса".equals(word) ? "alisa" : "яндекс".equals(word) ? "yandex" : "";
        if (folder.length() == 0) {
            return wakeNote;
        }
        try {
            ensureLibs(ctx);
            if (gen != armGen) {
                return wakeNote;
            }
            File dir = extractModel(ctx, folder);
            synchronized (Spotter.class) {
                if (gen != armGen) {
                    return wakeNote;
                }
                String got = openWake(dir.getAbsolutePath());
                if (gen != armGen) {
                    return wakeNote;
                }
                wakeNote = got == null ? "пусто" : got;
                wakeReady = wakeNote.startsWith("штатное");
                return wakeNote;
            }
        } catch (Throwable t) {
            String msg = t.getMessage();
            wakeNote = t.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
            wakeReady = false;
            return wakeNote;
        }
    }

    static synchronized void ensureLibs(Context ctx) throws Exception {
        if (ready) {
            return;
        }
        File dir = new File(ctx.getFilesDir(), "quasar");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("нет каталога библиотеки");
        }
        String[] names = {"libiconv.so", "libglib.so", "libtflite.so", "libquasar_daemons.so"};
        ZipFile zip = new ZipFile(SDK_APK);
        try {
            for (int i = 0; i < names.length; i++) {
                copyZip(zip, "lib/arm64-v8a/" + names[i], new File(dir, names[i]));
            }
        } finally {
            zip.close();
        }
        loadOnce(new File(dir, "libiconv.so").getAbsolutePath());
        loadOnce(new File(dir, "libglib.so").getAbsolutePath());
        loadOnce(new File(dir, "libtflite.so").getAbsolutePath());
        loadOnce(new File(dir, "libquasar_daemons.so").getAbsolutePath());
        try {
            System.loadLibrary("ysecommand");
        } catch (UnsatisfiedLinkError e) {
            if (!alreadyLoaded(e)) {
                throw e;
            }
        }
        ready = true;
        note = "библиотека споттера загружена";
    }

    static void loadOnce(String path) {
        try {
            System.load(path);
        } catch (UnsatisfiedLinkError e) {
            if (!alreadyLoaded(e)) {
                throw e;
            }
        }
    }

    static boolean alreadyLoaded(UnsatisfiedLinkError e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("already") || msg.contains("уже"));
    }

    static File extractModel(Context ctx, String folder) throws Exception {
        File dir = new File(ctx.getFilesDir(), "spotter/" + folder);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("нет каталога модели");
        }
        String prefix = "assets/quark/spotter_models/activation/" + folder + "/";
        ZipFile zip = new ZipFile(SDK_APK);
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean any = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                String rel = name.substring(prefix.length());
                if (rel.length() == 0 || rel.indexOf("..") >= 0) {
                    continue;
                }
                File dest = new File(dir, rel);
                if ("flags.txt".equals(rel)) {
                    writeFlags(zip, entry, dest);
                } else {
                    copyEntry(zip, entry, dest);
                }
                any = true;
            }
            if (!any) {
                throw new IllegalStateException("в прошивке нет модели " + folder);
            }
        } finally {
            zip.close();
        }
        return dir;
    }

    static void writeFlags(ZipFile zip, ZipEntry entry, File dest) throws Exception {
        InputStream in = zip.getInputStream(entry);
        byte[] raw = readAll(in);
        in.close();
        String text = new String(raw, "UTF-8");
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("raw-mic-channels ")) {
                line = "raw-mic-channels 0";
            } else if (line.startsWith("freq-filter-enabled ")) {
                line = "freq-filter-enabled false";
            }
            if (i > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        FileOutputStream file = new FileOutputStream(dest);
        file.write(out.toString().getBytes("UTF-8"));
        file.close();
    }

    static void copyZip(ZipFile zip, String entryName, File dest) throws Exception {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IllegalStateException("нет " + entryName);
        }
        copyEntry(zip, entry, dest);
    }

    static void copyEntry(ZipFile zip, ZipEntry entry, File dest) throws Exception {
        if (dest.isFile() && entry.getSize() > 0 && dest.length() == entry.getSize()) {
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        InputStream in = zip.getInputStream(entry);
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
        if (dest.length() < 16) {
            throw new IllegalStateException("пустой " + dest.getName());
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
