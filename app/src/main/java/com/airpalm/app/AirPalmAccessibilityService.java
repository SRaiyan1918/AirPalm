package com.airpalm.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class AirPalmAccessibilityService extends AccessibilityService {
    private static volatile AirPalmAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static void tap(float x, float y) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        s.dispatchGesture(gesture, null, null);
    }

    /**
     * Swipe centred on the middle of the screen.
     * dirSign +1 = finger moves UP (page scrolls down), -1 = finger moves DOWN.
     */
    public static void scroll(float x, int dirSign, float distancePx, long durationMs) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return;

        float h = s.getResources().getDisplayMetrics().heightPixels;
        float w = s.getResources().getDisplayMetrics().widthPixels;
        float half = Math.min(distancePx, h * 0.6f) / 2f;
        float cy = h * 0.5f;
        float startY = cy + dirSign * half;
        float endY = cy - dirSign * half;
        float cx = Math.max(w * 0.1f, Math.min(w * 0.9f, x));

        Path path = new Path();
        path.moveTo(cx, startY);
        path.lineTo(cx, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs)))
                .build();
        s.dispatchGesture(gesture, null, null);
    }

    public static void back() {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
