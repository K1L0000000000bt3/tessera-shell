package com.tessera.shell;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tessera 桌面：表盘、开始屏（全部应用的磁贴网格）、通知中心和设置都在这个 WebView 里，
 * 用的是设计稿原封不动的组件（bundle.js / bundle.css / tokens.css）。
 */
public class MainActivity extends Activity implements Host.Listener {
    private WebView web;
    /** 全原生桌面（默认）；prefs 里 shell_web = true 才回到旧的 WebView 版。 */
    private NativeShell shell;
    private WebView migrator;

    /** WebView 版存在 localStorage 里的设置（磁贴顺序/尺寸、主题、表盘样式）：开一个看不见的 WebView 读出来交给原生，只做一次。 */
    private void migrateLocalStorage() {
        // 只有这里会碰 Chromium（一初始化就会拉起一个约 90MB 的渲染进程），所以只在还没迁移过时走一次
        migrator = Web.create(this, bridge, "migrate.html", true);
        root.addView(migrator, new FrameLayout.LayoutParams(1, 1));
    }

    void onStorageExported(String json) {
        if (shell != null) { shell.data.importLocalStorage(json); shell.reload(); }
        if (migrator != null) { root.removeView(migrator); migrator.destroy(); migrator = null; }
    }
    private FaceView face;
    private Steps steps;

    /** 原生表盘；Bridge 用它同步下沉、可见性和即时活动。 */
    FaceView face() { return face; }

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case Intent.ACTION_BATTERY_CHANGED:
                    Web.emit(web, "battery", batteryJson(intent));
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    // 抬腕看到的永远是表盘
                    if (shell != null) shell.onScreenOff();
                    Web.emit(web, "screen", "{\"on\":false}");
                    break;
                case Intent.ACTION_SCREEN_ON:
                    Web.emit(web, "screen", "{\"on\":true}");
                    break;
                default:
                    if (shell != null) shell.onTimeTick();
                    Web.emit(web, "time", null);
            }
        }
    };

    private Bridge bridge;
    private FrameLayout root;
    private TileGridView grid;
    /** 调试：广播切过来的开关，onResume 时生效。 */
    static boolean toggleGridForDebug = false;
    private boolean gridApplied = false;
    static MainActivity current;

    private String gridTilesJson;

    /** 页面推来的磁贴布局，转给 TileGridView。 */
    /** 开始屏用原生网格还是 WebView 版（持久化，默认 WebView）。 */
    boolean startNative() {
        return getSharedPreferences("tessera", MODE_PRIVATE).getBoolean("start_native", false);
    }

    void setStartNative(boolean on) {
        getSharedPreferences("tessera", MODE_PRIVATE).edit().putBoolean("start_native", on).apply();
        if (!on) showGrid(false);
    }

    /** 原生网格只建一次、一直挂在 WebView 上面，用 VISIBLE / GONE 切换（保住滚动位置，也省得反复加删视图）。 */
    private TileGridView ensureGrid() {
        if (grid != null || root == null) return grid;
        grid = new TileGridView(this);
        grid.setVisibility(View.GONE);
        grid.setCallback(new TileGridView.Callback() {
            @Override public void onLaunch(String pkg, String cls, String label) { bridge.launchTurn(pkg, cls); }
            @Override public void onReorder(String idsJson) { Web.emit(web, "gridOrder", idsJson); }
            @Override public void onExitToFace() {
                hideGrid();
                toggleGridForDebug = false; gridApplied = false;
                Web.emit(web, "home", null);
            }
            // 顶上下拉：表盘跟着从下沉的位置浮回来（和 WebView 版 dragApply('start') 一样的 11px / 0.93 / 0.85）
            @Override public void onPull(float p) { face.setShown(true); face.setSink(1f - p, 0); }
            @Override public void onPullEnd(boolean commit) {
                face.setSink(commit ? 0f : 1f, 200);
                if (!commit) root.postDelayed(() -> {
                    if (grid.getVisibility() == View.VISIBLE && grid.getTranslationY() == 0f) face.setShown(false);
                }, 350);
            }
            @Override public void onBack() { Web.emit(web, "back", null); }
            @Override public void onOpenPage(String kind, String id) {
                Web.emit(web, "gridOpen", "{\"kind\":" + JSONObject.quote(kind) + ",\"id\":" + JSONObject.quote(id) + "}");
            }
            @Override public void onSearch() { openSearch(); }
        });
        if (gridTilesJson != null) grid.setJson(gridTilesJson);
        else grid.setJson(bridge.apps());
        root.addView(grid, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return grid;
    }

    private android.animation.ValueAnimator gridAnim;
    private boolean gridHideAtEnd;

    private boolean gridVisible() { return grid != null && grid.getVisibility() == View.VISIBLE; }

    /** 显式显示/隐藏原生网格（"开始屏用原生"模式下来自页面的导航）。 */
    void showGrid(boolean on) {
        TileGridView g = ensureGrid();
        if (g == null) return;
        if (on) {
            if (!gridVisible()) { cancelGridAnim(); g.setEnterY(0f); g.setVisibility(View.VISIBLE); }
        } else {
            if (gridAnim != null && gridHideAtEnd) return;          // 正在滑出去：让动画自己收尾
            hideGrid();
        }
    }

    // ---------- 应用搜索（在原生开始屏从右往左滑进来） ----------
    private SearchPage search;

    private SearchPage ensureSearch() {
        if (search != null || root == null || grid == null) return search;
        search = new SearchPage(this, grid);
        search.setCallback(new SearchPage.Callback() {
            @Override public void onLaunch(TileGridView.App app) {
                if ("settings".equals(app.kind)) {         // 设置：Tessera 自己的页面
                    search.dismiss();
                    Web.emit(web, "gridOpen", "{\"kind\":\"settings\",\"id\":" + JSONObject.quote(app.id) + "}");
                    return;
                }
                bridge.launchTurn(app.pkg, app.cls);       // 开始屏的卡片在进搜索时已经翻走了，回来时 returnFromApp 翻回来
                root.postDelayed(() -> { if (search != null) search.dismiss(); }, 400);
                // 兜底：应用没起来（桌面还在前台）就把卡片翻回来
                root.postDelayed(() -> { if (hasWindowFocus() && gridVisible() && (search == null || !search.isOpen())) grid.returnFromApp(); }, 1500);
            }
            @Override public void onClosed() { if (grid != null) grid.returnFromApp(); }
        });
        root.addView(search, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return search;
    }

    /** 开始屏的卡片按行转门翻走，搜索页再翻进来。 */
    void openSearch() {
        TileGridView g = ensureGrid();
        if (g == null || !gridVisible()) return;
        SearchPage s = ensureSearch();
        if (s == null || s.isOpen()) return;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        g.turnstileOutAll(s::open);
    }

    private void hideGrid() {
        cancelGridAnim();
        if (search != null) search.dismiss();
        if (grid == null) return;
        grid.setVisibility(View.GONE);
        grid.resetTransient();
    }

    private void cancelGridAnim() {
        if (gridAnim == null) return;
        android.animation.ValueAnimator a = gridAnim;
        gridAnim = null;
        a.cancel();
    }

    /** 页面驱动网格整层的位置（设计像素；H = 在屏幕下面）：表盘上拉跟手、进出场动画。curve 0 = 缓出，1 = 加速离场。 */
    void gridPose(float yDesign, int ms, int curve, boolean hideAtEnd) {
        TileGridView g = ensureGrid();
        if (g == null) return;
        cancelGridAnim();
        float px = yDesign * root.getWidth() / 200f;
        g.setVisibility(View.VISIBLE);
        if (ms <= 0) { g.setEnterY(px); if (hideAtEnd) hideGrid(); return; }
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(g.getTranslationY(), px).setDuration(ms);
        a.setInterpolator(curve == 1 ? new android.view.animation.PathInterpolator(0.7f, 0f, 0.9f, 0.3f)
                : new android.view.animation.PathInterpolator(0.1f, 0.9f, 0.2f, 1f));
        a.addUpdateListener(v -> g.setEnterY((float) v.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator anim) {
                if (gridAnim != a) return;                  // 被新的动画取消了
                gridAnim = null;
                if (hideAtEnd) hideGrid();
            }
        });
        gridAnim = a;
        gridHideAtEnd = hideAtEnd;
        a.start();
    }

    /** 从 Tessera 自己的页面回到开始屏：磁贴按列翻回来。 */
    void gridTurnIn() {
        TileGridView g = ensureGrid();
        if (g == null) return;
        showGrid(true);
        g.turnIn();
    }

    void setGridTiles(String json) {
        gridTilesJson = json;
        if (grid != null) grid.setJson(json);
    }

    /** 调试用：在原生磁贴网格和现在的 WebView 界面之间切换（迁移期间并存）。 */
    void toggleGrid() {
        if (ensureGrid() == null) return;
        showGrid(!gridVisible());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Curves.load(this);                              // 上次推过来的动画曲线
        if (getIntent() != null && getIntent().hasExtra("curves")) Curves.apply(this, getIntent().getStringExtra("curves"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // 表盘（FaceView）在最下面；其余界面默认全是原生的 NativeShell，shell_web = true 时才回到旧的 WebView 版。
        face = new FaceView(this);
        bridge = new Bridge(this);
        FrameLayout root = new FrameLayout(this);
        this.root = root;
        root.setBackgroundColor(0xFF000000);            // 表盘下沉缩小时露出的边
        root.addView(face, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (getSharedPreferences("tessera", MODE_PRIVATE).getBoolean("shell_web", false)) {
            WebView.setWebContentsDebuggingEnabled(true);
            web = Web.create(this, bridge, "index.html", true);
            root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            shell = new NativeShell(this, root, face, bridge);
            if (!shell.data.migrated()) migrateLocalStorage();
        }
        setContentView(root);
        current = this;
        face.resetPanelsMode();      // 每次冷启动回到"时间可见、卡片原色"
        // root 已经铺黑，窗口背景就不用再画一遍（少一次整屏填充）
        getWindow().setBackgroundDrawable(null);
        densityDpi = getResources().getConfiguration().densityDpi;
        hideSystemBars();
        root.post(this::excludeSystemGestures);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(systemReceiver, f);

        steps = new Steps(this, count -> { if (shell != null) shell.onSteps(count); else Web.emit(web, "steps", "{\"today\":" + count + "}"); });
        Host.add(this);
        OverlayService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Host.shellForeground = true;
        Host.emit("shell", "{\"foreground\":true}");
        hideSystemBars();
        ensureCornerMask();
        steps.start();
        face.setResumed(true);
        if (shell != null) shell.onResume();
        Web.emit(web, "resume", null);
        if (shell == null && gridVisible()) grid.returnFromApp();      // 从应用回来：卡片按转门翻回来
        if (toggleGridForDebug != gridApplied) {
            gridApplied = toggleGridForDebug;
            toggleGrid();
        }
    }

    @Override
    protected void onPause() {
        face.setResumed(false);
        Host.shellForeground = false;
        Host.emit("shell", "{\"foreground\":false}");
        if (shell != null) shell.onPause();
        Web.emit(web, "pause", null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Host.remove(this);
        unregisterReceiver(systemReceiver);
        steps.stop();
        if (shell != null) shell.onDestroy();
        if (web != null) web.destroy();
        if (migrator != null) migrator.destroy();
        super.onDestroy();
    }

    /** 再次收到 HOME（侧键 / 已在桌面时按主页）：回到表盘。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (shell != null) shell.onHome(); else Web.emit(web, "home", null);
        }
        // 别的应用里的横幅点了"N 条新消息"：回到桌面并拉下通知中心
        if (intent != null && intent.getBooleanExtra("openNotifs", false) && shell != null) {
            final NativeShell s = shell;
            root.post(s::openNotifs);
        }
        // 调试：am start -n com.tessera.shell/.MainActivity --ez facemode true
        if (intent != null && intent.getBooleanExtra("facemode", false) && face != null) {
            face.togglePanelsMode();
        }
        // 调试：am start -n com.tessera.shell/.MainActivity --ei faceStyle 0..4（0 秒轨 / 1 磁贴 / 2 数据 / 3 大字 / 4 拼板）
        if (intent != null && intent.hasExtra("faceStyle") && shell != null) shell.setFaceStyle(intent.getIntExtra("faceStyle", FaceStyles.DATA));
        // 调试：--es debugTime 14:25:25 固定表盘时间（空串恢复）；--ei debugHr 72 --ei debugSteps 6240 假数据
        if (intent != null && intent.hasExtra("debugTime")) {
            String t = intent.getStringExtra("debugTime");
            if (t == null || t.isEmpty()) FaceView.debugTime = null;
            else {
                String[] p = t.split(":");
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(p[0])); c.set(java.util.Calendar.MINUTE, Integer.parseInt(p[1]));
                c.set(java.util.Calendar.SECOND, p.length > 2 ? Integer.parseInt(p[2]) : 0);
                FaceView.debugTime = c;
            }
            face.invalidate(); if (shell != null) shell.debugRefresh();
        }
        // 调试：--ez overlays false 暂时藏起四角遮罩和底边感应带（查合成掉帧），true 放回
        if (intent != null && intent.hasExtra("overlays")) TesseraA11y.debugShowOverlays(intent.getBooleanExtra("overlays", true));
        // 调曲线：--es curves 'nc.shift=0.3,0,0.2,1.12;…' 实时换（reset 恢复默认，dump 打印），见 Curves
        if (intent != null && intent.hasExtra("curves")) Curves.apply(this, intent.getStringExtra("curves"));
        // 调试：--ef slow 8 所有桌面动画放慢 8 倍（截中间帧用），1 恢复
        if (intent != null && intent.hasExtra("slow")) TileGridView.slow = Math.max(0.1f, intent.getFloatExtra("slow", 1f));
        // 调试：--es faceCards heart,act（逗号分隔，空串 = 没有卡片）
        if (intent != null && intent.hasExtra("debugLive") && shell != null) shell.debugLive(intent.getStringExtra("debugLive"));
        if (intent != null && intent.hasExtra("faceCards") && shell != null) shell.debugCards(intent.getStringExtra("faceCards"));
        if (intent != null && (intent.hasExtra("debugHr") || intent.hasExtra("debugSteps")) && shell != null)
            shell.debugData(intent.getIntExtra("debugHr", -1), intent.getIntExtra("debugSteps", -1));
        // 开始屏渲染器：--ez startnative true / false（持久化）
        if (intent != null && intent.hasExtra("startnative")) {
            setStartNative(intent.getBooleanExtra("startnative", false));
        }
        // 调试：am start -n com.tessera.shell/.MainActivity --ez grid true   （原生磁贴网格开关）
        if (intent != null && intent.getBooleanExtra("grid", false)) {
            toggleGrid();
        }
    }

    @Override
    public void onBackPressed() {
        if (shell != null) { shell.onBack(); return; }
        // 搜索开着：先关搜索（转门翻回去，开始屏翻回来）
        if (search != null && search.isOpen()) { search.close(); return; }
        // 返回由页面决定（关掉弹层 / 回到上一层 / 回到表盘），桌面本身不退出
        Web.emit(web, "back", null);
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        if (shell != null) shell.onEnterAnimationComplete();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            excludeSystemGestures();
        }
    }

    /** 不让系统的边缘手势（上滑回桌面/查看最近任务）抢走我们的滑动：
     *  否则快速上滑会把整个桌面当成一个应用"划出去"，页面自己也收不到 touchmove，看着就是"不跟手"。 */
    private void excludeSystemGestures() {
        android.view.View decor = getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        decor.setSystemGestureExclusionRects(
                java.util.Collections.singletonList(new android.graphics.Rect(0, 0, w, h)));
    }

    /** 屏幕四角遮罩靠 TesseraA11y 画。强行停止本应用（am force-stop / 设置里"强行停止"）时，
     *  系统会把它从已启用的无障碍服务里删掉，还常把它记进"已崩溃"名单，之后单纯写回设置也不会重新绑定，
     *  圆角就没了。回到桌面时检查：不在名单里就加回去；1.5 秒后还没连上，就先删掉再写回（这样才会清掉"已崩溃"）。
     *  需要 adb 授予过 WRITE_SECURE_SETTINGS，没授予就只记一条日志。 */
    private void ensureCornerMask() {
        if (TesseraA11y.connected) return;
        if (!setCornerMaskEnabled(true)) return;
        root.postDelayed(() -> {
            if (TesseraA11y.connected || isFinishing()) return;
            Log.i("Tessera", "corner mask service not bound, toggling");
            if (setCornerMaskEnabled(false)) root.postDelayed(() -> setCornerMaskEnabled(true), 500);
        }, 1500);
    }

    private int densityDpi;

    /** 密度变了：SystemUI 会按新密度重建自己的圆角层（rounded_corner_radius 55dp），
     *  却不会重新套用 sysui_rounded_size 的覆盖值，大圆角就回来了。把这个值改一下再改回去，逼它重新套用。 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // 等 SystemUI 按新密度重建完圆角层再套用（实测 3 秒内一定已经重建）
        if (densityDpi != 0 && c.densityDpi != densityDpi) root.postDelayed(this::reapplySysuiCorners, 3000);
        densityDpi = c.densityDpi;
    }

    private void reapplySysuiCorners() {
        ContentResolver cr = getContentResolver();
        String v = Settings.Secure.getString(cr, "sysui_rounded_size");
        if (v == null || "null".equals(v)) return;
        try {
            int size = Integer.parseInt(v.trim());
            Settings.Secure.putString(cr, "sysui_rounded_size", String.valueOf(size + 1));
            root.postDelayed(() -> Settings.Secure.putString(cr, "sysui_rounded_size", String.valueOf(size)), 500);
            Log.i("Tessera", "re-applied sysui_rounded_size=" + size + " after density change");
        } catch (NumberFormatException | SecurityException e) {
            Log.w("Tessera", "could not re-apply sysui_rounded_size: " + e);
        }
    }

    /** 把 TesseraA11y 加进 / 移出已启用的无障碍服务名单（保留名单里的其它服务）。 */
    private boolean setCornerMaskEnabled(boolean on) {
        ComponentName me = new ComponentName(this, TesseraA11y.class);
        ContentResolver cr = getContentResolver();
        String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        StringBuilder out = new StringBuilder();
        boolean present = false;
        if (list != null) {
            for (String s : list.split(":")) {
                if (s.isEmpty() || "null".equals(s)) continue;
                if (me.equals(ComponentName.unflattenFromString(s))) { present = true; continue; }
                if (out.length() > 0) out.append(':');
                out.append(s);
            }
        }
        if (present == on) return true;
        if (on) out.append(out.length() > 0 ? ":" : "").append(me.flattenToString());
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, out.toString());
            if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i("Tessera", "corner mask service " + (on ? "enabled" : "removed"));
            return true;
        } catch (SecurityException e) {
            Log.w("Tessera", "corner mask service is off; run: pm grant com.tessera.shell android.permission.WRITE_SECURE_SETTINGS");
            return false;
        }
    }

    /** 表冠：霍尔传感器上报 F3/F4/F7/F8，或系统转换成滚动事件。 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int crown = crownDelta(keyCode);
        if (crown != 0) {
            if (shell != null) shell.onCrown(crown);
            else if (gridVisible()) grid.crown(crown);         // 原生开始屏：表冠滚网格
            else Web.emit(web, "crown", "{\"delta\":" + crown + "}");
            return true;
        }
        Web.emit(web, "key", "{\"code\":" + keyCode + ",\"name\":" + JSONObject.quote(KeyEvent.keyCodeToString(keyCode)) + "}");
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) {
            float v = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (v == 0) v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) {
                if (shell != null) shell.onCrown(v > 0 ? -1 : 1);
                else if (gridVisible()) grid.crown(v > 0 ? -1 : 1);
                else Web.emit(web, "crown", "{\"delta\":" + (v > 0 ? -1 : 1) + "}");
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private static int crownDelta(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_F3:
            case KeyEvent.KEYCODE_F7:
                return -1;
            case KeyEvent.KEYCODE_F4:
            case KeyEvent.KEYCODE_F8:
                return 1;
            default:
                return 0;
        }
    }

    @Override
    public void onEvent(String name, String json) {
        Web.emit(web, name, json);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    static String batteryJson(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        JSONObject o = new JSONObject();
        try {
            o.put("level", scale > 0 ? Math.round(level * 100f / scale) : level);
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }
}
