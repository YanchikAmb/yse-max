package com.yse.station;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.ToneGenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.util.Calendar;
import java.util.Locale;

/**
 * Полноценные будильники и мелодии будильника — 1-в-1 совместимы с masha/panel.html и yse_talk.py.
 */
public class Alarms {
    static final String[] DAY_NAMES = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    final Web web;
    final File alarmsFile;
    final File toneMetaFile;
    final File tonePcmFile;
    final File toneBinFile;
    JSONArray list = new JSONArray();
    String toneLabel = "Встроенная";
    int toneVolume = 40;
    volatile boolean ringing;
    volatile Thread ringThread;
    volatile MediaPlayer ringPlayer;
    volatile AudioTrack ringTrack;
    long lastFiredMinute = -1;

    Alarms(Web web) {
        this.web = web;
        File dir = web.getFilesDir();
        this.alarmsFile = new File(dir, "alarms_v2.json");
        this.toneMetaFile = new File(dir, "alarm_tone.json");
        this.tonePcmFile = new File(dir, "alarm_custom.pcm");
        this.toneBinFile = new File(dir, "alarm_custom.bin");
        load();
    }

    synchronized void load() {
        try {
            if (alarmsFile.isFile()) {
                byte[] b = readBytes(alarmsFile);
                list = new JSONArray(new String(b, Web.UTF8));
            }
        } catch (Throwable ignored) {
            list = new JSONArray();
        }
        try {
            if (toneMetaFile.isFile()) {
                byte[] b = readBytes(toneMetaFile);
                JSONObject m = new JSONObject(new String(b, Web.UTF8));
                toneLabel = m.optString("label", "Встроенная");
                toneVolume = clamp(m.optInt("volume", 40), 5, 100);
            }
        } catch (Throwable ignored) {
        }
    }

    synchronized void save() {
        writeBytes(alarmsFile, list.toString().getBytes(Web.UTF8));
    }

    synchronized void saveToneMeta() {
        try {
            JSONObject m = new JSONObject();
            m.put("label", toneLabel);
            m.put("volume", toneVolume);
            writeBytes(toneMetaFile, m.toString().getBytes(Web.UTF8));
        } catch (Throwable ignored) {
        }
    }

    synchronized String toJson() throws Exception {
        long now = System.currentTimeMillis();
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            JSONObject row = new JSONObject();
            String id = a.optString("id", "a" + i);
            String days = normalizeDays(a.optString("days", "-"));
            String time = a.optString("time", "07:00");
            String label = a.optString("label", "");
            boolean on = a.optBoolean("on", true);
            long at = a.optLong("at", 0);
            if (on && !"-".equals(days)) {
                at = nextOccurrence(time, days, now);
                a.put("at", at);
            } else if (on && at <= 0) {
                at = nextOnce(time, now);
                a.put("at", at);
            }
            row.put("id", id);
            row.put("time", time);
            row.put("days", days);
            row.put("repeat", repeatLabel(days));
            row.put("label", label);
            row.put("on", on);
            row.put("at", at / 1000L);
            row.put("stamp", at / 1000L);
            row.put("when", formatWhen(at, time, days, now));
            row.put("left", on ? formatLeft(at - now) : "");
            out.put(row);
        }
        JSONObject res = new JSONObject();
        res.put("alarms", out);
        res.put("ringing", ringing);
        return res.toString();
    }

    synchronized String handlePost(String text) throws Exception {
        JSONObject req = new JSONObject(text);
        String op = req.optString("op", "").trim().toLowerCase(Locale.US);
        long now = System.currentTimeMillis();

        if ("stop".equals(op) || "hush".equals(op)) {
            stopRinging();
            return toJson();
        }
        if ("clear".equals(op) || req.optBoolean("clear")) {
            stopRinging();
            list = new JSONArray();
            save();
            return toJson();
        }
        if ("del".equals(op) || req.has("drop")) {
            String id = req.optString("id", "");
            int dropIdx = req.optInt("drop", -1);
            JSONArray next = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                if ((!id.isEmpty() && id.equals(a.optString("id"))) || (dropIdx >= 0 && i == dropIdx)) {
                    continue;
                }
                next.put(a);
            }
            list = next;
            save();
            return toJson();
        }
        if ("on".equals(op) || "off".equals(op)) {
            String id = req.optString("id", "");
            boolean wantOn = "on".equals(op);
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                if (id.equals(a.optString("id"))) {
                    a.put("on", wantOn);
                    if (wantOn) {
                        String days = normalizeDays(a.optString("days", "-"));
                        String tm = a.optString("time", "07:00");
                        a.put("at", "-".equals(days) ? nextOnce(tm, now) : nextOccurrence(tm, days, now));
                    }
                    break;
                }
            }
            if (!wantOn) {
                stopRinging();
            }
            save();
            return toJson();
        }
        if ("edit".equals(op)) {
            String id = req.optString("id", "");
            String when = req.optString("when", req.optString("time", "")).trim();
            String days = extractDays(req);
            String label = req.optString("label", "").trim();
            if (label.length() > 40) {
                label = label.substring(0, 40);
            }
            validateTime(when);
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                if (id.equals(a.optString("id"))) {
                    a.put("time", when);
                    a.put("days", days);
                    a.put("label", label);
                    a.put("on", true);
                    a.put("at", "-".equals(days) ? nextOnce(when, now) : nextOccurrence(when, days, now));
                    break;
                }
            }
            save();
            return toJson();
        }

        // Создание нового будильника
        JSONObject a = new JSONObject();
        a.put("id", "a" + (System.currentTimeMillis() % 100000000L) + "_" + list.length());
        a.put("on", true);
        String label = req.optString("label", "").trim();
        if (label.length() > 40) {
            label = label.substring(0, 40);
        }

        if (req.has("stamp")) {
            long sec = Long.parseLong(String.valueOf(req.get("stamp")).trim());
            long at = sec * 1000L;
            if (at <= now) {
                throw new IllegalArgumentException("alarms");
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(at);
            String tm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            a.put("time", tm);
            a.put("days", "-");
            a.put("at", at);
            a.put("label", label);
        } else if (req.has("mins")) {
            int mins = Integer.parseInt(String.valueOf(req.get("mins")).trim());
            if (mins < 1 || mins > 1440) {
                throw new IllegalArgumentException("alarms");
            }
            long at = now + mins * 60000L;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(at);
            String tm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            a.put("time", tm);
            a.put("days", "-");
            a.put("at", at);
            a.put("label", label.isEmpty() ? ("через " + mins + " мин") : label);
        } else if (req.has("at")) {
            long at = req.getLong("at");
            if (at <= now) {
                throw new IllegalArgumentException("alarms");
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(at);
            String tm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            a.put("time", tm);
            a.put("days", "-");
            a.put("at", at);
            a.put("label", label);
        } else {
            String when = req.optString("when", req.optString("time", "")).trim();
            validateTime(when);
            String days = extractDays(req);
            a.put("time", when);
            a.put("days", days);
            a.put("at", "-".equals(days) ? nextOnce(when, now) : nextOccurrence(when, days, now));
            a.put("label", label);
        }
        list.put(a);
        save();
        return toJson();
    }

    static String extractDays(JSONObject req) {
        Object d = req.opt("days");
        if (d instanceof JSONArray) {
            JSONArray arr = (JSONArray) d;
            if (arr.length() == 0) {
                return "-";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(arr.optInt(i, -1));
            }
            return normalizeDays(sb.toString());
        }
        return normalizeDays(req.optString("days", "-"));
    }

    void stopRing() {
        stopRinging();
    }

    synchronized String toneJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("label", toneLabel);
        o.put("volume", toneVolume);
        o.put("custom", tonePcmFile.isFile() || toneBinFile.isFile());
        o.put("ok", true);
        return o.toString();
    }

    synchronized String setVolume(int vol) throws Exception {
        toneVolume = clamp(vol, 5, 100);
        saveToneMeta();
        return toneJson();
    }

    synchronized String setBuiltin() throws Exception {
        return setToneBuiltin();
    }

    synchronized String saveCustomFile(byte[] raw, int len, String name) throws Exception {
        byte[] data = (raw != null && len >= 0 && len < raw.length) ? java.util.Arrays.copyOf(raw, len) : raw;
        return setToneBin(data, name);
    }

    synchronized String saveCustomPcm(byte[] raw, int len, String label) throws Exception {
        byte[] data = (raw != null && len >= 0 && len < raw.length) ? java.util.Arrays.copyOf(raw, len) : raw;
        if (data == null || data.length < 4000) {
            throw new IllegalArgumentException("Мелодия пустая");
        }
        if (toneBinFile.exists()) {
            toneBinFile.delete();
        }
        writeBytes(tonePcmFile, data);
        toneLabel = (label == null || label.trim().isEmpty()) ? "Яндекс Музыка" : label.trim();
        saveToneMeta();
        return toneJson();
    }

    synchronized String setToneVolume(String body) throws Exception {
        int vol = toneVolume;
        String s = body == null ? "" : body.trim();
        if (s.startsWith("{")) {
            vol = new JSONObject(s).optInt("volume", toneVolume);
        } else if (s.length() > 0) {
            vol = Integer.parseInt(s);
        }
        toneVolume = clamp(vol, 5, 100);
        saveToneMeta();
        return toneJson();
    }

    synchronized String setToneBuiltin() throws Exception {
        if (tonePcmFile.exists()) {
            tonePcmFile.delete();
        }
        if (toneBinFile.exists()) {
            toneBinFile.delete();
        }
        toneLabel = "Встроенная";
        saveToneMeta();
        return toneJson();
    }

    synchronized String setToneBin(byte[] raw, String label) throws Exception {
        if (raw == null || raw.length < 256) {
            throw new IllegalArgumentException("Файл слишком маленький");
        }
        if (tonePcmFile.exists()) {
            tonePcmFile.delete();
        }
        writeBytes(toneBinFile, raw);
        toneLabel = (label == null || label.trim().isEmpty()) ? "Своя мелодия" : label.trim();
        saveToneMeta();
        return toneJson();
    }

    synchronized String setTonePcm(byte[] pcm, String query) throws Exception {
        if (pcm == null || pcm.length < 4000) {
            throw new IllegalArgumentException("Мелодия пустая");
        }
        if (toneBinFile.exists()) {
            toneBinFile.delete();
        }
        writeBytes(tonePcmFile, pcm);
        String label = "Яндекс Музыка";
        if (query != null && query.contains("label=")) {
            for (String part : query.split("&")) {
                if (part.startsWith("label=")) {
                    try {
                        label = URLDecoder.decode(part.substring(6), "UTF-8").trim();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (label.isEmpty()) {
            label = "Яндекс Музыка";
        }
        toneLabel = label;
        saveToneMeta();
        return toneJson();
    }

    void tick() {
        long now = System.currentTimeMillis();
        long curMinute = now / 60000L;
        boolean fire = false;
        String fireLabel = "Будильник";
        synchronized (this) {
            boolean changed = false;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                boolean on = a.optBoolean("on", true);
                String days = normalizeDays(a.optString("days", "-"));
                long at = a.optLong("at", 0);
                if (on && at > 0 && at <= now && curMinute != lastFiredMinute) {
                    fire = true;
                    String lbl = a.optString("label", "");
                    fireLabel = lbl.isEmpty() ? ("Будильник " + a.optString("time", "")) : lbl;
                    if ("-".equals(days)) {
                        // Одноразовый будильник отработал — удаляем
                        changed = true;
                        continue;
                    } else {
                        try {
                            a.put("at", nextOccurrence(a.optString("time", "07:00"), days, now + 61000L));
                            changed = true;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                kept.put(a);
            }
            if (changed) {
                list = kept;
                save();
            }
        }
        if (fire) {
            lastFiredMinute = curMinute;
            startRinging(fireLabel);
        }
    }

    void startRinging(final String label) {
        stopRinging();
        ringing = true;
        Web.heard = label;
        Web.face = "говорю";
        Web.touch();
        Thread t = new Thread(new Runnable() {
            public void run() {
                int savedVol = web.volumePercent();
                try {
                    web.setVolumePercent(toneVolume);
                } catch (Throwable ignored) {
                }
                long endAt = System.currentTimeMillis() + 180000L;
                try {
                    while (ringing && web.run && System.currentTimeMillis() < endAt) {
                        if (tonePcmFile.isFile() && tonePcmFile.length() > 4000) {
                            playPcmLoop(endAt);
                        } else if (toneBinFile.isFile() && toneBinFile.length() > 256) {
                            playBinLoop(endAt);
                        } else {
                            playBuiltinOnce();
                            Thread.sleep(600);
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    ringing = false;
                    try {
                        web.setVolumePercent(savedVol);
                    } catch (Throwable ignored) {
                    }
                    if ("говорю".equals(Web.face)) {
                        Web.face = "слушаю";
                        Web.touch();
                    }
                }
            }
        });
        ringThread = t;
        t.start();
    }

    void stopRinging() {
        ringing = false;
        MediaPlayer mp = ringPlayer;
        ringPlayer = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
        AudioTrack at = ringTrack;
        ringTrack = null;
        if (at != null) {
            try {
                at.stop();
            } catch (Throwable ignored) {
            }
            try {
                at.release();
            } catch (Throwable ignored) {
            }
        }
        Thread t = ringThread;
        ringThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    void playPcmLoop(long endAt) {
        int rate = 16000;
        int min = Math.max(4096, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 2, AudioTrack.MODE_STREAM);
        ringTrack = track;
        FileInputStream in = null;
        try {
            track.play();
            in = new FileInputStream(tonePcmFile);
            byte[] buf = new byte[4096];
            int n;
            while (ringing && System.currentTimeMillis() < endAt && (n = in.read(buf)) > 0) {
                track.write(buf, 0, n);
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            ringTrack = null;
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    void playBinLoop(long endAt) {
        MediaPlayer mp = new MediaPlayer();
        ringPlayer = mp;
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(toneBinFile.getAbsolutePath());
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            while (ringing && mp.isPlaying() && System.currentTimeMillis() < endAt) {
                Thread.sleep(250);
            }
        } catch (Throwable ignored) {
        } finally {
            ringPlayer = null;
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
    }

    void playBuiltinOnce() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 95);
            try {
                for (int i = 0; i < 3 && ringing; i++) {
                    tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260);
                    Thread.sleep(360);
                }
            } finally {
                tg.release();
            }
        } catch (Throwable ignored) {
        }
    }

    static void validateTime(String hhmm) {
        String[] p = hhmm.split(":");
        if (p.length != 2) {
            throw new IllegalArgumentException("alarms");
        }
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        if (h < 0 || h > 23 || m < 0 || m > 59) {
            throw new IllegalArgumentException("alarms");
        }
    }

    static String normalizeDays(String days) {
        if (days == null) {
            return "-";
        }
        String d = days.trim();
        if (d.length() == 7 && d.matches("[01]{7}") && d.contains("1")) {
            return d;
        }
        if (d.matches("[0-6](,[0-6])*")) {
            char[] mask = "0000000".toCharArray();
            boolean any = false;
            for (String p : d.split(",")) {
                int idx = Integer.parseInt(p.trim());
                if (idx >= 0 && idx < 7) {
                    mask[idx] = '1';
                    any = true;
                }
            }
            return any ? new String(mask) : "-";
        }
        return "-";
    }

    static String repeatLabel(String days) {
        if ("-".equals(days)) {
            return "";
        }
        if ("1111111".equals(days)) {
            return "каждый день";
        }
        if ("1111100".equals(days)) {
            return "по будням";
        }
        if ("0000011".equals(days)) {
            return "по выходным";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (days.charAt(i) == '1') {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(DAY_NAMES[i]);
            }
        }
        return sb.toString();
    }

    static long nextOnce(String hhmm, long now) {
        String[] p = hhmm.split(":");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(p[1]));
        if (c.getTimeInMillis() <= now) {
            c.add(Calendar.DATE, 1);
        }
        return c.getTimeInMillis();
    }

    static long nextOccurrence(String hhmm, String days, long now) {
        String[] p = hhmm.split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        for (int add = 0; add < 8; add++) {
            Calendar cand = (Calendar) c.clone();
            cand.add(Calendar.DATE, add);
            int dow = cand.get(Calendar.DAY_OF_WEEK); // Sun=1, Mon=2 .. Sat=7
            int idx = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY);
            if (days.charAt(idx) == '1' && cand.getTimeInMillis() > now) {
                return cand.getTimeInMillis();
            }
        }
        return nextOnce(hhmm, now);
    }

    static String formatWhen(long at, String time, String days, long now) {
        if (!"-".equals(days)) {
            return time;
        }
        Calendar cNow = Calendar.getInstance();
        cNow.setTimeInMillis(now);
        Calendar cAt = Calendar.getInstance();
        cAt.setTimeInMillis(at);
        int dNow = cNow.get(Calendar.DAY_OF_YEAR) + cNow.get(Calendar.YEAR) * 400;
        int dAt = cAt.get(Calendar.DAY_OF_YEAR) + cAt.get(Calendar.YEAR) * 400;
        if (dAt == dNow) {
            return "сегодня " + time;
        }
        if (dAt == dNow + 1) {
            return "завтра " + time;
        }
        return String.format(Locale.US, "%02d.%02d %s",
                cAt.get(Calendar.DAY_OF_MONTH), cAt.get(Calendar.MONTH) + 1, time);
    }

    static String formatLeft(long diffMs) {
        if (diffMs <= 0) {
            return "сейчас";
        }
        long mins = (diffMs + 59999L) / 60000L;
        long days = mins / 1440L;
        long hrs = (mins % 1440L) / 60L;
        long rem = mins % 60L;
        if (days > 0) {
            return "через " + days + " д " + hrs + " ч";
        }
        if (hrs > 0) {
            return rem > 0 ? ("через " + hrs + " ч " + rem + " мин") : ("через " + hrs + " ч");
        }
        return "через " + rem + " мин";
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static byte[] readBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int pos = 0;
            while (pos < buf.length) {
                int n = in.read(buf, pos, buf.length - pos);
                if (n <= 0) {
                    break;
                }
                pos += n;
            }
            return buf;
        } finally {
            in.close();
        }
    }

    static void writeBytes(File f, byte[] data) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(data);
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
