package com.tessera.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 应用列表（用户 2026-10-25，替代原来的搜索页）：开始屏只放钉上去的磁贴，从开始屏往左滑进这里——WP8 的应用列表样式。
 * 一列应用（小方块图标 + 名字，按拼音排），左上角一个圆形搜索按钮，固定不随列表滚动；位置照用户的草图按屏宽比例放。
 * · 点应用：整页按行转门翻走、被点的那行最后走，应用顺着翻进来。
 * · 长按应用（400ms）：拿起来，列表翻走、开始屏翻回来，手里的磁贴交给开始屏继续跟手，松手放在哪儿就钉在哪儿（已经钉着的就是挪位置）。
 * · 点搜索按钮：右边出输入框、弹键盘，边打字边筛（拼音首字母 / 全拼，同以前的 Spotlight 逻辑），回车打开排第一的；再点按钮或返回键退出搜索。
 * · 右滑：回开始屏（转门往回翻）。
 * 按系统显示密度画（dp，和通知中心、设置页统一）；手势阈值是旧的"屏宽 200"设计单位乘 g，手感不变。
 */
final class AppList extends FrameLayout {
    interface Callback {
        /** 打开这个应用（转门已经翻完）。 */
        void onLaunch(TileGridView.App app);
        /** 关掉了（转门已经翻完），开始屏该翻回来了。 */
        void onClosed();
        /** 拿着的应用交给开始屏（列表已经翻走）：返回 false = 没接住。坐标是屏幕像素。 */
        boolean onDragToStart(TileGridView.App app, float xPx, float yPx);
        void onDragMove(float xPx, float yPx);
        void onDragEnd(float xPx, float yPx, boolean cancel);
        /** 列表顶上往下拉（同开始屏）：该回表盘了。这里先调 faceTurnOut() 让内容翻走，表盘由外面落下来。 */
        void onPullToFace();
    }

    // 用户草图（按屏宽的比例）：列表这一列从 22.8% 到 78.4%；搜索按钮圆心 (11%, 11.3%)、半径 6.7%
    private static final float COL_L = 0.228f, COL_R = 0.784f, BTN_CX = 0.11f, BTN_CY = 0.113f, BTN_R = 0.067f;
    // dp
    // 方块 40；行与行之间的缝和开始屏磁贴之间一样宽（4 个"屏宽 200"单位 = 9.6 屏幕像素，乘 g 换成 dp）——用户 2026-10-25：间距小一点、卡片放大点、和开始屏缝一样
    private static final float TILE_GAP = 4f, ICON = 40f, ICON_GAP = 10f, FIELD_H = 36f, STROKE = 2f, SAFE_BOTTOM = 28f, EDGE_TEXT_PX = 30f;
    private static final float TILE_S = 47f, R_LIFT_S = 12f;                // 开始屏小磁贴的边长 / 拿起时的圆角（"屏宽 200"单位，乘 g）
    private static final long TS_IN = 350, TS_OUT = 250, TS_STAGGER = 50, HOLD_MS = 400, LIFT_MS = 200;   // 转门同开始屏（WP）：0.35 / 0.25 秒，每行晚 0.05 秒
    private static final long TILT_MS = 16, TILT_HOLD_MS = 200, TILT_OUT_MS = 100;                      // WP TiltEffect：立刻倾，停 0.2 秒再 0.1 秒回正
    // 手势（"屏宽 200"单位，乘 g）
    private static final float DECEL = 0.998f, SPRING_W = 0.02f, BOUNCE_V = 1.5f, SLOP = 6f, HOLD_SLOP = 8f, EDGE_DX = 40f;
    // 回表盘的转门：和开始屏的 faceTurnOut 一样（TileGridView 的 FT_*）——绕屏幕左边缘转到 80°，透视 520（"屏宽 200"单位）、透视中心在屏幕左边正中，
    // 从上往下每行晚 35ms、同一行往右每块晚 12ms，220ms 先慢后快，160ms 起 80ms 淡出
    private static final float FT_DEG = TileGridView.TS_DEG, TS_DEG = TileGridView.TS_DEG;   // WP 转门：往外翻到 50°（负 = 右边朝人）
    private static final long FT_OUT = 250, FT_FADE_AT = 240, FT_FADE_OUT = 10, FT_ROW = 50, FT_COL = 17;

    private static final class Entry {
        final TileGridView.App app;
        final String name, full, initials;
        Entry(TileGridView.App a, String full, String initials) {
            app = a; name = a.name.toLowerCase(Locale.ROOT); this.full = full; this.initials = initials;
        }
    }

    private final TileGridView grid;                 // 颜色 tokens、画设计稿图标
    private Callback callback;
    private final EditText field;
    private final GradientDrawable fieldBg = new GradientDrawable();
    private final TextPaint item = new TextPaint(Paint.ANTI_ALIAS_FLAG);     // 名字 17/22
    private final TextPaint hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);     // 输入框 / 提示 15/20
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private float scale = 1.5f, W = 320f, g = 1.6f, edgeText = 20f, rowH = 46.4f;   // rowH = 方块 + 一条缝

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private static final HashMap<String, String[]> PINYIN = new HashMap<>();   // 名字 → {全拼, 首字母}
    private String query = "";
    private boolean searching, open;
    // 进出搜索的过渡（用户 2026-10-25）：0 = 列表，1 = 搜索。输入框从上面滑下来、同时往左伸把搜索按钮挤出去，整列应用一起左移、往下让出输入框；
    // 一起动，不错开、不回弹（"不要果冻效应"）
    private static final long SEARCH_MS = 350;                      // 滑入（WP 0.35 秒）
    private float spFrom, spTo;
    private long spT0;

    // 转门：0 静止，1 进场，2 退场（关掉 / 拿起），3 打开应用（被点的那行最后走），4 回表盘（同开始屏，从上往下）
    private int anim, sel = -1;
    private float shA, shNear, shFar, shCy;          // begin() 记下的这一块的阴影（强度、不暗 / 最暗的 x、行中线），end() 用
    private long animT0, animSpan;
    private float animE0, animRate = 1f;              // 时钟：进度 = animE0 + animRate·(now − animT0)/slow（快进 / 倒放时改这两个，不跳）
    private boolean rewinding;                        // 翻进来半路按了返回：正倒着翻回去
    private static final float FF_MS = 120f;                  // animSpan：整组的时长（ms，没乘慢放），曲线"整段"模式用
    private float imeH;                              // 键盘高度（dp）

    // 滚动
    private float pos;
    private int motion;
    private float downX, downY, lockY, startPos;
    private int lock, hitRow = -1;
    private boolean hitBtn, atTopAtDown, pulling;
    private float pull;
    private final float[] sy = new float[16];
    private final long[] st = new long[16];
    private int sn;
    private final Runnable holdR = this::lift;

    // 按压倾斜（角度和开始屏统一：TileGridView.TILT_*）
    private int pressRow = -1;
    private boolean pressDown;
    private long pressT0, releaseAt;
    private int tiltMode;
    private float tiltDeg, tiltAxis;

    // 拿起拖到开始屏
    private Entry lifted;
    private boolean dragging, handed, pendingUp, upCancel;
    private float ghostX, ghostY, upX, upY, liftFromX, liftFromY;
    private long liftT0;

    AppList(Context c, TileGridView grid) {
        super(c);
        this.grid = grid;
        setWillNotDraw(false);
        setClipChildren(false);
        Typeface w400 = Fonts.sans(c.getAssets(), 400);
        item.setTypeface(w400); item.setTextSize(17f);
        hint.setTypeface(w400); hint.setTextSize(15f);
        stroke.setStyle(Paint.Style.STROKE);

        field = new EditText(c);
        field.setSingleLine(true);
        field.setTypeface(w400);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setBackground(fieldBg);
        field.setHint("应用名或拼音");
        field.setVisibility(INVISIBLE);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void afterTextChanged(Editable s) { if (searching) setQuery(s.toString()); }
        });
        // 回车：打开排第一的那个（Spotlight）
        field.setOnEditorActionListener((v, actionId, ev) -> {
            if (searching && !shown.isEmpty() && anim == 0 && !query.trim().isEmpty()) { launch(0); return true; }
            return false;
        });
        field.setOnFocusChangeListener((v, f) -> styleField());
        addView(field, new LayoutParams(1, 1));
        // 键盘高度：结果挤在键盘上面
        setOnApplyWindowInsetsListener((v, ins) -> {
            float h = ins.getInsets(WindowInsets.Type.ime()).bottom / scale;
            if (Math.abs(h - imeH) > 0.5f) { imeH = h; pos = Math.max(0f, Math.min(pos, maxScroll())); invalidate(); }
            return ins;
        });
        applyScale();
        setVisibility(GONE);
    }

    void setCallback(Callback cb) { callback = cb; }

    boolean isOpen() { return open; }

    private static float slow() { return TileGridView.slow; }

    // ---------- 密度 ----------

    private void applyScale() {
        scale = getResources().getDisplayMetrics().density;
        int wpx = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        W = wpx / scale;
        g = (wpx / 200f) / scale;
        edgeText = EDGE_TEXT_PX / scale;
        rowH = ICON + TILE_GAP * g;
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale);
        int pad = Math.round(8f * scale);
        field.setPadding(pad, 0, pad, 0);
        styleField();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { applyScale(); }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); applyScale(); invalidate(); }

    private float viewH() { return getHeight() > 0 ? getHeight() / scale : W; }

    private float btnCx() { return BTN_CX * W; }

    private float btnCy() { return BTN_CY * W; }

    private float btnR() { return BTN_R * W; }

    private float colL() { return COL_L * W; }

    private float colR() { return COL_R * W; }

    /** 进出搜索的进度 0..1（指数缓出）。 */
    private float sp() {
        float k = Math.max(0f, Math.min(1f, (SystemClock.uptimeMillis() - spT0) / (SEARCH_MS * slow())));
        return spFrom + (spTo - spFrom) * Curves.AL_SEARCH.getInterpolation(k);
    }

    private boolean spMoving() { return SystemClock.uptimeMillis() - spT0 < SEARCH_MS * slow() + 16; }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** 这一列的左右边：搜索时整列移到左边，左边和输入框对齐（离屏幕边 30 屏幕像素），右边放宽到离屏幕边 30 屏幕像素。 */
    private float colLeft() { return lerp(colL(), edgeText, sp()); }

    private float colRight() { return lerp(colR(), W - edgeText, sp()); }

    /** 输入框：从屏幕上面滑下来（顶边从屏幕外到按钮那一行），左边从列表那一列伸到屏幕左边。 */
    private float fieldLeft() { return lerp(colL(), edgeText, sp()); }

    private float fieldTop() { return lerp(-FIELD_H - 4f, btnCy() - FIELD_H / 2f, sp()); }

    /** 第一行的顶：平时和搜索按钮同一高度（第一行和按钮中心对齐）；搜索时在输入框下面。 */
    private float listTop() { return lerp(btnCy() - rowH / 2f, btnCy() + FIELD_H / 2f + 8f, sp()); }

    private float listBottom() { return viewH() - imeH; }

    private float contentH() { return Math.max(1, shown.size()) * rowH + (searching ? 8f : SAFE_BOTTOM); }

    private float maxScroll() { return Math.max(0f, contentH() - (listBottom() - listTop())); }

    // ---------- 数据 ----------

    /** 全部应用（钉没钉在开始屏上都在），按拼音排。 */
    void setApps(List<TileGridView.App> apps) {
        all.clear();
        for (TileGridView.App a : apps) {
            String[] py = pinyin(a.name);
            all.add(new Entry(a, py[0], py[1]));
        }
        setQuery(searching ? query : "");
    }

    private void setQuery(String q) {
        query = q;
        shown.clear();
        String t = q.trim().toLowerCase(Locale.ROOT), tn = t.replace(" ", "");
        if (t.isEmpty()) {
            shown.addAll(all);
            shown.sort((a, b) -> a.full.compareTo(b.full));
        } else {
            final HashMap<Entry, Integer> score = new HashMap<>();
            for (Entry e : all) {
                int s = 0;
                if (e.name.startsWith(t)) s = 100;
                else if (e.name.contains(t)) s = 80;
                else if (!e.initials.isEmpty() && e.initials.startsWith(tn)) s = 75;
                else if (!e.full.isEmpty() && e.full.startsWith(tn)) s = 70;
                else if (e.initials.contains(tn)) s = 50;
                else if (e.full.contains(tn)) s = 45;
                else if (e.app.pkg.toLowerCase(Locale.ROOT).contains(tn)) s = 20;
                if (s > 0) { score.put(e, s); shown.add(e); }
            }
            shown.sort((a, b) -> {
                int d = score.get(b) - score.get(a);
                return d != 0 ? d : a.name.length() != b.name.length() ? a.name.length() - b.name.length() : a.full.compareTo(b.full);
            });
        }
        pos = 0;
        invalidate();
    }

    /** 中文名 → {全拼（无空格）, 首字母}；用系统自带的 ICU 转写，结果缓存。 */
    private static String[] pinyin(String name) {
        String[] c = PINYIN.get(name);
        if (c != null) return c;
        String full, initials = "";
        try {
            android.icu.text.Transliterator tr = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower");
            String s = tr.transliterate(name).trim();
            StringBuilder ini = new StringBuilder();
            for (String part : s.split("[\\s\\p{Punct}]+")) if (!part.isEmpty()) ini.append(part.charAt(0));
            full = s.replaceAll("[\\s\\p{Punct}]+", "");
            initials = ini.toString();
        } catch (Throwable ignored) {
            full = name.toLowerCase(Locale.ROOT);
        }
        c = new String[] {full, initials};
        PINYIN.put(name, c);
        return c;
    }

    // ---------- 打开 / 关闭 / 搜索 ----------

    /** 进场：搜索按钮、每一行依次按转门翻进来。 */
    void open() {
        searching = false;
        spFrom = spTo = 0f;
        query = "";
        field.setText("");
        field.setVisibility(INVISIBLE);
        setQuery("");
        pos = 0;
        imeH = 0;
        dragging = handed = pendingUp = false;
        pressRow = -1;
        open = true;
        // 纯黑主题不刷底色（根布局本来就是黑的，开始屏翻走后也不画了）：每帧少刷一整屏
        if (grid.screenBg() == 0xFF000000) setBackground(null); else setBackgroundColor(grid.screenBg());
        setVisibility(VISIBLE);
        requestApplyInsets();
        styleField();
        start(1, -1);
    }

    /** 回表盘：内容按开始屏的样子从上往下翻走；返回最晚那块的延迟（ms，没乘慢放），表盘在它 + 200ms 后落下来。 */
    long faceTurnOut() {
        hideKeyboard();
        motion++;
        anim = 4;
        animT0 = SystemClock.uptimeMillis(); animE0 = 0f; animRate = 1f; rewinding = false;
        field.setVisibility(INVISIBLE);
        float lastCy = Math.min(listBottom(), listTop() - pos + shown.size() * rowH) - rowH / 2f;
        long last = Math.max(0, Math.round((lastCy - btnCy()) / rowH)) * stg(4) + stg(4) * FT_COL / FT_ROW;
        animSpan = last + FT_OUT;
        invalidate();
        return last;
    }

    /** 返回键：在搜索里先退出搜索，否则关掉列表。 */
    void back() {
        if (dragging) return;
        if (searching) exitSearch(); else close();
    }

    /** 关掉：所有元素往回翻走，翻完开始屏翻回来。 */
    void close() {
        if (!open || anim == 2 || anim == 3 || rewinding) return;
        hideKeyboard();
        if (anim == 1) {                                 // 还没翻进来完就返回：原路倒着翻回去（不跳），回到起点后开始屏翻回来
            long now = SystemClock.uptimeMillis();
            animE0 = aEl(now); animT0 = now; animRate = -1f; rewinding = true;
            motion++;
            invalidate();
            return;
        }
        start(2, -1);
        postDelayed(() -> { dismiss(); if (callback != null) callback.onClosed(); }, length(2));
    }

    /** 直接收掉（回到表盘等）。 */
    void dismiss() {
        hideKeyboard();
        motion++;
        anim = 0;
        rewinding = false;
        animRate = 1f;
        open = false;
        searching = false;
        spFrom = spTo = 0f;
        dragging = handed = pendingUp = false;
        removeCallbacks(holdR);
        field.setVisibility(INVISIBLE);
        setVisibility(GONE);
    }

    private void enterSearch() {
        searching = true;
        field.setText("");
        setQuery("");
        spFrom = sp(); spTo = 1f; spT0 = SystemClock.uptimeMillis();
        field.setVisibility(INVISIBLE);                 // 过渡期间画在画布上，到位了再换成真的输入框、弹键盘
        final int me = ++motion;
        postDelayed(() -> {
            if (me != motion || !searching || !open) return;
            placeField();
            field.setVisibility(VISIBLE);
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, Math.round(SEARCH_MS * slow()) + 16);
        invalidate();
    }

    /** 退出搜索：原路返回（输入框往右缩、往上滑走，按钮被放回来，整列回到原位）。 */
    private void exitSearch() {
        hideKeyboard();
        searching = false;
        field.setText("");
        field.setVisibility(INVISIBLE);
        setQuery("");
        spFrom = sp(); spTo = 0f; spT0 = SystemClock.uptimeMillis();
        motion++;
        invalidate();
    }

    private void launch(int idx) {
        if (idx < 0 || idx >= shown.size()) return;
        hideKeyboard();
        final TileGridView.App app = shown.get(idx).app;
        start(3, idx);
        postDelayed(() -> { if (callback != null) callback.onLaunch(app); }, length(3));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    private void start(int a, int selected) {
        anim = a;
        sel = selected;
        animT0 = SystemClock.uptimeMillis(); animE0 = 0f; animRate = 1f; rewinding = false;
        long n = elements();
        animSpan = a == 1 ? Math.max(0, n - 1) * stg(1) + TS_IN : a == 3 ? lastDelay() + TS_OUT : n * stg(a) + TS_OUT;
        field.setVisibility(INVISIBLE);                 // 翻的时候输入框画在画布上（和别的元素一起转）
        final int me = ++motion;
        postDelayed(() -> { if (motion == me && anim == a) finishAnim(); }, length(a));
        invalidate();
    }

    private void finishAnim() {
        anim = 0;
        animRate = 1f;
        if (searching && open && !dragging) field.setVisibility(VISIBLE);
        invalidate();
    }

    private float aEl(long now) { return Math.max(0f, animE0 + animRate * (now - animT0) / slow()); }

    /** 翻进来的时候手指按下：剩下的一段在 FF_MS 内走完（接着现在的角度走、只是变快，不跳），手势照常接。 */
    private void fastForward(long now) {
        float e = aEl(now), rem = animSpan - e;
        if (rem > FF_MS) { animE0 = e; animT0 = now; animRate = rem / FF_MS; }
        invalidate();
    }

    // ---------- 输入框 ----------

    private void styleField() {
        fieldBg.setColor(grid.tok("surface-2"));
        fieldBg.setStroke(Math.round(STROKE * scale), field.hasFocus() ? grid.accent() : grid.tok("line-strong"));
        field.setTextColor(grid.tok("ink"));
        field.setHintTextColor(grid.tok("ink-subtle"));
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(grid.tok("accent-ink"));
        cursor.setSize(Math.round(2 * scale), Math.round(18 * scale));
        if (android.os.Build.VERSION.SDK_INT >= 29) field.setTextCursorDrawable(cursor);
    }

    /** 输入框（到位后）：左右各离屏幕边 30 屏幕像素，和原来按钮中心同高。 */
    private void placeField() {
        LayoutParams lp = (LayoutParams) field.getLayoutParams();
        int x = Math.round(edgeText * scale), y = Math.round((btnCy() - FIELD_H / 2f) * scale);
        int w = Math.round((W - 2f * edgeText) * scale), h = Math.round(FIELD_H * scale);
        if (lp.leftMargin != x || lp.topMargin != y || lp.width != w || lp.height != h) {
            lp.leftMargin = x; lp.topMargin = y; lp.width = w; lp.height = h;
            field.setLayoutParams(lp);
        }
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        if (handed) return;                              // 已经交给开始屏：这一层只是透明的触摸转发
        long now = SystemClock.uptimeMillis();
        // 翻进来走完了就收尾（快进过的不用等原来的定时器）；倒放回到起点了就收掉、让开始屏翻回来
        if (anim == 1 && !rewinding && aEl(now) >= animSpan) finishAnim();
        if (rewinding && aEl(now) <= 0f) { rewinding = false; post(() -> { dismiss(); if (callback != null) callback.onClosed(); }); }
        boolean again = anim != 0 || dragging || spMoving();
        float spNow = sp();
        if (searching && !spMoving()) placeField();
        c.save();
        c.scale(scale, scale);
        boolean out = dragging && anim != 2;             // 拿起后列表已经翻走：只剩手里那块
        int el = 0;
        if (!out) {
            if (spNow < 0.999f) { if (begin(c, el, now, btnCy(), -1)) { drawButton(c, spNow); end(c); } }
            el++;
            if (spNow > 0.001f) {
                if (field.getVisibility() != VISIBLE && begin(c, el, now, btnCy(), -1)) { drawFieldStandIn(c); end(c); }
                el++;
            }
            float top = listTop(), bottom = listBottom();
            c.save();
            c.clipRect(0, spNow > 0.001f ? Math.max(0f, fieldTop() + FIELD_H + 4f) : 0f, W, bottom);   // 滚动时行从输入框下面过
            float y = top - pos;
            if (shown.isEmpty() && searching && !query.trim().isEmpty()) {
                if (begin(c, el, now, y + rowH / 2f, -1)) {
                    hint.setColor(grid.tok("ink-muted"));
                    c.drawText(fit("没有找到“" + query.trim() + "”", hint, colRight() - colLeft()), colLeft(), TileGridView.baseline(hint, y + (rowH - 20f) / 2f, 20f), hint);
                    end(c);
                }
            }
            for (int i = 0; i < shown.size(); i++, y += rowH) {
                if (y + rowH < -4 || y > bottom + 4) { if (y + rowH >= -4) el++; continue; }
                if (begin(c, el++, now, y + rowH / 2f, i)) {
                    boolean hl = searching && i == 0 && !query.trim().isEmpty();
                    again |= drawRow(c, shown.get(i), i, y, hl, now);
                    end(c);
                }
            }
            c.restore();
        }
        if (dragging && lifted != null) drawGhost(c, now);
        c.restore();
        if (again) postInvalidateOnAnimation();
    }

    private static String fit(String s, TextPaint p, float w) { return TextUtils.ellipsize(s, p, Math.max(0f, w), TextUtils.TruncateAt.END).toString(); }

    /** 搜索按钮：GlyphButton（2 的描边圆 + 放大镜）。进搜索时被输入框的左边推着往左走出屏幕、同时淡出。 */
    private void drawButton(Canvas c, float sp) {
        float cx = btnCx() - (colL() - fieldLeft()), cy = btnCy(), rr = btnR();
        boolean down = hitBtn && lock == 0 && anim == 0 && downActive;
        int ink = grid.tok("ink"), a = Math.round(255 * (1f - sp));
        if (down) {
            fill.setColor(grid.tok("surface-2"));
            fill.setAlpha(a);
            c.drawCircle(cx, cy, rr, fill);
            fill.setAlpha(255);
        }
        stroke.setStrokeWidth(STROKE);
        stroke.setColor((ink & 0x00FFFFFF) | (a << 24));
        c.drawCircle(cx, cy, rr - STROKE / 2f, stroke);
        grid.glyphCentered(c, "search", cx, cy, 18f, (ink & 0x00FFFFFF) | (a << 24));
    }

    /** 转门期间输入框画在画布上。 */
    private void drawFieldStandIn(Canvas c) {
        float x = fieldLeft(), y = fieldTop(), w = W - edgeText - x;
        fill.setColor(grid.tok("surface-2"));
        r.set(x, y, x + w, y + FIELD_H);
        c.drawRect(r, fill);
        stroke.setStrokeWidth(STROKE);
        stroke.setColor(grid.tok("line-strong"));
        r.inset(STROKE / 2f, STROKE / 2f);
        c.drawRect(r, stroke);
        boolean empty = query.isEmpty();
        hint.setColor(empty ? grid.tok("ink-subtle") : grid.tok("ink"));
        c.drawText(fit(empty ? "应用名或拼音" : query, hint, w - 16f), x + 8f, TileGridView.baseline(hint, y + (FIELD_H - 20f) / 2f, 20f), hint);
    }

    /** 一行：40 的方块图标（tile-soft 底 + 图标）+ 17/22 名字，名字到列表那一列的右边为止；排第一的结果垫一块 surface-2。 */
    private boolean drawRow(Canvas c, Entry e, int i, float y, boolean hl, long now) {
        boolean again = false;
        float cl = colLeft(), cr = colRight();
        c.save();
        if (i == pressRow) again = applyTilt(c, now, (cl + cr) / 2f, y + rowH / 2f);
        if (hl) {
            fill.setColor(grid.tok("surface-2"));
            c.drawRect(cl - 6f, y, cr + 6f, y + rowH, fill);
        }
        if (dragging && lifted == e) { c.restore(); return again; }   // 拿起来的那行：原位空着
        float iy = y + (rowH - ICON) / 2f;
        drawIcon(c, e.app, cl, iy, ICON, 0f);
        item.setColor(grid.tok("ink"));
        float tx = cl + ICON + ICON_GAP;
        c.drawText(fit(e.app.name, item, cr - tx), tx, TileGridView.baseline(item, y + (rowH - 22f) / 2f, 22f), item);
        c.restore();
        return again;
    }

    /** 方块图标：底色 + 应用自己的图标（62%）或设计稿图标；radius > 0 时是圆角（拿起来的样子）。 */
    private void drawIcon(Canvas c, TileGridView.App a, float x, float y, float s, float radius) {
        fill.setColor(a.bg);
        r.set(x, y, x + s, y + s);
        if (radius > 0f) c.drawRoundRect(r, radius, radius, fill); else c.drawRect(r, fill);
        float is = s * (radius > 0f ? 0.47f : 0.62f);              // 拿起来时按开始屏小磁贴的比例（22 / 47）
        if (a.icon != null) {
            // 按不透明部分居中、缩放：各家前景层留白不一样，按整张图放会有的大有的小、有的偏
            TileGridView.bitmapCentered(c, a.icon, x + s / 2f, y + s / 2f, s * (radius > 0f ? 0.42f : 0.55f), bmp);
        } else grid.glyphCentered(c, a.glyph.isEmpty() ? "more" : a.glyph, x + s / 2f, y + s / 2f, is, a.fg);
    }

    /** 拿着的那块：从行里的小方块长成开始屏小磁贴的大小、长出圆角（200ms），中心跟着手指。 */
    private void drawGhost(Canvas c, long now) {
        float k = Curves.AL_LIFT.getInterpolation(Math.max(0f, Math.min(1f, (now - liftT0) / (LIFT_MS * slow()))));
        float size = ICON + (TILE_S * g * 1.08f - ICON) * k;
        float cx = liftFromX + (ghostX - liftFromX) * k, cy = liftFromY + (ghostY - liftFromY) * k;
        drawIcon(c, lifted.app, cx - size / 2f, cy - size / 2f, size, R_LIFT_S * g * k);
    }

    /**
     * 第 i 个元素开始画：按当前动画套上转门变换（绕屏幕左边缘，透视中心在屏幕正中）。
     * 返回 false 表示这一帧它已经翻走、不用画；返回 true 时调用方画完要 c.restore()。
     */
    private boolean begin(Canvas c, int i, long now, float cy, int row) {
        float deg = 0f;
        // 进场 / 退场 / 打开应用都和开始屏的转门一模一样（用户 2026-10-25："这个退出/进入动画要跟开始界面一样"）：
        // 绕屏幕左边缘转，正角度 = 右边往屏幕里走（往里翻），最多 90°，透视 600、透视中心在这一行自己的中间
        if (anim == 1) {                                  // 进场：从外面 50°（右边朝人）转回 0——翻走的反过来（WP）
            float k = lk(Curves.AL_TS_IN, now, i * stg(1), TS_IN);
            deg = TS_DEG * (1f - Curves.AL_TS_IN.shape(k));
        } else if (anim == 2) {                           // 退场：0 → 往外 50°（往左甩走，WP），从下往上一个个走，最上面的搜索按钮最后（用户 2026-10-25）
            long d = Math.round(Math.max(0f, (listBottom() - cy) / rowH) * stg(2));
            float k = lk(Curves.AL_TS_OUT, now, d, TS_OUT);
            if (k >= 1f) return false;
            deg = TS_DEG * Curves.AL_TS_OUT.shape(k);
        } else if (anim == 3) {                           // 打开应用：0 → 往外 50°，被点的那行最后走
            long delay = row >= 0 && row == sel ? lastDelay() : i * stg(3);
            float k = lk(Curves.AL_LAUNCH, now, delay, TS_OUT);
            if (k >= 1f) return false;
            deg = TS_DEG * Curves.AL_LAUNCH.shape(k);
        }
        float extraA = 1f;                                // 回表盘那种按时间淡掉的不透明度
        if (anim == 4) {                                  // 下滑回表盘：同开始屏回表盘（WP 转门出场，按行从上往下）
            boolean button = row < 0 && Math.abs(cy - btnCy()) < 0.5f;
            long dl = Math.max(0, Math.round((cy - btnCy()) / rowH)) * stg(4) + (button ? 0 : stg(4) * FT_COL / FT_ROW);
            float lv = Curves.AL_FT_OUT.vt(aEl(now), animSpan) - dl;
            float k = Math.max(0f, Math.min(1f, lv / FT_OUT));
            extraA = 1f - Math.max(0f, Math.min(1f, (lv - FT_FADE_AT) / FT_FADE_OUT));
            if (extraA <= 0f) return false;
            deg = FT_DEG * Curves.AL_FT_OUT.shape(k);
        }
        float fa = Shade.edge(deg, TS_DEG, anim != 1) * extraA;           // 快转到头时整块淡掉（翻走的从一半角度就开始淡）
        if (fa <= 0f) return false;
        if (fa < 0.999f) c.saveLayerAlpha(0, cy - rowH / 2f - 2f, W, cy + rowH / 2f + 2f, Math.round(255 * fa)); else c.save();
        shA = 0f;
        if (deg != 0f) {
            shA = Shade.amount(deg); shCy = cy;
            shNear = 0f; shFar = W;                       // 转轴在左边：越往右越暗
            camera.save();
            camera.setLocation(0, 0, -600f * g / 72f);
            float ax = W / 2f - TileGridView.TS_AXIS * W;   // 同 TileGridView.rotateHinge：转轴在左边缘再往左 20%，挪到原点、转、再挪回去
            camera.translate(-ax, 0, 0);
            camera.rotateY(deg);
            camera.translate(ax, 0, 0);
            camera.getMatrix(matrix);
            camera.restore();
            matrix.preTranslate(-W / 2f, -cy);
            matrix.postTranslate(W / 2f, cy);
            c.concat(matrix);
        }
        return true;
    }

    /** begin() 之后画完这一块：没转平就盖上阴影（列表底是纯黑，整行盖一层只会压暗图标和字），再 restore。 */
    private void end(Canvas c) {
        if (shA > 0f) Shade.over(c, shA, shNear, shFar, 0f, shCy - rowH / 2f, W, shCy + rowH / 2f);
        shA = 0f;
        c.restore();
    }

    private int elements() {
        float vis = (listBottom() - Math.max(0f, listTop() - pos)) / rowH + 2;
        return 1 + (searching ? 1 : 0) + (int) Math.min(shown.size(), vis);
    }

    private long lastDelay() { return elements() * stg(3) + TS_OUT; }

    private long length(int a) {
        long n = elements(), ms;
        if (a == 1) ms = n * stg(1) + TS_IN;
        else if (a == 3) ms = lastDelay() + TS_OUT;
        else ms = n * stg(a) + TS_OUT;
        return Math.round(ms * slow());
    }

    /** 动画 a 里每行之间晚多少 ms（调曲线网页可改；回表盘那种是"行"的间隔，列按同样比例跟着变）。 */
    private long stg(int a) {
        switch (a) {
            case 1: return Curves.AL_TS_IN.stagger(TS_STAGGER);
            case 2: return Curves.AL_TS_OUT.stagger(TS_STAGGER);
            case 3: return Curves.AL_LAUNCH.stagger(TS_STAGGER);
            default: return Curves.AL_FT_OUT.stagger(FT_ROW);
        }
    }

    /** 错开的一组里延迟 delay、时长 dur 的那一块此刻的进度 0..1（曲线"整段 / 各自"见 Curves.Curve.vt）。 */
    private float lk(Curves.Curve cv, long now, long delay, long dur) {
        return Math.max(0f, Math.min(1f, (cv.vt(aEl(now), animSpan) - delay) / dur));
    }

    private static float prog(long now, long t0, long dur) { return Math.max(0f, Math.min(1f, (now - t0) / (dur * slow()))); }

    // ---------- 按压倾斜 ----------

    private float tiltAmount(long now) {
        float in = TILT_MS * slow();
        if (pressDown || now < releaseAt) return Curves.TILT_IN.getInterpolation(Math.max(0f, Math.min(1f, (now - pressT0) / in)));
        float at = Curves.TILT_IN.getInterpolation(Math.max(0f, Math.min(1f, (releaseAt - pressT0) / in)));
        float rr = Math.max(0f, Math.min(1f, (now - releaseAt) / (TILT_OUT_MS * slow())));
        return at * (1f - Curves.TILT_OUT.getInterpolation(rr));
    }

    private boolean applyTilt(Canvas c, long now, float cx, float cy) {
        float k = tiltAmount(now);
        boolean released = !pressDown && now >= releaseAt;
        if (released && k <= 0f) { pressRow = -1; return false; }
        boolean again = !(pressDown && k >= 1f);
        if (tiltMode == 0) { float s = 1f - TileGridView.TILT_SINK * k; c.scale(s, s, cx, cy); return again; }
        float deg = tiltDeg * k;
        camera.save();
        camera.setLocation(0, 0, -TileGridView.TILT_PERSP * g / 72f);
        if (tiltMode == 1) camera.rotateY(deg);
        else if (tiltMode == 2) camera.rotateX(deg);
        if (tiltMode == 3) {
            camera.restore();
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
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
        c.concat(matrix);
        return again;
    }

    /** 按下一行：按在两头绕中轴、角上绕对角线、中间整块下陷；角度和开始屏统一。 */
    private void press(int row, float x, float y) {
        float rx = colLeft(), rw = colRight() - colLeft(), ry = listTop() - pos + row * rowH, rh = rowH;
        float cx = rx + rw / 2f, cy = ry + rh / 2f;
        float nx = Math.max(-1f, Math.min(1f, (x - cx) / (rw / 2f))), ny = Math.max(-1f, Math.min(1f, (y - cy) / (rh / 2f)));
        float ax = Math.abs(nx), ay = Math.abs(ny);
        if (ax < 0.3f && ay < 0.3f) tiltMode = 0;
        else if (ax > 0.55f && ay > 0.55f) {
            float sxn = Math.signum(nx), syn = Math.signum(ny);
            tiltAxis = (float) Math.atan2(-syn * rh, sxn * rw);
            float d = (float) (-Math.sin(tiltAxis) * sxn * rw / 2f + Math.cos(tiltAxis) * syn * rh / 2f);
            tiltDeg = d < 0 ? TileGridView.TILT_CORNER_DEG : -TileGridView.TILT_CORNER_DEG;
            tiltMode = 3;
        } else if (ax >= ay) {
            tiltDeg = nx >= 0 ? TileGridView.TILT_EDGE_DEG : -TileGridView.TILT_EDGE_DEG;
            tiltMode = 1;
        } else {
            tiltDeg = ny < 0 ? TileGridView.TILT_EDGE_DEG : -TileGridView.TILT_EDGE_DEG;
            tiltMode = 2;
        }
        pressRow = row; pressDown = true; pressT0 = SystemClock.uptimeMillis(); releaseAt = 0;
        invalidate();
    }

    private void unpress() {
        if (pressRow >= 0 && pressDown) {
            long now = SystemClock.uptimeMillis();
            pressDown = false;
            releaseAt = now + Math.round(TILT_HOLD_MS * slow());          // WP：松手先停 0.2 秒再回正
        }
        invalidate();
    }

    // ---------- 触摸 ----------

    private boolean downActive;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return dragging;                                 // 拿着的时候手指可能划过输入框，全归这一层
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        long now = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (anim == 1 && !rewinding) fastForward(now);   // 翻进来的时候按下：剩下的一段快速走完，手势照常接
                if ((anim != 0 && anim != 1) || rewinding || !open || dragging || spMoving()) return true;
                motion++;
                downX = x; downY = y; lock = 0; sn = 0; startPos = pos; downActive = true;
                atTopAtDown = pos <= 1f; pulling = false; pull = 0f;
                sample(now, y);
                hitBtn = !searching && Math.hypot(x - btnCx(), y - btnCy()) <= btnR() + 6f;
                hitRow = hitBtn ? -1 : rowAt(x, y);
                if (hitRow >= 0) { press(hitRow, x, y); postDelayed(holdR, Math.round(HOLD_MS * slow())); }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    if (handed) { if (callback != null) callback.onDragMove(e.getX(), e.getY()); }
                    else { ghostX = x; ghostY = y; invalidate(); }
                    return true;
                }
                if (!downActive) return true;
                float dx = x - downX, dy = y - downY;
                if (Math.hypot(dx, dy) > HOLD_SLOP * g) removeCallbacks(holdR);
                if (lock == 0) {
                    if (Math.abs(dx) < SLOP * g && Math.abs(dy) < SLOP * g) return true;
                    lock = Math.abs(dy) >= Math.abs(dx) ? 1 : -1;
                    lockY = downY + (dy > 0 ? SLOP * g : -SLOP * g);
                    removeCallbacks(holdR);
                    unpress();
                }
                if (lock == 1) {
                    sample(now, y);
                    float raw = startPos - (y - lockY), m = maxScroll(), d = listBottom() - listTop();
                    if (raw < 0 && atTopAtDown && !searching) {   // 顶上往下拉：同开始屏，列表不跟手，记下距离、松手决定回不回表盘
                        pulling = true; pull = -raw; pos = 0;
                    } else {
                        if (pulling) { pulling = false; pull = 0f; }
                        pos = raw < 0 ? -rubber(-raw, d) : raw > m ? m + rubber(raw - m, d) : raw;
                    }
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                removeCallbacks(holdR);
                if (dragging) {
                    if (handed) {
                        if (callback != null) callback.onDragEnd(e.getX(), e.getY(), cancel);
                        dismiss();
                    } else { pendingUp = true; upX = e.getX(); upY = e.getY(); upCancel = cancel; }
                    return true;
                }
                if (!downActive) return true;
                downActive = false;
                if (lock == 0 && !cancel && hitBtn) { hitBtn = false; if (searching) exitSearch(); else enterSearch(); return true; }
                if (lock == 0 && !cancel && hitRow >= 0) { int h = hitRow; hitRow = -1; launch(h); return true; }   // 倾着不回正，等它翻走
                if (lock == -1 && !cancel && x - downX > EDGE_DX * g && Math.abs(y - downY) < 40f * g) { unpress(); close(); return true; }
                if (lock == 1 && pulling) {                       // 判定同开始屏：拉过 40，或者过 12 且往下快甩（> 0.4）
                    float v = cancel ? 0f : fingerV(now);
                    boolean commit = !cancel && (pull > 40f * g || (pull > 12f * g && v > 0.4f * g));
                    pulling = false; pull = 0f;
                    if (commit && callback != null) { hitBtn = false; hitRow = -1; unpress(); callback.onPullToFace(); return true; }
                } else if (lock == 1) settle(cancel ? 0 : -fingerV(now));
                hitBtn = false;
                hitRow = -1;
                unpress();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private int rowAt(float x, float y) {
        if (x < colLeft() - 8f || y > listBottom()) return -1;
        if (searching && y < listTop() - 4f) return -1;
        float yy = y - listTop() + pos;
        int i = (int) Math.floor(yy / rowH);
        return yy >= 0 && i < shown.size() ? i : -1;
    }

    /** 长按满 400ms：拿起这一行，列表整个翻走，交给开始屏。 */
    private void lift() {
        if (anim == 1 && !rewinding) finishAnim();     // 快进还没走完就点了：直接收尾（只剩一两度）
        if (hitRow < 0 || hitRow >= shown.size() || lock != 0 || anim != 0 || dragging) return;
        lifted = shown.get(hitRow);
        dragging = true; handed = false; pendingUp = false;
        pressRow = -1; pressDown = false;
        liftFromX = colLeft() + ICON / 2f;
        liftFromY = listTop() - pos + hitRow * rowH + rowH / 2f;
        ghostX = downX; ghostY = downY;
        liftT0 = SystemClock.uptimeMillis();
        android.os.Vibrator vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(10, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        hideKeyboard();
        start(2, -1);
        final int me = motion;
        postDelayed(() -> { if (dragging && !handed && motion == me) handOff(); }, length(2));
    }

    private void handOff() {
        boolean ok = callback != null && callback.onDragToStart(lifted.app, ghostX * scale, ghostY * scale);
        if (!ok) { dragging = false; lifted = null; start(1, -1); return; }
        handed = true;
        anim = 0;
        field.setVisibility(INVISIBLE);
        setBackgroundColor(0);                           // 透明：底下的开始屏露出来，这一层只转发触摸
        invalidate();
        if (pendingUp) {
            pendingUp = false;
            if (callback != null) callback.onDragEnd(upX, upY, upCancel);
            dismiss();
        }
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

    /** 松手：同开始屏的手感（惯性、撞边回弹）。 */
    private void settle(float v) {
        final int me = ++motion;
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
                float m = maxScroll();
                boolean more;
                if (Float.isNaN(spring[0]) && (pos < 0 || pos > m)) {
                    spring[0] = pos < 0 ? 0 : m; spring[1] = pos - spring[0];
                    spring[2] = Math.max(-bounce, Math.min(bounce, vv[0])); spring[3] = 0;
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
}
