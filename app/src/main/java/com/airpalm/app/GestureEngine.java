package com.airpalm.app;

/**
 * AirPalm gesture logic. Pure Java (no Android classes) so it can be tested on a PC.
 *
 * Gestures
 *   MOVE   : index finger tip moves the cursor
 *   TAP    : pinch thumb + index and release quickly (cursor is frozen where it was before the pinch)
 *   SCROLL : (a) two fingers up (index + middle), move hand up/down like a joystick
 *            (b) pinch and hold, then move hand up/down
 *   BACK   : pinch thumb + middle finger while index stays straight
 *
 * Everything is measured in "hand sizes" (wrist -> middle knuckle) so it works at any distance
 * from the camera and at any hand rotation.
 */
public class GestureEngine {

    public interface Listener {
        /** Cursor position in screen pixels and current mode (MODE_*). */
        void onCursor(float x, float y, int mode);
        void onTap(float x, float y);
        /** dirSign = +1 swipes finger UP (content moves up), -1 swipes DOWN. */
        void onScroll(float x, int dirSign, float distancePx, long durationMs);
        void onBack();
    }

    public static final int MODE_IDLE = 0;
    public static final int MODE_ARMED = 1;   // pinch is closing, cursor frozen
    public static final int MODE_PINCHED = 2; // pinch closed
    public static final int MODE_SCROLL = 3;  // two-finger scroll
    public static final int MODE_DRAG = 4;    // pinch-hold scroll
    public static final int MODE_LOST = 5;

    // ---- tunable (set through configure) ----
    private float smooth = 0.5f;
    private float pinchSens = 1f;
    private float scrollSpeed = 1f;

    // ---- fixed tuning ----
    private static final float ZONE_X0 = 0.12f, ZONE_X1 = 0.88f;
    private static final float ZONE_Y0 = 0.15f, ZONE_Y1 = 0.85f;
    private static final float FINGER_UP = 1.15f;        // tip/pip distance from wrist
    private static final float FINGER_UP_STRICT = 1.30f;  // ring + pinky must be below this
    private static final float SCROLL_DEAD = 0.30f;       // hand sizes
    private static final float DRAG_DEAD = 0.35f;
    private static final float SCROLL_FULL = 0.90f;
    private static final long LOOKBACK_MS = 150;
    private static final long LOST_GRACE_MS = 300;

    private final Listener listener;
    private final float screenW, screenH;

    private final OneEuro fx = new OneEuro();
    private final OneEuro fy = new OneEuro();

    // history of filtered cursor positions
    private static final int H = 16;
    private final long[] ht = new long[H];
    private final float[] hx = new float[H];
    private final float[] hy = new float[H];
    private int hCount = 0, hPos = 0;

    private long lastT = 0;
    private long lastSeen = 0;

    // pinch state machine
    private int phase = 0; // 0 none, 1 armed, 2 pinched
    private float frozenX, frozenY;
    private long armedAt, pinchAt;
    private boolean dragging = false;
    private boolean blockArm = false;
    private float pinchAnchorY;
    private long lastTap = 0;

    // two finger scroll
    private int scrollCount = 0;
    private boolean scrollActive = false;
    private float scrollAnchorY;
    private long nextScrollAt = 0;
    private long scrollEndedAt = -100000;

    // back gesture
    private boolean midPinching = false;
    private long lastBack = 0;

    // landmarks in pixels
    private float[] px = new float[21];
    private float[] py = new float[21];

    public GestureEngine(float screenW, float screenH, Listener l) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.listener = l;
        configure(50, 43, 33);
    }

    /** Slider values 0..100. */
    public void configure(int smoothP, int pinchP, int scrollP) {
        smooth = clamp01(smoothP / 100f);
        pinchSens = 0.7f + clamp01(pinchP / 100f) * 0.7f;
        scrollSpeed = 0.5f + clamp01(scrollP / 100f) * 1.5f;
        fx.minCutoff = 3.0f - 2.5f * smooth;
        fy.minCutoff = fx.minCutoff;
    }

    public boolean isIdle(long now) {
        return lastSeen == 0 || now - lastSeen > 4000;
    }

    /** Call when no hand is visible. */
    public String handLost(long now) {
        if (lastSeen != 0 && now - lastSeen > LOST_GRACE_MS) {
            reset();
        }
        return "NO HAND";
    }

    private void reset() {
        phase = 0;
        dragging = false;
        blockArm = false;
        scrollCount = 0;
        scrollActive = false;
        scrollEndedAt = -100000;
        midPinching = false;
        hCount = 0;
        hPos = 0;
        lastT = 0;
        fx.reset();
        fy.reset();
        listener.onCursor(-1, -1, MODE_LOST);
    }

    /**
     * @param lx,ly  21 landmarks, normalized 0..1 (already mirrored/rotated upright)
     * @param fw,fh  frame size in pixels (needed for correct aspect ratio)
     * @return short debug label
     */
    public String update(float[] lx, float[] ly, int fw, int fh, long now) {
        for (int i = 0; i < 21; i++) {
            px[i] = lx[i] * fw;
            py[i] = ly[i] * fh;
        }
        lastSeen = now;
        float dt = lastT == 0 ? 0.05f : Math.max(0.01f, (now - lastT) / 1000f);
        lastT = now;

        float size = dist(0, 9);
        if (size < 8f) return "HAND TOO SMALL";

        float idxPinch = dist(4, 8) / size;
        float midPinch = dist(4, 12) / size;
        boolean idxUp = ext(8, 6) > FINGER_UP;
        boolean midUp = ext(12, 10) > FINGER_UP;
        boolean ringUp = ext(16, 14) > FINGER_UP_STRICT;
        boolean pinkyUp = ext(20, 18) > FINGER_UP_STRICT;

        float s = pinchSens;
        float arm = 0.45f * s, armRel = 0.55f * s, on = 0.28f * s, off = 0.40f * s;

        // ---- cursor (index tip -> active zone -> One-Euro filter) ----
        float u = clamp01((lx[8] - ZONE_X0) / (ZONE_X1 - ZONE_X0));
        float v = clamp01((ly[8] - ZONE_Y0) / (ZONE_Y1 - ZONE_Y0));
        float curX = fx.filter(u, dt) * screenW;
        float curY = fy.filter(v, dt) * screenH;
        pushHistory(now, curX, curY);

        // ---- BACK: thumb + middle pinch, index straight ----
        if (midPinching) {
            if (midPinch > off) midPinching = false;
            listener.onCursor(curX, curY, MODE_IDLE);
            return "BACK";
        }
        boolean midPinchNow = midPinch < on && idxPinch > 0.7f * s && idxUp && !midUp;
        // note: when middle touches thumb it is bent, so midUp is false
        if (midPinchNow && now - lastBack > 900 && phase == 0 && !scrollActive) {
            midPinching = true;
            lastBack = now;
            listener.onBack();
            listener.onCursor(curX, curY, MODE_IDLE);
            return "BACK";
        }

        // ---- two-finger pose with tolerance ----
        boolean pose = idxUp && midUp && !ringUp && !pinkyUp && idxPinch > armRel && phase == 0;
        if (pose) {
            scrollCount = Math.min(8, scrollCount + 1);
        } else {
            scrollCount = Math.max(0, scrollCount - 2);
        }
        if (!scrollActive && scrollCount >= 3) {
            scrollActive = true;
            // if the pose only flickered away briefly, keep the old anchor so the scroll continues
            if (now - scrollEndedAt > 800) {
                scrollAnchorY = (py[8] + py[12]) * 0.5f;
            }
        }
        if (scrollActive && scrollCount == 0) {
            scrollActive = false;
            scrollEndedAt = now;
        }

        // ---- pinch state machine ----
        if (blockArm && idxPinch > armRel) blockArm = false;

        if (phase == 0) {
            if (!scrollActive && !blockArm && idxPinch < arm) {
                phase = 1;
                armedAt = now;
                float[] p = lookback(now - LOOKBACK_MS, curX, curY);
                frozenX = p[0];
                frozenY = p[1];
            }
        } else if (phase == 1) {
            if (idxPinch < on) {
                phase = 2;
                pinchAt = now;
                dragging = false;
                pinchAnchorY = (py[4] + py[8]) * 0.5f;
            } else if (idxPinch > armRel) {
                phase = 0;
            } else if (now - armedAt > 1500) {
                phase = 0;
                blockArm = true; // thumb is just resting near index; give the cursor back
            }
        } else { // phase 2
            if (idxPinch > off) {
                if (!dragging && now - pinchAt < 700 && now - lastTap > 250) {
                    lastTap = now;
                    listener.onTap(frozenX, frozenY);
                }
                phase = 0;
                dragging = false;
            }
        }

        // ---- scrolling ----
        String label;
        int mode;
        float outX = curX, outY = curY;

        if (phase == 2) {
            float mid = (py[4] + py[8]) * 0.5f;
            float off2 = (mid - pinchAnchorY) / size;
            if (Math.abs(off2) > DRAG_DEAD) dragging = true;
            if (dragging) {
                pinchAnchorY = runScroll(off2, DRAG_DEAD, mid, pinchAnchorY, size, now, frozenX);
            }
            mode = dragging ? MODE_DRAG : MODE_PINCHED;
            label = dragging ? "PINCH-DRAG" : "PINCH";
            outX = frozenX;
            outY = frozenY;
        } else if (phase == 1) {
            mode = MODE_ARMED;
            label = "ARMED";
            outX = frozenX;
            outY = frozenY;
        } else if (scrollActive) {
            float avg = (py[8] + py[12]) * 0.5f;
            float off1 = (avg - scrollAnchorY) / size;
            scrollAnchorY = runScroll(off1, SCROLL_DEAD, avg, scrollAnchorY, size, now, curX);
            mode = MODE_SCROLL;
            label = "SCROLL";
        } else {
            mode = MODE_IDLE;
            label = "MOVE";
        }

        listener.onCursor(outX, outY, mode);
        return String.format("%s p%.2f %s%s%s%s", label, idxPinch,
                idxUp ? "I" : "-", midUp ? "M" : "-", ringUp ? "R" : "-", pinkyUp ? "P" : "-");
    }

    /**
     * Joystick scroll. offset is in hand sizes relative to anchor.
     * Returns the (possibly drifted) anchor.
     */
    private float runScroll(float offset, float dead, float current, float anchor,
                            float size, long now, float x) {
        float a = Math.abs(offset);
        if (a < dead) {
            return anchor + (current - anchor) * 0.05f; // follow slow drift
        }
        float m = Math.min(1f, (a - dead) / (SCROLL_FULL - dead));
        if (now >= nextScrollAt) {
            int dir = offset < 0 ? 1 : -1; // hand up -> swipe up
            float dist = screenH * (0.08f + 0.22f * m) * scrollSpeed;
            dist = Math.min(dist, screenH * 0.55f);
            long dur = (long) Math.max(160, Math.min(320, 140 + dist * 0.35f));
            nextScrollAt = now + dur + 40 + (long) ((1f - m) * 120);
            float sx = Math.max(screenW * 0.15f, Math.min(screenW * 0.85f, x));
            listener.onScroll(sx, dir, dist, dur);
        }
        return anchor;
    }

    // ---- helpers ----
    private float dist(int a, int b) {
        float dx = px[a] - px[b], dy = py[a] - py[b];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** distance wrist->tip divided by wrist->pip (rotation invariant "finger is straight"). */
    private float ext(int tip, int pip) {
        float d = dist(0, pip);
        return d < 1e-3f ? 0f : dist(0, tip) / d;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void pushHistory(long t, float x, float y) {
        ht[hPos] = t;
        hx[hPos] = x;
        hy[hPos] = y;
        hPos = (hPos + 1) % H;
        if (hCount < H) hCount++;
    }

    private float[] lookback(long t, float defX, float defY) {
        float[] r = {defX, defY};
        long bestT = Long.MIN_VALUE;
        long oldestT = Long.MAX_VALUE;
        float ox = defX, oy = defY;
        for (int i = 0; i < hCount; i++) {
            int idx = ((hPos - 1 - i) % H + H) % H;
            if (ht[idx] < oldestT) {
                oldestT = ht[idx];
                ox = hx[idx];
                oy = hy[idx];
            }
            if (ht[idx] <= t && ht[idx] > bestT) {
                bestT = ht[idx];
                r[0] = hx[idx];
                r[1] = hy[idx];
            }
        }
        if (bestT == Long.MIN_VALUE) { // nothing old enough: use oldest we have
            r[0] = ox;
            r[1] = oy;
        }
        return r;
    }

    /** One Euro filter (Casiez et al.), good for jittery cursors: smooth when slow, responsive when fast. */
    static class OneEuro {
        float minCutoff = 1.75f, beta = 10f, dCutoff = 1f;
        boolean init = false;
        float xPrev, dxPrev;

        void reset() {
            init = false;
        }

        private float alpha(float cutoff, float dt) {
            float tau = 1f / (2f * (float) Math.PI * cutoff);
            return 1f / (1f + tau / dt);
        }

        float filter(float x, float dt) {
            if (!init) {
                init = true;
                xPrev = x;
                dxPrev = 0;
                return x;
            }
            float dx = (x - xPrev) / dt;
            float ad = alpha(dCutoff, dt);
            dxPrev = ad * dx + (1 - ad) * dxPrev;
            float cutoff = minCutoff + beta * Math.abs(dxPrev);
            float a = alpha(cutoff, dt);
            xPrev = a * x + (1 - a) * xPrev;
            return xPrev;
        }
    }
}
