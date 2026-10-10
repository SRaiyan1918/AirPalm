package com.airpalm.app;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * openWakeWord pipeline in plain Java (port of the project's streaming logic):
 *
 *   16 kHz PCM, 80 ms chunks (1280 samples)
 *     -> melspectrogram model   (1280 new + 480 old samples -> 8 frames x 32 bins, then x/10 + 2)
 *     -> embedding model        (last 76 mel frames -> 96 numbers)
 *     -> wake word model        (last 16 embeddings -> score 0..1)
 *
 * The three neural networks are behind the small {@link Model} interface, so this class has no Android
 * or TensorFlow code and can be unit tested on a PC with fake models.
 */
public class OpenWakeWord {

    public interface Model {
        /** @param shape tensor shape of the input, e.g. {1, 1760}. @return flat output values */
        float[] run(float[] input, int[] shape);
    }

    public static final int CHUNK = 1280;
    public static final int MEL_CONTEXT = 480;
    public static final int MEL_BINS = 32;
    public static final int MEL_WINDOW = 76;
    public static final int EMB_DIM = 96;
    public static final int EMB_WINDOW = 16;
    /** the first chunks are ignored while the buffers fill with real audio (about 2 s) */
    public static final int WARMUP_CHUNKS = 25;

    private final Model mel, emb, wake;
    private final float[] raw = new float[CHUNK + MEL_CONTEXT];
    private final ArrayDeque<float[]> melFrames = new ArrayDeque<>();
    private final ArrayDeque<float[]> embeddings = new ArrayDeque<>();
    private int chunks = 0;
    private float inputScale = 1f;

    public OpenWakeWord(Model mel, Model emb, Model wake) {
        this.mel = mel;
        this.emb = emb;
        this.wake = wake;
        reset();
    }

    /** 1 = feed raw 16-bit sample values (what the models were built for); 1/32768 = normalised -1..1. */
    public void setInputScale(float scale) {
        inputScale = scale;
    }

    public void reset() {
        Arrays.fill(raw, 0f);
        melFrames.clear();
        embeddings.clear();
        for (int i = 0; i < MEL_WINDOW; i++) {
            float[] ones = new float[MEL_BINS];
            Arrays.fill(ones, 1f);
            melFrames.addLast(ones);
        }
        for (int i = 0; i < EMB_WINDOW; i++) embeddings.addLast(new float[EMB_DIM]);
        chunks = 0;
    }

    /**
     * Feeds exactly CHUNK samples and returns the wake word score (0 during warm-up).
     */
    public float process(short[] pcm, int offset) {
        // keep the last 480 old samples in front of the new 1280
        System.arraycopy(raw, CHUNK, raw, 0, MEL_CONTEXT);
        for (int i = 0; i < CHUNK; i++) raw[MEL_CONTEXT + i] = pcm[offset + i] * inputScale;

        float[] m = mel.run(raw, new int[]{1, raw.length});
        int frames = m.length / MEL_BINS;
        for (int f = 0; f < frames; f++) {
            float[] row = new float[MEL_BINS];
            for (int k = 0; k < MEL_BINS; k++) row[k] = m[f * MEL_BINS + k] / 10f + 2f;
            melFrames.addLast(row);
        }
        while (melFrames.size() > 200) melFrames.removeFirst();

        // embedding of the most recent 76 frames
        float[] embIn = new float[MEL_WINDOW * MEL_BINS];
        int skip = melFrames.size() - MEL_WINDOW;
        int idx = 0, n = 0;
        for (float[] row : melFrames) {
            if (n++ < skip) continue;
            System.arraycopy(row, 0, embIn, idx, MEL_BINS);
            idx += MEL_BINS;
        }
        float[] e = emb.run(embIn, new int[]{1, MEL_WINDOW, MEL_BINS, 1});
        float[] vec = new float[EMB_DIM];
        System.arraycopy(e, 0, vec, 0, Math.min(EMB_DIM, e.length));
        embeddings.addLast(vec);
        while (embeddings.size() > 64) embeddings.removeFirst();

        // wake word score from the last 16 embeddings
        float[] wIn = new float[EMB_WINDOW * EMB_DIM];
        skip = embeddings.size() - EMB_WINDOW;
        idx = 0;
        n = 0;
        for (float[] v : embeddings) {
            if (n++ < skip) continue;
            System.arraycopy(v, 0, wIn, idx, EMB_DIM);
            idx += EMB_DIM;
        }
        float[] out = wake.run(wIn, new int[]{1, EMB_WINDOW, EMB_DIM});

        chunks++;
        if (chunks < WARMUP_CHUNKS || out.length == 0) return 0f;
        return Math.max(0f, Math.min(1f, out[0]));
    }

    /**
     * Turns a stream of scores into a single "wake word heard" event: needs a few high scores in a
     * row (avoids one-frame noise) and then stays quiet for a cool-down.
     */
    public static class Trigger {
        private final int needed;
        private final long cooldownMs;
        private int hits = 0;
        private long blockedUntil = 0;

        public Trigger(int needed, long cooldownMs) {
            this.needed = needed;
            this.cooldownMs = cooldownMs;
        }

        public void reset() {
            hits = 0;
        }

        public boolean update(float score, float threshold, long nowMs) {
            if (nowMs < blockedUntil) {
                hits = 0;
                return false;
            }
            if (score >= threshold) hits++; else hits = 0;
            if (hits >= needed) {
                hits = 0;
                blockedUntil = nowMs + cooldownMs;
                return true;
            }
            return false;
        }
    }
}
