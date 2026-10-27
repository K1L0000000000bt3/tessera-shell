package com.tessera.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;

/**
 * 长按表盘换表盘：Cover Flow（设计稿"C 数据 · 长按换表盘"Data.dc.html 的 cover() / onDown / onMove / onUp / pick）。
 * 方形封面，正中那张正对着你，其余转 65° 叠在两边；手指拖着整排走，松手落到最近的一张，快速一甩最多多走一张；
 * 点一张：先跑到正中，360ms 后放大铺满屏幕。
 *
 * 所有几何都在 200×200 的设计坐标里算，和 CSS 一样：舞台 perspective 320px、perspective-origin 50% 43%，
 * 每张卡 transform = translateX translateY(-14) translateZ rotateY scale(0.5)，transform-origin 卡片中心。
 * 表盘先画进位图（每秒重画一次），再按算好的透视矩阵贴上去。
 */
final class FaceGallery extends View {
    interface Listener {
        /** 选中了一张（关画廊的动画开始时）。 */
        void onPick(int style);
        /** 画廊完全收起、不再盖住表盘。 */
        void onClosed();
    }

    static final String[] NAMES = FaceStyles.NAMES;
    private static final int N = FaceStyles.COUNT;
    private static final float SIZE = 200f;
    private static final float STEP = 84f;          // 拖动多少移动一张 = 正中到第一张侧卡的距离
    private static final float SIDE = 32f;          // 侧卡之间的距离
    private static final float PERSPECTIVE = 320f, ORIGIN_X = 100f, ORIGIN_Y = 86f;
    private static final long T_MOVE = 380, T_FADE = 240, T_NAME = 300, T_GROUND = 320, T_CHROME = 200, ENTER_DELAY = 360;
    private static final int FACE_BG = 0xFF000000;

    private static final class Card {
        final Tween x = new Tween(0), y = new Tween(0), z = new Tween(0), rot = new Tween(0), sc = new Tween(1), op = new Tween(0), name = new Tween(0);
        int zIndex;
        final Matrix m = new Matrix(), inv = new Matrix();
    }

    private final FaceView face;
    private final Listener listener;
    private final Card[] cards = new Card[N];
    private final Tween ground = new Tween(0), chrome = new Tween(0);
    private final Bitmap[] faceBmp = new Bitmap[N], reflBmp = new Bitmap[N], nameBmp = new Bitmap[N];
    private final float[] nameW = new float[N];
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint();
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final RectF dst = new RectF();
    private final Integer[] order = new Integer[N];
    private int surface = 0xFF181C20, ink = 0xFFEEF2F5;
    private long renderedSecond = -1;
    private int dirty;
    private Bitmap bigBmp;                 // 正在铺满 / 从铺满缩回的那张用全分辨率，其余半分辨率
    private int bigFor = -1;
    private Paint mask;
    private int maskH;

    private boolean gallery, dragging, justOpened, dragged;
    private int selected, focus;
    private float dragX;
    // 按下
    private boolean pressed;
    private float px0, lastDx, vel;
    private long lastT;
    private final Runnable enter = this::enterFocused;
    private int pendingEnter = -1;

    FaceGallery(Context c, FaceView face, Listener listener) {
        super(c);
        this.face = face;
        this.listener = listener;
        for (int i = 0; i < N; i++) { cards[i] = new Card(); order[i] = i; }
        namePaint.setTypeface(Fonts.sans(c.getAssets(), 600));
        setVisibility(GONE);
    }

    void setTheme(Theme th) { surface = th.surface; ink = th.ink; for (int i = 0; i < N; i++) nameBmp[i] = null; invalidate(); }

    boolean isOpen() { return getVisibility() == VISIBLE && gallery; }

    boolean isShowing() { return getVisibility() == VISIBLE; }

    private int px() { return getWidth() > 0 ? getWidth() : face.getWidth(); }

    private float k() { return px() > 0 ? px() / SIZE : 2.4f; }

    // ---------- 状态 ----------

    /** 长按表盘：打开画廊，当前表盘在正中。 */
    void open(int current) {
        if (getVisibility() != VISIBLE) {
            // 从"关着"的样子出发：当前那张铺满屏幕，其余在两边屏幕外、透明
            long t = SystemClock.uptimeMillis();
            selected = current; focus = current;
            gallery = false; dragging = false; dragX = 0;
            for (int i = 0; i < N; i++) pose(i, t, false);
            ground.set(0f, t, 0, null); chrome.set(0f, t, 0, null);
            renderedSecond = -1;
            ensureBitmaps(true);                         // 先把各表盘画好再起动画（第一次要几十毫秒，别吃掉动画开头）
            renderBig(current);
            setVisibility(VISIBLE);
            face.setVisibility(INVISIBLE);               // 画廊是不透明的整屏，底下的表盘不用再每帧画一遍
        }
        long now = SystemClock.uptimeMillis();
        removeCallbacks(enter); pendingEnter = -1;
        gallery = true; focus = selected; dragX = 0; dragging = false;
        justOpened = true;
        poseAll(now, true);
        invalidate();
    }

    /** 收起（返回键 / 回主屏）：保持原来的表盘。 */
    void close() {
        if (!isShowing()) return;
        removeCallbacks(enter); pendingEnter = -1;
        if (!gallery) return;
        gallery = false; dragging = false; dragX = 0;
        renderBig(selected);
        poseAll(SystemClock.uptimeMillis(), true);
        invalidate();
    }

    /** 立刻收掉（不播动画）。 */
    void dismiss() {
        removeCallbacks(enter); pendingEnter = -1;
        gallery = false; dragging = false; pressed = false;
        if (getVisibility() == VISIBLE) { face.setVisibility(VISIBLE); setVisibility(GONE); recycle(); listener.onClosed(); }
    }

    /** 表冠：一格移一张。 */
    void crown(int delta) {
        if (!gallery) return;
        int f = clamp(focus + (delta > 0 ? 1 : -1));
        if (f == focus) return;
        focus = f;
        poseAll(SystemClock.uptimeMillis(), true);
        invalidate();
    }

    private int clamp(int f) { return Math.max(0, Math.min(N - 1, f)); }

    private void poseAll(long now, boolean animate) {
        for (int i = 0; i < N; i++) pose(i, now, animate && !dragging);
        ground.set(gallery ? 1f : 0f, now, T_GROUND, Curves.GAL_FADE);
        chrome.set(gallery ? 1f : 0f, now, T_CHROME, Curves.GAL_FADE);
    }

    /** 设计稿 cover(i)。 */
    private void pose(int i, long now, boolean animate) {
        Card c = cards[i];
        float x, y, z, rot, sc, op, nm;
        if (!gallery) {
            if (i == selected) { x = 0; y = 0; z = 0; rot = 0; sc = 1; op = 1; c.zIndex = 10; }
            else {
                float s = i < selected ? -1f : 1f;
                x = s * 260f; y = -14f; z = -60f; rot = -s * 65f; sc = 0.5f; op = 0; c.zIndex = 1;
            }
            nm = 0;
        } else {
            float pos = i - focus + dragX / STEP, a = Math.abs(pos), s = pos < 0 ? -1f : 1f;
            x = a < 1 ? pos * STEP : s * (STEP + (a - 1) * SIDE);
            rot = a < 1 ? -pos * 65f : -s * 65f;
            z = -60f * Math.min(1f, a);
            y = -14f; sc = 0.5f; op = 1;
            c.zIndex = 10 - Math.round(a * 2);
            nm = Math.max(0f, 1f - a);
        }
        long dm = animate ? T_MOVE : 0, df = animate ? T_FADE : 0, dn = animate ? T_NAME : 0;
        c.x.set(x, now, dm, Curves.GAL_MOVE); c.y.set(y, now, dm, Curves.GAL_MOVE); c.z.set(z, now, dm, Curves.GAL_MOVE);
        c.rot.set(rot, now, dm, Curves.GAL_MOVE); c.sc.set(sc, now, dm, Curves.GAL_MOVE);
        c.op.set(op, now, df, Curves.GAL_FADE);
        c.name.set(nm, now, dn, Curves.GAL_FADE);
    }

    /** 点了一张：已在正中就直接铺满，否则先跑到正中、360ms 后铺满。 */
    private void pick(int i) {
        removeCallbacks(enter);
        if (i == focus) { selected = i; pendingEnter = -1; enterFocused(); return; }
        focus = i;
        poseAll(SystemClock.uptimeMillis(), true);
        pendingEnter = i;
        postDelayed(enter, ENTER_DELAY);
        invalidate();
    }

    private void enterFocused() {
        int i = pendingEnter >= 0 ? pendingEnter : focus;
        pendingEnter = -1;
        selected = i;
        gallery = false; dragging = false; dragX = 0;
        renderBig(i);
        poseAll(SystemClock.uptimeMillis(), true);
        listener.onPick(i);
        invalidate();
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float k = k();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                px0 = e.getX(); lastDx = 0; vel = 0; lastT = e.getEventTime();
                justOpened = false;
                if (gallery) {
                    removeCallbacks(enter); pendingEnter = -1;
                    dragging = true; dragX = 0;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!pressed) return true;
                if (gallery && dragging) {
                    float dx = (e.getX() - px0) / k;
                    long t = e.getEventTime(), dt = Math.max(1, t - lastT);
                    vel = 0.7f * ((dx - lastDx) / dt) + 0.3f * vel;
                    lastDx = dx; lastT = t;
                    dragX = dx;
                    poseAll(SystemClock.uptimeMillis(), false);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!pressed) return true;
                pressed = false;
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (gallery && dragging) {
                    float flick = Math.max(-0.55f * STEP, Math.min(0.55f * STEP, vel * 60f));
                    float thrown = dragX + flick;
                    int f = clamp(focus - Math.round(thrown / STEP));
                    dragged = Math.abs(dragX) > 6f;
                    dragging = false; dragX = 0; focus = f;
                    poseAll(SystemClock.uptimeMillis(), true);
                    invalidate();
                }
                if (cancel) { dragged = false; return true; }
                // click
                if (justOpened) { justOpened = false; return true; }
                if (dragged) { dragged = false; return true; }
                if (!gallery) return true;
                int hit = hitTest(e.getX(), e.getY());
                if (hit >= 0) pick(hit);
                return true;
            }
        }
        return true;
    }

    /** 最上面那张盖住手指的卡（每张卡的按钮就是它自己 200×200 的方块，名字和倒影不接点击）。 */
    private int hitTest(float sx, float sy) {
        long now = SystemClock.uptimeMillis();
        sortByZ();
        float[] p = new float[2];
        for (int j = N - 1; j >= 0; j--) {
            int i = order[j];
            Card c = cards[i];
            if (c.op.get(now) <= 0.01f) continue;
            matrix(c, now, c.m);
            if (!c.m.invert(c.inv)) continue;
            p[0] = sx; p[1] = sy;
            c.inv.mapPoints(p);
            if (p[0] >= 0 && p[0] <= SIZE && p[1] >= 0 && p[1] <= SIZE) return i;
        }
        return -1;
    }

    private void sortByZ() {
        // z-index 小的先画；一样时按 DOM 顺序（后面的盖住前面的）
        java.util.Arrays.sort(order, (a, b) -> cards[a].zIndex != cards[b].zIndex ? cards[a].zIndex - cards[b].zIndex : a - b);
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float k = k();
        boolean busy = ground.running(now) || chrome.running(now);
        for (Card c : cards) {
            if (c.x.running(now) || c.y.running(now) || c.z.running(now) || c.rot.running(now) || c.sc.running(now)
                    || c.op.running(now) || c.name.running(now)) { busy = true; break; }
        }
        ensureBitmaps(!busy && !dragging);              // 动画 / 拖动中不重画表盘（软件画五张要几毫秒），停下再换秒

        float g = ground.get(now);
        canvas.drawColor(lerpColor(FACE_BG, surface, g));

        sortByZ();
        boolean reflect = gallery;
        for (int j = 0; j < N; j++) {
            int i = order[j];
            Card c = cards[i];
            float op = c.op.get(now), nm = c.name.get(now);
            if (op <= 0.003f) continue;
            matrix(c, now, c.m);
            canvas.save();
            canvas.concat(c.m);
            int a = Math.round(op * 255);
            if (reflect && reflBmp[i] != null) {
                // -webkit-box-reflect: below 3px，倒影只露出卡片下面 38%（62% 处起渐变到 0.3）
                bmpPaint.setAlpha(a);
                dst.set(0, SIZE + 3f, SIZE, SIZE + 3f + SIZE * 0.38f);
                canvas.drawBitmap(reflBmp[i], null, dst, bmpPaint);
            }
            bmpPaint.setAlpha(a);
            dst.set(0, 0, SIZE, SIZE);
            boolean big = i == bigFor && bigBmp != null && c.sc.get(now) > 0.6f;   // 缩到 0.6 以下就用半分辨率
            canvas.drawBitmap(big ? bigBmp : faceBmp[i], null, dst, bmpPaint);
            if (nm > 0.003f && nameBmp[i] != null) {
                // 名字跟着自己的卡走、一起转；离正中越远越淡（26/36 600，卡片坐标 top 222，居中）
                bmpPaint.setAlpha(Math.round(op * nm * 255));
                float w = nameW[i];
                dst.set(SIZE / 2f - w / 2f, 222f, SIZE / 2f + w / 2f, 258f);
                canvas.drawBitmap(nameBmp[i], null, dst, bmpPaint);
            }
            canvas.restore();
        }

        // 底部圆点：4×4、间隔 4、离底 12，当前那个不透明、其余 0.35
        float ch = chrome.get(now);
        if (ch > 0.003f) {
            float total = N * 4f + (N - 1) * 4f, x0 = (SIZE - total) / 2f, y0 = SIZE - 12f - 4f;
            for (int i = 0; i < N; i++) {
                fill.setColor(ink);
                fill.setAlpha(Math.round(ch * (i == focus ? 1f : 0.35f) * 255));
                canvas.drawRect((x0 + i * 8f) * k, y0 * k, (x0 + i * 8f + 4f) * k, (y0 + 4f) * k, fill);
            }
        }

        if (gallery && !busy) bigFor = -1;
        if (!gallery && !busy && pendingEnter < 0 && !dragging) {
            // 收起的动画走完：选中那张已经和表盘一模一样地铺满屏幕，换回真正的表盘
            post(() -> { if (!gallery && getVisibility() == VISIBLE) { face.setVisibility(VISIBLE); setVisibility(GONE); recycle(); listener.onClosed(); } });
            return;
        }
        if (busy || dragging || dirty != 0) postInvalidateOnAnimation();
        else postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000 + 5);   // 静止时每秒换一下时间
    }

    /**
     * 卡片 i 的设计坐标 → 屏幕像素的透视矩阵（和浏览器一样的 4×4 乘法，再取 z=0 平面上的 3×3）：
     * scale(k) · T(origin) · perspective(320) · T(-origin) · T(100,100) · T(x,y,z) · rotateY · scale(s) · T(-100,-100)
     */
    private void matrix(Card c, long now, Matrix out) {
        float tx = c.x.get(now), ty = c.y.get(now), tz = c.z.get(now), s = c.sc.get(now);
        double r = Math.toRadians(c.rot.get(now)), cs = Math.cos(r), sn = Math.sin(r);
        // 局部点 (u, v, 0)：先绕中心缩放、转，再平移
        // q = (100 + tx + s*(u-100)*cos,  100 + ty + s*(v-100),  tz - s*(u-100)*sin)
        // 透视：w = 1 - q.z / 320；屏幕 = origin + (q.xy - origin) / w
        // 写成 u, v 的线性式：X = a0*u + a1*v + a2，Y = b0*u + b1*v + b2，Z = c0*u + c1*v + c2
        double a0 = s * cs, a2 = 100 + tx - s * 100 * cs;
        double b1 = s, b2 = 100 + ty - s * 100;
        double c0 = -s * sn, c2 = tz + s * 100 * sn;
        // w = 1 - Z/d；x' = ox*w + (X - ox) = X - ox*Z/d ；y' = Y - oy*Z/d
        double d = PERSPECTIVE;
        double w0 = -c0 / d, w2 = 1 - c2 / d;
        double x0 = a0 - ORIGIN_X * c0 / d, x2 = a2 - ORIGIN_X * c2 / d;
        double y0 = -ORIGIN_Y * c0 / d, y1 = b1, y2 = b2 - ORIGIN_Y * c2 / d;
        float k = k();
        float[] v = {
                (float) (x0 * k / w2), 0f, (float) (x2 * k / w2),
                (float) (y0 * k / w2), (float) (y1 * k / w2), (float) (y2 * k / w2),
                (float) (w0 / w2), 0f, 1f
        };
        out.setValues(v);
    }

    // ---------- 位图 ----------

    /**
     * 各表盘的位图。第一次（打开画廊时）一次画齐；之后每秒换时间时标记全部"脏"，一帧只重画一张（先画正中那张），
     * 一帧里软件画五张要 ~30ms，会顿一下。动画 / 拖动中不重画。
     */
    private void ensureBitmaps(boolean tick) {
        if (px() <= 0) return;
        int px = Math.round(px() / 2f);                    // 画廊里正中那张就是半屏大，半分辨率正好 1:1（采样整张大图 GPU 吃不消）
        long sec = System.currentTimeMillis() / 1000;
        boolean fresh = faceBmp[0] == null || faceBmp[0].getWidth() != px;
        if (fresh) {
            for (int i = 0; i < N; i++) render(i, px);
            renderedSecond = sec; dirty = 0;
            return;
        }
        if (!tick) return;
        if (sec != renderedSecond) { renderedSecond = sec; dirty = (1 << N) - 1; }
        if (dirty == 0) return;
        int i = (dirty & (1 << focus)) != 0 ? focus : Integer.numberOfTrailingZeros(dirty);
        dirty &= ~(1 << i);
        render(i, px);
    }

    private void render(int i, int px) {
        float k = px / SIZE;
        int rh = Math.round(SIZE * 0.38f * k);
        if (faceBmp[i] == null || faceBmp[i].getWidth() != px) {
            faceBmp[i] = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(faceBmp[i]);
        c.save();
        c.scale(k, k);
        face.renderFace(c, i, FaceView.now());
        c.restore();
        // 倒影：卡片下面 38% 上下翻过来，靠卡片那头 0.3、往下渐变到 0
        if (reflBmp[i] == null || reflBmp[i].getWidth() != px) {
            reflBmp[i] = Bitmap.createBitmap(px, rh, Bitmap.Config.ARGB_8888);
        }
        Canvas rc = new Canvas(reflBmp[i]);
        rc.drawColor(0, PorterDuff.Mode.CLEAR);
        rc.save();
        rc.scale(1f, -1f, 0, rh / 2f);
        rc.translate(0, rh - px);
        rc.drawBitmap(faceBmp[i], 0, 0, null);
        rc.restore();
        if (mask == null || maskH != rh) {
            maskH = rh;
            mask = new Paint();
            mask.setShader(new LinearGradient(0, 0, 0, rh, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }
        rc.drawRect(0, 0, px, rh, mask);
        if (nameBmp[i] == null) {
            String s = NAMES[i];
            namePaint.setTextSize(26f);
            float w = namePaint.measureText(s) + 4f;            // 设计单位，左右各留 2
            nameW[i] = w;
            nameBmp[i] = Bitmap.createBitmap(Math.round(w * 1.2f), Math.round(36f * 1.2f), Bitmap.Config.ARGB_8888);
            Canvas nc = new Canvas(nameBmp[i]);
            namePaint.setColor(ink);
            nc.scale(1.2f, 1.2f);
            nc.drawText(s, 2f, TileGridView.baseline(namePaint, 0f, 36f), namePaint);
        }
    }

    private void renderBig(int i) {
        int px = px();
        if (px <= 0) return;
        if (bigBmp == null || bigBmp.getWidth() != px) bigBmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bigBmp);
        c.save();
        c.scale(px / SIZE, px / SIZE);
        face.renderFace(c, i, FaceView.now());
        c.restore();
        bigFor = i;
    }

    private void recycle() {
        bigBmp = null; bigFor = -1;
        for (int i = 0; i < N; i++) { faceBmp[i] = null; reflBmp[i] = null; }
        renderedSecond = -1;
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
