package com.airpalm.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
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
    private WindowManager.LayoutParams cursorParams;
    private int screenWidth;
    private int screenHeight;
    private int cursorSize;

    private long lastProcessedFrame = 0;
    private long lastTap = 0;
    private long lastScroll = 0;
    private float smoothX = -1;
    private float smoothY = -1;
    private boolean pinching = false;

    private int scrollPoseFrames = 0;
    private float scrollAnchorX = Float.NaN;
    private float scrollAnchorY = Float.NaN;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

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

        setupOverlay();
        startCamera();
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
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
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
        if (now - lastProcessedFrame < 80) {
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
            processResult(result, now);
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

    private void processResult(HandLandmarkerResult result, long now) {
        List<List<NormalizedLandmark>> allHands = result.landmarks();
        if (allHands == null || allHands.isEmpty()) {
            mainHandler.post(() -> {
                if (cursorView != null) cursorView.setAlpha(0.25f);
            });
            resetScroll();
            pinching = false;
            return;
        }

        List<NormalizedLandmark> hand = allHands.get(0);
        if (hand.size() < 21) return;

        NormalizedLandmark indexTip = hand.get(8);
        NormalizedLandmark thumbTip = hand.get(4);

        float targetX = clamp(indexTip.x()) * screenWidth;
        float targetY = clamp(indexTip.y()) * screenHeight;

        if (smoothX < 0) {
            smoothX = targetX;
            smoothY = targetY;
        } else {
            smoothX = smoothX * 0.62f + targetX * 0.38f;
            smoothY = smoothY * 0.62f + targetY * 0.38f;
        }

        updateCursor(smoothX, smoothY);

        float dx = indexTip.x() - thumbTip.x();
        float dy = indexTip.y() - thumbTip.y();
        float pinchDistance = (float) Math.sqrt(dx * dx + dy * dy);
        boolean isPinch = pinchDistance < 0.055f;

        if (isPinch && !pinching && now - lastTap > 450) {
            lastTap = now;
            resetScroll();
            AirPalmAccessibilityService.tap(smoothX, smoothY);
        }
        pinching = isPinch;

        if (isPinch) {
            resetScroll();
            return;
        }

        boolean indexExtended = hand.get(8).y() < hand.get(6).y();
        boolean middleExtended = hand.get(12).y() < hand.get(10).y();
        boolean ringExtended = hand.get(16).y() < hand.get(14).y();
        boolean pinkyExtended = hand.get(20).y() < hand.get(18).y();

        boolean cleanTwoFinger =
                indexExtended &&
                middleExtended &&
                !ringExtended &&
                !pinkyExtended;

        if (!cleanTwoFinger) {
            resetScroll();
            return;
        }

        float avgX = (hand.get(8).x() + hand.get(12).x()) * 0.5f;
        float avgY = (hand.get(8).y() + hand.get(12).y()) * 0.5f;

        if (scrollPoseFrames < 2) {
            scrollPoseFrames++;
            scrollAnchorX = avgX;
            scrollAnchorY = avgY;
            return;
        }

        float deltaX = avgX - scrollAnchorX;
        float deltaY = avgY - scrollAnchorY;

        boolean clearVerticalMove =
                Math.abs(deltaY) > 0.075f &&
                Math.abs(deltaY) > Math.abs(deltaX) * 1.25f;

        if (clearVerticalMove && now - lastScroll > 320) {
            int direction = deltaY < 0 ? 1 : -1;
            AirPalmAccessibilityService.scroll(smoothX, smoothY, direction);
            lastScroll = now;
            scrollAnchorX = avgX;
            scrollAnchorY = avgY;
        }
    }

    private void resetScroll() {
        scrollPoseFrames = 0;
        scrollAnchorX = Float.NaN;
        scrollAnchorY = Float.NaN;
    }

    private void updateCursor(float x, float y) {
        mainHandler.post(() -> {
            if (windowManager == null || cursorView == null || cursorParams == null) return;
            cursorView.setAlpha(1f);
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

    private float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        isRunning = false;

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        if (handLandmarker != null) {
            handLandmarker.close();
        }
        if (windowManager != null && cursorView != null) {
            try {
                windowManager.removeView(cursorView);
            } catch (Exception ignored) {
            }
        }

        super.onDestroy();
    }

    private static class CursorView extends View {
        private final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(android.content.Context context) {
            super(context);
            outer.setColor(Color.WHITE);
            outer.setStyle(Paint.Style.FILL);
            inner.setColor(Color.rgb(30, 220, 145));
            inner.setStyle(Paint.Style.FILL);
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
