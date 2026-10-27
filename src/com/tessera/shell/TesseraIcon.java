package com.tessera.shell;

import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

/**
 * Tessera 自己的图标（和 bundle.js 的 Icon 一样）：24 网格、2px 线、圆头圆角，单色 currentColor。
 * 路径字符串原样抄自设计稿，这里只是把 SVG path 语法翻译成 android.graphics.Path。
 */
final class TesseraIcon {
    private static final Map<String, String> PATHS = new HashMap<>();
    private static final Map<String, Path> CACHE = new HashMap<>();

    static {
        PATHS.put("message", "M3 4h18v12H8l-5 4z");
        PATHS.put("phone", "M5 3h4l2 5-3 2a11 11 0 0 0 6 6l2-3 5 2v4a2 2 0 0 1-2 2A17 17 0 0 1 3 5a2 2 0 0 1 2-2z");
        PATHS.put("heart", "M12 20s-8-5-8-11a4 4 0 0 1 8-1 4 4 0 0 1 8 1c0 6-8 11-8 11z");
        PATHS.put("calendar", "M3 5h18v16H3z M3 10h18 M8 3v4 M16 3v4");
        PATHS.put("music", "M9 18V5l11-2v13 M9 18a3 3 0 1 1-3-3 3 3 0 0 1 3 3z M20 16a3 3 0 1 1-3-3 3 3 0 0 1 3 3z");
        PATHS.put("weather", "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z M12 1v3 M12 20v3 M1 12h3 M20 12h3 M4.2 4.2l2.1 2.1 M17.7 17.7l2.1 2.1 M4.2 19.8l2.1-2.1 M17.7 6.3l2.1-2.1");
        PATHS.put("mail", "M2 5h20v14H2z M2 5l10 8 10-8");
        PATHS.put("settings", "M4 6h16 M4 12h16 M4 18h16 M8 4v4 M15 10v4 M10 16v4");
        PATHS.put("timer", "M12 8v5l3 2 M9 2h6 M12 4a9 9 0 1 0 0 18 9 9 0 0 0 0-18z");
        PATHS.put("activity", "M2 12h5l3-7 4 14 3-7h5");
        PATHS.put("map", "M12 22s7-7 7-12a7 7 0 0 0-14 0c0 5 7 12 7 12z M12 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6z");
        PATHS.put("bell", "M6 17v-6a6 6 0 0 1 12 0v6l2 2H4z M10 22h4");
        PATHS.put("people", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21a7 7 0 0 1 14 0 M16 3a4 4 0 0 1 0 8 M22 21a7 7 0 0 0-4-6.3");
        PATHS.put("play", "M7 4l13 8-13 8z");
        PATHS.put("pause", "M7 4h3v16H7z M14 4h3v16h-3z");
        PATHS.put("next", "M5 4l10 8-10 8z M19 4v16");
        PATHS.put("prev", "M19 4L9 12l10 8z M5 4v16");
        PATHS.put("check", "M4 12l5 5L20 6");
        PATHS.put("close", "M5 5l14 14 M19 5L5 19");
        PATHS.put("add", "M12 4v16 M4 12h16");
        PATHS.put("back", "M20 12H5 M11 5l-7 7 7 7");
        PATHS.put("reply", "M10 5l-7 7 7 7 M3 12h11a7 7 0 0 1 7 7v1");
        PATHS.put("more", "M5 12h1 M11.5 12h1 M18 12h1");
        PATHS.put("mic", "M9 3h6v11H9z M5 11a7 7 0 0 0 14 0 M12 18v4");
        PATHS.put("flame", "M12 22a7 7 0 0 0 7-7c0-4-3-6-4-10-2 2-3 4-3 6-1-1-2-2-2-4-3 3-5 5-5 8a7 7 0 0 0 7 7z");
        PATHS.put("lock", "M5 11h14v10H5z M8 11V7a4 4 0 0 1 8 0v4");
        PATHS.put("moon", "M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z");
        // 控制中心（同一套画法：24 格、2px 线、圆头圆角）
        PATHS.put("wifi", "M2 8.5a15 15 0 0 1 20 0 M5 12a10 10 0 0 1 14 0 M8.5 15.5a5 5 0 0 1 7 0 M12 19h.01");
        PATHS.put("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17");
        PATHS.put("torch", "M7 2h10v5l-3 4v11h-4V11L7 7z M7 7h10");
        PATHS.put("volume", "M4 9h4l5-4v14l-5-4H4z M17 9.5a4 4 0 0 1 0 5 M19.5 7a8 8 0 0 1 0 10");
        PATHS.put("battery", "M2 7h17v10H2z M22 10v4");
        PATHS.put("vibrate", "M8 3h8v18H8z M4 8v8 M20 8v8");
        PATHS.put("search", "M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z M15.5 15.5L21 21");
        PATHS.put("sun","M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4");
    }

    private TesseraIcon() {}

    /** 24×24 坐标里的路径；未知名字用 more（与组件一致）。 */
    static synchronized Path get(String name) {
        String key = PATHS.containsKey(name) ? name : "more";
        Path p = CACHE.get(key);
        if (p == null) {
            p = parse(PATHS.get(key));
            CACHE.put(key, p);
        }
        return p;
    }

    // ---------- 极简 SVG path 解析：M L H V C S A Z（含小写相对坐标），够这套图标用 ----------

    private static Path parse(String d) {
        Path path = new Path();
        Tokens t = new Tokens(d);
        float x = 0, y = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;
        char cmd = 'M';
        while (t.more()) {
            if (t.isCommand()) cmd = t.command();
            boolean rel = Character.isLowerCase(cmd);
            char c = Character.toUpperCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (c) {
                case 'M':
                    x = ox + t.num(); y = oy + t.num();
                    path.moveTo(x, y); sx = x; sy = y; lcx = x; lcy = y;
                    cmd = rel ? 'l' : 'L';     // 后续坐标按 lineto
                    break;
                case 'L':
                    x = ox + t.num(); y = oy + t.num();
                    path.lineTo(x, y); lcx = x; lcy = y;
                    break;
                case 'H':
                    x = ox + t.num(); path.lineTo(x, y); lcx = x; lcy = y;
                    break;
                case 'V':
                    y = oy + t.num(); path.lineTo(x, y); lcx = x; lcy = y;
                    break;
                case 'C': {
                    float x1 = ox + t.num(), y1 = oy + t.num(), x2 = ox + t.num(), y2 = oy + t.num();
                    x = ox + t.num(); y = oy + t.num();
                    path.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2;
                    break;
                }
                case 'S': {
                    float x1 = 2 * x - lcx, y1 = 2 * y - lcy, x2 = ox + t.num(), y2 = oy + t.num();
                    x = ox + t.num(); y = oy + t.num();
                    path.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2;
                    break;
                }
                case 'A': {
                    float rx = t.num(), ry = t.num(); t.num();           // 旋转角：这套图标都是 0
                    boolean large = t.flag(), sweep = t.flag();
                    float nx = ox + t.num(), ny = oy + t.num();
                    arc(path, x, y, nx, ny, rx, ry, large, sweep);
                    x = nx; y = ny; lcx = x; lcy = y;
                    break;
                }
                case 'Z':
                    path.close(); x = sx; y = sy; lcx = x; lcy = y;
                    break;
                default:
                    return path;
            }
        }
        return path;
    }

    /** SVG 端点式圆弧 → 圆心式（这套图标的 rx == ry、无旋转）。 */
    private static void arc(Path path, float x0, float y0, float x1, float y1, float rx, float ry,
                            boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) { path.lineTo(x1, y1); return; }
        double r = Math.abs(rx);
        double dx = (x0 - x1) / 2.0, dy = (y0 - y1) / 2.0;
        double d2 = dx * dx + dy * dy;
        if (d2 > r * r) r = Math.sqrt(d2);                       // 半径不够时按规范放大
        double k = Math.sqrt(Math.max(0, (r * r - d2) / d2));
        if (large == sweep) k = -k;
        double cx = (x0 + x1) / 2.0 + k * dy, cy = (y0 + y1) / 2.0 - k * dx;
        double a0 = Math.toDegrees(Math.atan2(y0 - cy, x0 - cx));
        double a1 = Math.toDegrees(Math.atan2(y1 - cy, x1 - cx));
        double sweepDeg = a1 - a0;
        if (sweep && sweepDeg < 0) sweepDeg += 360;
        if (!sweep && sweepDeg > 0) sweepDeg -= 360;
        RectF oval = new RectF((float) (cx - r), (float) (cy - r), (float) (cx + r), (float) (cy + r));
        path.arcTo(oval, (float) a0, (float) sweepDeg, false);
    }

    private static final class Tokens {
        private final String s;
        private int i;

        Tokens(String s) { this.s = s; }

        boolean more() { skip(); return i < s.length(); }

        boolean isCommand() { skip(); return i < s.length() && Character.isLetter(s.charAt(i)); }

        char command() { skip(); return s.charAt(i++); }

        boolean flag() { skip(); return s.charAt(i++) == '1'; }

        float num() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (Character.isDigit(ch)) { i++; continue; }
                if (ch == '.' && !dot) { dot = true; i++; continue; }
                break;
            }
            return Float.parseFloat(s.substring(st, i));
        }

        private void skip() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',')) i++;
        }
    }
}
