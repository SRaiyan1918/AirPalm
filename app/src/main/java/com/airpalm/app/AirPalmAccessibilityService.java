package com.airpalm.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
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
    // Drag session: a virtual finger that stays down on the screen and follows the hand.
    // Built from chained "continued" strokes (API 26+). Ending it with a still segment
    // means the page does not fling after you let go.
    // ------------------------------------------------------------------
    private final Handler dragHandler = new Handler(Looper.getMainLooper());
    private GestureDescription.StrokeDescription dragStroke;
    private boolean dragActive, dragPending, dragEndRequested, hasPendingBegin;
    private float dragX, dragY, dragTargetY, pendingX, pendingY;

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

    private void beginDrag(float x, float y) {
        if (dragActive) { // previous session is still lifting: start right after it
            pendingX = x;
            pendingY = y;
            hasPendingBegin = true;
            return;
        }
        dragX = x;
        dragY = y;
        dragTargetY = y;
        dragEndRequested = false;
        Path p = new Path();
        p.moveTo(x, y);
        try {
            dragStroke = new GestureDescription.StrokeDescription(p, 0, 30, true);
        } catch (Exception e) {
            return;
        }
        dragActive = true;
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
        if (!dragActive || dragPending) return;
        boolean last = dragEndRequested;
        float h = getResources().getDisplayMetrics().heightPixels;
        float ny = last ? dragY : Math.max(h * 0.04f, Math.min(h * 0.96f, dragTargetY));

        Path p = new Path();
        p.moveTo(dragX, dragY);
        if (Math.abs(ny - dragY) >= 1f) p.lineTo(dragX, ny);

        GestureDescription.StrokeDescription next;
        try {
            next = dragStroke.continueStroke(p, 0, last ? 40 : 45, !last);
        } catch (Exception e) {
            abortDrag();
            return;
        }
        dragStroke = next;
        dragY = ny;
        dispatchDrag(next, last);
    }

    private void dispatchDrag(GestureDescription.StrokeDescription stroke, boolean last) {
        dragPending = true;
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                dragPending = false;
                if (last) {
                    finishDrag();
                } else {
                    pumpDrag();
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                dragPending = false;
                abortDrag();
            }
        }, dragHandler);
        if (!ok) {
            dragPending = false;
            abortDrag();
        }
    }

    private void finishDrag() {
        dragActive = false;
        dragEndRequested = false;
        if (hasPendingBegin) {
            hasPendingBegin = false;
            beginDrag(pendingX, pendingY);
        }
    }

    private void abortDrag() {
        dragActive = false;
        dragPending = false;
        dragEndRequested = false;
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
