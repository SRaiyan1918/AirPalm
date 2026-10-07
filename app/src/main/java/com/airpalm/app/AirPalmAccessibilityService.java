package com.airpalm.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

    // ------------------------------------------------------------------
    // Drag session: a virtual finger that follows the hand.
    //
    // Mode 1 (smooth): one finger stays down and is moved with chained "continued" strokes.
    //   The chain is driven by a timer, not only by callbacks, because some phones never
    //   deliver onCompleted for a stroke that will continue (that looked like a long-press).
    // Mode 2 (safe, automatic fallback): if the phone cancels/refuses the chain, we switch to
    //   short proportional swipes instead. Works everywhere, but the page may glide a bit.
    // ------------------------------------------------------------------
    private final Handler dragHandler = new Handler(Looper.getMainLooper());
    private final Runnable pumpRunnable = this::pumpDrag;
    private GestureDescription.StrokeDescription dragStroke;
    private boolean dragActive, dragPending, dragEndRequested, endDispatched, hasPendingBegin;
    private boolean safeMode = false;
    private volatile float dragTargetY;
    private float dragX, dragY, appliedY, pendingX, pendingY;
    private int dragSeq;
    private long dragLastDispatch;
    private int stDispatch, stDone, stCancel, stFail;

    /** Short status for the camera preview label, e.g. "d12/12 c0 f0". */
    public static String statsSuffix() {
        AirPalmAccessibilityService s = instance;
        if (s == null || s.stDispatch == 0) return "";
        return " d" + s.stDispatch + "/" + s.stDone + " c" + s.stCancel + " f" + s.stFail
                + (s.safeMode ? " SAFE" : "");
    }

    public static void dragBegin(float x, float y) {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.dragHandler.post(() -> s.beginDrag(x, y));
    }

    public static void dragMove(float y) {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.dragTargetY = y;
    }

    public static void dragEnd() {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.dragHandler.post(s::endDrag);
    }

    private void schedulePump(long ms) {
        dragHandler.removeCallbacks(pumpRunnable);
        dragHandler.postDelayed(pumpRunnable, ms);
    }

    private void beginDrag(float x, float y) {
        if (dragActive) { // previous session is still lifting: start right after it
            pendingX = x;
            pendingY = y;
            hasPendingBegin = true;
            return;
        }
        dragX = x;
        dragY = y;
        appliedY = y;
        dragTargetY = y;
        dragEndRequested = false;
        endDispatched = false;
        dragPending = false;
        dragActive = true;
        if (safeMode) {
            schedulePump(60);
            return;
        }
        Path p = new Path();
        p.moveTo(x, y);
        try {
            dragStroke = new GestureDescription.StrokeDescription(p, 0, 30, true);
        } catch (Exception e) {
            enterSafeMode();
            return;
        }
        dispatchDrag(dragStroke, false);
    }

    private void endDrag() {
        hasPendingBegin = false;
        if (dragActive) {
            dragEndRequested = true;
            pumpDrag();
        }
    }

    private void pumpDrag() {
        if (!dragActive) return;
        long now = SystemClock.uptimeMillis();
        if (dragPending) {
            if (now - dragLastDispatch < 70) { // previous segment still running
                schedulePump(25);
                return;
            }
            dragPending = false; // callback never came; assume the segment is done
            if (endDispatched) {
                finishDrag();
                return;
            }
        }
        if (dragEndRequested && (safeMode || endDispatched)) {
            finishDrag();
            return;
        }
        if (safeMode) pumpSafe(); else pumpContinuous();
    }

    private void pumpContinuous() {
        boolean last = dragEndRequested;
        float h = getResources().getDisplayMetrics().heightPixels;
        float ny = last ? dragY : Math.max(h * 0.04f, Math.min(h * 0.96f, dragTargetY));

        Path p = new Path();
        p.moveTo(dragX, dragY);
        if (Math.abs(ny - dragY) >= 1f) p.lineTo(dragX, ny);

        GestureDescription.StrokeDescription next;
        try {
            next = dragStroke.continueStroke(p, 0, 40, !last);
        } catch (Exception e) {
            enterSafeMode();
            return;
        }
        dragStroke = next;
        dragY = ny;
        if (last) endDispatched = true;
        dispatchDrag(next, last);
    }

    private void pumpSafe() {
        float h = getResources().getDisplayMetrics().heightPixels;
        float delta = dragTargetY - appliedY;
        if (Math.abs(delta) >= 14f) {
            delta = Math.max(-h * 0.3f, Math.min(h * 0.3f, delta));
            float cy = h * 0.5f;
            Path p = new Path();
            p.moveTo(dragX, cy - delta / 2f);
            p.lineTo(dragX, cy + delta / 2f);
            long dur = (long) Math.max(70, Math.min(300, Math.abs(delta) * 1.4f));
            try {
                GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, dur);
                appliedY += delta;
                dispatchDrag(st, false);
            } catch (Exception ignored) {
                schedulePump(100);
            }
            return;
        }
        schedulePump(60);
    }

    private void dispatchDrag(GestureDescription.StrokeDescription stroke, boolean last) {
        final int seq = ++dragSeq;
        dragPending = true;
        dragLastDispatch = SystemClock.uptimeMillis();
        stDispatch++;
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (seq != dragSeq) return;
                stDone++;
                dragPending = false;
                if (last) finishDrag(); else pumpDrag();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (seq != dragSeq) return;
                stCancel++;
                dragPending = false;
                if (!safeMode) enterSafeMode(); else schedulePump(80);
            }
        }, dragHandler);
        if (!ok) {
            stFail++;
            dragPending = false;
            if (!safeMode) enterSafeMode(); else schedulePump(100);
            return;
        }
        // timer backup in case the completion callback is never delivered
        schedulePump(last ? 90 : 70);
    }

    private void enterSafeMode() {
        safeMode = true;
        dragPending = false;
        appliedY = dragY;
        if (dragActive) {
            if (dragEndRequested) finishDrag(); else schedulePump(60);
        }
    }

    private void finishDrag() {
        dragHandler.removeCallbacks(pumpRunnable);
        dragActive = false;
        dragPending = false;
        dragEndRequested = false;
        endDispatched = false;
        if (hasPendingBegin) {
            hasPendingBegin = false;
            beginDrag(pendingX, pendingY);
        }
    }

    public static void back() {
        AirPalmAccessibilityService s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
