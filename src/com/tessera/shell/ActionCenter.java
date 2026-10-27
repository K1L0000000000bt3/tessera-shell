package com.tessera.shell;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 通知中心 + 控制中心（用户 2026-10-24：按之前的 Windows Metro 风格整个重新设计，新增控制中心）。
 * 一块从表盘上面拉下来的面板，没有标题、没有时间（用户 10-25 去掉了 Pivot 和时间），内容从屏幕顶边铺下来；
 * 左右滑在两页之间切换，像 WP Pivot 一样是一条首尾相接的环：左滑继续往左走到下一页，右滑往回走；切换时内容按行错开滑进来。
 * · 通知：每条一块铺满屏宽的直角磁贴，4 的黑缝隔开；最新一条用主题色，其余 tile-base。按下倾斜（TiltEffect），
 *   松手后整页按行转门翻走、被点的那块最后走，应用顺着同一方向翻进来；往右划掉；滚到底继续上拉收起。
 * · 控制：按开始屏的格子排（一格 = 屏宽 1/4），可以切到 1/2、1/4 格（用户 10-25 定的精度，不再更细）：
 *   亮度 / 音量是整行宽、半格高的条（主题色填充跟手）；开关和信息是一格见方，一行四个（开 = 主题色、关 = tile-soft，状态用字写）；
 *   有媒体时最上面一块整行宽、一格高的「正在播放」。长按开关进对应的系统设置页。
 * 显示密度：和系统界面统一（用户 10-25），按 Android 的 dp 画（wm density 240 时 1 dp = 1.5 屏幕像素），
 * 不再按"屏宽 200"放大；改了系统密度这里跟着变。贴着屏幕边的字照用户的横幅规则放（离边 30 屏幕像素，角上的头一行同横幅的位置）。
 * 手势的距离 / 速度阈值按屏幕像素换算，手感和以前一样。
 */
final class ActionCenter extends View {
    interface Listener {
        /** 通知页上左边缘右滑：收起整个面板。 */
        void onClose();
        /** 滚到底继续往上拉的距离（屏幕像素，负数）；0 = 推回去了。 */
        void onPull(float dyPx);
        /** 拉着松手：dy 距离（屏幕像素），vy 速度（屏幕像素/ms，向下为正）。 */
        void onPullEnd(float dyPx, float vyPx);
        /** 转门翻完了：打开这条通知。 */
        void onOpen(ShellData.Notif n);
        void onAction(ShellData.Notif n, int index);
        void onDismiss(ShellData.Notif n);
        void onClearAll();
        void onAllowAccess();
        /** 打开系统设置的某一页（空串 = 设置首页）。 */
        void onSettings(String action);
    }

    // ---------- tokens（dp） ----------
    private static final float SAFE_BOTTOM = 28f;
    // 磁贴之间的缝和开始屏一样宽（用户 2026-10-25）：开始屏是 4 个"屏宽 200"单位 = 9.6 屏幕像素，按 g 换成 dp；一格的边长也就和开始屏小磁贴一样
    private static final float TILE_GAP = 4f;
    private float gap = 6.4f;
    // 两页之间的空隙：切页时两页不再贴着走（用户 2026-10-25：贴着走"有割裂，看着很难受"）
    private static final float PAGE_GAP = 24f;
    // 贴着屏幕边的字（屏幕像素，同横幅 ToastView 钉死的位置）：正文离边 30；头一行图标离左边 40、右边的时间离右边 43；头一行行框顶 20
    private static final float EDGE_TEXT_PX = 30f, HEAD_LEFT_PX = 40f, HEAD_RIGHT_PX = 43f, HEAD_TOP_PX = 20f;
    // 通知磁贴
    // 通知磁贴（用户 2026-10-25 的草图）：左边一个圆形图标，右边三行字——标题（右边是相对时间）+ 最多两行正文，整块上下居中
    private static final float C_PT = 9f, C_HEAD = 14f, C_TITLE = 20f, C_BODY = 18f, C_AGAP = 8f, C_ACT = 26f, C_PB = 10f;
    private static final float N_ICON = 44f, N_GAP = 12f, N_PAD = 10f, N_ICON_ART = 32f, N_GLYPH = 30f;
    // 控制磁贴：格子里的内边距、图标
    // 控制中心的图标统一一个大小（用户 2026-10-25："尽量统一图标大小并保证舒适视觉效果"）：方块里居中，滑条上和第一列方块的图标中心对齐
    // 图标和开始屏小磁贴一样大（用户 2026-10-25）：22 个"屏宽 200"单位，乘 g 换成 dp（方块本身也和小磁贴一样大）
    private static final float T_PAD = 8f, TILE_ICON = 22f;
    // 手感（同 PageView / ListScroller）；这一组是"屏宽 200"的设计单位，用的时候乘 g 换成 dp（物理距离不变）
    private static final float DECEL = 0.998f, SPRING_W = 0.02f, BOUNCE_V = 1.5f, SLOP = 6f, FLING = 0.35f, COMMIT = 0.35f, EDGE = 14f;
    // 动效
    // 切页时整页一起走（不再按行错开：错开时各行左右参差，也是"割裂"）
    // WP：进来 / 滑入 0.35 秒，出去 / 滑出 0.25 秒，转门转到 50°、每行晚 0.05 秒；按下立刻倾
    private static final long PIVOT_MS = 350, PIVOT_STAGGER = 0, TILT_MS = 16, TS_OUT = 250, TS_IN = 350, TS_STAGGER = 50;
    private static final float TS_DEG = TileGridView.TS_DEG;
    // 划掉：滑出去 200ms；滑出 90ms 时让出位置，下面的从上往下一张接一张补上来（每张晚 40ms，380ms，带一点过冲的果冻感）——用户 2026-10-25
    private static final long CLOSE_AT = 90, SHIFT_MS = 350, SHIFT_STAGGER = 50;
    private static final long LEAVE_MS = 250, BACK_MS = 350, CLEAR_STAGGER = 50, FADE_MS = 200, HOLD_MS = 400, CROWN_MS = 250;
    private static final long STATE_HOLD = 900;               // 刚点过的开关：这么久之内不让旧的系统状态把它改回去
    // 按压倾斜：角度、透视、下陷和开始屏磁贴统一（TileGridView.TILT_*，用户 2026-10-25）；透视是屏宽 200 的设计单位（乘 g）。
    // 轻点也先倾到底、停 80ms 再用 160ms 回正（否则轻点几乎看不出来）
    private static final float PERSP_TS = 600f;
    private static final long TILT_HOLD_MS = 200, TILT_OUT_MS = 100;   // WP TiltEffect：松手停 0.2 秒、0.1 秒匀速回正
    private static final String[] RADIO = {"关", "打开中", "开", "已连接", "关闭中"};
    private static final String POWER_USAGE = "android.intent.action.POWER_USAGE_SUMMARY";

    Theme theme;
    private final TileGridView glyphs;          // 借它画设计稿图标
    private final SysControls sys;
    private Listener listener;
    /** 应用图标（自适应图标的前景层 / 老式整张图标），通知左边的圆里放它。 */
    java.util.function.Function<String, Bitmap> appIcon;
    private float scale = 1.5f;                 // 屏幕像素 / dp（系统显示密度）
    private float W = 320f, g = 1.6f;           // 屏宽（dp）；g = 旧的"屏宽 200"设计单位换成 dp 的倍数
    private float edgeText, headLeft, headRight, headTop;   // 上面那几条屏幕像素规则换成 dp
    private float cell = 77f;                   // 一格（屏宽 1/4）

    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG), lab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cap = new TextPaint(Paint.ANTI_ALIAS_FLAG), t15 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t13 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint btn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), snapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();

    // ---------- 页面：px 是环上的连续位置，整数 k 对应第 k mod 2 页 ----------
    private int page;                           // 当前页（0 通知，1 控制）
    private float px;                           // 拖动 / 切换中的位置
    private float pxBase;                       // 按下时停在哪一页
    private boolean pivAnim;
    private float pxFrom, pxTo;
    private long pivT0;
    private final float[] pos = new float[2];   // 两页各自的滚动位置

    // ---------- 通知 ----------
    static final class Card {
        ShellData.Notif n;
        boolean accent, allow;
        String icon = "bell";
        Bitmap appBmp, snap;
        boolean snapAccent;
        String ago = "", title = "";
        String[] body = new String[0];
        float h;
        float dx, dxFrom;                       // 往右划的距离
        long leaveT0, backT0, goneAt, shiftT0;
        boolean noCollapse, gone, sent, closed;   // closed：已经让出位置（不占高度了，还在滑出去 / 淡出）
        float shiftFrom;                          // 补位：还要往上走多少（画的时候加在 y 上，按 Curves.NC_SHIFT 归零）
        // 每帧算出来
        float y, collapse = 1f, alpha = 1f;
    }

    private final List<Card> cards = new ArrayList<>();
    private float clearFrom;                    // 「全部清除」按钮也跟着补位
    private long clearT0;
    private boolean access = true;
    private long emptyT0;
    private boolean wasEmpty;

    // ---------- 控制 ----------
    private static final int TOGGLE = 0, SLIDER = 1, INFO = 2, LINK = 3, MEDIA = 4;

    static final class Ctl {
        final String id, icon, label;
        final int kind;
        float x, y, w, h;
        float k = -1f;                          // 关 0 → 开 1 的颜色过渡
        long t0;
        boolean target;
        Ctl(String id, int kind, String icon, String label) { this.id = id; this.kind = kind; this.icon = icon; this.label = label; }
    }

    private final List<Ctl> ctls = new ArrayList<>();
    private float ctlH;
    // 系统状态（SysControls 在后台线程拍的快照；点开关时先乐观地改，等系统追上）
    private SysControls.State s = new SysControls.State();
    private final HashMap<String, Long> holdUntil = new HashMap<>();
    private float volDrag = -1f;                // 拖音量时的连续位置（松手吸到整格）

    // ---------- 触摸 ----------
    private static final int M_NONE = 0, M_SCROLL = 1, M_PIVOT = 2, M_SWIPE = 3, M_SLIDER = 4, M_EDGE = 5;
    private int mode, motion;
    private float downX, downY, lockX, lockY, startPos, slStart;
    private int lock;
    private boolean pulling, atEdgeAtDown, held;
    private float pullDy;
    private final float[] sx = new float[16], sy = new float[16];
    private final long[] st = new long[16];
    private int sn;

    /** 按到的东西。 */
    private static final class Hit {
        Card card; Ctl ctl;
        int btn = -1;                           // 通知上的操作按钮 / 媒体按钮（0 上一首 1 播放 2 下一首）
        boolean clear;
    }
    private Hit hit;
    private final Runnable holdR = this::onHold;

    // 按压倾斜（同 TileGridView：角上绕对角线、边上绕中轴、中间整块下陷）
    private Object pressObj;
    private boolean pressDown;
    private long pressT0, releaseAt;
    private int tiltMode;
    private float tiltDeg, tiltAxis;
    private int downBtn = -1;
    private Object downBtnOwner;
    private static final Object CLEAR = new Object();

    // 转门（打开通知）
    private int tsDir;                          // 1 翻出去，-1 翻回来
    private long tsT0, tsLastOthers, tsSpan;       // tsSpan：整组的时长（ms，没乘慢放），曲线"整段"模式用
    private Card tsSel;
    private boolean tsGone;

    ActionCenter(Context c, Theme theme, TileGridView glyphs, SysControls sys) {
        super(c);
        this.theme = theme;
        this.glyphs = glyphs;
        this.sys = sys;
        Typeface w400 = Fonts.sans(c.getAssets(), 400), w600 = Fonts.sans(c.getAssets(), 600);
        head.setTypeface(w600); head.setTextSize(11f); head.setLetterSpacing(0.08f);
        lab.setTypeface(w600); lab.setTextSize(11f); lab.setLetterSpacing(0.02f);
        cap.setTypeface(w400); cap.setTextSize(11f);
        t15.setTypeface(w400); t15.setTextSize(15f);
        t13.setTypeface(w400); t13.setTextSize(13f);
        btn.setTypeface(w400); btn.setTextSize(12f); btn.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        applyScale();
    }

    void setListener(Listener l) { listener = l; }

    int page() { return page; }

    private static float slow() { return TileGridView.slow; }

    private float viewH() { return getHeight() > 0 ? getHeight() / scale : W; }

    private static int mod2(float p) { int k = Math.round(p) % 2; return k < 0 ? k + 2 : k; }

    /** 环上两页的相对位置折到 (-1, 1]。 */
    private static float wrap(float d) { return d - 2f * Math.round(d / 2f); }

    // ---------- 密度 ----------

    /** 按系统显示密度重算尺寸（第一次量到大小时、改了 wm density 时）。 */
    private void applyScale() {
        scale = getResources().getDisplayMetrics().density;
        int wpx = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        W = wpx / scale;
        g = (wpx / 200f) / scale;
        edgeText = EDGE_TEXT_PX / scale;
        headLeft = HEAD_LEFT_PX / scale;
        headRight = HEAD_RIGHT_PX / scale;
        headTop = Math.max(C_PT, HEAD_TOP_PX / scale);
        gap = TILE_GAP * g;
        cell = (W - 3f * gap) / 4f;
        for (Card cd : cards) layoutCard(cd);
        layoutCtls();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { applyScale(); }

    @Override
    protected void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); applyScale(); }

    /** 每次拉出来：回到通知页顶上。 */
    void reset() {
        motion++;
        removeCallbacks(holdR);
        page = 0; px = 0f; pxBase = 0f; pivAnim = false;
        pos[0] = 0f; pos[1] = 0f;
        tsDir = 0; tsGone = false; tsSel = null;
        pressObj = null; downBtn = -1; mode = M_NONE; hit = null;
        for (Card cd : cards) { cd.dx = 0f; cd.backT0 = 0; cd.shiftFrom = 0f; }
        clearFrom = 0f;
        refreshControls();
        invalidate();
    }

    // ---------- 通知数据 ----------

    void setNotifs(List<ShellData.Notif> list, boolean hasAccess) {
        access = hasAccess;
        HashMap<String, Card> old = new HashMap<>();
        HashMap<String, Integer> oldIdx = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) { Card cd = cards.get(i); if (cd.n != null) { old.put(cd.n.key, cd); oldIdx.put(cd.n.key, i); } }
        List<Card> next = new ArrayList<>();
        if (!hasAccess) {
            Card cd = new Card();
            cd.allow = true;
            ShellData.Notif n = new ShellData.Notif();
            n.key = "__allow"; n.app = "Tessera"; n.title = "允许读取通知"; n.text = "开始屏和通知中心要用到"; n.when = 0;
            cd.n = n;
            next.add(cd);
        } else {
            long now = SystemClock.uptimeMillis();
            for (ShellData.Notif n : list) {
                Card cd = old.remove(n.key);
                if (cd == null) cd = new Card();
                // 已经划掉、等系统删掉的：常驻通知删不掉会一直在，1.5 秒后还在就放回来
                if (cd.gone && now - cd.goneAt > 1500) { cd.gone = false; cd.sent = false; cd.leaveT0 = 0; cd.dx = 0; cd.collapse = 1f; cd.alpha = 1f; cd.noCollapse = false; cd.closed = false; cd.shiftFrom = 0f; }
                cd.n = n;
                next.add(cd);
            }
            // 系统已经删了、但还在滑出去的那几块：动画放完再走
            for (java.util.Map.Entry<String, Card> e : old.entrySet()) {
                Card cd = e.getValue();
                if (cd.leaveT0 > 0 && !cd.gone) next.add(Math.min(next.size(), oldIdx.get(e.getKey())), cd);
            }
        }
        cards.clear();
        cards.addAll(next);
        for (Card cd : cards) { layoutCard(cd); if (cd.allow) cd.accent = true; }
        reaccent();                                            // 最新的一条用主题色
        pos[0] = Math.max(0f, Math.min(pos[0], maxScroll(0)));
        invalidate();
    }

    /** 每分钟：相对时间（"2分"）重排一遍。 */
    void tick() { for (Card cd : cards) layoutCard(cd); invalidate(); }

    /** 文字从哪儿开始：圆形图标右边（图标离屏幕左边 30 屏幕像素）。 */
    private float textX() { return edgeText + N_ICON + N_GAP; }

    /** 字的宽：到离屏幕右边 30 屏幕像素为止。 */
    private float textW() { return W - edgeText - textX(); }

    private void layoutCard(Card cd) {
        if (cd.n == null) return;
        ShellData.Notif n = cd.n;
        String glyph = ShellData.glyphName(n.pkg);
        cd.icon = glyph.isEmpty() ? "bell" : glyph;
        Bitmap b = glyph.isEmpty() && appIcon != null && !n.pkg.isEmpty() ? appIcon.apply(n.pkg) : null;
        cd.appBmp = b;
        cd.ago = cd.allow ? "" : ShellData.ago(n.when);
        float agoW = cd.ago.isEmpty() ? 0f : cap.measureText(cd.ago) + 8f;
        // 标题：通知自己的标题；没有标题就用应用名，正文照放
        String title = n.title.isEmpty() ? n.app : n.title;
        cd.title = fit(title.replace('\n', ' '), t15, textW() - agoW);
        cd.body = wrap2(n.text, t13, textW());
        int na = cd.allow ? 0 : Math.min(2, n.actions.size());
        // 高度统一成控制中心一格（= 开始屏小磁贴，用户 2026-10-25："为啥通知高矮不一"）；带操作按钮的放不下，1.5 格
        cd.h = na > 0 ? cell * 1.5f : cell;
        cd.snap = null;
    }

    /** 图标和三行字那一块的高（两者取高的，上下居中对齐）。 */
    private static float contentH(Card cd) { return Math.max(N_ICON, C_TITLE + cd.body.length * C_BODY); }

    /** 正文最多两行：第一行按词断，第二行放不下就省略号。 */
    private static String[] wrap2(String s, TextPaint p, float w) {
        if (s == null) return new String[0];
        s = s.replace('\n', ' ').trim();
        if (s.isEmpty()) return new String[0];
        if (p.measureText(s) <= w) return new String[] {s};
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), p, (int) Math.floor(w)).setIncludePad(false).build();
        int end = Math.max(1, l.getLineEnd(0));
        String a = s.substring(0, end).trim(), b = s.substring(end).trim();
        if (b.isEmpty()) return new String[] {a};
        return new String[] {a, TextUtils.ellipsize(b, p, w, TextUtils.TruncateAt.END).toString()};
    }

    private static String fit(String s, TextPaint p, float w) { return s == null ? "" : TextUtils.ellipsize(s, p, Math.max(0, w), TextUtils.TruncateAt.END).toString(); }

    private boolean clearable() {
        if (!access) return false;
        for (Card cd : cards) if (!cd.gone && cd.leaveT0 == 0 && cd.n.clearable) return true;
        return false;
    }

    private int visibleCards() { int k = 0; for (Card cd : cards) if (!cd.gone) k++; return k; }

    /** 每块的动画状态（滑出去、弹回来、补位）和纵向位置（不含补位偏移，画的时候加 shiftOf）；返回还有没有在动。 */
    private boolean layoutCards(long now) {
        boolean again = false;
        float y = 0f;
        float sl = slow();
        for (int i = 0; i < cards.size(); i++) {
            Card cd = cards.get(i);
            if (cd.gone) { cd.collapse = 0f; cd.y = y; continue; }
            if (cd.leaveT0 > 0) {
                if (now < cd.leaveT0) { again = true; cd.alpha = 1f; }
                else {
                    float k = Math.min(1f, (now - cd.leaveT0) / (LEAVE_MS * sl));
                    cd.dx = cd.dxFrom + (W - cd.dxFrom) * Curves.NC_CARD_LEAVE.getInterpolation(k);
                    cd.alpha = 1f - k;
                    if (!cd.noCollapse && !cd.closed && now - cd.leaveT0 >= CLOSE_AT * sl) closeGap(i, now);
                    if (k >= 1f && !cd.noCollapse) markGone(cd, now);
                    again |= !cd.gone && !(cd.noCollapse && k >= 1f);
                }
            } else if (cd.backT0 > 0) {
                float k = Math.min(1f, (now - cd.backT0) / (BACK_MS * sl));
                cd.dx = cd.dxFrom * (1f - Curves.NC_CARD_BACK.getInterpolation(k));
                cd.alpha = 1f - Math.min(0.6f, cd.dx / W * 0.6f);
                if (k >= 1f) { cd.backT0 = 0; cd.dx = 0; cd.alpha = 1f; } else again = true;
            } else {
                cd.alpha = 1f - Math.min(0.6f, cd.dx / W * 0.6f);
            }
            cd.collapse = cd.closed || cd.gone ? 0f : 1f;
            cd.y = y;
            y += (cd.h + gap) * cd.collapse;
            if (shiftOf(cd, now) != 0f) again = true;
        }
        if (clearShift(now) != 0f) again = true;
        return again;
    }

    /** 第 idx 块让出位置：它下面的每一块（和「全部清除」）往上补它那么高，从上往下一张接一张起步。 */
    private void closeGap(int idx, long now) {
        Card cd = cards.get(idx);
        cd.closed = true;
        float d = cd.h + gap, sl = slow();
        int k = 0;
        for (int j = idx + 1; j < cards.size(); j++) {
            Card o = cards.get(j);
            if (o.gone || o.closed) continue;
            o.shiftFrom = shiftOf(o, now) + d;          // 上一次补位还没走完：从现在的位置接着走
            o.shiftT0 = now + Math.round(k * SHIFT_STAGGER * sl);
            k++;
        }
        clearFrom = clearShift(now) + d;
        clearT0 = now + Math.round(k * SHIFT_STAGGER * sl);
    }

    /** 补位还剩多少（dp，往下的偏移）；走完归零。 */
    private float shiftOf(Card o, long now) {
        if (o.shiftFrom == 0f) return 0f;
        float t = (now - o.shiftT0) / (SHIFT_MS * slow());
        if (t <= 0f) return o.shiftFrom;
        if (t >= 1f) { o.shiftFrom = 0f; return 0f; }
        return o.shiftFrom * (1f - Curves.NC_SHIFT.getInterpolation(t));
    }

    private float clearShift(long now) {
        if (clearFrom == 0f) return 0f;
        float t = (now - clearT0) / (SHIFT_MS * slow());
        if (t <= 0f) return clearFrom;
        if (t >= 1f) { clearFrom = 0f; return 0f; }
        return clearFrom * (1f - Curves.NC_SHIFT.getInterpolation(t));
    }

    private void markGone(Card cd, long now) {
        if (cd.gone) return;
        cd.gone = true;
        cd.goneAt = now;
        cd.collapse = 0f;
        cd.snap = null;
        if (!cd.sent) {
            cd.sent = true;
            final ShellData.Notif n = cd.n;
            post(() -> { if (listener != null) listener.onDismiss(n); });     // 画的过程中不改列表
        }
    }

    private float cardsBottom() {
        float y = 0f;
        for (Card cd : cards) if (!cd.gone) y = Math.max(y, cd.y + (cd.h + gap) * cd.collapse);
        return y;
    }

    private float notifsH() {
        if (visibleCards() == 0) return viewH();
        float h = cardsBottom() - gap;
        if (clearable()) h += 12f + 36f;
        return h + SAFE_BOTTOM;
    }

    private float contentH(int pg) { return pg == 0 ? notifsH() : ctlH + SAFE_BOTTOM; }

    private float maxScroll(int pg) { return Math.max(0f, contentH(pg) - viewH()); }

    // ---------- 控制：状态与布局 ----------

    /** 让后台线程重新拍一张系统状态（拍好了走 onState）。 */
    void refreshControls() { if (sys != null) sys.refresh(); }

    private boolean holding(String id, long now) { Long t = holdUntil.get(id); return t != null && now < t; }

    private void hold(String id) { holdUntil.put(id, SystemClock.uptimeMillis() + STATE_HOLD); }

    /** 后台线程拍好的系统状态（主线程）。刚点过的开关、正在拖的滑块不让旧状态改回去。 */
    void onState(SysControls.State n) {
        long now = SystemClock.uptimeMillis();
        SysControls.State o = s;
        if (holding("wifi", now)) n.wifi = o.wifi;
        if (holding("bt", now)) n.bt = o.bt;
        if (holding("dnd", now)) n.dnd = o.dnd;
        if (holding("mute", now)) n.ringer = o.ringer;
        if (holding("torch", now)) n.torch = o.torch;
        if (holding("loc", now)) n.loc = o.loc;
        if (holding("media", now)) n.playing = o.playing;
        if (holding("bright", now) || (mode == M_SLIDER && hit != null && hit.ctl != null && "bright".equals(hit.ctl.id))) n.bright = o.bright;
        if (holding("vol", now) || (mode == M_SLIDER && hit != null && hit.ctl != null && "vol".equals(hit.ctl.id))) { n.vol = o.vol; n.volMax = o.volMax; }
        boolean relayout = n.media != o.media || n.hasTorch != o.hasTorch;
        s = n;
        if (relayout) layoutCtls();
        invalidate();
    }

    /**
     * 按格子排（一格 = 屏宽 1/4，4 的缝；只用整格、1/2 格、1/4 格）：
     * 正在播放（整行 × 一格）→ 四个开关（一格见方）→ 左边 2×2 其余开关 / 右边亮度、音量竖条（一格宽 × 两格高）。
     */
    private void layoutCtls() {
        HashMap<String, Ctl> old = new HashMap<>();
        for (Ctl c : ctls) old.put(c.id, c);
        ctls.clear();
        float y = 0f;
        if (s.media) y = row(old, y, cell, new Ctl("media", MEDIA, "music", "正在播放"));
        // 第一行：四个开关
        Ctl[] top = {new Ctl("wifi", TOGGLE, "wifi", "WLAN"), new Ctl("bt", TOGGLE, "bluetooth", "蓝牙"),
                new Ctl("dnd", TOGGLE, "moon", "勿扰"), new Ctl("mute", TOGGLE, "vibrate", "静音")};
        for (int j = 0; j < 4; j++) put(old, top[j], j * (cell + gap), y, j == 3 ? W - 3 * (cell + gap) : cell, cell);
        y += cell + gap;
        // 下面两行：左边两列是其余的（2×2，不满时最后一块拉宽补满），右边两列是亮度、音量两根竖条（一格宽、两格高；用户 2026-10-25："改成竖着的"）
        List<Ctl> rest = new ArrayList<>();
        if (s.hasTorch) rest.add(new Ctl("torch", TOGGLE, "torch", "手电筒"));
        rest.add(new Ctl("loc", TOGGLE, "map", "位置"));
        rest.add(new Ctl("battery", INFO, "battery", "电池"));
        rest.add(new Ctl("settings", LINK, "settings", "设置"));
        for (int i = 0; i < rest.size(); i++) {
            int r = i / 2, c = i % 2;
            boolean lastAlone = i == rest.size() - 1 && c == 0;
            put(old, rest.get(i), c * (cell + gap), y + r * (cell + gap), lastAlone ? 2 * cell + gap : cell, cell);
        }
        float tall = 2 * cell + gap, x2 = 2 * (cell + gap);
        put(old, new Ctl("bright", SLIDER, "sun", "亮度"), x2, y, cell, tall);
        put(old, new Ctl("vol", SLIDER, "volume", "音量"), x2 + cell + gap, y, W - (x2 + cell + gap), tall);
        y += tall + gap;
        ctlH = y - gap;
    }

    private float row(HashMap<String, Ctl> old, float y, float h, Ctl c) { put(old, c, 0f, y, W, h); return y + h + gap; }

    private void put(HashMap<String, Ctl> old, Ctl c, float x, float y, float w, float h) {
        Ctl o = old.get(c.id);
        if (o != null) { c.k = o.k; c.t0 = o.t0; c.target = o.target; }
        c.x = x; c.y = y; c.w = w; c.h = h;
        ctls.add(c);
    }

    private boolean isOn(Ctl c) {
        switch (c.id) {
            case "wifi": return s.wifi >= 1 && s.wifi <= 3;
            case "bt": return s.bt >= 1 && s.bt <= 3;
            case "dnd": return s.dnd;
            case "mute": return s.ringer != 0;
            case "torch": return s.torch;
            case "loc": return s.loc;
            default: return false;
        }
    }

    private String valueOf(Ctl c) {
        switch (c.id) {
            case "wifi": return RADIO[Math.max(0, Math.min(4, s.wifi))];
            case "bt": return RADIO[Math.max(0, Math.min(4, s.bt))];
            case "mute": return s.ringer == 1 ? "震动" : s.ringer == 2 ? "静音" : "关";
            case "battery": return s.bat < 0 ? "--" : s.bat + "%";
            case "settings": return "";
            case "bright": return Math.round(s.bright * 100f) + "%";
            case "vol": return Math.round(sliderValue(c) * 100f) + "%";
            default: return isOn(c) ? "开" : "关";
        }
    }

    /** 磁贴底下那行小字：一般是名字；电池充电时写状态。 */
    private String labelOf(Ctl c) {
        if ("battery".equals(c.id) && s.bat >= 0 && s.batState != 0) return s.batState == 2 ? "已充满" : "充电中";
        return c.label;
    }

    private float sliderValue(Ctl c) {
        if ("bright".equals(c.id)) return s.bright;
        if (volDrag >= 0f) return volDrag;
        return s.vol / (float) Math.max(1, s.volMax);
    }

    private void setSlider(Ctl c, float v, boolean commit) {
        v = Math.max(0f, Math.min(1f, v));
        hold(c.id);
        if ("bright".equals(c.id)) {
            s.bright = v;
            sys.setBrightness(v);                              // 后台线程只写最新的那次
        } else {
            volDrag = commit ? -1f : v;
            int step = Math.round(v * s.volMax);
            if (step != s.vol) { s.vol = step; sys.setVolume(step); }
        }
        invalidate();
    }

    private void toggle(Ctl c) {
        hold(c.id);
        switch (c.id) {
            case "wifi": { boolean to = !isOn(c); s.wifi = to ? 1 : 4; sys.setWifi(to); break; }
            case "bt": { boolean to = !isOn(c); s.bt = to ? 1 : 4; sys.setBluetooth(to); break; }
            case "dnd":
                if (sys.setDnd(!s.dnd)) s.dnd = !s.dnd;
                else { holdUntil.remove("dnd"); if (listener != null) listener.onSettings("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"); }
                break;
            case "mute": { boolean to = s.ringer == 0; sys.setMuted(to); s.ringer = to ? 1 : 0; break; }
            case "torch": s.torch = !s.torch; sys.setTorch(s.torch); break;
            case "loc": {
                boolean to = !s.loc;
                if (sys.setLocation(to)) s.loc = to;
                else { holdUntil.remove("loc"); if (listener != null) listener.onSettings(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS); }
                break;
            }
            default:
        }
        invalidate();
    }

    private static String settingsFor(String id) {
        switch (id) {
            case "wifi": return android.provider.Settings.ACTION_WIFI_SETTINGS;
            case "bt": return android.provider.Settings.ACTION_BLUETOOTH_SETTINGS;
            case "dnd": return "android.settings.ZEN_MODE_SETTINGS";
            case "mute": case "vol": return android.provider.Settings.ACTION_SOUND_SETTINGS;
            case "loc": return android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS;
            case "bright": return android.provider.Settings.ACTION_DISPLAY_SETTINGS;
            case "battery": return POWER_USAGE;
            default: return "";
        }
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        c.save();
        c.scale(scale, scale);
        float vh = viewH();
        // 半透明底（同以前的通知中心：surface 62%，底下的表盘由 FaceView 画磨砂；卡片之间的缝里透出来）
        fill.setColor((theme.surface & 0x00FFFFFF) | (Math.round(0.62f * 255) << 24));
        c.drawRect(0, 0, W, vh, fill);
        again |= layoutCards(now);
        if (pivAnim && now - pivT0 > (PIVOT_MS + 12 * PIVOT_STAGGER) * slow()) { pivAnim = false; px = mod2(pxTo); page = (int) px; }
        if (pivAnim) again = true;
        if (!tsGone) {
            again |= drawNotifs(c, now, vh);
            again |= drawControls(c, now, vh);
        }
        if (tsDir != 0) again = true;
        c.restore();
        if (again) postInvalidateOnAnimation();
    }

    /** 一个元素在环上的位置：拖动时跟手，切换时按顺序错开（WP Pivot 的内容是一行行滑进来的）。 */
    private float elemPx(int order, long now) {
        if (!pivAnim) return px;
        float k = Math.max(0f, Math.min(1f, (now - pivT0 - order * PIVOT_STAGGER * slow()) / (PIVOT_MS * slow())));
        return pxFrom + (pxTo - pxFrom) * Curves.NC_PIVOT.getInterpolation(k);
    }

    /** 第 pg 页的元素此刻的横向偏移（dp，两页之间隔 PAGE_GAP）；|偏移| ≥ 屏宽就不用画。 */
    private float pageX(int pg, int order, long now) { return wrap(pg - elemPx(order, now)) * (W + PAGE_GAP); }

    /** 转门角度：翻出去 0 → 90°（右边往屏幕里走），翻回来 90° → 0；NaN = 已经翻走。 */
    private float tsAngle(int order, boolean selected, long now) {
        if (tsDir == 0) return 0f;
        long delay = selected && tsDir > 0 ? tsLastOthers : order * tsStg();
        long dur = tsDir > 0 ? TS_OUT : TS_IN;
        Curves.Curve cv = tsDir > 0 ? Curves.NC_TS_OUT : Curves.NC_TS_IN;
        float k = Math.max(0f, Math.min(1f, (cv.vt((now - tsT0) / slow(), tsSpan) - delay) / dur));
        if (tsDir > 0) return k >= 1f ? Float.NaN : TS_DEG * cv.shape(k);
        return TS_DEG * (1f - cv.shape(k));
    }

    // ---------- 通知页 ----------

    private boolean drawNotifs(Canvas c, long now, float vh) {
        boolean again = false;
        float baseY = -pos[0];
        int order = 0;
        int vis = visibleCards();
        if (vis == 0 && access) {
            if (!wasEmpty) { wasEmpty = true; emptyT0 = now; }
            float k = Math.min(1f, (now - emptyT0) / (FADE_MS * slow()));
            if (k < 1f) again = true;
            float ex = pageX(0, 0, now);
            float hinge = tsAngle(0, false, now);
            if (Float.isNaN(hinge) || Math.abs(ex) >= W) return again;
            c.save();
            c.translate(ex, 0);
            int a = Math.round(255 * k);
            float cy = vh / 2f;
            glyphs.glyph(c, "check", W / 2f - 11f, cy - 22f - 4f, 22f, (theme.inkSubtle & 0x00FFFFFF) | (a << 24));
            t15.setColor(theme.inkMuted); t15.setAlpha(a);
            t15.setTextAlign(Paint.Align.CENTER);
            c.drawText("没有新通知", W / 2f, TileGridView.baseline(t15, cy + 4f, 20f), t15);
            t15.setTextAlign(Paint.Align.LEFT);
            c.restore();
            return again;
        }
        wasEmpty = false;
        // 先算好每张卡片在哪、转了多少，再画：往外翻走时（tsDir > 0）上面的先走、离人更近也更大，倒过来画让近的盖住远的
        java.util.ArrayList<Card> drawCards = new java.util.ArrayList<>();
        java.util.ArrayList<float[]> drawGeo = new java.util.ArrayList<>();
        for (Card cd : cards) {
            if (cd.gone) continue;
            float y = baseY + cd.y + shiftOf(cd, now), h = cd.h;
            if (y + h < -2 || y > vh + 2) continue;
            float ex = pageX(0, order, now);
            boolean sel = cd == tsSel;
            float hinge = tsAngle(order, sel, now);
            order++;
            if (Float.isNaN(hinge) || Math.abs(ex) >= W || cd.alpha <= 0f) continue;
            drawCards.add(cd); drawGeo.add(new float[] {y, h, ex, hinge});
        }
        final boolean rev = tsDir > 0;
        final int clearOrder = order;
        if (rev) drawClearRow(c, now, baseY, vh, clearOrder);
        for (int n = 0; n < drawCards.size(); n++) {
            int j = rev ? drawCards.size() - 1 - n : n;
            Card cd = drawCards.get(j);
            float[] gm = drawGeo.get(j);
            float y = gm[0], h = gm[1], ex = gm[2], hinge = gm[3];
            c.save();
            if (hinge != 0f) rotateHinge(c, hinge, y + cd.h / 2f);
            c.translate(ex, 0);
            int sl = hinge != 0f ? Shade.begin(c, 0f, y - 2f, W, y + h + 2f, Shade.edge(hinge, TS_DEG, tsDir > 0)) : -1;   // 没转平：离左边转轴越远越暗
            again |= drawCard(c, cd, y, now, hinge != 0f);
            if (sl >= 0) Shade.end(c, sl, Shade.amount(hinge), 0f, W, 0f, y - 2f, W, y + h + 2f);
            c.restore();
        }
        if (!rev) drawClearRow(c, now, baseY, vh, clearOrder);
        return again;
    }

    /** "全部清除"那一行（跟着卡片一起转门）。 */
    private void drawClearRow(Canvas c, long now, float baseY, float vh, int order) {
        if (clearable()) {
            float y = baseY + cardsBottom() - gap + 12f + clearShift(now);
            if (y < vh && y + 36f > 0) {
                float ex = pageX(0, order, now);
                float hinge = tsAngle(order, false, now);
                if (!Float.isNaN(hinge) && Math.abs(ex) < W) {
                    c.save();
                    if (hinge != 0f) rotateHinge(c, hinge, y + 18f);
                    c.translate(ex, 0);
                    if (pressObj == CLEAR && pressDown) c.scale(0.96f, 0.96f, W / 2f, y + 18f);
                    int sl = hinge != 0f ? Shade.begin(c, 0f, y - 2f, W, y + 38f, Shade.edge(hinge, TS_DEG, tsDir > 0)) : -1;
                    fill.setColor(theme.surface2);
                    r.set(edgeText, y, W - edgeText, y + 36f);
                    c.drawRoundRect(r, 18f, 18f, fill);
                    t15.setColor(theme.ink);
                    t15.setTextAlign(Paint.Align.CENTER);
                    c.drawText("全部清除", W / 2f, TileGridView.baseline(t15, y + 8f, 20f), t15);
                    t15.setTextAlign(Paint.Align.LEFT);
                    if (sl >= 0) Shade.end(c, sl, Shade.amount(hinge), 0f, W, 0f, y - 2f, W, y + 38f);
                    c.restore();
                }
            }
        }
    }

    /** 一块通知；在倾斜、转门、淡出时贴快照（透视下的文字每帧都要重新描轮廓，这块芯片上很贵）。 */
    private boolean drawCard(Canvas c, Card cd, float y, long now, boolean turning) {
        boolean again = false;
        c.save();
        c.translate(cd.dx, 0);
        boolean tilt = pressObj == cd;
        if (tilt) again |= applyTilt(c, now, W / 2f, y + cd.h / 2f);
        boolean useSnap = tilt || turning || cd.alpha < 0.999f;
        Bitmap b = useSnap ? snap(cd) : null;
        if (b != null) {
            snapPaint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, cd.alpha))));
            r.set(0, y, W, y + cd.h);
            c.drawBitmap(b, null, r, snapPaint);
            snapPaint.setAlpha(255);
        } else paintCard(c, cd, y);
        c.restore();
        return again;
    }

    private Bitmap snap(Card cd) {
        if (cd.snap != null && cd.snapAccent == cd.accent) return cd.snap;
        int w = getWidth(), h = (int) Math.ceil(cd.h * scale);
        if (w <= 0 || h <= 0) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        bc.scale(scale, scale);
        int ob = downBtn;
        downBtn = -1;                                          // 快照里不带按下的按钮
        paintCard(bc, cd, 0f);
        downBtn = ob;
        cd.snap = b;
        cd.snapAccent = cd.accent;
        return b;
    }

    /** 一块通知：头一行（单色图标 + 大写应用名 + 右边相对时间）按横幅的位置放，标题 15、正文 13 离边 30 屏幕像素。 */
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private void paintCard(Canvas c, Card cd, float y) {
        int bg = cd.accent ? theme.accent : theme.tileBase, fg = cd.accent ? theme.onAccent : theme.onTile;
        fill.setColor(bg);
        c.drawRect(0, y, W, y + cd.h, fill);
        int nAct = cd.allow ? 0 : Math.min(2, cd.n.actions.size());
        // 图标和三行字在卡片里上下居中（有按钮时在按钮上面那块里居中，按钮贴着下边留 N_PAD）
        float ch = contentH(cd), area = cd.h - (nAct > 0 ? C_AGAP + C_ACT + N_PAD : 0f), top = y + (area - ch) / 2f;
        // 图标：没有底（用户 2026-10-25），放在左边 N_ICON 见方的位置里、按图形本身居中——
        // 应用图标按不透明部分居中、较长边缩到 N_ICON_ART；设计稿图标按笔画居中
        float cx = edgeText + N_ICON / 2f, cy = top + ch / 2f;
        if (cd.appBmp != null) TileGridView.bitmapCentered(c, cd.appBmp, cx, cy, N_ICON_ART, iconPaint);
        else glyphs.glyphCentered(c, cd.icon, cx, cy, N_GLYPH, fg);
        // 三行字：标题（右边相对时间）+ 正文
        float tx = textX(), ty = top + (ch - (C_TITLE + cd.body.length * C_BODY)) / 2f;
        t15.setColor(fg);
        c.drawText(cd.title, tx, TileGridView.baseline(t15, ty, C_TITLE), t15);
        if (!cd.ago.isEmpty()) {
            cap.setColor(fg);
            cap.setTextAlign(Paint.Align.RIGHT);
            c.drawText(cd.ago, W - edgeText, TileGridView.baseline(cap, ty + 3f, 14f), cap);
            cap.setTextAlign(Paint.Align.LEFT);
        }
        ty += C_TITLE;
        t13.setColor(fg);
        for (String line : cd.body) { c.drawText(line, tx, TileGridView.baseline(t13, ty, C_BODY), t13); ty += C_BODY; }
        int na = cd.allow ? 0 : Math.min(2, cd.n.actions.size());
        if (na > 0) {
            float ay = y + cd.h - N_PAD - C_ACT;
            float w = (textW() - 4f * (na - 1)) / na;
            for (int i = 0; i < na; i++) {
                float x = tx + i * (w + 4f);
                boolean down = downBtnOwner == cd && downBtn == i;
                r.set(x + 1f, ay + 1f, x + w - 1f, ay + C_ACT - 1f);
                float br = C_ACT / 2f - 1f;
                if (down) { fill.setColor(fg); c.drawRoundRect(r, br, br, fill); }
                stroke.setStrokeWidth(2f);
                stroke.setColor(fg);
                c.drawRoundRect(r, br, br, stroke);
                btn.setColor(down ? bg : fg);
                c.drawText(fit(cd.n.actions.get(i), btn, w - 16f), x + w / 2f, TileGridView.baseline(btn, ay + (C_ACT - 16f) / 2f, 16f), btn);
            }
        }
        if (!cd.allow && cd.n.progress >= 0) {                  // 进度：贴着下沿一条 3 的细线
            fill.setColor((fg & 0x00FFFFFF) | 0x4D000000);
            c.drawRect(0, y + cd.h - 3f, W, y + cd.h, fill);
            fill.setColor(fg);
            c.drawRect(0, y + cd.h - 3f, W * Math.min(100, cd.n.progress) / 100f, y + cd.h, fill);
        }
    }

    // ---------- 控制页 ----------

    private boolean drawControls(Canvas c, long now, float vh) {
        boolean again = false;
        float baseY = -pos[1];
        int order = -1;
        float lastY = Float.NaN;
        for (Ctl t : ctls) {
            float y = baseY + t.y;
            if (t.y != lastY) { order++; lastY = t.y; }          // 同一行的几块一起动
            if (y + t.h < -2 || y > vh + 2) continue;
            float ex = pageX(1, order, now);
            if (Math.abs(ex) >= W) continue;
            c.save();
            c.translate(ex, 0);
            if (pressObj == t) again |= applyTilt(c, now, t.x + t.w / 2f, y + t.h / 2f);
            again |= drawCtl(c, t, y, now);
            c.restore();
        }
        return again;
    }

    /** 字离磁贴左边多远：贴着屏幕左边的那一列按横幅的规则离屏幕边 30 屏幕像素，其余 8。 */
    private float padL(Ctl t) { return t.x <= 0.5f ? Math.max(T_PAD, edgeText) : T_PAD; }

    private float padR(Ctl t) { return t.x + t.w >= W - 0.5f ? Math.max(T_PAD, edgeText) : T_PAD; }

    private boolean drawCtl(Canvas c, Ctl t, float y, long now) {
        boolean again = false;
        float x = t.x;
        int bg, fg;
        switch (t.kind) {
            case TOGGLE: {
                boolean on = isOn(t);
                if (t.k < 0) { t.k = on ? 1f : 0f; t.target = on; t.t0 = 0; }
                if (on != t.target) { t.target = on; t.t0 = now; }
                float kk = Math.min(1f, (now - t.t0) / (FADE_MS * slow()));
                t.k = t.target ? kk : 1f - kk;
                if (kk < 1f) again = true;
                bg = PageView.mix(theme.tileSoft, theme.accent, t.k);
                fg = PageView.mix(theme.onTile, theme.onAccent, t.k);
                break;
            }
            case MEDIA: bg = theme.accent; fg = theme.onAccent; break;
            case SLIDER: bg = theme.tileSoft; fg = theme.onTile; break;
            default: bg = theme.tileBase; fg = theme.onTile;
        }
        fill.setColor(bg);
        c.drawRect(x, y, x + t.w, y + t.h, fill);
        if (t.kind == MEDIA) { drawMedia(c, t, y); return again; }
        if (t.kind == SLIDER) {
            float v = sliderValue(t), fy = y + t.h * (1f - v);          // 竖条：主题色从下往上填
            fill.setColor(theme.accent);
            c.drawRect(x, fy, x + t.w, y + t.h, fill);
            // 图标压在填充上的那一段用 on-accent，其余用 on-tile（浅色主题下两者不一样；深色主题下一样，只画一遍）
            if (theme.onAccent == theme.onTile) sliderText(c, t, y, theme.onTile);
            else {
                c.save(); c.clipRect(x, fy, x + t.w, y + t.h); sliderText(c, t, y, theme.onAccent); c.restore();
                c.save(); c.clipRect(x, y, x + t.w, fy); sliderText(c, t, y, theme.onTile); c.restore();
            }
            return again;
        }
        // 和开始屏的应用磁贴一样只有一个居中的图标（用户 2026-10-25），开 / 关靠底色
        float is = TILE_ICON * g, ix = x + (t.w - is) / 2f, iy = y + (t.h - is) / 2f;
        glyphs.glyphCentered(c, t.icon, x + t.w / 2f, y + t.h / 2f, is, fg);
        if ("battery".equals(t.id) && s.bat >= 0) {            // 电池：图标里按电量填一截（battery 图标的电池身在 24 格的 x 2..19、y 7..17）
            float u = is / 24f, level = Math.max(0f, Math.min(1f, s.bat / 100f));
            fill.setColor(fg);
            c.drawRect(ix + 4.5f * u, iy + 9.5f * u, ix + (4.5f + 12f * level) * u, iy + 14.5f * u, fill);
        }
        return again;
    }

    /** 竖条：底下一个图标，填充高度就是数值。 */
    private void sliderText(Canvas c, Ctl t, float y, int fg) {
        // 只留图标（用户 2026-10-25），数值就是填充的高度；图标在最下面那一格的正中（和旁边方块的图标同一高度）
        float is = TILE_ICON * g, cx = t.x + t.w / 2f, cy = y + t.h - cell / 2f;
        levelIcon(c, t, cx, cy, is, fg, sliderValue(t));
    }

    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path iconPath = new android.graphics.Path();
    private final RectF iconInk = new RectF();
    { iconStroke.setStyle(Paint.Style.STROKE); iconStroke.setStrokeCap(Paint.Cap.ROUND); iconStroke.setStrokeJoin(Paint.Join.ROUND); }

    /**
     * 跟着数值变的图标（用户 2026-10-25："亮度图标和声音图标能不能动态"），同一套画法（24 格、2 的线、圆头）：
     * 亮度——太阳的 8 道光芒随亮度从小圆点长到满长；音量——喇叭前面的两道声波随音量依次出现（淡入），音量 0 时换成一个叉。
     * 位置按完整图标的笔画外框居中（同 glyphCentered），数值变的时候图标不跳。
     */
    private void levelIcon(Canvas c, Ctl t, float cx, float cy, float size, int fg, float v) {
        TesseraIcon.get(t.icon).computeBounds(iconInk, true);
        float k = size / 24f;
        c.save();
        c.translate(cx - iconInk.centerX() * k, cy - iconInk.centerY() * k);
        c.scale(k, k);
        iconStroke.setStrokeWidth(2f);
        iconStroke.setColor(fg);
        if ("bright".equals(t.id)) {
            c.drawCircle(12f, 12f, 4f, iconStroke);
            float r0 = 7.5f, r1 = r0 + 0.01f + 2.5f * Math.max(0f, Math.min(1f, v));   // 光芒：从小圆点长到 2.5 长
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                c.drawLine(12f + cs * r0, 12f + sn * r0, 12f + cs * r1, 12f + sn * r1, iconStroke);
            }
        } else {
            iconPath.reset();                                     // 喇叭（同 volume 图标的身子）
            iconPath.moveTo(4f, 9f); iconPath.lineTo(8f, 9f); iconPath.lineTo(13f, 5f); iconPath.lineTo(13f, 19f);
            iconPath.lineTo(8f, 15f); iconPath.lineTo(4f, 15f); iconPath.close();
            c.drawPath(iconPath, iconStroke);
            if (v <= 0.001f) {                                    // 静音：一个叉
                c.drawLine(17f, 9.5f, 21f, 14.5f, iconStroke);
                c.drawLine(21f, 9.5f, 17f, 14.5f, iconStroke);
            } else {
                float a1 = Math.min(1f, v * 3f), a2 = Math.max(0f, Math.min(1f, (v - 0.45f) * 3f));
                RectF o = iconInk;
                iconStroke.setAlpha(Math.round(Color_alpha(fg) * a1));
                // 两道弧和 volume 图标的路径一样：近的 M17 9.5 a4 4 → 圆心 (13.88, 12)、半径 4；远的 M19.5 7 a8 8 → 圆心 (13.26, 12)、半径 8；都张开 ±38.7°
                o.set(9.877f, 8f, 17.877f, 16f);
                c.drawArc(o, -38.68f, 77.36f, false, iconStroke);
                if (a2 > 0f) {
                    iconStroke.setAlpha(Math.round(Color_alpha(fg) * a2));
                    o.set(5.255f, 4f, 21.255f, 20f);
                    c.drawArc(o, -38.68f, 77.36f, false, iconStroke);
                }
                iconStroke.setAlpha(Color_alpha(fg));
            }
        }
        c.restore();
    }

    private static int Color_alpha(int c) { return c >>> 24; }

    /** 正在播放：整行 × 一格。左边应用名 / 曲名 / 歌手，右边上一首、播放暂停（套 2 的圆）、下一首。 */
    private void drawMedia(Canvas c, Ctl t, float y) {
        int fg = theme.onAccent, bg = theme.accent;
        float textR = mediaBtnX(0) - 18f;
        glyphs.glyph(c, "music", headLeft, y + headTop, 14f, fg);
        head.setColor(fg);
        c.drawText(fit(s.mediaApp.toUpperCase(Locale.ROOT), head, textR - headLeft - 18f), headLeft + 18f, TileGridView.baseline(head, y + headTop, 14f), head);
        float ty = y + headTop + 14f + 2f;
        t15.setColor(fg);
        c.drawText(fit(s.mediaTitle.isEmpty() ? "正在播放" : s.mediaTitle, t15, textR - edgeText), edgeText, TileGridView.baseline(t15, ty, 20f), t15);
        if (!s.mediaArtist.isEmpty()) {
            t13.setColor(fg);
            c.drawText(fit(s.mediaArtist, t13, textR - edgeText), edgeText, TileGridView.baseline(t13, ty + 20f, 18f), t13);
        }
        float cy = mediaBtnY(t, y);
        String[] gl = {"prev", s.playing ? "pause" : "play", "next"};
        for (int i = 0; i < 3; i++) {
            float cx = mediaBtnX(i);
            boolean down = downBtnOwner == t && downBtn == i;
            if (i == 1) {
                if (down) { fill.setColor(fg); c.drawCircle(cx, cy, 14f, fill); }
                stroke.setStrokeWidth(2f); stroke.setColor(fg);
                c.drawCircle(cx, cy, 13f, stroke);
            } else if (down) { fill.setColor((fg & 0x00FFFFFF) | 0x33000000); c.drawCircle(cx, cy, 14f, fill); }
            glyphs.glyph(c, gl[i], cx - 8f, cy - 8f, 16f, i == 1 && down ? bg : fg);
        }
    }

    /** 媒体按钮的圆心：从右往左 下一首 / 播放 / 上一首，间距 32，最右那个离屏幕右边 43 屏幕像素。 */
    private float mediaBtnX(int i) { return W - headRight - 13f - (2 - i) * 32f; }

    private float mediaBtnY(Ctl t, float y) { return y + t.h / 2f + 4f; }

    // ---------- 变换 ----------

    /** 转门：绕屏幕左边缘再往左 20% 的竖轴转 deg 度（同 TileGridView.rotateHinge，透视 600 设计单位）。 */
    private void rotateHinge(Canvas c, float deg, float cy) {
        camera.save();
        camera.setLocation(0, 0, -PERSP_TS * g / 72f);
        float ax = W / 2f - TileGridView.TS_AXIS * W;     // 转轴在左边缘再往左 20%（WP）
        camera.translate(-ax, 0, 0);
        camera.rotateY(deg);
        camera.translate(ax, 0, 0);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-W / 2f, -cy);
        matrix.postTranslate(W / 2f, cy);
        c.concat(matrix);
    }

    /**
     * 0..1：按下立刻倾到位；松手先停 0.2 秒、再 0.1 秒回正（WP TiltEffect）。点得很快（倾到一半就松手）也先倾到底、停一下再回正，
     * 否则轻点一下几乎看不出倾斜（用户 2026-10-25："卡片按下去时候的翻转不够明显"）。
     */
    private float tiltAmount(long now) {
        float in = TILT_MS * slow();
        if (pressDown || now < releaseAt) return Curves.TILT_IN.getInterpolation(Math.max(0f, Math.min(1f, (now - pressT0) / in)));
        float at = Curves.TILT_IN.getInterpolation(Math.max(0f, Math.min(1f, (releaseAt - pressT0) / in)));
        float rr = Math.max(0f, Math.min(1f, (now - releaseAt) / (TILT_OUT_MS * slow())));
        return at * (1f - Curves.TILT_OUT.getInterpolation(rr));
    }

    /** 按压倾斜；返回还在动。 */
    private boolean applyTilt(Canvas c, long now, float cx, float cy) {
        float k = tiltAmount(now);
        boolean released = !pressDown && now >= releaseAt;
        boolean again = released ? k > 0f : true;
        if (released && k <= 0f) { pressObj = null; return false; }
        if (!released && k >= 1f && pressDown) again = false;
        if (tiltMode == 0) { float sc = 1f - TileGridView.TILT_SINK * k; c.scale(sc, sc, cx, cy); return again; }
        float deg = tiltDeg * k;
        if (tiltMode == 1) { rotate(c, 0, deg, cx, cy); return again; }
        if (tiltMode == 2) { rotate(c, deg, 0, cx, cy); return again; }
        float a = (float) Math.toDegrees(tiltAxis);
        c.translate(cx, cy);
        c.rotate(a);
        camera.save();
        camera.setLocation(0, 0, -TileGridView.TILT_PERSP * g / 72f);
        camera.rotateX(deg);
        camera.getMatrix(matrix);
        camera.restore();
        c.concat(matrix);
        c.rotate(-a);
        c.translate(-cx, -cy);
        return again;
    }

    private void rotate(Canvas c, float rx, float ry, float cx, float cy) {
        camera.save();
        camera.setLocation(0, 0, -TileGridView.TILT_PERSP * g / 72f);
        if (rx != 0f) camera.rotateX(rx);
        if (ry != 0f) camera.rotateY(ry);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
        c.concat(matrix);
    }

    /** 按下：按在角上绕对角线、边上绕中轴、正中间整块下陷（同 TileGridView.press）。 */
    private void press(Object obj, float rx, float ry, float rw, float rh, float x, float y) {
        float cx = rx + rw / 2f, cy = ry + rh / 2f;
        float nx = Math.max(-1f, Math.min(1f, (x - cx) / (rw / 2f))), ny = Math.max(-1f, Math.min(1f, (y - cy) / (rh / 2f)));
        float ax = Math.abs(nx), ay = Math.abs(ny);
        if (ax < 0.3f && ay < 0.3f) tiltMode = 0;
        else if (ax > 0.55f && ay > 0.55f) {
            float sxn = Math.signum(nx), syn = Math.signum(ny);
            tiltAxis = (float) Math.atan2(-syn * rh, sxn * rw);
            float ppx = sxn * rw / 2f, ppy = syn * rh / 2f;
            float d = (float) (-Math.sin(tiltAxis) * ppx + Math.cos(tiltAxis) * ppy);
            float deg = TileGridView.TILT_CORNER_DEG;
            tiltDeg = d < 0 ? deg : -deg;
            tiltMode = 3;
        } else if (ax >= ay) {
            float deg = TileGridView.TILT_EDGE_DEG;
            tiltDeg = nx >= 0 ? deg : -deg;
            tiltMode = 1;
        } else {
            float deg = TileGridView.TILT_EDGE_DEG;
            tiltDeg = ny < 0 ? deg : -deg;
            tiltMode = 2;
        }
        pressObj = obj; pressDown = true; pressT0 = SystemClock.uptimeMillis(); releaseAt = 0;
        invalidate();
    }

    private void unpress() {
        if (pressObj != null && pressDown) {
            long now = SystemClock.uptimeMillis();
            pressDown = false;
            // WP：松手先停 0.2 秒再回正
            releaseAt = now + Math.round(TILT_HOLD_MS * slow());
        }
        downBtn = -1; downBtnOwner = null;
        invalidate();
    }

    // ---------- 触摸（坐标 dp；阈值是"屏宽 200"的设计单位，乘 g） ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        float fy = y + getTranslationY() / scale;              // 拖动跟踪用屏幕坐标（整层会跟着手指走）
        long now = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (tsDir != 0 || tsGone) return true;          // 转门中不接手势
                motion++;
                removeCallbacks(holdR);
                if (pivAnim) { pivAnim = false; px = mod2(pxTo); page = (int) px; }
                pxBase = px;
                downX = x; downY = fy; lock = 0; sn = 0; mode = M_NONE; held = false;
                pulling = false; pullDy = 0;
                startPos = pos[page];
                atEdgeAtDown = pos[page] >= maxScroll(page) - 1;
                sample(now, x, fy);
                hit = hitTest(x, y, now);
                if (hit != null) {
                    if (hit.btn >= 0) { downBtn = hit.btn; downBtnOwner = hit.card != null ? hit.card : hit.ctl; invalidate(); }
                    else if (hit.clear) { pressObj = CLEAR; pressDown = true; pressT0 = SystemClock.uptimeMillis(); releaseAt = 0; invalidate(); }
                    else if (hit.card != null) press(hit.card, 0, -pos[0] + hit.card.y, W, hit.card.h, x, y);
                    else if (hit.ctl != null && hit.ctl.kind != SLIDER) press(hit.ctl, hit.ctl.x, -pos[1] + hit.ctl.y, hit.ctl.w, hit.ctl.h, x, y);
                    if (hit.ctl != null && hit.ctl.kind != SLIDER && hit.btn < 0) postDelayed(holdR, HOLD_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == M_NONE && lock != 0) return true;
                float dx = x - downX, dy = fy - downY, slop = SLOP * g;
                if (lock == 0) {
                    if (held || Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                    lock = Math.abs(dy) >= Math.abs(dx) ? 1 : -1;
                    lockX = downX + (dx > 0 ? slop : -slop);
                    lockY = downY + (dy > 0 ? slop : -slop);
                    removeCallbacks(holdR);
                    boolean onSlider = hit != null && hit.ctl != null && hit.ctl.kind == SLIDER;
                    if (onSlider && lock == 1) { mode = M_SLIDER; slStart = sliderValue(hit.ctl); }   // 竖条：上下拖调节；左右滑还是切页
                    else if (lock == 1) mode = M_SCROLL;
                    else if (page == 0 && downX < EDGE * g && dx > 0) mode = M_EDGE;
                    else if (page == 0 && dx > 0 && hit != null && hit.card != null && !hit.card.allow && hit.card.leaveT0 == 0) mode = M_SWIPE;
                    else mode = M_PIVOT;
                    unpress();
                }
                sample(now, x, fy);
                switch (mode) {
                    case M_SCROLL: scrollMove(fy); break;
                    case M_PIVOT:                               // 环：往哪边都能一直拖到下一页
                        px = pxBase - Math.max(-W, Math.min(W, x - lockX)) / W;
                        invalidate();
                        break;
                    case M_SWIPE: {
                        Card cd = hit.card;
                        float d = Math.max(0f, x - lockX);
                        cd.dx = cd.n.clearable ? d : rubber(d, 120f * g);   // 常驻通知划不掉：只给一点橡皮筋
                        cd.backT0 = 0;
                        invalidate();
                        break;
                    }
                    case M_SLIDER: setSlider(hit.ctl, slStart - (fy - lockY) / hit.ctl.h, false); break;   // 往上拖变大
                    default:
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                removeCallbacks(holdR);
                Hit h = hit;
                int m = mode;
                if (held) { held = false; hit = null; mode = M_NONE; unpress(); return true; }
                switch (m) {
                    case M_SCROLL: {
                        float vy = cancel ? 0f : fingerV(now, sy);
                        if (pulling) { pulling = false; if (listener != null) listener.onPullEnd(pullDy * scale, (atEdgeAtDown ? vy : 0f) * scale); }
                        else settle(-vy);
                        break;
                    }
                    case M_PIVOT: {
                        float dx = x - lockX, vx = cancel ? 0f : fingerV(now, sx);
                        float to = pxBase;
                        if (dx < -W * COMMIT || (vx <= -FLING * g && dx < -8f * g)) to = pxBase + 1f;        // 往左：下一页
                        else if (dx > W * COMMIT || (vx >= FLING * g && dx > 8f * g)) to = pxBase - 1f;      // 往右：上一页
                        startPivot(to);
                        break;
                    }
                    case M_SWIPE: {
                        Card cd = h.card;
                        float vx = cancel ? 0f : fingerV(now, sx);
                        if (!cancel && cd.n.clearable && (cd.dx > 60f * g || (vx >= FLING * g && cd.dx > 16f * g))) leave(cd);
                        else { cd.dxFrom = cd.dx; cd.backT0 = SystemClock.uptimeMillis(); }
                        invalidate();
                        break;
                    }
                    case M_SLIDER: setSlider(h.ctl, sliderValue(h.ctl), true); break;
                    case M_EDGE:
                        if (!cancel && x - downX > 40f * g && Math.abs(fy - downY) < 40f * g && listener != null) listener.onClose();
                        break;
                    default:
                        if (!cancel && lock == 0 && h != null) { hit = null; mode = M_NONE; tap(h, x, y); return true; }
                }
                hit = null;
                mode = M_NONE;
                unpress();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void scrollMove(float fy) {
        int pg = page;
        float raw = startPos - (fy - lockY), mx = maxScroll(pg), d = viewH();
        if (raw > mx) {                                        // 滚到底继续往上拉：整个面板跟着手指走（收起）
            pulling = true;
            pullDy = -(raw - mx);
            pos[pg] = mx;
            if (listener != null) listener.onPull(pullDy * scale);
        } else {
            if (pulling) { pulling = false; pullDy = 0; if (listener != null) listener.onPull(0); }
            pos[pg] = raw < 0 ? -rubber(-raw, d) : raw;
        }
        invalidate();
    }

    private Hit hitTest(float x, float y, long now) {
        Hit h = new Hit();
        layoutCards(now);
        if (page == 0) {
            float baseY = -pos[0];
            for (Card cd : cards) {
                if (cd.gone || cd.leaveT0 > 0) continue;
                float tp = baseY + cd.y + shiftOf(cd, now);
                if (y >= tp && y < tp + cd.h) {
                    h.card = cd;
                    int na = cd.allow ? 0 : Math.min(2, cd.n.actions.size());
                    if (na > 0) {
                        float by = tp + cd.h - N_PAD - C_ACT;
                        if (y >= by - 2f && y <= by + C_ACT + 2f) {
                            float w = (textW() - 4f * (na - 1)) / na;
                            for (int i = 0; i < na; i++) { float bx = textX() + i * (w + 4f); if (x >= bx && x <= bx + w) h.btn = i; }
                        }
                    }
                    return h;
                }
            }
            if (clearable()) {
                float by = baseY + cardsBottom() - gap + 12f;
                if (y >= by && y <= by + 36f && x >= edgeText && x <= W - edgeText) { h.clear = true; return h; }
            }
            return null;
        }
        float baseY = -pos[1];
        for (Ctl t : ctls) {
            float tp = baseY + t.y;
            if (x >= t.x && x < t.x + t.w && y >= tp && y < tp + t.h) {
                h.ctl = t;
                if (t.kind == MEDIA) {
                    float cy = mediaBtnY(t, tp);
                    for (int i = 0; i < 3; i++) if (Math.abs(x - mediaBtnX(i)) < 16f && Math.abs(y - cy) < 20f) h.btn = i;
                }
                return h;
            }
        }
        return null;
    }

    private void tap(Hit h, float x, float y) {
        if (h.clear) { unpress(); clearAll(); return; }
        if (h.card != null) {
            Card cd = h.card;
            if (cd.allow) { unpress(); if (listener != null) listener.onAllowAccess(); return; }
            if (h.btn >= 0) { unpress(); if (listener != null) listener.onAction(cd.n, h.btn); return; }
            if (!cd.n.content) { unpress(); return; }            // 没有界面可开：倾一下就好，不翻走
            open(cd);                                          // 倾着别回正，等它自己翻走
            return;
        }
        if (h.ctl != null) {
            Ctl t = h.ctl;
            if (t.kind == MEDIA) {
                if (h.btn >= 0) { sys.mediaKey(h.btn - 1); if (h.btn == 1) { s.playing = !s.playing; hold("media"); } }
                else sys.openMedia();
            } else if (t.kind == TOGGLE) toggle(t);
            else if (t.kind == SLIDER) { /* 竖条只认上下拖：点一下就跳到手指位置在手表上太容易误触（亮度一下变成 4%） */ }
            else if (listener != null) listener.onSettings(t.kind == INFO ? POWER_USAGE : "");
            unpress();
        }
    }

    private void onHold() {
        if (hit == null || hit.ctl == null || lock != 0) return;
        held = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        unpress();
        String a = settingsFor(hit.ctl.id);
        if (listener != null && (!a.isEmpty() || "settings".equals(hit.ctl.id))) listener.onSettings(a);
    }

    /** 切到环上的 to（可以是 −1、2 这种，动画走完再折回 0 / 1）。 */
    private void startPivot(float to) {
        pxFrom = px;
        pxTo = to;
        int target = mod2(to);
        if (target != page) refreshControls();
        page = target;
        pivT0 = SystemClock.uptimeMillis();
        pivAnim = Math.abs(pxFrom - to) > 0.001f;
        if (!pivAnim) px = mod2(to);
        invalidate();
    }

    private void leave(Card cd) {
        cd.dxFrom = cd.dx;
        cd.leaveT0 = SystemClock.uptimeMillis();
        cd.backT0 = 0;
        reaccent();
    }

    /** 主题色换给剩下的第一条（正在走的那几块保持原样）。 */
    private void reaccent() {
        boolean first = true;
        for (Card cd : cards) {
            if (cd.allow) continue;
            boolean alive = !cd.gone && cd.leaveT0 == 0;
            cd.accent = alive ? first : cd.accent;
            if (alive) first = false;
        }
    }

    /** 全部清除：能清的那几块从上往下依次往右滑走，滑完一起交给系统清掉。 */
    private void clearAll() {
        long now = SystemClock.uptimeMillis();
        int i = 0;
        long last = 0;
        float baseY = -pos[0], vh = viewH();
        for (Card cd : cards) {
            if (cd.gone || cd.leaveT0 > 0 || !cd.n.clearable) continue;
            float y = baseY + cd.y;
            boolean vis = y + cd.h > 0 && y < vh;
            long d = vis ? i++ * CLEAR_STAGGER : 0;
            cd.dxFrom = cd.dx;
            cd.leaveT0 = now + Math.round(d * slow());
            cd.noCollapse = true;
            last = Math.max(last, d);
        }
        reaccent();
        invalidate();
        postDelayed(() -> {
            long t = SystemClock.uptimeMillis();
            for (Card cd : cards) if (cd.noCollapse && cd.leaveT0 > 0) { cd.sent = true; markGone(cd, t); }
            pos[0] = 0f;
            reaccent();
            invalidate();
            if (listener != null) listener.onClearAll();
        }, Math.round((last + LEAVE_MS) * slow()) + 20);
    }

    /** 打开通知：其余按行从上往下转门翻走，被点的那块最后走，翻完交给外面拉起应用。 */
    private void open(Card cd) {
        long now = SystemClock.uptimeMillis();
        tsSel = cd;
        tsDir = 1;
        tsT0 = now;
        tsGone = false;
        int rows = visibleRows(cd);                            // 看得见的其余行数
        tsLastOthers = Math.max(0, rows) * tsStg() + (rows > 0 ? TS_OUT : 0);
        tsSpan = tsLastOthers + TS_OUT;
        long len = Math.round((tsLastOthers + TS_OUT) * slow());
        final int me = ++motion;
        final ShellData.Notif n = cd.n;
        postDelayed(() -> {
            if (me != motion || tsDir != 1) return;
            tsGone = true;
            pressObj = null;
            invalidate();
            if (listener != null) listener.onOpen(n);
        }, len);
        invalidate();
    }

    /** 转门每行之间晚多少 ms（调曲线网页可改）。 */
    private long tsStg() { return (tsDir < 0 ? Curves.NC_TS_IN : Curves.NC_TS_OUT).stagger(TS_STAGGER); }

    /** 通知页上看得见的行数（卡片 + "全部清除"），skip 那张不算。 */
    private int visibleRows(Card skip) {
        int rows = 0;
        float baseY = -pos[0], vh = viewH();
        for (Card x : cards) { if (x.gone || x == skip) continue; float y = baseY + x.y; if (y + x.h > 0 && y < vh) rows++; }
        if (clearable()) { float y = baseY + cardsBottom() - gap + 12f; if (y < vh && y + 36f > 0) rows++; }
        return rows;
    }

    /** 应用没起来（通知没有界面）：翻回来。 */
    void turnBack() {
        if (!tsGone && tsDir != 1) return;
        tsGone = false;
        tsSel = null;
        pressObj = null;
        tsDir = -1;
        tsT0 = SystemClock.uptimeMillis();
        tsSpan = Math.max(0, visibleRows(null) - 1) * tsStg() + TS_IN;
        final int me = ++motion;
        postDelayed(() -> { if (me == motion && tsDir == -1) { tsDir = 0; invalidate(); } }, Math.round((12 * tsStg() + TS_IN) * slow()));
        invalidate();
    }

    /** 表冠：当前页纵向滚动（一格约 40 设计单位）。 */
    void crown(int delta) {
        final int pg = page;
        final float from = pos[pg], to = Math.max(0f, Math.min(maxScroll(pg), from + delta * 40f * g));
        if (to == from) return;
        final int me = ++motion;
        final long t0 = SystemClock.uptimeMillis();
        postOnAnimation(new Runnable() {
            @Override public void run() {
                if (me != motion) return;
                float k = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) CROWN_MS);
                pos[pg] = from + (to - from) * Curves.CROWN.getInterpolation(k);
                invalidate();
                if (k < 1f) postOnAnimation(this);
            }
        });
    }

    // ---------- 滚动手感（同 PageView） ----------

    private void sample(long t, float x, float y) {
        if (sn == sy.length) {
            System.arraycopy(sx, 1, sx, 0, sn - 1); System.arraycopy(sy, 1, sy, 0, sn - 1); System.arraycopy(st, 1, st, 0, sn - 1); sn--;
        }
        sx[sn] = x; sy[sn] = y; st[sn] = t; sn++;
    }

    private float fingerV(long now, float[] v) {
        if (sn < 2 || now - st[sn - 1] > 80) return 0f;
        int i = sn - 1;
        while (i > 0 && st[sn - 1] - st[i - 1] <= 100) i--;
        long dt = st[sn - 1] - st[i];
        return dt > 0 ? (v[sn - 1] - v[i]) / dt : 0f;
    }

    private void settle(float v) {
        final int me = ++motion;
        final int pg = page;
        final float[] vv = {v}, spring = {Float.NaN, 0, 0, 0};
        final long[] last = {0};
        final float bounce = BOUNCE_V * g;
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (me != motion) return;
                long now = SystemClock.uptimeMillis();
                float dt = last[0] == 0 ? 16f : Math.min(40f, now - last[0]);
                last[0] = now;
                float m = maxScroll(pg);
                boolean more;
                float p = pos[pg];
                if (Float.isNaN(spring[0]) && (p < 0 || p > m)) {
                    spring[0] = p < 0 ? 0 : m; spring[1] = p - spring[0];
                    spring[2] = Math.max(-bounce, Math.min(bounce, vv[0])); spring[3] = 0;
                }
                if (!Float.isNaN(spring[0])) {
                    spring[3] += dt;
                    float t = spring[3];
                    float xx = (float) ((spring[1] + (spring[2] + SPRING_W * spring[1]) * t) * Math.exp(-SPRING_W * t));
                    more = !(t > 60 && Math.abs(xx) < 0.3f * g);
                    pos[pg] = more ? spring[0] + xx : spring[0];
                } else {
                    pos[pg] = p + vv[0] * dt;
                    vv[0] *= (float) Math.pow(DECEL, dt);
                    more = Math.abs(vv[0]) >= 0.02f * g || pos[pg] < 0 || pos[pg] > m;
                }
                invalidate();
                if (more && me == motion) postOnAnimation(this);
            }
        });
    }

    private static float rubber(float x, float d) { return (1 - 1 / (x * 0.55f / d + 1)) * d; }
}
