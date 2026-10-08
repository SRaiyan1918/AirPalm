package com.airpalm.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
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

    /** One fixed swipe: the finger goes from (x0,y0) to (x1,y1) in durationMs. */
    public static void swipe(float x0, float y0, float x1, float y1, long durationMs) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return;

        Path path = new Path();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs)))
                .build();
        s.dispatchGesture(gesture, null, null);
    }

    public static void back() {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** Home, recents, notifications, quick settings, screenshot, lock screen (GLOBAL_ACTION_*). */
    public static boolean global(int action) {
        AirPalmAccessibilityService s = instance;
        return s != null && s.performGlobalAction(action);
    }

    /**
     * Starts an activity from the accessibility service (allowed even when AirPalm is in the background).
     */
    public static boolean launch(Intent intent) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return false;
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            s.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Voice swipe, same sizes as the hand swipe defaults. dir uses GestureEngine.DIR_*. */
    public static void swipeDir(int dir) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return;
        float w = s.getResources().getDisplayMetrics().widthPixels;
        float h = s.getResources().getDisplayMetrics().heightPixels;
        float cx = w * 0.5f, cy = h * 0.5f;
        float len;
        float x0 = cx, y0 = cy, x1 = cx, y1 = cy;
        switch (dir) {
            case GestureEngine.DIR_UP:
                len = h * 0.33f; y0 = cy + len / 2f; y1 = cy - len / 2f; break;
            case GestureEngine.DIR_DOWN:
                len = h * 0.33f; y0 = cy - len / 2f; y1 = cy + len / 2f; break;
            case GestureEngine.DIR_LEFT:
                len = w * 0.55f; x0 = cx + len / 2f; x1 = cx - len / 2f; break;
            default:
                len = w * 0.55f; x0 = cx - len / 2f; x1 = cx + len / 2f; break;
        }
        swipe(x0, y0, x1, y1, (long) Math.max(250f, Math.min(600f, len * 0.9f)));
    }
}
