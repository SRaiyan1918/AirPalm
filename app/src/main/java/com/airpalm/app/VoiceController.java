package com.airpalm.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice session: turned on/off with the open-palm gesture. While ON it listens for commands
 * (open apps, back, home, scroll...) and switches itself OFF after a quiet period.
 * Everything runs on the main thread.
 */
public class VoiceController {
    private static final long SESSION_TIMEOUT_MS = 40000;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean active = false;
    private long lastActivity = 0;
    private int errorStreak = 0;
    private boolean warnedNetwork = false;
    private final ArrayList<String> labels = new ArrayList<>();
    private final ArrayList<String> packages = new ArrayList<>();
    private Runnable stateListener;

    public VoiceController(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    public boolean isActive() {
        return active;
    }

    /** Called (on the main thread) whenever listening starts or stops. */
    public void setStateListener(Runnable r) {
        stateListener = r;
    }

    private void notifyState() {
        if (stateListener != null) stateListener.run();
    }

    /** Safe to call from any thread. */
    public void toggle() {
        main.post(() -> {
            if (active) stop("Voice OFF"); else start();
        });
    }

    public void destroy() {
        main.post(() -> {
            active = false;
            notifyState();
            main.removeCallbacksAndMessages(null);
            destroyRecognizer();
        });
    }

    // ------------------------------------------------------------------ session

    private void start() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission needed: open the AirPalm app and allow it");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            toast("Speech recognition is not available on this phone");
            return;
        }
        loadApps();
        active = true;
        notifyState();
        errorStreak = 0;
        warnedNetwork = false;
        lastActivity = SystemClock.uptimeMillis();
        buzz();
        toast("Listening...  (say: YouTube kholo / tap Subscribe / likho ... / help)");
        createRecognizer();
        listen();
        main.removeCallbacks(timeoutCheck);
        main.postDelayed(timeoutCheck, 3000);
    }

    private void stop(String message) {
        active = false;
        notifyState();
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
        buzz();
        if (message != null) toast(message);
    }

    private final Runnable timeoutCheck = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            if (SystemClock.uptimeMillis() - lastActivity > SESSION_TIMEOUT_MS) {
                stop("Voice OFF (koi command nahi mila)");
            } else {
                main.postDelayed(this, 3000);
            }
        }
    };

    private void createRecognizer() {
        destroyRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(listener);
        } catch (Exception e) {
            recognizer = null;
            stop("Could not start speech recognition");
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private void listen() {
        if (!active || recognizer == null) return;
        String lang = prefs.getBoolean("hindi", false) ? "hi-IN" : "en-IN";
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            main.postDelayed(this::recreateAndListen, 800);
        }
    }

    private void recreateAndListen() {
        if (!active) return;
        createRecognizer();
        listen();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle partialResults) { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onResults(Bundle results) {
            if (!active) return;
            errorStreak = 0;
            ArrayList<String> heard = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            handle(heard);
            if (active) main.postDelayed(VoiceController.this::listen, 250);
        }

        @Override
        public void onError(int error) {
            if (!active) return;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    main.postDelayed(VoiceController.this::listen, 200); // just silence
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stop("Microphone permission missing");
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    stop("Language not supported: try the Hindi/English option in AirPalm");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    if (!warnedNetwork) {
                        warnedNetwork = true;
                        toast("Voice needs internet (or an offline language pack)");
                    }
                    if (++errorStreak >= 5) stop("Voice OFF (network problem)");
                    else main.postDelayed(VoiceController.this::recreateAndListen, 1500);
                    break;
                default: // busy, client error, ...
                    if (++errorStreak >= 6) stop("Voice OFF (recognizer problem)");
                    else main.postDelayed(VoiceController.this::recreateAndListen, 700);
                    break;
            }
        }
    };

    // ------------------------------------------------------------------ commands

    private void handle(ArrayList<String> heard) {
        if (heard == null || heard.isEmpty()) return;
        String notFound = null;
        for (String h : heard) {
            VoiceCommandParser.Command c = VoiceCommandParser.parse(h);
            if (c.type == VoiceCommandParser.UNKNOWN) continue;
            if (c.type == VoiceCommandParser.OPEN_APP) {
                int idx = VoiceCommandParser.matchApp(c.arg, labels);
                if (idx >= 0) {
                    lastActivity = SystemClock.uptimeMillis();
                    openApp(idx);
                    return;
                }
                if (!c.bare && notFound == null) notFound = c.arg;
                continue;
            }
            lastActivity = SystemClock.uptimeMillis();
            run(c);
            return;
        }
        lastActivity = SystemClock.uptimeMillis();
        if (notFound != null) toast("App not found: " + title(notFound));
        else toast("Samajh nahi aaya: " + heard.get(0));
    }

    private void run(VoiceCommandParser.Command c) {
        switch (c.type) {
            case VoiceCommandParser.BACK:
                AirPalmAccessibilityService.back();
                break;
            case VoiceCommandParser.HOME:
                AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_HOME);
                break;
            case VoiceCommandParser.RECENTS:
                AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_RECENTS);
                break;
            case VoiceCommandParser.NOTIFICATIONS:
                AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case VoiceCommandParser.QUICK_SETTINGS:
                AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case VoiceCommandParser.SCREENSHOT:
                if (Build.VERSION.SDK_INT >= 28) {
                    AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                }
                break;
            case VoiceCommandParser.LOCK:
                if (Build.VERSION.SDK_INT >= 28) {
                    stop(null);
                    AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                }
                break;
            case VoiceCommandParser.SCROLL_DOWN: // page goes down = finger swipes up
                AirPalmAccessibilityService.swipeDir(GestureEngine.DIR_UP);
                break;
            case VoiceCommandParser.SCROLL_UP:
                AirPalmAccessibilityService.swipeDir(GestureEngine.DIR_DOWN);
                break;
            case VoiceCommandParser.SWIPE_LEFT:
                AirPalmAccessibilityService.swipeDir(GestureEngine.DIR_LEFT);
                break;
            case VoiceCommandParser.SWIPE_RIGHT:
                AirPalmAccessibilityService.swipeDir(GestureEngine.DIR_RIGHT);
                break;
            case VoiceCommandParser.VOLUME_UP:
                volume(AudioManager.ADJUST_RAISE);
                break;
            case VoiceCommandParser.VOLUME_DOWN:
                volume(AudioManager.ADJUST_LOWER);
                break;
            case VoiceCommandParser.SEARCH:
                search(c.arg);
                break;
            case VoiceCommandParser.VOICE_OFF:
                stop("Voice OFF");
                break;
            case VoiceCommandParser.TAP_TEXT: {
                String hit = AirPalmAccessibilityService.tapText(c.arg);
                if (hit == null) toast("Not found on screen: " + c.arg);
                break;
            }
            case VoiceCommandParser.TYPE_TEXT:
                if (!AirPalmAccessibilityService.typeText(c.arg)) {
                    toast("No text box found - tap a text field first");
                }
                break;
            case VoiceCommandParser.SEND:
                if (AirPalmAccessibilityService.tapText("send") == null && !AirPalmAccessibilityService.imeEnter()) {
                    toast("No Send button found");
                }
                break;
            case VoiceCommandParser.ENTER:
                if (!AirPalmAccessibilityService.imeEnter()) toast("Enter is not available here");
                break;
            case VoiceCommandParser.CLEAR_TEXT:
                if (!AirPalmAccessibilityService.clearText()) toast("No text box found");
                break;
            case VoiceCommandParser.MEDIA_PAUSE:
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case VoiceCommandParser.MEDIA_PLAY:
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case VoiceCommandParser.HELP:
                toastLong("Say: <app> kholo | back, home, recents | swipe up/down/left/right | "
                        + "tap <text on screen> | likho <text>, send, enter, clear | pause, play | "
                        + "volume up/down | search <text> | stop listening");
                break;
            default:
                break;
        }
    }

    /** Pause / play for whatever is playing (YouTube, Spotify...). */
    private void mediaKey(int keyCode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void volume(int direction) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void search(String query) {
        toast("Search: " + query);
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(SearchManager.QUERY, query);
        if (!AirPalmAccessibilityService.launch(i)) {
            Intent v = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            AirPalmAccessibilityService.launch(v);
        }
    }

    private void openApp(int idx) {
        String label = labels.get(idx);
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(packages.get(idx));
        if (launch == null) {
            toast("Cannot open: " + label);
            return;
        }
        toast("Opening " + label);
        if (!AirPalmAccessibilityService.launch(launch)) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            } catch (Exception e) {
                toast("Cannot open: " + label);
            }
        }
    }

    private void loadApps() {
        labels.clear();
        packages.clear();
        PackageManager pm = ctx.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(mainIntent, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || packages.contains(pkg)) continue;
                CharSequence l = ri.loadLabel(pm);
                if (l == null) continue;
                labels.add(l.toString());
                packages.add(pkg);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ small helpers

    private void toast(String text) {
        main.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
    }

    private void toastLong(String text) {
        main.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_LONG).show());
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
        }
    }

    private static String title(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return sb.toString();
    }
}
