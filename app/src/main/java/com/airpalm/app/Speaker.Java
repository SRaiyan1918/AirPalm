package com.airpalm.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Speaks short replies with Android's own TextToSpeech.
 * Uses a Hindi voice when the phone has one (Hindi script text), otherwise English-India with Roman text.
 * Music keeps playing: audio focus is requested as "may duck", so it just gets quieter while Jarvis talks.
 */
public class Speaker {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private TextToSpeech tts;
    private boolean ready = false;
    private boolean hindi = false;
    private Replies.Reply pending;
    private int speaking = 0;
    private AudioFocusRequest focus;
    private final ArrayList<Runnable> idleWaiters = new ArrayList<>();

    public Speaker(Context ctx) {
        this.ctx = ctx;
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(ctx, status -> main.post(() -> onInit(status)));
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            tts = null;
            flushIdle();
            return;
        }
        try {
            tts.setAudioAttributes(attrs);
            int hi = tts.isLanguageAvailable(new Locale("hi", "IN"));
            if (hi >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(new Locale("hi", "IN"));
                hindi = true;
            } else {
                tts.setLanguage(new Locale("en", "IN"));
                hindi = false;
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { main.post(Speaker.this::finished); }
                @Override public void onError(String utteranceId) { main.post(Speaker.this::finished); }
            });
        } catch (Exception ignored) {
        }
        ready = true;
        if (pending != null) {
            Replies.Reply p = pending;
            pending = null;
            say(p);
        }
    }

    /** Safe to call from any thread. */
    public void say(Replies.Reply reply) {
        main.post(() -> {
            if (reply == null) return;
            if (tts == null) return;
            if (!ready) {
                pending = reply;
                return;
            }
            String text = hindi ? reply.hi : reply.roman;
            requestFocus();
            speaking++;
            Bundle params = new Bundle();
            int r = tts.speak(text, TextToSpeech.QUEUE_ADD, params, "airpalm-" + System.nanoTime());
            if (r != TextToSpeech.SUCCESS) {
                finished();
            }
        });
    }

    /** Runs r when nothing is being spoken (right away if already quiet). Safe from any thread. */
    public void runWhenIdle(Runnable r) {
        main.post(() -> {
            if (speaking <= 0) {
                r.run();
            } else {
                idleWaiters.add(r);
                // never wait forever
                main.postDelayed(() -> {
                    if (idleWaiters.remove(r)) r.run();
                }, 8000);
            }
        });
    }

    private void finished() {
        if (speaking > 0) speaking--;
        if (speaking == 0) {
            abandonFocus();
            flushIdle();
        }
    }

    private void flushIdle() {
        ArrayList<Runnable> copy = new ArrayList<>(idleWaiters);
        idleWaiters.clear();
        for (Runnable r : copy) r.run();
    }

    private void requestFocus() {
        if (am == null || focus != null) return;
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(change -> { })
                    .build();
            am.requestAudioFocus(focus);
        } catch (Exception ignored) {
            focus = null;
        }
    }

    private void abandonFocus() {
        if (am != null && focus != null) {
            try {
                am.abandonAudioFocusRequest(focus);
            } catch (Exception ignored) {
            }
        }
        focus = null;
    }

    public void shutdown() {
        main.post(() -> {
            abandonFocus();
            if (tts != null) {
                try {
                    tts.stop();
                    tts.shutdown();
                } catch (Exception ignored) {
                }
                tts = null;
            }
            flushIdle();
        });
    }
}
