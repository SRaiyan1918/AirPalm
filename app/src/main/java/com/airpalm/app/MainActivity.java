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

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 10;
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
        sub.setText("Touchless Android control\nMove: index finger\nTap: pinch thumb+index, release quickly\nScroll: two fingers up (index+middle) and move hand up/down, OR pinch-hold and move up/down\nBack: pinch thumb + middle finger");
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

        root.addView(makeButton("1. Allow camera", v -> requestCamera()), spaced());
        root.addView(makeButton("2. Allow floating cursor", v -> openOverlayPermission()), spaced());
        root.addView(makeButton("3. Enable AirPalm accessibility", v -> openAccessibilitySettings()), spaced());
        root.addView(makeButton("START AIRPALM", v -> startAirPalm()), spaced());
        root.addView(makeButton("STOP", v -> {
            stopService(new Intent(this, HandTrackingService.class));
            refreshStatus();
        }), spaced());

        addTuning(root);

        TextView note = new TextView(this);
        note.setText("After START, leave this app and open any normal app. Keep your hand inside the front-camera view. Cursor colour: green = moving, yellow = pinch closing (cursor frozen), red = pressed, blue = scrolling. Sliders apply live, no restart needed.");
        note.setTextSize(14);
        note.setTextColor(Color.rgb(145, 155, 170));
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note, fullWidth());

        return scroll;
    }

    private void addTuning(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences("airpalm", MODE_PRIVATE);

        TextView head = new TextView(this);
        head.setText("Tuning");
        head.setTextSize(20);
        head.setTextColor(Color.WHITE);
        head.setPadding(0, dp(28), 0, dp(4));
        root.addView(head, fullWidth());

        addSlider(root, prefs, "smooth", 50, "Cursor smoothness (left = fast/jittery, right = smooth/laggy)");
        addSlider(root, prefs, "pinch", 43, "Pinch sensitivity (right = easier to pinch)");
        addSlider(root, prefs, "scroll", 33, "Scroll speed");

        CheckBox preview = new CheckBox(this);
        preview.setText("Show camera preview (restart AirPalm to apply)");
        preview.setTextColor(Color.rgb(170, 185, 205));
        preview.setChecked(prefs.getBoolean("preview", true));
        preview.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("preview", checked).apply());
        root.addView(preview, spaced());
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean access = accessibilityEnabled();

        status.setText(
                "Camera: " + mark(camera) +
                "\nFloating cursor: " + mark(overlay) +
                "\nAccessibility: " + mark(access) +
                "\nAirPalm service: " + (HandTrackingService.isRunning ? "RUNNING" : "STOPPED"));
    }

    private String mark(boolean ok) {
        return ok ? "READY" : "NEEDED";
    }
}
