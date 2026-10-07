package com.airpalm.app;

/**
 * AirPalm gesture logic (v0.5). Pure Java (no Android classes) so it can be tested on a PC.
 *
 * Gestures
 *   MOVE  : index fingertip moves the cursor (other fingers folded, thumb can rest anywhere)
 *   CLICK : move thumb AWAY from the middle finger, then TOUCH it again quickly (a "tap")
 *           -> click happens at the place the cursor was just before the touch
 *   BACK  : touch thumb + middle finger and HOLD ~0.9 s (cursor turns red, then yellow)
 *   SWIPE : two fingers up (index + middle) = swipe mode (cursor turns blue),
 *           then a quick FLICK of the hand up / down / left / right sends one fixed swipe.
 *
 * Swipe safety: the flick must be fast (slow movement and the hand coming back are ignored),
 * the pose must be stable first, and after each swipe there is a lock-out until the hand calms down.
 */
public class GestureEngine {

    public interface Listener {
        /** Cursor position in screen pixels and current mode (MODE_*). x < 0 means hand lost. */
        void onCursor(float x, float y, int mode);
        void onTap(float x, float y);
        /** One fixed swipe: finger goes from (x0,y0) to (x1,y1) in durationMs. */
        void onSwipe(float x0, float y0, float x1, float y1, long durationMs);
        void onBack();
    }

    public static final int MODE_IDLE = 0;    // green
    public static final int MODE_ARMED = 1;   // yellow: keep holding for Back
    public static final int MODE_PINCHED = 2; // red: thumb touching
    public static final int MODE_SCROLL = 3;  // blue: swipe mode
    public static final int MODE_DRAG = 4;
    public static final int MODE_LOST = 5;

    public static final int DIR_UP = 0, DIR_DOWN = 1, DIR_LEFT = 2, DIR_RIGHT = 3;

    // ---- tunable (set through configure) ----
    private float smooth = 0.5f;
    private float touchSens = 1f;
    private float swipeThr = 0.475f;   // hand sizes within 250 ms
    private float lenV = 0.33f, lenH = 0.55f; // swipe length as fraction of screen height / width

    // ---- fixed tuning ----
    private static final float ZONE_X0 = 0.18f, ZONE_X1 = 0.82f;
    private static final float ZONE_Y0 = 0.10f;
    private static final float FINGER_UP = 1.15f;        // tip/pip distance from wrist
    private static final float FINGER_UP_STRICT = 1.30f; // ring + pinky must be below this
    private static final long LOOKBACK_MS = 150;
    private static final long LOST_GRACE_MS = 300;
    private static final long CLICK_MAX_MS = 500;
    private static final long BACK_HOLD_MS = 900;
    private static final long FLICK_WINDOW_MS = 250;
    private static final long SWIPE_LOCK_MS = 650;
    private static final float CALM = 0.18f;             // hand sizes in 150 ms = "hand is calm"
    private static final float AXIS_DOMINANCE = 1.3f;

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

    // history of hand position while in swipe mode (pixels)
    private final long[] st = new long[H];
    private final float[] sx = new float[H];
    private final float[] sy = new float[H];
    private int sCount = 0, sPos = 0;

    // stillness lock: when the fingertip is only trembling, the cursor stays exactly put
    private static final int LN = 8;
    private final float[] lfx = new float[LN];
    private final float[] lfy = new float[LN];
    private int lCount = 0, lPos = 0;
    private boolean locked = false;
    private float lockX, lockY, dispX, dispY;
    private boolean dispInit = false;
    private int unlockCount = 0;

    // click approach: cursor freezes while the thumb is closing
    private boolean closing = false;
    private long closeStartAt = 0;
    private float frozenX, frozenY;

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

    // swipe mode
    private int poseCount = 0;
    private boolean swipeMode = false;
    private boolean needSettle = true;
    private long swipeLockUntil = 0;
    private long swipeLabelUntil = 0;
    private String swipeLabel = "";

    // landmarks in pixels
    private final float[] px = new float[21];
    private final float[] py = new float[21];

    public GestureEngine(float screenW, float screenH, Listener l) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.listener = l;
        configure(50, 43, 50, 40);
    }

    /** Slider values 0..100. */
    public void configure(int smoothP, int touchP, int swipeSensP, int swipeLenP) {
        smooth = clamp01(smoothP / 100f);
        touchSens = 0.7f + clamp01(touchP / 100f) * 0.7f;
        swipeThr = 0.65f - 0.35f * clamp01(swipeSensP / 100f);
        float p = clamp01(swipeLenP / 100f);
        lenV = 0.15f + 0.45f * p;
        lenH = 0.35f + 0.50f * p;
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
        swipeMode = false;
        poseCount = 0;
        needSettle = true;
        touching = false;
        armed = false;
        openFrames = 0;
        backDone = false;
        hCount = 0;
        hPos = 0;
        sCount = 0;
        sPos = 0;
        lastT = 0;
        smSize = 0;
        locked = false;
        unlockCount = 0;
        lCount = 0;
        lPos = 0;
        dispInit = false;
        closing = false;
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
        float fu = fx.filter(u, dt);
        float fv = fy.filter(v, dt);
        float filtX = fu * screenW;
        float filtY = fv * screenH;
        if (!dispInit) {
            dispInit = true;
            dispX = filtX;
            dispY = filtY;
        }
        // Stillness lock (kills hand tremor). Done in camera pixels, not screen pixels, because the
        // screen is much more sensitive vertically than horizontally.
        float camX = fu * (ZONE_X1 - ZONE_X0) * fw;
        float camY = fv * (yBottom - ZONE_Y0) * fh;
        float lockR = (0.03f + 0.09f * smooth) * smSize; // hand sizes -> pixels
        lfx[lPos] = camX;
        lfy[lPos] = camY;
        lPos = (lPos + 1) % LN;
        if (lCount < LN) lCount++;
        if (locked) {
            // leave the lock only after the fingertip was clearly away for 2 frames in a row
            if (Math.hypot(camX - lockX, camY - lockY) > lockR * 1.3f) {
                unlockCount++;
                if (unlockCount >= 2) {
                    locked = false;
                    lCount = 0;
                    unlockCount = 0;
                }
            } else {
                unlockCount = 0;
            }
        } else if (lCount >= LN) {
            float mx = 0, my = 0;
            for (int i = 0; i < LN; i++) {
                mx += lfx[i];
                my += lfy[i];
            }
            mx /= LN;
            my /= LN;
            float var = 0;
            for (int i = 0; i < LN; i++) {
                float ddx = lfx[i] - mx, ddy = lfy[i] - my;
                var += ddx * ddx + ddy * ddy;
            }
            float std = (float) Math.sqrt(var / LN);
            if (std < lockR * 0.6f) { // only trembling, not moving
                locked = true;
                unlockCount = 0;
                lockX = mx; // reference = where the fingertip really is (average)
                lockY = my;
            }
        }
        if (!locked) {
            float dd = (float) Math.hypot(filtX - dispX, filtY - dispY);
            float k = dd > 120f ? 1f : 0.6f; // ease small corrections, follow big moves directly
            dispX += (filtX - dispX) * k;
            dispY += (filtY - dispY) * k;
        }
        float curX = dispX;
        float curY = dispY;
        pushHistory(now, curX, curY);

        // ---- two-finger pose (swipe mode) with tolerance ----
        boolean pose = idxUp && midUp && !ringUp && !pinkyUp && !touching;
        if (pose) {
            poseCount = Math.min(5, poseCount + 1);
        } else {
            poseCount = Math.max(0, poseCount - 1);
        }
        if (!swipeMode && poseCount >= 2) {
            swipeMode = true;
            sCount = 0;
            sPos = 0;
            needSettle = true;
            noClickUntil = now + 400;
            armed = false;
            openFrames = 0;
        }
        if (swipeMode && poseCount == 0) {
            swipeMode = false;
            noClickUntil = now + 400;
        }
        if (swipeMode) {
            float hxp = (px[8] + px[12]) * 0.5f;
            float hyp = (py[8] + py[12]) * 0.5f;
            pushSwipe(now, hxp, hyp);
            if (poseCount >= 3) detectSwipe(now, size); // tolerate flicker while the hand moves fast
        }

        // ---- thumb + middle: click (tap) / back (hold) ----
        String label;
        boolean cursorPose = idxUp && !swipeMode && now >= noClickUntil;
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
                closing = false;
            }
            // thumb starts closing: freeze the cursor where it was just before (index tends to move with the thumb)
            if (armed && !closing && tm < open) {
                closing = true;
                closeStartAt = now;
                float[] q = lookback(now - 120, curX, curY);
                frozenX = q[0];
                frozenY = q[1];
            } else if (closing && tm >= open) {
                closing = false; // thumb opened again, nothing happened
            }
            if (closing && now - closeStartAt > 800) { // thumb resting half way: give the cursor back
                closing = false;
                armed = false;
                openFrames = 0;
            }
            if (armed && tm < on && cursorPose) {
                touching = true;
                backDone = false;
                armed = false;
                openFrames = 0;
                touchAt = now;
                if (closing) {
                    touchX = frozenX;
                    touchY = frozenY;
                } else {
                    float[] q = lookback(now - LOOKBACK_MS, curX, curY);
                    touchX = q[0];
                    touchY = q[1];
                }
                closing = false;
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
        } else if (closing) {
            outX = frozenX;
            outY = frozenY;
            mode = MODE_IDLE;
            label = "CLICK...";
        } else if (swipeMode) {
            mode = MODE_SCROLL;
            if (now < swipeLabelUntil) {
                label = swipeLabel;
            } else if (poseCount >= 3 && !needSettle && now >= swipeLockUntil) {
                label = "SWIPE READY";
            } else {
                label = "SWIPE MODE";
            }
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

    // ---- swipe detection ----
    private void detectSwipe(long now, float size) {
        if (needSettle) {
            float[] d = disp(now, 150, size);
            if (d[2] >= 100 && Math.hypot(d[0], d[1]) < CALM) needSettle = false;
            return;
        }
        if (now < swipeLockUntil) return;

        float[] d = disp(now, FLICK_WINDOW_MS, size);
        if (d[2] < 90) return;
        float ax = Math.abs(d[0]), ay = Math.abs(d[1]);
        float mag = (float) Math.hypot(ax, ay);
        if (mag < swipeThr) return;
        if (Math.max(ax, ay) < AXIS_DOMINANCE * Math.min(ax, ay)) return; // diagonal: ambiguous

        int dir;
        if (ay > ax) dir = d[1] < 0 ? DIR_UP : DIR_DOWN;
        else dir = d[0] < 0 ? DIR_LEFT : DIR_RIGHT;
        fireSwipe(dir, now);
    }

    private void fireSwipe(int dir, long now) {
        float cx = screenW * 0.5f, cy = screenH * 0.5f;
        float x0 = cx, y0 = cy, x1 = cx, y1 = cy, len;
        String name;
        switch (dir) {
            case DIR_UP:
                len = screenH * lenV;
                y0 = cy + len / 2f; y1 = cy - len / 2f; name = "SWIPE UP";
                break;
            case DIR_DOWN:
                len = screenH * lenV;
                y0 = cy - len / 2f; y1 = cy + len / 2f; name = "SWIPE DOWN";
                break;
            case DIR_LEFT:
                len = screenW * lenH;
                x0 = cx + len / 2f; x1 = cx - len / 2f; name = "SWIPE LEFT";
                break;
            default:
                len = screenW * lenH;
                x0 = cx - len / 2f; x1 = cx + len / 2f; name = "SWIPE RIGHT";
                break;
        }
        long dur = (long) clamp(len * 0.9f, 250f, 600f);
        swipeLockUntil = now + SWIPE_LOCK_MS;
        needSettle = true;
        swipeLabel = name;
        swipeLabelUntil = now + 500;
        listener.onSwipe(x0, y0, x1, y1, dur);
    }

    /** Hand displacement over the last windowMs, in hand sizes: {dx, dy, spanMs}. */
    private float[] disp(long now, long windowMs, float size) {
        float[] r = {0, 0, 0};
        if (sCount == 0) return r;
        int latest = (sPos - 1 + H) % H;
        long bestAge = -1;
        int best = latest;
        for (int i = 0; i < sCount; i++) {
            int idx = ((sPos - 1 - i) % H + H) % H;
            long age = now - st[idx];
            if (age <= windowMs && age > bestAge) {
                bestAge = age;
                best = idx;
            }
        }
        r[0] = (sx[latest] - sx[best]) / size;
        r[1] = (sy[latest] - sy[best]) / size;
        r[2] = bestAge;
        return r;
    }

    private void pushSwipe(long t, float x, float y) {
        st[sPos] = t;
        sx[sPos] = x;
        sy[sPos] = y;
        sPos = (sPos + 1) % H;
        if (sCount < H) sCount++;
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
