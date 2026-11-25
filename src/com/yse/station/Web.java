package com.yse.station;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

public class Web extends Service {
    static final int PORT = 18080;
    /** Запасной адрес. Рабочий берётся из files/server.txt: строка 1 — IP, строка 2 — BASE. */
    static String HOST = "192.0.2.10";
    static int BASE = 18805;
    /** 16ch WAV с root-демона (yse-ear); как clean.ring у housed. */
    static final String[] EAR_LOOP = {
            "/storage/emulated/0/Download/yse_ear_loop.wav",
            "/sdcard/Download/yse_ear_loop.wav",
            "/data/local/tmp/yse_ear_loop.wav",
            "/data/opt/ear/loop.wav"
    };
    static final int EAR_FRAMES = 960;
    static final int EAR_CH = 16;
    static final int EAR_PCM_BYTES = EAR_FRAMES * EAR_CH * 2;
    static final Charset UTF8 = Charset.forName("UTF-8");
    static volatile String lastKey = "кнопка ещё не нажималась";
    static volatile String screenText = "Столовая";
    static volatile String face = "слушаю";
    static volatile int screenColor = 0xFF3AA0FF;
    static volatile Runnable onScreen;
    static volatile boolean screenUp;
    static volatile String heard = "После слова здесь будет фраза";
    static volatile String screenNote = "";
    static volatile int voiceWritten;
    static volatile int voicePlayed;
    static volatile int level;
    static volatile String levelNote = "микрофон запускается";
    static volatile OutputStream phraseOut;
    static volatile int phraseGen;
    static volatile long lastGoMs;
    static volatile long lastAliceBtnMs;
    static volatile String commands = "команды загружаются";
    volatile String heldWord = "";
    volatile long heldAt;
    String lastAct = "";
    long lastActAt;
    float wakePrev;
    static Web self;

    volatile boolean run;
    java.net.ServerSocket socket;
    JSONArray alarms = new JSONArray();
    Alarms alarmsMgr;
    BleGateway bleGw;
    volatile boolean localWakeMode;
    volatile String stockWord = "";

    static String stockTrace() {
        if (!Spotter.ready) {
            return "";
        }
        try {
            String text = Spotter.trace();
            return text == null ? "" : text;
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }
    volatile long lastModePingMs;
    volatile long speakMuteUntilMs;
    volatile String btNote = "";
    ConnectivityManager.NetworkCallback wifiHold;
    byte[] page;
    AudioTrack musicTrack;
    AudioTrack voiceTrack;
    final Object playLock = new Object();
    BroadcastReceiver volReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel channel = new NotificationChannel("station", "Столовая", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
        Notification note = new Notification.Builder(this, "station")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Столовая")
                .setContentText("панель :" + PORT)
                .build();
        startForeground(1, note);
        self = this;
        applyServer();
        loadPage();
        alarmsMgr = new Alarms(this);
        bleGw = new BleGateway(this);
        loadSettings();
        if (!run) {
            run = true;
            new Thread(new Runnable() {
                public void run() {
                    LocalJarvis.init(Web.this, 0.74f);
                }
            }).start();
            new Thread(new Loop()).start();
            new Thread(new Go()).start();
            new Thread(new Chime()).start();
            new Thread(new MicUart()).start();
            new Thread(new AliceButtonWatcher()).start();
            Ring.init(this);
            try {
                volReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                        if (stream == AudioManager.STREAM_MUSIC || stream == -1) {
                            int pct = volumePercent();
                            Ring.showVolume(pct);
                        }
                    }
                };
                registerReceiver(volReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
            } catch (Throwable ignored) {
            }
            LedFace.start(this);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        holdWifi();
                        Thread.sleep(800);
                        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                        if (wm != null) {
                            wm.reconnect();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
            new Thread(new Runnable() {
                public void run() {
                    loadCommands();
                }
            }).start();
            new Thread(new Runnable() {
                public void run() {
                    for (int i = 0; i < 8 && run; i++) {
                        try {
                            pushSettings();
                            return;
                        } catch (Throwable ignored) {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException wake) {
                                return;
                            }
                        }
                    }
                }
            }).start();
        }
        return START_STICKY;
    }

    void showPanel() {
        if (screenUp) {
            return;
        }
        Intent panel = new Intent(this, Panel.class);
        panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(panel);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        run = false;
        try {
            if (volReceiver != null) {
                unregisterReceiver(volReceiver);
                volReceiver = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    void loadPage() {
        InputStream in = null;
        try {
            in = getAssets().open("panel.html");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            page = buf.toByteArray();
        } catch (Throwable t) {
            page = ("<h1>нет страницы " + t.getMessage() + "</h1>").getBytes(UTF8);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    File settingsFile() {
        return new File(getFilesDir(), "settings.json");
    }

    /** Сервис yse-home пишет этот файл перед стартом. Пустой файл оставляет запасной адрес. */
    void applyServer() {
        try {
            File file = new File(getFilesDir(), "server.txt");
            if (!file.exists()) {
                return;
            }
            byte[] buf = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int got = in.read(buf);
            in.close();
            if (got <= 0) {
                return;
            }
            String[] lines = new String(buf, 0, got, UTF8).split("\n");
            String host = lines[0].trim();
            if (host.length() > 0) {
                HOST = host;
            }
            if (lines.length > 1 && lines[1].trim().length() > 0) {
                BASE = Integer.parseInt(lines[1].trim());
            }
        } catch (Throwable ignored) {
        }
    }

    synchronized JSONObject settings() {
        try {
            File file = settingsFile();
            if (!file.exists()) {
                return defaults();
            }
            byte[] buf = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int got = in.read(buf);
            in.close();
            if (got <= 0) {
                return defaults();
            }
            return new JSONObject(new String(buf, 0, got, UTF8));
        } catch (Throwable t) {
            return defaults();
        }
    }

    JSONObject defaults() {
        JSONObject o = new JSONObject();
        try {
            o.put("nn", "ha");
            o.put("news", "yandex");
            o.put("stt", "gigaam");
            o.put("voice", "own:jarvis_new");
            o.put("wake", "джарвис-локально");
            o.put("persona", "");
            o.put("bt", "0");
            o.put("ble_gw", "1");
            o.put("mqtt_host", "192.168.209.15");
            o.put("mqtt_port", "1883");
            o.put("mqtt_user", "");
            o.put("mqtt_pass", "");
            o.put("llm_url", "");
            o.put("llm_model", "");
            o.put("llm_key", "");
            o.put("screen", "Столовая");
            o.put("wake_sound", "1");
            o.put("ring", "1");
            o.put("ring_color", "#3aa0ff");
            o.put("ring_bright", "160");
            o.put("ring_effect", "fill");
            o.put("lamp", "0");
            o.put("lamp_color", "#ffd8a8");
            o.put("lamp_bright", "180");
            o.put("screen_bright", "255");
            o.put("host_phrase", "0");
            o.put("max_stt", "0");
            o.put("vqe_ch", "1");
            o.put("news_url", "");
            o.put("news_model", "");
            o.put("news_key", "");
            o.put("alarms", new JSONArray());
        } catch (Throwable ignored) {
        }
        return o;
    }

    synchronized void saveSettings(JSONObject o) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(settingsFile());
            out.write(o.toString().getBytes(UTF8));
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
        applyScreen(o);
        Ring.apply(o);
        applyWakeAndBt(o);
        try {
            alarms = o.optJSONArray("alarms");
            if (alarms == null) {
                alarms = new JSONArray();
            }
        } catch (Throwable ignored) {
        }
        try {
            pushSettings();
        } catch (Throwable ignored) {
        }
    }

    void loadSettings() {
        JSONObject o = settings();
        applyScreen(o);
        Ring.apply(o);
        applyWakeAndBt(o);
        alarms = o.optJSONArray("alarms");
        if (alarms == null) {
            alarms = new JSONArray();
        }
    }

    void applyWakeAndBt(JSONObject o) {
        String w = o.optString("wake", "джарвис").trim().toLowerCase(Locale.US);
        boolean wantLocal = "джарвис-локально".equals(w) || "джарвис локально".equals(w);
        if (wantLocal != localWakeMode) {
            localWakeMode = wantLocal;
            if (wantLocal) {
                LocalJarvis.reset();
            }
        }
        String nextStock = "алиса".equals(w) ? "алиса" : "яндекс".equals(w) ? "яндекс" : "";
        if (!nextStock.equals(stockWord)) {
            stockWord = nextStock;
            final int gen = Spotter.beginArm(nextStock);
            if (nextStock.length() > 0) {
                final String word = nextStock;
                new Thread(new Runnable() {
                    public void run() {
                        Spotter.armWake(Web.this, word, gen);
                        touch();
                    }
                }).start();
            }
        }
        applyBluetooth("1".equals(o.optString("bt", "0")));
        if (bleGw != null) {
            bleGw.configure(o);
        }
    }

    void applyBluetooth(boolean on) {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                btNote = "no adapter";
                return;
            }
            if (!bt.isEnabled()) {
                bt.enable();
            }
            int mode = on ? BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                    : BluetoothAdapter.SCAN_MODE_CONNECTABLE;
            try {
                java.lang.reflect.Method m = bt.getClass().getMethod("setScanMode", int.class, int.class);
                m.invoke(bt, mode, 0);
                btNote = on ? "discoverable (A2DP Sink)" : "connectable";
            } catch (Throwable e1) {
                try {
                    java.lang.reflect.Method m2 = bt.getClass().getMethod("setScanMode", int.class);
                    m2.invoke(bt, mode);
                    btNote = on ? "discoverable" : "connectable";
                } catch (Throwable e2) {
                    btNote = "scanMode err: " + e2.getClass().getSimpleName();
                }
            }
        } catch (Throwable t) {
            btNote = "bt err: " + t.getClass().getSimpleName();
        }
    }

    void applyScreen(JSONObject o) {
        screenText = o.optString("screen", "Столовая");
        screenColor = color(o.optString("ring_color", "#3aa0ff"));
        int level = number(o.optString("screen_bright", "255"), 255);
        int maxB = number(LedFace.readText("/sys/class/backlight/fb4/max_brightness"), 200);
        if (maxB < 10 || maxB > 4095) {
            maxB = 200;
        }
        int scaled = Math.max(0, Math.min(maxB, level * maxB / 255));
        Ring.setScreenBright(scaled);
        String sysfs = Ring.writeNote("/sys/class/backlight/fb4/brightness", scaled);
        String via = systemBright(level);
        if (sysfs == null) {
            screenNote = "часы " + scaled + "/" + maxB;
        } else if (via == null) {
            screenNote = "часы " + level + " (система)";
        } else {
            screenNote = sysfs;
        }
        touch();
    }

    String systemBright(int level) {
        try {
            android.provider.Settings.System.putInt(
                    getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            if (!android.provider.Settings.System.putInt(
                    getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    level)) {
                return "settings false";
            }
            return null;
        } catch (Throwable t) {
            String msg = t.getMessage();
            return t.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
        }
    }

    static int number(String text, int fallback) {
        try {
            int value = Integer.parseInt(text);
            if (value < 1) {
                return 1;
            }
            if (value > 255) {
                return 255;
            }
            return value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    static void touch() {
        Ring.setMode(face);
        LedFace.sync(face, musicPlaying());
        if (onScreen != null) {
            onScreen.run();
        }
    }

    static boolean musicPlaying() {
        Web w = self;
        if (w == null) {
            return false;
        }
        AudioTrack track = w.musicTrack;
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (w.voiceTrack != null) {
            return false;
        }
        try {
            AudioManager am = (AudioManager) w.getSystemService(AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    static int color(String hex) {
        try {
            return (int) Long.parseLong(hex.replace("#", ""), 16) | 0xFF000000;
        } catch (Throwable t) {
            return 0xFF1C1C1C;
        }
    }

    String talkUrl() {
        return "http://" + HOST + ":" + (BASE + 3);
    }

    String musicUrl() {
        return "http://" + HOST + ":" + (BASE + 1);
    }

    void pushSettings() throws Exception {
        JSONObject o = settings();
        JSONObject body = new JSONObject();
        body.put("nn", o.optString("nn", "ha"));
        body.put("news", o.optString("news", "yandex"));
        body.put("stt", o.optString("stt", "gigaam"));
        body.put("voice", o.optString("voice", "own:jarvis_new"));
        body.put("wake", o.optString("wake", "джарвис-локально"));
        body.put("persona", o.optString("persona", ""));
        body.put("llm_url", o.optString("llm_url", ""));
        body.put("llm_model", o.optString("llm_model", ""));
        if (o.optString("llm_key", "").length() > 0) {
            body.put("llm_key", o.optString("llm_key", ""));
        }
        body.put("news_url", o.optString("news_url", ""));
        body.put("news_model", o.optString("news_model", ""));
        if (o.optString("news_key", "").length() > 0) {
            body.put("news_key", o.optString("news_key", ""));
        }
        http(talkUrl() + "/v1/settings", "POST", "application/json", body.toString().getBytes(UTF8), 4000);
    }

    String http(String url, String method, String type, byte[] body, int timeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setRequestMethod(method);
        if (body != null) {
            conn.setDoOutput(true);
            if (type != null) {
                conn.setRequestProperty("Content-Type", type);
            }
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] tmp = new byte[1024];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            in.close();
        }
        String text = new String(buf.toByteArray(), UTF8);
        if (code >= 400) {
            throw new IllegalStateException(code + " " + text);
        }
        return text;
    }

    void speak(String text) throws Exception {
        if (text == null || text.length() == 0) {
            return;
        }
        face = "говорю";
        touch();
        http(talkUrl() + "/v1/speak", "POST", "application/json",
                ("{\"text\":" + JSONObject.quote(text) + "}").getBytes(UTF8), 8000);
    }

    void ask(String text) throws Exception {
        String nn = settings().optString("nn", "openai");
        if (nn.length() == 0) {
            nn = "openai";
        }
        face = "думаю";
        touch();
        http(talkUrl() + "/v1/ask?nn=" + URLEncoder.encode(nn, "UTF-8"), "POST",
                "text/plain; charset=utf-8", text.getBytes(UTF8), 90000);
    }

    String music(String cmd) throws Exception {
        if ("play".equals(cmd)) {
            JSONObject now = new JSONObject(http(musicUrl() + "/v1/now", "GET", null, null, 4000));
            if ("stop".equals(now.optString("state"))) {
                String started = http(musicUrl() + "/v1/playlist", "POST", "text/plain; charset=utf-8",
                        "мне нравится".getBytes(UTF8), 20000);
                JSONObject row = new JSONObject(started);
                if (!row.optBoolean("ok", false)) {
                    return row.optString("error", "музыка не стартовала");
                }
                return "играет " + row.optString("title", "мне нравится");
            }
        }
        http(musicUrl() + "/v1/ctrl", "POST", "text/plain; charset=utf-8", cmd.getBytes(UTF8), 8000);
        JSONObject now = new JSONObject(http(musicUrl() + "/v1/now", "GET", null, null, 4000));
        String title = (now.optString("artist") + " " + now.optString("title")).trim();
        if (title.length() == 0) {
            title = now.optString("playlist", "");
        }
        return now.optString("state", "") + (title.length() == 0 ? "" : " " + title);
    }

    class Loop implements Runnable {
        public void run() {
            try {
                socket = new java.net.ServerSocket(PORT);
            } catch (Throwable t) {
                return;
            }
            while (run) {
                try {
                    final Socket accepted = socket.accept();
                    new Thread(new Runnable() {
                        public void run() {
                            serve(accepted);
                        }
                    }).start();
                } catch (Throwable ignored) {
                    if (!run) {
                        break;
                    }
                }
            }
        }
    }

    void loadCommands() {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(getFilesDir(), "spotter/command");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                commands = "нет каталога команд";
                return;
            }
            String[] names = {"flags.txt", "words.txt", "acoustic_model.nnet"};
            for (int i = 0; i < names.length; i++) {
                File dest = new File(dir, names[i]);
                if (dest.isFile() && dest.length() > 0) {
                    continue;
                }
                in = getAssets().open("spotter/command/" + names[i]);
                out = new FileOutputStream(dest);
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    out.write(tmp, 0, n);
                }
                out.close();
                out = null;
                in.close();
                in = null;
            }
            commands = Spotter.ready ? Spotter.open(dir.getAbsolutePath()) : Spotter.note;
        } catch (Throwable t) {
            commands = t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
        touch();
    }

    void noteCommand(String word) {
        if (word == null) {
            return;
        }
        word = word.trim();
        if (word.length() == 0 || word.charAt(0) == '<' || "SIL".equals(word)) {
            return;
        }
        if (phraseOut != null) {
            return;
        }
        heldWord = word;
        heldAt = System.currentTimeMillis();
    }

    void flushCommand() {
        String word = heldWord;
        if (word == null || word.length() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phraseOut != null) {
            heldWord = "";
            return;
        }
        if (now - heldAt < 3000) {
            return;
        }
        heldWord = "";
        if (word.equals(lastAct) && now - lastActAt < 1000) {
            return;
        }
        lastAct = word;
        lastActAt = now;
        final String runWord = word;
        new Thread(new Runnable() {
            public void run() {
                act(runWord);
            }
        }).start();
    }

    void act(String word) {
        face = word;
        touch();
        try {
            if ("тише".equals(word) || "громче".equals(word)) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int step = Math.max(1, max / 10);
                vol += "громче".equals(word) ? step : -step;
                if (vol < 0) {
                    vol = 0;
                }
                if (vol > max) {
                    vol = max;
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                lastKey = "громкость " + vol;
            } else if ("дальше".equals(word) || "следующий".equals(word)) {
                music("next");
            } else if ("назад".equals(word)) {
                music("prev");
            } else if ("играй".equals(word) || "продолжи".equals(word)) {
                music("play");
            } else if ("пауза".equals(word)) {
                music("pause");
            } else if ("стоп".equals(word) || "хватит".equals(word)) {
                phraseGen++;
                phraseOut = null;
                drop(voiceTrack);
                drop(musicTrack);
                try {
                    http(talkUrl() + "/v1/stop", "POST", "text/plain", "stop".getBytes(UTF8), 3000);
                } catch (Throwable ignored) {
                }
                music("stop");
            } else if ("лайк".equals(word) || "дизлайк".equals(word)) {
                http(musicUrl() + "/v1/mark", "POST", "text/plain; charset=utf-8",
                        ("лайк".equals(word) ? "like" : "dislike").getBytes(UTF8), 4000);
            }
        } catch (Throwable t) {
            lastKey = word + " " + t.getClass().getSimpleName();
        }
        touch();
    }

    void pushSamples(DatagramSocket udp, InetAddress host, short[] mono, int seq) throws Exception {
        long sum = 0;
        short[] wake = new short[320];
        for (int i = 0; i < 320; i++) {
            float x = mono[i];
            sum += (long) mono[i] * mono[i];
            float y = x - 0.97f * wakePrev;
            wakePrev = x;
            int sample = Math.round(y);
            if (sample > 32767) {
                sample = 32767;
            } else if (sample < -32768) {
                sample = -32768;
            }
            wake[i] = (short) sample;
        }
        int rms = (int) Math.sqrt(sum / 320.0);
        level = Math.min(100, rms / 30);
        levelNote = "канал " + micChannel + " (vqe=" + vqeCh + "), RMS " + rms + (micFail.length() == 0 ? "" : ". " + micFail);
        byte[] packet = new byte[4 + 640];
        ByteBuffer view = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        view.putInt(seq);
        byte[] pcm = new byte[640];
        ByteBuffer pcmView = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 320; i++) {
            view.putShort(wake[i]);
            pcmView.putShort(mono[i]);
        }
        try {
            udp.send(new DatagramPacket(packet, packet.length, host, BASE));
        } catch (Throwable ignored) {
        }
        String deviceWord = localWakeMode ? "джарвис-локально" : stockWord;
        if (deviceWord.length() > 0) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastModePingMs >= 3000L) {
                lastModePingMs = nowMs;
                try {
                    byte[] ping = ("YSEMODE " + deviceWord).getBytes(UTF8);
                    udp.send(new DatagramPacket(ping, ping.length, host, BASE));
                } catch (Throwable ignored) {
                }
            }
            if (stockWord.length() > 0 && Spotter.wakeReady) {
                String heard = Spotter.feedWake(pcm);
                String heardLow = heard == null ? "" : heard.toLowerCase(java.util.Locale.ROOT);
                if (heardLow.length() > 0 && !Ring.isActiveMode(face)
                        && phraseOut == null && nowMs >= speakMuteUntilMs && nowMs - lastGoMs >= 2500L
                        && heardLow.indexOf(stockWord) >= 0) {
                    lastGoMs = nowMs;
                    try {
                        byte[] wakePkt = ("YSEWAKE " + nowMs + " " + stockWord).getBytes(UTF8);
                        udp.send(new DatagramPacket(wakePkt, wakePkt.length, host, BASE));
                    } catch (Throwable ignored) {
                    }
                    if (alarmsMgr != null && alarmsMgr.ringing) {
                        alarmsMgr.stopRing();
                    }
                    lastKey = stockWord;
                    Ring.pulseAlice(1500L);
                    face = "говорите";
                    touch();
                    if (!"0".equals(settings().optString("wake_sound", "1"))) {
                        beep();
                    }
                    startPhrase();
                }
            }
            if (localWakeMode && LocalJarvis.ready && !Ring.isActiveMode(face) && phraseOut == null && nowMs >= speakMuteUntilMs) {
                int hit = LocalJarvis.feed320(wake, musicTrack != null);
                if (hit == 1 && nowMs - lastGoMs >= 2500L) {
                    lastGoMs = nowMs;
                    LocalJarvis.reset();
                    try {
                        byte[] wakePkt = ("YSEWAKE " + nowMs + " джарвис-локально").getBytes(UTF8);
                        udp.send(new DatagramPacket(wakePkt, wakePkt.length, host, BASE));
                    } catch (Throwable ignored) {
                    }
                    if (alarmsMgr != null && alarmsMgr.ringing) {
                        alarmsMgr.stopRing();
                    }
                    lastKey = "джарвис-локально";
                    Ring.pulseAlice(1500L);
                    face = "говорите";
                    touch();
                    if (!"0".equals(settings().optString("wake_sound", "1"))) {
                        beep();
                    }
                    startPhrase();
                }
            }
        }
        OutputStream out = phraseOut;
        if (out != null) {
            try {
                out.write(pcm);
                out.flush();
            } catch (Throwable ignored) {
                phraseOut = null;
            }
        }
        if (Spotter.ready && commands.startsWith("команды")) {
            String hit = Spotter.feed(pcm);
            if (hit != null && hit.length() > 0) {
                String[] lines = hit.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    noteCommand(lines[i]);
                }
            }
        }
        flushCommand();
    }

    volatile int micChannel;
    volatile int vqeCh = 1;
    volatile int micSession;
    volatile int micRate;
    volatile int micCount;
    final int[] chRms = new int[16];
    volatile byte[] dumpBuf;
    volatile int dumpPos;
    String micFail = "";

    void noteMic(String text) {
        if (micFail.length() > 180) {
            return;
        }
        micFail = micFail.length() == 0 ? text : micFail + "; " + text;
    }

    AudioRecord tryWide(int mask) {
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelIndexMask(mask)
                    .build();
            int count = fmt.getChannelCount();
            // Точно как у com.yandex.io.sdk: FrmCnt = 9216 = 3 * 3072 (chunkSize YandexVQE)
            int bufBytes = Math.max(count, 1) * 9216 * 2;
            AudioRecord rec = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufBytes)
                    .build();
            if (rec.getState() == AudioRecord.STATE_INITIALIZED && rec.getChannelCount() >= 9) {
                rec.startRecording();
                if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop();
                    micChannel = vqeCh;
                    micSession = rec.getAudioSessionId();
                    micRate = rec.getSampleRate();
                    micCount = rec.getChannelCount();
                    micFail = "";
                    return rec;
                }
                noteMic("wide start=" + rec.getRecordingState());
            } else {
                noteMic("маска " + mask + " состояние " + rec.getState() + " каналов " + rec.getChannelCount());
            }
            rec.release();
        } catch (Throwable t) {
            String msg = t.getMessage();
            noteMic("маска " + mask + " " + t.getClass().getSimpleName()
                    + (msg == null ? "" : " " + msg));
        }
        return null;
    }

    AudioRecord openMic() {
        micFail = "";
        // На Station Max YandexVQE работает внутри audioserver поверх AudioSource.MIC 48kHz 16ch (0xFFFF).
        AudioRecord wide = tryWide(0xFFFF);
        if (wide != null) {
            return wide;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (int i = 0; i < all.length; i++) {
                if (all[i].getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    continue;
                }
                int[] masks = all[i].getChannelIndexMasks();
                for (int m = 0; m < masks.length; m++) {
                    AudioRecord rec = tryWide(masks[m]);
                    if (rec != null) {
                        return rec;
                    }
                }
            }
        } catch (Throwable t) {
            noteMic(t.getClass().getSimpleName());
        }
        micChannel = -1;
        levelNote = micFail.length() == 0 ? "wide mic start fail" : micFail;
        return null;
    }

    File earLoopFile() {
        for (int i = 0; i < EAR_LOOP.length; i++) {
            File f = new File(EAR_LOOP[i]);
            if (f.length() > 44 + EAR_PCM_BYTES) {
                try {
                    RandomAccessFile probe = new RandomAccessFile(f, "r");
                    probe.close();
                    return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    int wavHeaderBytes(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] hdr = new byte[12];
            if (raf.read(hdr) != 12) {
                return 44;
            }
            if (hdr[0] != 'R' || hdr[1] != 'I' || hdr[2] != 'F' || hdr[3] != 'F') {
                return 0;
            }
            raf.seek(12);
            while (raf.getFilePointer() + 8 <= raf.length()) {
                byte[] id = new byte[4];
                if (raf.read(id) != 4) {
                    break;
                }
                int size = Integer.reverseBytes(raf.readInt());
                if (id[0] == 'd' && id[1] == 'a' && id[2] == 't' && id[3] == 'a') {
                    return (int) (raf.getFilePointer());
                }
                raf.seek(raf.getFilePointer() + size);
            }
        } finally {
            raf.close();
        }
        return 44;
    }

    boolean fillEarBlock(File wav, int header, short[] block) {
        try {
            RandomAccessFile raf = new RandomAccessFile(wav, "r");
            try {
                long need = header + EAR_PCM_BYTES;
                long len = raf.length();
                if (len < need) {
                    return false;
                }
                raf.seek(len - EAR_PCM_BYTES);
                byte[] raw = new byte[EAR_PCM_BYTES];
                if (raf.read(raw) != EAR_PCM_BYTES) {
                    return false;
                }
                ByteBuffer view = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < block.length; i++) {
                    block[i] = view.getShort();
                }
                return true;
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            noteMic("ear-loop " + t.getClass().getSimpleName());
            return false;
        }
    }

    boolean runEarLoop(DatagramSocket udp, InetAddress host) throws Exception {
        File wav = earLoopFile();
        if (wav == null) {
            return false;
        }
        int header = wavHeaderBytes(wav);
        if (header <= 0) {
            return false;
        }
        short[] block = new short[EAR_FRAMES * EAR_CH];
        micFail = "";
        micChannel = -2;
        levelNote = "ear-loop " + wav.getName();
        int seq = 0;
        int idle = 0;
        while (run) {
            if (!fillEarBlock(wav, header, block)) {
                idle++;
                if (idle > 200) {
                    return false;
                }
                Thread.sleep(150);
                continue;
            }
            idle = 0;
            long ref = 0;
            for (int i = 0; i < EAR_FRAMES; i += 3) {
                int sample = block[i * EAR_CH + 8];
                ref += (long) sample * sample;
            }
            int channel = ref / (EAR_FRAMES / 3) > 250000L ? 2 : 0;
            micChannel = channel;
            short[] mono = new short[320];
            for (int i = 0; i < 320; i++) {
                mono[i] = block[(i * 3) * EAR_CH + channel];
            }
            pushSamples(udp, host, mono, seq);
            seq++;
            Thread.sleep(150);
        }
        return true;
    }

    class MicUart implements Runnable {
        public void run() {
            int seq = 0;
            while (run) {
                DatagramSocket udp = null;
                AudioRecord rec = null;
                try {
                    vqeCh = Integer.parseInt(settings().optString("vqe_ch", "1"));
                } catch (Throwable ignored) {
                    vqeCh = 1;
                }
                try {
                    udp = new DatagramSocket();
                    InetAddress host = InetAddress.getByName(HOST);
                    rec = openMic();
                    if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        levelNote = micFail.length() == 0 ? "микрофон не открылся" : micFail;
                        Thread.sleep(3000);
                        continue;
                    }
                    int ch = rec.getChannelCount();
                    int rate = rec.getSampleRate();
                    boolean array = ch >= 9;
                    int ratio = Math.max(1, rate / 16000);
                    int frameBytes = ch * 2;
                    // Читаем ровно чанком YandexVQE (3072 фрейма = 64 мс при 48кГц)
                    int chunkFrames = array ? 3072 : 320;
                    byte[] raw = new byte[frameBytes * chunkFrames];
                    short[] mono = new short[320];
                    int monoFill = 0;
                    long[] sq = new long[16];
                    rec.startRecording();
                    levelNote = array
                            ? ("hal-vqe " + ch + "ch " + rate + "Hz sess=" + rec.getAudioSessionId() + " pick=" + vqeCh)
                            : ("mono " + ch + "ch " + rate + "Hz");
                    while (run && rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        int n = rec.read(raw, 0, raw.length);
                        if (n < frameBytes) {
                            Thread.sleep(5);
                            continue;
                        }
                        byte[] db = dumpBuf;
                        if (db != null) {
                            int pos = dumpPos;
                            int rem = db.length - pos;
                            if (rem > 0) {
                                int cp = Math.min(rem, n);
                                System.arraycopy(raw, 0, db, pos, cp);
                                dumpPos = pos + cp;
                            }
                        }
                        int frames = n / frameBytes;
                        ByteBuffer view = ByteBuffer.wrap(raw, 0, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN);
                        if (array) {
                            int pick = vqeCh;
                            if (pick < 0 || pick >= ch) {
                                pick = 1;
                            }
                            micChannel = pick;
                            for (int c = 0; c < 16; c++) {
                                sq[c] = 0;
                            }
                            int acc = 0;
                            int accN = 0;
                            for (int f = 0; f < frames; f++) {
                                short picked = 0;
                                for (int c = 0; c < ch; c++) {
                                    short s = view.getShort();
                                    if (c < 16) {
                                        sq[c] += (long) s * s;
                                    }
                                    if (c == pick) {
                                        picked = s;
                                    }
                                }
                                acc += picked;
                                accN++;
                                if (accN >= ratio) {
                                    mono[monoFill++] = (short) (acc / accN);
                                    acc = 0;
                                    accN = 0;
                                    if (monoFill >= 320) {
                                        pushSamples(udp, host, mono, seq++);
                                        monoFill = 0;
                                    }
                                }
                            }
                            if (frames > 0) {
                                for (int c = 0; c < Math.min(ch, 16); c++) {
                                    chRms[c] = (int) Math.sqrt((double) sq[c] / frames);
                                }
                            }
                        } else {
                            int pick = 0;
                            int acc = 0;
                            int accN = 0;
                            for (int f = 0; f < frames; f++) {
                                short sample = 0;
                                for (int c = 0; c < ch; c++) {
                                    short s = view.getShort();
                                    if (c == pick) {
                                        sample = s;
                                    }
                                }
                                acc += sample;
                                accN++;
                                if (accN >= ratio) {
                                    mono[monoFill++] = (short) (acc / accN);
                                    acc = 0;
                                    accN = 0;
                                    if (monoFill >= 320) {
                                        pushSamples(udp, host, mono, seq++);
                                        monoFill = 0;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    String msg = t.getMessage();
                    levelNote = "микрофон " + t.getClass().getSimpleName()
                            + (msg == null ? "" : " " + msg);
                } finally {
                    if (rec != null) {
                        try {
                            rec.stop();
                        } catch (Throwable ignored) {
                        }
                        rec.release();
                    }
                    if (udp != null) {
                        udp.close();
                    }
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    class Go implements Runnable {
        public void run() {
            DatagramSocket box = null;
            try {
                box = new DatagramSocket(18081);
                byte[] buf = new byte[32];
                while (run) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    box.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), Charset.forName("US-ASCII")).trim();
                    if (text.startsWith("YSEGO")) {
                        long now = System.currentTimeMillis();
                        if (now - lastGoMs < 600) {
                            continue;
                        }
                        lastGoMs = now;
                        face = "говорите";
                        touch();
                        if (!"0".equals(settings().optString("wake_sound", "1"))) {
                            beep();
                        }
                        startPhrase();
                    } else if (text.startsWith("YSEIDLE")) {
                        face = "слушаю";
                        touch();
                    }
                }
            } catch (Throwable t) {
                if (run) {
                    face = "сигнал слова не открылся";
                    touch();
                }
            } finally {
                if (box != null) {
                    box.close();
                }
            }
        }
    }

    void beep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
        } catch (Throwable ignored) {
        }
    }

    static void adjustVolume(int delta) {
        Web w = self;
        if (w == null) {
            return;
        }
        AudioManager am = (AudioManager) w.getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) + delta;
        if (vol < 0) {
            vol = 0;
        }
        if (vol > max) {
            vol = max;
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
        Ring.showVolume(w.volumePercent());
        lastKey = "громкость " + vol;
        w.beep();
        if (onScreen != null) {
            onScreen.run();
        }
    }

    static void cancelTalk() {
        Web w = self;
        if (w == null) {
            return;
        }
        lastKey = "алиса отмена";
        OutputStream out = phraseOut;
        phraseOut = null;
        if (out != null) {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
        w.face = "слушаю";
        w.touch();
    }

    static void onAliceHardwareButton(String source) {
        long now = System.currentTimeMillis();
        if (now - lastAliceBtnMs < 700L) {
            return;
        }
        lastAliceBtnMs = now;
        lastKey = "кнопка алисы (" + source + ")";
        Ring.pulseAlice(1500L);
        Web w = self;
        if (w == null) {
            return;
        }
        if (w.alarmsMgr != null && w.alarmsMgr.ringing) {
            w.alarmsMgr.stopRing();
            w.face = "слушаю";
            w.touch();
            return;
        }
        if (Ring.isActiveMode(w.face)) {
            cancelTalk();
        } else {
            buttonTalk();
        }
    }

    /**
     * Страховка на случай, если физическую кнопку Алисы сверху колонки перехватил
     * системный сервис / quark-native мимо VoiceInteractionService.
     * Спит на блокирующем readLine() с узким фильтром (0% CPU в простое).
     */
    class AliceButtonWatcher implements Runnable {
        public void run() {
            while (run) {
                Process p = null;
                try {
                    p = new ProcessBuilder(
                            "/system/bin/logcat",
                            "-T", "1",
                            "-v", "raw",
                            "ActivityManager:W",
                            "quark-native:I",
                            "*:S"
                    ).redirectErrorStream(true).start();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), UTF8));
                    String line;
                    while (run && (line = br.readLine()) != null) {
                        if (line.contains("AliceVoiceInteractionService")
                                || line.contains("startConversation")
                                || line.contains("listen.VOICE_INPUT")
                                || line.contains("onAliceStateChanged: LISTENING")) {
                            onAliceHardwareButton("log");
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (p != null) {
                        try {
                            p.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    static void buttonToggle() {
        final Web w = self;
        if (w == null) {
            return;
        }
        lastKey = "алиса коротко";
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject now = new JSONObject(w.http(w.musicUrl() + "/v1/now", "GET", null, null, 4000));
                    String state = now.optString("state", "stop");
                    w.face = w.music("play".equals(state) ? "pause" : "play");
                } catch (Throwable t) {
                    String msg = t.getMessage();
                    w.face = msg == null ? "музыка не переключилась" : msg;
                }
                w.touch();
            }
        }).start();
    }

    static void buttonTalk() {
        Web w = self;
        if (w == null) {
            return;
        }
        lastKey = "алиса долго";
        w.face = "говорите";
        w.touch();
        if (!"0".equals(w.settings().optString("wake_sound", "1"))) {
            w.beep();
        }
        w.startPhrase();
    }

    synchronized void startPhrase() {
        startPhrase(false);
    }

    synchronized void startPhrase(boolean follow) {
        phraseGen++;
        listenSpeech(phraseGen, follow);
    }

    boolean hostPhrase() {
        return "1".equals(settings().optString("host_phrase", "0"));
    }

    boolean useJarvisStt() {
        return "1".equals(settings().optString("max_stt", "0"))
                && SpeechRecognizer.isRecognitionAvailable(this);
    }

    void listenSpeech(final int gen, final boolean follow) {
        if (!follow && hostPhrase()) {
            face = "говорите";
            levelNote = "фраза на хосте (vqe)";
            touch();
            return;
        }
        if (!follow && useJarvisStt() && SpeechRecognizer.isRecognitionAvailable(this)) {
            Handler main = new Handler(Looper.getMainLooper());
            main.post(new Runnable() {
                public void run() {
                    runSpeech(gen);
                }
            });
            return;
        }
        new Thread(new Phrase(gen, follow)).start();
    }

    void runSpeech(final int gen) {
        final SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        sr.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) {
                face = "говорите";
                touch();
            }

            public void onBeginningOfSpeech() {
            }

            public void onRmsChanged(float rmsdB) {
                level = Math.min(100, (int) (rmsdB * 3));
                levelNote = "STT RMS " + (int) rmsdB + " dB";
            }

            public void onBufferReceived(byte[] buffer) {
            }

            public void onEndOfSpeech() {
            }

            public void onError(int error) {
                if (gen != phraseGen) {
                    return;
                }
                face = "STT " + error;
                touch();
                sr.destroy();
            }

            public void onResults(Bundle results) {
                if (gen != phraseGen) {
                    sr.destroy();
                    return;
                }
                java.util.ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = list != null && list.size() > 0 ? list.get(0) : "";
                heard = text;
                face = text.length() == 0 ? "пусто" : "думаю";
                touch();
                sr.destroy();
                if (text.length() > 0) {
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                ask(text);
                            } catch (Throwable ignored) {
                            }
                        }
                    }).start();
                } else {
                    face = "слушаю";
                    touch();
                }
            }

            public void onPartialResults(Bundle partialResults) {
            }

            public void onEvent(int eventType, Bundle params) {
            }
        });
        sr.startListening(intent);
    }

    class Phrase implements Runnable {
        final int gen;
        final boolean follow;

        Phrase(int gen, boolean follow) {
            this.gen = gen;
            this.follow = follow;
        }

        public void run() {
            Socket sock = null;
            OutputStream out = null;
            try {
                sock = new Socket();
                sock.connect(new java.net.InetSocketAddress(HOST, BASE + 5), 4000);
                out = sock.getOutputStream();
                out.write(("YSE1 lag=0 chime=0 follow=" + (follow ? "1" : "0") + "\n").getBytes(UTF8));
                out.flush();
                phraseOut = out;
                InputStream in = sock.getInputStream();
                byte[] stop = new byte[64];
                long until = System.currentTimeMillis() + 28000;
                while (gen == phraseGen && System.currentTimeMillis() < until) {
                    int ready = in.available();
                    if (ready > 0) {
                        int n = in.read(stop, 0, Math.min(ready, stop.length));
                        if (n > 0 && new String(stop, 0, n, UTF8).contains("stop")) {
                            break;
                        }
                    }
                    Thread.sleep(40);
                }
            } catch (Throwable t) {
                if (gen == phraseGen) {
                    face = "фраза не ушла";
                    touch();
                }
            } finally {
                if (phraseOut == out) {
                    phraseOut = null;
                }
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (gen == phraseGen) {
                    if ("говорите".equals(face) || "фраза не ушла".equals(face)) {
                        face = "слушаю";
                        touch();
                    }
                }
            }
        }
    }

    class Chime implements Runnable {
        public void run() {
            while (run) {
                try {
                    Thread.sleep(1000);
                    if (alarmsMgr != null) {
                        alarmsMgr.tick();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    void streamPcm(InputStream in, int rate, int channels, boolean voice) {
        if (channels < 1) {
            channels = 1;
        }
        if (rate < 8000) {
            rate = 48000;
        }
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min < 1) {
            min = rate * channels;
        }
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min * 2, rate * channels), AudioTrack.MODE_STREAM);
        track.play();
        synchronized (playLock) {
            if (voice) {
                drop(voiceTrack);
                voiceTrack = track;
            } else {
                drop(musicTrack);
                musicTrack = track;
            }
        }
        if (voice) {
            face = "говорю";
            touch();
        }
        byte[] buf = new byte[4096];
        int written = 0;
        int frameBytes = channels * 2;
        try {
            while (run) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                written += writeAll(track, buf, n);
            }
            int pad = rate * frameBytes / 8;
            if (pad > 0 && written > 0) {
                written += writeAll(track, new byte[pad], pad);
            }
        } catch (Throwable ignored) {
        }
        synchronized (playLock) {
            if (voice && voiceTrack == track) {
                voiceTrack = null;
            }
            if (!voice && musicTrack == track) {
                musicTrack = null;
            }
        }
        int frames = written / frameBytes;
        if (voice) {
            speakMuteUntilMs = System.currentTimeMillis() + 5000L;
            LocalJarvis.reset();
            voiceWritten = frames;
        }
        int played = finish(track, frames);
        if (voice) {
            voicePlayed = played;
        }
        if (voice) {
            face = "слушаю";
            touch();
        }
    }

    int writeAll(AudioTrack track, byte[] buf, int n) {
        int off = 0;
        while (off < n) {
            int w = track.write(buf, off, n - off);
            if (w <= 0) {
                break;
            }
            off += w;
        }
        return off;
    }

    int finish(AudioTrack track, int frames) {
        int played = 0;
        if (track == null) {
            return 0;
        }
        try {
            long deadline = System.currentTimeMillis() + 8000;
            while (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                    && track.getPlaybackHeadPosition() < frames
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            played = track.getPlaybackHeadPosition();
        } catch (Throwable ignored) {
        }
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        try {
            track.release();
        } catch (Throwable ignored) {
        }
        return played;
    }

    void drop(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
            track.release();
        } catch (Throwable ignored) {
        }
    }

    void alarmTone() {
        try {
            final ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1600);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(1800);
                    } catch (InterruptedException ignored) {
                    }
                    tone.release();
                }
            }).start();
        } catch (Throwable ignored) {
        }
    }

    static String queryValue(String query, String key) {
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            String name = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
            if (!key.equals(name)) {
                continue;
            }
            String value = eq < 0 ? "" : pairs[i].substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(value, "UTF-8");
            } catch (Throwable t) {
                return "";
            }
        }
        return "";
    }

    static boolean personId(String id) {
        if (id == null || id.length() != 8) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    static boolean musicKind(String kind) {
        if (kind == null || kind.length() == 0 || kind.length() > 12) {
            return false;
        }
        if ("wave".equals(kind) || "likes".equals(kind)) {
            return true;
        }
        for (int i = 0; i < kind.length(); i++) {
            if (kind.charAt(i) < '0' || kind.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    String musicGet(String path) throws Exception {
        return http(musicUrl() + path, "GET", null, null, 20000);
    }

    String musicPost(String path, String body) throws Exception {
        return http(musicUrl() + path, "POST", "text/plain; charset=utf-8", body.getBytes(UTF8), 20000);
    }

    String talkGet(String path) throws Exception {
        return http(talkUrl() + path, "GET", null, null, 8000);
    }

    String talkPost(String path, String body) throws Exception {
        return http(talkUrl() + path, "POST", "application/json; charset=utf-8", body.getBytes(UTF8), 8000);
    }

    String musicPoll(String id) throws Exception {
        String raw;
        try {
            raw = musicGet("/v1/login/poll?id=" + id);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int sp = msg.indexOf(' ');
            raw = sp > 0 ? msg.substring(sp + 1) : "{\"ready\":\"no\"}";
        }
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Throwable t) {
            o = new JSONObject();
            o.put("ready", "no");
        }
        o.remove("token");
        o.remove("uid");
        if ("yes".equals(o.optString("ready")) || Boolean.TRUE.equals(o.opt("ready"))) {
            rememberPerson(id, o.optString("login", ""));
        }
        return o.toString();
    }

    void rememberPerson(String id, String login) throws Exception {
        JSONObject o = settings();
        String name = id.equals(o.optString("login_id")) ? o.optString("login_name", "") : "";
        if (name.length() == 0) {
            name = login.length() == 0 ? "Яндекс" : login;
        }
        JSONArray people = o.optJSONArray("people");
        JSONArray next = new JSONArray();
        boolean found = false;
        if (people != null) {
            for (int i = 0; i < people.length(); i++) {
                JSONObject person = people.getJSONObject(i);
                if (id.equals(person.optString("id"))) {
                    person.put("name", name);
                    person.put("login", login);
                    found = true;
                }
                next.put(person);
            }
        }
        if (!found) {
            JSONObject person = new JSONObject();
            person.put("id", id);
            person.put("name", name);
            person.put("login", login);
            next.put(person);
        }
        o.put("people", next);
        o.put("music_who", id);
        saveSettings(o);
    }

    String peopleJson() {
        String who = "";
        JSONArray local = new JSONArray();
        try {
            JSONObject stored = settings();
            who = stored.optString("music_who", "");
            JSONArray got = stored.optJSONArray("people");
            if (got != null) {
                local = got;
            }
        } catch (Throwable ignored) {
        }
        JSONArray remote = new JSONArray();
        try {
            JSONObject got = new JSONObject(musicGet("/v1/people"));
            if (got.optString("who").length() > 0) {
                who = got.optString("who");
            }
            if (got.optJSONArray("people") != null) {
                remote = got.optJSONArray("people");
            }
        } catch (Throwable ignored) {
        }
        JSONArray people = new JSONArray();
        String seen = ",";
        JSONArray[] both = {remote, local};
        for (int n = 0; n < both.length; n++) {
            JSONArray src = both[n];
            for (int i = 0; i < src.length(); i++) {
                JSONObject person = src.optJSONObject(i);
                if (person == null || !personId(person.optString("id"))) {
                    continue;
                }
                String id = person.optString("id");
                if (seen.contains("," + id + ",")) {
                    continue;
                }
                seen = seen + id + ",";
                JSONObject row = new JSONObject();
                try {
                    row.put("id", id);
                    row.put("name", person.optString("name", "Яндекс"));
                    row.put("login", person.optString("login", ""));
                    people.put(row);
                } catch (Throwable ignored) {
                }
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("who", who);
            out.put("people", people);
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    void serve(Socket client) {
        try {
            InputStream in = client.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                head.write(b);
                byte[] raw = head.toByteArray();
                int n = raw.length;
                if (n >= 4 && raw[n - 4] == '\r' && raw[n - 3] == '\n' && raw[n - 2] == '\r' && raw[n - 1] == '\n') {
                    break;
                }
                if (n > 16384) {
                    break;
                }
            }
            String header = new String(head.toByteArray(), UTF8);
            int cut = header.indexOf("\r\n");
            String first = cut < 0 ? header : header.substring(0, cut);
            String[] parts = first.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String url = parts.length > 1 ? parts[1] : "/";
            String query = "";
            int q = url.indexOf('?');
            if (q >= 0) {
                query = url.substring(q + 1);
                url = url.substring(0, q);
            }
            int length = 0;
            int rate = 48000;
            int channels = 2;
            boolean voice = true;
            String[] lines = header.split("\r\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String low = line.toLowerCase(Locale.US);
                if (low.startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                } else if (low.startsWith("x-rate:")) {
                    rate = Integer.parseInt(line.substring(7).trim());
                } else if (low.startsWith("x-channels:")) {
                    channels = Integer.parseInt(line.substring(11).trim());
                } else if (low.startsWith("x-role:")) {
                    voice = line.substring(7).trim().equals("voice");
                }
            }
            if ("/api/pcm".equals(url) && "POST".equals(method)) {
                streamPcm(in, rate, channels, voice);
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
                return;
            }
            int maxLen = ("/api/alarm-tone/file".equals(url) || "/api/alarm-tone/pcm".equals(url))
                    ? (12 * 1024 * 1024) : 32768;
            if (length > maxLen) {
                length = maxLen;
            }
            byte[] body = new byte[length];
            int got = 0;
            while (got < length) {
                int n = in.read(body, got, length - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            String text = ("/api/alarm-tone/file".equals(url) || "/api/alarm-tone/pcm".equals(url))
                    ? "" : new String(body, 0, got, UTF8);
            if ("/".equals(url)) {
                send(client, 200, "text/html; charset=utf-8", page);
            } else if ("/api/clock".equals(url)) {
                send(client, 200, "application/json", clock());
            } else if ("/api/settings".equals(url) && "GET".equals(method)) {
                JSONObject o = settings();
                o.put("wifi_now", currentNetName());
                double lux = readLux();
                double chipC = readChipC();
                Boolean hp = readHeadphones();
                if (lux >= 0) o.put("lux", Math.round(lux * 100.0) / 100.0);
                if (chipC > -90) o.put("chip_c", Math.round(chipC * 10.0) / 10.0);
                if (hp != null) o.put("headphones", hp.booleanValue());
                o.put("ha_url_set", true);
                o.put("ha_token_set", true);
                send(client, 200, "application/json", o.toString().getBytes(UTF8));
            } else if ("/api/settings".equals(url) && "POST".equals(method)) {
                JSONObject incoming = new JSONObject(text);
                JSONObject o = settings();
                copy(incoming, o, "nn");
                copy(incoming, o, "news");
                copy(incoming, o, "stt");
                copy(incoming, o, "voice");
                copy(incoming, o, "wake");
                copy(incoming, o, "persona");
                copy(incoming, o, "bt");
                copy(incoming, o, "ble_gw");
                copy(incoming, o, "mqtt_host");
                copy(incoming, o, "mqtt_port");
                copy(incoming, o, "mqtt_user");
                if (incoming.optString("mqtt_pass", "").length() > 0) {
                    copy(incoming, o, "mqtt_pass");
                }
                copy(incoming, o, "llm_url");
                copy(incoming, o, "llm_model");
                if (incoming.optString("llm_key", "").length() > 0) {
                    copy(incoming, o, "llm_key");
                }
                copy(incoming, o, "screen");
                copy(incoming, o, "wake_sound");
                copy(incoming, o, "ring");
                copy(incoming, o, "ring_color");
                copy(incoming, o, "ring_bright");
                copy(incoming, o, "ring_effect");
                copy(incoming, o, "lamp");
                copy(incoming, o, "lamp_color");
                copy(incoming, o, "lamp_bright");
                copy(incoming, o, "screen_bright");
                copy(incoming, o, "news_url");
                copy(incoming, o, "news_model");
                if (incoming.optString("news_key", "").length() > 0) {
                    copy(incoming, o, "news_key");
                }
                saveSettings(o);
                send(client, 200, "application/json", o.toString().getBytes(UTF8));
            } else if ("/api/volume".equals(url) && "GET".equals(method)) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                send(client, 200, "application/json", ("{\"volume\":" + vol + "}").getBytes(UTF8));
            } else if ("/api/volume".equals(url) && "POST".equals(method)) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int vol = Integer.parseInt(text.trim());
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (vol < 0) {
                    vol = 0;
                }
                if (vol > max) {
                    vol = max;
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                Ring.showVolume(volumePercent());
                send(client, 200, "application/json", ("{\"volume\":" + vol + "}").getBytes(UTF8));
            } else if ("/api/speak".equals(url) && "POST".equals(method)) {
                JSONObject o = new JSONObject(text);
                String line = o.optString("text", "");
                heard = line;
                speak(line);
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/ask".equals(url) && "POST".equals(method)) {
                heard = text;
                screenText = text.length() == 0 ? "Столовая" : text;
                touch();
                ask(text);
                send(client, 200, "application/json", ("{\"heard\":" + JSONObject.quote(text) + "}").getBytes(UTF8));
            } else if ("/api/heard".equals(url) && "POST".equals(method)) {
                heard = text;
                screenText = text.length() == 0 ? "Столовая" : text;
                face = "услышал";
                touch();
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/heard".equals(url)) {
                send(client, 200, "application/json", ("{\"text\":" + JSONObject.quote(heard) + "}").getBytes(UTF8));
            } else if ("/api/level".equals(url)) {
                send(client, 200, "application/json", ("{\"text\":" + JSONObject.quote(levelNote) + "}").getBytes(UTF8));
            } else if ("/api/ch16".equals(url)) {
                JSONObject j = new JSONObject();
                j.put("rate", micRate);
                j.put("count", micCount);
                j.put("session", micSession);
                j.put("pick", vqeCh);
                j.put("note", levelNote);
                JSONArray arr = new JSONArray();
                for (int i = 0; i < 16; i++) {
                    arr.put(chRms[i]);
                }
                j.put("rms", arr);
                send(client, 200, "application/json", j.toString().getBytes(UTF8));
            } else if ("/api/vqe_ch".equals(url) && "POST".equals(method)) {
                int n = Integer.parseInt(text.trim());
                if (n >= 0 && n < 16) {
                    vqeCh = n;
                    JSONObject o = settings();
                    o.put("vqe_ch", String.valueOf(n));
                    saveSettings(o);
                }
                send(client, 200, "application/json", ("{\"vqe_ch\":" + vqeCh + "}").getBytes(UTF8));
            } else if ("/api/dump16".equals(url)) {
                // Снять 1.0 сек (48000 фреймов * 16ch * 2 = 1536000 байт) живого 16-канального выхода AudioRecord
                int bytes = 48000 * 16 * 2;
                byte[] buf = new byte[bytes];
                dumpPos = 0;
                dumpBuf = buf;
                long end = System.currentTimeMillis() + 2500;
                while (dumpPos < bytes && System.currentTimeMillis() < end) {
                    Thread.sleep(30);
                }
                int dumpGot = dumpPos;
                dumpBuf = null;
                byte[] out = new byte[dumpGot];
                System.arraycopy(buf, 0, out, 0, dumpGot);
                send(client, 200, "application/octet-stream", out);
            } else if ("/api/wake".equals(url) && "POST".equals(method)) {
                face = "говорите";
                touch();
                if (!"0".equals(settings().optString("wake_sound", "1"))) {
                    beep();
                }
                startPhrase();
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/follow".equals(url) && "POST".equals(method)) {
                face = "говорите";
                touch();
                startPhrase(true);
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/ha/voice".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", haVoiceJson().getBytes(UTF8));
            } else if ("/api/ha/voice".equals(url) && "POST".equals(method)) {
                haVoiceSet(text);
                send(client, 200, "application/json", haVoiceJson().getBytes(UTF8));
            } else if ("/api/ha/device".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", haDeviceJson().getBytes(UTF8));
            } else if ("/api/ha/device".equals(url) && "POST".equals(method)) {
                haDeviceSet(text);
                send(client, 200, "application/json", haDeviceJson().getBytes(UTF8));
            } else if ("/api/ha/light".equals(url) && "POST".equals(method)) {
                haLightSet(text);
                send(client, 200, "application/json", haDeviceJson().getBytes(UTF8));
            } else if ("/api/message".equals(url) && "POST".equals(method)) {
                JSONObject req = new JSONObject(text);
                String msg = req.optString("text", "").trim();
                if (msg.length() > 0) {
                    speak(msg);
                }
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/remote/pair".equals(url) && "POST".equals(method)) {
                Intent pair = new Intent();
                pair.setClassName("com.yandex.tv.setupwizard", "com.yandex.tv.setupwizard.aliasBluetoothRemotePairing");
                pair.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(pair);
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/status".equals(url)) {
                send(client, 200, "application/json", ("{\"board\":" + JSONObject.quote(face)
                        + ",\"key\":" + JSONObject.quote(lastKey)
                        + ",\"matrix\":" + JSONObject.quote(LedFace.note)
                        + ",\"ring\":" + JSONObject.quote(Ring.note)
                        + ",\"screen\":" + JSONObject.quote(screenNote)
                        + ",\"voiceFrames\":" + voiceWritten
                        + ",\"voicePlayed\":" + voicePlayed
                        + ",\"ear\":" + JSONObject.quote(Ear.note)
                        + ",\"earCode\":" + Ear.openCode
                        + ",\"localJarvis\":" + JSONObject.quote(LocalJarvis.summary())
                        + ",\"localJarvisReady\":" + LocalJarvis.ready
                        + ",\"localWakeMode\":" + localWakeMode
                        + ",\"stockSpotter\":" + JSONObject.quote(Spotter.wakeNote)
                        + ",\"stockTrace\":" + JSONObject.quote(stockTrace())
                        + ",\"bt\":" + JSONObject.quote(btNote)
                        + ",\"bleScan\":" + JSONObject.quote(bleGw != null ? bleGw.scanNote : "")
                        + ",\"bleMqtt\":" + JSONObject.quote(bleGw != null ? bleGw.mqttNote : "")
                        + ",\"bleCount\":" + (bleGw != null ? bleGw.activeCount() : 0)
                        + ",\"lux\":" + readLux()
                        + ",\"chip_c\":" + readChipC()
                        + ",\"alarmRinging\":" + (alarmsMgr != null && alarmsMgr.ringing)
                        + "}").getBytes(UTF8));
            } else if ("/api/ble".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", (bleGw != null ? bleGw.toJson() : "{\"devices\":[]}").getBytes(UTF8));
            } else if ("/api/people".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", peopleJson().getBytes(UTF8));
            } else if ("/api/open".equals(url) && "POST".equals(method)) {
                String which = text.trim();
                String pkg = "vlc".equals(which) ? "org.videolan.vlc"
                        : "tvbro".equals(which) ? "com.phlox.tvwebbrowser"
                        : "aurora".equals(which) ? "com.aurora.store"
                        : "rustore".equals(which) ? "ru.vk.store.tv"
                        : "kinopoisk".equals(which) ? "ru.kinopoisk.tv"
                        : "smarttube".equals(which) ? "org.smarttube.stable"
                        : "launcher".equals(which) ? "com.spocky.projengmenu"
                        : "video".equals(which) ? "com.yandex.tv.videoplayer"
                        : "com.yandex.tv.webplayer";
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    send(client, 404, "application/json", "{\"error\":\"приложение ещё не стоит\"}".getBytes(UTF8));
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
                }
            } else if ("/api/playlists".equals(url) && "GET".equals(method)) {
                String id = queryValue(query, "id");
                if (!personId(id)) {
                    throw new IllegalArgumentException("id");
                }
                send(client, 200, "application/json", musicGet("/v1/playlists?id=" + id).getBytes(UTF8));
            } else if ("/api/music/now".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", musicGet("/v1/now").getBytes(UTF8));
            } else if ("/api/music/login".equals(url) && "POST".equals(method)) {
                String name = new JSONObject(text).optString("name", "").trim();
                if (name.length() == 0 || name.length() > 40) {
                    throw new IllegalArgumentException("имя");
                }
                String started = musicPost("/v1/login/start", name);
                JSONObject row = new JSONObject(started);
                JSONObject o = settings();
                o.put("login_id", row.optString("id", ""));
                o.put("login_name", name);
                saveSettings(o);
                send(client, 200, "application/json", started.getBytes(UTF8));
            } else if ("/api/music/poll".equals(url) && "GET".equals(method)) {
                String id = queryValue(query, "id");
                if (!personId(id)) {
                    throw new IllegalArgumentException("id");
                }
                send(client, 200, "application/json", musicPoll(id).getBytes(UTF8));
            } else if ("/api/music/who".equals(url) && "POST".equals(method)) {
                String id = new JSONObject(text).optString("id", "");
                if (!personId(id)) {
                    throw new IllegalArgumentException("id");
                }
                musicPost("/v1/who-id", id);
                JSONObject o = settings();
                o.put("music_who", id);
                saveSettings(o);
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/music/play".equals(url) && "POST".equals(method)) {
                JSONObject req = new JSONObject(text);
                String id = req.optString("id", "");
                String kind = req.optString("kind", "");
                if (!personId(id) || !musicKind(kind)) {
                    throw new IllegalArgumentException("play");
                }
                send(client, 200, "application/json", musicPost("/v1/play", id + " " + kind).getBytes(UTF8));
            } else if ("/api/music/mark".equals(url) && "POST".equals(method)) {
                String cmd = new JSONObject(text).optString("cmd", "");
                if (!"like".equals(cmd) && !"dislike".equals(cmd)) {
                    throw new IllegalArgumentException("mark");
                }
                send(client, 200, "application/json", musicPost("/v1/mark", cmd).getBytes(UTF8));
            } else if ("/api/voices".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", talkGet("/v1/voices").getBytes(UTF8));
            } else if ("/api/voices".equals(url) && "POST".equals(method)) {
                send(client, 200, "application/json", talkPost("/v1/voices", text).getBytes(UTF8));
            } else if ("/api/alarms".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", alarmsMgr.toJson().getBytes(UTF8));
            } else if ("/api/alarms".equals(url) && "POST".equals(method)) {
                send(client, 200, "application/json", alarmsMgr.handlePost(text).getBytes(UTF8));
            } else if ("/api/alarm-tone".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", alarmsMgr.toneJson().getBytes(UTF8));
            } else if ("/api/alarm-tone/volume".equals(url) && "POST".equals(method)) {
                JSONObject req = new JSONObject(text);
                send(client, 200, "application/json", alarmsMgr.setVolume(req.optInt("volume", 50)).getBytes(UTF8));
            } else if ("/api/alarm-tone/builtin".equals(url) && "POST".equals(method)) {
                send(client, 200, "application/json", alarmsMgr.setBuiltin().getBytes(UTF8));
            } else if ("/api/alarm-tone/file".equals(url) && "POST".equals(method)) {
                String fname = queryValue(query, "name");
                if (fname == null || fname.length() == 0) {
                    fname = "мелодия.mp3";
                }
                send(client, 200, "application/json", alarmsMgr.saveCustomFile(body, got, fname).getBytes(UTF8));
            } else if ("/api/alarm-tone/pcm".equals(url) && "POST".equals(method)) {
                String lbl = queryValue(query, "label");
                if (lbl == null || lbl.length() == 0) {
                    lbl = "Трек Яндекс Музыки";
                }
                send(client, 200, "application/json", alarmsMgr.saveCustomPcm(body, got, lbl).getBytes(UTF8));
            } else if ("/api/alarm-tone/search".equals(url) && "GET".equals(method)) {
                String qv = queryValue(query, "q");
                send(client, 200, "application/json", musicPost("/v1/alarm-search", qv == null ? "" : qv).getBytes(UTF8));
            } else if ("/api/alarm-tone/track".equals(url) && "POST".equals(method)) {
                String tid = text.trim();
                if (tid.startsWith("{")) {
                    tid = new JSONObject(tid).optString("id", "").trim();
                }
                send(client, 200, "application/json", musicPost("/v1/alarm-track", tid).getBytes(UTF8));
            } else if ("/api/wifi".equals(url) && "GET".equals(method)) {
                send(client, 200, "application/json", wifiJson().getBytes(UTF8));
            } else if ("/api/wifi".equals(url) && "POST".equals(method)) {
                wifiConnect(text);
                send(client, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
            } else if ("/api/music/ctrl".equals(url)) {
                String cmd = text.trim();
                if (cmd.startsWith("{")) {
                    cmd = new JSONObject(cmd).optString("cmd", "");
                }
                String status = music(cmd);
                send(client, 200, "application/json", ("{\"status\":" + JSONObject.quote(status) + ",\"ok\":true}").getBytes(UTF8));
            } else {
                send(client, 404, "application/json", "{\"error\":\"not-found\"}".getBytes(UTF8));
            }
        } catch (Throwable t) {
            try {
                send(client, 400, "application/json", ("{\"error\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}").getBytes(UTF8));
            } catch (Throwable ignored) {
            }
        } finally {
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
    }

    String wifiJson() throws Exception {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            wm.startScan();
        } catch (Throwable ignored) {
        }
        String now = "";
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getSSID() != null) {
                now = info.getSSID().replace("\"", "");
                if ("<unknown ssid>".equals(now)) {
                    now = "";
                }
            }
        } catch (Throwable ignored) {
        }
        JSONArray nets = new JSONArray();
        String seen = ",";
        List<ScanResult> rows = wm.getScanResults();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                ScanResult row = rows.get(i);
                if (row == null || row.SSID == null || row.SSID.length() == 0 || seen.contains("," + row.SSID + ",")) {
                    continue;
                }
                seen = seen + row.SSID + ",";
                JSONObject item = new JSONObject();
                item.put("ssid", row.SSID);
                item.put("dbm", row.level);
                item.put("band", row.frequency >= 5000 ? 5 : 2);
                nets.put(item);
            }
        }
        JSONObject out = new JSONObject();
        out.put("now", now);
        out.put("nets", nets);
        return out.toString();
    }

    void wifiConnect(String text) throws Exception {
        JSONObject req = new JSONObject(text);
        String ssid = req.optString("ssid", "").trim();
        String password = req.optString("password", "");
        if (ssid.length() == 0 || ssid.length() > 32 || ssid.indexOf('"') >= 0 || ssid.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("сеть");
        }
        if (password.length() > 63 || password.indexOf('"') >= 0) {
            throw new IllegalArgumentException("пароль");
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = "\"" + ssid + "\"";
        if (password.length() == 0) {
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            cfg.preSharedKey = "\"" + password + "\"";
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        int id = wm.addNetwork(cfg);
        if (id < 0) {
            throw new IllegalStateException("сеть не принята");
        }
        // Кабель уже держит сеть, и Android гасит Wi-Fi, пока его никто не просит.
        holdWifi();
        Thread.sleep(700);
        if (!wm.enableNetwork(id, true)) {
            throw new IllegalStateException("не подключилась");
        }
        wm.reconnect();
        long until = System.currentTimeMillis() + 12000L;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(500);
            if (ssid.equals(wifiSsid(wm))) {
                return;
            }
        }
        throw new IllegalStateException("не подключилась, проверьте пароль");
    }

    void holdWifi() {
        if (wifiHold != null) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiHold = new ConnectivityManager.NetworkCallback() {};
        cm.requestNetwork(request, wifiHold);
    }

    static String wifiSsid(WifiManager wm) {
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getNetworkId() < 0 || info.getSSID() == null) {
                return "";
            }
            String ssid = info.getSSID().replace("\"", "").trim();
            return "<unknown ssid>".equals(ssid) ? "" : ssid;
        } catch (Throwable ignored) {
            return "";
        }
    }

    synchronized void changeAlarm(String text) throws Exception {
        JSONObject req = new JSONObject(text);
        if (req.optBoolean("clear")) {
            alarms = new JSONArray();
        } else if (req.has("drop")) {
            int drop = req.getInt("drop");
            JSONArray left = new JSONArray();
            for (int i = 0; i < alarms.length(); i++) {
                if (i != drop) {
                    left.put(alarms.get(i));
                }
            }
            alarms = left;
        } else if (req.has("at")) {
            long at = req.getLong("at");
            long now = System.currentTimeMillis();
            if (at < now || at > now + 48L * 3600000L) {
                throw new IllegalArgumentException("время");
            }
            JSONObject a = new JSONObject();
            a.put("at", at);
            a.put("label", req.optString("label", "будильник"));
            alarms.put(a);
        } else if (req.has("mins")) {
            int mins = req.getInt("mins");
            if (mins < 1 || mins > 1440) {
                throw new IllegalArgumentException("минуты");
            }
            JSONObject a = new JSONObject();
            a.put("at", System.currentTimeMillis() + mins * 60000L);
            a.put("label", "через " + mins + " мин");
            alarms.put(a);
        } else {
            String when = req.optString("time", "");
            String[] hm = when.split(":");
            if (hm.length != 2) {
                throw new IllegalArgumentException("время");
            }
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
            cal.set(java.util.Calendar.MINUTE, Integer.parseInt(hm[1]));
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DATE, 1);
            }
            JSONObject a = new JSONObject();
            a.put("at", cal.getTimeInMillis());
            a.put("label", when);
            alarms.put(a);
        }
        JSONObject o = settings();
        o.put("alarms", alarms);
        saveSettings(o);
    }

    synchronized String alarmsJson() throws Exception {
        JSONArray view = new JSONArray();
        for (int i = 0; i < alarms.length(); i++) {
            view.put(alarms.getJSONObject(i));
        }
        JSONObject o = new JSONObject();
        o.put("alarms", view);
        return o.toString();
    }

    static void copy(JSONObject from, JSONObject to, String key) throws Exception {
        if (from.has(key)) {
            to.put(key, from.get(key));
        }
    }

    int volumePercent() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.max(0, Math.min(100, (int) Math.round(cur * 100.0 / max)));
        } catch (Throwable ignored) {
            return 50;
        }
    }

    void setVolumePercent(int pct) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int p = Math.max(0, Math.min(100, pct));
            int target = (int) Math.round(p * max / 100.0);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            Ring.showVolume(p);
        } catch (Throwable ignored) {
        }
    }

    String haVoiceJson() throws Exception {
        JSONObject o = settings();
        JSONObject out = new JSONObject();
        out.put("nn", o.optString("nn", "ha"));
        out.put("news", o.optString("news", "yandex"));
        out.put("stt", o.optString("stt", "gigaam"));
        out.put("voice", o.optString("voice", "own:jarvis_new"));
        out.put("wake", o.optString("wake", "джарвис-локально"));
        out.put("persona", o.optString("persona", ""));
        out.put("llm_url", o.optString("llm_url", ""));
        out.put("llm_model", o.optString("llm_model", ""));
        out.put("wake_sound", !"0".equals(o.optString("wake_sound", "1")));
        out.put("bluetooth", "1".equals(o.optString("bt", "0")));
        out.put("local", localWakeMode ? "1" : "0");
        out.put("local_ok", LocalJarvis.ready);
        out.put("volume", volumePercent());
        out.put("eq", "0 0 0 0 0");
        return out.toString();
    }

    void haVoiceSet(String body) throws Exception {
        JSONObject req = new JSONObject(body);
        JSONObject o = settings();
        boolean push = false;
        if (req.has("volume")) {
            setVolumePercent(req.optInt("volume", 50));
        }
        String[] keys = {"nn", "news", "stt", "voice", "wake", "persona", "llm_url", "llm_model", "llm_key"};
        for (int i = 0; i < keys.length; i++) {
            if (req.has(keys[i])) {
                o.put(keys[i], req.optString(keys[i], ""));
                push = true;
            }
        }
        if (req.has("wake_sound")) {
            o.put("wake_sound", req.optBoolean("wake_sound", true) ? "1" : "0");
        }
        if (req.has("bluetooth")) {
            o.put("bt", req.optBoolean("bluetooth", false) ? "1" : "0");
        }
        saveSettings(o);
        if (push) {
            try {
                pushSettings();
            } catch (Throwable ignored) {
            }
        }
    }

    String haDeviceJson() throws Exception {
        JSONObject o = settings();
        JSONObject out = new JSONObject();
        int lc = Ring.color(o.optString("lamp_color", "#ffd8a8"));
        JSONObject lamp = new JSONObject();
        lamp.put("on", "1".equals(o.optString("lamp", "0")));
        lamp.put("brightness", Ring.number(o.optString("lamp_bright", "180"), 180));
        lamp.put("r", (lc >> 16) & 255);
        lamp.put("g", (lc >> 8) & 255);
        lamp.put("b", lc & 255);
        out.put("lamp", lamp);

        JSONObject ring = new JSONObject();
        ring.put("on", !"0".equals(o.optString("ring", "1")));
        ring.put("bright", Ring.number(o.optString("ring_bright", "180"), 180));
        ring.put("color", o.optString("ring_color", "#3aa0ff").replace("#", ""));
        ring.put("effect", o.optString("ring_effect", "fill"));
        out.put("ring", ring);

        JSONObject music = new JSONObject();
        try {
            music = new JSONObject(http(musicUrl() + "/v1/now", "GET", null, null, 2000));
        } catch (Throwable ignored) {
            music.put("state", "stop");
        }
        out.put("music", music);
        try {
            out.put("alarms", new JSONObject(alarmsMgr.toJson()).optJSONArray("alarms"));
        } catch (Throwable ignored) {
            out.put("alarms", new JSONArray());
        }
        out.put("wifi_now", currentNetName());
        out.put("ha_url_set", true);
        out.put("ha_token_set", true);
        double lux = readLux();
        double chipC = readChipC();
        Boolean hp = readHeadphones();
        out.put("lux", lux >= 0 ? Math.round(lux * 100.0) / 100.0 : JSONObject.NULL);
        out.put("chip_c", chipC > -90 ? Math.round(chipC * 10.0) / 10.0 : JSONObject.NULL);
        out.put("volts", JSONObject.NULL);
        out.put("amps", JSONObject.NULL);
        out.put("watts", JSONObject.NULL);
        out.put("headphones", hp == null ? JSONObject.NULL : hp.booleanValue());
        if (bleGw != null) {
            out.put("ble_count", bleGw.activeCount());
        }
        return out.toString();
    }

    static String readFirstLine(String path) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(path));
            String line = br.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    static double readLux() {
        String s = readFirstLine("/sys/bus/iio/devices/iio:device0/in_intensity_input");
        if (s.length() == 0) return -1;
        try { return Double.parseDouble(s); } catch (Throwable ignored) { return -1; }
    }

    static double readChipC() {
        String s = readFirstLine("/sys/class/thermal/thermal_zone0/temp");
        if (s.length() == 0) return -100;
        try {
            double v = Double.parseDouble(s);
            return v > 200 ? v / 1000.0 : v;
        } catch (Throwable ignored) {
            return -100;
        }
    }

    static Boolean readHeadphones() {
        String s = readFirstLine("/sys/devices/platform/auge_sound/extcon/headphone/state");
        if (s.length() == 0) s = readFirstLine("/sys/class/switch/headphone/state");
        if (s.length() == 0) return null;
        if (s.contains("=1") || "1".equals(s)) return Boolean.TRUE;
        if (s.contains("=0") || "0".equals(s)) return Boolean.FALSE;
        return null;
    }

    String currentNetName() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        ssid = ssid.replace("\"", "").trim();
                        if (ssid.length() > 0 && !"<unknown ssid>".equals(ssid)) {
                            return ssid;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String eth = readFirstLine("/sys/class/net/eth0/carrier");
        if ("1".equals(eth)) {
            return "Ethernet";
        }
        return "";
    }

    void haDeviceSet(String body) throws Exception {
        JSONObject req = new JSONObject(body);
        JSONObject o = settings();
        if (req.has("ring")) {
            o.put("ring", req.optBoolean("ring", true) ? "1" : "0");
        }
        if (req.has("color")) {
            String c = req.optString("color", "3aa0ff").replace("#", "");
            o.put("ring_color", "#" + c);
        }
        if (req.has("bright")) {
            o.put("ring_bright", String.valueOf(req.optInt("bright", 180)));
        }
        if (req.has("effect")) {
            o.put("ring_effect", req.optString("effect", "fill"));
        }
        saveSettings(o);
        Ring.apply(o);
    }

    void haLightSet(String body) throws Exception {
        JSONObject req = new JSONObject(body);
        JSONObject o = settings();
        if (req.has("on")) {
            o.put("lamp", req.optBoolean("on", false) ? "1" : "0");
        }
        if (req.has("brightness")) {
            o.put("lamp_bright", String.valueOf(req.optInt("brightness", 180)));
        }
        if (req.has("r") || req.has("g") || req.has("b")) {
            int r = Math.max(0, Math.min(255, req.optInt("r", 255)));
            int g = Math.max(0, Math.min(255, req.optInt("g", 216)));
            int b = Math.max(0, Math.min(255, req.optInt("b", 168)));
            o.put("lamp_color", String.format(Locale.US, "#%02x%02x%02x", r, g, b));
        }
        saveSettings(o);
        Ring.apply(o);
    }

    byte[] clock() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String[] months = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
        String text = cal.get(java.util.Calendar.DAY_OF_MONTH) + " " + months[cal.get(java.util.Calendar.MONTH)]
                + String.format(Locale.US, ", %02d:%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND));
        return ("{\"text\":" + JSONObject.quote(text) + "}").getBytes(UTF8);
    }

    void send(Socket client, int code, String type, byte[] body) throws Exception {
        String reason = code == 200 ? "OK" : "Error";
        String head = "HTTP/1.0 " + code + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(Charset.forName("US-ASCII")));
        out.write(body);
        out.flush();
    }
}
