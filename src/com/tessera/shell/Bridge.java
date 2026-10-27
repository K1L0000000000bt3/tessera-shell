package com.tessera.shell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** 页面调用原生能力的唯一入口（window.TesseraHost）。 */
public class Bridge {
    private static final String TAG = "Tessera";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private OverlayService overlay;

    Bridge(Context context) {
        this.context = context;
    }

    Bridge(OverlayService overlay) {
        this.context = overlay;
        this.overlay = overlay;
    }

    // ---------- 应用 ----------

    @JavascriptInterface
    public String apps() {
        JSONArray arr = new JSONArray();
        LauncherApps la = context.getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> list = la.getActivityList(null, Process.myUserHandle());
        for (LauncherActivityInfo info : list) {
            ComponentName c = info.getComponentName();
            if (c.getPackageName().equals(context.getPackageName())) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", c.flattenToShortString());
                o.put("pkg", c.getPackageName());
                o.put("cls", c.getClassName());
                o.put("label", String.valueOf(info.getLabel()));
                String icon = appIcon(c.getPackageName(), info);
                if (icon != null) o.put("icon", icon);
            } catch (JSONException ignored) {
            }
            arr.put(o);
        }
        return arr.toString();
    }

    /** 应用图标：画成 120px 的 PNG，转成 data URI 塞进页面（同包只算一次）。 */
    private final java.util.HashMap<String, String> iconCache = new java.util.HashMap<>();

    private String appIcon(String pkg, LauncherActivityInfo info) {
        String cached = iconCache.get(pkg);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String uri = "";
        try {
            android.graphics.drawable.Drawable d = info.getIcon(
                    android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi);
            // 自适应图标只取前景层：否则会带一块深色圆底，贴在磁贴上像个徽章。
            // 只留前景就是"平铺在块面上的图形"，更接近 Windows 8 的磁贴观感。
            if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                android.graphics.drawable.Drawable fg =
                        ((android.graphics.drawable.AdaptiveIconDrawable) d).getForeground();
                if (fg != null) d = fg;
            }
            if (d != null) {
                int size = 120;
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        size, size, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(cv);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                uri = "data:image/png;base64," + android.util.Base64.encodeToString(
                        bos.toByteArray(), android.util.Base64.NO_WRAP);
            }
        } catch (Throwable t) {
            Log.w(TAG, "icon failed: " + pkg, t);
        }
        iconCache.put(pkg, uri);
        return uri.isEmpty() ? null : uri;
    }

    @JavascriptInterface
    public void launch(String pkg, String cls) {
        main.post(() -> {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startQuietly(i);
        });
    }

    /** 从卡片放大到全屏：页面把那张卡片的矩形（CSS 像素）传过来，交给系统做 makeScaleUpAnimation。 */
    @JavascriptInterface
    public void launchAt(String pkg, String cls, float x, float y, float w, float h) {
        main.post(() -> {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            View src = context instanceof Activity ? ((Activity) context).getWindow().getDecorView() : null;
            if (src == null || src.getWidth() <= 0 || w <= 0 || h <= 0) { startQuietly(i); return; }
            float k = src.getWidth() / 200f;   // 页面是 200 设计像素宽
            try {
                ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(src,
                        Math.round(x * k), Math.round(y * k), Math.round(w * k), Math.round(h * k));
                Log.i(TAG, "launchAt " + pkg + " from " + Math.round(x * k) + "," + Math.round(y * k)
                        + " " + Math.round(w * k) + "x" + Math.round(h * k));
                context.startActivity(i, opts.toBundle());
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "launchAt failed: " + i, e);
                startQuietly(i);
            }
        });
    }

    /** 淡入启动：卡片先在桌面里放大到铺满屏幕，应用从这张卡片里化出来。 */
    @JavascriptInterface
    public void launchFade(String pkg, String cls) { launchWith(pkg, cls, "ts_fade_in"); }

    /** 原生网格用：卡片按转门翻走之后，应用顺着同一个方向翻进来（res/anim/ts_turn_in.xml）。 */
    @JavascriptInterface
    public void launchTurn(String pkg, String cls) { launchWith(pkg, cls, "ts_turn_in"); }

    private void launchWith(String pkg, String cls, String anim) {
        main.post(() -> {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            int enter = context.getResources().getIdentifier(anim, "anim", context.getPackageName());
            try {
                ActivityOptions opts = ActivityOptions.makeCustomAnimation(context, enter, 0);
                context.startActivity(i, opts.toBundle());
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "launchFade failed: " + i, e);
                startQuietly(i);
            }
        });
    }

    /** 打开系统设置的某一页（未设计的部分先交给系统页面）。 */
    @JavascriptInterface
    public void openSettings(String action) {
        main.post(() -> {
            Intent i = new Intent(action == null || action.isEmpty() ? Settings.ACTION_SETTINGS : action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startQuietly(i);
        });
    }

    private void startQuietly(Intent i) {
        try {
            // 转门：开始屏磁贴在页面里翻出，应用窗口同时从右侧转入（res/anim/ts_open_*）
            int enter = context.getResources().getIdentifier("ts_open_enter", "anim", context.getPackageName());
            int exit = context.getResources().getIdentifier("ts_open_exit", "anim", context.getPackageName());
            Log.i(TAG, "launch anim ids enter=" + enter + " exit=" + exit + " pkg=" + context.getPackageName());
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(context, enter, exit);
            context.startActivity(i, opts.toBundle());
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "launch failed: " + i, e);
        }
    }

    // ---------- 通知 ----------

    @JavascriptInterface
    public String notifications() {
        return NotifService.snapshot();
    }

    @JavascriptInterface
    public void notifOpen(String key) {
        NotifService.open(key);
    }

    /** 通知中心里点开：应用用转门翻进来（res/anim/ts_turn_in.xml，和开始屏启动应用同一个方向）。 */
    public void notifOpenTurn(String key) {
        int enter = context.getResources().getIdentifier("ts_turn_in", "anim", context.getPackageName());
        android.os.Bundle opts = null;
        try { opts = ActivityOptions.makeCustomAnimation(context, enter, 0).toBundle(); } catch (Throwable ignored) { }
        NotifService.open(key, opts);
    }

    @JavascriptInterface
    public void notifDismiss(String key) {
        NotifService.dismiss(key);
    }

    @JavascriptInterface
    public void notifClear() {
        NotifService.clearAll();
    }

    @JavascriptInterface
    public void notifAction(String key, int index) {
        NotifService.action(key, index);
    }

    @JavascriptInterface
    public boolean notifAccess() {
        return NotifService.connected();
    }

    // ---------- 状态 ----------

    @JavascriptInterface
    public String battery() {
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b == null ? "{}" : MainActivity.batteryJson(b);
    }

    @JavascriptInterface
    public long steps() {
        return Steps.today(context);
    }

    @JavascriptInterface
    public long nextAlarm() {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo info = am == null ? null : am.getNextAlarmClock();
        return info == null ? 0 : info.getTriggerTime();
    }

    @JavascriptInterface
    public boolean is24Hour() {
        return android.text.format.DateFormat.is24HourFormat(context);
    }

    // ---------- 快捷开关（设置的胚胎） ----------

    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public boolean wifi() {
        WifiManager w = context.getApplicationContext().getSystemService(WifiManager.class);
        return w != null && w.isWifiEnabled();
    }

    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public void setWifi(boolean on) {
        WifiManager w = context.getApplicationContext().getSystemService(WifiManager.class);
        if (w != null) w.setWifiEnabled(on);
    }

    @JavascriptInterface
    public boolean bluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        return a != null && a.isEnabled();
    }

    @JavascriptInterface
    public void setBluetooth(boolean on) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        if (on) a.enable(); else a.disable();
    }

    @JavascriptInterface
    public int brightness() {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
    }

    @JavascriptInterface
    public boolean setBrightness(int value) {
        if (!Settings.System.canWrite(context)) return false;
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        return Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                Math.max(1, Math.min(255, value)));
    }

    @JavascriptInterface
    public boolean dnd() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        return nm != null && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    @JavascriptInterface
    public boolean setDnd(boolean on) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) return false;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
        return true;
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        Vibrator v = context.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @JavascriptInterface
    public void log(String msg) {
        Log.i(TAG, msg);
    }

    /** 开始屏是否用原生网格渲染。 */
    @JavascriptInterface
    public boolean startNative() {
        return context instanceof MainActivity && ((MainActivity) context).startNative();
    }

    /** 显式显示/隐藏原生网格。 */
    @JavascriptInterface
    public void showGrid(boolean on) {
        if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).showGrid(on));
    }

    /** 页面把磁贴布局（id/size/tone/label/icon）推给原生网格。 */
    @JavascriptInterface
    public void gridTiles(String json) {
        if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).setGridTiles(json));
    }

    /** 一次性迁移：migrate.html 把 localStorage 里 ts.* 的设置交给原生桌面。 */
    @JavascriptInterface
    public void exportStorage(String json) {
        if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).onStorageExported(json));
    }

    /** 调试：切换原生磁贴网格（迁移中，和 WebView 界面并存）。 */
    @JavascriptInterface
    public void toggleGrid() {
        if (context instanceof MainActivity) main.post(((MainActivity) context)::toggleGrid);
    }

    /** 网格整层的位置（设计像素，200 = 在屏幕下面）：表盘上拉跟手、进出场；curve 0 缓出 / 1 加速离场；hide = 到位后隐藏。 */
    @JavascriptInterface
    public void gridPose(float y, int ms, int curve, boolean hide) {
        if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).gridPose(y, ms, curve, hide));
    }

    /** 从 Tessera 自己的页面回到开始屏：磁贴按列翻回来。 */
    @JavascriptInterface
    public void gridTurnIn() {
        if (context instanceof MainActivity) main.post(((MainActivity) context)::gridTurnIn);
    }

    /** 调试：原生网格动画慢放倍数（1 = 正常），截中间帧用。 */
    @JavascriptInterface
    public void gridSlow(float factor) { TileGridView.slow = Math.max(0.1f, factor); }

    // ---------- 原生表盘（FaceView 在 WebView 下面，页面只负责手势与数据） ----------

    private FaceView face() {
        return context instanceof MainActivity ? ((MainActivity) context).face() : null;
    }

    /** 下沉进度 0..1（被开始屏/通知中心盖住时为 1）；ms = 0 立即到位，否则按 .sh-face-layer 的过渡补完。 */
    @JavascriptInterface
    public void faceSink(float p, int ms) {
        FaceView f = face();
        if (f != null) main.post(() -> f.setSink(Math.max(0f, Math.min(1f, p)), ms));
    }

    /** 点一下表盘上的卡片：翻转换色（坐标是页面 CSS 像素）。返回是否命中卡片。 */
    @JavascriptInterface
    public boolean faceTap(float cssX, float cssY) {
        FaceView f = face();
        if (f == null) return false;
        boolean hit = f.hitPanel(cssX, cssY);       // 同步判断，命中就不让 JS 再去开应用
        Log.i(TAG, "faceTap " + cssX + "," + cssY + " hit=" + hit);
        if (hit) main.post(() -> f.tapPanel(cssX, cssY));
        return hit;
    }

    /** 双指按住/松开：时间逐渐隐藏/恢复。 */
    @JavascriptInterface
    public void faceTimeHide(boolean hidden) {
        FaceView f = face();
        if (f != null) main.post(() -> f.setPanelsTimeHidden(hidden));
    }

    /** 双指点击：进入/退出可翻状态（进入时先隐藏时间，之后点卡片才会翻转）。 */
    @JavascriptInterface
    public void faceToggleMode() {
        FaceView f = face();
        if (f != null) main.post(f::togglePanelsMode);
    }

    /** 双指点击：所有改过色的卡片翻回原色。 */
    @JavascriptInterface
    public void faceReset() {
        FaceView f = face();
        if (f != null) main.post(f::resetPanels);
    }

    /** 表盘是否露出来；完全被盖住时停止逐帧走秒。 */
    @JavascriptInterface
    public void faceShown(boolean shown) {
        FaceView f = face();
        if (f != null) main.post(() -> f.setShown(shown));
    }

    /** 通知中心下边缘位置（占屏高比例，0 = 不在了）：原生表盘在这个范围里画磨砂。 */
    @JavascriptInterface
    public void faceBlur(float bottom) {
        FaceView f = face();
        if (f != null) main.post(() -> f.setBlur(bottom));
    }

    /** 表盘上的通知横幅范围（设计像素）和圆角；全 0 = 没有横幅。原生表盘在横幅底下画同一张磨砂快照。 */
    @JavascriptInterface
    public void faceToastBlur(float l, float t, float r, float b, float radius) {
        FaceView f = face();
        if (f != null) main.post(() -> f.setToastBlur(l, t, r, b, radius));
    }

    /** 即时活动 {icon,title,value,lines[]}；空串 / "null" 表示没有。 */
    @JavascriptInterface
    public void faceActivity(String json) {
        FaceView f = face();
        if (f == null) return;
        FaceView.Activity a = null;
        if (json != null && !json.isEmpty() && !"null".equals(json)) {
            try {
                JSONObject o = new JSONObject(json);
                List<String> lines = new java.util.ArrayList<>();
                JSONArray arr = o.optJSONArray("lines");
                if (arr != null) for (int i = 0; i < arr.length() && i < 2; i++) lines.add(arr.optString(i));
                a = new FaceView.Activity(o.optString("icon", null), o.optString("title", ""), o.optString("value", ""), lines);
            } catch (JSONException e) {
                Log.w(TAG, "bad face activity: " + json, e);
            }
        }
        final FaceView.Activity act = a;
        main.post(() -> f.setActivity(act));
    }

    // ---------- 悬浮层（只在 OverlayService 的页面里有效） ----------

    /** 横幅出现时让悬浮窗接收触摸（height 为设计像素）；收起后恢复穿透。 */
    @JavascriptInterface
    public void overlayTouch(boolean touchable, int designHeight) {
        // 悬浮层已经改成原生绘制，横幅的大小/触摸由 OverlayService 自己管，这里不再需要 */
        if (overlay == null) return;
    }
}
