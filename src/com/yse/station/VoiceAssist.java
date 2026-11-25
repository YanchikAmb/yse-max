package com.yse.station;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

/**
 * Системный обработчик кнопки Алисы (KEYCODE_VOICE_ASSIST) на Station Max.
 */
public class VoiceAssist extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        try {
            startForegroundService(new Intent(this, Web.class));
        } catch (Throwable ignored) {
        }
    }
}
