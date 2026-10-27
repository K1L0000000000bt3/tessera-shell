package com.tessera.shell;

import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 设计稿（2026-10-23 的三份：B 大字 · 默认、C 数据 · 长按换表盘、C 数据 · 拖入卡片与即时活动）里的新表盘，
 * 只负责"画"，不管动画状态：动画进度由 FaceView 算好，放在 DataFrame 里传进来。
 * 坐标全是 200×200 的设计像素；数值照抄设计稿的 inline style。
 */
final class FaceStyles {
    /** 表盘样式：前四个和设计稿画廊里的顺序一致，拼板（用户自己的图片表盘）排在最后。 */
    static final int SECONDS = 0, TILES = 1, DATA = 2, BIG = 3, PANELS = 4;
    static final String[] NAMES = {"秒轨", "磁贴", "数据", "大字", "拼板"};
    static final int COUNT = 5;

    private static final float SIZE = 200f;
    static final int FACE_INK = 0xFFF2F4F6, FACE_MUTED = 0xFF9AA3AD, LINE_STRONG = 0xFF7D8895;
    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    /** 表盘卡片的内容。end = 放在左边方卡时数值和小字靠右（让开屏幕左下角的圆角）。 */
    static final class Card {
        final String id, icon, name, value, unit, sub;
        final boolean end;

        Card(String id, String icon, String name, String value, String unit, String sub, boolean end) {
            this.id = id; this.icon = icon; this.name = name; this.value = value;
            this.unit = unit == null ? "" : unit; this.sub = sub == null ? "" : sub; this.end = end;
        }
    }

    /** 即时活动（叠在卡片区上的主题色卡）：标题行 + 大数值 + 右侧最多两行小字。 */
    static final class Live {
        final String key, icon, name, value;
        final List<String> lines;

        Live(String key, String icon, String name, String value, List<String> lines) {
            this.key = key; this.icon = icon; this.name = name; this.value = value; this.lines = lines;
        }

        boolean sameAs(Live o) {
            return o != null && TextUtils.equals(key, o.key) && TextUtils.equals(icon, o.icon) && TextUtils.equals(name, o.name)
                    && TextUtils.equals(value, o.value) && lines.equals(o.lines);
        }
    }

    /** 一张卡片这一帧画在哪儿。 */
    static final class CardDraw {
        Card card;
        float x, y, w, h, alpha = 1f;
        int bg;
        Boolean wide, left;       // 排版按落定后的格子定（null = 按当前大小算），格子在动时内容不跳
    }

    /** 拿起来的卡片 / 磁贴（跟着手指，圆角 + 放大）。asTile = 还是开始屏磁贴的样子（从应用列表拿起来的）。 */
    static final class Ghost {
        Card card;
        float x, y, w, h, radius, scale = 1f, ox, oy, alpha = 1f;
        int bg, fg;
        boolean asTile;
        String tileSize = "s";
        float tileMix;            // 0 = 磁贴样子，1 = 卡片样子（落进卡片区时交叉淡变）
        Boolean wide, left;
        android.graphics.Bitmap tileLook;   // 开始屏上那块磁贴的样子（快照）
    }

    /** 数据表盘这一帧的状态。 */
    static final class DataFrame {
        float morph = 1f;          // 0 = B 布局（大字），1 = C 布局（一行时间 + 卡片）；已经过缓动
        float minuteInk = 1f;      // 分钟的颜色：0 = 主题色字（B），1 = 表盘字色（C）
        boolean pieces;            // true = 画移动中的三块数字；false = 画 C 的真实一行（带冒号）
        float bAlpha;              // B 独有：右上周几 / 日期、右下"秒"
        float cAlpha = 1f;         // C 独有：日期行、冒号
        final List<CardDraw> cards = new ArrayList<>();
        float stackDy, stackScale = 1f, stackAlpha = 1f;
        Live live;
        float liveDx, liveAlpha;
        final RectF placeholder = new RectF();
        float phAlpha;
        Ghost ghost;
    }

    private final TextPaint big, tile, row, value, unitP, ss18, num28, cap, capN, date;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Paint ghostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();

    FaceStyles(AssetManager assets) {
        big = text(assets, 300); tile = text(assets, 300); row = text(assets, 300); value = text(assets, 300); unitP = text(assets, 300);
        ss18 = text(assets, 400); num28 = text(assets, 300); cap = text(assets, 600); date = text(assets, 600);
        cap.setFontFeatureSettings(null); date.setFontFeatureSettings(null);   // 小字（标题、单位、日期）设计稿没开等宽数字
        big.setTextSize(86f);
        tile.setTextSize(84f); tile.setLetterSpacing(-0.02f);
        row.setTextSize(56f); row.setLetterSpacing(-0.02f);
        value.setLetterSpacing(-0.01f);
        unitP.setLetterSpacing(-0.01f);
        ss18.setTextSize(18f);
        num28.setTextSize(28f);
        cap.setTextSize(11f);
        capN = text(assets, 600); capN.setTextSize(11f);  // 磁贴右下角的秒：小字但开等宽数字
        date.setTextSize(13f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(2f);
        dash.setPathEffect(new DashPathEffect(new float[] {6f, 4f}, 0f));
        dash.setColor(LINE_STRONG);
    }

    private static TextPaint text(AssetManager assets, int weight) {
        // LINEAR_TEXT：字宽不取整。这里按设计像素的字号排（56 号），画布再放大 2.4 倍；不开的话安卓把每个字宽取整，
        // 数字 36.23 变 36、冒号 15.05 变 15，越往右偏得越多（和设计稿比冒号之后整体左移 1~2 像素）
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        p.setTypeface(Fonts.sans(assets, weight));
        p.setFontFeatureSettings("'tnum'");               // font-variant-numeric: tabular-nums（CSS 写法，要带引号，不带会被忽略）
        return p;
    }

    // ---------- 大字（B） ----------

    /** 大字表盘就是数据表盘没有卡片时的样子（设计稿 Stacked.dc.html）。 */
    void drawBig(Canvas c, Calendar now, Theme th) {
        DataFrame f = new DataFrame();
        f.morph = 0f; f.minuteInk = 0f; f.pieces = true; f.bAlpha = 1f; f.cAlpha = 0f;
        drawData(c, now, th, f);
    }

    // ---------- 磁贴 ----------

    /** 2×2 四块磁贴，各放一位数字（Data.dc.html 的"磁贴"）。 */
    void drawTiles(Canvas c, Calendar now, Theme th) {
        String hh = pad(now.get(Calendar.HOUR_OF_DAY)), mm = pad(now.get(Calendar.MINUTE));
        String[] d = {hh.substring(0, 1), hh.substring(1), mm.substring(0, 1), mm.substring(1)};
        int[] bg = {th.tileBase, th.tileBase, th.tileSoft, th.accent};
        int[] fg = {th.onTile, th.onTile, th.onTile, th.onAccent};
        c.drawColor(0xFF000000);
        for (int i = 0; i < 4; i++) {
            float x = (i % 2) * 102f, y = (i / 2) * 102f;
            fill.setColor(bg[i]);
            c.drawRect(x, y, x + 98f, y + 98f, fill);
            tile.setColor(fg[i]);
            float w = advance(tile, d[i]);
            drawCss(c, d[i], x + (98f - w) / 2f, TileGridView.baseline(tile, y + (98f - 84f) / 2f, 84f), tile);
        }
        cap.setColor(th.onTile);
        String wd = WEEK[now.get(Calendar.DAY_OF_WEEK) - 1] + " " + now.get(Calendar.DAY_OF_MONTH);
        drawCss(c, wd, 102f + 8f, TileGridView.baseline(cap, 98f - 6f - 14f, 14f), cap);
        capN.setColor(th.onAccent);
        drawCss(c, pad(now.get(Calendar.SECOND)), 102f + 8f, TileGridView.baseline(capN, 102f + 6f, 14f), capN);
    }

    // ---------- 数据（C）/ 大字（B）之间可变形 ----------

    void drawData(Canvas c, Calendar now, Theme th, DataFrame f) {
        c.drawColor(0xFF000000);
        String hh = pad(now.get(Calendar.HOUR_OF_DAY)), mm = pad(now.get(Calendar.MINUTE)), ss = pad(now.get(Calendar.SECOND));
        String week = WEEK[now.get(Calendar.DAY_OF_WEEK) - 1];

        if (f.pieces) {
            // 三块数字从 B 的位置移动、缩放到 C 一行里各自的字形上（设计稿 hT / mT / sT）
            float m = f.morph, sC = 56f / 86f, sS = 18f / 28f;
            big.setLetterSpacing(lerp(-0.03f, -0.02f, m));
            big.setColor(FACE_INK);
            drawScaled(c, hh, lerp(16f, 11.6f, m), lerp(22f, 21.72f, m), lerp(1f, sC, m), big, 74f);
            big.setColor(lerpColor(th.accentInk, FACE_INK, f.minuteInk));
            drawScaled(c, mm, lerp(16f, 95.74f, m), lerp(100f, 21.72f, m), lerp(1f, sC, m), big, 74f);
            num28.setColor(th.accentInk);
            drawScaled(c, ss, lerp(147.77f, 167.96f, m), lerp(140f, 49.29f, m), lerp(1f, sS, m), num28, 32f);
            if (f.cAlpha > 0f) {                           // 冒号：C 那一行里冒号所在的 14px 窄窗
                c.save();
                c.clipRect(81.82f, 18f, 95.82f, 74f);
                drawRow(c, hh + ":" + mm, null, th, f.cAlpha);
                c.restore();
            }
        } else {
            drawRow(c, hh + ":" + mm, ss, th, 1f);
        }

        if (f.bAlpha > 0f) {                               // B：右上周几 / 日期，右下"秒"
            int a = Math.round(f.bAlpha * 255);
            cap.setColor(withAlpha(FACE_MUTED, a));
            drawRight(c, week, SIZE - 16f, TileGridView.baseline(cap, 26f, 14f), cap);
            num28.setColor(withAlpha(FACE_INK, a));
            drawRight(c, String.valueOf(now.get(Calendar.DAY_OF_MONTH)), SIZE - 16f, TileGridView.baseline(num28, 40f, 32f), num28);
            drawRight(c, "秒", SIZE - 16f, TileGridView.baseline(cap, 126f, 14f), cap);
        }
        if (f.cAlpha > 0f) {                               // C：日期行
            date.setColor(withAlpha(FACE_MUTED, Math.round(f.cAlpha * 255)));
            String dl = (now.get(Calendar.MONTH) + 1) + "月" + now.get(Calendar.DAY_OF_MONTH) + "日" + week;
            drawCss(c, dl, (SIZE - advance(date, dl)) / 2f, TileGridView.baseline(date, 76f, 18f), date);
        }

        // 卡片区：即时活动压在上面时整摞往后退（transform-origin 50% 102px）
        if (!f.cards.isEmpty() || f.phAlpha > 0f) {
            c.save();
            c.translate(0, f.stackDy);
            c.scale(f.stackScale, f.stackScale, SIZE / 2f, 102f);
            if (f.phAlpha > 0f) {                          // 虚线：卡片会落在哪儿（2px dashed line-strong，box-sizing border-box）
                dash.setAlpha(Math.round(f.phAlpha * f.stackAlpha * 255));
                r.set(f.placeholder.left + 1f, f.placeholder.top + 1f, f.placeholder.right - 1f, f.placeholder.bottom - 1f);
                c.drawRect(r, dash);
            }
            for (CardDraw d : f.cards) {
                if (d.alpha <= 0f) continue;
                boolean wide = d.wide != null ? d.wide : d.w > 150f;
                boolean left = d.left != null ? d.left : !wide && d.x < 100f;
                drawCard(c, d.card, d.x, d.y, d.w, d.h, d.bg, th.onTile, d.alpha * f.stackAlpha, wide, left);
            }
            c.restore();
        }
        if (f.live != null && f.liveAlpha > 0f) drawLive(c, f.live, f.liveDx, th, f.liveAlpha);
        if (f.ghost != null && f.ghost.alpha > 0f) drawGhost(c, f.ghost, th);
    }

    /** C 的一行：56px 时间 + 2px + 18px 秒（基线对齐），左 11.6、行盒顶 18。ss = null 时不画秒。 */
    private void drawRow(Canvas c, String hhmm, String ss, Theme th, float alpha) {
        int a = Math.round(alpha * 255);
        row.setColor(withAlpha(FACE_INK, a));
        float base = TileGridView.baseline(row, 18f, 56f);
        drawCss(c, hhmm, 11.6f, base, row);
        if (ss != null) {
            ss18.setColor(withAlpha(0xFF000000 | th.accentInk, a));
            drawCss(c, ss, 11.6f + advance(row, hhmm) + 2f, base, ss18);
        }
    }

    /** 一张卡片（Data.dc.html / FaceComplications.dc.html 的 c.*）。 */
    void drawCard(Canvas c, Card card, float x, float y, float w, float h, int bg, int fg, float alpha) {
        boolean wide = w > 150f;
        drawCard(c, card, x, y, w, h, bg, fg, alpha, wide, !wide && x < 100f);
    }

    /** wide = 宽卡排版；left = 左边方卡（带 end 的数值和小字靠右，让开屏幕左下角的圆角）。 */
    void drawCard(Canvas c, Card card, float x, float y, float w, float h, int bg, int fg, float alpha, boolean wide, boolean leftSq) {
        int a = Math.round(alpha * 255);
        c.save();
        c.clipRect(x, y, x + w, y + h);
        fill.setColor(withAlpha(bg, a));
        c.drawRect(x, y, x + w, y + h, fill);
        float padL = wide ? 16f : 12f;
        header(c, card.icon, card.name, x + padL, y + 10f, withAlpha(fg, a));
        int len = card.value.length();
        float vSize = wide ? 40f : (len >= 5 ? 26f : len == 4 ? 30f : 40f);
        value.setTextSize(vSize); value.setColor(withAlpha(fg, a));
        unitP.setTextSize(vSize / 2f); unitP.setColor(withAlpha(fg, a));
        cap.setColor(withAlpha(fg, a));
        float vw = advance(value, card.value) + (card.unit.isEmpty() ? 0f : advance(unitP, card.unit));
        if (wide) {
            float bottom = y + h - 22f;
            float base = TileGridView.baseline(value, bottom - 40f, 40f);
            drawValue(c, card, x + padL, base);
            float subMax = w - padL * 2f - vw - 8f;
            String sub = ellipsize(card.sub, cap, subMax);
            drawRight(c, sub, x + w - padL, TileGridView.baseline(cap, bottom - 14f, 14f), cap);
        } else {
            boolean left = leftSq, endAl = left && card.end;
            float vTop = y + 10f + 14f + 2f, subTop = vTop + 44f + 2f;
            float subMax = left && !endAl ? 58f : 62f;
            String sub = ellipsize(card.sub, cap, subMax);
            float base = TileGridView.baseline(value, vTop, 44f);
            if (endAl) {
                float subR = card.unit.isEmpty() ? Math.round(vSize * 0.075f) : 2f;
                float blockR = x + 12f + Math.max(vw, advance(cap, sub) + subR);
                drawValue(c, card, blockR - vw, base);
                drawRight(c, sub, blockR - subR, TileGridView.baseline(cap, subTop, 14f), cap);
            } else {
                drawValue(c, card, x + 12f, base);
                drawCss(c, sub, x + 12f + (left ? 16f : 0f), TileGridView.baseline(cap, subTop, 14f), cap);
            }
        }
        c.restore();
    }

    private void drawValue(Canvas c, Card card, float x, float base) {
        drawCss(c, card.value, x, base, value);
        if (!card.unit.isEmpty()) drawCss(c, card.unit, x + advance(value, card.value), base, unitP);
    }

    /** 即时活动卡：主题色，占满卡片区 (0, 102, 200, 98)，内边距 10 16 22。 */
    private void drawLive(Canvas c, Live l, float dx, Theme th, float alpha) {
        int a = Math.round(alpha * 255);
        float x = dx, y = 102f, w = SIZE, h = 98f;
        c.save();
        c.clipRect(x, y, x + w, y + h);
        fill.setColor(withAlpha(th.accent, a));
        c.drawRect(x, y, x + w, y + h, fill);
        header(c, l.icon, l.name, x + 16f, y + 10f, withAlpha(th.onAccent, a));
        float bottom = y + h - 22f;
        value.setTextSize(40f); value.setColor(withAlpha(th.onAccent, a));
        cap.setColor(withAlpha(th.onAccent, a));
        int n = l.lines == null ? 0 : Math.min(2, l.lines.size());
        float linesW = 0f;
        for (int i = 0; i < n; i++) linesW = Math.max(linesW, advance(cap, l.lines.get(i)));
        linesW = Math.min(linesW, 72f);
        for (int i = 0; i < n; i++) {
            String s = ellipsize(l.lines.get(i), cap, 72f);
            drawRight(c, s, x + w - 16f, TileGridView.baseline(cap, bottom - (n - i) * 14f, 14f), cap);
        }
        String v = ellipsize(l.value, value, w - 32f - (n > 0 ? linesW + 8f : 0f));
        drawCss(c, v, x + 16f, TileGridView.baseline(value, bottom - 40f, 40f), value);
        c.restore();
    }

    /** 标题行：14px 图标 + 4px + 名字（11/14 600）。 */
    private void header(Canvas c, String icon, String name, float x, float top, int color) {
        float tx = x;
        Path p = icon == null ? null : TesseraIcon.get(icon);
        if (p != null) {
            glyph(c, p, x, top, 14f, color);
            tx = x + 14f + 4f;
        }
        cap.setColor(color);
        drawCss(c, name, tx, TileGridView.baseline(cap, top, 14f), cap);
    }

    /** 拿起的卡片：圆角、绕手指位置放大；从应用列表拿起的先是磁贴的样子，落进卡片区时换成卡片。 */
    void drawGhost(Canvas c, Ghost g, Theme th) {
        c.save();
        c.scale(g.scale, g.scale, g.x + g.ox, g.y + g.oy);
        r.set(g.x, g.y, g.x + g.w, g.y + g.h);
        clip.reset();
        clip.addRoundRect(r, g.radius, g.radius, Path.Direction.CW);
        c.clipPath(clip);
        float cardA = g.asTile ? g.tileMix : 1f;
        if (cardA < 1f && g.tileLook != null) {           // 磁贴样子：直接用开始屏上那块的快照（底色跟着过渡）
            fill.setColor(withAlpha(g.bg, Math.round(g.alpha * 255)));
            c.drawRect(r, fill);
            ghostPaint.setAlpha(Math.round(g.alpha * (1f - cardA) * 255));
            c.drawBitmap(g.tileLook, null, r, ghostPaint);
        } else if (cardA < 1f) {                           // 磁贴样子：底色 + 居中图标（小卡 22，其余 30）+ 左下名字
            int a = Math.round(g.alpha * (1f - cardA) * 255);
            fill.setColor(withAlpha(g.bg, Math.round(g.alpha * 255)));
            c.drawRect(r, fill);
            float gs = "s".equals(g.tileSize) ? 22f : 30f;
            Path p = TesseraIcon.get(g.card.icon);
            if (p != null) glyph(c, p, g.x + (g.w - gs) / 2f, g.y + (g.h - gs) / 2f, gs, withAlpha(g.fg, a));
            if (!"s".equals(g.tileSize)) {
                cap.setColor(withAlpha(g.fg, a));
                drawCss(c, g.card.name, g.x + 8f, TileGridView.baseline(cap, g.y + g.h - 6f - 14f, 14f), cap);
            }
        }
        if (cardA > 0f) {
            boolean wide = g.wide != null ? g.wide : g.w > 150f;
            boolean left = g.left != null ? g.left : !wide && g.x < 100f;
            drawCard(c, g.card, g.x, g.y, g.w, g.h, g.bg, g.fg, g.alpha * cardA, wide, left);
        }
        c.restore();
    }

    // ---------- 小工具 ----------

    private void glyph(Canvas c, Path p, float x, float y, float size, int color) {
        c.save();
        c.translate(x, y);
        c.scale(size / 24f, size / 24f);
        stroke.setColor(color);
        stroke.setStrokeWidth(2f);
        c.drawPath(p, stroke);
        c.restore();
    }

    /** 带缩放的一块文字：行盒左上角在 (x, top)，transform-origin 0 0。 */
    private void drawScaled(Canvas c, String s, float x, float top, float scale, TextPaint p, float lineH) {
        c.save();
        c.translate(x, top);
        c.scale(scale, scale);
        drawCss(c, s, 0f, TileGridView.baseline(p, 0f, lineH), p);
        c.restore();
    }

    /**
     * 按 CSS 的位置画字：CSS 的 letter-spacing 加在每个字后面，第一个字从 x 起；
     * 安卓把字距一半加在字前、一半加在字后，第一个字会偏出半个字距，这里补回来。
     */
    private static void drawCss(Canvas c, String s, float x, float base, Paint p) {
        if (s == null || s.isEmpty()) return;
        c.drawText(s, x - p.getLetterSpacing() * p.getTextSize() / 2f, base, p);
    }

    private static void drawRight(Canvas c, String s, float right, float base, Paint p) {
        if (s == null || s.isEmpty()) return;
        drawCss(c, s, right - advance(p, s), base, p);
    }

    /**
     * CSS 里这段字的宽度（每个字后面都带字距，最后一个也算）。不用 measureText：它把结果向上取整（154.36 → 155），
     * 跟在后面的秒、右对齐的字会因此偏出将近 1 设计像素。
     */
    static float advance(Paint p, String s) {
        if (s == null || s.isEmpty()) return 0f;
        int n = s.length();
        return p.getRunAdvance(s, 0, n, 0, n, false, n);
    }

    private static String ellipsize(String s, TextPaint p, float w) {
        if (s == null || s.isEmpty() || w <= 0) return "";
        return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString();
    }

    static String pad(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    static int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, Math.round(((color >>> 24) / 255f) * a))) << 24); }

    static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** 卡片区的格位：1 张 = 一张宽卡，2 张 = 两张方卡（C 的样子）。 */
    static RectF[] slots(int n) {
        if (n == 1) return new RectF[] {new RectF(0f, 102f, 200f, 200f)};
        if (n >= 2) return new RectF[] {new RectF(0f, 102f, 98f, 200f), new RectF(102f, 102f, 200f, 200f)};
        return new RectF[0];
    }
}
