package com.tessera.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 今日步数：硬件计步器是开机累计值，这里按天换算，并扛住中途重启。 */
final class Steps implements SensorEventListener {
    interface Callback {
        void onSteps(long today);
    }

    private final SensorManager sensors;
    private final Sensor counter;
    private final SharedPreferences prefs;
    private final Callback callback;
    private boolean running;

    Steps(Context context, Callback callback) {
        this.sensors = context.getSystemService(SensorManager.class);
        this.counter = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        this.prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE);
        this.callback = callback;
    }

    void start() {
        if (running || counter == null) return;
        running = sensors.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL);
    }

    void stop() {
        if (!running) return;
        sensors.unregisterListener(this);
        running = false;
    }

    static long today(Context context) {
        SharedPreferences p = context.getSharedPreferences("steps", Context.MODE_PRIVATE);
        if (!day().equals(p.getString("day", ""))) return 0;
        return p.getLong("today", 0);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long value = (long) event.values[0];
        String day = day();
        long base = prefs.getLong("base", value);
        long offset = prefs.getLong("offset", 0);
        long last = prefs.getLong("last", value);
        if (!day.equals(prefs.getString("day", ""))) {
            base = value;
            offset = 0;
        } else if (value < last) {
            // 计步器在重启后归零：把重启前的部分记进 offset
            offset += last - base;
            base = value;
        }
        long today = offset + (value - base);
        prefs.edit().putString("day", day).putLong("base", base).putLong("offset", offset)
                .putLong("last", value).putLong("today", today).apply();
        callback.onSteps(today);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static String day() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }
}
