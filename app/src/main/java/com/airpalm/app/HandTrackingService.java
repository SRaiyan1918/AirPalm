package com.airpalm.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Size;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HandTrackingService extends LifecycleService {
    public static volatile boolean isRunning = false;

    private static final String CHANNEL_ID = "airpalm_tracking";
    private static final int NOTIFICATION_ID = 1918;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private HandLandmarker handLandmarker;

    private WindowManager windowManager;
    private CursorView cursorView;
    private DebugView debugView;
    private WindowManager.LayoutParams cursorParams;
    private WindowManager.LayoutParams debugParams;
    private int screenWidth;
    private int screenHeight;
    private int cursorSize;

    private long lastProcessedFrame = 0;
    private GestureEngine engine;
    private boolean showPreview = true;
    private VoiceController voice;
    private android.content.SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createChannel();
        prefs = getSharedPreferences("airpalm", MODE_PRIVATE);
        boolean voiceOn = prefs.getBoolean("voice", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (voiceOn) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, buildNotification(), type);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        if (voiceOn) voice = new VoiceController(this, prefs);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            setupHandLandmarker();
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
            return;
        }

        showPreview = prefs.getBoolean("preview", true);

        setupOverlay();
        initEngine();
        startCamera();
    }

    private void initEngine() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = screenWidth > 0 ? screenWidth : dm.widthPixels;
        int h = screenHeight > 0 ? screenHeight : dm.heightPixels;
        engine = new GestureEngine(w, h, new GestureEngine.Listener() {
            @Override
            public void onCursor(float x, float y, int mode) {
                if (x < 0) {
                    mainHandler.post(() -> {
                        if (cursorView != null) cursorView.setAlpha(0.25f);
                    });
                } else {
                    updateCursor(x, y, mode);
                }
            }

            @Override
            public void onTap(float x, float y) {
                AirPalmAccessibilityService.tap(x, y);
            }

            @Override
            public void onSwipe(float x0, float y0, float x1, float y1, long durationMs) {
                AirPalmAccessibilityService.swipe(x0, y0, x1, y1, durationMs);
            }

            @Override
            public void onBack() {
                AirPalmAccessibilityService.back();
            }

            @Override
            public void onVoiceToggle() {
                if (voice != null) {
                    voice.toggle();
                } else {
                    mainHandler.post(() -> Toast.makeText(HandTrackingService.this,
                            "Voice is off: enable it in the AirPalm app, then STOP and START", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AirPalm hand tracking", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps front-camera hand tracking active.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("AirPalm is active")
                .setContentText("Hand tracking and floating cursor are running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void setupHandLandmarker() {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build();

        HandLandmarker.HandLandmarkerOptions options =
                HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.4f)
                        .setMinTrackingConfidence(0.4f)
                        .build();

        handLandmarker = HandLandmarker.createFromOptions(getApplicationContext(), options);
    }

    private void setupOverlay() {
        if (!Settings.canDrawOverlays(this)) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        cursorSize = dp(34);

        cursorView = new CursorView(this);
        cursorView.setAlpha(0.35f);

        cursorParams = new WindowManager.LayoutParams(
                cursorSize,
                cursorSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.START;
        cursorParams.x = screenWidth / 2;
        cursorParams.y = screenHeight / 2;

        windowManager.addView(cursorView, cursorParams);

        // Small non-touchable camera preview for debugging hand/gesture recognition.
        if (!prefs.getBoolean("preview", true)) return;
        debugView = new DebugView(this);
        debugParams = new WindowManager.LayoutParams(
                dp(230),
                dp(175),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        debugParams.gravity = Gravity.TOP | Gravity.START;
        debugParams.x = dp(10);
        debugParams.y = dp(48);
        windowManager.addView(debugView, debugParams);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        Executor mainExecutor = command -> mainHandler.post(command);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        analysis);
            } catch (Exception e) {
                e.printStackTrace();
                stopSelf();
            }
        }, mainExecutor);
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        long now = SystemClock.uptimeMillis();
        // ~22 fps while a hand is around, ~5 fps when nobody has been there for a few seconds (saves battery)
        long minInterval = (engine == null || engine.isIdle(now)) ? 200 : 45;
        if (now - lastProcessedFrame < minInterval) {
            image.close();
            return;
        }
        lastProcessedFrame = now;

        Bitmap bitmap = null;
        try {
            bitmap = rgbaToBitmap(image);
            int rotation = image.getImageInfo().getRotationDegrees();
            image.close();

            Bitmap oriented = orientFrontCamera(bitmap, rotation);
            if (oriented != bitmap) {
                bitmap.recycle();
            }

            MPImage mpImage = new BitmapImageBuilder(oriented).build();
            HandLandmarkerResult result = handLandmarker.detectForVideo(mpImage, now);
            processResult(result, now, oriented);
            oriented.recycle();
        } catch (Throwable t) {
            try {
                image.close();
            } catch (Exception ignored) {
            }
            t.printStackTrace();
        }
    }

    private Bitmap rgbaToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();

        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + Math.max(0, rowPadding / pixelStride);

        Bitmap padded = Bitmap.createBitmap(
                paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        if (paddedWidth == image.getWidth()) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private Bitmap orientFrontCamera(Bitmap src, int rotationDegrees) {
        Matrix m = new Matrix();
        m.postRotate(rotationDegrees);
        m.postScale(-1f, 1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private void processResult(HandLandmarkerResult result, long now, Bitmap debugFrame) {
        if (engine != null && prefs != null) {
            engine.configure(prefs.getInt("smooth", 50), prefs.getInt("pinch", 43),
                    prefs.getInt("swipe", 50), prefs.getInt("swipelen", 40));
        }

        List<List<NormalizedLandmark>> allHands = result.landmarks();
        if (allHands == null || allHands.isEmpty() || allHands.get(0).size() < 21) {
            String label = engine.handLost(now);
            updateDebug(debugFrame, null, label);
            return;
        }

        List<NormalizedLandmark> hand = allHands.get(0);
        float[] lx = new float[21];
        float[] ly = new float[21];
        for (int i = 0; i < 21; i++) {
            lx[i] = hand.get(i).x();
            ly[i] = hand.get(i).y();
        }
        String label = engine.update(lx, ly, debugFrame.getWidth(), debugFrame.getHeight(), now);
        if (voice != null && voice.isActive()) label = label + " MIC";
        updateDebug(debugFrame, hand, label);
    }

    private void updateDebug(Bitmap source, List<NormalizedLandmark> hand, String state) {
        if (debugView == null || source == null) return;

        final int w = dp(220);
        final int h = dp(165);
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
        mainHandler.post(() -> {
            if (debugView != null) {
                debugView.setFrame(small, hand, state);
            } else {
                small.recycle();
            }
        });
    }

    private void updateCursor(float x, float y, int mode) {
        mainHandler.post(() -> {
            if (windowManager == null || cursorView == null || cursorParams == null) return;
            cursorView.setAlpha(1f);
            cursorView.setMode(mode);
            cursorParams.x = Math.max(-cursorSize / 2,
                    Math.min(screenWidth - cursorSize / 2, (int) x - cursorSize / 2));
            cursorParams.y = Math.max(-cursorSize / 2,
                    Math.min(screenHeight - cursorSize / 2, (int) y - cursorSize / 2));
            try {
                windowManager.updateViewLayout(cursorView, cursorParams);
            } catch (Exception ignored) {
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (voice != null) {
            voice.destroy();
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        if (handLandmarker != null) {
            handLandmarker.close();
        }
        if (windowManager != null) {
            if (cursorView != null) {
                try {
                    windowManager.removeView(cursorView);
                } catch (Exception ignored) {
                }
            }
            if (debugView != null) {
                try {
                    windowManager.removeView(debugView);
                } catch (Exception ignored) {
                }
            }
        }

        super.onDestroy();
    }


    private static class DebugView extends View {
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap frame;
        private List<NormalizedLandmark> landmarks;
        private String state = "NO HAND";

        DebugView(android.content.Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            pointPaint.setStyle(Paint.Style.FILL);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(2f);
            textPaint.setTextSize(12f * context.getResources().getDisplayMetrics().scaledDensity);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void setFrame(Bitmap newFrame, List<NormalizedLandmark> newLandmarks, String newState) {
            if (frame != null && frame != newFrame) frame.recycle();
            frame = newFrame;
            landmarks = newLandmarks;
            state = newState;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            if (frame != null) {
                canvas.drawBitmap(frame, null,
                        new android.graphics.Rect(0, 0, getWidth(), getHeight()),
                        imagePaint);
            }

            if (landmarks != null && landmarks.size() >= 21) {
                linePaint.setColor(Color.WHITE);
                int[][] bones = {
                        {0,1},{1,2},{2,3},{3,4},
                        {0,5},{5,6},{6,7},{7,8},
                        {5,9},{9,10},{10,11},{11,12},
                        {9,13},{13,14},{14,15},{15,16},
                        {13,17},{17,18},{18,19},{19,20},
                        {0,17}
                };
                for (int[] bone : bones) {
                    NormalizedLandmark a = landmarks.get(bone[0]);
                    NormalizedLandmark b = landmarks.get(bone[1]);
                    canvas.drawLine(a.x() * getWidth(), a.y() * getHeight(),
                            b.x() * getWidth(), b.y() * getHeight(), linePaint);
                }

                for (int i = 0; i < landmarks.size(); i++) {
                    NormalizedLandmark p = landmarks.get(i);
                    pointPaint.setColor(i == 8 ? Color.GREEN : (i == 4 ? Color.YELLOW : Color.WHITE));
                    float radius = (i == 8 || i == 4) ? 5f : 3f;
                    canvas.drawCircle(p.x() * getWidth(), p.y() * getHeight(), radius, pointPaint);
                }
            }

            // Readable status label for screen recordings.
            textPaint.setColor(Color.WHITE);
            textPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
            canvas.drawText(state, 8f, 18f, textPaint);
            textPaint.clearShadowLayer();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (frame != null) {
                frame.recycle();
                frame = null;
            }
            super.onDetachedFromWindow();
        }
    }

    private static class CursorView extends View {
        private final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mode = -1;

        CursorView(android.content.Context context) {
            super(context);
            outer.setColor(Color.WHITE);
            outer.setStyle(Paint.Style.FILL);
            inner.setStyle(Paint.Style.FILL);
            setMode(GestureEngine.MODE_IDLE);
        }

        void setMode(int newMode) {
            if (newMode == mode) return;
            mode = newMode;
            switch (newMode) {
                case GestureEngine.MODE_ARMED:   inner.setColor(Color.rgb(255, 200, 40)); break; // yellow: about to click
                case GestureEngine.MODE_PINCHED: inner.setColor(Color.rgb(255, 70, 70)); break;  // red: pressed
                case GestureEngine.MODE_SCROLL:
                case GestureEngine.MODE_DRAG:    inner.setColor(Color.rgb(70, 140, 255)); break; // blue: scrolling
                default:                         inner.setColor(Color.rgb(30, 220, 145)); break; // green: moving
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, Math.min(cx, cy), outer);
            canvas.drawCircle(cx, cy, Math.min(cx, cy) * 0.58f, inner);
        }
    }
}
