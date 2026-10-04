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

    private static final float PINCH_START = 0.043f;
    private static final float PINCH_RELEASE = 0.070f;
    private static final int PINCH_STABLE_FRAMES = 2;

    private static final int SCROLL_STABLE_FRAMES = 4;
    private static final float SCROLL_MOVE_THRESHOLD = 0.085f;
    private static final float SCROLL_VERTICAL_DOMINANCE = 1.35f;

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
    private int pinchCandidateFrames = 0;

    private int twoFingerStableFrames = 0;
    private boolean scrollMode = false;
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
                        .setMinHandDetectionConfidence(0.58f)
                        .setMinHandPresenceConfidence(0.58f)
                        .setMinTrackingConfidence(0.58f)
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
                        .setTargetResolution(new Size(480, 360))
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

        if (now - lastProcessedFrame < 90) {
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
                paddedWidth,
                image.getHeight(),
                Bitmap.Config.ARGB_8888);

        padded.copyPixelsFromBuffer(buffer);

        if (paddedWidth == image.getWidth()) return padded;

        Bitmap cropped = Bitmap.createBitmap(
                padded,
                0,
                0,
                image.getWidth(),
                image.getHeight());

        padded.recycle();
        return cropped;
    }

    private Bitmap orientFrontCamera(Bitmap src, int rotationDegrees) {
        Matrix m = new Matrix();
        m.postRotate(rotationDegrees);
        m.postScale(-1f, 1f);

        return Bitmap.createBitmap(
                src,
                0,
                0,
                src.getWidth(),
                src.getHeight(),
                m,
                true);
    }

    private void processResult(HandLandmarkerResult result, long now) {
        List<List<NormalizedLandmark>> allHands = result.landmarks();

        if (allHands == null || allHands.isEmpty()) {
            mainHandler.post(() -> {
                if (cursorView != null) cursorView.setAlpha(0.25f);
            });

            resetGestureState();
            return;
        }

        List<NormalizedLandmark> hand = allHands.get(0);
        if (hand.size() < 21) return;

        NormalizedLandmark thumbTip = hand.get(4);
        NormalizedLandmark indexTip = hand.get(8);
        NormalizedLandmark middleTip = hand.get(12);

        float targetX = clamp(indexTip.x()) * screenWidth;
        float targetY = clamp(indexTip.y()) * screenHeight;

        if (smoothX < 0) {
            smoothX = targetX;
            smoothY = targetY;
        } else {
            smoothX = smoothX * 0.56f + targetX * 0.44f;
            smoothY = smoothY * 0.56f + targetY * 0.44f;
        }

        updateCursor(smoothX, smoothY);

        float pinchDistance = distance(
                indexTip.x(),
                indexTip.y(),
                thumbTip.x(),
                thumbTip.y());

        boolean pinchNow = pinching
                ? pinchDistance < PINCH_RELEASE
                : pinchDistance < PINCH_START;

        if (pinchNow) {
            pinchCandidateFrames++;

            if (!pinching
                    && pinchCandidateFrames >= PINCH_STABLE_FRAMES
                    && now - lastTap > 500) {

                pinching = true;
                cancelScrollMode();
                lastTap = now;

                AirPalmAccessibilityService.tap(smoothX, smoothY);
            }

        } else {
            pinching = false;
            pinchCandidateFrames = 0;
        }

        if (pinching) {
            cancelScrollMode();
            return;
        }

        boolean indexExtended = fingerExtended(hand, 8, 6);
        boolean middleExtended = fingerExtended(hand, 12, 10);
        boolean ringExtended = fingerExtended(hand, 16, 14);
        boolean pinkyExtended = fingerExtended(hand, 20, 18);

        boolean cleanTwoFingerPose =
                indexExtended
                        && middleExtended
                        && !ringExtended
                        && !pinkyExtended;

        if (!cleanTwoFingerPose) {
            cancelScrollMode();
            return;
        }

        float avgX = (indexTip.x() + middleTip.x()) * 0.5f;
        float avgY = (indexTip.y() + middleTip.y()) * 0.5f;

        if (!scrollMode) {
            twoFingerStableFrames++;

            if (twoFingerStableFrames >= SCROLL_STABLE_FRAMES) {
                scrollMode = true;
                scrollAnchorX = avgX;
                scrollAnchorY = avgY;
            }

            return;
        }

        float deltaX = avgX - scrollAnchorX;
        float deltaY = avgY - scrollAnchorY;

        float vertical = Math.abs(deltaY);
        float horizontal = Math.abs(deltaX);

        boolean verticalMovement =
                vertical > SCROLL_MOVE_THRESHOLD
                        && vertical > horizontal * SCROLL_VERTICAL_DOMINANCE;

        if (verticalMovement && now - lastScroll > 420) {
            int direction = deltaY < 0 ? 1 : -1;

            AirPalmAccessibilityService.scroll(
                    smoothX,
                    smoothY,
                    direction);

            lastScroll = now;
            scrollAnchorX = avgX;
            scrollAnchorY = avgY;
        }
    }

    private boolean fingerExtended(
            List<NormalizedLandmark> hand,
            int tip,
            int pip) {

        return hand.get(tip).y() < hand.get(pip).y() - 0.018f;
    }

    private float distance(
            float x1,
            float y1,
            float x2,
            float y2) {

        float dx = x1 - x2;
        float dy = y1 - y2;

        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void cancelScrollMode() {
        twoFingerStableFrames = 0;
        scrollMode = false;
        scrollAnchorX = Float.NaN;
        scrollAnchorY = Float.NaN;
    }

    private void resetGestureState() {
        pinching = false;
        pinchCandidateFrames = 0;
        cancelScrollMode();
    }

    private void updateCursor(float x, float y) {
        mainHandler.post(() -> {
            if (windowManager == null
                    || cursorView == null
                    || cursorParams == null) {
                return;
            }

            cursorView.setAlpha(1f);

            cursorParams.x = Math.max(
                    -cursorSize / 2,
                    Math.min(
                            screenWidth - cursorSize / 2,
                            (int) x - cursorSize / 2));

            cursorParams.y = Math.max(
                    -cursorSize / 2,
                    Math.min(
                            screenHeight - cursorSize / 2,
                            (int) y - cursorSize / 2));

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
        return Math.round(
                value * getResources().getDisplayMetrics().density);
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
        private final Paint outer =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint inner =
                new Paint(Paint.ANTI_ALIAS_FLAG);

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

            canvas.drawCircle(
                    cx,
                    cy,
                    Math.min(cx, cy),
                    outer);

            canvas.drawCircle(
                    cx,
                    cy,
                    Math.min(cx, cy) * 0.58f,
                    inner);
        }
    }
}
