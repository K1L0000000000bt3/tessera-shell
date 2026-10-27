package com.tessera.shell;

import android.view.animation.Interpolator;

/**
 * 一个按 CSS transition 方式变化的数：目标一变，从当前值出发重新走一段（可带 delay，delay 期间停在出发值）。
 * 时间用 SystemClock.uptimeMillis()。
 */
final class Tween {
    private float from, to;
    private long t0, dur;
    private Interpolator ip;

    Tween(float v0) { from = to = v0; }

    float target() { return to; }

    void set(float target, long now, long d, Interpolator i) { set(target, now, d, i, 0); }

    void set(float target, long now, long d, Interpolator i, long delay) {
        if (target == to && (d > 0 || delay > 0 || !running(now))) return;   // 值没变：CSS 不会起新的过渡
        float cur = get(now);
        if (d <= 0 && delay <= 0) { jump(target); return; }
        from = cur; to = target; t0 = now + delay; dur = Math.max(0, d); ip = i;
    }

    /** 不要过渡，直接到位（CSS 里 transition: none / 该属性不在 transition 列表里）。 */
    void jump(float target) { from = to = target; dur = 0; t0 = 0; }

    float get(long now) {
        if (now < t0) return from;
        if (dur <= 0 || now >= t0 + dur) return to;
        return from + (to - from) * ip.getInterpolation((now - t0) / (float) dur);
    }

    boolean running(long now) { return now < t0 + dur; }
}
