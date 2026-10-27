package com.tessera.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Camera;
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
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 应用搜索（用户 2026-10-21 指定）：在原生开始屏从右往左滑进来。
 * 逻辑同 macOS Spotlight——边打字边出结果，最匹配的排第一并高亮，回车直接打开它；中文名可以用拼音首字母 / 全拼搜。
 * 界面和动画是 Metro：PivotHeader 式的大标题（26/32 细体、全小写）、ListItem 式的结果行（32 方块图标 + 17/22 标题），
 * 进出都用转门（绕屏幕左边缘，标题 → 输入框 → 每一行依次错开）。
 * 坐标都是设计像素（屏宽 200），绘制时乘 scale = 宽 / 200；颜色取自 TileGridView 推来的设计稿 tokens。
 */
final class SearchPage extends FrameLayout {
    interface Callback {
        /** 打开这个应用（转门已经翻完）。 */
        void onLaunch(TileGridView.App app);
        /** 关掉了（转门已经翻完），开始屏该翻回来了。 */
        void onClosed();
    }

    // ---------- tokens（设计像素） ----------
    private static final float SIZE = 200f, SAFE = 12f, ROW = 48f, ICON = 32f, FIELD_H = 36f, STROKE = 2f;
    // 键盘出来时的紧凑尺寸：这台手表的键盘占掉六成屏，输入框挪到最上面，排第一的结果压成一行紧贴在键盘上沿
    private static final float ROW_C = 30f, ICON_C = 22f, FIELD_HC = 32f;
    private static final long TS_IN = 350, TS_OUT = 250, TS_STAGGER = 30, COMPACT_MS = 200;
    private static final float DECEL = 0.998f, SPRING_W = 0.02f, BOUNCE_V = 1.5f, SLOP = 6f, EDGE_DX = 40f;
    private static final PathInterpolator EXPO_OUT = new PathInterpolator(0.1f, 0.9f, 0.2f, 1f);
    private static final PathInterpolator ACT_IN = new PathInterpolator(0.7f, 0f, 0.9f, 0.3f);

    private static final class Entry {
        final TileGridView.App app;
        final String name, full, initials;
        Entry(TileGridView.App a, String full, String initials) {
            app = a; name = a.name.toLowerCase(Locale.ROOT); this.full = full; this.initials = initials;
        }
    }

    private final TileGridView grid;
    private Callback callback;
    private final EditText field;
    private final GradientDrawable fieldBg = new GradientDrawable();
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);    // .ts-pivot-app 11/14 600 0.08em 大写
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);    // .ts-pivot-item 26/32 300 -0.01em
    private final TextPaint item = new TextPaint(Paint.ANTI_ALIAS_FLAG);     // .ts-item-title 17/22
    private final TextPaint hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);     // 输入框里的文字 15/20
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);    // 分组标题 11/14 600
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private float scale = 2.4f;

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private static final HashMap<String, String[]> PINYIN = new HashMap<>();   // 名字 → {全拼, 首字母}
    private String query = "";

    // 动画：0 静止，1 进场，2 关闭（往回翻），3 打开应用（往前翻走，选中那行最后走）
    private int anim;
    private long animT0;
    private int sel = -1;
    private boolean open;
    private float imeH;                // 键盘高度（设计像素）
    private float compact, compactFrom;
    private long compactT0;

    // 结果列表滚动
    private float pos;
    private int motion;
    private float downX, downY, lockY, startPos;
    private int lock, hit = -1;
    private final float[] sy = new float[16];
    private final long[] st = new long[16];
    private int sn;

    SearchPage(Context c, TileGridView grid) {
        super(c);
        this.grid = grid;
        setWillNotDraw(false);
        setClipChildren(false);
        Typeface w300 = Fonts.sans(c.getAssets(), 300), w400 = Fonts.sans(c.getAssets(), 400), w600 = Fonts.sans(c.getAssets(), 600);
        label.setTypeface(w600); label.setTextSize(11f); label.setLetterSpacing(0.08f);
        title.setTypeface(w300); title.setTextSize(26f); title.setLetterSpacing(-0.01f);
        item.setTypeface(w400); item.setTextSize(17f);
        hint.setTypeface(w400); hint.setTextSize(15f);
        small.setTypeface(w600); small.setTextSize(11f); small.setLetterSpacing(0.08f);

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
            @Override public void afterTextChanged(Editable s) { setQuery(s.toString()); }
        });
        // 回车：打开排第一的那个（Spotlight）
        field.setOnEditorActionListener((v, actionId, ev) -> {
            if (!shown.isEmpty() && anim == 0 && !query.trim().isEmpty()) { launch(0); return true; }
            return false;
        });
        field.setOnFocusChangeListener((v, f) -> styleField());
        addView(field, new LayoutParams(1, 1));
        // 键盘高度：结果要挤在键盘上面，标题跟着收起来
        setOnApplyWindowInsetsListener((v, ins) -> {
            float h = ins.getInsets(WindowInsets.Type.ime()).bottom / scale;
            if (Math.abs(h - imeH) > 0.5f) { imeH = h; setCompact(h > 0 ? 1f : 0f); }
            return ins;
        });
        setVisibility(GONE);
    }

    void setCallback(Callback cb) { callback = cb; }

    boolean isOpen() { return open; }

    // ---------- 打开 / 关闭 ----------

    /** 进场：标题、输入框、每一行依次按转门翻进来，翻完弹出键盘。 */
    void open() {
        all.clear();
        for (TileGridView.App a : grid.apps()) {
            String[] py = pinyin(a.name);
            all.add(new Entry(a, py[0], py[1]));
        }
        query = "";
        field.setText("");
        pos = 0;
        imeH = 0;
        compact = 0; compactFrom = 0;
        setQuery("");
        open = true;
        setBackgroundColor(grid.screenBg());
        setVisibility(VISIBLE);
        requestApplyInsets();
        styleField();
        start(1, -1);
        postDelayed(() -> {
            if (!open || anim != 0) return;
            field.setVisibility(VISIBLE);
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, length(1) + 20);
    }

    /** 返回：所有元素往回翻走，翻完开始屏翻回来。 */
    void close() {
        if (!open || anim == 2 || anim == 3) return;
        hideKeyboard();
        start(2, -1);
        postDelayed(() -> { dismiss(); if (callback != null) callback.onClosed(); }, length(2));
    }

    /** 直接收掉（回到表盘等）。 */
    void dismiss() {
        hideKeyboard();
        motion++;
        anim = 0;
        open = false;
        field.setVisibility(INVISIBLE);
        setVisibility(GONE);
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
        animT0 = SystemClock.uptimeMillis();
        field.setVisibility(INVISIBLE);           // 翻的时候输入框画在画布上（和别的元素一起转）
        final int me = ++motion;
        postDelayed(() -> { if (motion == me && anim == a) { anim = 0; if (a == 1) invalidate(); } }, length(a));
        invalidate();
    }

    // ---------- 搜索 ----------

    private void setQuery(String q) {
        query = q;
        shown.clear();
        String t = q.trim().toLowerCase(Locale.ROOT), tn = t.replace(" ", "");
        if (t.isEmpty()) {
            shown.addAll(all);
            shown.sort((a, b) -> a.full.compareTo(b.full));       // 全部应用：按拼音排
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
        String full = "", initials = "";
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

    // ---------- 布局（键盘出来时收起大标题） ----------

    private void setCompact(float to) {
        compactFrom = compactNow();
        compact = to;
        compactT0 = SystemClock.uptimeMillis();
        invalidate();
    }

    private float compactNow() {
        float k = EXPO_OUT.getInterpolation(clamp((SystemClock.uptimeMillis() - compactT0) / (float) COMPACT_MS, 0, 1));
        return compactFrom + (compact - compactFrom) * k;
    }

    private static final float LABEL_Y = 18f;

    private float fieldY(float cp) { return 72f - 64f * cp; }    // 展开：大标题下面；收起：屏幕最上面

    private float fieldH(float cp) { return FIELD_H + (FIELD_HC - FIELD_H) * cp; }

    private float rowH(float cp) { return ROW + (ROW_C - ROW) * cp; }

    private float iconS(float cp) { return ICON + (ICON_C - ICON) * cp; }

    /** 空搜索时"全部应用"那行分组标题的高度（紧凑时收起）。 */
    private float headH(float cp) { return query.trim().isEmpty() ? 22f * (1f - cp) : 0f; }

    private float listY(float cp) { return fieldY(cp) + fieldH(cp) + 8f - 4f * cp; }

    private float viewH() { return getHeight() > 0 ? getHeight() / scale : SIZE; }

    private float listBottom() { return viewH() - imeH; }

    private float contentH(float cp) { return headH(cp) + Math.max(1, shown.size()) * rowH(cp); }

    private float maxScroll(float cp) { return Math.max(0, contentH(cp) - (listBottom() - listY(cp))); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = w / SIZE;
        hint.setTextSize(15f);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale);
        int pad = Math.round(8f * scale);
        field.setPadding(pad, 0, pad, 0);
        styleField();
    }

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

    private void placeField(float cp) {
        LayoutParams lp = (LayoutParams) field.getLayoutParams();
        int x = Math.round(SAFE * scale), y = Math.round(fieldY(cp) * scale);
        int w = Math.round((SIZE - SAFE * 2) * scale), h = Math.round(fieldH(cp) * scale);
        if (lp.leftMargin != x || lp.topMargin != y || lp.width != w || lp.height != h) {
            lp.leftMargin = x; lp.topMargin = y; lp.width = w; lp.height = h;
            field.setLayoutParams(lp);
        }
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float cp = compactNow();
        boolean again = anim != 0 || Math.abs(cp - compact) > 0.001f;
        placeField(cp);
        c.save();
        c.scale(scale, scale);
        int el = 0;

        // PivotHeader：小字 + 大标题（键盘出来时大标题淡出、输入框顶上去）
        int ink = grid.tok("ink");
        if (begin(c, el++, now, 8f + LABEL_Y)) {
            label.setColor(ink);
            label.setAlpha(Math.round(255 * (1f - cp)));
            c.drawText("TESSERA", SAFE + 2f, TileGridView.baseline(label, LABEL_Y, 14f), label);
            c.restore();
        }
        if (begin(c, el++, now, 50f)) {
            title.setColor(ink);
            title.setAlpha(Math.round(255 * (1f - cp)));
            if (cp < 0.99f) c.drawText("搜索", SAFE, TileGridView.baseline(title, 34f - 12f * cp, 32f), title);
            title.setAlpha(255);
            c.restore();
        }
        // 输入框：静止时是真的 EditText；翻的时候画一个一样的
        float fy = fieldY(cp);
        if (field.getVisibility() != VISIBLE && begin(c, el, now, fy + fieldH(cp) / 2f)) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(grid.tok("surface-2"));
            r.set(SAFE, fy, SIZE - SAFE, fy + fieldH(cp));
            c.drawRect(r, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(STROKE);
            fill.setColor(grid.tok("line-strong"));
            r.inset(STROKE / 2f, STROKE / 2f);
            c.drawRect(r, fill);
            fill.setStyle(Paint.Style.FILL);
            boolean empty = query.isEmpty();
            hint.setColor(empty ? grid.tok("ink-subtle") : ink);
            c.drawText(empty ? "应用名或拼音" : query, SAFE + 8f, TileGridView.baseline(hint, fy + (fieldH(cp) - 20f) / 2f, 20f), hint);
            c.restore();
        }
        el++;

        // 结果
        float top = listY(cp), bottom = listBottom();
        c.save();
        c.clipRect(0, top, SIZE, bottom);
        float y = top - pos;
        boolean empty = query.trim().isEmpty();
        if (empty && headH(cp) > 0.5f) {
            small.setAlpha(Math.round(255 * (1f - cp)));
            if (begin(c, el++, now, y + 11f)) {
                small.setColor(grid.tok("ink-muted"));
                c.drawText("全部应用", SAFE, TileGridView.baseline(small, y + 4f, 14f), small);
                c.restore();
            }
            small.setAlpha(255);
            y += headH(cp);
        }
        if (shown.isEmpty()) {
            if (begin(c, el, now, y + rowH(cp) / 2f)) {
                hint.setColor(grid.tok("ink-muted"));
                c.drawText("没有找到“" + query.trim() + "”", SAFE, TileGridView.baseline(hint, y + (rowH(cp) - 20f) / 2f, 20f), hint);
                c.restore();
            }
        }
        for (int i = 0; i < shown.size(); i++, y += rowH(cp)) {
            if (y + rowH(cp) < top - 4 || y > bottom + 4) { el++; continue; }
            if (begin(c, el++, now, y + rowH(cp) / 2f, i)) {
                drawRow(c, shown.get(i), y, i == 0 && !empty, i == hit && lock == 0, cp);
                c.restore();
            }
        }
        c.restore();
        c.restore();
        if (again) postInvalidateOnAnimation();
    }

    /** 一行：32 方块图标（磁贴的颜色 + 图标）+ 17/22 名字；排第一的高亮（回车打开的就是它）。 */
    private void drawRow(Canvas c, Entry e, float y, boolean top, boolean pressed, float cp) {
        final float ROW = rowH(cp), ICON = iconS(cp);
        item.setTextSize(17f - 2f * cp);
        if (top || pressed) {
            fill.setColor(grid.tok(pressed ? "surface" : "surface-2"));
            c.drawRect(0, y, SIZE, y + ROW, fill);
        }
        float ix = SAFE, iy = y + (ROW - ICON) / 2f;
        fill.setColor(e.app.bg);
        c.drawRect(ix, iy, ix + ICON, iy + ICON, fill);
        if (e.app.icon != null) {
            float iw = ICON * 0.62f;
            r.set(ix + (ICON - iw) / 2f, iy + (ICON - iw) / 2f, ix + (ICON + iw) / 2f, iy + (ICON + iw) / 2f);
            c.drawBitmap(e.app.icon, null, r, bmp);
        } else {
            float gs = ICON * 0.62f;
            grid.glyph(c, e.app.glyph.isEmpty() ? "more" : e.app.glyph, ix + (ICON - gs) / 2f, iy + (ICON - gs) / 2f, gs, e.app.fg);
        }
        item.setColor(grid.tok("ink"));
        float tx = ix + ICON + 8f, w = SIZE - SAFE - tx;
        c.drawText(TextUtils.ellipsize(e.app.name, item, w, TextUtils.TruncateAt.END).toString(), tx,
                TileGridView.baseline(item, y + (ROW - 22f) / 2f, 22f), item);
    }

    /**
     * 第 i 个元素开始画：按当前动画给它套上转门变换（绕屏幕左边缘，透视中心在屏幕正中）。
     * 返回 false 表示这一帧它已经翻走、不用画；返回 true 时调用方画完要 c.restore()。
     */
    private boolean begin(Canvas c, int i, long now, float cy) { return begin(c, i, now, cy, -1); }

    private boolean begin(Canvas c, int i, long now, float cy, int row) {
        float deg = 0f;
        if (anim == 1) {                                  // 进场：从 −80°（右边朝人）转到 0
            float k = prog(now, animT0 + i * TS_STAGGER, TS_IN);
            deg = -80f * (1f - EXPO_OUT.getInterpolation(k));
        } else if (anim == 2) {                           // 返回：0 → −80°（往回翻走）
            float k = prog(now, animT0 + i * TS_STAGGER, TS_OUT);
            if (k >= 1f) return false;
            deg = -80f * ACT_IN.getInterpolation(k);
        } else if (anim == 3) {                           // 打开应用：0 → 90°（往前翻走），被点的那行最后走
            long delay = row == sel ? lastDelay() : i * TS_STAGGER;
            float k = prog(now, animT0 + delay, TS_OUT);
            if (k >= 1f) return false;
            deg = 90f * ACT_IN.getInterpolation(k);
        }
        c.save();
        if (deg != 0f) {
            camera.save();
            camera.setLocation(0, 0, -600f / 72f);
            camera.translate(-SIZE / 2f, 0, 0);
            camera.rotateY(deg);
            camera.translate(SIZE / 2f, 0, 0);
            camera.getMatrix(matrix);
            camera.restore();
            matrix.preTranslate(-SIZE / 2f, -cy);
            matrix.postTranslate(SIZE / 2f, cy);
            c.concat(matrix);
        }
        return true;
    }

    private int elements() {
        int n = 3 + (query.trim().isEmpty() ? 1 : 0);
        float cp = compactNow(), vis = (listBottom() - listY(cp)) / rowH(cp) + 2;
        return n + (int) Math.min(shown.size(), vis);
    }

    private long lastDelay() { return elements() * TS_STAGGER + TS_OUT; }

    private long length(int a) {
        long n = elements();
        if (a == 1) return n * TS_STAGGER + TS_IN;
        if (a == 3) return lastDelay() + TS_OUT;
        return n * TS_STAGGER + TS_OUT;
    }

    private static float prog(long now, long t0, long dur) { return clamp((now - t0) / (dur * TileGridView.slow), 0, 1); }

    // ---------- 触摸：结果列表滚动、点开、右滑返回 ----------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        // 输入框自己处理点按（弹键盘、挪光标）；其余都归这一页
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (anim != 0) return true;
        float x = e.getX() / scale, y = e.getY() / scale;
        long now = e.getEventTime();
        float cp = compactNow(), top = listY(cp);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                motion++;
                downX = x; downY = y; lock = 0; sn = 0; startPos = pos;
                sample(now, y);
                hit = rowAt(y, top, cp);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (lock == 0) {
                    if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return true;
                    lock = Math.abs(dy) >= Math.abs(dx) ? 1 : -1;
                    lockY = downY + (dy > 0 ? SLOP : -SLOP);
                    invalidate();
                }
                if (lock == 1) {
                    sample(now, y);
                    float raw = startPos - (y - lockY), m = maxScroll(cp), d = listBottom() - top;
                    pos = raw < 0 ? -rubber(-raw, d) : raw > m ? m + rubber(raw - m, d) : raw;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (lock == 0 && !cancel && hit >= 0) { int h = hit; hit = -1; launch(h); return true; }
                if (lock == -1 && !cancel && x - downX > EDGE_DX && Math.abs(y - downY) < 40f) { hit = -1; close(); return true; }
                if (lock == 1) settle(cancel ? 0 : -fingerV(now));
                hit = -1;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private int rowAt(float y, float top, float cp) {
        if (y < top || y > listBottom()) return -1;
        float yy = y - top + pos - headH(cp);
        int i = (int) Math.floor(yy / rowH(cp));
        return yy >= 0 && i < shown.size() ? i : -1;
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
        final float[] vv = {v}, spring = {Float.NaN, 0, 0, 0};   // 回弹：edge, x0, v0, t
        final long[] last = {0};
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (me != motion) return;
                long now = SystemClock.uptimeMillis();
                float dt = last[0] == 0 ? 16f : Math.min(40f, now - last[0]);
                last[0] = now;
                float m = maxScroll(compactNow());
                boolean more;
                if (Float.isNaN(spring[0]) && (pos < 0 || pos > m)) {
                    spring[0] = pos < 0 ? 0 : m; spring[1] = pos - spring[0];
                    spring[2] = Math.max(-BOUNCE_V, Math.min(BOUNCE_V, vv[0])); spring[3] = 0;
                }
                if (!Float.isNaN(spring[0])) {
                    spring[3] += dt;
                    float t = spring[3];
                    float xx = (float) ((spring[1] + (spring[2] + SPRING_W * spring[1]) * t) * Math.exp(-SPRING_W * t));
                    more = !(t > 60 && Math.abs(xx) < 0.3f);
                    pos = more ? spring[0] + xx : spring[0];
                } else {
                    pos += vv[0] * dt;
                    vv[0] *= (float) Math.pow(DECEL, dt);
                    more = Math.abs(vv[0]) >= 0.02f || pos < 0 || pos > m;
                }
                invalidate();
                if (more && me == motion) postOnAnimation(this);
            }
        });
    }

    private static float rubber(float x, float d) { return (1 - 1 / (x * 0.55f / d + 1)) * d; }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
