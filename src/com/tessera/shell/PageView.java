package com.tessera.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 原生的 Tessera 页面（Watch 组件：顶栏标题 + 时间，下面一列内容），通知中心 / 设置 / 编辑磁贴都用它。
 * 组件逐项照 bundle.css：.ts-status、.ts-item（ListItem）、.ts-toggle、.ts-btn，以及 shell.css 的 .sh-section / .sh-row 等。
 * 滚动手感同 ListScroller（惯性 + 橡皮筋 + 撞边回弹）；可选"滚到底继续上拉 → 整页跟手拉走"（通知中心）。
 * 坐标是设计像素（屏宽 200），绘制时乘 scale。
 */
class PageView extends View {
    interface Listener {
        /** 左边缘右滑。 */
        void onBack();
        /** 滚到底继续往上拉的距离（设计像素，负数）；0 = 推回去了。 */
        default void onPull(float dy) { }
        /** 拉着松手：dy 距离，vy 速度（设计像素/ms，向下为正）。 */
        default void onPullEnd(float dy, float vy) { }
    }

    // ---------- tokens ----------
    static final float STATUS_H = 42f, STATUS_CORNER = 30f, SAFE_BOTTOM = 28f;
    /** 屏幕像素 → dp 后的屏宽、旧的“屏宽 200”设计单位换成 dp 的倍数、字离屏幕左右边的距离（30 屏幕像素，同横幅规则）。 */
    float W = 200f, g = 1f, safe = 12f;
    private static final float DECEL = 0.998f, SPRING_W = 0.02f, BOUNCE_V = 1.5f, SLOP = 6f;

    Theme theme;
    private Listener listener;
    String title = "";
    final List<Item> items = new ArrayList<>();
    boolean translucent;            // 通知中心：底色 surface 62%，正文淡出遮罩要用图层
    boolean pullAtBottom;
    float scale = 2.4f;

    final TextPaint statusTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint statusTime = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    // 左上角应用名、右上角时间：笔画离屏幕边缘（含圆角）30 个屏幕像素，离上边和侧边一样远（落在两边夹角的中心）
    private final SafeEdge.Corner cornerTitle = new SafeEdge.Corner(statusTitle, false), cornerTime = new SafeEdge.Corner(statusTime, true);
    final TextPaint t11 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t11b = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint t13 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t15 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t17 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    final RectF r = new RectF();
    final TileGridView glyphs;      // 借它画设计稿图标

    // 滚动
    private float pos;
    private int motion;
    private float downX, downY, lockY, startPos;
    private int lock;
    private boolean pulling, atEdgeAtDown;
    private float pullDy;
    private final float[] sy = new float[16];
    private final long[] st = new long[16];
    private int sn;
    private Item pressed, swiping;

    PageView(Context c, Theme theme, TileGridView glyphs) {
        super(c);
        this.theme = theme;
        this.glyphs = glyphs;
        Typeface w400 = Fonts.sans(c.getAssets(), 400), w600 = Fonts.sans(c.getAssets(), 600);
        statusTitle.setTypeface(w600); statusTitle.setTextSize(11f); statusTitle.setLetterSpacing(0.08f);
        statusTime.setTypeface(w400); statusTime.setTextSize(13f);
        t11.setTypeface(w400); t11.setTextSize(11f);
        t11b.setTypeface(w600); t11b.setTextSize(11f); t11b.setLetterSpacing(0.08f);
        t13.setTypeface(w400); t13.setTextSize(13f);
        t15.setTypeface(w400); t15.setTextSize(15f);
        t17.setTypeface(w400); t17.setTextSize(17f);
        stroke.setStyle(Paint.Style.STROKE);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    void setListener(Listener l) { listener = l; }

    void setItems(String title, List<Item> list) {
        this.title = title;
        items.clear();
        items.addAll(list);
        pos = Math.max(0, Math.min(pos, maxScroll()));
        invalidate();
    }

    void scrollToTop() { pos = 0; motion++; invalidate(); }

    // ---------- 尺寸 ----------

    private float viewH() { return getHeight() > 0 ? getHeight() / scale : W; }

    private float bodyH() { return viewH() - STATUS_H; }

    private float contentH() {
        float h = 0;
        for (Item it : items) h += it.height(this);
        return h + SAFE_BOTTOM;
    }

    private float maxScroll() { return Math.max(0, contentH() - bodyH()); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { applyScale(); }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); applyScale(); invalidate(); }

    /** 和系统界面统一显示密度（用户 2026-10-25）：按 Android 的 dp 画，不再按“屏宽 200”放大。 */
    private void applyScale() {
        int wpx = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        scale = getResources().getDisplayMetrics().density;
        W = wpx / scale;
        g = (wpx / 200f) / scale;
        safe = Math.max(12f, 30f / scale);
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        c.save();
        c.scale(scale, scale);
        float vh = viewH();
        if (translucent) {
            fill.setColor((theme.surface & 0x00FFFFFF) | (Math.round(0.62f * 255) << 24));   // color-mix(surface 62%)
            c.drawRect(0, 0, W, vh, fill);
        } else {
            c.drawColor(theme.bg);
        }

        // 正文（.ts-body：顶边 8px、底边 24px 淡出）
        float top = STATUS_H;
        int save;
        if (translucent) save = c.saveLayer(0, top, W, vh, null);
        else { save = c.save(); c.clipRect(0, top, W, vh); }
        float y = top - pos;
        for (Item it : items) {
            float h = it.height(this);
            if (y + h > top - 2 && y < vh + 2) again |= it.draw(c, this, y, now);
            y += h;
        }
        if (translucent) {
            fade.setShader(new LinearGradient(0, top, 0, vh, new int[] {0, 0xFF000000, 0xFF000000, 0},
                    new float[] {0f, 8f / (vh - top), 1f - 24f / (vh - top), 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, top, W, vh, fade);
        } else {
            // 底色不透明：用底色渐变盖住边缘，效果同遮罩，省一个图层
            int bg = theme.bg;
            fill.setShader(new LinearGradient(0, top, 0, top + 8f, bg, bg & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, top, W, top + 8f, fill);
            fill.setShader(new LinearGradient(0, vh - 24f, 0, vh, bg & 0x00FFFFFF, bg, Shader.TileMode.CLAMP));
            c.drawRect(0, vh - 24f, W, vh, fill);
            fill.setShader(null);
        }
        c.restoreToCount(save);

        // 顶栏：标题（左上角，11/14 600 0.08em 大写，主题色字）+ 时间（右上角，13）——都按安全距离放进各自的夹角
        cornerTitle.layout(title.toUpperCase(Locale.ROOT), scale, W, vh);
        cornerTitle.draw(c, theme.accentInk);
        cornerTime.layout(ShellData.timeText(System.currentTimeMillis()), scale, W, vh);
        cornerTime.draw(c, theme.ink);
        c.restore();
        if (again) postInvalidateOnAnimation();
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        // 拖动跟踪用屏幕坐标：滚到底继续上拉时整页跟着手指走，层内坐标会把位移吃掉一半
        float fy = y + getTranslationY() / scale;
        long now = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                motion++;
                downX = x; downY = fy; lock = 0; sn = 0; startPos = pos; pulling = false; pullDy = 0;
                atEdgeAtDown = pos >= maxScroll() - 1;
                sample(now, fy);
                pressed = itemAt(y);
                if (pressed != null && pressed.pressable()) { pressed.press(true); invalidate(); } else pressed = null;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = fy - downY;
                if (lock == 0) {
                    if (Math.abs(dx) < SLOP * g && Math.abs(dy) < SLOP * g) return true;
                    lock = Math.abs(dy) >= Math.abs(dx) ? 1 : -1;
                    lockY = downY + (dy > 0 ? SLOP * g : -SLOP * g);
                    if (pressed != null) { pressed.press(false); }
                    Item it = itemAt(downY - getTranslationY() / scale);
                    if (lock == -1 && dx < 0 && it != null && it.onSwipe != null) swiping = it;   // 列表行左滑删除
                    pressed = null;
                    invalidate();
                }
                if (lock == -1 && swiping != null) { swiping.swipeDx = Math.max(-140f * g, Math.min(0f, dx)); invalidate(); return true; }
                if (lock != 1) return true;
                sample(now, fy);
                float raw = startPos - (fy - lockY), m = maxScroll(), d = bodyH();
                if (pullAtBottom && raw > m) {                       // 滚到底继续往上拉：整页跟着手指走
                    pulling = true;
                    pullDy = -(raw - m);
                    pos = m;
                    if (listener != null) listener.onPull(pullDy);
                } else {
                    if (pulling) { pulling = false; pullDy = 0; if (listener != null) listener.onPull(0); }
                    pos = raw < 0 ? -rubber(-raw, d) : raw > m ? m + rubber(raw - m, d) : raw;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                float v = cancel ? 0 : fingerV(now);
                if (swiping != null) {                               // 过 60 就滑出去删掉，否则弹回
                    Item it = swiping;
                    swiping = null;
                    if (!cancel && it.swipeDx < -60f * g) it.leave(); else it.swipeDx = 0;
                    invalidate();
                    return true;
                }
                if (pressed != null) {
                    Item it = pressed;
                    pressed = null;
                    it.press(false);
                    if (!cancel && lock == 0) it.tap(x - safe, this);
                    invalidate();
                    return true;
                }
                if (lock == 1) {
                    if (pulling) { pulling = false; if (listener != null) listener.onPullEnd(pullDy, atEdgeAtDown ? v : 0); }
                    else settle(-v);
                } else if (lock == -1 && !cancel && downX < 14f * g && x - downX > 40f * g && Math.abs(fy - downY) < 40f * g && listener != null) {
                    listener.onBack();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private Item itemAt(float y) {
        float yy = STATUS_H - pos;
        if (y < STATUS_H) return null;
        for (Item it : items) {
            float h = it.height(this);
            if (y >= yy && y < yy + h) return it;
            yy += h;
        }
        return null;
    }

    private void sample(long t, float y) {
        if (sn == sy.length) { System.arraycopy(sy, 1, sy, 0, sn - 1); System.arraycopy(st, 1, st, 0, sn - 1); sn--; }
        sy[sn] = y; st[sn] = t; sn++;
    }

    private float fingerV(long now) {
        if (sn < 2 || now - st[sn - 1] > 80) return 0f;
        int i = sn - 1;
        while (i > 0 && st[sn - 1] - st[i - 1] <= 100) i--;
        long dt = st[sn - 1] - st[i];
        return dt > 0 ? (sy[sn - 1] - sy[i]) / dt : 0f;
    }

    private void settle(float v) {
        final int me = ++motion;
        final float[] vv = {v}, spring = {Float.NaN, 0, 0, 0};
        final long[] last = {0};
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (me != motion) return;
                long now = SystemClock.uptimeMillis();
                float dt = last[0] == 0 ? 16f : Math.min(40f, now - last[0]);
                last[0] = now;
                float m = maxScroll();
                boolean more;
                if (Float.isNaN(spring[0]) && (pos < 0 || pos > m)) {
                    spring[0] = pos < 0 ? 0 : m; spring[1] = pos - spring[0];
                    spring[2] = Math.max(-BOUNCE_V * g, Math.min(BOUNCE_V * g, vv[0])); spring[3] = 0;
                }
                if (!Float.isNaN(spring[0])) {
                    spring[3] += dt;
                    float t = spring[3];
                    float xx = (float) ((spring[1] + (spring[2] + SPRING_W * spring[1]) * t) * Math.exp(-SPRING_W * t));
                    more = !(t > 60 && Math.abs(xx) < 0.3f * g);
                    pos = more ? spring[0] + xx : spring[0];
                } else {
                    pos += vv[0] * dt;
                    vv[0] *= (float) Math.pow(DECEL, dt);
                    more = Math.abs(vv[0]) >= 0.02f * g || pos < 0 || pos > m;
                }
                invalidate();
                if (more && me == motion) postOnAnimation(this);
            }
        });
    }

    private static float rubber(float x, float d) { return (1 - 1 / (x * 0.55f / d + 1)) * d; }

    // ---------- 画组件的小工具 ----------

    float base(Paint p, float top, float lh) { return TileGridView.baseline(p, top, lh); }

    String fit(String s, TextPaint p, float w) { return s == null ? "" : TextUtils.ellipsize(s, p, Math.max(0, w), TextUtils.TruncateAt.END).toString(); }

    /** .ts-btn：高 36 的胶囊，surface-2 底（accent 变体主题色底），15/20。 */
    void pill(Canvas c, float x, float y, float w, String text, boolean accent, boolean down) {
        c.save();
        if (down) c.scale(0.96f, 0.96f, x + w / 2f, y + 18f);
        fill.setColor(accent ? theme.accent : theme.surface2);
        r.set(x, y, x + w, y + 36f);
        c.drawRoundRect(r, 18f, 18f, fill);
        t15.setColor(accent ? theme.onAccent : theme.ink);
        t15.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x + w / 2f, base(t15, y + 8f, 20f), t15);
        t15.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    float pillW(String text) { return 12f * 2 + t15.measureText(text); }

    // ---------- 组件 ----------

    /** 一行内容。y 是这一行在屏幕上的顶边（设计像素），x 从 p.safe 开始画。 */
    abstract static class Item {
        Runnable onSwipe;           // 非空：可以左滑删除
        float swipeDx;
        boolean down;
        long leaveT0;
        abstract float height(PageView p);
        /** 返回 true = 还在动，下一帧接着画。 */
        abstract boolean draw(Canvas c, PageView p, float y, long now);
        boolean pressable() { return false; }
        void press(boolean d) { down = d; }
        void tap(float x, PageView p) { }
        void leave() {
            leaveT0 = SystemClock.uptimeMillis();
            swipeDx = -170f;
        }
    }

    /** .sh-section：上 12 下 4，11/14 600 0.08em 大写，主题色字。 */
    static final class Section extends Item {
        final String text;
        Section(String t) { text = t; }
        @Override float height(PageView p) { return 12f + 14f + 4f; }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            p.t11b.setColor(p.theme.accentInk);
            c.drawText(text.toUpperCase(Locale.ROOT), p.safe, p.base(p.t11b, y + 12f, 14f), p.t11b);
            return false;
        }
    }

    /** .ts-toggle：左边名字（15/20）+ 状态（13/18 次要灰），右边 44×26 的开关，200ms 滑动。 */
    static final class Toggle extends Item {
        final String label;
        final BooleanSupplier get;
        final Consumer<Boolean> set;
        private float k = -1;       // 0 关 → 1 开（动画中间值）
        private long t0;
        private boolean target;
        Toggle(String label, BooleanSupplier get, Consumer<Boolean> set) { this.label = label; this.get = get; this.set = set; }
        @Override float height(PageView p) { return 8f + 38f + 8f; }
        @Override boolean pressable() { return true; }
        @Override void tap(float x, PageView p) { boolean on = !get.getAsBoolean(); set.accept(on); }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            boolean on = get.getAsBoolean();
            if (k < 0) { k = on ? 1 : 0; target = on; }
            if (on != target) { target = on; t0 = now; }
            float kk = Curves.PAGE_TOGGLE.getInterpolation(Math.min(1f, (now - t0) / 200f));
            float from = target ? 0 : 1, to = target ? 1 : 0;
            k = now - t0 >= 200 ? to : from + (to - from) * kk;
            Theme th = p.theme;
            p.t15.setColor(th.ink);
            c.drawText(label, p.safe, p.base(p.t15, y + 8f, 20f), p.t15);
            p.t13.setColor(th.inkMuted);
            c.drawText(on ? "开" : "关", p.safe, p.base(p.t13, y + 28f, 18f), p.t13);
            float tx = p.W - p.safe - 44f, ty = y + 8f + (38f - 26f) / 2f;
            p.r.set(tx + 1f, ty + 1f, tx + 43f, ty + 25f);
            p.fill.setColor(mix(0x00000000, th.accent, k));
            c.drawRoundRect(p.r, 13f, 13f, p.fill);
            p.stroke.setStrokeWidth(2f);
            p.stroke.setColor(mix(th.lineStrong, th.accent, k));
            c.drawRoundRect(p.r, 12f, 12f, p.stroke);
            p.fill.setColor(mix(th.lineStrong, th.onAccent, k));
            c.drawCircle(tx + 2f + 2f + 9f + 18f * k, ty + 13f, 9f, p.fill);
            return now - t0 < 200;
        }
    }

    /** .sh-row：名字 + 副标题，右边一排胶囊按钮。 */
    static final class Row extends Item {
        final String label;
        final java.util.function.Supplier<String> sub;
        final String[] buttons;
        final Consumer<Integer> onButton;
        private int downIdx = -1;
        Row(String label, java.util.function.Supplier<String> sub, String[] buttons, Consumer<Integer> onButton) {
            this.label = label; this.sub = sub; this.buttons = buttons; this.onButton = onButton;
        }
        @Override float height(PageView p) { return 8f + 38f + 8f; }
        @Override boolean pressable() { return buttons.length > 0; }
        private float[] xs(PageView p) {
            float[] out = new float[buttons.length + 1];
            float x = p.W - p.safe;
            for (int i = buttons.length - 1; i >= 0; i--) { x -= p.pillW(buttons[i]); out[i] = x; x -= 4f; }
            out[buttons.length] = p.W - p.safe;
            return out;
        }
        @Override void tap(float x, PageView p) {
            float[] xs = xs(p);
            for (int i = 0; i < buttons.length; i++) if (x + p.safe >= xs[i] && x + p.safe <= xs[i] + p.pillW(buttons[i])) { onButton.accept(i); return; }
        }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            Theme th = p.theme;
            p.t15.setColor(th.ink);
            c.drawText(label, p.safe, p.base(p.t15, y + 8f, 20f), p.t15);
            String s = sub.get();
            if (s != null && !s.isEmpty()) { p.t13.setColor(th.inkMuted); c.drawText(s, p.safe, p.base(p.t13, y + 28f, 18f), p.t13); }
            float[] xs = xs(p);
            for (int i = 0; i < buttons.length; i++) p.pill(c, xs[i], y + 8f + 1f, p.pillW(buttons[i]), buttons[i], false, false);
            return false;
        }
    }

    /** .sh-swatches：28 的圆点，间距 6；选中的带 focus-ring（2px 底色 + 2px 墨色）。 */
    static final class Swatches extends Item {
        final int[] colors;
        final java.util.function.IntSupplier selected;
        final Consumer<Integer> pick;
        Swatches(int[] colors, java.util.function.IntSupplier selected, Consumer<Integer> pick) { this.colors = colors; this.selected = selected; this.pick = pick; }
        @Override float height(PageView p) { return 6f + 28f + 6f; }
        @Override boolean pressable() { return true; }
        @Override void tap(float x, PageView p) { int i = (int) Math.floor(x / 34f); if (i >= 0 && i < colors.length && x - i * 34f <= 28f) pick.accept(i); }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            int sel = selected.getAsInt();
            for (int i = 0; i < colors.length; i++) {
                float cx = p.safe + 14f + i * 34f, cy = y + 6f + 14f;
                if (i == sel) {
                    p.stroke.setStrokeWidth(2f);
                    p.stroke.setColor(p.theme.ink);
                    c.drawCircle(cx, cy, 14f + 3f, p.stroke);
                }
                p.fill.setColor(colors[i]);
                c.drawCircle(cx, cy, 14f, p.fill);
            }
            return false;
        }
    }

    /** .ts-item（ListItem）：32 方块图标（tone 色）+ 标题 17/22 + 副标题 13/18 + 右上角时间；按下缩 0.97 并铺 surface。 */
    static final class ListItem extends Item {
        final String icon, tone, title, sub, meta;
        final Runnable onClick;
        ListItem(String icon, String tone, String title, String sub, String meta, Runnable onClick) {
            this.icon = icon; this.tone = tone; this.title = title; this.sub = sub; this.meta = meta; this.onClick = onClick;
        }
        @Override float height(PageView p) {
            float h = 8f + Math.max(32f, 22f + (sub == null || sub.isEmpty() ? 0 : 18f)) + 8f;
            if (leaveT0 > 0) h *= 1f - Math.min(1f, (SystemClock.uptimeMillis() - leaveT0 - 200) / 120f) * (SystemClock.uptimeMillis() - leaveT0 > 200 ? 1 : 0);
            return h;
        }
        @Override boolean pressable() { return onClick != null; }
        @Override void tap(float x, PageView p) { if (onClick != null) onClick.run(); }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            Theme th = p.theme;
            float h = height(p);
            boolean anim = false;
            float dx = swipeDx;
            int alpha = 255;
            if (leaveT0 > 0) {                                     // 滑出去 200ms（同 .sh-swipe.is-gone），再收起这一行
                float k = Math.min(1f, (now - leaveT0) / 250f);           // WP 滑出 0.25 秒
                dx = -p.W * 0.85f * Curves.PAGE_SWIPE.getInterpolation(k);
                alpha = Math.round(255 * (1 - k));
                anim = now - leaveT0 < 320;
                if (!anim && onSwipe != null) { Runnable r2 = onSwipe; onSwipe = null; p.post(r2); }   // 画的过程中不能改列表
            }
            if (alpha <= 0 || h <= 0.5f) return anim;
            c.save();
            c.translate(dx, 0);
            float full = 8f + Math.max(32f, 22f + (sub == null || sub.isEmpty() ? 0 : 18f)) + 8f;
            if (down) {
                c.scale(0.97f, 0.97f, p.W / 2f, y + full / 2f);
                p.fill.setColor(th.surface);
                c.drawRect(p.safe, y, p.W - p.safe, y + full, p.fill);
            }
            float ix = p.safe, iy = y + (full - 32f) / 2f;
            p.fill.setColor(th.toneBg(tone));
            p.fill.setAlpha(alpha);
            c.drawRect(ix, iy, ix + 32f, iy + 32f, p.fill);
            p.glyphs.glyph(c, icon, ix + 7f, iy + 7f, 18f, (th.toneFg(tone) & 0x00FFFFFF) | (alpha << 24));
            float tx = ix + 32f + 8f, right = p.W - p.safe;
            float metaW = 0;
            if (meta != null && !meta.isEmpty()) {
                p.t11.setColor(th.inkSubtle); p.t11.setAlpha(alpha);
                metaW = p.t11.measureText(meta) + 8f;
                p.t11.setTextAlign(Paint.Align.RIGHT);
                c.drawText(meta, right, p.base(p.t11, y + 8f + 4f, 14f), p.t11);   // align-self: flex-start; padding-top 4
                p.t11.setTextAlign(Paint.Align.LEFT);
            }
            boolean hasSub = sub != null && !sub.isEmpty();
            float textTop = y + (full - (22f + (hasSub ? 18f : 0))) / 2f;
            p.t17.setColor(th.ink); p.t17.setAlpha(alpha);
            c.drawText(p.fit(title, p.t17, right - metaW - tx), tx, p.base(p.t17, textTop, 22f), p.t17);
            if (hasSub) {
                p.t13.setColor(th.inkMuted); p.t13.setAlpha(alpha);
                c.drawText(p.fit(sub, p.t13, right - metaW - tx), tx, p.base(p.t13, textTop + 22f, 18f), p.t13);
            }
            c.restore();
            return anim;
        }
    }

    /** .ts-btn（block）：整行宽的胶囊；上方留 12（.sh-clear）。 */
    static final class Button extends Item {
        final String text;
        final boolean accent;
        final Runnable onClick;
        Button(String text, boolean accent, Runnable onClick) { this.text = text; this.accent = accent; this.onClick = onClick; }
        @Override float height(PageView p) { return 12f + 36f; }
        @Override boolean pressable() { return true; }
        @Override void tap(float x, PageView p) { onClick.run(); }
        @Override boolean draw(Canvas c, PageView p, float y, long now) { p.pill(c, p.safe, y + 12f, p.W - p.safe * 2, text, accent, down); return false; }
    }

    /** .sh-pills：一排胶囊（选中的用主题色），间距 4。 */
    static final class Pills extends Item {
        final String[] options;
        final java.util.function.IntSupplier selected;
        final Consumer<Integer> pick;
        Pills(String[] options, java.util.function.IntSupplier selected, Consumer<Integer> pick) { this.options = options; this.selected = selected; this.pick = pick; }
        @Override float height(PageView p) { return 36f; }
        @Override boolean pressable() { return true; }
        @Override void tap(float x, PageView p) {
            float xx = 0;
            for (int i = 0; i < options.length; i++) { float w = p.pillW(options[i]); if (x >= xx && x <= xx + w) { pick.accept(i); return; } xx += w + 4f; }
        }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            float x = p.safe;
            int sel = selected.getAsInt();
            for (int i = 0; i < options.length; i++) { float w = p.pillW(options[i]); p.pill(c, x, y, w, options[i], i == sel, false); x += w + 4f; }
            return false;
        }
    }

    /** .sh-note：11/14 淡字，上方 12。 */
    static final class Note extends Item {
        final String text;
        Note(String t) { text = t; }
        @Override float height(PageView p) { return 12f + 14f; }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            p.t11.setColor(p.theme.inkSubtle);
            c.drawText(text, p.safe, p.base(p.t11, y + 12f, 14f), p.t11);
            return false;
        }
    }

    /** .sh-empty：上方 44，22 的图标居中（淡），下面 15/20 次要灰的一句话。 */
    static final class Empty extends Item {
        final String icon, text;
        Empty(String icon, String text) { this.icon = icon; this.text = text; }
        @Override float height(PageView p) { return 44f + 22f + 8f + 20f; }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            p.glyphs.glyph(c, icon, p.W / 2f - 11f, y + 44f, 22f, p.theme.inkSubtle);
            p.t15.setColor(p.theme.inkMuted);
            p.t15.setTextAlign(Paint.Align.CENTER);
            c.drawText(text, p.W / 2f, p.base(p.t15, y + 44f + 22f + 8f, 20f), p.t15);
            p.t15.setTextAlign(Paint.Align.LEFT);
            return false;
        }
    }

    /** .sh-preview：一块磁贴的预览（借一个不挂在界面上的 TileGridView 来画），上 8 下 12。 */
    static final class Preview extends Item {
        final TileGridView tile;
        Preview(TileGridView tile) { this.tile = tile; }
        // 磁贴是开始屏的“屏宽 200”单位：乘 g 放回和开始屏上一样大
        @Override float height(PageView p) { return 8f + tile.previewHeight() * p.g + 12f; }
        @Override boolean draw(Canvas c, PageView p, float y, long now) {
            c.save();
            c.translate(0f, y + 8f);
            c.scale(p.g, p.g);
            tile.drawPreview(c, 0f, 0f);
            c.restore();
            return false;
        }
    }

    static int mix(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
