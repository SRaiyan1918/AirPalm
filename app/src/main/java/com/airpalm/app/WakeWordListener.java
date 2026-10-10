package com.airpalm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Always-on wake word ("Jarvis") using openWakeWord models.
 *
 * It owns its own AudioRecord (no audio focus is taken, so music and reels keep playing while it waits).
 * When the wake word is heard the listener stops by itself and releases the microphone; the caller starts
 * the command recogniser and calls {@link #start()} again when the command is finished.
 *
 * Model files (copied by the app into its private "wakeword" folder, or bundled in assets/wakeword/):
 *   melspectrogram.tflite, embedding_model.tflite and one wake word model (for example hey_jarvis_v0.1.tflite).
 */
public class WakeWordListener {
    public interface Callback {
        void onWake();
    }

    // shown on the status screen (same process as the app)
    public static volatile String status = "off";
    public static volatile float lastScore = 0f;
    public static volatile float maxScore = 0f;
    public static volatile int rms = 0;
    public static volatile String wakeModelName = "";
    /** shown in the app so you can see which version is installed */
    public static final String BUILD = "wake-v3 (10 Oct)";
    /** short description of the loaded models for the status screen */
    public static volatile String modelInfo = "";

    public static final String MEL = "melspectrogram.tflite";
    public static final String EMB = "embedding_model.tflite";
    private static final int RATE = 16000;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Thread thread;
    private volatile boolean running = false;
    private TfliteModel mel, emb, wake;
    private OpenWakeWord engine;
    private PowerManager.WakeLock lock;
    private boolean modelsLoaded = false;

    public WakeWordListener(Context ctx, SharedPreferences prefs, Callback callback) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.callback = callback;
    }

    // ------------------------------------------------------------------ model files

    public static File modelDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "wakeword");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Name of the wake word model file ("jarvis" in the name wins), or null. */
    public static String findWakeModel(Context ctx) {
        String best = null;
        String[] files = modelDir(ctx).list();
        if (files != null) {
            for (String f : files) {
                if (!f.endsWith(".tflite") || f.equals(MEL) || f.equals(EMB)) continue;
                if (best == null || f.toLowerCase().contains("jarvis")) best = f;
            }
        }
        if (best != null) return best;
        try {
            String[] assets = ctx.getAssets().list("wakeword");
            if (assets != null) {
                for (String f : assets) {
                    if (!f.endsWith(".tflite") || f.equals(MEL) || f.equals(EMB)) continue;
                    if (best == null || f.toLowerCase().contains("jarvis")) best = f;
                }
            }
        } catch (IOException ignored) {
        }
        return best;
    }

    private static byte[] readModel(Context ctx, String name) throws IOException {
        File f = new File(modelDir(ctx), name);
        InputStream is;
        if (f.exists()) is = new FileInputStream(f);
        else is = ctx.getAssets().open("wakeword/" + name);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[16384];
            int n;
            while ((n = is.read(tmp)) > 0) bos.write(tmp, 0, n);
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        bb.put(bytes);
        bb.rewind();
        return bb;
    }

    /** Which of the 3 files are missing, e.g. "melspectrogram.tflite, wake word model" or "" if all present. */
    public static String missingFiles(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (!exists(ctx, MEL)) sb.append(MEL);
        if (!exists(ctx, EMB)) sb.append(sb.length() > 0 ? ", " : "").append(EMB);
        if (findWakeModel(ctx) == null) sb.append(sb.length() > 0 ? ", " : "").append("a wake word model (e.g. hey_jarvis_v0.1.tflite)");
        return sb.toString();
    }

    private static boolean exists(Context ctx, String name) {
        if (new File(modelDir(ctx), name).exists()) return true;
        try {
            ctx.getAssets().open("wakeword/" + name).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ control

    /** Loads the models (first time) and starts listening. Safe to call repeatedly. */
    public synchronized void start() {
        if (running) return;
        String missing = missingFiles(ctx);
        if (!missing.isEmpty()) {
            status = "models missing: " + missing;
            return;
        }
        if (!loadModels()) return;
        applyConfig();
        acquireLock();
        running = true;
        status = "starting";
        thread = new Thread(this::loop, "airpalm-wakeword");
        thread.start();
    }

    /** Stops listening and frees the microphone (blocks very briefly). */
    public synchronized void pause() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        if (!status.startsWith("models") && !status.startsWith("error")) status = "paused";
    }

    /** Applies sensitivity / input scale changes without restarting. */
    public synchronized void refreshConfig() {
        applyConfig();
    }

    /** Reloads model files and settings (after the user picked new files). */
    public synchronized void reload() {
        pause();
        closeModels();
        start();
    }

    public synchronized void destroy() {
        pause();
        closeModels();
        releaseLock();
        status = "off";
    }

    private boolean loadModels() {
        if (modelsLoaded) return true;
        String which = "melspectrogram";
        try {
            // The melspectrogram file is stored with a 1-sample input; give it the real size (1 x 1760)
            // before TensorFlow Lite prepares it, otherwise opening it fails.
            byte[] melBytes = readModel(ctx, MEL);
            boolean patched = TfliteShapePatch.patchInputShape(melBytes, 0, new int[]{1, OpenWakeWord.CHUNK + OpenWakeWord.MEL_CONTEXT});
            mel = new TfliteModel(direct(melBytes), false);

            which = "embedding";
            emb = new TfliteModel(direct(readModel(ctx, EMB)), true);

            which = "wake word (" + findWakeModel(ctx) + ")";
            wakeModelName = findWakeModel(ctx);
            wake = new TfliteModel(direct(readModel(ctx, wakeModelName)), true);

            modelInfo = "mel " + (patched ? "patched " : "") + mel.describe() + "\nembedding " + emb.describe()
                    + "\nwake " + wake.describe();
            engine = new OpenWakeWord(mel, emb, wake);
            modelsLoaded = true;
            return true;
        } catch (Throwable t) {
            status = "error loading " + which + ": " + t.getMessage();
            closeModels();
            return false;
        }
    }

    private void closeModels() {
        if (mel != null) mel.close();
        if (emb != null) emb.close();
        if (wake != null) wake.close();
        mel = emb = wake = null;
        engine = null;
        modelsLoaded = false;
    }

    private volatile float threshold = 0.5f;

    private void applyConfig() {
        int sens = prefs.getInt("wake_sens", 50);
        threshold = 0.75f - 0.45f * (sens / 100f);
        if (engine != null) {
            engine.setInputScale(prefs.getBoolean("wake_norm", false) ? 1f / 32768f : 1f);
        }
    }

    // ------------------------------------------------------------------ audio loop

    private void loop() {
        boolean fired = false;
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(min, OpenWakeWord.CHUNK * 2 * 4);
            OpenWakeWord.Trigger trigger = new OpenWakeWord.Trigger(2, 3000);
            short[] buf = new short[OpenWakeWord.CHUNK];

            while (running && rec == null) {
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                } else {
                    r.release();
                    status = "microphone busy, retrying";
                    SystemClock.sleep(2000);
                }
            }
            if (rec == null) return;
            rec.startRecording();
            engine.reset();
            maxScore = 0f;
            status = "listening";

            int filled = 0;
            while (running) {
                int n = rec.read(buf, filled, buf.length - filled);
                if (n < 0) {
                    status = "error: microphone read " + n;
                    break;
                }
                filled += n;
                if (filled < buf.length) continue;
                filled = 0;

                long sum = 0;
                for (short v : buf) sum += (long) v * v;
                rms = (int) Math.sqrt(sum / (double) buf.length);

                float score = engine.process(buf, 0);
                lastScore = score;
                if (score > maxScore) maxScore = score;
                if (trigger.update(score, threshold, SystemClock.uptimeMillis())) {
                    fired = true;
                    break;
                }
            }
        } catch (Throwable t) {
            status = "error: " + t;
        } finally {
            running = false;
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
        }
        if (fired) {
            status = "wake word heard";
            main.post(callback::onWake);
        }
    }

    // ------------------------------------------------------------------ wake lock (keeps listening with the screen off)

    private void acquireLock() {
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airpalm:wakeword");
            }
            if (lock != null && !lock.isHeld()) lock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLock() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }
}
             try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
        }
        if (fired) {
            status = "wake word heard";
            main.post(callback::onWake);
        }
    }

    // ------------------------------------------------------------------ wake lock (keeps listening with the screen off)

    private void acquireLock() {
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airpalm:wakeword");
            }
            if (lock != null && !lock.isHeld()) lock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLock() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }
}
