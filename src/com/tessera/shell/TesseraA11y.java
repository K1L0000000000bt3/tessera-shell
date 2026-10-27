package com.tessera.shell;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 真机屏幕圆角遮罩（无障碍浮层）。
 *
 * 为什么不用普通悬浮窗：TYPE_APPLICATION_OVERLAY 会被“要求隐藏他人悬浮窗”的应用强制隐藏
 * （系统设置 SettingsHomepageActivity 的 pfl 里就有 HIDE_NON_SYSTEM_OVERLAY_WINDOWS，
 * dumpsys window 能看到我们的窗口 mForceHideNonSystemOverlayWindow=true）。
 * TYPE_ACCESSIBILITY_OVERLAY 不在那个名单里，所以能一直盖在所有应用上方。
 *
 * 遮罩半径跟桌面 CSS 的 --radius-screen 一致（28/200 屏宽，480 上约 67px）。
 */
public class TesseraA11y extends AccessibilityService implements Host.Listener {
    /** 服务已被系统绑定（桌面据此判断要不要把它重新打开）。 */
    static volatile boolean connected;
    private static TesseraA11y instance;

    /** 调试：暂时藏起 / 放回两个浮层窗口（四角遮罩、底边感应带），看合成方式对掉帧的影响（--ez overlays false/true）。 */
    static void debugShowOverlays(boolean on) {
        TesseraA11y s = instance;
        if (s == null) return;
        int v = on ? View.VISIBLE : View.GONE;
        if (s.mask != null) s.mask.setVisibility(v);
        if (s.strip != null) s.strip.setVisibility(v);
    }

    private View mask;
    private View strip;                 // 底边上滑回桌面的感应带

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        if (mask != null) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        mask = new CornerMask(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Tessera Corner Mask");
        try {
            wm.addView(mask, lp);
        } catch (RuntimeException e) {
            mask = null;
        }
        addHomeStrip(wm);
        Host.add(this);
    }

    // ---------- 在应用里从屏幕底边往上滑：回桌面 ----------
    // 停用 Launcher3 之后系统的"上滑回桌面"没了（它会把 Tessera 自己也划走，见 HANDOFF 坑 11），这里自己补一个：
    // 底边一条很窄的感应带，上滑超过 30 设计像素、或快速往上一甩，就执行无障碍的 HOME。Tessera 在前台时这条带不接触摸。
    private float stripY0 = Float.NaN;
    private long stripT0;
    private boolean stripFired;

    private void addHomeStrip(WindowManager wm) {
        float unit = getResources().getDisplayMetrics().widthPixels / 200f;       // 1 设计像素
        strip = new View(this);
        strip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    stripY0 = e.getRawY(); stripT0 = e.getEventTime(); stripFired = false; return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!stripFired && stripY0 - e.getRawY() > 30f * unit) { stripFired = true; performGlobalAction(GLOBAL_ACTION_HOME); }
                    return true;
                case android.view.MotionEvent.ACTION_UP: {
                    float dy = stripY0 - e.getRawY(), dt = Math.max(1, e.getEventTime() - stripT0);
                    if (!stripFired && dy > 10f * unit && dy / dt > 0.35f * unit) performGlobalAction(GLOBAL_ACTION_HOME);
                    return true;
                }
                default: return true;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Math.round(10f * unit),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                stripFlags(Host.shellForeground),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("Tessera Home Swipe");
        try { wm.addView(strip, lp); } catch (RuntimeException e) { strip = null; }
    }

    private static int stripFlags(boolean shellFront) {
        int f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        return shellFront ? f | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : f;     // 桌面自己处理底边手势
    }

    @Override
    public void onEvent(String name, String json) {
        if (!"shell".equals(name) || strip == null) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) strip.getLayoutParams();
        int f = stripFlags(Host.shellForeground);
        if (wm != null && lp != null && lp.flags != f) { lp.flags = f; try { wm.updateViewLayout(strip, lp); } catch (RuntimeException ignored) { } }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        connected = false;
        Host.remove(this);
        if (strip != null) {
            WindowManager w = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            try { if (w != null) w.removeView(strip); } catch (IllegalArgumentException ignored) { }
            strip = null;
        }
        if (mask != null) {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            try { if (wm != null) wm.removeView(mask); } catch (IllegalArgumentException ignored) { }
            mask = null;
        }
        super.onDestroy();
    }

    /** 四个角各一块“方形减去四分之一圆”的黑片，中间完全透明，不接收触摸。 */
    static final class CornerMask extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path corner = new Path();
        private final RectF oval = new RectF();
        private float radius;

        CornerMask(Context context) {
            super(context);
            paint.setColor(0xFF000000);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            radius = Math.min(w, h) * 28f / 200f;
            oval.set(0f, 0f, radius * 2f, radius * 2f);
            corner.reset();
            corner.moveTo(0f, 0f);
            corner.lineTo(radius, 0f);
            corner.arcTo(oval, 270f, -90f, false);
            corner.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (radius <= 0f) return;
            int w = getWidth(), h = getHeight();
            canvas.drawPath(corner, paint);
            canvas.save(); canvas.translate(w, 0f); canvas.scale(-1f, 1f); canvas.drawPath(corner, paint); canvas.restore();
            canvas.save(); canvas.translate(0f, h); canvas.scale(1f, -1f); canvas.drawPath(corner, paint); canvas.restore();
            canvas.save(); canvas.translate(w, h); canvas.scale(-1f, -1f); canvas.drawPath(corner, paint); canvas.restore();
        }
    }
}
