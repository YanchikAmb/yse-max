package com.yse.station;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Сессия голосовой кнопки: не рисует окно поверх ТВ, сразу подсвечивает кнопку/кольцо и запускает Джарвиса.
 */
public class VoiceSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    static class Session extends VoiceInteractionSession {
        Session(Context ctx) {
            super(ctx);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            try {
                if (Web.self == null) {
                    getContext().startForegroundService(new Intent(getContext(), Web.class));
                }
            } catch (Throwable ignored) {
            }
            Web.onAliceHardwareButton("voice-session");
            // Не перекрываем экран телевизора никаким окном
            hide();
            finish();
        }
    }
}
