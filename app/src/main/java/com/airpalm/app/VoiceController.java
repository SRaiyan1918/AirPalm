package com.airpalm.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telecom.TelecomManager;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice session, started/stopped from the floating mic button (or the open-palm gesture).
 *
 * - A fresh speech recogniser is created for every listening round (a reused one often says "busy").
 * - If something is playing (reels, video), only ONE command is taken per tap, so the media is not
 *   paused for long. (Android pauses other audio while the recogniser listens.)
 * - When the screen turns off the session goes into standby: only "screen on", "answer call",
 *   "reject call" and "stop listening" are accepted.
 * Everything runs on the main thread.
 */
public class VoiceController {
    public static final int STATE_OFF = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_LISTENING = 2;

    public interface StateListener {
        void onState(int state);
    }

    private static final long SESSION_TIMEOUT_MS = 40000;
    private static final long STANDBY_TIMEOUT_MS = 15 * 60 * 1000L;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean active = false;
    private boolean oneShot = false;
    private boolean standby = false;
    private int state = STATE_OFF;
    private long lastActivity = 0;
    private int errorStreak = 0;
    private boolean warnedNetwork = false;
    private boolean receiverRegistered = false;
    private PowerManager.WakeLock standbyLock;
    private StateListener stateListener;
    private final ArrayList<String> labels = new ArrayList<>();
    private final ArrayList<String> packages = new ArrayList<>();

    // own microphone feed (Android 13+): lets the recogniser work without taking audio focus
    private AudioRecord ownRec;
    private ParcelFileDescriptor ownRead;
    private volatile boolean ownRun;
    private boolean ownAudioInUse = false;

    public VoiceController(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    public boolean isActive() {
        return active;
    }

    public void setStateListener(StateListener l) {
        stateListener = l;
    }

    private void setState(int s) {
        if (state == s) return;
        state = s;
        if (stateListener != null) stateListener.onState(s);
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
            setState(STATE_OFF);
            main.removeCallbacksAndMessages(null);
            releaseOwnAudio();
            destroyRecognizer();
            unregisterScreenReceiver();
            releaseStandbyLock();
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
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        oneShot = am != null && am.isMusicActive(); // something is playing: take just one command
        standby = false;
        active = true;
        errorStreak = 0;
        warnedNetwork = false;
        lastActivity = SystemClock.uptimeMillis();
        registerScreenReceiver();
        buzz();
        toast(oneShot ? "Listening for one command..." : "Listening...  (say: YouTube kholo / tap Subscribe / help)");
        setState(STATE_PREPARING);
        cycle(0);
        main.removeCallbacks(timeoutCheck);
        main.postDelayed(timeoutCheck, 3000);
    }

    private void stop(String message) {
        active = false;
        standby = false;
        main.removeCallbacksAndMessages(null);
        releaseOwnAudio();
        destroyRecognizer();
        unregisterScreenReceiver();
        releaseStandbyLock();
        setState(STATE_OFF);
        buzz();
        if (message != null) toast(message);
    }

    private final Runnable timeoutCheck = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            long limit = standby ? STANDBY_TIMEOUT_MS : SESSION_TIMEOUT_MS;
            if (SystemClock.uptimeMillis() - lastActivity > limit) {
                stop(standby ? null : "Voice OFF (koi command nahi mila)");
            } else {
                main.postDelayed(this, 3000);
            }
        }
    };

    /** Starts the next listening round with a brand-new recogniser. */
    private void cycle(long delayMs) {
        main.postDelayed(() -> {
            if (!active) return;
            releaseOwnAudio();
            createRecognizer();
            listen();
        }, delayMs);
    }

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
                recognizer.setRecognitionListener(null);
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
        ownAudioInUse = attachOwnAudio(i);
        setState(STATE_PREPARING);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            main.postDelayed(() -> cycle(0), 800);
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { if (active) setState(STATE_LISTENING); }
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
            releaseOwnAudio();
            setState(STATE_PREPARING);
            ArrayList<String> heard = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            handle(heard);
            if (!active) return;
            if (oneShot) {
                stop(null);
            } else {
                cycle(120);
            }
        }

        @Override
        public void onError(int error) {
            if (!active) return;
            releaseOwnAudio();
            setState(STATE_PREPARING);
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    if (oneShot) stop("Kuch suna nahi - mic dobara dabao");
                    else cycle(150); // just silence
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
                    else cycle(1500);
                    break;
                default: // audio, client, busy, ...
                    if (ownAudioInUse && (error == SpeechRecognizer.ERROR_AUDIO
                            || error == SpeechRecognizer.ERROR_CLIENT)) {
                        // the recogniser did not accept our own audio feed: use its own microphone from now on
                        prefs.edit().putBoolean("own_audio_failed", true).apply();
                    }
                    if (++errorStreak >= 6) stop("Voice OFF (recognizer problem)");
                    else cycle(500);
                    break;
            }
        }
    };

    // ------------------------------------------------------------------ own audio feed (Android 13+)

    private boolean attachOwnAudio(Intent i) {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (!prefs.getBoolean("own_audio", true) || prefs.getBoolean("own_audio_failed", false)) return false;
        try {
            final int rate = 16000;
            int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return false;
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                pipe[0].close();
                pipe[1].close();
                return false;
            }
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            ownRec = rec;
            ownRead = pipe[0];
            ownRun = true;
            final ParcelFileDescriptor writeEnd = pipe[1];
            new Thread(() -> {
                byte[] buf = new byte[3200];
                try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                    rec.startRecording();
                    while (ownRun) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n > 0) os.write(buf, 0, n);
                        else if (n < 0) break;
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                }
            }, "airpalm-audio").start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void releaseOwnAudio() {
        ownRun = false;
        ownRec = null;
        if (ownRead != null) {
            try {
                ownRead.close();
            } catch (Exception ignored) {
            }
            ownRead = null;
        }
        ownAudioInUse = false;
    }

    // ------------------------------------------------------------------ screen off = standby

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!active) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                enterStandby();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                leaveStandby();
            }
        }
    };

    private void registerScreenReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else ctx.registerReceiver(screenReceiver, f);
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterScreenReceiver() {
        if (!receiverRegistered) return;
        try {
            ctx.unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private void enterStandby() {
        standby = true;
        oneShot = false;
        lastActivity = SystemClock.uptimeMillis();
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && (standbyLock == null || !standbyLock.isHeld())) {
                standbyLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airpalm:standby");
                standbyLock.acquire(STANDBY_TIMEOUT_MS + 5000);
            }
        } catch (Exception ignored) {
        }
    }

    private void leaveStandby() {
        standby = false;
        lastActivity = SystemClock.uptimeMillis();
        releaseStandbyLock();
    }

    private void releaseStandbyLock() {
        try {
            if (standbyLock != null && standbyLock.isHeld()) standbyLock.release();
        } catch (Exception ignored) {
        }
        standbyLock = null;
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "airpalm:wake");
            w.acquire(5000);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ commands

    private void handle(ArrayList<String> heard) {
        if (heard == null || heard.isEmpty()) return;
        String notFound = null;
        for (String h : heard) {
            VoiceCommandParser.Command c = VoiceCommandParser.parse(h);
            if (c.type == VoiceCommandParser.UNKNOWN) continue;
            if (standby && !allowedInStandby(c.type)) continue; // screen is off: ignore everything else
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
            if (c.type == VoiceCommandParser.NUMBER && c.bare && !AirPalmAccessibilityService.numbersVisible()) {
                continue; // a lone number heard while no numbers are shown: ignore
            }
            lastActivity = SystemClock.uptimeMillis();
            run(c);
            return;
        }
        lastActivity = SystemClock.uptimeMillis();
        if (standby) return;
        if (notFound != null) toast("App not found: " + title(notFound));
        else toast("Samajh nahi aaya: " + heard.get(0));
    }

    private static boolean allowedInStandby(int type) {
        return type == VoiceCommandParser.SCREEN_ON
                || type == VoiceCommandParser.VOICE_OFF
                || type == VoiceCommandParser.ANSWER_CALL
                || type == VoiceCommandParser.REJECT_CALL;
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
            case VoiceCommandParser.SCREEN_OFF:
                if (Build.VERSION.SDK_INT >= 28) {
                    toast("Screen off - still listening (say: screen on)");
                    enterStandby();
                    AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                }
                break;
            case VoiceCommandParser.SCREEN_ON:
                wakeScreen();
                leaveStandby();
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
                stop(standby ? null : "Voice OFF");
                break;
            case VoiceCommandParser.TAP_TEXT: {
                String hit = AirPalmAccessibilityService.tapText(c.arg);
                if (hit == null) toast("Not found on screen: " + c.arg + "  (try: show numbers)");
                break;
            }
            case VoiceCommandParser.SHOW_NUMBERS: {
                int n = AirPalmAccessibilityService.showNumbers();
                if (n == 0) {
                    toast("Nothing to tap on this screen");
                } else {
                    oneShot = false; // stay on until a number is said
                    toast("Say a number (or 'tap 5'). 'hide numbers' to close");
                }
                break;
            }
            case VoiceCommandParser.HIDE_NUMBERS:
                AirPalmAccessibilityService.hideNumbers();
                break;
            case VoiceCommandParser.NUMBER: {
                int n = Integer.parseInt(c.arg);
                if (!AirPalmAccessibilityService.numbersVisible()) {
                    toast("Say 'show numbers' first");
                } else if (!AirPalmAccessibilityService.tapNumber(n)) {
                    toast("No item numbered " + n);
                }
                break;
            }
            case VoiceCommandParser.TYPE_TEXT:
                if (!AirPalmAccessibilityService.typeText(c.arg)) {
                    toast("No text box found - tap a text field first");
                }
                break;
            case VoiceCommandParser.REPLACE: {
                int r = AirPalmAccessibilityService.replaceText(c.arg, c.arg2);
                if (r == 1) toast("No text box found");
                else if (r == 2) toast("'" + c.arg + "' not found in the text");
                break;
            }
            case VoiceCommandParser.UNDO:
                if (!AirPalmAccessibilityService.undoText()) toast("Nothing to undo");
                break;
            case VoiceCommandParser.REDO:
                if (!AirPalmAccessibilityService.redoText()) toast("Nothing to redo");
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
                if (!AirPalmAccessibilityService.clearText()) toast("No text to clear");
                break;
            case VoiceCommandParser.MEDIA_PAUSE:
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case VoiceCommandParser.MEDIA_PLAY:
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case VoiceCommandParser.ANSWER_CALL:
                answerCall();
                break;
            case VoiceCommandParser.REJECT_CALL:
                rejectCall();
                break;
            case VoiceCommandParser.HELP:
                toastLong("Say: <app> kholo | back, home, recents | swipe up/down/left/right | "
                        + "tap <text> / show numbers | likho <text>, undo, redo, replace A with B, send, clear | "
                        + "pause, play | screen off / on | answer call | volume up/down | search <text> | stop listening");
                break;
            default:
                break;
        }
    }

    private void answerCall() {
        if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow call control in the AirPalm app (button 4)");
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.acceptRingingCall();
                stop(standby ? null : "Call answered - listening off"); // mic is needed for the call
            }
        } catch (Exception e) {
            toast("Could not answer the call");
        }
    }

    private void rejectCall() {
        if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow call control in the AirPalm app (button 4)");
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= 28) {
                tm.endCall();
                toast("Call ended");
            }
        } catch (Exception e) {
            toast("Could not end the call");
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
        if (standby) return; // screen is off
        main.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
    }

    private void toastLong(String text) {
        if (standby) return;
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
