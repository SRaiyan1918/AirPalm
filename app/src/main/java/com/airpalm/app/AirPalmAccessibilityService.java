package com.airpalm.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        String base = currentText(f);
        String result = base.isEmpty() ? text : base + (base.endsWith(" ") ? "" : " ") + text;
        boolean ok = setText(f, result);
        if (ok) s.remember(base, result);
        return ok;
    }

    public static boolean clearText() {
        AirPalmAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo f = s.editableTarget();
        if (f == null) return false;
        String base = currentText(f);
        if (base.isEmpty()) return true; // already empty: nothing to do, not an error
        boolean ok = setText(f, "");
        if (ok) s.remember(base, "");
        return ok;
    }

    private static String currentText(AccessibilityNodeInfo f) {
        CharSequence cur = f.getText();
        boolean hint = Build.VERSION.SDK_INT >= 26 && f.isShowingHintText();
        return (cur == null || hint) ? "" : cur.toString();
    }

    // ---- undo / redo / replace for dictated text ----

    private static final class Edit {
        final String before, after;

        Edit(String before, String after) {
            this.before = before;
            this.after = after;
        }
    }

    private final ArrayDeque<Edit> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Edit> redoStack = new ArrayDeque<>();

    private void remember(String before, String after) {
        undoStack.push(new Edit(before, after));
        while (undoStack.size() > 30) undoStack.removeLast();
        redoStack.clear();
    }

    /** Removes the last thing that was typed / replaced / cleared by voice. */
    public static boolean undoText() {
        AirPalmAccessibilityService s = instance;
        if (s == null || s.undoStack.isEmpty()) return false;
        AccessibilityNodeInfo f = s.editableTarget();
        if (f == null) return false;
        Edit e = s.undoStack.peek();
        if (!setText(f, e.before)) return false;
        s.undoStack.pop();
        s.redoStack.push(e);
        return true;
    }

    public static boolean redoText() {
        AirPalmAccessibilityService s = instance;
        if (s == null || s.redoStack.isEmpty()) return false;
        AccessibilityNodeInfo f = s.editableTarget();
        if (f == null) return false;
        Edit e = s.redoStack.peek();
        if (!setText(f, e.after)) return false;
        s.redoStack.pop();
        s.undoStack.push(e);
        return true;
    }

    /**
     * Replaces the most recent occurrence of {@code from} with {@code to} in the text box.
     * If the exact words are not there, the most similar word is replaced (speech is not always exact).
     * @return 0 = done, 1 = no text box, 2 = not found
     */
    public static int replaceText(String from, String to) {
        AirPalmAccessibilityService s = instance;
        if (s == null) return 1;
        AccessibilityNodeInfo f = s.editableTarget();
        if (f == null) return 1;
        String text = currentText(f);
        if (text.isEmpty()) return 2;

        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerFrom = from.toLowerCase(Locale.ROOT).trim();
        int start = lowerFrom.isEmpty() ? -1 : lowerText.lastIndexOf(lowerFrom);
        int end = start + lowerFrom.length();

        if (start < 0 && lowerFrom.length() >= 3 && lowerFrom.indexOf(' ') < 0) {
            // closest single word
            int bestDist = Integer.MAX_VALUE;
            Matcher m = Pattern.compile("[\\p{L}\\p{N}]+").matcher(lowerText);
            while (m.find()) {
                int d = VoiceCommandParser.levenshtein(m.group(), lowerFrom);
                if (d <= Math.max(1, lowerFrom.length() / 3) && d <= bestDist) {
                    bestDist = d;
                    start = m.start();
                    end = m.end();
                }
            }
        }
        if (start < 0) return 2;

        String result = text.substring(0, start) + to + text.substring(end);
        if (!setText(f, result)) return 1;
        s.remember(text, result);
        return 0;
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

    // ------------------------------------------------------------------
    // "show numbers": every tappable thing gets a number, then "tap 5" (works for icons without names)
    // ------------------------------------------------------------------

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideNumbers = this::hideNumbersInternal;
    private final ArrayList<AccessibilityNodeInfo> numberNodes = new ArrayList<>();
    private final ArrayList<Rect> numberRects = new ArrayList<>();
    private NumbersView numbersView;

    public static int showNumbers() {
        AirPalmAccessibilityService s = instance;
        return s == null ? 0 : s.showNumbersInternal();
    }

    public static void hideNumbers() {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.hideNumbersInternal();
    }

    public static boolean numbersVisible() {
        AirPalmAccessibilityService s = instance;
        return s != null && s.numbersView != null;
    }

    /** Taps item number n (1-based) and hides the numbers. */
    public static boolean tapNumber(int n) {
        AirPalmAccessibilityService s = instance;
        if (s == null || n < 1 || n > s.numberNodes.size()) return false;
        AccessibilityNodeInfo node = s.numberNodes.get(n - 1);
        Rect r = new Rect(s.numberRects.get(n - 1));
        s.hideNumbersInternal();
        boolean ok = false;
        try {
            ok = node.refresh() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {
        }
        if (!ok) tap(r.centerX(), r.centerY());
        return true;
    }

    private int showNumbersInternal() {
        hideNumbersInternal();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return 0;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        long screenArea = (long) dm.widthPixels * dm.heightPixels;

        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        ArrayList<Rect> rects = new ArrayList<>();
        collectTappable(root, nodes, rects, screenArea, 0, new int[]{0});
        if (nodes.isEmpty()) return 0;

        // reading order: rows from the top, left to right inside a row
        final int row = Math.max(1, dm.heightPixels / 28);
        Integer[] order = new Integer[nodes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int ra = rects.get(a).centerY() / row, rb = rects.get(b).centerY() / row;
            if (ra != rb) return Integer.compare(ra, rb);
            return Integer.compare(rects.get(a).centerX(), rects.get(b).centerX());
        });
        int count = Math.min(order.length, 90);
        for (int i = 0; i < count; i++) {
            numberNodes.add(nodes.get(order[i]));
            numberRects.add(rects.get(order[i]));
        }

        numbersView = new NumbersView(this, numberRects);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(numbersView, lp);
        } catch (Exception e) {
            numbersView = null;
            numberNodes.clear();
            numberRects.clear();
            return 0;
        }
        uiHandler.removeCallbacks(autoHideNumbers);
        uiHandler.postDelayed(autoHideNumbers, 25000);
        return count;
    }

    private void hideNumbersInternal() {
        uiHandler.removeCallbacks(autoHideNumbers);
        if (numbersView != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(numbersView);
            } catch (Exception ignored) {
            }
            numbersView = null;
        }
        numberNodes.clear();
        numberRects.clear();
    }

    private void collectTappable(AccessibilityNodeInfo n, ArrayList<AccessibilityNodeInfo> nodes,
                                 ArrayList<Rect> rects, long screenArea, int depth, int[] count) {
        if (n == null || depth > 40 || ++count[0] > 4000) return;
        if (n.isVisibleToUser() && n.isEnabled() && (n.isClickable() || n.isEditable())) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (r.width() >= 10 && r.height() >= 10 && area < screenArea * 0.7 && !rects.contains(r)) {
                nodes.add(n);
                rects.add(r);
            }
        }
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) collectTappable(n.getChild(i), nodes, rects, screenArea, depth + 1, count);
    }

    private static class NumbersView extends View {
        private final ArrayList<Rect> rects;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        NumbersView(android.content.Context c, ArrayList<Rect> rects) {
            super(c);
            this.rects = rects;
            float d = c.getResources().getDisplayMetrics().density;
            radius = 13 * d;
            fill.setColor(Color.rgb(255, 170, 0));
            ring.setColor(Color.WHITE);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            text.setColor(Color.BLACK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setTextSize(13 * d);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            for (int i = 0; i < rects.size(); i++) {
                Rect r = rects.get(i);
                float x = r.centerX() - loc[0];
                float y = r.centerY() - loc[1];
                canvas.drawCircle(x, y, radius, fill);
                canvas.drawCircle(x, y, radius, ring);
                canvas.drawText(String.valueOf(i + 1), x, y + text.getTextSize() * 0.35f, text);
            }
        }
    }
}
