package com.airpalm.app;

/**
 * AirPalm gesture logic (v0.4.1). Pure Java (no Android classes) so it can be tested on a PC.
 *
 * Gestures
 *   MOVE   : index fingertip moves the cursor (other fingers folded, thumb can rest anywhere)
 *   CLICK  : move thumb AWAY from the middle finger, then TOUCH it again quickly (a "tap")
 *            -> click happens at the place the cursor was just before the touch
 *   BACK   : touch thumb + middle finger and HOLD ~0.9 s (cursor turns red, then yellow)
 *   SCROLL : two fingers up (index + middle). The page follows your hand like a finger on the
 *            screen: hand up = page up, hand stops = page stops, drop the pose = release.
 *
 * Why "away, then touch": many people rest the thumb on the folded middle finger while pointing.
 * A click is only possible after the thumb was clearly open, so resting never clicks.
 */
public class GestureEngine {

    public interface Listener {
        /** Cursor position in screen pixels and current mode (MODE_*). x < 0 means hand lost. */
        void onCursor(float x, float y, int mode);
        void onTap(float x, float y);
        /** Virtual finger touches the screen here (scroll starts). */
        void onDragStart(float x, float y);
        /** Virtual finger moves to this y (x stays the same). */
        void onDragMove(float y);
        /** Virtual finger lifts (without fling). */
        void onDragEnd();
        void onBack();
    }

    public static final int MODE_IDLE = 0;    // green
    public static final int MODE_ARMED = 1;   // yellow: keep holding for Back
    public static final int MODE_PINCHED = 2; // red: thumb touching
    public static final int MODE_SCROLL = 3;  // blue
    public static final int MODE_DRAG = 4;
    public static final int MODE_LOST = 5;

    // ---- tunable (set through configure) ----
    private float smooth = 0.5f;
    private float touchSens = 1f;
    private float scrollSpeed = 1f;

    // ---- fixed tuning ----
    private static final float ZONE_X0 = 0.18f, ZONE_X1 = 0.82f;
    private static final float ZONE_Y0 = 0.10f;
    private static final float FINGER_UP = 1.15f;        // tip/pip distance from wrist
    private static final float FINGER_UP_STRICT = 1.30f; // ring + pinky must be below this
    private static final long LOOKBACK_MS = 150;
    private static final long LOST_GRACE_MS = 300;
    private static final long CLICK_MAX_MS = 500;
    private static final long BACK_HOLD_MS = 900;
    private static final float SCROLL_GAIN = 0.45f;      // screen heights per hand size at speed 1.0
    private static final float DRAG_LIMIT = 0.38f;       // virtual finger max distance from centre (screen heights)

    private final Listener listener;
    private final float screenW, screenH;

    private final OneEuro fx = new OneEuro();
    private final OneEuro fy = new OneEuro();
    private final OneEuro fs = new OneEuro(); // scroll hand position

    // history of filtered cursor positions
    private static final int H = 16;
    private final long[] ht = new long[H];
    private final float[] hx = new float[H];
    private final float[] hy = new float[H];
    private int hCount = 0, hPos = 0;

    private long lastT = 0;
    private long lastSeen = 0;
    private float smSize = 0;

    // thumb + middle (click / back)
    private int openFrames = 0;
    private boolean armed = false;
    private boolean touching = false;
    private boolean backDone = false;
    private long touchAt = 0;
    private float touchX, touchY;
    private long lastClick = 0;
    private long noClickUntil = 0;
    private long flashUntil = 0;

    // two finger scroll
    private int scrollCount = 0;
    private boolean scrollActive = false;
    private float scrollAnchor;     // px (frame)
    private float scrollSize;       // hand size in px at start
    private float dragX;
    private float lastVy;

    // landmarks in pixels
    private final float[] px = new float[21];
    private final float[] py = new float[21];

    public GestureEngine(float screenW, float screenH, Listener l) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.listener = l;
        configure(50, 43, 33);
    }

    /** Slider values 0..100. */
    public void configure(int smoothP, int touchP, int scrollP) {
        smooth = clamp01(smoothP / 100f);
        touchSens = 0.7f + clamp01(touchP / 100f) * 0.7f;
        scrollSpeed = 0.5f + clamp01(scrollP / 100f) * 1.5f;
        fx.minCutoff = 3.0f - 2.5f * smooth;
        fy.minCutoff = fx.minCutoff;
        fs.minCutoff = 2.5f;
        fs.beta = 6f;
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
        if (scrollActive) listener.onDragEnd();
        scrollActive = false;
        scrollCount = 0;
        touching = false;
        armed = false;
        openFrames = 0;
        backDone = false;
        hCount = 0;
        hPos = 0;
        lastT = 0;
        smSize = 0;
        fx.reset();
        fy.reset();
        fs.reset();
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
        smSize = smSize == 0 ? size : smSize * 0.9f + size * 0.1f;

        float tm = dist(4, 12) / size; // thumb tip <-> middle tip, in hand sizes
        boolean idxUp = ext(8, 6) > FINGER_UP;
        boolean midUp = ext(12, 10) > FINGER_UP;
        boolean ringUp = ext(16, 14) > FINGER_UP_STRICT;
        boolean pinkyUp = ext(20, 18) > FINGER_UP_STRICT;

        float s = touchSens;
        float on = 0.28f * s, off = 0.40f * s;
        float open = Math.max(0.65f, off + 0.15f);

        // ---- cursor (index tip -> reachable zone -> One-Euro filter) ----
        // Lowest useful fingertip position depends on hand size: the wrist must stay in the frame.
        float yBottom = clamp(0.97f - 2.05f * smSize / fh, 0.30f, 0.78f);
        float u = clamp01((lx[8] - ZONE_X0) / (ZONE_X1 - ZONE_X0));
        float v = clamp01((ly[8] - ZONE_Y0) / (yBottom - ZONE_Y0));
        float curX = fx.filter(u, dt) * screenW;
        float curY = fy.filter(v, dt) * screenH;
        pushHistory(now, curX, curY);

        // smoothed vertical position of the two fingertips (always running so it is warm)
        float avgYn = (ly[8] + ly[12]) * 0.5f;
        float handY = fs.filter(avgYn, dt) * fh;

        // ---- two-finger scroll pose with tolerance ----
        boolean pose = idxUp && midUp && !ringUp && !pinkyUp && !touching;
        if (pose) {
            scrollCount = Math.min(5, scrollCount + 1);
        } else {
            scrollCount = Math.max(0, scrollCount - 1);
        }
        if (!scrollActive && scrollCount >= 2) {
            scrollActive = true;
            scrollAnchor = handY;
            scrollSize = size;
            lastVy = 0;
            dragX = Math.max(screenW * 0.15f, Math.min(screenW * 0.85f, curX));
            noClickUntil = now + 400;
            armed = false;
            openFrames = 0;
            listener.onDragStart(dragX, screenH * 0.5f);
        }
        if (scrollActive && scrollCount == 0) {
            scrollActive = false;
            noClickUntil = now + 400;
            listener.onDragEnd();
        }
        if (scrollActive && pose) {
            // page follows the hand (1 hand size = SCROLL_GAIN screen heights)
            float vy = (handY - scrollAnchor) / scrollSize * screenH * SCROLL_GAIN * scrollSpeed;
            float limit = screenH * DRAG_LIMIT;
            if (Math.abs(vy) > limit) {
                // virtual finger reached the screen edge: lift, put it back in the middle, continue
                listener.onDragEnd();
                scrollAnchor = handY;
                lastVy = 0;
                vy = 0;
                listener.onDragStart(dragX, screenH * 0.5f);
            } else if (Math.abs(vy - lastVy) >= 3f) {
                lastVy = vy;
                listener.onDragMove(screenH * 0.5f + vy);
            }
        }

        // ---- thumb + middle: click (tap) / back (hold) ----
        String label;
        boolean cursorPose = idxUp && !scrollActive && now >= noClickUntil;
        if (!touching) {
            if (tm > open) {
                openFrames = Math.min(openFrames + 1, 10);
            } else if (tm < off && !armed) {
                openFrames = 0; // resting on the finger: needs to open again first
            }
            if (openFrames >= 2) armed = true;
            if (!cursorPose) {
                armed = false;
                openFrames = 0;
            }
            if (armed && tm < on && cursorPose) {
                touching = true;
                backDone = false;
                armed = false;
                openFrames = 0;
                touchAt = now;
                float[] p = lookback(now - LOOKBACK_MS, curX, curY);
                touchX = p[0];
                touchY = p[1];
            }
        } else {
            long held = now - touchAt;
            if (tm > off) {
                if (held < CLICK_MAX_MS && now - lastClick > 250) {
                    lastClick = now;
                    flashUntil = now + 180;
                    listener.onTap(touchX, touchY);
                }
                touching = false;
                armed = false;
                openFrames = 0;
            } else if (!backDone && held >= BACK_HOLD_MS) {
                backDone = true;
                listener.onBack();
            }
        }

        // ---- output ----
        int mode;
        float outX = curX, outY = curY;
        if (touching) {
            long held = now - touchAt;
            mode = held >= CLICK_MAX_MS ? MODE_ARMED : MODE_PINCHED;
            outX = touchX;
            outY = touchY;
            label = backDone ? "BACK" : (held >= CLICK_MAX_MS ? "HOLD..." : "TOUCH");
        } else if (scrollActive) {
            mode = MODE_SCROLL;
            label = "SCROLL";
        } else if (now < flashUntil) {
            mode = MODE_PINCHED;
            label = "CLICK";
        } else {
            mode = MODE_IDLE;
            label = armed ? "MOVE (ready)" : "MOVE";
        }
        listener.onCursor(outX, outY, mode);
        return String.format("%s t%.2f %s%s%s%s", label, tm,
                idxUp ? "I" : "-", midUp ? "M" : "-", ringUp ? "R" : "-", pinkyUp ? "P" : "-");
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

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
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

    /** One Euro filter (Casiez et al.): smooth when slow, responsive when fast. */
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
