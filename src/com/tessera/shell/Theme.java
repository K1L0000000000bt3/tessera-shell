package com.tessera.shell;

/**
 * 设计稿 tokens.css 的颜色（深色 / 浅色两套）+ shell.js 的五个主题色；原生界面全部从这里取色。
 */
final class Theme {
    /** id, 名字, 深色[主题色, 主题色字], 浅色[主题色, 主题色字] —— 同 shell.js 的 ACCENTS。 */
    static final String[][] ACCENTS = {
            {"slate", "灰蓝", "#516b85", "#9db6cf", "#48617a", "#3d5670"},
            {"teal", "青灰", "#4f7377", "#9cc3c5", "#45666a", "#36585b"},
            {"mauve", "灰紫", "#625d82", "#b3add6", "#565177", "#48436a"},
            {"sage", "灰绿", "#57705e", "#a6c3ad", "#4c6453", "#3d5545"},
            {"sand", "暖灰", "#7a6a5c", "#cdb9a6", "#6b5c4f", "#594b3f"},
    };

    final boolean dark;
    final String accentId;
    final int bg, surface, surface2, line, lineStrong, ink, inkMuted, inkSubtle;
    final int accent, onAccent, accentInk, tileBase, tileSoft, onTile, danger, faceBg;

    Theme(boolean dark, String accentId) {
        this.dark = dark;
        String[] a = ACCENTS[0];
        for (String[] x : ACCENTS) if (x[0].equals(accentId)) a = x;
        this.accentId = a[0];
        if (dark) {
            // bg 用纯黑（用户 2026-10-25：开始屏、搜索界面要纯黑；设计稿原值 #0a0d0f 近黑）
            bg = 0xFF000000; surface = 0xFF181C20; surface2 = 0xFF262B31; line = 0xFF2C333A; lineStrong = 0xFF7D8895;
            ink = 0xFFEEF2F5; inkMuted = 0xFFA3ADB8; inkSubtle = 0xFF8893A0; tileBase = 0xFF3E4956; tileSoft = 0xFF2C333A;
        } else {
            bg = 0xFFF6F7F9; surface = 0xFFEAEDF0; surface2 = 0xFFDCE0E5; line = 0xFFD5DBE2; lineStrong = 0xFF6A7684;
            ink = 0xFF161B21; inkMuted = 0xFF505B67; inkSubtle = 0xFF5F6B78; tileBase = 0xFFB2BCC7; tileSoft = 0xFFD5DBE2;
        }
        accent = TileGridView.parseCss(dark ? a[2] : a[4], 0xFF516B85);
        accentInk = TileGridView.parseCss(dark ? a[3] : a[5], 0xFF9DB6CF);
        onAccent = 0xFFFFFFFF;
        onTile = ink;
        danger = 0xFFB5473C;
        faceBg = 0xFF000000;
    }

    String accentName() {
        for (String[] x : ACCENTS) if (x[0].equals(accentId)) return x[1];
        return ACCENTS[0][1];
    }

    /** 设计稿 TONES：磁贴 / 列表图标块的底色和前景色。 */
    int toneBg(String tone) {
        if ("accent".equals(tone)) return accent;
        if ("soft".equals(tone)) return tileSoft;
        if ("danger".equals(tone)) return danger;
        return tileBase;
    }

    int toneFg(String tone) { return "accent".equals(tone) || "danger".equals(tone) ? onAccent : onTile; }

    static String hex(int c) { return String.format("#%06x", c & 0xFFFFFF); }

    /** 给 TileGridView / SearchPage 用的 tok 表（和 shell.js 推过去的格式一样）。 */
    org.json.JSONObject tok() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("ink", hex(ink)); o.put("ink-muted", hex(inkMuted)); o.put("ink-subtle", hex(inkSubtle));
            o.put("surface", hex(surface)); o.put("surface-2", hex(surface2)); o.put("line-strong", hex(lineStrong));
            o.put("accent-ink", hex(accentInk)); o.put("on-accent", hex(onAccent));
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }
}
