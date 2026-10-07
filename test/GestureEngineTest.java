package com.airpalm.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Run: see test/run.sh. Simulates hands with noise/tilt/dropouts and checks the events. */
public class GestureEngineTest {
    static final int FW = 480, FH = 640;      // upright frame, like the app after rotation
    static final float SW = 1080, SH = 2400;  // screen
    static final Random rnd = new Random(7);

    // events
    static List<String> ev = new ArrayList<>();
    static float lastCurX, lastCurY;
    static int scrollsUp, scrollsDown, taps, backs;
    static float tapX, tapY;

    static GestureEngine.Listener L = new GestureEngine.Listener() {
        public void onCursor(float x, float y, int mode) { lastCurX = x; lastCurY = y; }
        public void onTap(float x, float y) { taps++; tapX = x; tapY = y; ev.add("TAP"); }
        public void onScroll(float x, int d, float dist, long dur) {
            if (d > 0) scrollsUp++; else scrollsDown++;
            ev.add("SCROLL" + (d > 0 ? "^" : "v"));
        }
        public void onBack() { backs++; ev.add("BACK"); }
    };

    static void clear() { ev.clear(); scrollsUp = scrollsDown = taps = backs = 0; }

    // Hand pose description (local frame, units = hand size, y up is negative)
    static class Pose {
        boolean idx = true, mid = true, ring = false, pinky = false;
        float pinchIdx = -1;   // >=0: thumb tip distance to index tip (hand sizes)
        float pinchMid = -1;
        float cx = 0.5f, cy = 0.65f; // wrist position normalized
        float angleDeg = 0;
        float scalePx = 130;
        float noisePx = 1.5f;
    }

    static final float[] MCPX = {-0.35f, 0f, 0.32f, 0.58f};
    static final float[] MCPY = {-0.95f, -1.0f, -0.93f, -0.8f};
    static final float[][] SEG = {{0.45f, 0.30f, 0.25f}, {0.50f, 0.33f, 0.27f},
            {0.45f, 0.30f, 0.25f}, {0.35f, 0.25f, 0.20f}};

    static float[][] build(Pose p) {
        float[] lx = new float[21], ly = new float[21];
        float[][] loc = new float[21][2];
        loc[0] = new float[]{0, 0};
        boolean[] up = {p.idx, p.mid, p.ring, p.pinky};
        for (int f = 0; f < 4; f++) {
            int base = 5 + f * 4;
            float mx = MCPX[f], my = MCPY[f];
            loc[base] = new float[]{mx, my};
            if (up[f]) {
                loc[base + 1] = new float[]{mx, my - SEG[f][0]};
                loc[base + 2] = new float[]{mx, my - SEG[f][0] - SEG[f][1]};
                loc[base + 3] = new float[]{mx, my - SEG[f][0] - SEG[f][1] - SEG[f][2]};
            } else {
                loc[base + 1] = new float[]{mx, my - SEG[f][0] * 0.8f};
                loc[base + 2] = new float[]{mx, my - SEG[f][0] * 0.8f + SEG[f][1] * 0.6f};
                loc[base + 3] = new float[]{mx, my - SEG[f][0] * 0.8f + SEG[f][1] * 0.6f + 0.05f};
            }
        }
        // thumb
        loc[1] = new float[]{-0.25f, -0.2f};
        loc[2] = new float[]{-0.45f, -0.4f};
        loc[3] = new float[]{-0.58f, -0.55f};
        loc[4] = new float[]{-0.7f, -0.7f};
        if (p.pinchIdx >= 0) {
            float[] t = loc[8];
            loc[4] = new float[]{t[0] - p.pinchIdx * 0.7f, t[1] + p.pinchIdx * 0.7f};
        }
        if (p.pinchMid >= 0) {
            float[] t = loc[12];
            loc[4] = new float[]{t[0] - p.pinchMid * 0.7f, t[1] + p.pinchMid * 0.7f};
        }
        double a = Math.toRadians(p.angleDeg);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        for (int i = 0; i < 21; i++) {
            float x = loc[i][0], y = loc[i][1];
            float rx = x * cos - y * sin, ry = x * sin + y * cos;
            float pxl = p.cx * FW + rx * p.scalePx + (float) rnd.nextGaussian() * p.noisePx;
            float pyl = p.cy * FH + ry * p.scalePx + (float) rnd.nextGaussian() * p.noisePx;
            lx[i] = pxl / FW;
            ly[i] = pyl / FH;
        }
        return new float[][]{lx, ly};
    }

    static long t = 1000;

    static void frames(GestureEngine e, Pose p, int n) {
        for (int i = 0; i < n; i++) {
            float[][] lm = build(p);
            e.update(lm[0], lm[1], FW, FH, t);
            t += 45;
        }
    }

    static int fail = 0;

    static void check(String name, boolean ok, String info) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "   " + info);
        if (!ok) fail++;
    }

    public static void main(String[] a) {
        // A: two-finger scroll, tilted hands, noise, random bad frames
        for (int angle : new int[]{0, 25, -30}) {
            clear();
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.angleDeg = angle;
            frames(e, p, 8);                       // settle in pose
            // move hand UP by ~0.9 hand sizes over 25 frames, with 12% bad frames (ring finger pops up)
            for (int i = 0; i < 25; i++) {
                p.cy = 0.65f - (0.9f * 130f / FH) * (i / 24f);
                p.ring = rnd.nextFloat() < 0.12f;
                frames(e, p, 1);
            }
            p.ring = false;
            p.cy = 0.65f - 0.9f * 130f / FH;
            frames(e, p, 30);                      // hold up -> continuous scroll
            check("A scroll-up @" + angle + "deg", scrollsUp >= 4 && scrollsDown == 0 && taps == 0,
                    "up=" + scrollsUp + " down=" + scrollsDown + " taps=" + taps);
        }

        // A2: scroll down
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            frames(e, p, 8);
            p.cy = 0.65f + 1.0f * 130f / FH;
            frames(e, p, 30);
            check("A2 scroll-down", scrollsDown >= 3 && scrollsUp == 0, "up=" + scrollsUp + " down=" + scrollsDown);
        }

        // B: holding two fingers still (noise only) must NOT scroll
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            frames(e, p, 80);
            check("B still two-finger = no scroll", scrollsUp + scrollsDown == 0 && taps == 0,
                    "scrolls=" + (scrollsUp + scrollsDown) + " taps=" + taps);
        }

        // B2: slow drift must not scroll
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            frames(e, p, 8);
            for (int i = 0; i < 80; i++) {
                p.cy -= 0.0015f; // ~0.1 hand sizes per 14 frames
                frames(e, p, 1);
            }
            check("B2 slow drift = no scroll", scrollsUp + scrollsDown == 0, "scrolls=" + (scrollsUp + scrollsDown));
        }

        // C: open palm (all fingers up) must not scroll
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.ring = true;
            p.pinky = true;
            frames(e, p, 8);
            p.cy -= 0.3f;
            frames(e, p, 30);
            check("C open palm = no scroll", scrollsUp + scrollsDown == 0, "scrolls=" + (scrollsUp + scrollsDown));
        }

        // D: pointing, then pinch while the index tip drifts; tap must land where the cursor WAS
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.mid = false;
            p.cx = 0.5f;
            p.cy = 0.65f;
            frames(e, p, 25);
            float beforeX = lastCurX, beforeY = lastCurY;
            // close pinch over 7 frames; the whole hand also drifts down-left like a real pinch
            for (int i = 0; i < 7; i++) {
                p.pinchIdx = 0.9f - 0.8f * (i / 6f);
                p.cx -= 0.004f;
                p.cy += 0.008f;
                frames(e, p, 1);
            }
            frames(e, p, 5);     // hold closed
            p.pinchIdx = 0.9f;   // release
            frames(e, p, 6);
            float err = (float) Math.hypot(tapX - beforeX, tapY - beforeY);
            check("D tap fires once", taps == 1, "taps=" + taps);
            check("D tap position ~ pre-pinch cursor", err < 90, "error=" + (int) err + "px (screen 1080x2400)");
        }

        // E: pinch + hold + move => scroll, no tap
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.mid = false;
            frames(e, p, 15);
            p.pinchIdx = 0.1f;
            frames(e, p, 6);
            for (int i = 0; i < 20; i++) {
                p.cy -= 0.9f * 130f / FH / 20f;
                frames(e, p, 1);
            }
            frames(e, p, 20);
            p.pinchIdx = 0.9f;
            frames(e, p, 8);
            check("E pinch-drag scrolls up, no tap", scrollsUp >= 3 && taps == 0,
                    "up=" + scrollsUp + " taps=" + taps);
        }

        // F: thumb + middle pinch => exactly one BACK
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.mid = false;
            frames(e, p, 10);
            p.pinchMid = 0.1f;
            frames(e, p, 15);
            p.pinchMid = -1;
            frames(e, p, 5);
            check("F back gesture once", backs == 1 && taps == 0 && scrollsUp + scrollsDown == 0,
                    "backs=" + backs + " taps=" + taps);
        }

        // G: hand lost for a blink (150 ms) must not break scrolling; long loss resets
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            frames(e, p, 8);
            e.handLost(t); t += 45;
            e.handLost(t); t += 45;
            p.cy += 1.0f * 130f / FH;
            frames(e, p, 20);
            check("G short hand loss tolerated", scrollsDown >= 2, "down=" + scrollsDown);
        }

        // H: simple tap with a perfectly still hand
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.mid = false;
            frames(e, p, 20);
            p.pinchIdx = 0.1f;
            frames(e, p, 5);
            p.pinchIdx = 0.8f;
            frames(e, p, 6);
            check("H plain tap", taps == 1 && scrollsUp + scrollsDown == 0, "taps=" + taps);
        }

        // I: resting hand with thumb near index (never closes) should not freeze cursor forever
        clear();
        {
            GestureEngine e = new GestureEngine(SW, SH, L);
            Pose p = new Pose();
            p.mid = false;
            frames(e, p, 10);
            p.pinchIdx = 0.4f;           // between arm and on... 0.4 is above 'on' (0.28) but below arm (0.45)
            frames(e, p, 50);            // 2.2 s
            p.cx += 0.2f;
            frames(e, p, 10);
            check("I resting thumb releases cursor", taps == 0, "taps=" + taps + " cursorX=" + (int) lastCurX);
        }

        System.out.println(fail == 0 ? "\nALL PASSED" : "\nFAILURES: " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
