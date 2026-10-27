package com.tessera.shell;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;

/**
 * 转门 / 翻页时还没转平的卡片加阴影：离转轴越远越暗，转得越多越暗（用户 2026-10-25："未翻转过来的卡片末尾加上阴影"）。
 * 都画在已经套好转门变换的画布上，所以阴影跟着卡片一起转。
 */
final class Shade {
    private Shade() {}

    static final float MAX = 0.65f;                 // 转到 90° 时最远那一端的黑色不透明度
    private static final Paint P = new Paint();
    private static final PorterDuffXfermode ATOP = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);

    /**
     * 快转到头时整块的不透明度，到 maxDeg 时完全看不见（WP 是到 50° 一下出现 / 一下消失）。
     * 翻进来（leaving = false）：只在最后 15% 的角度淡入，几毫秒，转的过程整个看得见
     *   （从 55% 就开始淡的话，50° 的转门转的那一段全被淡掉，用户说"applist 转门还是没做好"）。
     * 翻走（leaving = true）：转过一半角度就开始淡，边甩边淡掉（用户 2026-10-25："applist 到最后为什么是突然消失的"）。
     */
    static float edge(float deg, float maxDeg, boolean leaving) {
        float m = Math.abs(maxDeg), from = leaving ? 0.5f : 0.85f;   // 往外翻的角度是负的
        float x = (Math.abs(deg) - from * m) / ((1f - from) * m);
        if (x <= 0f) return 1f;
        if (x >= 1f) return 0f;
        return 1f - x * x * (3f - 2f * x);
    }

    /** 转过 deg 度时的阴影强度 0..1。 */
    static float amount(float deg) { return Math.min(1f, (float) Math.abs(Math.sin(Math.toRadians(deg)))); }

    /** 不透明的矩形块（磁贴）：直接在 [l,t,r,b] 上盖一层，nearX 处不暗、farX 处最暗；a = amount × 这块本身的不透明度。 */
    static void over(Canvas c, float a, float nearX, float farX, float l, float t, float r, float b) {
        if (a <= 0.004f) return;
        paint(a, nearX, farX);
        c.drawRect(l, t, r, b, P);
        P.setShader(null);
    }

    /** 不是整块矩形（圆角、文字、半透明底上的卡片）：先开一层画内容（整层不透明度 alpha），end 时阴影只落在画出来的像素上。 */
    static int begin(Canvas c, float l, float t, float r, float b, float alpha) {
        return c.saveLayerAlpha(l, t, r, b, Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
    }

    static void end(Canvas c, int save, float a, float nearX, float farX, float l, float t, float r, float b) {
        if (a > 0.004f) {
            paint(a, nearX, farX);
            P.setXfermode(ATOP);
            c.drawRect(l, t, r, b, P);
            P.setXfermode(null);
            P.setShader(null);
        }
        c.restoreToCount(save);
    }

    private static void paint(float a, float nearX, float farX) {
        int alpha = Math.round(255 * MAX * Math.min(1f, a));
        P.setShader(new LinearGradient(nearX, 0, farX, 0, Color.argb(0, 0, 0, 0), Color.argb(alpha, 0, 0, 0), Shader.TileMode.CLAMP));
    }
}
