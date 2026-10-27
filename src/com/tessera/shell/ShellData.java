package com.tessera.shell;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 原生桌面的数据：应用、通知、步数、闹钟，以及存下来的开始屏布局 / 外观 / 表盘样式。
 * 磁贴内容和即时活动的规则逐条照搬 shell.js（buildTiles / liveActivity），保证和 WebView 版一致。
 */
final class ShellData {
    static final class App {
        final String id, pkg, cls, label;
        final Bitmap icon;          // 第三方应用的真实图标（自适应图标只取前景层）；有设计稿图标的应用也会有，但不用
        App(String id, String pkg, String cls, String label, Bitmap icon) { this.id = id; this.pkg = pkg; this.cls = cls; this.label = label; this.icon = icon; }
    }

    static final class Notif {
        String key = "", pkg = "", app = "", title = "", text = "", category = "";
        long when;
        boolean ongoing, clearable, media, fgs, content = true;
        int progress = -1;
        final List<String> actions = new ArrayList<>();
    }

    /** 设计稿自己的图标和名字（common.js 的 TS.APPS）。 */
    static final HashMap<String, String[]> META = new HashMap<>();
    static {
        META.put("com.android.messaging", new String[] {"message", "信息"});
        META.put("com.android.dialer", new String[] {"phone", "电话"});
        META.put("com.android.contacts", new String[] {"people", "联系人"});
        META.put("com.ailife.betterhealth", new String[] {"activity", "活动"});
        META.put("com.android.deskclock", new String[] {"timer", "时钟"});
        META.put("org.fossify.calendar", new String[] {"calendar", "日历"});
        META.put("org.lineageos.eleven", new String[] {"music", "音乐"});
        META.put("com.android.settings", new String[] {"settings", "设置"});
        META.put("org.lineageos.recorder", new String[] {"mic", "录音机"});
        META.put("com.android.email", new String[] {"mail", "邮件"});
    }
    /** 不放进开始屏的入口：旧桌面、旧工具和开发工具。 */
    static final List<String> HIDDEN = Arrays.asList("com.ailife.clox", "com.s108.watchclock", "com.android.shell", "org.omnirom.logcat");
    /** 默认顺序、尺寸、明度（shell.js 的 DEFAULTS）。 */
    static final String[][] DEFAULTS = {
            {"com.android.messaging", "m", "accent"}, {"com.android.dialer", "s", "base"}, {"com.android.deskclock", "s", "base"},
            {"org.fossify.calendar", "s", "base"}, {"com.android.contacts", "s", "base"}, {"com.ailife.betterhealth", "w", "base"},
            {"org.lineageos.eleven", "s", "base"}, {"com.android.settings", "s", "base"}, {"org.lineageos.recorder", "s", "soft"},
            {"com.ailife.rotatingcamera", "s", "soft"}, {"org.lineageos.glimpse", "s", "soft"}, {"com.ailife.onyxbrowser2", "s", "soft"},
    };
    static final List<String> LIVE_EXCLUDE = Arrays.asList("org.lineageos.recorder");
    static final int GOAL = 8000;

    private final Context ctx;
    private final Bridge bridge;
    private final SharedPreferences prefs;
    final List<App> apps = new ArrayList<>();
    final List<Notif> notifs = new ArrayList<>();
    long steps;
    private final HashMap<String, Bitmap> iconCache = new HashMap<>();

    // 存下来的状态
    final List<String> order = new ArrayList<>();
    final HashMap<String, String[]> cfg = new HashMap<>();   // id → {size, tone}
    boolean dark = true;
    String accent = "slate";
    int faceIndex;                                            // 0 = 纯表盘，1 = 带活动（秒轨表盘用）
    int faceStyle = FaceStyles.DATA;                          // 选中的表盘（设计稿画廊默认选"数据"）
    final List<String> cardIds = new ArrayList<>();           // 数据表盘上的卡片，最多两张
    int heartRate;                                            // 最近一次心率（0 = 还没测到）
    /** 能放到数据表盘上的卡片：心率、活动、信息、日历（设计稿 APPS 里有数据来源的那几个）。 */
    static final List<String> CARD_IDS = Arrays.asList("heart", "act", "msg", "cal");

    ShellData(Context ctx, Bridge bridge) {
        this.ctx = ctx;
        this.bridge = bridge;
        prefs = ctx.getSharedPreferences("tessera", Context.MODE_PRIVATE);
        loadPrefs();
    }

    Theme theme() { return new Theme(dark, accent); }

    // ---------- 存取 ----------

    private void loadPrefs() {
        try {
            JSONObject l = new JSONObject(prefs.getString("layout", "{}"));
            order.clear();
            JSONArray o = l.optJSONArray("order");
            if (o != null) for (int i = 0; i < o.length(); i++) order.add(o.optString(i));
            cfg.clear();
            JSONObject c = l.optJSONObject("cfg");
            if (c != null) {
                java.util.Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject v = c.optJSONObject(k);
                    if (v != null) cfg.put(k, new String[] {v.optString("size", "s"), v.optString("tone", "soft")});
                }
            }
        } catch (JSONException ignored) {
        }
        try {
            JSONObject k = new JSONObject(prefs.getString("look", "{}"));
            dark = !"light".equals(k.optString("theme", "dark"));
            accent = k.optString("accent", "slate");
        } catch (JSONException ignored) {
        }
        faceIndex = prefs.getInt("face", 0);
        faceStyle = prefs.getInt("faceStyle", FaceStyles.DATA);
        cardIds.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("faceCards", "[\"heart\",\"act\"]"));
            for (int i = 0; i < a.length() && cardIds.size() < 2; i++) if (validCard(a.optString(i))) cardIds.add(a.optString(i));
        } catch (JSONException ignored) {
        }
    }

    void saveFaceStyle() { prefs.edit().putInt("faceStyle", faceStyle).apply(); }

    /** 能放上表盘的卡片 id：固定的四种，或者从开始屏拖过来的应用卡（app:包名）。 */
    static boolean validCard(String id) { return CARD_IDS.contains(id) || (id != null && id.startsWith("app:") && id.length() > 4); }

    /** 开始屏的磁贴拖到表盘上变成哪张卡：信息 / 日历 / 健康（步数）有专门的卡，其它应用是通用应用卡；设置不行。 */
    static String cardIdFor(String pkg, String kind) {
        if (pkg == null || pkg.isEmpty() || "settings".equals(kind)) return null;
        switch (pkg) {
            case "com.android.messaging": return "msg";
            case "org.fossify.calendar": return "cal";
            case "com.ailife.betterhealth": return "act";
            default: return "app:" + pkg;
        }
    }

    void saveCards() { prefs.edit().putString("faceCards", new JSONArray(cardIds).toString()).apply(); }

    void saveLayout() {
        try {
            JSONObject c = new JSONObject();
            for (String k : cfg.keySet()) c.put(k, new JSONObject().put("size", cfg.get(k)[0]).put("tone", cfg.get(k)[1]));
            prefs.edit().putString("layout", new JSONObject().put("order", new JSONArray(order)).put("cfg", c).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    void saveLook() {
        try {
            prefs.edit().putString("look", new JSONObject().put("theme", dark ? "dark" : "light").put("accent", accent).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    void saveFace() { prefs.edit().putInt("face", faceIndex).apply(); }

    /** WebView 版存在 localStorage 里的设置（ts.layout / ts.look / ts.face）导过来，只做一次。 */
    boolean migrated() { return prefs.getBoolean("ls_migrated", false); }

    void importLocalStorage(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = prefs.edit();
            if (o.has("ts.layout")) e.putString("layout", o.getString("ts.layout"));
            if (o.has("ts.look")) e.putString("look", o.getString("ts.look"));
            if (o.has("ts.face")) { try { e.putInt("face", Integer.parseInt(o.getString("ts.face").trim())); } catch (NumberFormatException ignored) { } }
            e.putBoolean("ls_migrated", true).apply();
            loadPrefs();
        } catch (JSONException ignored) {
            prefs.edit().putBoolean("ls_migrated", true).apply();
        }
    }

    // ---------- 刷新 ----------

    void reloadApps() {
        apps.clear();
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        if (la == null) return;
        for (LauncherActivityInfo info : la.getActivityList(null, Process.myUserHandle())) {
            ComponentName c = info.getComponentName();
            if (c.getPackageName().equals(ctx.getPackageName()) || HIDDEN.contains(c.getPackageName())) continue;
            apps.add(new App(c.flattenToShortString(), c.getPackageName(), c.getClassName(), String.valueOf(info.getLabel()), icon(c.getPackageName(), info)));
        }
        initPins();
    }

    private Bitmap icon(String pkg, LauncherActivityInfo info) {
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        Bitmap bmp = null;
        try {
            Drawable d = info.getIcon(android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi);
            // 自适应图标只取前景层：否则会带一块深色圆底，贴在磁贴上像个徽章（同 Bridge.appIcon）
            if (d instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) d).getForeground() != null) d = ((AdaptiveIconDrawable) d).getForeground();
            if (d != null) {
                bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 120, 120);
                d.draw(new Canvas(bmp));
            }
        } catch (Throwable ignored) {
        }
        iconCache.put(pkg, bmp);
        return bmp;
    }

    Bitmap iconOf(String pkg) { return iconCache.get(pkg); }

    /** 不建 ShellData 也能拿到当前外观（悬浮层的横幅用）。 */
    static Theme loadTheme(Context ctx) {
        try {
            JSONObject k = new JSONObject(ctx.getSharedPreferences("tessera", Context.MODE_PRIVATE).getString("look", "{}"));
            return new Theme(!"light".equals(k.optString("theme", "dark")), k.optString("accent", "slate"));
        } catch (JSONException e) {
            return new Theme(true, "slate");
        }
    }

    void reloadNotifs() {
        notifs.clear();
        try {
            JSONArray arr = new JSONArray(NotifService.snapshot());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Notif n = new Notif();
                n.key = o.optString("key"); n.pkg = o.optString("pkg"); n.app = o.optString("app");
                n.title = o.optString("title"); n.text = o.optString("text"); n.category = o.optString("category");
                n.when = o.optLong("when"); n.ongoing = o.optBoolean("ongoing"); n.clearable = o.optBoolean("clearable");
                n.media = o.optBoolean("media"); n.fgs = o.optBoolean("fgs"); n.progress = o.optInt("progress", -1); n.content = o.optBoolean("content", true);
                JSONArray ac = o.optJSONArray("actions");
                if (ac != null) for (int k = 0; k < ac.length(); k++) n.actions.add(ac.optJSONObject(k) != null ? ac.optJSONObject(k).optString("label") : ac.optString(k));
                notifs.add(n);
            }
        } catch (JSONException ignored) {
        }
        if (dismissedLive != null) {
            boolean still = false;
            for (Notif n : notifs) if (n.key.equals(dismissedLive)) still = true;
            if (!still) dismissedLive = null;
        }
    }

    static Notif parseNotif(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Notif n = new Notif();
            n.key = o.optString("key"); n.pkg = o.optString("pkg"); n.app = o.optString("app");
            n.title = o.optString("title"); n.text = o.optString("text"); n.when = o.optLong("when");
            n.clearable = o.optBoolean("clearable"); n.ongoing = o.optBoolean("ongoing");
            JSONArray ac = o.optJSONArray("actions");
            if (ac != null) for (int k = 0; k < ac.length(); k++) n.actions.add(ac.optJSONObject(k) != null ? ac.optJSONObject(k).optString("label") : ac.optString(k));
            return n;
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------- 开始屏 ----------

    static int rank(String pkg) {
        for (int i = 0; i < DEFAULTS.length; i++) if (DEFAULTS[i][0].equals(pkg)) return i;
        return 999;
    }

    String[] cfgFor(App a) {
        String[] c = cfg.get(a.id);
        if (c != null) return c;
        int r = rank(a.pkg);
        return r < DEFAULTS.length ? new String[] {DEFAULTS[r][1], DEFAULTS[r][2]} : new String[] {"s", "soft"};
    }

    /** 存下的顺序在前；新装的按默认优先级插入，其余按名字（中文排序）放最后。 */
    /**
     * 开始屏只放"钉上去"的应用（用户 2026-10-25：开始屏 + 右边一列应用列表，从列表拖到开始屏）。钉上去的就是 order 里的那些、按 order 的顺序。
     * 第一次用这一版时，把当时开始屏上的全部应用记成已钉，开始屏看起来不变；之后新装的应用只出现在应用列表里（同 WP）。
     */
    private void initPins() {
        if (prefs.getBoolean("pinsInit", false) || apps.isEmpty()) return;
        List<App> now = ordered();
        order.clear();
        for (App a : now) order.add(a.id);
        saveLayout();
        prefs.edit().putBoolean("pinsInit", true).apply();
    }

    /** 开始屏上的磁贴（已钉、还装着的），按 order 的顺序。 */
    List<App> pinned() {
        HashMap<String, App> byId = new HashMap<>();
        for (App a : apps) byId.put(a.id, a);
        List<App> out = new ArrayList<>();
        for (String id : order) { App a = byId.remove(id); if (a != null) out.add(a); }
        return out;
    }

    boolean isPinned(String id) { return order.contains(id); }

    void pin(String id) { if (!order.contains(id)) { order.add(id); saveLayout(); } }

    void unpin(String id) { if (order.remove(id)) saveLayout(); }

    List<App> ordered() {
        HashMap<String, App> byId = new HashMap<>();
        for (App a : apps) byId.put(a.id, a);
        List<App> out = new ArrayList<>();
        for (String id : order) { App a = byId.remove(id); if (a != null) out.add(a); }
        List<App> rest = new ArrayList<>(byId.values());
        final Collator zh = Collator.getInstance(Locale.CHINA);
        rest.sort((x, y) -> rank(x.pkg) != rank(y.pkg) ? rank(x.pkg) - rank(y.pkg) : zh.compare(x.label, y.label));
        out.addAll(rest);
        return out;
    }

    static String label(App a) { String[] m = META.get(a.pkg); return m != null ? m[1] : a.label; }

    static String glyphName(String pkg) { String[] m = META.get(pkg); return m != null ? m[0] : ""; }

    private List<Notif> byPkg(String pkg) {
        List<Notif> out = new ArrayList<>();
        for (Notif n : notifs) if (n.pkg.equals(pkg) && !n.ongoing) out.add(n);
        return out;
    }

    static String num(long n) { return NumberFormat.getIntegerInstance(Locale.US).format(n); }

    static String timeText(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int m = c.get(Calendar.MINUTE);
        return c.get(Calendar.HOUR_OF_DAY) + ":" + (m < 10 ? "0" : "") + m;
    }

    /** 磁贴网格要的数据（和 shell.js 推给 TileGridView 的格式一样；图标用 iconPkg 直接拿位图，不再编码）。 */
    JSONObject tiles() {
        Theme th = theme();
        JSONArray arr = new JSONArray();
        int flip = 0;
        try {
            for (App a : pinned()) {
                String[] c = cfgFor(a);
                String size = c[0], tone = c[1];
                String glyph = glyphName(a.pkg), lbl = label(a), count = "", iconPkg = "";
                JSONArray lines = null;
                JSONObject back = null;
                if (glyph.isEmpty()) {
                    if (a.icon != null) { lbl = ""; iconPkg = a.pkg; }      // 第三方应用：真实图标居中，不显示名字
                    else count = lbl.trim().isEmpty() ? "?" : lbl.trim().substring(0, 1);
                }
                List<Notif> mine = byPkg(a.pkg);
                switch (a.pkg) {
                    case "com.android.messaging":
                        if (!mine.isEmpty()) {
                            count = String.valueOf(mine.size());
                            if (!"s".equals(size)) back = new JSONObject().put("title", mine.get(0).title).put("body", mine.get(0).text);
                        }
                        break;
                    case "com.android.dialer":
                        if (!mine.isEmpty()) { count = String.valueOf(mine.size()); if ("s".equals(size)) glyph = ""; }
                        break;
                    case "org.fossify.calendar":
                        if ("s".equals(size)) glyph = "";
                        count = String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH));
                        break;
                    case "com.ailife.betterhealth": {
                        long left = Math.max(0, GOAL - steps);
                        if ("w".equals(size)) {
                            glyph = "";
                            String l1 = left > 0 ? "还差 " + num((long) Math.ceil(left / 10.0) * 10) + " 步" : num(steps) + " 步";
                            String l2 = left > 0 ? "饭后走 " + Math.max(1, (long) Math.ceil(left / 100.0)) + " 分钟就够" : "今天的目标完成了";
                            lines = new JSONArray().put(l1).put(l2);
                        } else if ("m".equals(size)) {
                            count = num(steps);
                        }
                        break;
                    }
                    case "org.lineageos.eleven":
                        for (Notif n : notifs) if (n.media) { if (!"s".equals(size)) back = new JSONObject().put("title", n.title).put("body", n.text); break; }
                        break;
                    case "com.android.deskclock": {
                        long at = bridge.nextAlarm();
                        if (at > 0 && !"s".equals(size)) back = new JSONObject().put("title", "闹钟").put("body", timeText(at));
                        break;
                    }
                }
                double flipDelay = 0;
                if (back != null) { flipDelay = flip * 3; flip++; }
                JSONObject t = new JSONObject()
                        .put("id", a.id).put("pkg", a.pkg).put("cls", a.cls)
                        .put("kind", "com.android.settings".equals(a.pkg) ? "settings" : "")
                        .put("size", size).put("tone", tone)
                        .put("bg", Theme.hex(th.toneBg(tone))).put("fg", Theme.hex(th.toneFg(tone)))
                        .put("label", lbl).put("name", a.label).put("iconPkg", iconPkg)
                        .put("glyph", glyph).put("sglyph", glyphName(a.pkg)).put("count", count)
                        .put("flipDelay", flipDelay);
                if (lines != null) t.put("lines", lines);
                if (back != null) t.put("back", back);
                arr.put(t);
            }
            return new JSONObject().put("bg", Theme.hex(th.bg)).put("accent", Theme.hex(th.accent)).put("tok", th.tok()).put("tiles", arr);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ---------- 数据表盘的卡片 ----------

    /** 现在要放在数据表盘上的卡片（按顺序）。 */
    List<FaceStyles.Card> faceCards() {
        List<FaceStyles.Card> out = new ArrayList<>();
        for (String id : cardIds) { FaceStyles.Card c = card(id); if (c != null) out.add(c); }
        return out;
    }

    /** 一张卡片的内容（设计稿 FaceComplications 的 APPS：value / unit / sub / end）。 */
    FaceStyles.Card card(String id) {
        switch (id) {
            case "heart":
                return new FaceStyles.Card(id, "heart", "心率", heartRate > 0 ? String.valueOf(heartRate) : "--", "", "次/分", true);
            case "act": {
                long pct = Math.round(steps * 100.0 / GOAL);
                return new FaceStyles.Card(id, "activity", "活动", String.valueOf(pct), "%", num(steps) + " 步", true);
            }
            case "msg": {
                List<Notif> mine = byPkg("com.android.messaging");
                String sub = mine.isEmpty() ? "没有新消息" : (mine.get(0).title.isEmpty() ? mine.get(0).text : mine.get(0).title + "：" + mine.get(0).text);
                return new FaceStyles.Card(id, "message", "信息", String.valueOf(mine.size()), "", sub, false);
            }
            case "cal": {
                String[] ev = nextEvent();
                return new FaceStyles.Card(id, "calendar", "日历", ev == null ? "--" : ev[0], "", ev == null ? "没有日程" : ev[1], true);
            }
            default:
                return id.startsWith("app:") ? appCard(id) : null;
        }
    }

    /** 通用应用卡：数值是这个应用的通知数，小字是最新一条；闹钟显示下一个闹钟。 */
    private FaceStyles.Card appCard(String id) {
        String pkg = id.substring(4);
        App app = null;
        for (App a : apps) if (a.pkg.equals(pkg)) { app = a; break; }
        if (app == null) return null;
        String glyph = glyphName(pkg), name = label(app);
        if ("com.android.deskclock".equals(pkg)) {
            long at = bridge.nextAlarm();
            return new FaceStyles.Card(id, glyph.isEmpty() ? null : glyph, name, at > 0 ? timeText(at) : "--", "", at > 0 ? "下一个闹钟" : "没有闹钟", true);
        }
        List<Notif> mine = byPkg(pkg);
        String sub = mine.isEmpty() ? "没有通知" : (mine.get(0).title.isEmpty() ? mine.get(0).text : mine.get(0).title + "：" + mine.get(0).text);
        return new FaceStyles.Card(id, glyph.isEmpty() ? null : glyph, name, String.valueOf(mine.size()), "", sub, false);
    }

    private String[] eventCache;
    private long eventAt;

    /** 今天起 7 天内的下一个日程：{时间 HH:mm, 标题}；没有或读不到日历时为 null。一分钟查一次。 */
    private String[] nextEvent() {
        long now = System.currentTimeMillis();
        if (now - eventAt < 60000) return eventCache;
        eventAt = now;
        eventCache = null;
        android.net.Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, now);
        android.content.ContentUris.appendId(b, now + 7L * 24 * 3600 * 1000);
        try (android.database.Cursor cur = ctx.getContentResolver().query(b.build(),
                new String[] {android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.TITLE},
                android.provider.CalendarContract.Instances.END + " >= ?", new String[] {String.valueOf(now)},
                android.provider.CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur != null && cur.moveToFirst()) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(cur.getLong(0));
                eventCache = new String[] {pad2(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad2(c.get(Calendar.MINUTE)), cur.getString(1) == null ? "" : cur.getString(1)};
            }
        } catch (Throwable ignored) {
        }
        return eventCache;
    }

    private static String pad2(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /** 数据表盘上的即时活动：正在播放的媒体 / 进行中的任务（计时器、导航、下载……）；没有为 null。 */
    FaceStyles.Live faceLive() {
        for (Notif n : notifs) if (n.media && !dismissed(n)) {
            List<String> l = new ArrayList<>(); if (!n.text.isEmpty()) l.add(n.text);
            return new FaceStyles.Live(n.key, "music", n.app, n.title.isEmpty() ? "正在播放" : n.title, l);
        }
        for (Notif n : notifs) if (isLive(n) && !dismissed(n)) {
            List<String> l = new ArrayList<>(); if (!n.text.isEmpty()) l.add(n.text);
            return new FaceStyles.Live(n.key, n.progress >= 0 ? "timer" : notifIcon(n.pkg), n.app,
                    n.progress >= 0 ? n.progress + "%" : (n.title.isEmpty() ? n.app : n.title), l);
        }
        return null;
    }

    // ---------- 表盘的即时活动 ----------

    static String notifIcon(String pkg) { String g = glyphName(pkg); return g.isEmpty() ? "bell" : g; }

    private static boolean isLive(Notif n) {
        if (!n.ongoing || n.fgs || LIVE_EXCLUDE.contains(n.pkg) || "service".equals(n.category)) return false;
        return n.progress >= 0 || n.category.matches(".*(stopwatch|alarm|progress|navigation|transport|workout).*");
    }

    /** 正在播放的媒体 / 进行中的任务；都没有时，"活动"表盘显示今天的步数。返回 null = 不显示。 */
    FaceView.Activity activity() {
        for (Notif n : notifs) if (n.media) {
            List<String> l = new ArrayList<>(); if (!n.text.isEmpty()) l.add(n.text);
            return new FaceView.Activity("music", n.app, n.title.isEmpty() ? "正在播放" : n.title, l);
        }
        for (Notif n : notifs) if (isLive(n)) {
            List<String> l = new ArrayList<>(); if (!n.text.isEmpty()) l.add(n.text);
            return new FaceView.Activity(n.progress >= 0 ? "timer" : notifIcon(n.pkg), n.app,
                    n.progress >= 0 ? n.progress + "%" : (n.title.isEmpty() ? n.app : n.title), l);
        }
        if (faceIndex == 1) {
            long pct = Math.min(100, Math.round(steps * 100.0 / GOAL));
            return new FaceView.Activity("activity", "活动", pct + "%", Arrays.asList(num(steps) + " 步", "目标 " + num(GOAL)));
        }
        return null;
    }

    /** 点表盘下半部打开的通知（只有真正的媒体 / 进行中任务才有）。 */
    String liveKey() {
        for (Notif n : notifs) if (n.media && !dismissed(n)) return n.key;
        for (Notif n : notifs) if (isLive(n) && !dismissed(n)) return n.key;
        return null;
    }

    /** 在数据表盘上横滑划掉的即时活动：同一条通知不再显示，通知没了就忘掉。 */
    String dismissedLive;

    private boolean dismissed(Notif n) { return n.key.equals(dismissedLive); }

    /** 通知中心里列出来的：去掉自己、媒体、前台服务、录音占位、没内容的；新的在前。 */
    List<Notif> listed() {
        List<Notif> out = new ArrayList<>();
        for (Notif n : notifs) {
            if (n.pkg.equals(ctx.getPackageName()) || n.media || n.fgs || LIVE_EXCLUDE.contains(n.pkg)) continue;
            if (n.title.isEmpty() && n.text.isEmpty()) continue;
            out.add(n);
        }
        out.sort((a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    /** 相对时间缩写（同 common.js 的 TS.ago）。 */
    static String ago(long when) {
        long min = (System.currentTimeMillis() - when) / 60000;
        if (min < 1) return "现在";
        if (min < 60) return min + "分";
        Calendar d = Calendar.getInstance(), now = Calendar.getInstance();
        d.setTimeInMillis(when);
        if (min / 60 < 24 && d.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return (min / 60) + "时";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (d.get(Calendar.YEAR) == now.get(Calendar.YEAR) && d.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return "昨天";
        return (d.get(Calendar.MONTH) + 1) + "月" + d.get(Calendar.DAY_OF_MONTH) + "日";
    }
}
