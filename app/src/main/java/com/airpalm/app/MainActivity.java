package com.airpalm.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 10;
    private static final int REQ_AUDIO = 12;
    private static final int REQ_CALLS = 13;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(34));
        root.setBackgroundColor(Color.rgb(12, 15, 20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AirPalm");
        title.setTextSize(38);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, fullWidth());

        TextView sub = new TextView(this);
        sub.setText("Voice control for your phone.\nTap the floating mic button, then speak.");
        sub.setTextSize(15);
        sub.setTextColor(Color.rgb(170, 185, 205));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(8), 0, dp(26));
        root.addView(sub, fullWidth());

        status = new TextView(this);
        status.setTextSize(16);
        status.setTextColor(Color.WHITE);
        status.setPadding(dp(18), dp(18), dp(18), dp(18));
        status.setBackgroundColor(Color.rgb(27, 33, 43));
        root.addView(status, fullWidth());

        root.addView(makeButton("1. Allow microphone", v -> micButton()), spaced());
        root.addView(makeButton("2. Allow floating button (draw over apps)", v -> openOverlayPermission()), spaced());
        root.addView(makeButton("3. Enable AirPalm accessibility", v -> openAccessibilitySettings()), spaced());
        root.addView(makeButton("4. Allow call control (answer / reject by voice)", v -> callsButton()), spaced());
        root.addView(makeButton("START AIRPALM", v -> startAirPalm()), spaced());
        root.addView(makeButton("STOP", v -> {
            stopService(new Intent(this, HandTrackingService.class));
            refreshStatus();
        }), spaced());

        addVoiceSection(root);
        addHandSection(root);

        TextView note = new TextView(this);
        note.setText("After START, leave this app. A round mic button floats over every app: tap it to listen "
                + "(red = listening), drag it to move it. It switches off by itself after ~40 s without a command. "
                + "If you updated AirPalm, turn the accessibility service OFF and ON once so \"Screen reading\" says READY.");
        note.setTextSize(14);
        note.setTextColor(Color.rgb(145, 155, 170));
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note, fullWidth());

        return scroll;
    }

    private void addVoiceSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);

        TextView vh = new TextView(this);
        vh.setText("Voice");
        vh.setTextSize(20);
        vh.setTextColor(Color.WHITE);
        vh.setPadding(0, dp(28), 0, dp(4));
        root.addView(vh, fullWidth());

        CheckBox hindi = new CheckBox(this);
        hindi.setText("Hindi recognition (off = English-India, which also understands Hinglish)");
        hindi.setTextColor(Color.rgb(170, 185, 205));
        hindi.setChecked(prefs.getBoolean("hindi", false));
        hindi.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("hindi", checked).apply());
        root.addView(hindi, spaced());

        CheckBox ownAudio = new CheckBox(this);
        ownAudio.setText("Keep videos/reels playing while listening (experimental, Android 13+)");
        ownAudio.setTextColor(Color.rgb(170, 185, 205));
        ownAudio.setChecked(prefs.getBoolean("own_audio", true));
        ownAudio.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("own_audio", checked).putBoolean("own_audio_failed", false).apply());
        root.addView(ownAudio, spaced());

        TextView vhelp = new TextView(this);
        vhelp.setText("Mic button: grey = off, orange = getting ready (wait), red = listening (speak now).\n"
                + "If a video or reel is playing, the mic takes ONE command per tap.\n\n"
                + "Open apps: \"YouTube kholo\", \"open Chrome\"\n"
                + "System: back, home, recents, notifications, quick settings, screenshot, lock screen\n"
                + "Swipe: swipe up / down / left / right\n"
                + "Tap by name: \"tap Subscribe\", \"click Search\", \"Like dabao\"\n"
                + "Tap any icon: \"show numbers\", then say the number (\"5\" or \"tap 5\"), \"hide numbers\"\n"
                + "Typing: \"likho hello kaise ho\" (also \"type\" / \"write\"), then undo, redo, "
                + "\"replace hello with hi\", send, enter, clear\n"
                + "Video: \"pause\", \"play\"\n"
                + "Screen: \"screen off\" (keeps listening), \"screen on\"\n"
                + "Calls: \"answer call\" (then listening switches off), \"reject call\"\n"
                + "Other: volume up / down, \"search <text>\", help, stop listening\n\n"
                + "App not installed? You will see: App not found: <name>.");
        vhelp.setTextSize(13);
        vhelp.setTextColor(Color.rgb(120, 135, 160));
        vhelp.setPadding(0, dp(8), 0, 0);
        root.addView(vhelp, fullWidth());
    }

    private void addHandSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);

        TextView head = new TextView(this);
        head.setText("Hand control (optional, experimental)");
        head.setTextSize(20);
        head.setTextColor(Color.WHITE);
        head.setPadding(0, dp(34), 0, dp(4));
        root.addView(head, fullWidth());

        CheckBox hand = new CheckBox(this);
        hand.setText("Use hand control (uses the camera, more battery). STOP and START to apply.");
        hand.setTextColor(Color.rgb(170, 185, 205));
        hand.setChecked(prefs.getBoolean("hand", false));
        hand.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("hand", checked).apply();
            if (checked) requestCamera();
            refreshStatus();
        });
        root.addView(hand, spaced());

        root.addView(makeButton("Allow camera (only for hand control)", v -> requestCamera()), spaced());

        TextView how = new TextView(this);
        how.setText("Move: index finger. Click: thumb away from middle finger, then tap it back. "
                + "Back: touch thumb + middle finger and hold ~1 s. Swipe: two fingers up, then flick the hand. "
                + "Voice on/off: open palm held still ~1 s.");
        how.setTextSize(13);
        how.setTextColor(Color.rgb(120, 135, 160));
        how.setPadding(0, dp(8), 0, 0);
        root.addView(how, fullWidth());

        addSlider(root, prefs, "smooth", 50, "Cursor smoothness (left = fast/jittery, right = smooth/laggy)");
        addSlider(root, prefs, "pinch", 43, "Click sensitivity (right = easier to trigger)");
        addSlider(root, prefs, "swipe", 50, "Swipe sensitivity (right = smaller flick is enough)");
        addSlider(root, prefs, "swipelen", 40, "Swipe length (how far each swipe goes)");

        CheckBox preview = new CheckBox(this);
        preview.setText("Show camera preview (restart to apply)");
        preview.setTextColor(Color.rgb(170, 185, 205));
        preview.setChecked(prefs.getBoolean("preview", true));
        preview.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("preview", checked).apply());
        root.addView(preview, spaced());
    }

    /** Button 1: asks for the microphone; if Android no longer shows the dialog, opens the app settings. */
    private void micButton() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);
        boolean asked = prefs.getBoolean("mic_asked", false);
        if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Android will not show the dialog again: send the user to the app's permission page
            Toast.makeText(this, "Open Permissions > Microphone > Allow", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        prefs.edit().putBoolean("mic_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    /** Button 4: call control (answer / reject). */
    private void callsButton() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Call control already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);
        boolean asked = prefs.getBoolean("calls_asked", false);
        if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.ANSWER_PHONE_CALLS)) {
            Toast.makeText(this, "Open Permissions > Phone > Allow", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        prefs.edit().putBoolean("calls_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, REQ_CALLS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    private void requestAudioIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    private void addSlider(LinearLayout root, SharedPreferences prefs, String key, int def, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(Color.rgb(170, 185, 205));
        t.setPadding(0, dp(12), 0, 0);
        root.addView(t, fullWidth());

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(prefs.getInt(key, def));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) prefs.edit().putInt(key, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        root.addView(bar, fullWidth());
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams p = fullWidth();
        p.topMargin = dp(12);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestCamera() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    private void openOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String target = new ComponentName(this, AirPalmAccessibilityService.class).flattenToString();
        return enabled.toLowerCase().contains(target.toLowerCase());
    }

    private void startAirPalm() {
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);
        boolean hand = prefs.getBoolean("hand", false);
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        if (!mic && !(hand && cam)) {
            Toast.makeText(this, "Allow the microphone first (button 1), then tap START again", Toast.LENGTH_LONG).show();
            micButton();
            return;
        }
        if (hand && !cam) {
            Toast.makeText(this, "Hand control needs the camera permission", Toast.LENGTH_LONG).show();
            requestCamera();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission();
            return;
        }
        if (!accessibilityEnabled()) {
            openAccessibilitySettings();
            return;
        }

        Intent intent = new Intent(this, HandTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean access = accessibilityEnabled();
        boolean hand = prefs.getBoolean("hand", false);
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        status.setText(
                "Microphone: " + mark(mic) +
                "\nFloating button: " + mark(overlay) +
                "\nAccessibility: " + mark(access) +
                "\nCall control (optional): " + mark(checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                        == PackageManager.PERMISSION_GRANTED) +
                "\nScreen reading (tap / type): " + (AirPalmAccessibilityService.canReadScreen()
                        ? "READY" : (access ? "OFF - turn accessibility off and on" : "NEEDED")) +
                "\nHand control: " + (hand ? (cam ? "ON" : "needs camera") : "off") +
                "\nAirPalm service: " + (HandTrackingService.isRunning ? "RUNNING" : "STOPPED"));
    }

    private String mark(boolean ok) {
        return ok ? "READY" : "NEEDED";
    }
}
