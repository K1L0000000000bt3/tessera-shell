package com.tessera.shell;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * 心率（数据表盘的"心率"卡片）：只在表盘露出来、上面有心率卡片的时候开传感器（测心率的灯一直亮很费电），
 * 别的时候关掉，卡片显示最后一次的读数。需要 BODY_SENSORS 权限。
 * 开关传感器放在后台线程：这块表关一次心率传感器要卡 ~20ms，放在主线程上正好卡在上滑松手、表盘飞出去的那一帧。
 */
final class HeartRate implements SensorEventListener {
    interface Callback {
        void onHeartRate(int bpm);
    }

    private final Context context;
    private final SensorManager sensors;
    private final Sensor sensor;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler bg;
    private volatile boolean wanted;     // 主线程上的意图；后台线程按它开关
    private boolean running;             // 只在后台线程上读写
    private int last;

    HeartRate(Context context, Callback callback) {
        this.context = context;
        this.sensors = context.getSystemService(SensorManager.class);
        this.sensor = find(sensors);
        this.callback = callback;
    }

    /**
     * 标准心率传感器；没有就按名字找厂商自己的（S108：名字 HEART_RATE，类型 android.sensor.HRN = 65599，bd1860）。
     */
    private static Sensor find(SensorManager sm) {
        if (sm == null) return null;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        if (s != null) return s;
        for (Sensor o : sm.getSensorList(Sensor.TYPE_ALL)) {
            String n = o.getName() == null ? "" : o.getName().toLowerCase(), t = o.getStringType() == null ? "" : o.getStringType().toLowerCase();
            if (n.contains("heart") || t.endsWith(".hrn") || t.contains("heart_rate")) return o;
        }
        return null;
    }

    void start() {
        if (wanted || sensor == null) return;
        if (context.checkSelfPermission(android.Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) return;
        wanted = true;
        bg().post(this::apply);
    }

    void stop() {
        if (!wanted) return;
        wanted = false;
        bg().post(this::apply);
    }

    private Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("heart-rate");
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    /** 后台线程：让传感器的开关跟上 wanted（连着开关好几次时只按最后的意图做一次）。 */
    private void apply() {
        boolean want = wanted;
        if (want == running) return;
        if (want) running = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, bg);
        else { sensors.unregisterListener(this); running = false; }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        // 标准传感器 values[0] = 心率；S108 的厂商传感器是 [1 = 戴好在测 / 0 = 没测到, 心率, 0…]
        boolean vendor = e.sensor.getType() != Sensor.TYPE_HEART_RATE;
        int bpm = vendor ? (e.values.length > 1 && e.values[0] >= 1f ? Math.round(e.values[1]) : 0) : Math.round(e.values[0]);
        if (bpm <= 0 || e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return;
        if (bpm == last) return;
        last = bpm;
        main.post(() -> callback.onHeartRate(bpm));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) { }
}
