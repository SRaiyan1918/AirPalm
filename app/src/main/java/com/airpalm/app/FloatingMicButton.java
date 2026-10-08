package com.airpalm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * A small round microphone button that floats over every app.
 * Tap = start/stop listening. Drag = move it (position is remembered).
 * Grey = idle, red = listening.
 */
public class FloatingMicButton {
    private final Context ctx;
    private final SharedPreferences prefs;
    private final Runnable onTap;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MicView view;
    private WindowManager.LayoutParams lp;
    private final int size;

    public FloatingMicButton(Context ctx, SharedPreferences prefs, Runnable onTap) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.onTap = onTap;
        this.size = Math.round(56 * ctx.getResources().getDisplayMetrics().density);
    }

    public void show() {
        main.post(() -> {
            if (view != null || !Settings.canDrawOverlays(ctx)) return;
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            lp = new WindowManager.LayoutParams(size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = prefs.getInt("mic_x", dm.widthPixels - size - Math.round(8 * dm.density));
            lp.y = prefs.getInt("mic_y", dm.heightPixels / 2);
            clampToScreen(dm);

            view = new MicView(ctx);
            view.setOnTouchListener(new DragTouch(dm));
            try {
                wm.addView(view, lp);
            } catch (Exception e) {
                view = null;
            }
        });
    }

    public void hide() {
        main.post(() -> {
            if (view != null && wm != null) {
                try {
                    wm.removeView(view);
                } catch (Exception ignored) {
                }
            }
            view = null;
        });
    }

    public void setActive(boolean active) {
        main.post(() -> {
            if (view != null) view.setActive(active);
        });
    }

    private void clampToScreen(DisplayMetrics dm) {
        lp.x = Math.max(0, Math.min(dm.widthPixels - size, lp.x));
        lp.y = Math.max(0, Math.min(dm.heightPixels - size, lp.y));
    }

    private class DragTouch implements View.OnTouchListener {
        private final DisplayMetrics dm;
        private final int slop;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        DragTouch(DisplayMetrics dm) {
            this.dm = dm;
            this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop * 1.5f) dragging = true;
                    if (dragging) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        clampToScreen(dm);
                        try {
                            wm.updateViewLayout(view, lp);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        prefs.edit().putInt("mic_x", lp.x).putInt("mic_y", lp.y).apply();
                    } else {
                        onTap.run();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    private static class MicView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active = false;

        MicView(Context c) {
            super(c);
            fg.setColor(Color.WHITE);
            fg.setStyle(Paint.Style.STROKE);
            fg.setStrokeCap(Paint.Cap.ROUND);
            setActive(false);
            setAlpha(0.92f);
        }

        void setActive(boolean a) {
            active = a;
            bg.setColor(a ? Color.rgb(220, 50, 50) : Color.rgb(45, 55, 72));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f;
            c.drawCircle(w / 2f, h / 2f, r, bg);

            float u = r / 10f; // drawing unit
            fg.setStrokeWidth(u * 1.1f);
            fg.setStyle(Paint.Style.FILL);
            RectF capsule = new RectF(w / 2f - u * 2.2f, h / 2f - u * 5.2f, w / 2f + u * 2.2f, h / 2f + u * 1.4f);
            c.drawRoundRect(capsule, u * 2.2f, u * 2.2f, fg);

            fg.setStyle(Paint.Style.STROKE);
            RectF arc = new RectF(w / 2f - u * 3.8f, h / 2f - u * 3.2f, w / 2f + u * 3.8f, h / 2f + u * 3.6f);
            c.drawArc(arc, 0, 180, false, fg);
            c.drawLine(w / 2f, h / 2f + u * 3.6f, w / 2f, h / 2f + u * 5.4f, fg);
            c.drawLine(w / 2f - u * 2f, h / 2f + u * 5.4f, w / 2f + u * 2f, h / 2f + u * 5.4f, fg);
        }
    }
}
