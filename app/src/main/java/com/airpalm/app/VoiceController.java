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
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.telecom.TelecomManager;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice session, started/stopped from the floating mic button (or the open-palm gesture).
 *
 * - A fresh speech recogniser is created for every listening round (a reused one often says "busy").
 * - The recogniser uses the phone's own microphone (so you hear the usual start beep).
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

    private final Speaker speaker;
    private final Nlu.Engine nlu = new Nlu.TfliteSlot();
    private boolean replied = false;
    private boolean everReady = false;
    private final ArrayList<Integer> mutedStreams = new ArrayList<>();
    private boolean sessionOpen = false;
    private Runnable onSessionStart, onSessionEnd;

    public VoiceController(Context ctx, SharedPreferences prefs, Speaker speaker) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.speaker = speaker;
        recoverMute(); // if the app was killed while the beep was muted, give the sound back
    }

    /** Called when a listening session starts / ends (the wake word listener pauses and resumes with these). */
    public void setSessionListener(Runnable onStart, Runnable onEnd) {
        onSessionStart = onStart;
        onSessionEnd = onEnd;
    }

    /** "Jarvis" was heard: take exactly one command, then stop. Safe from any thread. */
    public void triggerSingle() {
        main.post(() -> {
            if (!active) {
                start(true);
                // could not start (no permission, no recogniser): let the wake word listener carry on
                if (!active && onSessionEnd != null) onSessionEnd.run();
            }
        });
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
            if (active) stop("Voice OFF"); else start(false);
        });
    }

    public void destroy() {
        main.post(() -> {
            active = false;
            setState(STATE_OFF);
            main.removeCallbacksAndMessages(null);
            unmuteBeep();
            destroyRecognizer();
            unregisterScreenReceiver();
            releaseStandbyLock();
        });
    }

    // ------------------------------------------------------------------ session

    private void start(boolean forceOneShot) {
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
        oneShot = forceOneShot || (am != null && am.isMusicActive()); // 'Jarvis' = one command; also when something is playing
        standby = false;
        active = true;
        errorStreak = 0;
        warnedNetwork = false;
        lastActivity = SystemClock.uptimeMillis();
        registerScreenReceiver();
        buzz();
        if (!forceOneShot) {
            toast(oneShot ? "Listening for one command..." : "Listening...  (say: YouTube kholo / tap Subscribe / help)");
        }
        sessionOpen = true;
        everReady = false;
        if (onSessionStart != null) onSessionStart.run();
        setState(STATE_PREPARING);
        cycle(0);
        main.removeCallbacks(timeoutCheck);
        main.postDelayed(timeoutCheck, 3000);
    }

    private void stop(String message) {
        active = false;
        standby = false;
        main.removeCallbacksAndMessages(null);
        unmuteBeep();
        destroyRecognizer();
        unregisterScreenReceiver();
        releaseStandbyLock();
        setState(STATE_OFF);
        buzz();
        if (message != null) toast(message);
        if (sessionOpen) {
            sessionOpen = false;
            if (onSessionEnd != null) onSessionEnd.run();
        }
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
        if (!everReady) setState(STATE_PREPARING); // amber only until the first round is really ready
        muteBeep();
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            unmuteBeep();
            main.postDelayed(() -> cycle(0), 800);
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {
            if (active) {
                everReady = true;
                setState(STATE_LISTENING);
            }
        }
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
            unmuteBeep(); // sound back before any spoken reply
            ArrayList<String> heard = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            handle(heard);
            if (!active) return;
            if (oneShot) {
                stop(null);
            } else if (speaker != null && prefs.getBoolean("speak_continuous", true)) {
                speaker.runWhenIdle(() -> cycle(0)); // do not listen to our own voice
            } else {
                cycle(0); // straight into the next command, no pause
            }
        }

        @Override
        public void onError(int error) {
            if (!active) return;
            unmuteBeep();
            if (!everReady) setState(STATE_PREPARING);
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    if (oneShot) stop("Kuch suna nahi - mic dobara dabao");
                    else cycle(0); // just silence: listen again at once
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
                    if (++errorStreak >= 6) stop("Voice OFF (recognizer problem)");
                    else cycle(500);
                    break;
            }
        }
    };

    // ------------------------------------------------------------------ silence Google's start/end beep

    private static final int[] BEEP_STREAMS = {AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_MUSIC};
    private final Runnable beepFailsafe = this::unmuteBeep;

    /** The recogniser's beeps play on one of a few streams (depends on phone and Android version): mute them while listening. */
    private void muteBeep() {
        if (!prefs.getBoolean("mute_beep", true) || !mutedStreams.isEmpty()) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        for (int st : BEEP_STREAMS) {
            try {
                if (!am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0);
                    mutedStreams.add(st);
                }
            } catch (Exception ignored) {
                // some phones do not allow muting every stream without Do-Not-Disturb access
            }
        }
        if (!mutedStreams.isEmpty()) {
            prefs.edit().putBoolean("beep_muted", true).apply();
            main.removeCallbacks(beepFailsafe);
            main.postDelayed(beepFailsafe, 20000);
        }
    }

    private void unmuteBeep() {
        main.removeCallbacks(beepFailsafe);
        if (mutedStreams.isEmpty()) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (int st : new ArrayList<>(mutedStreams)) {
                try {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0);
                } catch (Exception ignored) {
                }
            }
        }
        mutedStreams.clear();
        prefs.edit().putBoolean("beep_muted", false).apply();
    }

    private void recoverMute() {
        if (!prefs.getBoolean("beep_muted", false)) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (int st : BEEP_STREAMS) {
                try {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0);
                } catch (Exception ignored) {
                }
            }
        }
        prefs.edit().putBoolean("beep_muted", false).apply();
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
            Nlu.Result res = nlu.parse(h);
            VoiceCommandParser.Command c = res.command;
            if (c.type == VoiceCommandParser.UNKNOWN) continue;
            Log.d("AirPalmNLU", "heard=\"" + h + "\" -> " + res.toJson());
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
            replied = false;
            run(c);
            if (!replied) say(Replies.done());
            return;
        }
        lastActivity = SystemClock.uptimeMillis();
        if (standby) return;
        if (notFound != null) fail("App not found: " + title(notFound), Replies.appNotFound(title(notFound)));
        else fail("Samajh nahi aaya: " + heard.get(0), Replies.notUnderstood());
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
                    replied = true;
                    stop(null);
                    AirPalmAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                }
                break;
            case VoiceCommandParser.SCREEN_OFF:
                if (Build.VERSION.SDK_INT >= 28) {
                    toast("Screen off - still listening (say: screen on)");
                    say(Replies.screenOff());
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
                say(Replies.volumeUp());
                break;
            case VoiceCommandParser.VOLUME_DOWN:
                volume(AudioManager.ADJUST_LOWER);
                say(Replies.volumeDown());
                break;
            case VoiceCommandParser.VOLUME_SET:
                Skills.setVolumePercent(ctx, Integer.parseInt(c.arg));
                say(Replies.volumeSet(Integer.parseInt(c.arg)));
                break;
            case VoiceCommandParser.MUTE:
                Skills.mute(ctx, true);
                say(Replies.muted());
                break;
            case VoiceCommandParser.UNMUTE:
                Skills.mute(ctx, false);
                say(Replies.unmuted());
                break;
            case VoiceCommandParser.FLASH_ON:
            case VoiceCommandParser.FLASH_OFF:
            case VoiceCommandParser.FLASH_TOGGLE: {
                int mode = c.type == VoiceCommandParser.FLASH_ON ? 1 : (c.type == VoiceCommandParser.FLASH_OFF ? 0 : 2);
                Boolean on = Skills.flashlight(ctx, mode);
                if (on == null) fail("Flashlight not available (is the camera in use?)", Replies.failed());
                else say(on ? Replies.flashOn() : Replies.flashOff());
                break;
            }
            case VoiceCommandParser.TIME:
                say(Skills.timeReply());
                break;
            case VoiceCommandParser.DATE:
                say(Skills.dateReply());
                break;
            case VoiceCommandParser.BATTERY:
                say(Skills.batteryReply(ctx));
                break;
            case VoiceCommandParser.SEARCH:
                search(c.arg);
                break;
            case VoiceCommandParser.VOICE_OFF:
                stop(standby ? null : "Voice OFF");
                break;
            case VoiceCommandParser.TAP_TEXT: {
                String hit = AirPalmAccessibilityService.tapText(c.arg);
                if (hit == null) fail("Not found on screen: " + c.arg + "  (try: show numbers)", Replies.notOnScreen(c.arg));
                break;
            }
            case VoiceCommandParser.SHOW_NUMBERS: {
                int n = AirPalmAccessibilityService.showNumbers();
                if (n == 0) {
                    fail("Nothing to tap on this screen", Replies.failed());
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
                    fail("Say 'show numbers' first", Replies.failed());
                } else if (!AirPalmAccessibilityService.tapNumber(n)) {
                    fail("No item numbered " + n, Replies.failed());
                }
                break;
            }
            case VoiceCommandParser.TYPE_TEXT:
                if (!AirPalmAccessibilityService.typeText(c.arg)) {
                    fail("No text box found - tap a text field first", Replies.noTextBox());
                } else {
                    say(Replies.typed());
                }
                break;
            case VoiceCommandParser.REPLACE: {
                int r = AirPalmAccessibilityService.replaceText(c.arg, c.arg2);
                if (r == 1) fail("No text box found", Replies.noTextBox());
                else if (r == 2) fail("'" + c.arg + "' not found in the text", Replies.failed());
                break;
            }
            case VoiceCommandParser.UNDO:
                if (!AirPalmAccessibilityService.undoText()) fail("Nothing to undo", Replies.failed());
                break;
            case VoiceCommandParser.REDO:
                if (!AirPalmAccessibilityService.redoText()) fail("Nothing to redo", Replies.failed());
                break;
            case VoiceCommandParser.SEND:
                if (AirPalmAccessibilityService.tapText("send") == null && !AirPalmAccessibilityService.imeEnter()) {
                    fail("No Send button found", Replies.failed());
                }
                break;
            case VoiceCommandParser.ENTER:
                if (!AirPalmAccessibilityService.imeEnter()) fail("Enter is not available here", Replies.failed());
                break;
            case VoiceCommandParser.CLEAR_TEXT:
                if (!AirPalmAccessibilityService.clearText()) fail("No text to clear", Replies.failed());
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
                        + "pause, play | flashlight on/off | time, battery | screen off / on | answer call | "
                        + "volume up/down/50 | mute | search <text> | stop listening");
                break;
            default:
                break;
        }
    }

    private void answerCall() {
        if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            fail("Allow call control in the AirPalm app (button 4)", Replies.needPermission("call control"));
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.acceptRingingCall();
                replied = true; // no speech: the call needs the speaker and the mic
                stop(standby ? null : "Call answered - listening off");
            }
        } catch (Exception e) {
            fail("Could not answer the call", Replies.failed());
        }
    }

    private void rejectCall() {
        if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            fail("Allow call control in the AirPalm app (button 4)", Replies.needPermission("call control"));
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= 28) {
                tm.endCall();
                toast("Call ended");
                say(Replies.callEnded());
            }
        } catch (Exception e) {
            fail("Could not end the call", Replies.failed());
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
            fail("Cannot open: " + label, Replies.failed());
            return;
        }
        toast("Opening " + label);
        say(Replies.openApp(label));
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

    private void say(Replies.Reply r) {
        replied = true;
        if (standby || speaker == null) return;
        if (!oneShot && !prefs.getBoolean("speak_continuous", true)) return; // floating-mic mode, replies switched off
        speaker.say(r);
    }

    private void fail(String toastText, Replies.Reply r) {
        toast(toastText);
        say(r);
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
