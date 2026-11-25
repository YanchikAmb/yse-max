package com.yse.station;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

public class Panel extends Activity {
    TextView timeView;
    TextView infoView;
    Handler handler;
    Runnable tick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(48, 48, 48, 48);
        timeView = new TextView(this);
        timeView.setTextSize(120);
        timeView.setTextColor(Color.WHITE);
        timeView.setGravity(Gravity.CENTER);
        infoView = new TextView(this);
        infoView.setTextSize(32);
        infoView.setTextColor(Color.WHITE);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(0, 24, 0, 0);
        box.addView(timeView);
        box.addView(infoView);
        setContentView(box);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        box.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        startForegroundService(new Intent(this, Web.class));
        Web.onScreen = new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        paint();
                    }
                });
            }
        };
        handler = new Handler();
        tick = new Runnable() {
            public void run() {
                paint();
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tick);
        assist(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Web.screenUp = true;
        paint();
    }

    @Override
    protected void onPause() {
        Web.screenUp = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (handler != null && tick != null) {
            handler.removeCallbacks(tick);
        }
        if (Web.onScreen != null) {
            Web.onScreen = null;
        }
        super.onDestroy();
    }

    long lastVol;
    long talkDown;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        assist(intent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_PAGE_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                volume(5);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                volume(-5);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                talkDown = event.getDownTime();
                Ring.pulseAlice(1500L);
            } else if (action == KeyEvent.ACTION_UP && talkDown != 0) {
                long held = event.getEventTime() - talkDown;
                talkDown = 0;
                if (held >= 650) {
                    Web.buttonToggle();
                } else if (Ring.isActiveMode(Web.face)) {
                    Web.cancelTalk();
                } else {
                    Web.buttonTalk();
                }
                paint();
            }
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            Web.lastKey = code + " " + KeyEvent.keyCodeToString(code);
            paint();
        }
        return super.dispatchKeyEvent(event);
    }

    void volume(int delta) {
        long now = System.currentTimeMillis();
        if (now - lastVol < 140) {
            return;
        }
        lastVol = now;
        Web.adjustVolume(delta);
        paint();
    }

    void assist(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_ASSIST.equals(action) || Intent.ACTION_VOICE_COMMAND.equals(action)) {
            Web.buttonToggle();
            paint();
        }
    }

    void paint() {
        Calendar cal = Calendar.getInstance();
        boolean busy = !"слушаю".equals(Web.face) || "music".equals(LedFace.scene);
        timeView.setTextSize(busy ? 72 : 120);
        timeView.setText(String.format(Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
        if (busy) {
            infoView.setTextSize(48);
            infoView.setText("music".equals(LedFace.scene) ? "музыка" : Web.face);
        } else {
            infoView.setTextSize(32);
            infoView.setText(Web.screenText);
        }
        View root = timeView.getRootView();
        root.setBackgroundColor(Web.screenColor);
    }
}
