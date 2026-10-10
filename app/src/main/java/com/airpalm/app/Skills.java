package com.airpalm.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;

/** Small phone skills that need no screen automation: flashlight, time, date, battery, volume. */
public final class Skills {
    private Skills() {
    }

    private static String torchId;
    private static volatile boolean torchOn = false;
    private static boolean torchWatching = false;

    // ---------------------------------------------------------------- flashlight (CameraManager, silent)

    private static void watchTorch(Context ctx, CameraManager cm) {
        if (torchWatching) return;
        torchWatching = true;
        cm.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String cameraId, boolean enabled) {
                if (cameraId.equals(torchId)) torchOn = enabled;
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private static String findTorchCamera(CameraManager cm) throws Exception {
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (flash != null && flash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    /**
     * @param mode 0 = off, 1 = on, 2 = toggle, -1 = only start watching the torch state
     * @return the new state (true = on), or null if the phone could not do it
     */
    public static Boolean flashlight(Context ctx, int mode) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return null;
            if (torchId == null) torchId = findTorchCamera(cm);
            if (torchId == null) return null;
            watchTorch(ctx, cm);
            if (mode < 0) return torchOn; // just warm up, change nothing
            boolean target = mode == 2 ? !torchOn : mode == 1;
            cm.setTorchMode(torchId, target);
            torchOn = target;
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- time, date, battery

    public static Replies.Reply timeReply() {
        Calendar c = Calendar.getInstance();
        return Replies.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    public static Replies.Reply dateReply() {
        Calendar c = Calendar.getInstance();
        return Replies.date(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR),
                c.get(Calendar.DAY_OF_WEEK));
    }

    public static Replies.Reply batteryReply(Context ctx) {
        Intent st = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (st == null) return Replies.failed();
        int level = st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = st.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (level < 0 || scale <= 0) return Replies.failed();
        return Replies.battery(Math.round(level * 100f / scale), plugged != 0);
    }

    // ---------------------------------------------------------------- volume

    public static void setVolumePercent(Context ctx, int percent) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = Math.round(max * Math.max(0, Math.min(100, percent)) / 100f);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI);
    }

    public static void mute(Context ctx, boolean mute) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
    }
}
