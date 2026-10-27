package com.tessera.shell;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户定的安全距离规则（2026-10-23 横幅时定下、10-24 推广到状态区）：每个元素的笔画离屏幕边缘的直线距离至少 30 个屏幕像素，
 * 四个角按到圆角（半径 28 设计像素，TesseraA11y 的遮罩）的最近距离算。
 * 角上的字（右上角时间、左上角应用名 / Pivot 标题）还要"在两边夹角的中心"：笔画外框离上边和离侧边一样远。
 * 坐标单位由调用方定（设计像素或 dp）：传进来屏宽 viewW、屏高 viewH 和“屏幕像素 / 单位”的 scale；圆角半径按屏宽的 28/200 算（TesseraA11y 的遮罩）。
 */
final class SafeEdge {
    static final float R_FRACTION = 28f / 200f, SAFE_PX = 30f;

    private SafeEdge() {}

    /** 一行字的轮廓采样点（起点 x = 0、基线 y = 0）。按 10 倍字号取再缩回来：小字号的字体微调会让轮廓差一截。 */
    static List<float[]> inkPoints(TextPaint src, String s) {
        TextPaint big = new TextPaint(src);
        big.setTextSize(src.getTextSize() * 10f);
        big.setTextAlign(android.graphics.Paint.Align.LEFT);
        Path p = new Path();
        big.getTextPath(s, 0, s.length(), 0f, 0f, p);
        Matrix m = new Matrix();
        m.setScale(0.1f, 0.1f);
        p.transform(m);
        List<float[]> pts = new ArrayList<>();
        PathMeasure pm = new PathMeasure(p, true);
        float[] pt = new float[2];
        do {
            float len = pm.getLength();
            for (float d = 0; d <= len; d += 0.25f) { pm.getPosTan(d, pt, null); pts.add(new float[] {pt[0], pt[1]}); }
        } while (pm.nextContour());
        return pts;
    }

    /** {minX, minY, maxX, maxY}；没有笔画（空串）时全 0。 */
    static float[] bounds(List<float[]> pts) {
        if (pts.isEmpty()) return new float[4];
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] q : pts) { b[0] = Math.min(b[0], q[0]); b[1] = Math.min(b[1], q[1]); b[2] = Math.max(b[2], q[0]); b[3] = Math.max(b[3], q[1]); }
        return b;
    }

    /** 点到屏幕边缘的最近距离（四角是半径为屏宽 28/200 的圆弧）。 */
    static float edgeDist(float x, float y, float viewW, float viewH) {
        float R = R_FRACTION * viewW;
        float cx = x < R ? R : x > viewW - R ? viewW - R : x;
        float cy = y < R ? R : y > viewH - R ? viewH - R : y;
        if (cx != x && cy != y) return R - (float) Math.hypot(x - cx, y - cy);
        return Math.min(Math.min(x, viewW - x), Math.min(y, viewH - y));
    }

    /**
     * 笔画外框离上边 m、离左边（right = false）或右边（right = true）也是 m：从安全距离起往里挪，
     * 直到所有笔画离屏幕边缘（含圆角）都够 30 个屏幕像素。返回 m。
     */
    static float cornerMargin(List<float[]> pts, float[] b, boolean right, float scale, float viewW, float viewH) {
        float need = SAFE_PX / scale;
        if (pts.isEmpty()) return need;
        float limit = 0.2f * viewW;
        for (float m = need; m < limit; m += 0.25f) {
            float dx = right ? (viewW - m) - b[2] : m - b[0], dy = m - b[1];
            float min = Float.MAX_VALUE;
            for (float[] q : pts) { min = Math.min(min, edgeDist(q[0] + dx, q[1] + dy, viewW, viewH)); if (min < need) break; }
            if (min >= need) return m;
        }
        return limit;
    }

    /** 放在某个上角的一行字：换了字（或屏幕尺寸）才重算位置。 */
    static final class Corner {
        final TextPaint paint;
        final boolean right;
        private String str;
        private float scale = -1f, viewW = -1f, viewH = -1f;
        float x, base, inkL, inkR, inkTop, inkBottom;

        Corner(TextPaint paint, boolean right) { this.paint = paint; this.right = right; }

        void layout(String s, float scale, float viewW, float viewH) {
            if (s.equals(str) && scale == this.scale && viewW == this.viewW && viewH == this.viewH) return;
            str = s; this.scale = scale; this.viewW = viewW; this.viewH = viewH;
            List<float[]> pts = inkPoints(paint, s);
            float[] b = bounds(pts);
            float m = cornerMargin(pts, b, right, scale, viewW, viewH);
            x = right ? (viewW - m) - b[2] : m - b[0];
            base = m - b[1];
            inkL = x + b[0]; inkR = x + b[2]; inkTop = m; inkBottom = base + b[3];
        }

        /** 在设计坐标的画布上画（画笔按左对齐用）。 */
        void draw(Canvas c, int color) {
            if (str == null) return;
            paint.setColor(color);
            c.drawText(str, x, base, paint);
        }
    }
}
