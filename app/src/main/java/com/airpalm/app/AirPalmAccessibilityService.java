package com.airpalm.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
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

    /** True when the running accessibility service is allowed to read the screen (tap by name / type). */
    public static boolean canReadScreen() {
        AirPalmAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityServiceInfo info = s.getServiceInfo();
        return info != null
                && (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
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

    // ------------------------------------------------------------------
    // Reading the screen: tap by name, type into the text box
    // ------------------------------------------------------------------

    private static class Best {
        final String spoken;
        AccessibilityNodeInfo node;
        String label;
        int rank = -1;
        int area = Integer.MAX_VALUE;

        Best(String spoken) {
            this.spoken = spoken;
        }
    }

    /**
     * Finds the visible text / description that best matches what was said and taps it.
     * @return the label that was tapped, or null if nothing on screen matched
     */
    public static String tapText(String spoken) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return null;

        Best best = new Best(spoken);
        s.scan(root, best, 0, new int[]{0});
        if (best.node == null) return null;

        AccessibilityNodeInfo c = best.node;
        for (int up = 0; c != null && !c.isClickable() && up < 6; up++) c = c.getParent();
        if (c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return best.label;
        }
        Rect r = new Rect();
        best.node.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            tap(r.centerX(), r.centerY());
            return best.label;
        }
        return null;
    }

    private void scan(AccessibilityNodeInfo n, Best b, int depth, int[] count) {
        if (n == null || depth > 40 || ++count[0] > 4000) return;
        if (n.isVisibleToUser()) {
            consider(n, n.getText(), b);
            consider(n, n.getContentDescription(), b);
            if (Build.VERSION.SDK_INT >= 26) consider(n, n.getHintText(), b);
        }
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) scan(n.getChild(i), b, depth + 1, count);
    }

    private void consider(AccessibilityNodeInfo n, CharSequence label, Best b) {
        if (label == null || label.length() == 0 || label.length() > 120) return;
        int sc = VoiceCommandParser.matchScore(b.spoken, label.toString());
        if (sc < 70) return;
        int rank = sc * 10 + (n.isClickable() ? 3 : 0) + (n.isEnabled() ? 1 : 0);
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        int area = Math.max(1, r.width() * r.height());
        if (rank > b.rank || (rank == b.rank && area < b.area)) {
            b.node = n;
            b.label = label.toString();
            b.rank = rank;
            b.area = area;
        }
    }

    private AccessibilityNodeInfo editableTarget() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null && f.isEditable()) return f;
        return findEditable(root, 0, new int[]{0});
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth, int[] count) {
        if (n == null || depth > 40 || ++count[0] > 4000) return null;
        if (n.isVisibleToUser() && n.isEditable()) return n;
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i), depth + 1, count);
            if (r != null) return r;
        }
        return null;
    }

    /** Adds text to the active text box (or the first visible one). */
    public static boolean typeText(String text) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo f = s.editableTarget();
        if (f == null) return false;
        if (!f.isFocused()) f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        CharSequence cur = f.getText();
        boolean hint = Build.VERSION.SDK_INT >= 26 && f.isShowingHintText();
        String base = (cur == null || hint) ? "" : cur.toString();
        String result = base.isEmpty() ? text : base + (base.endsWith(" ") ? "" : " ") + text;
        return setText(f, result);
    }

    public static boolean clearText() {
        AirPalmAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo f = s.editableTarget();
        return f != null && setText(f, "");
    }

    private static boolean setText(AccessibilityNodeInfo f, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Presses the keyboard's Enter / Search / Send key (Android 11+). */
    public static boolean imeEnter() {
        AirPalmAccessibilityService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo f = s.editableTarget();
        return f != null && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }
}
