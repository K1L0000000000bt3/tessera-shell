package com.tessera.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 原生磁贴网格：布局和画法逐项移植自设计稿 bundle.js 的 AppList / Tile / TileGrid 和 bundle.css、tokens.css；
 * 滚动手感同 shell.js 的 ListScroller；启动应用按 Windows Phone 的倾斜 + 转门（用户 2026-10-21 指定）。
 * 坐标全部是设计像素（屏宽 200），绘制时整体乘 scale = 宽 / 200。
 *
 * 布局：4 列 × --tile-s 47、行高 47、gap --space-1 4，grid-auto-flow: row dense（每块都从头找第一个放得下的格子）；
 *       s = 1×1、m = 2×2、w = 4×2；直角（--radius-card: 0），拿起时才变圆（22 / 小卡 12）。
 */
public class TileGridView extends View {
    public interface Callback {
        /** 转门翻完了，拉起应用（应用用 ts_turn_in 顺着同一方向翻进来）。 */
        void onLaunch(String pkg, String cls, String label);
        /** 顶上下拉过了阈值、整层已经滑出去：回表盘。 */
        void onExitToFace();
        /** 拖拽排序结束：按新顺序回传 id 列表。 */
        void onReorder(String idsJson);
        /** 顶上下拉露出表盘的进度 0..1（拖动中逐帧），表盘据此下沉。 */
        default void onPull(float progress) { }
        /** 下拉松手：commit = 要回表盘（整层接着滑出去），否则弹回。 */
        default void onPullEnd(boolean commit) { }
        /** 左边缘右滑返回。 */
        default void onBack() { }
        /** 打开 Tessera 自己的页面（kind = settings / edit）；转门已经翻完。 */
        default void onOpenPage(String kind, String id) { }
        /** 从右往左滑：进搜索。 */
        default void onSearch() { }
        /**
         * 拿起的磁贴贴着屏幕上沿停了一下：交给表盘，接着拖就是往表盘上放卡片（返回 false = 这块不能上表盘，继续排序）。
         * look = 磁贴的样子；x/y/w/h = 拿起的磁贴（没放大前）在屏幕上的位置；fx/fy = 手指；都是设计像素。
         */
        default boolean onLiftToFace(String pkg, String kind, String size, int bg, Bitmap look, float x, float y, float w, float h,
                                     float fx, float fy, float radius, float scale) { return false; }
        /** 交给表盘之后手指的移动 / 松手（屏幕坐标，设计像素）。 */
        default void onFaceDrag(float x, float y) { }
        default void onFaceDrop(float x, float y, boolean cancel) { }
    }

    // ---------- tokens（设计像素） ----------
    private static final float SIZE = 200f, TILE = 47f, GAP = 4f, PAD = 8f;
    private static final int COLS = 4;
    private static final float RADIUS_SCREEN = 28f, R_LIFT = 22f, R_LIFT_S = 12f;
    private static final long HOLD_MS = 400;                  // --hold-ms
    private static final float HOLD_SLOP = 8f, SLOP = 6f;     // --hold-slop；判断横竖
    private static final float EDGE = 14f, EDGE_DX = 40f;     // 左边缘返回 / 右往左滑搜索
    // 滚动手感：和 shell.js 的 ListScroller 同一套参数
    private static final float DECEL = 0.998f, SPRING_W = 0.02f, BOUNCE_V = 1.5f;
    private static final float FLING = 0.35f, COMMIT = 0.35f;
    // 动效时长（毫秒）
    private static final long LIFT_MS = 200, SETTLE_MS = 240, PULL_MS = 200, CROWN_MS = 250, EDIT_DELAY = 320;
    // 设置 / 编辑页进出也是转门（同打开应用）；活动磁贴翻面 0.85 秒（WP HubTile），每 9 秒两次
    private static final long TURN_STAGGER = 50, FLIP_CYCLE = 9000, FLIP_MS = 850;
    // Windows Phone：倾斜（TiltEffect）和转门（Turnstile）
    // WP（Windows Phone Toolkit 的 TurnstileFeather ForwardOut / BackwardIn）：翻走 = 0.25 秒往外翻到 50°（右边朝人、往左甩走），
    // 翻回 = 反过来，0.35 秒从外面 50° 转平；每行晚 0.05 秒（用户 2026-10-25："wp 其实是退出的时候往左边翻而不是往里，进入反之"）。
    // 角度正 = 右边往屏幕里走（Camera / CSS 一样），往外就是负的。按下立刻倾（一帧），松手先停 0.2 秒、再 0.1 秒匀速回正（TiltEffect）
    private static final long TILT_MS = 16, TILT_HOLD_MS = 200, TILT_OUT_MS = 100, TS_OUT = 250, TS_IN = 350, TS_STAGGER = 50, TS_HOLD = 1500;
    static final float TS_DEG = -50f;
    /** 转门的转轴：屏幕左边缘再往左 20% 屏宽（WP TurnstileFeatherEffect.FeatheringCenterOfRotationX = -0.2）。 */
    static final float TS_AXIS = -0.2f;
    // 倾斜（所有卡片统一，用户 2026-10-25）：不管卡片多大，边上按都转 12.5°、角上按都转 17°，透视 350（设计单位），正中间整块缩 4%。
    // 调过两轮：6°/10° 嫌不明显，15°/20°+透视 300 嫌太狠，10°/14°+透视 400 又嫌小——取中间
    // 通知中心 / 控制中心（ActionCenter）、应用列表（AppList）也用这一组
    static final float TILT_EDGE_DEG = 12.5f, TILT_CORNER_DEG = 17f, TILT_PERSP = 350f, TILT_SINK = 0.04f;
    private static final float PERSP_TS = 600f;
    /** 调试用慢放倍数（Bridge.gridSlow），截动画中间帧看方向对不对。 */
    static volatile float slow = 1f;
    /** 原生桌面直接给位图（按包名取），不再把图标编码成 base64 塞进 JSON。 */
    static java.util.function.Function<String, Bitmap> iconSource;

    private static final class Tile {
        String id = "", pkg = "", cls = "", kind = "", size = "s", label = "", name = "", glyph = "", sglyph = "", count = "";
        String backTitle = "", backBody = "";
        final List<String> lines = new ArrayList<>();
        Bitmap icon;
        int bg, fg;
        long flipDelay;
        boolean live;
        int col, row;
        float x, y, w, h;
        String labelFit = "";
        String key = "";      // 内容键：动画快照按它复用
    }

    /** 给搜索页用的应用条目（名字、启动目标、图标、色块）。 */
    static final class App {
        final String id, pkg, cls, kind, name, glyph;
        final Bitmap icon;
        final int bg, fg;

        App(Tile t) {
            id = t.id; pkg = t.pkg; cls = t.cls; kind = t.kind; icon = t.icon; bg = t.bg; fg = t.fg;
            glyph = !t.sglyph.isEmpty() ? t.sglyph : t.glyph;
            name = !t.name.isEmpty() ? t.name : t.label;
        }

        /** 应用列表用：没钉在开始屏上的应用也要有一条。 */
        App(String id, String pkg, String cls, String kind, String name, String glyph, Bitmap icon, int bg, int fg) {
            this.id = id; this.pkg = pkg; this.cls = cls; this.kind = kind; this.name = name; this.glyph = glyph;
            this.icon = icon; this.bg = bg; this.fg = fg;
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private int screenBg = 0xFF000000, accent = 0xFF516B85;
    /** 设计稿颜色（tokens.css 深色主题的默认值；页面推来时按当前主题覆盖）。 */
    private final java.util.HashMap<String, Integer> tok = new java.util.HashMap<>();
    {
        tok.put("ink", 0xFFEEF2F5); tok.put("ink-muted", 0xFFA3ADB8); tok.put("ink-subtle", 0xFF8893A0);
        tok.put("surface", 0xFF181C20); tok.put("surface-2", 0xFF262B31); tok.put("line-strong", 0xFF7D8895);
        tok.put("accent-ink", 0xFF9DB6CF); tok.put("on-accent", 0xFFFFFFFF);
    }
    private float contentH;
    private float scale = 2.4f;
    private Callback callback;

    // 画笔（字体按设计稿：Inter + Noto Sans SC）
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint snapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // 动画快照：磁贴在做 3D 变换（入场、转门、翻页、倾斜、翻面）时贴这张图，GPU 只要变换一个贴图；
    // 描边图标和文字在透视矩阵下每帧都要重新光栅化，渲染线程要 5~7ms。静止时仍然矢量直绘，保证清晰。
    private final java.util.HashMap<String, Bitmap> snaps = new java.util.HashMap<>();
    private float snapScale;
    private boolean warmQueued;
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);   // .ts-tile-label 11/14 600 0.02em
    private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);   // .ts-tile-count 28/32 300
    private final TextPaint countSPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);  // 小卡的数字 20/24 300
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);   // .ts-tile-back-title 15/20
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);    // .ts-tile-back-body 13/18
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();

    // ---------- 滚动 ----------
    private float pos;                 // 显示位置（可能超出 [0, max]：底部橡皮筋）
    private int motion;                // 当前跑着的逐帧动画编号；新的动画让旧的自动停下
    private float pull;                // 顶上下拉的距离（整层下移，露出表盘）
    // WP 式进场：整层在屏幕下面 → 到位的进度（0..1），每一行按这个进度从"深处"浮上来
    private float enterP = 1f;
    private static final float ENTER_LAG = 0.12f, ENTER_ROWS = 4f;

    // ---------- 触摸 ----------
    private float downX, downY, lockY, startRaw;
    private int lock;                  // 0 未定，1 纵向，-1 横向
    private boolean atTopAtDown, pulling, pulled;
    private final float[] sy = new float[16];
    private final long[] st = new long[16];
    private int sn;
    private int hit = -1;

    // 按下：WP 倾斜——按哪里哪里陷下去，整块微微翘起、略缩小（100ms）
    private int pressIdx = -1;
    private long pressT0;
    private boolean pressDown;
    // 倾斜：mode 0 = 正中间整块往下陷，1 = 按在左右边绕竖中轴转，2 = 按在上下边绕横中轴转，3 = 按在角上绕对角线转（另两个角钉住不动）
    private int tiltMode;
    private float tiltDeg, tiltAxis;   // 转角；对角线模式下对角线相对水平方向的角度

    // 拿起拖动（ghost）
    private int liftIdx = -1;
    private long liftT0;
    private float ghostX, ghostY, ghostOx, ghostOy;
    // 拿着磁贴贴住屏幕上沿停 400ms → 交给表盘（用户 2026-10-24：保留排序，拖到最上沿停一下再去表盘）
    private static final float TOP_EDGE = 14f;
    private boolean handedOff, topWait;
    private int handIdx = -1;
    private final Runnable toFace = this::handOff;
    private boolean movedSinceLift;
    private final Runnable hold = this::lift;

    // 落地：带着圆角落下，再 240ms 收回直角
    private int settleIdx = -1;
    private long settleT0;

    // 转门启动：其他卡片按行错开绕屏幕左边缘翻走，被点的那块最后走；回来时按行翻回来
    private int tsDir;                 // 1 翻出去，-1 翻回来，0 没有
    // 表盘 ↔ 应用列表的转门（设计稿"上滑进应用列表 · 转门"FaceToApps）：1 = 翻进来，-1 = 翻走，0 = 没有
    private int ftDir;
    private long ftT0, ftLast;
    private float ftE0, ftRate = 1f;                  // 时钟：进度 = ftE0 + ftRate·(now − ftT0)/slow（快进 / 倒放时改这两个，不跳）
    // 表盘 ↔ 开始屏也是同一种转门：进来 0.35 秒从外面 50° 转平（先等表盘让开 0.13 秒），出去 0.25 秒往外翻到 50°；WP 是一下出现 / 最后 0.01 秒才消失
    private static final long FT_IN_DELAY = 130, FT_IN = 350, FT_FADE_IN = 10, FT_OUT = 250, FT_FADE_AT = 240, FT_FADE_OUT = 10;
    private static final float FT_DEG = TS_DEG;
    private long tsT0, tsSpan;
    private float tsE0, tsRate = 1f;                  // 转门的时钟（同上）                      // tsSpan：整组的时长（ms，没乘慢放），曲线"整段"模式用
    private int tsSel = -1;
    private boolean tsGone;            // 都翻走了，等应用盖上来
    private String returnId;

    // 转门（翻页，设计稿的按列中轴翻）
    private int turnDir;
    private long turnT0, turnSpan;
    private float turnE0, turnRate = 1f;              // 进出设置页的时钟（同上）                  // turnSpan：整组的时长（ms，没乘慢放），曲线"整段"模式用
    private Runnable turnDone;

    // 活卡片翻面（ts-flip 9s）
    private long flipBase = SystemClock.uptimeMillis();

    public TileGridView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Typeface w300 = Fonts.sans(c.getAssets(), 300), w400 = Fonts.sans(c.getAssets(), 400), w600 = Fonts.sans(c.getAssets(), 600);
        labelPaint.setTypeface(w600); labelPaint.setTextSize(11f); labelPaint.setLetterSpacing(0.02f);
        countPaint.setTypeface(w300); countPaint.setTextSize(28f);
        countSPaint.setTypeface(w300); countSPaint.setTextSize(20f); countSPaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTypeface(w400); titlePaint.setTextSize(15f);
        bodyPaint.setTypeface(w400); bodyPaint.setTextSize(13f);
        PM = Fonts.primaryMetrics(c.getAssets());
        // 整层是一块 .ts-screen（圆角 = 屏幕圆角），下拉时上沿露出圆角
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), RADIUS_SCREEN * scale); }
        });
        setClipToOutline(false);                     // 只在被往下拉、露出上沿的时候才裁圆角（setEnterY）：平时四角有遮罩层盖着，
                                                     // 每帧整屏按圆角裁会让 GPU 每次画都算一遍覆盖率，整屏重画要 15ms 以上（这块芯片 69Hz 一帧只有 14.5ms）
    }

    public void setCallback(Callback cb) { callback = cb; }

    // ---------- 数据 ----------

    /** 页面推来的布局：{bg, accent, tiles:[...]}（新格式）或者直接是 tiles 数组（旧格式 / Bridge.apps()）。 */
    public void setJson(String json) {
        try {
            Object v = new org.json.JSONTokener(json).nextValue();
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                screenBg = parseCss(o.optString("bg"), screenBg);
                accent = parseCss(o.optString("accent"), accent);
                JSONObject tk = o.optJSONObject("tok");
                if (tk != null) { java.util.Iterator<String> it = tk.keys(); while (it.hasNext()) { String k = it.next(); Integer old = tok.get(k); tok.put(k, parseCss(tk.optString(k), old == null ? 0xFFFF00FF : old)); } }
                setApps(o.optJSONArray("tiles"));
            } else if (v instanceof JSONArray) {
                setApps((JSONArray) v);
            }
        } catch (Exception ignored) {
        }
    }

    public void setApps(JSONArray arr) {
        if (arr == null) return;
        String keep = signature();
        tiles.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Tile t = new Tile();
            t.id = o.optString("id");
            t.pkg = o.optString("pkg");
            t.cls = o.optString("cls");
            if (t.pkg.isEmpty() && t.id.contains("/")) {      // 旧数据只有 id = pkg/cls
                t.pkg = t.id.substring(0, t.id.indexOf('/'));
                t.cls = t.id.substring(t.id.indexOf('/') + 1);
            }
            if (t.cls.startsWith(".")) t.cls = t.pkg + t.cls;
            t.kind = o.optString("kind");
            t.size = o.optString("size", "s");
            String tone = o.optString("tone", "base");
            t.bg = parseCss(o.optString("bg"), "accent".equals(tone) ? 0xFF516B85 : "soft".equals(tone) ? 0xFF2C333A : 0xFF3E4956);
            t.fg = parseCss(o.optString("fg"), "accent".equals(tone) ? 0xFFFFFFFF : 0xFFEEF2F5);
            t.label = o.optString("label").toLowerCase(Locale.ROOT);   // text-transform: lowercase
            t.name = o.optString("name", o.optString("label"));
            t.glyph = o.optString("glyph");
            t.sglyph = o.optString("sglyph");   // 应用自己的设计稿图标（搜索页用，和磁贴怎么配无关）
            t.count = o.optString("count");
            t.icon = decode(o.optString("icon"));
            if (t.icon == null && iconSource != null && !o.optString("iconPkg").isEmpty()) t.icon = iconSource.apply(o.optString("iconPkg"));
            JSONArray ln = o.optJSONArray("lines");
            if (ln != null) for (int k = 0; k < ln.length(); k++) t.lines.add(ln.optString(k));
            JSONObject bk = o.optJSONObject("back");
            if (bk != null) { t.backTitle = bk.optString("title"); t.backBody = bk.optString("body"); }
            t.flipDelay = Math.round(o.optDouble("flipDelay", 0) * 1000);
            t.live = bk != null && !"s".equals(t.size) && (!t.backTitle.isEmpty() || !t.backBody.isEmpty());
            t.key = t.size + "|" + t.bg + "|" + t.fg + "|" + t.label + "|" + t.glyph + "|" + t.count + "|" + t.lines + "|" + t.backTitle + "|" + t.backBody + "|" + System.identityHashCode(t.icon);
            tiles.add(t);
        }
        // 设计稿的 AppList 换数据时整块重新挂载，翻面动画从头开始
        if (!signature().equals(keep)) flipBase = SystemClock.uptimeMillis();
        layoutTiles();
        pos = clamp(pos, 0, maxScroll());
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (Tile t : tiles) { used.add("F" + t.key); used.add("B" + t.key); }
        snaps.keySet().retainAll(used);              // 不 recycle：渲染线程的显示列表可能还引用着
        warmLater();
        invalidate();
    }

    /** 取（没有就画）一块磁贴某一面的快照，像素大小和屏幕上 1:1。 */
    private Bitmap snapshot(Tile t, boolean back) {
        if (snapScale != scale) { snaps.clear(); snapScale = scale; }
        String k = (back ? "B" : "F") + t.key;
        Bitmap b = snaps.get(k);
        if (b == null) {
            int w = Math.max(1, Math.round(t.w * scale)), h = Math.max(1, Math.round(t.h * scale));
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(b);
            bc.scale(w / t.w, h / t.h);
            bc.translate(-t.x, -t.y);
            drawTile(bc, t, 1f, 0f, back);
            b.prepareToDraw();                            // 提前在渲染线程上传成纹理
            snaps.put(k, b);
        }
        return b;
    }

    private void drawSnapshot(Canvas c, Tile t, float alpha, boolean back) {
        Bitmap b = snapshot(t, back);
        r.set(t.x, t.y, t.x + t.w, t.y + t.h);
        snapPaint.setAlpha(Math.round(alpha * 255));
        c.drawBitmap(b, null, r, snapPaint);
    }

    /** 主线程空闲时一块一块把快照提前画好，动画第一帧不用现画。 */
    private void warmLater() {
        if (warmQueued) return;
        warmQueued = true;
        android.os.Looper.myQueue().addIdleHandler(() -> {
            if (getWidth() == 0) { warmQueued = false; return false; }
            if (snapScale != scale) { snaps.clear(); snapScale = scale; }
            for (Tile t : tiles) {
                if (!snaps.containsKey("F" + t.key)) { snapshot(t, false); return true; }
                if (t.live && !snaps.containsKey("B" + t.key)) { snapshot(t, true); return true; }
            }
            warmQueued = false;
            return false;
        });
    }

    /** 所有应用（搜索页用）。 */
    List<App> apps() {
        List<App> out = new ArrayList<>();
        for (Tile t : tiles) if (!t.pkg.isEmpty()) out.add(new App(t));
        return out;
    }

    int screenBg() { return screenBg; }

    /** 编辑页的预览：只画第一块磁贴（不带底色），左上角放在 (x, y)。 */
    float previewHeight() { return tiles.isEmpty() ? 0f : tiles.get(0).h; }

    void drawPreview(Canvas c, float x, float y) {
        if (tiles.isEmpty()) return;
        Tile t = tiles.get(0);
        c.save();
        c.translate(x - t.x, y - t.y);
        drawTile(c, t, 1f, 0f, false);
        c.restore();
    }

    int accent() { return accent; }

    int tok(String name) { Integer v = tok.get(name); return v == null ? 0xFFFF00FF : v; }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (Tile t : tiles) sb.append(t.id).append(t.size).append(t.backTitle).append(t.backBody).append('|');
        return sb.toString();
    }

    private static Bitmap decode(String dataUri) {
        int i = dataUri == null ? -1 : dataUri.indexOf("base64,");
        if (i < 0) return null;
        try {
            byte[] b = Base64.decode(dataUri.substring(i + 7), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /** CSS 颜色：#rgb / #rrggbb / #rrggbbaa / rgb() / rgba()。 */
    static int parseCss(String s, int fallback) {
        if (s == null) return fallback;
        s = s.trim();
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
                if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
                if (h.length() == 8) { long v = Long.parseLong(h, 16); return (int) (((v & 0xFF) << 24) | (v >>> 8)); }
            } else if (s.startsWith("rgb")) {
                String[] p = s.substring(s.indexOf('(') + 1, s.indexOf(')')).trim().split("[,\\s/]+");
                int a = p.length > 3 ? Math.round(Float.parseFloat(p[3]) * 255) : 255;
                return Color.argb(a, Math.round(Float.parseFloat(p[0])), Math.round(Float.parseFloat(p[1])), Math.round(Float.parseFloat(p[2])));
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    // ---------- 布局：CSS grid，row dense ----------

    private void layoutTiles() {
        List<boolean[]> occ = new ArrayList<>();
        int rows = 0;
        for (Tile t : tiles) {
            int cw = "w".equals(t.size) ? 4 : "m".equals(t.size) ? 2 : 1, rh = "s".equals(t.size) ? 1 : 2;
            boolean placed = false;
            for (int row = 0; !placed; row++) {
                for (int col = 0; col + cw <= COLS && !placed; col++) {
                    if (free(occ, row, col, cw, rh)) {
                        for (int a = 0; a < rh; a++) for (int b = 0; b < cw; b++) occ.get(row + a)[col + b] = true;
                        t.col = col; t.row = row; placed = true;
                    }
                }
            }
            t.x = t.col * (TILE + GAP);
            t.y = t.row * (TILE + GAP);
            t.w = cw * TILE + (cw - 1) * GAP;
            t.h = rh * TILE + (rh - 1) * GAP;
            rows = Math.max(rows, t.row + rh);
            t.labelFit = t.label.isEmpty() ? "" : TextUtils.ellipsize(t.label, labelPaint, t.w - PAD * 2, TextUtils.TruncateAt.END).toString();
        }
        contentH = rows > 0 ? rows * (TILE + GAP) - GAP : 0;
    }

    private static boolean free(List<boolean[]> occ, int row, int col, int cw, int rh) {
        while (occ.size() < row + rh) occ.add(new boolean[COLS]);
        for (int a = 0; a < rh; a++) for (int b = 0; b < cw; b++) if (occ.get(row + a)[col + b]) return false;
        return true;
    }

    private float viewH() { return getHeight() > 0 ? getHeight() / scale : SIZE; }

    private float maxScroll() { return Math.max(0, contentH - viewH()); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = w / SIZE;
        invalidateOutline();
        pos = clamp(pos, 0, maxScroll());
        warmLater();
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        settleClocks(now);
        c.save();
        c.scale(scale, scale);
        // 底色：根布局本来就是纯黑，黑底主题时不再整屏刷一遍（这块 GPU 和系统合成共用，每帧多刷一屏就掉帧）；
        // 被往下拉时要挡住底下的表盘，照刷
        if (ftDir == 0) {
            if (!tsGone && (screenBg != 0xFF000000 || getTranslationY() != 0f)) c.drawColor(screenBg);
        } else {                                             // 转门期间底色跟着淡入 / 淡出（底下是根布局的纯黑，和设计稿一样）
            float k = ftDir > 0 ? clamp((ftEl(now) - FT_IN_DELAY) / (ftLast + FT_IN), 0, 1) : 1f - clamp(ftEl(now) / (ftLast + FT_FADE_AT + FT_FADE_OUT), 0, 1);
            if (k > 0f) { fill.setColor(screenBg); fill.setAlpha(Math.round(k * 255)); c.drawRect(0, 0, SIZE, viewH(), fill); }
            if (ftDir > 0 ? k < 1f : true) again = true;
        }
        float vh = viewH();

        c.save();
        c.translate(0, -pos);
        long nextFlip = Long.MAX_VALUE;
        // 往外翻的磁贴离人更近、会变大叠到邻居上：转得多的要最后画。翻走时上面的行先走（转得多）→ 从下往上画；
        // 翻回来时下面的行还没转平（转得多）→ 照常从上往下画
        final boolean rev = tsDir > 0 || turnDir > 0 || ftDir < 0;
        if (!tsGone) for (int ii = 0; ii < tiles.size(); ii++) {
            int i = rev ? tiles.size() - 1 - ii : ii;
            Tile t = tiles.get(i);
            if (t.y + t.h < pos - 30 || t.y > pos + vh + 30) continue;
            if (i == handIdx) continue;                       // 已经拿到表盘上去了

            float ftDeg = 0f, ftA = 1f;
            if (ftDir != 0) {
                long dl = ftDelay(t);                         // 从上往下、从左往右：行 35ms、列 12ms
                if (ftDir > 0) {                              // 130ms 的起步等待不算在整段里
                    float lv = Curves.GRID_FT_IN.vt(ftEl(now) - FT_IN_DELAY, ftLast + FT_IN) - dl;
                    ftDeg = FT_DEG * (1f - Curves.GRID_FT_IN.shape(clamp(lv / FT_IN, 0, 1)));
                    ftA = clamp(lv / FT_FADE_IN, 0, 1);
                    if (ftA < 1f || ftDeg != 0f) again = true;
                } else {
                    float lv = Curves.GRID_FT_OUT.vt(ftEl(now), ftLast + FT_OUT) - dl;
                    ftDeg = FT_DEG * Curves.GRID_FT_OUT.shape(clamp(lv / FT_OUT, 0, 1));
                    ftA = 1f - clamp((lv - FT_FADE_AT) / FT_FADE_OUT, 0, 1);
                    if (ftA > 0f) again = true;
                }
                if (ftA <= 0f) continue;
            }
            float hinge = turnstileAngle(i, now);           // 转门：绕屏幕左边缘
            if (Float.isNaN(hinge)) continue;               // 已经翻走
            float turnDeg = turnAngle(t, now);              // 翻页：按列中轴翻
            if (Float.isNaN(turnDeg)) continue;
            if (tsDir != 0 || turnDir != 0) again = true;

            float s = 1f, rad = 0f, tk = 0f, ea = ftA, edy = 0f;
            if (enterP < 1f) {
                float row = Math.min(ENTER_ROWS, tsRow(t));
                float e = Curves.GRID_ENTER.getInterpolation(clamp(enterP * (1f + ENTER_LAG * ENTER_ROWS) - ENTER_LAG * row, 0, 1));
                s = 0.85f + 0.15f * e;           // 从深处：小一圈
                edy = (1f - e) * 28f;            // 而且更靠下，行和行之间拉开
                ea = 0.3f + 0.7f * e;
            }
            if (i == pressIdx) {
                float k = tiltAmount(now);
                if (k > 0f && k < 1f || (k > 0f && !pressDown)) again = true;
                if (k < 1f && pressDown) again = true;
                tk = k;
                if (tiltMode == 0) s = 1f - TILT_SINK * k;
            }
            if (i == settleIdx) {
                float k = Curves.GRID_SETTLE.getInterpolation(prog(now, settleT0, SETTLE_MS));
                s *= 1.08f - 0.08f * k;
                rad = (1 - k) * ("s".equals(t.size) ? R_LIFT_S : R_LIFT);
                if (k < 1) again = true; else settleIdx = -1;
            }
            float flipDeg = flipAngle(t, now);
            if (t.live) {
                long d = msToNextFlip(t, now);
                if (d == 0) again = true; else nextFlip = Math.min(nextFlip, d);
            }

            ea *= Shade.edge(hinge, TS_DEG, tsDir > 0) * Shade.edge(ftDeg, FT_DEG, ftDir < 0) * Shade.edge(turnDeg, TS_DEG, turnDir > 0);   // 快转到头时整块淡掉
            if (ea <= 0f) continue;
            c.save();
            float cx = t.x + t.w / 2f, cy = t.y + t.h / 2f;
            if (ftDeg != 0f) rotateHinge(c, ftDeg, cy);
            if (hinge != 0f) rotateHinge(c, hinge, cy);
            if (edy != 0f) c.translate(0, edy);
            if (s != 1f) c.scale(s, s, cx, cy);
            if (tk > 0f && tiltMode != 0) tilt(c, tk, cx, cy);   // 按压倾斜（perspective 400，.ts-tile）
            if (turnDeg != 0f) rotateHinge(c, turnDeg, cy);              // 进出设置页：同打开应用的转门
            boolean back = false;
            if (flipDeg != 0f) {                                          // 活卡片：rotateX
                float a = flipDeg % 360f;
                back = a > 90f && a < 270f;
                rotate(c, back ? a - 180f : a, 0, 400f, cx, cy);
            }
            boolean moving = ftDeg != 0f || hinge != 0f || edy != 0f || s != 1f || tk > 0f || turnDeg != 0f || flipDeg != 0f || ea != 1f;
            if (moving && rad == 0f && i != liftIdx) drawSnapshot(c, t, ea, back);
            else drawTile(c, t, (i == liftIdx ? 0.25f : 1f) * ea, rad, back);
            // 没转平的卡片：离转轴越远越暗（转轴在屏幕左边，越往右越暗）
            float hs = Math.max(Math.max(hinge != 0f ? Shade.amount(hinge) : 0f, ftDeg != 0f ? Shade.amount(ftDeg) : 0f), turnDeg != 0f ? Shade.amount(turnDeg) : 0f);
            if (hs > 0f) Shade.over(c, hs * ea, 0f, SIZE, t.x, t.y, t.x + t.w, t.y + t.h);
            c.restore();
        }
        c.restore();

        // 拿起的卡片（ghost）：跟着手指，先方后圆，放大 1.08
        if (liftIdx >= 0 && liftIdx < tiles.size()) {
            Tile t = tiles.get(liftIdx);
            float k = now < liftT0 ? 0f : Curves.GRID_LIFT.getInterpolation(prog(now, liftT0, LIFT_MS));
            if (k < 1) again = true;
            c.save();
            c.translate(ghostX - ghostOx - (t.x + t.w / 2f), ghostY - ghostOy - (t.y + t.h / 2f));
            float s = 1f + 0.08f * k;
            c.scale(s, s, t.x + t.w / 2f, t.y + t.h / 2f);
            drawGhost(c, t, k * ("s".equals(t.size) ? R_LIFT_S : R_LIFT));
            c.restore();
        }
        c.restore();

        if (again) postInvalidateOnAnimation();
        else if (nextFlip != Long.MAX_VALUE && getVisibility() == VISIBLE) postInvalidateDelayed(Math.max(16, nextFlip));
    }

    private static float prog(long now, long t0, long dur) { return clamp((now - t0) / (dur * slow), 0, 1); }

    /** 绕卡片中心转：rx = rotateX，ry = rotateY（角度同 CSS）；perspective 是设计像素（Camera 的单位是 1/72 英寸）。 */
    private void rotate(Canvas c, float rx, float ry, float perspective, float cx, float cy) {
        camera.save();
        camera.setLocation(0, 0, -perspective / 72f);
        if (rx != 0f) camera.rotateX(rx);
        if (ry != 0f) camera.rotateY(ry);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
        c.concat(matrix);
    }

    /** 按压倾斜：边上按 → 绕中轴转；角上按 → 绕另外两个角连成的对角线转（那两个角不动）。perspective 400。 */
    private void tilt(Canvas c, float k, float cx, float cy) {
        float deg = tiltDeg * k;
        if (tiltMode == 1) { rotate(c, 0, deg, TILT_PERSP, cx, cy); return; }
        if (tiltMode == 2) { rotate(c, deg, 0, TILT_PERSP, cx, cy); return; }
        // 对角线：先把它转到水平方向，绕它 rotateX，再转回去（Canvas 后调用的先作用在点上）
        float a = (float) Math.toDegrees(tiltAxis);
        c.translate(cx, cy);
        c.rotate(a);
        camera.save();
        camera.setLocation(0, 0, -TILT_PERSP / 72f);
        camera.rotateX(deg);
        camera.getMatrix(matrix);
        camera.restore();
        c.concat(matrix);
        c.rotate(-a);
        c.translate(-cx, -cy);
    }

    /** 转门：绕屏幕左边缘再往左 20% 的竖轴转 deg 度（正 = 右边往屏幕里走），透视中心在这一行中间（WP 每个元素自己的投影）。 */
    private void rotateHinge(Canvas c, float deg, float cy) {
        camera.save();
        camera.setLocation(0, 0, -PERSP_TS / 72f);
        float ax = SIZE / 2f - TS_AXIS * SIZE;    // 转轴到屏幕中线的距离：左边缘再往左 20%
        camera.translate(-ax, 0, 0);              // 点先平移（转轴到原点）、转、再平移回去
        camera.rotateY(deg);                      // 实测：Camera 的 rotateY 正负和 CSS 一致（正 = 右边往屏幕里走）
        camera.translate(ax, 0, 0);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-SIZE / 2f, -cy);
        matrix.postTranslate(SIZE / 2f, cy);
        c.concat(matrix);
    }


    /** 这块在屏幕上是第几行（小磁贴 47 + 4 一行）。 */
    private int ftRow(Tile t) { return tsRow(t); }

    /** 上滑进来 / 回表盘时这块的延迟：每行 50ms（WP），同一行里每列再晚一点（行间隔的 12/35）；行间隔可以在调曲线网页里改。 */
    private long ftDelay(Tile t) {
        long r = (ftDir < 0 ? Curves.GRID_FT_OUT : Curves.GRID_FT_IN).stagger(50);
        return ftRow(t) * r + t.col * r * 12 / 35;
    }

    /** 屏幕里看得见的磁贴里最晚的那块的延迟（行 35、列 12）。 */
    private long ftLastDelay() {
        long last = 0;
        for (Tile t : tiles) if (visible(t)) last = Math.max(last, ftDelay(t));
        return last;
    }

    /** 从表盘上滑进来：磁贴 130ms 后从 80° 翻平（380ms，每块按行列错开），淡入 60ms。 */
    void faceTurnIn() {
        motion++;
        ftDir = 1;
        ftT0 = SystemClock.uptimeMillis(); ftE0 = 0f; ftRate = 1f;
        ftLast = ftLastDelay();
        final long t0 = ftT0;
        postDelayed(() -> { if (ftDir == 1 && ftT0 == t0) { ftDir = 0; invalidate(); } }, Math.round((FT_IN_DELAY + ftLast + FT_IN) * slow) + 20);
        invalidate();
    }

    /** 回表盘：磁贴按同样的顺序翻走（220ms 先慢后快，160ms 起 80ms 淡出）。返回最晚那块的延迟（表盘在它 + 200ms 后落下来）。 */
    long faceTurnOut() {
        motion++;
        removeCallbacks(hold);
        pressIdx = -1;
        ftDir = -1;
        ftT0 = SystemClock.uptimeMillis(); ftE0 = 0f; ftRate = 1f;
        ftLast = ftLastDelay();
        invalidate();
        return Math.round(ftLast * slow);
    }

    boolean faceTurning() { return ftDir != 0; }

    // ---------- 打断（用户 2026-10-25："添加打断动画并保证不卡顿，出现动画抽搐，保证跟手"） ----------

    private static final float FF_MS = 120f;         // 翻进来时按下：剩下的一段在这么久里走完（乘慢放）

    private float tsEl(long now) { return Math.max(0f, tsE0 + tsRate * (now - tsT0) / slow); }
    private float ftEl(long now) { return Math.max(0f, ftE0 + ftRate * (now - ftT0) / slow); }
    private float turnEl(long now) { return Math.max(0f, turnE0 + turnRate * (now - turnT0) / slow); }

    /** 正在翻进来（从应用 / 应用列表 / 设置页回来、从表盘上滑进来）。 */
    private boolean entering() { return tsDir < 0 || ftDir > 0 || turnDir < 0; }

    /** 翻进来的时候手指按下：剩下的一段在 FF_MS 内走完——接着现在的角度走、只是变快，不跳。 */
    private void fastForward(long now) {
        if (tsDir < 0) { float e = tsEl(now), rem = tsSpan - e; if (rem > FF_MS) { tsE0 = e; tsT0 = now; tsRate = rem / FF_MS; } }
        if (ftDir > 0) { float e = ftEl(now), rem = FT_IN_DELAY + ftLast + FT_IN - e; if (rem > FF_MS) { ftE0 = e; ftT0 = now; ftRate = rem / FF_MS; } }
        if (turnDir < 0) { float e = turnEl(now), rem = turnSpan - e; if (rem > FF_MS) { turnE0 = e; turnT0 = now; turnRate = rem / FF_MS; } }
        invalidate();
    }

    /** 翻进来还没走完就要翻走（点了磁贴、往左滑）：把进场直接收尾（按下时已经快进过，这时只剩一两度）。 */
    private void finishEntering() {
        if (tsDir < 0) { tsDir = 0; tsRate = 1f; }
        if (ftDir > 0) { ftDir = 0; ftRate = 1f; }
        if (turnDir < 0) { turnDir = 0; turnRate = 1f; turnDone = null; }
    }

    /** 往应用列表翻走的半路按了返回：原路倒着翻回来（不跳）。返回 false = 不是这种情况。 */
    boolean rewindLeave() {
        if (tsDir <= 0 || tsSel >= 0 || tsGone || tsRate < 0f) return false;
        long now = SystemClock.uptimeMillis();
        tsE0 = tsEl(now); tsT0 = now; tsRate = -1f;
        motion++;                                      // 翻完要打开应用列表的那个回调作废
        invalidate();
        return true;
    }

    /** 每帧：翻进来走完了就收尾（快进过的不用等原来的定时器）；倒放回到起点了就停。 */
    private void settleClocks(long now) {
        if (tsDir < 0 && tsEl(now) >= tsSpan) { tsDir = 0; tsRate = 1f; }
        if (tsDir > 0 && tsRate < 0f && tsEl(now) <= 0f) { tsDir = 0; tsRate = 1f; }
        if (ftDir > 0 && ftEl(now) >= FT_IN_DELAY + ftLast + FT_IN) { ftDir = 0; ftRate = 1f; }
        if (turnDir < 0 && turnEl(now) >= turnSpan) { turnDir = 0; turnRate = 1f; Runnable r = turnDone; turnDone = null; if (r != null) post(r); }
    }

    /** 一块磁贴：底色 + 按尺寸排的内容（Tile 的正面 / 背面，flex 纵向 space-between）。 */
    private void drawTile(Canvas c, Tile t, float alpha, float radius, boolean back) {
        int a = Math.round(alpha * 255);
        fill.setColor(t.bg);
        fill.setAlpha(Color.alpha(t.bg) * a / 255);
        r.set(t.x, t.y, t.x + t.w, t.y + t.h);
        c.save();
        if (radius > 0f) {
            c.drawRoundRect(r, radius, radius, fill);
            path.reset();
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.clipPath(path);
        } else {
            c.drawRect(r, fill);
            c.clipRect(r);                        // .ts-face { overflow: hidden }
        }
        int fg = (t.fg & 0x00FFFFFF) | ((Color.alpha(t.fg) * a / 255) << 24);
        if (back) drawBack(c, t, fg); else drawFront(c, t, fg, a);
        c.restore();
    }

    private void drawFront(Canvas c, Tile t, int fg, int a) {
        // 第三方应用：真实图标当底图，居中、宽度 62%（shell.js 的 backgroundSize 62%）
        if (t.icon != null) {
            float iw = t.w * 0.62f, ih = iw * t.icon.getHeight() / Math.max(1f, t.icon.getWidth());
            r.set(t.x + (t.w - iw) / 2f, t.y + (t.h - ih) / 2f, t.x + (t.w + iw) / 2f, t.y + (t.h + ih) / 2f);
            bmp.setAlpha(a);
            c.drawBitmap(t.icon, null, r, bmp);
        }
        boolean hasCount = !t.count.isEmpty(), hasGlyph = !t.glyph.isEmpty() && t.icon == null;
        if ("s".equals(t.size)) {
            // 小卡：内容居中、没有内边距；有数字没图标画数字（20/24），否则画 22 的图标
            if (hasCount && t.glyph.isEmpty() && t.icon == null) {
                countSPaint.setColor(fg);
                c.drawText(t.count, t.x + t.w / 2f, baseline(countSPaint, t.y + (t.h - 24f) / 2f, 24f), countSPaint);
            } else if (t.icon == null) {
                glyph(c, t.glyph.isEmpty() ? "more" : t.glyph, t.x + (t.w - 22f) / 2f, t.y + (t.h - 22f) / 2f, 22f, fg);
            }
            return;
        }
        float top = t.y + PAD, bottom = t.y + t.h - PAD, left = t.x + PAD, right = t.x + t.w - PAD;
        float labelH = t.labelFit.isEmpty() ? 0f : 14f;
        if ("m".equals(t.size)) {
            float topH = hasCount ? 32f : 0f;
            float glyphH = !hasCount && hasGlyph ? 6f + 30f : 0f;
            float[] y = spaceBetween(top, bottom, topH, glyphH, labelH);
            if (hasCount) {
                countPaint.setColor(fg);
                c.drawText(t.count, left, baseline(countPaint, y[0], 32f), countPaint);
                if (hasGlyph) glyph(c, t.glyph, right - 22f, y[0], 22f, fg);
            }
            if (glyphH > 0) glyph(c, t.glyph, t.x + (t.w - 30f) / 2f, y[1] + 6f, 30f, fg);   // align-self: center; margin-top 6
            label(c, t, left, y[2], fg);
        } else {
            float topH = Math.max(hasGlyph ? 26f : 0f, hasCount ? 32f : 0f);
            float linesH = t.lines.size() * 18f;
            float[] y = spaceBetween(top, bottom, topH, linesH, labelH);
            if (hasGlyph) glyph(c, t.glyph, left, y[0], 26f, fg);
            if (hasCount) {
                countPaint.setColor(fg);
                countPaint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(t.count, right, baseline(countPaint, y[0], 32f), countPaint);
                countPaint.setTextAlign(Paint.Align.LEFT);
            }
            bodyPaint.setColor(fg);
            for (int i = 0; i < t.lines.size(); i++) {
                String s = TextUtils.ellipsize(t.lines.get(i), bodyPaint, right - left, TextUtils.TruncateAt.END).toString();
                c.drawText(s, left, baseline(bodyPaint, y[1] + i * 18f, 18f), bodyPaint);
            }
            label(c, t, left, y[2], fg);
        }
    }

    /** 背面：标题（15/20）+ 正文（13/18）在上，标签在下。 */
    private void drawBack(Canvas c, Tile t, int fg) {
        float top = t.y + PAD, bottom = t.y + t.h - PAD, left = t.x + PAD, w = t.w - PAD * 2;
        float headH = (t.backTitle.isEmpty() ? 0 : 20f) + (t.backBody.isEmpty() ? 0 : 18f);
        float[] y = spaceBetween(top, bottom, headH, 0f, t.labelFit.isEmpty() ? 0f : 14f);
        float yy = y[0];
        if (!t.backTitle.isEmpty()) {
            titlePaint.setColor(fg);
            c.drawText(TextUtils.ellipsize(t.backTitle, titlePaint, w, TextUtils.TruncateAt.END).toString(), left, baseline(titlePaint, yy, 20f), titlePaint);
            yy += 20f;
        }
        if (!t.backBody.isEmpty()) {
            bodyPaint.setColor(fg);
            c.drawText(TextUtils.ellipsize(t.backBody, bodyPaint, w, TextUtils.TruncateAt.END).toString(), left, baseline(bodyPaint, yy, 18f), bodyPaint);
        }
        label(c, t, left, y[2], fg);
    }

    /** 拿起的卡片：底色 + 图标居中（小卡 22，其余 30；第三方应用画它自己的图标）。 */
    private void drawGhost(Canvas c, Tile t, float radius) {
        fill.setColor(t.bg);
        r.set(t.x, t.y, t.x + t.w, t.y + t.h);
        c.drawRoundRect(r, radius, radius, fill);
        if (t.icon != null) {
            float iw = t.w * 0.62f;
            r.set(t.x + (t.w - iw) / 2f, t.y + (t.h - iw) / 2f, t.x + (t.w + iw) / 2f, t.y + (t.h + iw) / 2f);
            bmp.setAlpha(255);
            c.drawBitmap(t.icon, null, r, bmp);
        } else {
            float gs = "s".equals(t.size) ? 22f : 30f;
            glyph(c, t.glyph.isEmpty() ? "more" : t.glyph, t.x + (t.w - gs) / 2f, t.y + (t.h - gs) / 2f, gs, t.fg);
        }
    }

    private void label(Canvas c, Tile t, float left, float top, int fg) {
        if (t.labelFit.isEmpty()) return;
        labelPaint.setColor(fg);
        c.drawText(t.labelFit, left, baseline(labelPaint, top, 14f), labelPaint);
    }

    /** 设计稿图标：24 格路径，描边 2，圆头圆角，按 size 缩放。 */
    void glyph(Canvas c, String name, float x, float y, float size, int color) {
        Path p = TesseraIcon.get(name);
        if (p == null) return;
        c.save();
        c.translate(x, y);
        c.scale(size / 24f, size / 24f);
        stroke.setColor(color);
        stroke.setStrokeWidth(2f);
        c.drawPath(p, stroke);
        c.restore();
    }

    private static final java.util.HashMap<String, RectF> GLYPH_INK = new java.util.HashMap<>();

    /**
     * 按笔画居中画图标：(cx, cy) 对准笔画外框的中心，而不是 24 格的中心——很多图标在 24 格里本来就偏（铃铛偏下、WLAN 偏上），
     * 按格子居中看起来不在正中间（用户 2026-10-25）。size 仍是 24 格对应的大小，笔画粗细不变。
     */
    void glyphCentered(Canvas c, String name, float cx, float cy, float size, int color) {
        Path p = TesseraIcon.get(name);
        if (p == null) return;
        RectF b = GLYPH_INK.get(name);
        if (b == null) { b = new RectF(); p.computeBounds(b, true); GLYPH_INK.put(name, b); }
        float k = size / 24f;
        glyph(c, name, cx - b.centerX() * k, cy - b.centerY() * k, size, color);
    }

    private static final java.util.WeakHashMap<Bitmap, RectF> BMP_INK = new java.util.WeakHashMap<>();

    /** 位图里不透明部分的范围（像素）；自适应图标的前景层四周有大片透明，按它居中、缩放才和别的图标一样大一样正。 */
    static synchronized RectF inkBounds(Bitmap bm) {
        RectF b = BMP_INK.get(bm);
        if (b != null) return b;
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bt = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((px[y * w + x] >>> 24) > 24) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y;
        }
        b = r < l ? new RectF(0, 0, w, h) : new RectF(l, t, r + 1, bt + 1);
        BMP_INK.put(bm, b);
        return b;
    }

    /** 按不透明部分居中画位图：较长的一边缩到 size，中心对准 (cx, cy)。 */
    static void bitmapCentered(Canvas c, Bitmap bm, float cx, float cy, float size, Paint paint) {
        RectF b = inkBounds(bm);
        float k = size / Math.max(b.width(), b.height());
        RectF dst = new RectF(cx - (b.centerX()) * k, cy - (b.centerY()) * k, cx + (bm.getWidth() - b.centerX()) * k, cy + (bm.getHeight() - b.centerY()) * k);
        c.drawBitmap(bm, null, dst, paint);
    }

    /** flex 纵向 justify-content: space-between：三个子项（中间那个高度为 0 时当没有）的顶边。 */
    private static float[] spaceBetween(float top, float bottom, float h0, float h1, float h2) {
        boolean has1 = h1 > 0;
        int n = has1 ? 3 : 2;
        float free = Math.max(0, bottom - top - h0 - h1 - h2);
        float gap = free / (n - 1);
        float y1 = top + h0 + (has1 ? gap : 0), y2 = has1 ? y1 + h1 + gap : bottom - h2;
        return new float[] {top, y1, y2};
    }

    /** CSS 行盒里的基线：半行距加在上下。 */
    static float baseline(Paint p, float lineTop, float lineHeight) {
        float size = p.getTextSize(), a = PM[0] * size, d = PM[1] * size;   // 只按主字体 Inter 的上下高度（同 CSS）
        return lineTop + (lineHeight - (a + d)) / 2f + a;
    }

    /** Inter 每 1px 字号的 {ascent, descent}，见 Fonts.primaryMetrics。 */
    private static float[] PM = {0.96875f, 0.2412f};

    // ---------- 按下倾斜（Windows Phone TiltEffect） ----------

    /** 0..1：WP TiltEffect——按下立刻倾到位；松手先停 0.2 秒、再 0.1 秒匀速回正；被点去启动的那块一直保持倾着，直到它自己翻走。 */
    private float tiltAmount(long now) {
        if (pressDown) return Curves.TILT_IN.getInterpolation(prog(now, pressT0, TILT_MS));
        return releaseK * (1f - Curves.TILT_OUT.getInterpolation(prog(now, pressT0 + Math.round(TILT_HOLD_MS * slow), TILT_OUT_MS)));
    }

    private float releaseK;                        // 松手那一刻倾了多少（从这里回正）

    private void press(int idx, boolean down, float x, float y) {
        if (idx < 0) return;
        if (down) {
            Tile t = tiles.get(idx);
            // 按压倾斜（用户指定）：
            //  · 按在角上：按下的角陷进去、对角翘起来，另外两个角始终钉住不动 → 绕那两个角连成的对角线转；
            //  · 按在边上：绕卡片中轴转（跷跷板）：按的那边下去，对边翘起来；
            //  · 按在正中间：整块均匀往下陷一点。
            // 角度统一（用户 2026-10-25 "所有卡片都统一翻转角度"）：边上 TILT_EDGE_DEG、角上 TILT_CORNER_DEG。
            float cx = t.x + t.w / 2f, cy = t.y + t.h / 2f;
            float nx = clamp((x - cx) / (t.w / 2f), -1, 1), ny = clamp((y - cy) / (t.h / 2f), -1, 1);
            float ax = Math.abs(nx), ay = Math.abs(ny);
            if (ax < 0.3f && ay < 0.3f) {
                tiltMode = 0;
            } else if (ax > 0.55f && ay > 0.55f) {
                // 对角线：经过 (sx, −sy) 和 (−sx, sy) 两个角，方向 (sx·w, −sy·h)
                float sx = Math.signum(nx), sy = Math.signum(ny);
                tiltAxis = (float) Math.atan2(-sy * t.h, sx * t.w);
                // 按下的那个角转到对角线坐标系里离轴多远、在轴的哪一边
                float px = sx * t.w / 2f, py = sy * t.h / 2f;
                float d = (float) (-Math.sin(tiltAxis) * px + Math.cos(tiltAxis) * py);
                float deg = TILT_CORNER_DEG;
                tiltDeg = d < 0 ? deg : -deg;                 // rotateX 正 = 轴"上方"往里
                tiltMode = 3;
            } else if (ax >= ay) {
                float deg = TILT_EDGE_DEG;
                tiltDeg = nx >= 0 ? deg : -deg;               // rotateY 正 = 右边往里
                tiltMode = 1;
            } else {
                float deg = TILT_EDGE_DEG;
                tiltDeg = ny < 0 ? deg : -deg;                // rotateX 正 = 上边往里
                tiltMode = 2;
            }
            pressIdx = idx; pressDown = true; pressT0 = SystemClock.uptimeMillis();
        } else if (pressIdx == idx && pressDown) {
            // 已经倾了多少，就从那里开始回正；pressT0 记松手的时刻
            long now = SystemClock.uptimeMillis();
            releaseK = tiltAmount(now);
            pressDown = false;
            pressT0 = now;
        }
        invalidate();
    }

    // ---------- 活卡片翻面 / 翻页 / 转门的进度 ----------

    /** 活动磁贴：42% 处翻到背面、周期末尾翻回来，每次翻 FLIP_MS（0.85 秒，WP HubTile 三次方缓出）；曲线用 Curves.GRID_FLIP。 */
    private float flipAngle(Tile t, long now) {
        if (!t.live) return 0f;
        long tt = now - flipBase - t.flipDelay;
        if (tt < 0) return 0f;
        long in = tt % FLIP_CYCLE, a = FLIP_CYCLE * 42 / 100, c2 = FLIP_CYCLE - FLIP_MS;
        if (in < a) return 0f;
        if (in < a + FLIP_MS) return 180f * Curves.GRID_FLIP.getInterpolation((in - a) / (float) FLIP_MS);
        if (in < c2) return 180f;
        return 180f + 180f * Curves.GRID_FLIP.getInterpolation((in - c2) / (float) FLIP_MS);
    }

    /** 离下一次翻动还有多久（正在翻 = 0）。 */
    private long msToNextFlip(Tile t, long now) {
        long tt = now - flipBase - t.flipDelay;
        long a = FLIP_CYCLE * 42 / 100, b = a + FLIP_MS, c2 = FLIP_CYCLE - FLIP_MS;
        if (tt < 0) return -tt + a;
        long in = tt % FLIP_CYCLE;
        if ((in >= a && in < b) || in >= c2) return 0;
        return in < a ? a - in : c2 - in;
    }

    /** 进出设置 / 编辑页：和打开应用一样的 WP 转门，按行从上往下每行晚 50ms；翻走 0.25 秒往外翻到 50°，翻回 0.35 秒从外面 50° 转平。NaN = 已经翻走。 */
    private float turnAngle(Tile t, long now) {
        if (turnDir == 0) return 0f;
        long dur = turnDir > 0 ? TS_OUT : TS_IN;
        Curves.Curve cv = turnDir > 0 ? Curves.GRID_TURN_OUT : Curves.GRID_TURN_IN;
        float k = clamp((cv.vt(turnEl(now), turnSpan) - tsRow(t) * turnStg()) / dur, 0, 1);
        if (turnDir > 0) return k >= 1f ? Float.NaN : TS_DEG * cv.shape(k);
        return TS_DEG * (1f - cv.shape(k));
    }

    private int colOrder(Tile t) {
        boolean[] seen = new boolean[COLS];
        for (Tile o : tiles) seen[o.col] = true;
        int order = 0;
        for (int c2 = 0; c2 < t.col; c2++) if (seen[c2]) order++;
        return order;
    }

    /** 进出设置页这一组从第一行起步到最后一块走完（ms，没乘慢放）。 */
    private long turnEnd() {
        long end = 0, dur = turnDir > 0 ? TS_OUT : TS_IN;
        for (Tile t : tiles) if (visible(t)) end = Math.max(end, tsRow(t) * turnStg() + dur);
        return end;
    }

    private long turnLength() { return Math.round(turnEnd() * slow); }

    /** 翻页：翻出去（then 在翻完时调用）或翻回来。 */
    private void turn(int dir, Runnable then) {
        if (dir > 0) finishEntering();
        turnDir = dir;
        turnT0 = SystemClock.uptimeMillis();
        turnSpan = turnEnd(); turnE0 = 0f; turnRate = 1f;
        turnDone = then;
        postDelayed(() -> {
            if (turnDir != dir) return;
            if (dir < 0) turnDir = 0;
            Runnable r2 = turnDone;
            turnDone = null;
            if (r2 != null) r2.run();
            invalidate();
        }, turnLength());
        invalidate();
    }

    /** 从 Tessera 自己的页面回来：磁贴按列翻回来。 */
    public void turnIn() {
        // 从应用列表进的设置页回来时，磁贴还是"都转门翻走了"的状态：先清掉，不然翻回来也看不见
        tsGone = false; tsDir = 0; returnId = null;
        turnDir = 0; turn(-1, null);
    }

    // ---------- 从应用列表拖进来（用户 2026-10-25：应用列表里长按拖到开始屏） ----------

    private boolean external;          // 拿着的这块是从应用列表拖进来的（触摸由应用列表转发，坐标是屏幕像素）

    /** 让 id 这块（刚钉上去的或者本来就在开始屏上的）直接变成"拿着"，中心在手指下面。没有这块返回 false。 */
    boolean beginExternal(String id, float xPx, float yPx) {
        int idx = -1;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).id.equals(id)) idx = i;
        if (idx < 0) return false;
        removeCallbacks(hold);
        pressIdx = -1; hit = -1; lock = 0; settleIdx = -1;
        liftIdx = idx;
        external = true;
        movedSinceLift = true;                         // 放下时不进编辑页
        ghostX = xPx / scale; ghostY = yPx / scale; ghostOx = 0f; ghostOy = 0f;
        liftT0 = SystemClock.uptimeMillis() + 16;
        edgeScroll();
        invalidate();
        return true;
    }

    void externalMove(float xPx, float yPx) {
        if (!external || liftIdx < 0) return;
        ghostX = xPx / scale; ghostY = yPx / scale;
        invalidate();
    }

    /** 松手：排到离手指最近的那块的位置（同自己拖的放下）。 */
    void externalDrop(float xPx, float yPx, boolean cancel) {
        if (!external) return;
        external = false;
        if (liftIdx >= 0) drop(xPx / scale, yPx / scale, cancel);
    }

    /** 拖着贴近上下边缘时网格自己滚（离边 24 以内，越近越快）。 */
    private void edgeScroll() {
        // 自己的逐帧循环：别用 run()——它会让 motion 自增，正在翻回来的转门收不了尾（tsDir 一直不归零，之后不接触摸）
        final long[] last = {0};
        postOnAnimation(new Runnable() {
            @Override public void run() {
                if (!external || liftIdx < 0) return;
                long now = SystemClock.uptimeMillis();
                float dt = last[0] == 0 ? 16f : Math.min(40f, now - last[0]);
                last[0] = now;
                float vh = viewH(), edge = 24f, v = 0f;
                if (ghostY < edge) v = -(edge - Math.max(0f, ghostY)) / edge * 0.4f;
                else if (ghostY > vh - edge) v = (ghostY - (vh - edge)) / edge * 0.4f;
                if (v != 0f) { pos = clamp(pos + v * dt, 0, maxScroll()); invalidate(); }
                postOnAnimation(this);
            }
        });
    }

    /** 转门里每块的起始时间：按屏幕上的行从上往下每行晚 40ms；被点的那块等其他都翻完了再走。 */
    private long tsDelay(int i) {
        if (tsDir > 0 && i == tsSel) return tsLastOthers();
        return tsRow(tiles.get(i)) * tsStg();
    }

    /** 转门每行之间晚多少 ms（调曲线网页可改）。 */
    private long tsStg() { return (tsDir < 0 ? Curves.GRID_TS_IN : Curves.GRID_TS_OUT).stagger(TS_STAGGER); }

    /** 翻页每列之间晚多少 ms（调曲线网页可改）。 */
    private long turnStg() { return (turnDir < 0 ? Curves.GRID_TURN_IN : Curves.GRID_TURN_OUT).stagger(TURN_STAGGER); }

    /** 这块在屏幕上是第几行（只数屏幕里看得见的行）。 */
    private int tsRow(Tile t) {
        int first = (int) Math.floor(pos / (TILE + GAP));
        return Math.max(0, t.row - first);
    }

    private long tsLastOthers() {
        long end = 0;
        for (int i = 0; i < tiles.size(); i++) if (i != tsSel && visible(tiles.get(i))) end = Math.max(end, tsRow(tiles.get(i)) * tsStg() + TS_OUT);
        return end;
    }

    private boolean visible(Tile t) { return t.y + t.h > pos && t.y < pos + viewH(); }

    /** 转门角度：翻出去 0 → 往外 50°（右边朝人、往左甩走），翻回来反过来（WP）。NaN = 已经翻走。 */
    private float turnstileAngle(int i, long now) {
        if (tsDir == 0) return 0f;
        long dur = tsDir > 0 ? TS_OUT : TS_IN;
        Curves.Curve cv = tsDir > 0 ? Curves.GRID_TS_OUT : Curves.GRID_TS_IN;
        float k = clamp((cv.vt(tsEl(now), tsSpan) - tsDelay(i)) / dur, 0, 1);
        if (tsDir > 0) return k >= 1f ? Float.NaN : TS_DEG * cv.shape(k);
        return TS_DEG * (1f - cv.shape(k));
    }

    /** 转门整组从第一块起步到最后一块走完（ms，没乘慢放）。 */
    private long tsEnd(boolean out) {
        long end = 0;
        for (int i = 0; i < tiles.size(); i++) if (visible(tiles.get(i))) end = Math.max(end, tsDelay(i) + (out ? TS_OUT : TS_IN));
        return end;
    }

    private long tsLength(boolean out) { return Math.round(tsEnd(out) * slow); }

    /** 启动：被点的卡片倾着不动，其他卡片按行翻走；它最后翻走，翻完拉起应用。 */
    private void launch(int idx) {
        finishEntering();
        Tile t = tiles.get(idx);
        tsSel = idx;
        tsDir = 1;
        tsT0 = SystemClock.uptimeMillis();
        tsSpan = tsEnd(tsDir > 0); tsE0 = 0f; tsRate = 1f;
        tsGone = false;
        returnId = t.id;
        final int me = ++motion;
        long len = tsLength(true);
        postDelayed(() -> {
            if (motion != me || tsDir != 1) return;
            tsGone = true;                               // 都翻走了：留着空屏，等应用翻进来盖住
            pressIdx = -1;
            if (callback != null) callback.onLaunch(t.pkg, t.cls, t.name);
            invalidate();
        }, len);
        // 兜底：应用没起来时把卡片翻回来（同 WebView 版的 1500ms）
        // 应用起来了桌面就没焦点了：只在还有焦点（真没起来）时翻回，否则回来时就没有翻回的动画了
        postDelayed(() -> { if (motion == me && tsGone && returnId != null && hasWindowFocus()) returnFromApp(); }, len + TS_HOLD);
        invalidate();
    }

    /** 进搜索：所有卡片按行转门翻走（没有"最后走"的那块），翻完调 then；之后用 returnFromApp() 翻回来。 */
    public boolean turnstileOutAll(Runnable then) {
        if (entering()) finishEntering();
        if (tsDir != 0 || tsGone || turnDir != 0 || liftIdx >= 0) return false;
        tsSel = -1;
        tsDir = 1;
        tsT0 = SystemClock.uptimeMillis();
        tsSpan = tsEnd(tsDir > 0); tsE0 = 0f; tsRate = 1f;
        pressIdx = -1;
        final int me = ++motion;
        postDelayed(() -> {
            if (motion != me || tsDir != 1) return;
            tsGone = true;
            invalidate();
            if (then != null) then.run();
        }, tsLength(true));
        invalidate();
        return true;
    }

    /** 从应用回来：卡片按行从上往下翻回来。 */
    public void returnFromApp() {
        if (returnId == null && !tsGone) return;
        returnId = null;
        tsGone = false;
        tsSel = -1;
        pressIdx = -1;
        tsDir = -1;
        tsT0 = SystemClock.uptimeMillis();
        tsSpan = tsEnd(tsDir > 0); tsE0 = 0f; tsRate = 1f;
        final int me = ++motion;
        postDelayed(() -> { if (motion == me && tsDir == -1) { tsDir = 0; invalidate(); } }, tsLength(false));
        invalidate();
    }

    /** 从表盘进来时总是从顶上开始（WP：按开始键回到最上面）。 */
    void scrollToTop() { motion++; pos = 0; invalidate(); }

    /** 隐藏 / 回到表盘时把一次性的状态清掉。 */
    public void resetTransient() {
        motion++;
        returnId = null; tsGone = false; tsDir = 0; tsSel = -1; ftDir = 0;
        external = false;
        handedOff = false; topWait = false; handIdx = -1;
        removeCallbacks(toFace);
        liftIdx = -1; settleIdx = -1; pressIdx = -1;
        turnDir = 0; turnDone = null; pull = 0; pulling = false;
        removeCallbacks(hold);
        setEnterY(0);
        invalidate();
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        // 拖动跟踪用屏幕坐标：顶上下拉时整层跟着手指下移，层内坐标会把位移吃掉一半（只跟一半、松手又弹回）
        float fy = y + getTranslationY() / scale;
        long now = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 翻进来的时候按下：剩下的一段快速走完，手势照常接；正在翻走（离开开始屏）时不接
                if (entering()) fastForward(now);
                if (turnDir > 0 || tsDir > 0 || tsGone || ftDir < 0) return false;
                motion++;                       // 停下惯性、回弹
                downX = x; downY = fy; lock = 0; sn = 0; pulling = pulled = false;
                float m = maxScroll(), vh = viewH();
                startRaw = pos > m ? m + unrubber(pos - m, vh) : pos;
                atTopAtDown = pos <= 1f;
                sample(now, fy);
                hit = hitIndex(x, y + pos);
                movedSinceLift = false;
                if (hit >= 0 && settleIdx < 0) { press(hit, true, x, y + pos); postDelayed(hold, Math.round(HOLD_MS * slow)); }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (handedOff) { if (callback != null) callback.onFaceDrag(x, fy); return true; }
                float dx = x - downX, dy = fy - downY;
                if (liftIdx >= 0) {
                    ghostX = x; ghostY = y;
                    if (Math.abs(dx) > HOLD_SLOP || Math.abs(dy) > HOLD_SLOP) movedSinceLift = true;
                    boolean atTop = fy < TOP_EDGE;
                    if (atTop && !topWait) { topWait = true; postDelayed(toFace, Math.round(HOLD_MS * slow)); }
                    else if (!atTop && topWait) { topWait = false; removeCallbacks(toFace); }
                    invalidate();
                    return true;
                }
                if (Math.abs(dx) > HOLD_SLOP || Math.abs(dy) > HOLD_SLOP) { removeCallbacks(hold); press(hit, false, 0, 0); }
                if (lock == 0) {
                    if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return true;
                    lock = Math.abs(dy) >= Math.abs(dx) ? 1 : -1;
                    lockY = downY + (dy > 0 ? SLOP : -SLOP);     // 吃掉判定的那几像素，开始滚动时不跳
                    press(hit, false, 0, 0);
                }
                if (lock != 1) return true;
                sample(now, fy);
                float raw = startRaw - (fy - lockY), m = maxScroll(), vh = viewH();
                if (raw < 0) {                                  // 顶上继续往下拉：记下距离，松手时决定回不回表盘（设计稿里列表不跟手）
                    pulling = pulled = true;
                    pull = -raw;
                    pos = 0;
                } else {
                    if (pulling) { pulling = false; pull = 0; }
                    pos = raw > m ? m + rubber(raw - m, vh) : raw;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                removeCallbacks(hold);
                removeCallbacks(toFace); topWait = false;
                if (handedOff) { handedOff = false; if (callback != null) callback.onFaceDrop(x, fy, cancel); return true; }
                if (liftIdx >= 0) { drop(x, y, cancel); return true; }
                float v = cancel ? 0f : fingerV(now);
                if (lock == 0) {
                    if (!cancel && hit >= 0 && settleIdx < 0) { tap(hit); return true; }
                    press(hit, false, 0, 0);
                } else if (lock == 1) {
                    if (pulling) pullRelease(v);
                    else { pull = 0; settle(-v); }
                } else if (!cancel && callback != null && Math.abs(fy - downY) < 40f) {
                    if (downX < EDGE && x - downX > EDGE_DX) callback.onBack();          // 左边缘右滑：返回
                    else if (downX - x > EDGE_DX) callback.onSearch();                   // 从右往左滑：搜索
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void sample(long t, float y) {
        if (sn == sy.length) { System.arraycopy(sy, 1, sy, 0, sn - 1); System.arraycopy(st, 1, st, 0, sn - 1); sn--; }
        sy[sn] = y; st[sn] = t; sn++;
    }

    /** 手指速度（设计像素/ms，向下为正）：最近 100ms 的位移；抬手前停住超过 80ms 算 0。 */
    private float fingerV(long now) {
        if (sn < 2 || now - st[sn - 1] > 80) return 0f;
        int i = sn - 1;
        while (i > 0 && st[sn - 1] - st[i - 1] <= 100) i--;
        long dt = st[sn - 1] - st[i];
        return dt > 0 ? (sy[sn - 1] - sy[i]) / dt : 0f;
    }

    private int hitIndex(float x, float y) {
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            if (x >= t.x && x <= t.x + t.w && y >= t.y && y <= t.y + t.h) return i;
        }
        return -1;
    }

    private void tap(int idx) {
        Tile t = tiles.get(idx);
        if ("settings".equals(t.kind)) {               // 设置：Tessera 自己的页面，磁贴先按列翻出去
            press(idx, false, 0, 0);
            turn(1, () -> { if (callback != null) callback.onOpenPage("settings", t.id); });
        } else {
            launch(idx);                               // 应用：倾着不动，其他卡片转门翻走
        }
    }

    // ---------- 拿起 / 放下 ----------

    /** 拿着的磁贴在上沿停够了：交给表盘（表盘那边接管这块的样子和之后的拖动）。 */
    private void handOff() {
        topWait = false;
        if (liftIdx < 0 || liftIdx >= tiles.size() || callback == null || handedOff) return;
        Tile t = tiles.get(liftIdx);
        long now = SystemClock.uptimeMillis();
        float k = now < liftT0 ? 0f : Curves.GRID_LIFT.getInterpolation(prog(now, liftT0, LIFT_MS));
        float cx = ghostX - ghostOx, cy = ghostY - ghostOy;           // 拿起的磁贴中心（屏幕坐标）
        Bitmap look = snapshot(t, false);
        float rad = k * ("s".equals(t.size) ? R_LIFT_S : R_LIFT);
        if (!callback.onLiftToFace(t.pkg, t.kind, t.size, t.bg, look, cx - t.w / 2f, cy - t.h / 2f, t.w, t.h, ghostX, ghostY, rad, 1f + 0.08f * k)) return;
        handIdx = liftIdx;
        liftIdx = -1;
        handedOff = true;
        pressIdx = -1;
        invalidate();
    }

    private void lift() {
        if (hit < 0 || hit >= tiles.size() || lock != 0) return;
        Tile t = tiles.get(hit);
        press(hit, false, 0, 0);
        pressIdx = -1;
        liftIdx = hit;
        ghostX = downX; ghostY = downY;
        ghostOx = downX - (t.x + t.w / 2f);
        ghostOy = downY + pos - (t.y + t.h / 2f);
        liftT0 = SystemClock.uptimeMillis() + 16;      // 先方着画一帧，下一帧才变圆、放大
        android.os.Vibrator vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(10, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        invalidate();
    }

    /** 放下：排到离手指最近的那块的位置；落地时带着圆角，下一帧开始 240ms 收回直角。没挪动就去编辑页。 */
    private void drop(float x, float y, boolean cancel) {
        int from = liftIdx;
        liftIdx = -1;
        Tile t = tiles.get(from);
        int to = from;
        if (!cancel) {
            float best = Float.MAX_VALUE, fy = y + pos;
            for (int i = 0; i < tiles.size(); i++) {
                Tile o = tiles.get(i);
                float ddx = x - (o.x + o.w / 2f), ddy = fy - (o.y + o.h / 2f), d = ddx * ddx + ddy * ddy;
                if (d < best) { best = d; to = i; }
            }
        }
        if (to != from) {
            tiles.remove(from);
            tiles.add(to, t);
            layoutTiles();
            pos = clamp(pos, 0, maxScroll());
            if (callback != null) {
                JSONArray ids = new JSONArray();
                for (Tile o : tiles) ids.put(o.id);
                callback.onReorder(ids.toString());
            }
        }
        settleIdx = tiles.indexOf(t);
        settleT0 = SystemClock.uptimeMillis() + 16;
        if (!cancel && !movedSinceLift) {               // 长按不动就松手 → 编辑这块磁贴（同 WebView 版）
            postDelayed(() -> turn(1, () -> { if (callback != null) callback.onOpenPage("edit", t.id); }), EDIT_DELAY);
        }
        invalidate();
    }

    // ---------- 滚动物理（同 ListScroller） ----------

    private interface Step { boolean run(float dt); }

    /** 逐帧跑 step(dt)，返回 true 继续；新的 run / 触摸会让旧的自动停下。 */
    private void run(Step step) {
        final int me = ++motion;
        final long[] last = {0};
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (me != motion) return;
                long now = SystemClock.uptimeMillis();
                float dt = last[0] == 0 ? 16f : Math.min(40f, now - last[0]);
                last[0] = now;
                boolean more = step.run(dt);
                invalidate();
                if (more && me == motion) postOnAnimation(this);
            }
        });
    }

    /** 从过冲 x0 带着速度 v0 回到边缘：临界阻尼 x(t) = (x0 + (v0 + w·x0)·t)·e^(−w·t)。 */
    private void springTo(float edge, float x0, float v0) {
        final float[] tt = {0};
        run(dt -> {
            tt[0] += dt;
            float xx = (float) ((x0 + (v0 + SPRING_W * x0) * tt[0]) * Math.exp(-SPRING_W * tt[0]));
            if (tt[0] > 60 && Math.abs(xx) < 0.3f) { pos = edge; return false; }
            pos = edge + xx;
            return true;
        });
    }

    /** 松手：在边缘外就回弹；否则惯性滑行，撞到边缘带着剩余速度回弹。 */
    private void settle(float v) {
        float m = maxScroll();
        if (pos < 0) { springTo(0, pos, clampV(v)); return; }
        if (pos > m) { springTo(m, pos - m, clampV(v)); return; }
        if (Math.abs(v) < 0.02f) return;
        final float[] vv = {v};
        run(dt -> {
            pos += vv[0] * dt;
            vv[0] *= (float) Math.pow(DECEL, dt);
            float m2 = maxScroll();
            if (pos < 0 || pos > m2) {
                float edge = pos < 0 ? 0 : m2;
                springTo(edge, pos - edge, clampV(vv[0]));
                return false;
            }
            return Math.abs(vv[0]) >= 0.02f;
        });
    }

    /** 表冠：每格 51 设计像素，250ms 缓出。 */
    public void crown(int delta) {
        float from = clamp(pos, 0, maxScroll()), to = clamp(from + delta * 51f, 0, maxScroll());
        if (from == to) return;
        final float[] tt = {0};
        run(dt -> {
            tt[0] += dt;
            float k = Curves.CROWN.getInterpolation(clamp(tt[0] / CROWN_MS, 0, 1));
            pos = from + (to - from) * k;
            return k < 1f;
        });
    }

    // ---------- 顶上下拉回表盘 ----------

    /** 整层的竖直位置（像素）：同时算出进场进度，让每一行按进度从深处浮上来 / 沉下去。 */
    void setEnterY(float px) {
        setTranslationY(px);
        setClipToOutline(px != 0f);                  // 往下拉时上沿露出圆角
        float h = getHeight() > 0 ? getHeight() : SIZE * scale;
        enterP = clamp(1f - px / h, 0, 1);
        invalidate();
    }

    private void setPull(float p) {
        pull = Math.max(0, p);
        setEnterY(pull * scale);
        if (callback != null) callback.onPull(Math.min(1f, pull / viewH()));
    }

    /** 松手：在顶上往下滑超过 40，或超过 12 且往下快甩（> 0.4 设计像素/ms）就回表盘（设计稿 FaceToApps 的 onUp）。 */
    private void pullRelease(float v) {
        float vel = atTopAtDown ? v : 0f;
        boolean commit = pull > 40f || (pull > 12f && vel > 0.4f);
        pulling = false;
        pull = 0;
        if (callback != null) callback.onPullEnd(commit);
    }

    // ---------- 小工具 ----------

    private static float rubber(float x, float d) { return (1 - 1 / (x * 0.55f / d + 1)) * d; }

    private static float unrubber(float y, float d) { return y >= d * 0.99f ? d * 180 : (1 / (1 - y / d) - 1) * d / 0.55f; }

    private static float clampV(float v) { return Math.max(-BOUNCE_V, Math.min(BOUNCE_V, v)); }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
