package com.tessera.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.Gravity;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 原生表盘：逐项移植自设计稿 bundle.js 的 WatchFace（radialTicks、numeral、FaceClock、即时活动），
 * 数值全部来自 tokens.css。所有绘制都在 200×200 的设计坐标里进行，再整体缩放到屏幕。
 *
 * 层次与 DOM 顺序一致：底色 → 即时活动（光池 + 内容）→ 秒轨道 → 内容区（日期 + 时间）。
 * 拆成三个子视图：只有秒轨道逐帧重画；底层和内容层只在活动/时间变化时重录，其余帧直接复用显示列表。
 */
final class FaceView extends FrameLayout {
    // ---------- tokens ----------
    private static final float SIZE = 200f;
    /** 真机屏幕圆角（shell.css 覆盖后的 --radius-screen），与 TesseraA11y 的遮罩一致。 */
    private static final float RADIUS = 28f;
    private static final float FACE_INSET = 20f, CLOCK_TOP = 32f, CLOCK_TALL = 110f, CLOCK_SHORT = 56f;
    private static final float STROKE = 5f, DIGIT = 24f, GAP = 6f, COLON_GAP = 5f;
    private static final float DOT = Math.max(4.5f, STROKE * 1.4f);
    private static final int COUNT = 120;                 // 每秒两根：整秒长、半秒短
    private static final float FRONT = 1.5f;              // 点亮的前沿约 1.5 根刻度宽
    private static final long CLEAR = 400;                // 整分钟时走满的一圈 400ms 淡出
    private static final int FACE_INK = 0xFFF2F4F6, FACE_MUTED = 0xFF9AA3AD;
    private static final int TICK_OFF = 0xFF3B424A, FACE_POOL = 0x992B343D;
    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    // 设计稿的动效曲线

    /** 即时活动：标题行 + 一个大数值 + 右侧最多两行小字。 */
    static final class Activity {
        final String icon, title, value;
        final List<String> lines;

        Activity(String icon, String title, String value, List<String> lines) {
            this.icon = icon; this.title = title; this.value = value; this.lines = lines;
        }

        boolean sameAs(Activity o) {
            return o != null && TextUtils.equals(icon, o.icon) && TextUtils.equals(title, o.title)
                    && TextUtils.equals(value, o.value) && lines.equals(o.lines);
        }
    }

    // ---------- 图片表盘（用户给的拼板底图 + 居中数字时间） ----------
    /** 当前表盘（FaceStyles.SECONDS / TILES / DATA / BIG / PANELS）。PANELS = 用户给的拼板图片表盘。 */
    private int style = FaceStyles.DATA;

    private boolean isPanels() { return style == FaceStyles.PANELS; }

    /** 新的三种表盘（设计稿 2026-10-23）：整张都画在底层，有秒，每秒画一次。 */
    private boolean isNewStyle() { return style == FaceStyles.TILES || style == FaceStyles.DATA || style == FaceStyles.BIG; }
    private android.graphics.Bitmap panelsBmp;
    private PanelsFace panelsFace;
    private int panelsMinute = -1;

    PanelsFace panels() { return panelsFace; }

    /** 壁纸第一行卡片的下沿（横幅与它对齐）。 */
    float panelsRowBottom() {
        if (panelsFace == null) panelsFace = new PanelsFace(getContext());
        return panelsFace.firstRowBottom() * SIZE;
    }

    // ---------- 新表盘（FaceStyles）的数据 ----------
    private FaceStyles styles;
    private Theme faceTheme = new Theme(true, "slate");
    private FaceStyles.Live faceLive;
    /** 数据表盘的卡片区：顺序、拿起拖动、即时活动、B ↔ C 变形都在这里（设计稿 FaceComplications）。 */
    private final FaceCards faceCtl = new FaceCards(() -> {
        if (style == FaceStyles.DATA && FaceView.this.backLayer != null) FaceView.this.backLayer.invalidate();
        if (FaceView.this.ghostLayer != null) FaceView.this.ghostLayer.invalidate();
    });

    FaceCards cards() { return faceCtl; }

    private View ghostLayer;

    /**
     * 拿着的那张卡单独一层，由 NativeShell 加在根布局最上面：从开始屏拿磁贴过来时表盘本身还在往下落、开始屏还在翻走，
     * 卡片要盖在它们上面、只跟手指走（设计稿里它也是 z-index 40 的独立元素）。
     */
    View ghostLayer() {
        if (ghostLayer == null) ghostLayer = new View(getContext()) {
            @Override
            protected void onDraw(Canvas c) {
                long now = android.os.SystemClock.uptimeMillis();
                FaceStyles.Ghost g = faceCtl.ghost(now, faceTheme);
                if (g == null || styles == null) return;
                float k = getWidth() / SIZE;
                c.save();
                c.scale(k, k);
                if (g.alpha > 0f) styles.drawGhost(c, g, faceTheme);
                c.restore();
                postInvalidateOnAnimation();               // 拿着时每帧跟手；放下后 ghost 变 null 就停
            }
        };
        return ghostLayer;
    }

    int style() { return style; }

    void invalidateFace() { invalidateAll(); }

    /** 调试：固定表盘上显示的时间（和设计稿截图逐像素对比用），null = 真实时间。 */
    static Calendar debugTime;

    static Calendar now() { return debugTime != null ? (Calendar) debugTime.clone() : Calendar.getInstance(); }

    void setStyle(int s) {
        if (s == style) return;
        style = s;
        if (isPanels()) { ensurePanelsTime(); updatePanelsTime(); }
        if (panelsTime != null) panelsTime.setVisibility(isPanels() && blurBottom <= 0 ? View.VISIBLE : View.GONE);
        invalidateAll();
    }

    /** 表盘永远是深色（设计稿的 face-* 色），磁贴 / 卡片 / 主题色跟着用户选的主题色。 */
    void setFaceTheme(Theme th) { faceTheme = th; if (isNewStyle()) backLayer.invalidate(); }

    void setCards(List<FaceStyles.Card> cards) { faceCtl.setCards(cards); }

    void setLive(FaceStyles.Live l) {
        if (l == null ? faceLive == null : l.sameAs(faceLive)) return;
        faceLive = l;
        faceCtl.setLive(l);
    }

    /** 数据表盘这一帧（有卡片或即时活动是 C 布局，都没有就是 B；中间的变形由 FaceCards 算）。 */
    private FaceStyles.DataFrame dataFrame() { return faceCtl.frame(android.os.SystemClock.uptimeMillis(), faceTheme); }

    /** 把任意一种表盘画进给定画布（200×200 设计坐标），换表盘画廊截图用。 */
    void renderFace(Canvas c, int s, Calendar now) {
        c.drawColor(0xFF000000);
        switch (s) {
            case FaceStyles.TILES: styles.drawTiles(c, now, faceTheme); break;
            case FaceStyles.BIG: styles.drawBig(c, now, faceTheme); break;
            case FaceStyles.DATA: styles.drawData(c, now, faceTheme, dataFrame()); break;
            case FaceStyles.PANELS: {
                if (panelsFace == null) panelsFace = new PanelsFace(getContext());
                panelsFace.draw(c, SIZE, now);
                drawBezelShadow(c);
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setTextSize(SIZE * 0.34f);
                tp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                tp.setColor(0xFFF4F7FA);
                tp.setTextAlign(Paint.Align.CENTER);
                tp.setLetterSpacing(-0.035f);
                tp.setShadowLayer(SIZE * 0.035f, 0f, SIZE * 0.012f, 0xCC000000);
                Paint.FontMetrics fm = tp.getFontMetrics();
                c.save();
                c.scale(1f, 1.14f, SIZE / 2f, SIZE / 2f);
                c.drawText(now.get(Calendar.HOUR_OF_DAY) + ":" + pad(now.get(Calendar.MINUTE)), SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2f, tp);
                c.restore();
                break;
            }
            default:
                drawTicks(c, now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND));
                c.save();
                c.clipRect(FACE_INSET, FACE_INSET, SIZE - FACE_INSET, SIZE - FACE_INSET);
                drawDate(c, now);
                drawClock(c, now);
                c.restore();
        }
    }

    /** 图片表盘的时间：用原生 TextView 画（自己往硬件画布上画字不显示）。 */
    private TextView panelsTime;

    private void ensurePanelsTime() {
        if (panelsTime != null) return;
        panelsTime = new TextView(getContext());
        panelsTime.setGravity(Gravity.CENTER);
        panelsTime.setTextColor(0xFFF4F7FA);
        panelsTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        panelsTime.setShadowLayer(22f, 0f, 8f, 0xCC000000);
        panelsTime.setLetterSpacing(-0.035f);
        panelsTime.setScaleY(1.14f);                    // 拉长：竖着拉伸一点，更接近设计稿的字形比例
        panelsTime.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // 22px 的模糊阴影每帧重算很贵，单独一层只画一次
        addView(panelsTime, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panelsTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, getWidth() * 0.34f);
        updatePanelsTime();
    }

    void updatePanelsTime() {
        if (panelsTime == null) return;
        Calendar n = Calendar.getInstance();
        panelsTime.setText(n.get(Calendar.HOUR_OF_DAY) + ":" + (n.get(Calendar.MINUTE) < 10 ? "0" : "") + n.get(Calendar.MINUTE));
    }

    /** 双指按住：时间逐渐隐藏。 */
    void setPanelsTimeHidden(boolean hidden) {
        if (panelsTime == null) return;
        panelsTime.animate().alpha(hidden ? 0f : 1f).setDuration(400).start();
    }

    /** 点一下卡片：翻转换色。坐标是 CSS 像素（200 宽）。 */
    boolean tapPanel(float cssX, float cssY) {
        if (panelsFace == null || !isPanels()) return false;
        boolean hit = panelsFace.tap(cssX, cssY, SIZE);
        invalidateAll();                                  // 静止时不逐帧画，翻转要叫醒
        return hit;
    }

    /** 同步判断这点是不是落在某张卡片上（不触发翻转）。 */
    boolean hitPanel(float cssX, float cssY) {
        return panelsFace != null && isPanels() && panelsFace.hitTest(cssX, cssY, SIZE);
    }

    /** 双指点击：进入/退出"可翻"状态。进入时时间逐渐隐藏；退出时时间回来、所有卡片翻回原色。 */
    void togglePanelsMode() {
        if (panelsFace == null || !isPanels()) return;
        panelsFace.interactive = !panelsFace.interactive;
        android.util.Log.i("Tessera", "togglePanelsMode interactive=" + panelsFace.interactive + " time=" + (panelsTime != null));
        setPanelsTimeHidden(panelsFace.interactive);
        if (!panelsFace.interactive) panelsFace.resetAll();
        invalidateAll();
    }

    /** 强制回到"非可翻"状态：时间显示、卡片全部翻回原色（进桌面时调用）。 */
    void resetPanelsMode() {
        if (panelsFace == null) return;
        if (panelsFace.interactive) {
            panelsFace.interactive = false;
            panelsFace.resetAll();
        }
        setPanelsTimeHidden(false);
    }

    /** 双指点击：所有改过色的卡片翻回原色。 */
    void resetPanels() { if (panelsFace != null) { panelsFace.resetAll(); invalidateAll(); } }
    private final Paint panelsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bigTime = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private void loadPanels() {
        if (panelsBmp != null) return;
        try (java.io.InputStream in = getContext().getAssets().open("face/panels.png")) {
            panelsBmp = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) { }
    }

    // ---------- 绘制资源 ----------
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint poolPaint = new Paint();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint linesPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float[] tx1 = new float[COUNT], ty1 = new float[COUNT], tx2 = new float[COUNT], ty2 = new float[COUNT];
    private final float[] batchOn = new float[COUNT * 4], batchOff = new float[COUNT * 4];

    // ---------- 状态 ----------
    private float scale = 1f;
    private boolean resumed, shown = true;
    private String clockText = "";
    private float clockH = CLOCK_TALL - STROKE;           // 中心线高度（会动画）
    private ValueAnimator clockAnim;
    private final List<Glyph> glyphs = new ArrayList<>();
    private float inkL, inkR, builtH = -1;
    private String builtText = null;

    private Activity activity;          // 当前要显示的（null = 没有）
    private Activity shownActivity;     // 正在画的（离场时保留到推出画面）
    private float actOffset = 1f;       // 0 = 到位，1 = 在屏幕下面（translateY 52%）
    private ValueAnimator actAnim;

    private ValueAnimator sinkAnim;
    private float sink;                 // 0 = 表盘在最上面，1 = 被开始屏/通知中心盖住（下沉）

    private static final class Glyph {
        final Path path;     // 数字的中心线路径（已平移到所在格子）
        final boolean dot;
        final float cx;      // 冒号的圆心 x

        Glyph(Path path) { this.path = path; this.dot = false; this.cx = 0; }

        Glyph(float cx) { this.path = null; this.dot = true; this.cx = cx; }
    }

    private final Layer backLayer, tickLayer, frontLayer, blurLayer;
    private int builtMinuteKey = -1;

    // ---------- 通知中心 / 表盘横幅的磨砂（mica：blur 24px + saturate 140%） ----------
    // 表盘是原生画的，WebView 里的 backdrop-filter 看不到它，只能在这里模糊：通知中心出现时把表盘画进一张小图，
    // 高斯模糊后作为最上面一层，裁到通知中心当前盖住的范围（页面每帧报下边缘位置）。
    private static final int BLUR_PX = 64;                 // 快照边长：200 设计像素缩到 64，模糊后放大看不出颗粒
    private static final float MICA_BLUR = 24f, MICA_SATURATE = 1.4f;   // bundle.css 里 .ts-toast 最后生效的是 saturate(140%)
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap blurBmp;
    private float blurBottom;                              // 通知中心下边缘占屏高的比例，0 = 没有
    private boolean blurFresh;

    /** 一层：把绘制转回外层对应的方法，坐标统一缩放到 200×200。 */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) { super(c); this.kind = kind; }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(scale, scale);
            if (kind == 0) drawBack(canvas);
            else if (kind == 1) drawTickLayer(canvas);
            else if (kind == 2) drawFront(canvas);
            else drawBlur(canvas);
            canvas.restore();
            if (!running()) return;
            if (kind == 0 && isNewStyle()) {
                // 新表盘有秒：整秒时画一次就够（以前所有表盘都逐帧画，静止也 60 帧/秒）；卡片区在动时逐帧画
                if (style == FaceStyles.DATA && faceCtl.running(android.os.SystemClock.uptimeMillis())) postInvalidateOnAnimation();
                else postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000 + 5);
            } else if (kind == 1) {
                if (style == FaceStyles.SECONDS || (isPanels() && panelsFace != null && panelsFace.busy())) postInvalidateOnAnimation();
                else if (isPanels()) postInvalidateDelayed(60000 - System.currentTimeMillis() % 60000 + 20);
            }
        }
    }

    FaceView(Context context) {
        super(context);
        setWillNotDraw(true);
        backLayer = new Layer(context, 0);
        tickLayer = new Layer(context, 1);
        frontLayer = new Layer(context, 2);
        addView(backLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(tickLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(frontLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        blurLayer = new Layer(context, 3);
        addView(blurLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(MICA_SATURATE);
        blurPaint.setColorFilter(new ColorMatrixColorFilter(sat));
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);              // .ts-tick { stroke-linecap: round }
        digitPaint.setStyle(Paint.Style.STROKE);
        digitPaint.setColor(FACE_INK);
        digitPaint.setStrokeWidth(STROKE);
        digitPaint.setStrokeCap(Paint.Cap.BUTT);              // 端点平切
        digitPaint.setStrokeJoin(Paint.Join.MITER);           // 转角是尖的
        digitPaint.setStrokeMiter(2.5f);
        dotPaint.setColor(FACE_INK);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setColor(FACE_INK);

        Typeface w600 = Fonts.sans(context.getAssets(), 600), w300 = Fonts.sans(context.getAssets(), 300);
        datePaint.setTypeface(w600); datePaint.setTextSize(13f); datePaint.setColor(FACE_MUTED);
        datePaint.setTextAlign(Paint.Align.CENTER);
        headPaint.setTypeface(w600); headPaint.setTextSize(13f); headPaint.setColor(FACE_INK);
        valuePaint.setTypeface(w300); valuePaint.setTextSize(26f); valuePaint.setColor(FACE_INK);
        linesPaint.setTypeface(w600); linesPaint.setTextSize(11f); linesPaint.setColor(FACE_MUTED);
        linesPaint.setTextAlign(Paint.Align.RIGHT);

        styles = new FaceStyles(context.getAssets());
        buildTicks();
        poolPaint.setShader(new LinearGradient(0, SIZE, 0, SIZE * 0.52f,
                new int[] {FACE_POOL, FACE_POOL, 0x002B343D}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    // ---------- 对外 ----------

    /** Activity 前台/后台。只有前台且没被盖住时才逐帧走秒。 */
    void setResumed(boolean r) { resumed = r; invalidateAll(); }

    /** 是否露出来（开始屏、通知中心完全盖住时为 false，停止走秒省电）。 */
    void setShown(boolean s) {
        if (s == shown) return;                     // 已经是这个状态：别把所有层重录一遍（开始上滑那一帧会顿一下）
        shown = s; if (isPanels()) { ensurePanelsTime(); updatePanelsTime(); } invalidateAll(); }

    /** 下沉进度：被盖住时下移 11、缩到 0.93、不透明度 0.85（与 .sh-face-layer.is-sunk 一致）。 */
    void setSink(float target, int durationMs) {
        if (sinkAnim != null) sinkAnim.cancel();
        if (durationMs <= 0) { applySink(target, target); return; }
        final float from = sink;
        sinkAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(durationMs);
        sinkAnim.addUpdateListener(a -> {
            float k = (float) a.getAnimatedValue();
            applySink(from + (target - from) * Curves.FACE_SINK.getInterpolation(k), from + (target - from) * Curves.FACE_SINK_FADE.getInterpolation(k));
        });
        sinkAnim.start();
    }

    private void applySink(float pTransform, float pAlpha) {
        sink = pTransform;
        setPivotX(getWidth() / 2f);
        setPivotY(0f);                                        // transform-origin: center top
        setTranslationY(pTransform * 11f * scale);
        setScaleX(1f - 0.07f * pTransform);
        setScaleY(1f - 0.07f * pTransform);
        setAlpha(1f - 0.15f * pAlpha);
        if (blurActive()) blurLayer.invalidate();            // 裁切范围是屏幕坐标，表盘一动就要重算
    }

    /** 通知中心下边缘的位置（占屏高比例，0..1）；0 = 通知中心不在了。 */
    void setBlur(float bottom) {
        bottom = Math.max(0f, Math.min(1f, bottom));
        boolean wasAny = blurActive();
        if (bottom > 0 && !blurFresh) buildBlur();             // 每次拉出来都重新拍一张，时间、活动都是当下的
        boolean on = bottom > 0;
        blurBottom = bottom;
        // 磨砂盖住表盘时，把原生的大号时间藏起来——否则它会清晰地浮在糊掉的底图上面
        if (panelsTime != null) panelsTime.setVisibility(on || !isPanels() ? View.INVISIBLE : View.VISIBLE);
        blurChanged(wasAny);
    }

    // 表盘上的通知横幅（设计稿是 mica：blur 24px + saturate 140%，底色 62%）：同一张快照，画在横幅底下
    private final android.graphics.RectF toastRect = new android.graphics.RectF();
    private float toastRadius;
    private boolean toastOn;

    /** 横幅范围（设计像素，屏幕坐标）和圆角；宽或高为 0 表示没有横幅。 */
    void setToastBlur(float l, float t, float r, float b, float radius) {
        boolean wasAny = blurActive();
        boolean on = r - l > 0.5f && b - t > 0.5f;
        if (on && !blurFresh) buildBlur();
        toastOn = on;
        if (on) { toastRect.set(l, t, r, b); toastRadius = radius; }
        // 图片表盘的大号时间是叠在磨砂层上面的 TextView：横幅那一块要裁掉，否则清晰的数字会透过半透明横幅露出来
        // （磨砂快照里已经拍进了时间，糊掉的那份照样在）
        if (panelsTime != null) {
            if (on) {
                // 裁切范围是 TextView 变换前的坐标；它绕中心纵向拉伸了 1.14，要反算回去
                float py = panelsTime.getPivotY(), sy = panelsTime.getScaleY();
                int cut = Math.round(py + (b * scale - panelsTime.getTop() - py) / sy);
                panelsTime.setClipBounds(new android.graphics.Rect(0, Math.max(0, cut), panelsTime.getWidth(), panelsTime.getHeight()));
            } else {
                panelsTime.setClipBounds(null);
            }
        }
        blurChanged(wasAny);
    }

    private boolean blurActive() { return blurBottom > 0 || toastOn; }

    private void blurChanged(boolean wasAny) {
        if (!blurActive()) { blurFresh = false; removeCallbacks(blurMinute); }   // 两块都没了：下次出现重新拍
        else if (!wasAny) scheduleBlurMinute();
        blurLayer.invalidate();
        if (wasAny != blurActive()) invalidate();              // 重新取 hasOverlappingRendering
    }

    /** 磨砂盖着的时候表盘不走秒（被盖住就停了），快照里的时间会停在拉出来那一刻：每到整分钟重拍一张。 */
    private final Runnable blurMinute = new Runnable() {
        @Override
        public void run() {
            if (!blurActive()) return;
            updatePanelsTime();                                  // 图片表盘的时间是 TextView，先更新文字快照才拍得到
            buildBlur();
            blurLayer.invalidate();
            scheduleBlurMinute();
        }
    };

    private void scheduleBlurMinute() {
        removeCallbacks(blurMinute);
        postDelayed(blurMinute, 60000 - System.currentTimeMillis() % 60000 + 50);
    }

    /** 把表盘三层按当前状态画进一张 BLUR_PX 见方的小图，再做高斯模糊。 */
    /** 手指按到表盘上时先把磨砂底图拍好（往下拉出通知中心的第一帧就不用现拍现糊，原来要在主线程上卡 30ms）。 */
    void prepareBlur() { buildBlur(); }

    private void buildBlur() {
        if (blurBmp == null) blurBmp = Bitmap.createBitmap(BLUR_PX, BLUR_PX, Bitmap.Config.ARGB_8888);
        blurBmp.eraseColor(0xFF000000);
        Canvas c = new Canvas(blurBmp);
        c.scale(BLUR_PX / SIZE, BLUR_PX / SIZE);
        drawBack(c);
        if (isPanels() && panelsTime != null) {   // 把大号时间也拍进快照，糊出来的一圈才完整
            String t = panelsTime.getText().toString();
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize(SIZE * 0.34f);
            tp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            tp.setColor(0xFFF4F7FA);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setLetterSpacing(-0.035f);
            Paint.FontMetrics fm = tp.getFontMetrics();
            c.save();
            c.scale(1f, 1.14f, SIZE / 2f, SIZE / 2f);   // 和 TextView 的 scaleY 对齐
            c.drawText(t, SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2f, tp);
            c.restore();
        }
        if (style == FaceStyles.SECONDS) {       // 图片表盘只磨砂底图；新表盘 drawBack 已经整张画了
            Calendar now = Calendar.getInstance();
            drawTicks(c, now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND));
            c.save();
            c.clipRect(FACE_INSET, FACE_INSET, SIZE - FACE_INSET, SIZE - FACE_INSET);
            drawDate(c, now);
            drawClock(c, now);
            c.restore();
        }
        int[] px = new int[BLUR_PX * BLUR_PX];
        blurBmp.getPixels(px, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX);
        gaussian(px, BLUR_PX, MICA_BLUR * BLUR_PX / SIZE);
        blurBmp.setPixels(px, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX);
        BitmapShader shader = new BitmapShader(blurBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(SIZE / BLUR_PX, SIZE / BLUR_PX);
        shader.setLocalMatrix(m);
        blurPaint.setShader(shader);
        blurFresh = true;
    }

    /** CSS blur(σ) 的近似：三遍盒式模糊（宽度按 σ 换算），横竖各做一次，边缘取最近像素。 */
    private static void gaussian(int[] px, int n, float sigma) {
        int r = Math.max(1, Math.round((float) (Math.sqrt(12.0 * sigma * sigma / 3 + 1) - 1) / 2f));
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, n, r, true);
            boxBlur(tmp, px, n, r, false);
        }
    }

    private static void boxBlur(int[] src, int[] dst, int n, int r, boolean horizontal) {
        int w = 2 * r + 1;
        for (int line = 0; line < n; line++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = src[idx(line, Math.max(0, Math.min(n - 1, k)), n, horizontal)];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < n; i++) {
                dst[idx(line, i, n, horizontal)] = ((sa / w) << 24) | ((sr / w) << 16) | ((sg / w) << 8) | (sb / w);
                int out = src[idx(line, Math.max(0, i - r), n, horizontal)];
                int in = src[idx(line, Math.min(n - 1, i + r + 1), n, horizontal)];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }

    private static int idx(int line, int i, int n, boolean horizontal) { return horizontal ? line * n + i : i * n + line; }

    /** 磨砂层：通知中心 / 横幅盖住的范围是屏幕坐标，这里换算回表盘（可能正在下沉缩放）自己的坐标。 */
    private void drawBlur(Canvas canvas) {
        if (!blurActive() || blurPaint.getShader() == null) return;
        float sx = getScaleX(), sy = getScaleY(), ty = getTranslationY() / scale;   // pivot：水平居中、顶边
        if (blurBottom > 0) {                    // 通知中心：整屏宽，从顶到它的下边缘；下边两角跟屏幕圆角一致
            float bottom = (blurBottom * SIZE - ty) / sy;
            float half = SIZE / 2f / sx;
            float r = RADIUS / sx;
            canvas.drawRoundRect(SIZE / 2f - half, -r - 1f, SIZE / 2f + half, bottom, r, r, blurPaint);
        }
        if (toastOn) {                           // 横幅：它自己的圆角矩形
            canvas.drawRoundRect(SIZE / 2f + (toastRect.left - SIZE / 2f) / sx, (toastRect.top - ty) / sy,
                    SIZE / 2f + (toastRect.right - SIZE / 2f) / sx, (toastRect.bottom - ty) / sy,
                    toastRadius / sx, toastRadius / sy, blurPaint);
        }
    }

    /** 即时活动出现 / 更新 / 消失（null）。 */
    void setActivity(Activity a) {
        if (a == null && activity == null) return;
        if (a != null && a.sameAs(activity)) return;
        boolean wasOn = activity != null;
        activity = a;
        if (a != null) shownActivity = a;
        boolean on = a != null;
        if (on == wasOn) { backLayer.invalidate(); return; }
        animateClock(on);
        if (actAnim != null) actAnim.cancel();
        final float from = actOffset;
        actAnim = ValueAnimator.ofFloat(0f, 1f);
        if (on) {           // 0.35 秒（WP 滑入），晚 0.12 秒起步（时间先开始变矮）
            actAnim.setDuration(350).setStartDelay(120);
            actAnim.addUpdateListener(v -> { actOffset = from * (1 - Curves.FACE_ACT_IN.getInterpolation((float) v.getAnimatedValue())); backLayer.invalidate(); });
        } else {            // 0.25 秒（WP 滑出）
            actAnim.setDuration(250);
            actAnim.addUpdateListener(v -> {
                actOffset = from + (1 - from) * Curves.FACE_ACT_OUT.getInterpolation((float) v.getAnimatedValue());
                if (actOffset >= 1f && activity == null) shownActivity = null;
                backLayer.invalidate();
            });
        }
        actAnim.start();
    }

    // ---------- 时间伸缩：变矮立刻开始，拉长等 200ms 让活动先离开 ----------

    private void animateClock(boolean compact) {
        float to = (compact ? CLOCK_SHORT : CLOCK_TALL) - STROKE;
        if (clockAnim != null) clockAnim.cancel();
        final float from = clockH;
        if (from == to) return;
        clockAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(350);
        clockAnim.setStartDelay(compact ? 0 : 200);
        clockAnim.addUpdateListener(v -> { clockH = from + (to - from) * Curves.FACE_CLOCK.getInterpolation((float) v.getAnimatedValue()); frontLayer.invalidate(); });
        clockAnim.start();
    }

    // ---------- 尺寸 ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = Math.min(w, h) / SIZE;
        applySink(sink, sink);
        // 原生桌面启动时没人调 setShown：量好尺寸再建大号时间（布局过程中不能 addView，放到下一轮）
        if (panelsTime != null) panelsTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.34f);
        else if (isPanels()) post(() -> { if (isPanels()) { ensurePanelsTime(); updatePanelsTime(); } });
    }

    private boolean running() { return resumed && shown && isAttachedToWindow(); }

    // ---------- 绘制（三层） ----------
    // 屏幕四角由 TesseraA11y 的遮罩盖住、底下也是黑的，所以不再做圆角裁切（clipPath 每帧要走模板缓冲，最贵）。

    /** 底层：即时活动（底色由 MainActivity 的根布局铺黑，这里不再重复整屏填充）。只在活动推入/推出、内容变化时重录。 */
    private void drawBack(Canvas canvas) {
        if (isNewStyle()) {                                // 新表盘整张都在这一层
            Calendar now = now();
            if (style == FaceStyles.TILES) styles.drawTiles(canvas, now, faceTheme);
            else if (style == FaceStyles.BIG) styles.drawBig(canvas, now, faceTheme);
            else styles.drawData(canvas, now, faceTheme, dataFrame());
            return;
        }
        if (isPanels()) {
            if (panelsFace == null) panelsFace = new PanelsFace(getContext());
            // 画布已经按 design→px 缩放过（SIZE=200 是这个坐标系），所以这里传设计单位，不是像素
            panelsFace.draw(canvas, SIZE, Calendar.getInstance());
            drawBezelShadow(canvas);
            return;
        }

        if (style == FaceStyles.SECONDS && shownActivity != null && actOffset < 1f) drawActivity(canvas, shownActivity, actOffset * SIZE * 0.52f);
    }

    /** 表圈阴影：和 toast 一样是"贴边一小圈柔光"——1px 硬边 + 2/6px 两层晕开，不是四条渐变带。 */
    private void drawBezelShadow(Canvas canvas) {
        if (bezelBmp == null) {
            int n = (int) SIZE;
            bezelBmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(bezelBmp);
            android.graphics.RectF rr = new android.graphics.RectF(1.5f, 1.5f, n - 1.5f, n - 1.5f);
            // 第一层：等效 0 1px 2px #000000D9
            Paint p1 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p1.setStyle(Paint.Style.STROKE);
            p1.setStrokeWidth(2.5f);
            p1.setColor(0xD9000000);
            p1.setMaskFilter(new android.graphics.BlurMaskFilter(1.5f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            bc.drawRoundRect(rr, RADIUS - 1.5f, RADIUS - 1.5f, p1);
            // 第二层：等效 0 2px 6px #000000B3
            Paint p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p2.setStyle(Paint.Style.STROKE);
            p2.setStrokeWidth(4f);
            p2.setColor(0xB3000000);
            p2.setMaskFilter(new android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            bc.drawRoundRect(rr, RADIUS - 1.5f, RADIUS - 1.5f, p2);
        }
        canvas.drawBitmap(bezelBmp, null, new android.graphics.RectF(0, 0, SIZE, SIZE), null);
    }

    private Bitmap bezelBmp;

    /** 中层：秒轨道，逐帧重画；顺带发现换了分钟就让内容层重录。 */
    private void drawTickLayer(Canvas canvas) {
        if (isPanels()) {
            Calendar now = Calendar.getInstance();
            if (panelsFace != null && (panelsFace.busy() || minuteKey(now) != panelsMinute)) {
                // 换分钟：大号时间是单独的 TextView，要显式更新文字（以前只重画底图，停在表盘上时间就不走了）。
                // 不能在 draw 里直接 setText（会触发重新布局），放到下一轮主线程
                if (minuteKey(now) != panelsMinute) post(this::updatePanelsTime);
                panelsMinute = minuteKey(now);
                backLayer.postInvalidate();
            }
            return;
        }
        if (style != FaceStyles.SECONDS) return;
        Calendar now = Calendar.getInstance();
        drawTicks(canvas, now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND));
        if (minuteKey(now) != builtMinuteKey) frontLayer.postInvalidate();
    }

    /** 上层：日期 + 时间，裁在内容区（离屏幕边 face-inset）里。只在换分钟、时间伸缩时重录。 */
    private void drawFront(Canvas canvas) {
        Calendar now = Calendar.getInstance();
        builtMinuteKey = minuteKey(now);
        if (style != FaceStyles.SECONDS) return;   // 只有秒轨表盘用这一层；图片表盘的时间画在底图层
        canvas.clipRect(FACE_INSET, FACE_INSET, SIZE - FACE_INSET, SIZE - FACE_INSET);
        drawDate(canvas, now);
        drawClock(canvas, now);
    }

    /** 居中大号时间：带下投影，读起来像浮在底图上方。 */
    private void drawBigTime(Canvas canvas, Calendar now) {
        String time = now.get(Calendar.HOUR_OF_DAY) + ":" + pad(now.get(Calendar.MINUTE));
        float w = SIZE * scale;
        bigTime.setTextSize(w * 0.26f);
        bigTime.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        bigTime.setColor(0xFFF4F7FA);
        bigTime.setLetterSpacing(-0.02f);
        bigTime.setTextAlign(Paint.Align.CENTER);
        bigTime.setShadowLayer(w * 0.035f, 0f, w * 0.012f, 0xCC000000);
        bigTime.setStyle(Paint.Style.FILL);
        Paint.FontMetrics fm = bigTime.getFontMetrics();
        float cy = w / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(time, w / 2f, cy, bigTime);
    }

    private static int minuteKey(Calendar c) {
        return c.get(Calendar.DAY_OF_YEAR) * 1440 + c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private void invalidateAll() {
        backLayer.invalidate();
        tickLayer.invalidate();
        frontLayer.invalidate();
    }

    /** 三层基本不重叠、底下又是黑色，下沉时的半透明可以分层各自乘，不用整块离屏合成。
     *  只有磨砂层盖着时例外：要整体乘透明度，否则下面清晰的数字会透过磨砂露出来。 */
    @Override
    public boolean hasOverlappingRendering() { return blurActive(); }

    /** 秒轨道：跑马灯。每根刻度的“点亮量”按连续时间算，粗细 1→2、颜色 tick-off→ink 平滑插值。 */
    private void drawTicks(Canvas canvas, long msInMinute) {
        float pos = msInMinute / (60000f / COUNT);
        int nOn = 0, nOff = 0;
        for (int i = 0; i < COUNT; i++) {
            float a = clamp((pos - i) / FRONT);
            if (msInMinute < CLEAR && i > pos) a = Math.max(a, 1f - msInMinute / (float) CLEAR);   // 上一圈正在淡出
            float e = a * a * (3 - 2 * a);
            if (e <= 0.005f) { nOff = put(batchOff, nOff, i); continue; }
            if (e >= 0.995f) { nOn = put(batchOn, nOn, i); continue; }
            tickPaint.setColor(lerpColor(TICK_OFF, FACE_INK, e));
            tickPaint.setStrokeWidth(1f + e);
            canvas.drawLine(tx1[i], ty1[i], tx2[i], ty2[i], tickPaint);
        }
        if (nOff > 0) { tickPaint.setColor(TICK_OFF); tickPaint.setStrokeWidth(1f); canvas.drawLines(batchOff, 0, nOff, tickPaint); }
        if (nOn > 0) { tickPaint.setColor(FACE_INK); tickPaint.setStrokeWidth(2f); canvas.drawLines(batchOn, 0, nOn, tickPaint); }
    }

    private int put(float[] batch, int n, int i) {
        batch[n] = tx1[i]; batch[n + 1] = ty1[i]; batch[n + 2] = tx2[i]; batch[n + 3] = ty2[i];
        return n + 4;
    }

    /** 日期：face-date，13px 600，face-muted，内容区顶部下 4px，居中。 */
    private void drawDate(Canvas canvas, Calendar now) {
        String date = (now.get(Calendar.MONTH) + 1) + "月" + now.get(Calendar.DAY_OF_MONTH) + "日" + WEEK[now.get(Calendar.DAY_OF_WEEK) - 1];
        canvas.drawText(date, SIZE / 2f, baseline(datePaint, FACE_INSET + 4f, 18f), datePaint);
    }

    /** 画出来的时间（Tessera 数字）：中心线路径，按当前高度生成，按字形居中。 */
    private void drawClock(Canvas canvas, Calendar now) {
        String text = now.get(Calendar.HOUR_OF_DAY) + ":" + pad(now.get(Calendar.MINUTE));
        if (!text.equals(builtText) || clockH != builtH) buildClock(text, clockH);
        float vw = inkR - inkL;
        float left = SIZE / 2f - vw / 2f;                    // svg 在内容区里水平居中
        float top = FACE_INSET + CLOCK_TOP;
        canvas.save();
        canvas.translate(left - inkL, top);
        for (Glyph g : glyphs) {
            if (g.dot) {
                float off = Math.min(0.17f * clockH, 14f);
                float cy = STROKE / 2f + 0.54f * clockH;
                canvas.drawCircle(g.cx, cy - off, DOT / 2f, dotPaint);
                canvas.drawCircle(g.cx, cy + off, DOT / 2f, dotPaint);
            } else {
                canvas.drawPath(g.path, digitPaint);
            }
        }
        canvas.restore();
    }

    private void buildClock(String text, float H) {
        glyphs.clear();
        float right = 0;
        char prev = 0;
        inkL = Float.POSITIVE_INFINITY; inkR = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < text.length(); k++) {
            char ch = text.charAt(k);
            if (ch == ':') {
                float cx = right + COLON_GAP + DOT / 2f;
                glyphs.add(new Glyph(cx));
                right = cx + DOT / 2f;
                prev = ':';
                inkL = Math.min(inkL, cx - DOT / 2f); inkR = Math.max(inkR, cx + DOT / 2f);
                continue;
            }
            if (ch < '0' || ch > '9') continue;
            float x = (prev != 0 ? right + (prev == ':' ? COLON_GAP : GAP) : 0) + STROKE / 2f;
            Path p = Numerals.path(ch, DIGIT, H);
            p.offset(x, STROKE / 2f);
            glyphs.add(new Glyph(p));
            right = x + DIGIT + STROKE / 2f;
            prev = ch;
            inkL = Math.min(inkL, ch == '1' ? x + 0.14f * DIGIT - 0.3f * STROKE : x - STROKE / 2f);
            inkR = Math.max(inkR, ch == '1' ? x + 0.56f * DIGIT + STROKE / 2f : x + DIGIT + STROKE / 2f);
        }
        if (Float.isInfinite(inkL)) { inkL = 0; inkR = 0; }
        builtText = text; builtH = H;
    }

    /** 即时活动：光池（底部 48% 的渐变）+ 内容（离屏幕边 face-inset，内边距 12/12/8），整体 translateY。 */
    private void drawActivity(Canvas canvas, Activity a, float dy) {
        if (style != FaceStyles.SECONDS) return;   // 只有秒轨表盘用光池式即时活动
        canvas.save();
        canvas.translate(0, dy);
        canvas.drawRect(0, SIZE * 0.52f, SIZE, SIZE, poolPaint);

        float left = FACE_INSET + 12f, right = SIZE - FACE_INSET - 12f;
        float bodyBottom = SIZE - FACE_INSET - 8f;
        int nLines = a.lines == null ? 0 : Math.min(2, a.lines.size());
        float bodyH = Math.max(26f, nLines * 14f);
        float bodyTop = bodyBottom - bodyH;
        float headTop = bodyTop - 1f - 18f;

        // 标题行：14px 图标 + 4px + 标题（13px 600）
        float tx = left;
        if (a.icon != null && !a.icon.isEmpty()) {
            float s = 14f / 24f;
            canvas.save();
            canvas.translate(left, headTop + 2f);           // 18 高的行里垂直居中
            canvas.scale(s, s);
            iconPaint.setStrokeWidth(2f);
            canvas.drawPath(TesseraIcon.get(a.icon), iconPaint);
            canvas.restore();
            tx = left + 14f + 4f;
        }
        String title = ellipsize(a.title, headPaint, right - tx);
        canvas.drawText(title, tx, baseline(headPaint, headTop, 18f), headPaint);

        // 右侧小字：每行 14 高，底对齐
        float linesW = 0;
        for (int i = 0; i < nLines; i++) linesW = Math.max(linesW, linesPaint.measureText(a.lines.get(i)));
        for (int i = 0; i < nLines; i++) {
            float lineTop = bodyBottom - (nLines - i) * 14f;
            canvas.drawText(a.lines.get(i), right, baseline(linesPaint, lineTop, 14f), linesPaint);
        }
        // 大数值：26px 300，行高 26，底对齐
        float valueW = right - left - (linesW > 0 ? linesW + 8f : 0);
        canvas.drawText(ellipsize(a.value, valuePaint, valueW), left, baseline(valuePaint, bodyBottom - 26f, 26f), valuePaint);
        canvas.restore();
    }

    // ---------- 几何：与 radialTicks 完全相同 ----------

    private void buildTicks() {
        float half = SIZE / 2f, inset = 2f, h = half - inset, r = Math.max(0, RADIUS - inset), c = h - r;
        for (int i = 0; i < COUNT; i++) {
            double ang = i * 2 * Math.PI / COUNT;
            double dx = Math.sin(ang), dy = -Math.cos(ang);
            double t = h / Math.max(Math.abs(dx), Math.abs(dy)), px = t * dx, py = t * dy;
            if (Math.abs(px) > c && Math.abs(py) > c) {
                double cx0 = (px > 0 ? 1 : -1) * c, cy0 = (py > 0 ? 1 : -1) * c, dc = dx * cx0 + dy * cy0;
                t = dc + Math.sqrt(dc * dc - (cx0 * cx0 + cy0 * cy0) + r * r);
                px = t * dx; py = t * dy;
            }
            double len = (i % 2 == 0 ? 9 : 5.5) * (t / h);
            tx1[i] = (float) (half + px); ty1[i] = (float) (half + py);
            tx2[i] = (float) (half + px - len * dx); ty2[i] = (float) (half + py - len * dy);
        }
    }

    // ---------- 小工具 ----------

    /** CSS 行盒里的基线：半行距加在上下，字形按字体的 ascent/descent 放在中间。 */
    private static float baseline(Paint p, float lineTop, float lineHeight) {
        Paint.FontMetrics fm = p.getFontMetrics();
        float content = fm.descent - fm.ascent;
        return lineTop + (lineHeight - content) / 2f - fm.ascent;
    }

    private static String ellipsize(String s, TextPaint p, float width) {
        if (s == null) return "";
        if (width <= 0) return "";
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static String pad(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg2 = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg2 - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
