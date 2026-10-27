package com.tessera.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;
import java.util.Random;

/**
 * 桌面里的通知横幅（设计稿 Toast）：从顶上落下来（300ms），6 秒后或上滑收起（240ms），点一下打开。
 * 在表盘上是 mica 磨砂（底色 62% + 颗粒，模糊由 FaceView 在横幅底下画）；盖在别的页面上用实色（设计稿"降低透明度"的规则）。
 */
final class ToastView extends View {
    interface Listener {
        void onOpen(ShellData.Notif n, int count);
        void onAction(ShellData.Notif n, int index);
        /** 横幅的位置变了（设计像素；全 0 = 没了），表盘据此画磨砂。 */
        void onRect(float l, float t, float r, float b);
    }

    // 位置照用户效果图（2026-10-23 第 3 张，480 宽屏幕像素）钉死。
    // 左右：各元素笔画轮廓的边缘——🔔 左边 40（SHELL 跟在它后面）、时间右边 437、标题左边 29（"测"字的点尖不到半个像素，看到的是 30）、正文左边 27；
    // 安全距离 30（用户说的"15px"是 2 倍图）只用来定标题 / 正文省略号的宽度和按钮的边。
    private static final float SIZE = 200f, SAFE_PX = 30f;
    private static final float BELL_LEFT_PX = 40f, TIME_RIGHT_PX = 437f, TITLE_LEFT_PX = 29f, BODY_LEFT_PX = 27f;
    // 上下：在居中排版的基础上各行往下挪（屏幕像素）：头一行（🔔SHELL 和时间）2、标题 5、正文 6
    private static final float HEAD_DY_PX = 2f, TITLE_DY_PX = 5f, BODY_DY_PX = 6f;
    // 用户要求比设计稿矮、字更小：字号 11/15/12/13 → 10/13/11/12，正文只留 1 行，按钮 36 → 26
    private static final float PAD_T = 8f, HEAD = 12f, HEAD_GAP = 1f, TITLE_LH = 16f, BODY_LH = 14f, ACT_GAP = 6f, ACT_H = 26f, PAD_B = 6f;
    private static final long DROP = 350, LIFT = 250, STAY = 6000;   // WP：滑入 0.35 秒、滑出 0.25 秒

    private final TileGridView glyphs;
    private Listener listener;
    private Theme theme;
    private boolean mica;
    private ShellData.Notif n;
    private int count;
    private long t0;
    private boolean leaving;
    private float scale = 2.4f, h;
    private float safe = SAFE_PX / 2.4f;   // 安全距离换成设计像素
    private float rowH;           // 至少这么高（壁纸第一行卡片的下沿）
    private float top0;           // 居中排版时头一行行框的顶边
    private float headTop, titleBase, bodyBase, agoBase, actTop;   // 各行实际位置（设计像素）
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG), time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), text = new TextPaint(Paint.ANTI_ALIAS_FLAG), btn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), noise = new Paint(Paint.FILTER_BITMAP_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), ib = new RectF();
    // 排版结果（layoutText 算好，onDraw 直接画）
    private String titleStr = "", bodyStr, agoStr = "", appStr = "", icon = "bell";
    private float iconX, appX, appBase, titleX, bodyX, agoX;
    private final Runnable autoHide = this::dismiss;
    private float downY, downX;
    private int downBtn = -1;

    ToastView(Context c, TileGridView glyphs) {
        super(c);
        this.glyphs = glyphs;
        Typeface w400 = Fonts.sans(c.getAssets(), 400), w600 = Fonts.sans(c.getAssets(), 600);
        head.setTypeface(w600); head.setTextSize(10f); head.setLetterSpacing(0.08f);
        time.setTypeface(w400); time.setTextSize(10f);   // 左对齐画，横坐标按笔画右边贴安全线算
        title.setTypeface(w400); title.setTextSize(13f);
        text.setTypeface(w400); text.setTextSize(11f);
        btn.setTypeface(w400); btn.setTextSize(12f); btn.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        // 颗粒：设计稿的 feTurbulence 噪点（白色 6% 不透明度），120×120 平铺
        Bitmap g = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        Random rnd = new Random(7);
        int[] px = new int[120 * 120];
        for (int i = 0; i < px.length; i++) px[i] = (Math.round(rnd.nextFloat() * 0.06f * 2 * 255 * rnd.nextFloat()) << 24) | 0xFFFFFF;
        g.setPixels(px, 0, 120, 0, 0, 120, 120);
        noise.setShader(new BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        setVisibility(GONE);
    }

    void setListener(Listener l) { listener = l; }

    /** 横幅最低高度（设计像素）：和壁纸第一行卡片一样高；带操作按钮放不下时才更高。 */
    void setRowHeight(float rowHeight) { rowH = rowHeight; }

    boolean showing() { return n != null; }

    /** 横幅高度（设计像素）：show() 之后就有。悬浮层按它定窗口高度。 */
    float bannerH() { return h; }

    /** 来了一条新通知；横幅还在就累加成"N 条新消息"。 */
    void show(ShellData.Notif notif, Theme th, boolean onFace) {
        theme = th;
        mica = onFace;
        count = n != null && !leaving ? count + 1 : 1;
        n = notif;
        leaving = false;
        if (count == 1 || getVisibility() != VISIBLE) t0 = SystemClock.uptimeMillis();
        layoutText();
        setVisibility(VISIBLE);
        removeCallbacks(autoHide);
        postDelayed(autoHide, STAY);
        invalidate();
    }

    /** 表盘 / 别的页面切换时换材质。 */
    void setOnFace(boolean onFace) { if (mica != onFace) { mica = onFace; invalidate(); } }

    void dismiss() {
        if (n == null || leaving) return;
        removeCallbacks(autoHide);
        leaving = true;
        t0 = SystemClock.uptimeMillis();
        invalidate();
    }

    void hideNow() {
        removeCallbacks(autoHide);
        n = null; leaving = false;
        setVisibility(GONE);
        if (listener != null) listener.onRect(0, 0, 0, 0);
    }

    private String titleText() { return count > 1 ? count + " 条新消息" : (n.title.isEmpty() ? n.app : n.title); }

    private String bodyText() { return count > 1 ? (n.title.isEmpty() ? n.text : n.title + "：" + n.text) : n.text; }

    private boolean hasActions() { return count == 1 && !n.actions.isEmpty(); }

    private void layoutText() {
        String b = bodyText();
        float w = SIZE - safe * 2;
        bodyStr = b == null || b.isEmpty() ? null : TextUtils.ellipsize(b.replace('\n', ' '), text, w, TextUtils.TruncateAt.END).toString();
        titleStr = TextUtils.ellipsize(titleText(), title, w, TextUtils.TruncateAt.END).toString();
        agoStr = ShellData.ago(n.when);
        appStr = n.app.toUpperCase(Locale.ROOT);
        icon = ShellData.notifIcon(n.pkg);
        // 上下：内容在横幅里垂直居中，再按用户效果图（2026-10-23）往下挪：头一行 2、标题 5、正文 6 个屏幕像素
        float headDy = HEAD_DY_PX / scale, titleDy = TITLE_DY_PX / scale, bodyDy = BODY_DY_PX / scale;
        float content = PAD_T + HEAD + HEAD_GAP + TITLE_LH + (bodyStr != null ? BODY_LH : 0f) + (hasActions() ? ACT_GAP + ACT_H : 0f) + PAD_B;
        h = Math.max(content + (bodyStr != null ? bodyDy : titleDy), rowH);   // 带按钮时横幅跟着变高，按钮不挤底边
        top0 = PAD_T + (h - content) / 2f;
        headTop = top0 + headDy;
        float titleTop = top0 + HEAD + HEAD_GAP + titleDy, bodyTop = top0 + HEAD + HEAD_GAP + TITLE_LH + bodyDy;
        titleBase = TileGridView.baseline(title, titleTop, TITLE_LH);
        bodyBase = TileGridView.baseline(text, bodyTop, BODY_LH);
        actTop = (bodyStr != null ? bodyTop + BODY_LH : titleTop + TITLE_LH) + ACT_GAP;

        // 左右：按笔画（不是字框 / 图标的 24 格）把边缘放到效果图的位置上
        float iconL = 0f, iconR = HEAD, iconCy = headTop + HEAD / 2f;
        android.graphics.Path ip = TesseraIcon.get(icon);
        if (ip != null) {
            ip.computeBounds(ib, true);                     // 描边 2，向外半宽 1；24 格缩放到 HEAD
            iconL = (ib.left - 1f) * HEAD / 24f;
            iconR = (ib.right + 1f) * HEAD / 24f;
            iconCy = iconCentroid(ip, headTop);
        }
        iconX = BELL_LEFT_PX / scale - iconL;
        appX = iconX + iconR + 4f;
        // 应用名和图标按视觉重心（笔画覆盖量加权的纵向重心）对齐：不按行框（大写字母只占行框上半截），
        // 也不按外框中心（铃铛下面宽、还挂着铃舌，分量在下半截，按外框对齐看着字偏高）
        appBase = alignBaseline(head, appStr, iconCy);
        agoBase = alignBaseline(time, agoStr, iconCy);      // 右上的时间和 SHELL 在同一条线上（同样按视觉重心）
        android.graphics.Path p = new android.graphics.Path();
        textPath(time, agoStr, 0f, p);
        p.computeBounds(ib, true);
        agoX = TIME_RIGHT_PX / scale - ib.right;
        textPath(title, titleStr, 0f, p);
        p.computeBounds(ib, true);
        titleX = TITLE_LEFT_PX / scale - ib.left;
        if (bodyStr != null) {
            textPath(text, bodyStr, 0f, p);
            p.computeBounds(ib, true);
            bodyX = BODY_LEFT_PX / scale - ib.left;
        }
    }

    // 视觉重心要按屏幕上的实际像素渲染来量：设计像素的 10 号字在屏幕上是 24 像素，这么小的字字体微调会改字形、
    // 绘制时基线也会取整，按放大的轮廓算出来的重心和屏幕上看到的能差 1 像素

    /** 图标（24 格描边路径，线宽 2、圆头圆角，同 TileGridView.glyph）行框顶在 top 时，屏幕上的视觉重心 y（设计像素）。 */
    private float iconCentroid(android.graphics.Path icon, float top) {
        float k = HEAD / 24f * scale, topPx = top * scale;
        int t0 = (int) Math.floor(topPx) - 2, size = (int) Math.ceil(24f * k) + 6;
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.translate(2f, topPx - t0);                      // 和屏幕上同样的亚像素位置
        cv.scale(k, k);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeCap(Paint.Cap.ROUND);
        sp.setStrokeJoin(Paint.Join.ROUND);
        sp.setStrokeWidth(2f);
        cv.drawPath(icon, sp);
        float cy = centroidY(bm, size / 2f);
        bm.recycle();
        return (t0 + cy) / scale;
    }

    /** 一行字的基线放在哪儿（设计像素，正好落在整屏幕像素上），渲染出来的视觉重心离 targetY 最近。 */
    private float alignBaseline(TextPaint src, String s, float targetY) {
        if (s.isEmpty()) return targetY;
        big.set(src);
        big.setTextSize(src.getTextSize() * scale);       // 屏幕上的实际字号
        big.setColor(0xFF000000);
        Paint.FontMetrics fm = big.getFontMetrics();
        int base = (int) Math.ceil(-fm.top) + 2, w = (int) Math.ceil(big.measureText(s)) + 4, h = base + (int) Math.ceil(fm.bottom) + 2;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(bm).drawText(s, 2f, base, big);
        float cy = centroidY(bm, base) - base;              // 重心在基线上方多少像素（负数）
        bm.recycle();
        return Math.round(targetY * scale - cy) / scale;
    }

    /** 位图按不透明度加权的纵向重心（像素）；全空时返回 fallback。 */
    private static float centroidY(Bitmap bm, float fallback) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0, sy = 0;
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) row += px[y * w + x] >>> 24;
            sum += row;
            sy += row * (y + 0.5);
        }
        return sum > 0 ? (float) (sy / sum) : fallback;
    }

    private final TextPaint big = new TextPaint();
    private final android.graphics.Matrix shrink = new android.graphics.Matrix();

    /** 一行字的轮廓（起点 x = 0、基线在 baseline）。按 10 倍字号取再缩回来：这里的字号是设计像素（10~13），
     *  这么小取轮廓会被字体微调对齐到整数，字高能差出一截，按它对齐会偏 1 个屏幕像素。 */
    private void textPath(TextPaint src, String s, float baseline, android.graphics.Path out) {
        big.set(src);
        big.setTextSize(src.getTextSize() * 10f);
        big.getTextPath(s, 0, s.length(), 0f, 0f, out);
        shrink.setScale(0.1f, 0.1f);
        shrink.postTranslate(0f, baseline);
        out.transform(shrink);
    }

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) { scale = w / SIZE; safe = SAFE_PX / scale; }

    @Override
    protected void onDraw(Canvas c) {
        if (n == null) return;
        long now = SystemClock.uptimeMillis();
        float k;
        boolean again;
        if (leaving) { k = 1f - Curves.TOAST_LIFT.getInterpolation(Math.min(1f, (now - t0) / (float) LIFT)); again = now - t0 < LIFT; }
        else { k = Curves.TOAST_DROP.getInterpolation(Math.min(1f, (now - t0) / (float) DROP)); again = now - t0 < DROP; }
        float ty = -1.1f * h * (1f - k);                      // translateY(-110%) → 0
        if (listener != null) listener.onRect(0, Math.max(0, ty), SIZE, ty + h);
        if (leaving && !again) { hideNow(); return; }
        c.save();
        c.scale(scale, scale);
        c.translate(0, ty);
        int bg = theme.accent, fg = theme.onAccent;           // tone(accent)：--tile-bg / --tile-fg
        if (mica) {
            fill.setColor((bg & 0x00FFFFFF) | (Math.round(0.62f * 255) << 24));
            c.drawRect(0, 0, SIZE, h, fill);
            c.save(); c.scale(1f / scale, 1f / scale); c.drawRect(0, 0, SIZE * scale, h * scale, noise); c.restore();
        } else {
            fill.setColor(bg);
            c.drawRect(0, 0, SIZE, h, fill);
        }
        // 头：图标 + 应用名（大写）+ 右边时间；各行的横坐标在 layoutText 里按笔画算好
        glyphs.glyph(c, icon, iconX, headTop, HEAD, fg);
        head.setColor(fg);
        c.drawText(appStr, appX, appBase, head);
        time.setColor(fg);
        c.drawText(agoStr, agoX, agoBase, time);
        title.setColor(fg);
        c.drawText(titleStr, titleX, titleBase, title);
        if (bodyStr != null) {
            text.setColor(fg);
            c.drawText(bodyStr, bodyX, bodyBase, text);
        }
        if (hasActions()) {
            float y = actTop;
            int na = Math.min(2, n.actions.size());
            float w = (SIZE - safe * 2 - 4f * (na - 1)) / na;
            for (int i = 0; i < na; i++) {
                float x = safe + i * (w + 4f);
                r.set(x + 1f, y + 1f, x + w - 1f, y + ACT_H - 1f);
                float rr = ACT_H / 2f - 1f;
                if (i == downBtn) { fill.setColor(fg); c.drawRoundRect(r, rr, rr, fill); }
                stroke.setStrokeWidth(2f);
                stroke.setColor(fg);
                c.drawRoundRect(r, rr, rr, stroke);
                btn.setColor(i == downBtn ? bg : fg);
                c.drawText(TextUtils.ellipsize(n.actions.get(i), btn, w - 16f, TextUtils.TruncateAt.END).toString(), x + w / 2f, TileGridView.baseline(btn, y + (ACT_H - 16f) / 2f, 16f), btn);
            }
        }
        c.restore();
        if (again) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (n == null) return false;
        float x = e.getX() / scale, y = e.getY() / scale;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (y > h) return false;                          // 横幅以外的地方交给下面
            downX = x; downY = y;
            downBtn = buttonAt(x, y);
            invalidate();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int b = downBtn;
            downBtn = -1;
            invalidate();
            if (y - downY < -16f) { dismiss(); return true; }                    // 上滑收起
            if (Math.abs(x - downX) > 8f || Math.abs(y - downY) > 8f) return true;
            ShellData.Notif nn = n;
            int cnt = count;
            if (b >= 0 && b == buttonAt(x, y)) { if (listener != null) listener.onAction(nn, b); }
            else if (listener != null) listener.onOpen(nn, cnt);
            dismiss();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) { downBtn = -1; invalidate(); }
        return true;
    }

    private int buttonAt(float x, float y) {
        if (n == null || !hasActions()) return -1;
        float top = actTop;
        if (y < top || y > top + ACT_H) return -1;
        int na = Math.min(2, n.actions.size());
        float w = (SIZE - safe * 2 - 4f * (na - 1)) / na;
        for (int i = 0; i < na; i++) { float bx = safe + i * (w + 4f); if (x >= bx && x <= bx + w) return i; }
        return -1;
    }
}
