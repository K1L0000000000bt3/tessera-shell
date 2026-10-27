package com.tessera.shell;

import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;

/**
 * 原生渲染用的 Tessera 字体：和 CSS 的 font-family "Inter", "Noto Sans SC" 一样，
 * 拉丁文与数字用 Inter，没有的字形（中文）回落到 Noto Sans SC。字重只有 300 / 400 / 600。
 * 字体文件在 assets/fonts/ 里不压缩存放，Font.Builder 直接映射，不占应用堆内存。
 */
final class Fonts {
    private static final int[] WEIGHTS = {300, 400, 600};
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();
    private static FontFamily inter, noto;

    private Fonts() {}

    /** weight 取 300 / 400 / 600 中最接近的一个。 */
    static synchronized Typeface sans(AssetManager assets, int weight) {
        int w = nearest(weight);
        Typeface cached = CACHE.get(w);
        if (cached != null) return cached;
        Typeface tf;
        try {
            if (inter == null) inter = family(assets, "Inter");
            if (noto == null) noto = family(assets, "NotoSansSC");
            tf = new Typeface.CustomFallbackBuilder(inter)
                    .addCustomFallback(noto)
                    .setStyle(new FontStyle(w, FontStyle.FONT_SLANT_UPRIGHT))
                    .setSystemFallback("sans-serif")
                    .build();
        } catch (IOException e) {
            Log.w("Tessera", "font load failed, using system sans", e);
            tf = Typeface.create(Typeface.SANS_SERIF, w, false);
        }
        CACHE.put(w, tf);
        return tf;
    }

    private static float[] primaryMetrics;

    /**
     * 主字体（Inter）每 1px 字号的 {ascent, descent}（都是正数）。CSS 算行盒基线只看第一个字体的上下高度，
     * 中文回落到 Noto 也不改；而组合字体的 getFontMetrics 会把 Noto 算进去，基线就低了 1 个设计像素左右。
     */
    static synchronized float[] primaryMetrics(AssetManager assets) {
        if (primaryMetrics != null) return primaryMetrics;
        float a = 0.96875f, d = 0.2412f;                  // Inter 的 hhea（2048 em：1984 / 494），读不到字体时用
        try {
            if (inter == null) inter = family(assets, "Inter");
            Typeface only = new Typeface.CustomFallbackBuilder(inter).build();
            android.graphics.Paint p = new android.graphics.Paint();
            p.setTypeface(only);
            p.setTextSize(100f);
            android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
            a = -fm.ascent / 100f;
            d = fm.descent / 100f;
        } catch (IOException e) {
            Log.w("Tessera", "Inter metrics fallback", e);
        }
        primaryMetrics = new float[] {a, d};
        return primaryMetrics;
    }

    private static FontFamily family(AssetManager assets, String name) throws IOException {
        FontFamily.Builder b = null;
        for (int w : WEIGHTS) {
            Font f = new Font.Builder(assets, "fonts/" + name + "-" + w + ".ttf").setWeight(w).build();
            if (b == null) b = new FontFamily.Builder(f); else b.addFont(f);
        }
        return b.build();
    }

    private static int nearest(int weight) {
        int best = WEIGHTS[0];
        for (int w : WEIGHTS) if (Math.abs(w - weight) < Math.abs(best - weight)) best = w;
        return best;
    }
}
