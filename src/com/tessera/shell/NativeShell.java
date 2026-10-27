package com.tessera.shell;

import android.view.animation.Interpolator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 全原生的 Tessera 桌面（用户 2026-10-22 要求"所有东西必须跑原生"）：替代 shell.js 的导航状态机。
 * 层次（从下到上）：FaceView 表盘 → TileGridView 开始屏 → 通知中心 → 设置 / 编辑页 → 搜索 → 横幅。
 * 动效时长和曲线同 shell.css：进场 300ms (0.1, 0.9, 0.2, 1)、离场 240ms (0.7, 0, 0.9, 0.3)、拖动松手补完 200ms。
 */
final class NativeShell implements Host.Listener {
    static final int FACE = 0, START = 1, NOTIFS = 2, PAGE = 3;
    private static final long EASE_OUT = 350, EASE_IN = 250, SETTLE = 250;   // WP：进来 / 滑入 0.35 秒，出去 / 滑出 0.25 秒
    private static final float FLING = 0.35f, COMMIT = 0.35f;

    private final MainActivity act;
    private final FrameLayout root;
    private final FaceView face;
    private final Bridge bridge;
    final ShellData data;
    private final TileGridView grid;
    private final ActionCenter notifs;          // 通知中心 + 控制中心（下拉面板）
    private ActionCenter center;                // 同一个对象；给 SysControls 的回调用（构造时 notifs 还没赋值）
    private final SysControls sys;
    private boolean openedFromCenter;           // 从通知中心点开了应用：回来时回到表盘
    private final PageView page;
    private final AppList search;               // 应用列表（开始屏往左滑进来；以前的搜索页）
    private boolean dragNew;                    // 从应用列表拖过来的这块是不是刚钉上的（取消时要取下）
    private String dragId;
    private final ToastView toast;
    private final TileGridView previewTile;
    private final HeartRate heart;
    private final FaceGallery gallery;

    private int nav = FACE;
    private String pageKind = "", editId = "";
    private long foregroundAt;
    private boolean resumed;
    private final HashMap<Object, ValueAnimator> anims = new HashMap<>();
    private long lastTilesRefresh;

    NativeShell(MainActivity act, FrameLayout root, FaceView face, Bridge bridge) {
        this.act = act;
        this.root = root;
        this.face = face;
        this.bridge = bridge;
        data = new ShellData(act, bridge);
        data.reloadApps();
        data.reloadNotifs();
        data.steps = bridge.steps();
        TileGridView.iconSource = data::iconOf;

        grid = new TileGridView(act);
        grid.setVisibility(View.GONE);
        grid.setCallback(new TileGridView.Callback() {
            @Override public void onLaunch(String pkg, String cls, String label) { bridge.launchTurn(pkg, cls); }
            @Override public void onExitToFace() { grid.setVisibility(View.GONE); grid.resetTransient(); setNav(FACE, false); }
            @Override public void onReorder(String idsJson) {
                try {
                    JSONArray a = new JSONArray(idsJson);
                    data.order.clear();
                    for (int i = 0; i < a.length(); i++) data.order.add(a.getString(i));
                    data.saveLayout();
                } catch (Exception ignored) { }
            }
            @Override public void onPullEnd(boolean commit) { if (commit) leaveStart(); }
            @Override public boolean onLiftToFace(String pkg, String kind, String size, int bg, android.graphics.Bitmap look,
                                                  float x, float y, float w, float h, float fx, float fy, float radius, float scale) {
                return liftToFace(pkg, kind, size, bg, look, x, y, w, h, fx, fy, radius, scale);
            }
            @Override public void onFaceDrag(float x, float y) { face.cards().dragTo(x, y); }
            @Override public void onFaceDrop(float x, float y, boolean cancel) { dropOnFace(x, y); }
            @Override public void onBack() { leaveStart(); }
            @Override public void onOpenPage(String kind, String id) { openPage(kind, id); }
            @Override public void onSearch() { openSearch(); }
        });

        sys = new SysControls(act, bridge, st -> { if (center != null) center.onState(st); });
        notifs = new ActionCenter(act, data.theme(), grid, sys);
        notifs.appIcon = data::iconOf;
        notifs.setVisibility(View.GONE);
        center = notifs;
        notifs.setListener(new ActionCenter.Listener() {
            @Override public void onClose() { leaveNotifs(); }
            private float pullBase;                          // 开始推的时候面板在哪（正在落下来时按住：从这里接着跟手）
            private boolean pulling;
            @Override public void onPull(float dyPx) {
                if (!pulling) { pulling = true; pullBase = 0f; if (anims.containsKey(notifs)) { pullBase = notifs.getTranslationY(); cancel(notifs); } }
                poseNotifs(Math.min(0f, pullBase + dyPx));
            }
            @Override public void onPullEnd(float dyPx, float vyPx) {
                // 面板按系统密度画，回来的是屏幕像素：换回"屏宽 200"的设计单位，阈值和表盘下拉那边一样
                float dy = (pullBase + dyPx) / scale(), vy = vyPx / scale();
                pulling = false; pullBase = 0f;
                float H = viewH(), v = -vy;
                boolean commit = v >= FLING ? -dy > 8f : v <= -FLING ? false : -dy > H * COMMIT;
                if (commit) leaveNotifsFrom(SETTLE, Curves.NC_SETTLE); else animate(notifs, notifs.getTranslationY(), 0f, SETTLE, Curves.NC_SETTLE, NativeShell.this::poseNotifs, null);
            }
            @Override public void onOpen(ShellData.Notif n) { openFromCenter(n); }
            @Override public void onAction(ShellData.Notif n, int index) { bridge.notifAction(n.key, index); }
            @Override public void onDismiss(ShellData.Notif n) { bridge.notifDismiss(n.key); }
            @Override public void onClearAll() { bridge.notifClear(); }
            @Override public void onAllowAccess() { bridge.openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"); }
            @Override public void onSettings(String action) { bridge.openSettings(action); }
        });

        page = new PageView(act, data.theme(), grid);
        page.setVisibility(View.GONE);
        page.setListener(this::closePage);

        search = new AppList(act, grid);
        search.setCallback(new AppList.Callback() {
            @Override public void onLaunch(TileGridView.App app) {
                if ("settings".equals(app.kind)) { search.dismiss(); openPage("settings", app.id); return; }
                bridge.launchTurn(app.pkg, app.cls);
                act.getWindow().getDecorView().postDelayed(search::dismiss, 400);
            }
            @Override public void onClosed() { grid.returnFromApp(); }
            // 列表里长按拿起：没钉的先钉到末尾，开始屏翻回来，手里这块交给开始屏继续跟手；松手的位置由开始屏排（onReorder 存顺序）
            @Override public boolean onDragToStart(TileGridView.App app, float xPx, float yPx) {
                dragId = app.id;
                dragNew = !data.isPinned(app.id);
                if (dragNew) data.pin(app.id);
                refreshTiles();
                grid.returnFromApp();
                boolean ok = grid.beginExternal(app.id, xPx, yPx);
                if (!ok && dragNew) { data.unpin(app.id); refreshTiles(); }
                return ok;
            }
            @Override public void onDragMove(float xPx, float yPx) { grid.externalMove(xPx, yPx); }
            @Override public void onPullToFace() { leaveListToFace(); }
            @Override public void onDragEnd(float xPx, float yPx, boolean cancel) {
                grid.externalDrop(xPx, yPx, cancel);
                if (cancel && dragNew) { data.unpin(dragId); refreshTiles(); }
                dragNew = false;
            }
        });

        toast = new ToastView(act, grid);
        toast.setRowHeight(face.panelsRowBottom());      // 和壁纸第一行卡片一样高，下沿对齐第一条黑缝
        toast.setListener(new ToastView.Listener() {
            @Override public void onOpen(ShellData.Notif n, int count) { if (count > 1) openNotifs(); else bridge.notifOpen(n.key); }
            @Override public void onAction(ShellData.Notif n, int index) { bridge.notifAction(n.key, index); }
            @Override public void onRect(float l, float t, float r, float b) {
                if (nav == FACE && b > 0) face.setToastBlur(l, t, r, b, 0); else face.setToastBlur(0, 0, 0, 0, 0);
            }
        });

        previewTile = new TileGridView(act);

        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(grid, full);
        root.addView(notifs, new FrameLayout.LayoutParams(full));
        root.addView(page, new FrameLayout.LayoutParams(full));
        root.addView(search, new FrameLayout.LayoutParams(full));
        root.addView(toast, new FrameLayout.LayoutParams(full));
        root.addView(face.ghostLayer(), new FrameLayout.LayoutParams(full));   // 拿着的卡片，最上面一层
        face.ghostLayer().setTranslationZ(2f);    // 交接时表盘临时抬到 Z=1 盖住开始屏，这层要更高（没有背景，不会有阴影）
        face.setOnTouchListener(new FaceTouch());
        gallery = new FaceGallery(act, face, new FaceGallery.Listener() {
            @Override public void onPick(int style) { setFaceStyle(style); }
            @Override public void onClosed() { updateHeart(); }
        });
        root.addView(gallery, root.indexOfChild(face) + 1, new FrameLayout.LayoutParams(full));
        heart = new HeartRate(act, bpm -> { data.heartRate = bpm; face.setCards(data.faceCards()); });
        face.setStyle(data.faceStyle);
        act.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        refreshAll();
        Host.add(this);
    }

    // ---------- 尺寸 ----------

    private float scale() { return root.getWidth() > 0 ? root.getWidth() / 200f : 2.4f; }

    private float viewH() { return root.getHeight() > 0 ? root.getHeight() / scale() : 200f; }

    private float hPx() { return root.getHeight() > 0 ? root.getHeight() : 480f; }

    // ---------- 数据刷新 ----------

    private void refreshAll() {
        Theme th = data.theme();
        root.setBackgroundColor(0xFF000000);
        grid.setJson(data.tiles().toString());
        lastTilesRefresh = System.currentTimeMillis();
        face.setActivity(data.activity());
        face.setFaceTheme(new Theme(true, data.accent));
        gallery.setTheme(new Theme(true, data.accent));
        refreshFace();
        notifs.theme = th;
        page.theme = th;
        rebuildNotifs();
        if (page.getVisibility() == View.VISIBLE) rebuildPage();
    }

    /** 数据表盘的卡片和即时活动。 */
    private void refreshFace() {
        face.setCards(data.faceCards());
        face.setLive(debugLive != null ? debugLive : data.faceLive());
        updateHeart();
    }

    /** 心率传感器只在数据表盘露出来、上面有心率卡片时开（测心率的灯很费电）。 */
    private void updateHeart() {
        if (heart == null) return;
        boolean need = resumed && nav == FACE && !gallery.isShowing() && face.style() == FaceStyles.DATA && data.cardIds.contains("heart");
        if (need) heart.start(); else heart.stop();
    }

    /** 调试：假心率 / 步数（和设计稿截图对比用）；-1 = 不改。 */
    void debugData(int hr, int steps) {
        if (hr >= 0) data.heartRate = hr;
        if (steps >= 0) { data.steps = steps; debugSteps = true; }
        debugRefresh();
    }

    /** 调试：假的即时活动（--es debugLive "计时器|04:32"，空串去掉）。 */
    private FaceStyles.Live debugLive;

    void debugLive(String spec) {
        if (spec == null || spec.isEmpty()) debugLive = null;
        else {
            String[] p = spec.split("\\|");
            debugLive = new FaceStyles.Live("debug", "timer", p[0], p.length > 1 ? p[1] : "", java.util.Arrays.asList("煮蛋", "剩余"));
        }
        refreshFace();
    }

    /** 调试：直接设数据表盘上的卡片。 */
    void debugCards(String csv) {
        data.cardIds.clear();
        if (csv != null) for (String id : csv.split(",")) if (ShellData.validCard(id.trim()) && data.cardIds.size() < 2) data.cardIds.add(id.trim());
        data.saveCards();
        face.setCards(data.faceCards());
        updateHeart();
    }

    private boolean debugSteps;               // 调试假步数期间，计步器回调不覆盖

    void debugRefresh() { face.setCards(data.faceCards()); face.setStyle(face.style()); face.invalidateFace(); }

    /** 换表盘（画廊选中 / 调试入口），记住选择。 */
    void setFaceStyle(int s) {
        if (s < 0 || s >= FaceStyles.COUNT) return;
        data.faceStyle = s;
        data.saveFaceStyle();
        face.setStyle(s);
        updateHeart();
    }

    /** 长按表盘：Cover Flow 换表盘。 */
    private void openGallery() {
        if (nav != FACE) return;
        toast.dismiss();
        gallery.open(face.style());
        updateHeart();
    }

    /** 设置导进来之后整体刷新一遍。 */
    void reload() { refreshAll(); }

    private void refreshTiles() {
        grid.setJson(data.tiles().toString());
        lastTilesRefresh = System.currentTimeMillis();
    }

    @Override
    public void onEvent(String name, String json) {
        switch (name) {
            case "notifs":
                data.reloadNotifs();
                refreshTiles();
                face.setActivity(data.activity());
                refreshFace();
                rebuildNotifs();
                break;
            case "notify": {
                ShellData.Notif n = ShellData.parseNotif(json);
                if (n != null && resumed) {
                    toast.show(n, data.theme(), nav == FACE);
                    bridge.vibrate(30);
                }
                break;
            }
            default:
        }
    }

    /** 步数：每分钟最多刷新一次开始屏（免得频繁重排），表盘活动随时更新。 */
    void onSteps(long count) {
        if (debugSteps) return;
        data.steps = count;
        face.setActivity(data.activity());
        face.setCards(data.faceCards());
        if (System.currentTimeMillis() - lastTilesRefresh > 60000) refreshTiles();
    }

    void onTimeTick() {
        refreshTiles();
        face.setActivity(data.activity());
        refreshFace();
        notifs.tick();
        page.invalidate();
    }

    void onResume() {
        resumed = true;
        foregroundAt = System.currentTimeMillis();
        data.reloadApps();
        data.reloadNotifs();
        if (!debugSteps) data.steps = bridge.steps();
        refreshAll();
        // 从应用回来：卡片转门翻回来。要等应用关闭的窗口动画走完（否则翻回的过程全被它盖住），450ms 兜底
        if (nav == START && grid.getVisibility() == View.VISIBLE) { pendingReturn = true; root.removeCallbacks(returnNow); root.postDelayed(returnNow, 450); }
    }

    private boolean pendingReturn;
    private final Runnable returnNow = this::returnFromAppNow;

    private void returnFromAppNow() {
        if (!pendingReturn) return;
        pendingReturn = false;
        if (nav == START && grid.getVisibility() == View.VISIBLE) grid.returnFromApp();
    }

    /** 窗口转场（应用关闭）结束。 */
    void onEnterAnimationComplete() { root.removeCallbacks(returnNow); returnNow.run(); }

    void onPause() {
        resumed = false; heart.stop(); pendingReturn = false; root.removeCallbacks(returnNow); toast.hideNow();
        // 从通知中心点开的应用起来了：面板已经转门翻走，悄悄收起来，回来时看到的是表盘
        if (openedFromCenter) { openedFromCenter = false; goFaceNow(); }
    }

    void onDestroy() { Host.remove(this); sys.stop(); }

    /** 熄屏：抬腕看到的永远是表盘。 */
    void onScreenOff() { toast.hideNow(); goFaceNow(); }

    /** HOME：刚从别的应用回来 → 表盘；已经在桌面里：表盘 → 开始屏，其他 → 表盘。 */
    void onHome() {
        toast.dismiss();
        if (gallery.isOpen()) { gallery.close(); return; }
        if (System.currentTimeMillis() - foregroundAt < 800 || nav != FACE) goFaceNow();
        else openStart();
    }

    boolean onBack() {
        if (toast.showing()) { toast.dismiss(); return true; }
        if (search.isOpen()) { search.back(); return true; }
        if (gallery.isOpen()) { gallery.close(); return true; }
        if (nav == PAGE) { closePage(); return true; }
        if (nav == NOTIFS) { leaveNotifs(); return true; }
        if (nav == START && grid.rewindLeave()) return true;   // 往应用列表翻走的半路：原路翻回来
        if (nav == START) { leaveStart(); return true; }
        return true;
    }

    boolean onCrown(int delta) {
        if (gallery.isOpen()) { gallery.crown(delta); return true; }
        if (nav == START && grid.getVisibility() == View.VISIBLE) { grid.crown(delta); return true; }
        if (nav == NOTIFS) { notifs.crown(delta); return true; }
        return nav != FACE;
    }

    // ---------- 导航 ----------

    private void setNav(int n, boolean animate) {
        nav = n;
        if (n != FACE) gallery.dismiss();
        if (n == FACE) { leaveSeq++; faceFalling = false; cancel(face); face.setTranslationY(0f); face.setTranslationZ(0f); }
        toast.setOnFace(n == FACE);
        if (n == FACE) {
            face.setShown(true);
            face.setSink(0f, animate ? (int) EASE_OUT : 0);
        } else {
            face.setSink(1f, animate ? (int) EASE_OUT : 0);
            hideFaceLater();
        }
        updateHeart();
    }

    /** 被盖住 350ms 后停止走秒（省电）。 */
    private void hideFaceLater() {
        root.postDelayed(() -> { if (nav != FACE && grid.getTranslationY() == 0f && notifs.getTranslationY() == 0f) face.setShown(false); }, 350);
    }

    /** 直接回表盘（不播动画）。 */
    private void goFaceNow() {
        cancel(grid); cancel(notifs); cancel(page);
        grid.setVisibility(View.GONE); grid.resetTransient();
        showCenter(false); notifs.setTranslationY(0); notifs.reset();
        page.setVisibility(View.GONE);
        page.setLayerType(View.LAYER_TYPE_NONE, null); page.setRotationY(0); page.setAlpha(1f);
        search.dismiss();
        face.setBlur(0);
        setNav(FACE, false);
    }

    /** 表盘 → 开始屏（HOME、点按）：整层从下面推上来，每行从深处浮上来。 */
    private void openStart() { enterStart(); }

    /**
     * 表盘 → 应用列表（设计稿"上滑进应用列表 · 转门"）：表盘接着往上走出顶部（260ms (0.2, 0.6, 0.3, 1)），
     * 130ms 后磁贴从屏幕左边缘像转门一样翻进来。表盘在磁贴上面（DOM 顺序），走完就停在屏幕外。
     */
    private void enterStart() {
        nav = START;
        toast.setOnFace(false);
        gallery.dismiss();
        updateHeart();
        // setSink 会把表盘的 translationY 设回下沉位置（0）：先记住手指松开时表盘在哪，清掉下沉再放回去，
        // 否则表盘会先瞬间跳回原位、再从头往上飞（用户 2026-10-24 报的"上滑停住会抽一下"）
        float from = face.getTranslationY();
        face.setSink(0f, 0);
        face.setTranslationY(from);
        face.setTranslationZ(1f);
        animate(face, from, -hPx(), EASE_IN, Curves.FACE_UP, face::setTranslationY, () -> { face.setTranslationZ(0f); if (nav != FACE) face.setShown(false); });
        cancel(grid);
        grid.resetTransient();
        grid.scrollToTop();
        grid.setVisibility(View.VISIBLE);
        grid.faceTurnIn();
    }

    /** 开始屏 → 表盘（返回、左边缘右滑）：整层推下去。 */
    private void leaveStart() {
        if (grid.getVisibility() != View.VISIBLE || grid.faceTurning()) { if (grid.getVisibility() != View.VISIBLE) setNav(FACE, false); return; }
        cancel(grid);
        grid.setEnterY(0);
        long last = grid.faceTurnOut();
        face.setShown(true);
        face.setSink(0f, 0);
        face.setBlur(0);
        face.setTranslationZ(1f);
        cancel(face);
        face.setTranslationY(-hPx());
        final long me = ++leaveSeq;
        root.postDelayed(() -> {
            if (me != leaveSeq || nav != START) return;
            faceFalling = true;
            animate(face, -hPx(), 0f, EASE_OUT, Curves.FACE_DOWN, face::setTranslationY, () -> {
                faceFalling = false;
                if (me != leaveSeq) return;
                face.setTranslationZ(0f);
                grid.setVisibility(View.GONE);
                grid.resetTransient();
                setNav(FACE, false);
            });
        }, Math.round((last + 200) * TileGridView.slow));
    }

    private long leaveSeq;
    private boolean faceFalling;                       // 回表盘时表盘正从上面落下来（按住可以接住）

    /** 表盘正从上面落回来时按住它：开始屏 / 应用列表直接收掉，表盘就停在手指下面（不跳），接着跟手。 */
    private void landFace() {
        ++leaveSeq;
        float y = face.getTranslationY();
        cancel(face);
        faceFalling = false;
        search.dismiss();
        grid.setVisibility(View.GONE);
        grid.resetTransient();
        nav = FACE;
        toast.setOnFace(true);
        face.setShown(true);
        face.setSink(0f, 0);                           // setSink 会把 translationY 放回原位：先清下沉，再放回手指下面
        face.setTranslationY(y);
        face.setTranslationZ(0f);
        updateHeart();
    }

    /** applist 顶上往下拉 → 表盘：和开始屏回表盘一样——内容从上往下按行翻走，最晚那块 + 200ms 后表盘从上面落下来（340ms）。 */
    private void leaveListToFace() {
        long last = search.faceTurnOut();
        face.setShown(true);
        face.setSink(0f, 0);
        face.setBlur(0);
        face.setTranslationZ(1f);
        cancel(face);
        face.setTranslationY(-hPx());
        final long me = ++leaveSeq;
        root.postDelayed(() -> {
            if (me != leaveSeq || nav != START) return;
            faceFalling = true;
            animate(face, -hPx(), 0f, EASE_OUT, Curves.FACE_DOWN, face::setTranslationY, () -> {
                faceFalling = false;
                if (me != leaveSeq) return;
                face.setTranslationZ(0f);
                search.dismiss();
                grid.setVisibility(View.GONE);
                grid.resetTransient();
                setNav(FACE, false);
            });
        }, Math.round((last + 200) * TileGridView.slow));
    }

    // ---------- 从开始屏把磁贴拖到表盘上（用户 2026-10-24：拿起后贴着上沿停一下） ----------

    private boolean handoff, handoffFaceDown, handoffUp;
    private int handoffPrevStyle;

    /**
     * 开始屏交过来一块拿着的磁贴：开始屏按行翻走，表盘马上从上面落回来（设计稿：从应用列表拿起时不等，340ms），
     * 磁贴继续跟着手指（画在最上面一层）。不是数据表盘时先临时换成数据表盘（卡片只在它上面），没放成再换回去。
     */
    private boolean liftToFace(String pkg, String kind, String size, int bg, android.graphics.Bitmap look,
                               float x, float y, float w, float h, float fx, float fy, float radius, float scale) {
        String id = ShellData.cardIdFor(pkg, kind);
        FaceStyles.Card card = id == null ? null : data.card(id);
        if (card == null) return false;
        handoff = true; handoffFaceDown = false; handoffUp = false;
        handoffPrevStyle = face.style();
        if (face.style() != FaceStyles.DATA) face.setStyle(FaceStyles.DATA);
        face.cards().liftFromApps(card, look, size, bg, x, y, w, h, fx, fy, radius, scale);
        grid.faceTurnOut();
        face.setShown(true);
        face.setSink(0f, 0);
        face.setBlur(0);
        face.setTranslationZ(1f);
        cancel(face);
        animate(face, -hPx(), 0f, EASE_OUT, Curves.FACE_DOWN, face::setTranslationY, () -> { handoffFaceDown = true; finishHandoff(); });
        return true;
    }

    private void dropOnFace(float x, float y) {
        face.cards().dragTo(x, y);
        List<String> ids = face.cards().drop();
        if (ids != null) {
            data.cardIds.clear();
            data.cardIds.addAll(ids);
            data.saveCards();
            if (data.faceStyle != FaceStyles.DATA) setFaceStyle(FaceStyles.DATA);
        } else if (handoffPrevStyle != FaceStyles.DATA) {
            final int prev = handoffPrevStyle;
            root.postDelayed(() -> { if (nav == FACE || handoff) face.setStyle(prev); }, 240);   // 等放掉的那块缩完
        }
        handoffUp = true;
        finishHandoff();
    }

    private void finishHandoff() {
        if (!handoff || !handoffFaceDown || !handoffUp) return;
        handoff = false;
        face.setTranslationZ(0f);
        grid.setVisibility(View.GONE);
        grid.resetTransient();
        setNav(FACE, false);
    }

    // ---------- 通知中心 ----------

    private void rebuildNotifs() { notifs.setNotifs(data.listed(), bridge.notifAccess()); }

    /** 面板露出来时才监听系统开关（WLAN、蓝牙、音量……），收起来就停掉。 */
    private void showCenter(boolean on) {
        notifs.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) face.setBlur(0);
        if (on) sys.start(); else sys.stop();
    }

    /** 通知中心的位置（像素，0 = 到位，−H = 收在屏幕上面）：表盘跟着下沉，背后的磨砂跟着面板的下边缘（面板底色是半透明的）。 */
    private void poseNotifs(float px) {
        notifs.setTranslationY(px);
        float H = hPx(), bottom = Math.max(0f, Math.min(1f, (H + px) / H));
        face.setBlur(notifs.getVisibility() == View.VISIBLE ? bottom : 0f);
        face.setSink(bottom, 0);
    }

    /** 拉下来 / 横幅里点"N 条新消息" / 别的应用里点横幅回来：面板从顶上落下来，停在通知页。 */
    void openNotifs() {
        if (nav == NOTIFS && notifs.getVisibility() == View.VISIBLE) { rebuildNotifs(); return; }
        if (nav != FACE) goFaceNow();
        float from = notifs.getVisibility() == View.VISIBLE && anims.containsKey(notifs) ? notifs.getTranslationY() : -hPx();   // 正在收：从现在的位置落下来
        rebuildNotifs();
        notifs.reset();
        showCenter(true);
        setNav(NOTIFS, false);
        face.setShown(true);
        animate(notifs, from, 0f, EASE_OUT, Curves.NC_OPEN, this::poseNotifs, this::hideFaceLater);
    }

    private void leaveNotifs() { leaveNotifsFrom(EASE_IN, Curves.NC_CLOSE); }

    private void leaveNotifsFrom(long ms, Interpolator curve) {
        setNav(FACE, false);
        face.setShown(true);
        animate(notifs, notifs.getTranslationY(), -hPx(), ms, curve, this::poseNotifs,
                () -> { showCenter(false); notifs.setTranslationY(0); face.setSink(0, 0); });
    }

    /** 通知中心里点开一条：面板已经按行转门翻走，应用顺着同一方向翻进来；1.5 秒还没起来（通知没有界面）就翻回来。 */
    private void openFromCenter(ShellData.Notif n) {
        openedFromCenter = true;
        bridge.notifOpenTurn(n.key);
        root.postDelayed(() -> {
            if (openedFromCenter && act.hasWindowFocus() && nav == NOTIFS) { openedFromCenter = false; notifs.turnBack(); }
        }, 1500);
    }

    // ---------- 设置 / 编辑页（开始屏的磁贴按列翻完之后，页面绕左边翻进来） ----------

    private void openPage(String kind, String id) {
        pageKind = kind;
        editId = id;
        rebuildPage();
        page.scrollToTop();
        page.setVisibility(View.VISIBLE);
        nav = PAGE;
        page.setPivotX(0);
        page.setPivotY(hPx() / 2f);
        page.setCameraDistance(600f * scale() * act.getResources().getDisplayMetrics().density);
        // 整页转进来 + 淡入：动画期间挂硬件层，只画一次、之后只做合成（否则每帧整页离屏重画，掉帧）
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animate(page, 0f, 1f, EASE_OUT, Curves.PAGE_OPEN, k -> { page.setRotationY(80f * (1f - k)); page.setAlpha(1f); },    // WP ForwardIn：从里面 80°（右边在屏幕里）转平，一下出现；开始屏同时往外翻走
                () -> page.setLayerType(View.LAYER_TYPE_NONE, null));
    }

    private void closePage() {
        nav = START;
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animate(page, 0f, 1f, EASE_IN, Curves.PAGE_CLOSE, k -> { page.setRotationY(80f * k); page.setAlpha(k < 0.96f ? 1f : 0f); },    // WP BackwardOut：往里转到 80°，最后才消失；开始屏接着从外面翻回来
                () -> { page.setLayerType(View.LAYER_TYPE_NONE, null); page.setVisibility(View.GONE); page.setRotationY(0); page.setAlpha(1f); refreshTiles(); grid.turnIn(); });
    }

    private void rebuildPage() {
        if ("settings".equals(pageKind)) page.setItems("设置", settingsItems());
        else page.setItems("编辑", editItems());
    }

    private List<PageView.Item> settingsItems() {
        List<PageView.Item> l = new ArrayList<>();
        l.add(new PageView.Section("连接"));
        l.add(new PageView.Toggle("WLAN", bridge::wifi, on -> { bridge.setWifi(on); page.postDelayed(page::invalidate, 400); }));
        l.add(new PageView.Toggle("蓝牙", bridge::bluetooth, on -> { bridge.setBluetooth(on); page.postDelayed(page::invalidate, 400); }));
        l.add(new PageView.Toggle("勿扰", bridge::dnd, on -> {
            if (!bridge.setDnd(on)) bridge.openSettings("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS");
            page.postDelayed(page::invalidate, 400);
        }));
        l.add(new PageView.Section("显示"));
        l.add(new PageView.Row("亮度", () -> Math.round(bridge.brightness() * 100f / 255f) + "%", new String[] {"暗", "亮"}, i -> {
            int bri = Math.round(bridge.brightness() * 100f / 255f);
            int to = Math.max(5, Math.min(100, bri + (i == 0 ? -10 : 10)));
            if (!bridge.setBrightness(Math.round(to * 2.55f))) bridge.openSettings("android.settings.action.MANAGE_WRITE_SETTINGS");
            page.invalidate();
        }));
        l.add(new PageView.Toggle("深色", () -> data.dark, on -> { data.dark = on; data.saveLook(); refreshAll(); }));
        l.add(new PageView.Row("主题色", () -> data.theme().accentName(), new String[0], i -> { }));
        int[] colors = new int[Theme.ACCENTS.length];
        for (int i = 0; i < colors.length; i++) colors[i] = new Theme(data.dark, Theme.ACCENTS[i][0]).accent;
        l.add(new PageView.Swatches(colors, () -> {
            for (int i = 0; i < Theme.ACCENTS.length; i++) if (Theme.ACCENTS[i][0].equals(data.accent)) return i;
            return 0;
        }, i -> { data.accent = Theme.ACCENTS[i][0]; data.saveLook(); refreshAll(); }));
        l.add(new PageView.Section("系统"));
        l.add(new PageView.ListItem("flame", "soft", "性能", "省电 · 均衡 · 性能", null,
                () -> bridge.launch("com.android.settings", "com.android.settings.Settings$PerformanceProfileSettingsActivity")));
        l.add(new PageView.ListItem("settings", "soft", "更多设置", "系统设置", null, () -> bridge.openSettings("")));
        l.add(new PageView.ListItem("more", "soft", "Tessera", "0.1 · 原生", null, null));
        return l;
    }

    private static final String[][] SIZES = {{"s", "小"}, {"m", "中"}, {"w", "宽"}}, TONES = {{"accent", "重点"}, {"base", "普通"}, {"soft", "安静"}};

    private List<PageView.Item> editItems() {
        List<PageView.Item> l = new ArrayList<>();
        ShellData.App app = null;
        for (ShellData.App a : data.apps) if (a.id.equals(editId)) app = a;
        if (app == null) { l.add(new PageView.Empty("more", "应用已卸载")); return l; }
        final ShellData.App fa = app;
        String[] cfg = data.cfgFor(app);
        // 预览：用同一套规则生成这一块磁贴的数据，交给一个不挂在界面上的 TileGridView 画
        try {
            JSONObject all = data.tiles();
            JSONArray tiles = all.getJSONArray("tiles"), one = new JSONArray();
            for (int i = 0; i < tiles.length(); i++) if (tiles.getJSONObject(i).optString("id").equals(editId)) one.put(tiles.getJSONObject(i).put("back", JSONObject.NULL));
            all.put("tiles", one);
            previewTile.setJson(all.toString());
        } catch (Exception ignored) { }
        l.add(new PageView.Preview(previewTile));
        l.add(new PageView.Section("尺寸"));
        l.add(new PageView.Pills(new String[] {"小", "中", "宽"}, () -> idx(SIZES, data.cfgFor(fa)[0]), i -> setCfg(fa, SIZES[i][0], null)));
        l.add(new PageView.Section("明度"));
        l.add(new PageView.Pills(new String[] {"重点", "普通", "安静"}, () -> idx(TONES, data.cfgFor(fa)[1]), i -> setCfg(fa, null, TONES[i][0])));
        int accents = 0;
        for (ShellData.App a : data.apps) if (!a.id.equals(editId) && "accent".equals(data.cfgFor(a)[1])) accents++;
        if ("accent".equals(cfg[1]) && accents >= 2) l.add(new PageView.Note("每屏的重点最多 2 块"));
        l.add(new PageView.Button("完成", true, this::closePage));
        l.add(new PageView.Button("从开始屏取下", false, () -> { data.unpin(fa.id); closePage(); }));   // 取下后还在应用列表里
        return l;
    }

    private static int idx(String[][] t, String v) { for (int i = 0; i < t.length; i++) if (t[i][0].equals(v)) return i; return 0; }

    private void setCfg(ShellData.App a, String size, String tone) {
        String[] c = data.cfgFor(a);
        data.cfg.put(a.id, new String[] {size != null ? size : c[0], tone != null ? tone : c[1]});
        data.saveLayout();
        rebuildPage();
    }

    // ---------- 搜索 ----------

    /** 应用列表的条目：全部应用（钉没钉都在），设计稿有图标的用设计稿图标，其余用真实图标；方块底色一律 tile-soft（设计稿：应用列表全部 tile-soft）。 */
    private List<TileGridView.App> listApps() {
        Theme th = data.theme();
        List<TileGridView.App> out = new ArrayList<>();
        for (ShellData.App a : data.apps) {
            String glyph = ShellData.glyphName(a.pkg);
            out.add(new TileGridView.App(a.id, a.pkg, a.cls, "com.android.settings".equals(a.pkg) ? "settings" : "",
                    ShellData.label(a), glyph, glyph.isEmpty() ? a.icon : null, th.tileSoft, th.onTile));
        }
        return out;
    }

    /** 开始屏往左滑：磁贴按行转门翻走，应用列表（全部应用，按拼音排）翻进来。 */
    private void openSearch() {
        if (grid.getVisibility() != View.VISIBLE || search.isOpen()) return;
        search.setApps(listApps());
        grid.turnstileOutAll(search::open);
    }

    // ---------- 动画 ----------

    private interface FloatFn { void apply(float v); }

    private void animate(Object key, float from, float to, long ms, Interpolator curve, FloatFn fn, Runnable end) {
        cancel(key);
        ValueAnimator a = ValueAnimator.ofFloat(from, to).setDuration(Math.round(ms * TileGridView.slow));
        a.setInterpolator(curve);
        a.addUpdateListener(v -> fn.apply((float) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator x) { cancelled = true; }
            @Override public void onAnimationEnd(Animator x) { if (anims.get(key) == a) anims.remove(key); if (!cancelled && end != null) end.run(); }
        });
        anims.put(key, a);
        fn.apply(from);
        a.start();
    }

    private void cancel(Object key) {
        ValueAnimator a = anims.remove(key);
        if (a != null) a.cancel();
    }

    // ---------- 表盘上的手势 ----------

    /**
     * 上拉：开始屏跟着手指从下面推上来；下拉：通知中心跟着手指从上面落下来（同 shell.js 的 dragApply / dragRelease）。
     * 左右滑：换表盘样式（纯表盘 ↔ 带活动）；点按：先给图片表盘翻卡片，再看是不是点了即时活动；双指快速点：卡片模式。
     */
    private final class FaceTouch implements View.OnTouchListener {
        private float x0, y0;
        private long t0, twoAt;
        private int drag;           // 0 没拖，-1 上拉开始屏，1 下拉通知中心
        private int grab;           // 按下时接住了正在动的东西：-1 表盘，1 通知中心（从它现在的位置跟手，没有死区）
        private boolean prepared;   // 按下时已经备好通知列表和磨砂底图
        private float base;         // 接住时它在哪（设计单位）
        private float fireDy;       // 判定成竖拖那一刻已经走过的距离：表盘从这里起跟手，不一下跳 12 像素
        private boolean two, fired, held;
        private final Runnable hold = () -> { held = true; openGallery(); };   // 长按 400ms（--hold-ms）换表盘
        // 数据表盘：按在卡片上长按 = 拿起这张卡（拖动换位 / 拖出卡片区放掉）；按在即时活动上横滑 = 划走它
        private String cardId;
        private boolean lifted, onLive, liveDrag;
        private float lastX, lastY;
        private final Runnable cardHold = () -> {
            if (cardId == null) return;
            lifted = true;
            face.cards().lift(cardId, lastX, lastY);
        };
        private final float[] ys = new float[12];
        private final long[] ts = new long[12];
        private int n;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int am = e.getActionMasked();
            if (am == MotionEvent.ACTION_DOWN) { grab = 0; if (nav != FACE && !canGrab()) return false; }
            else if (nav != FACE && grab == 0) return false;
            // 用屏幕坐标：上滑时表盘自己跟着手指走，局部坐标会把位移吃掉一半
            float s = scale(), x = e.getX() / s, y = (e.getY() + v.getTranslationY()) / s, H = viewH();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = x; y0 = y; t0 = e.getEventTime(); drag = 0; two = false; fired = false; n = 0;
                    // 在动的东西按住就接住（用户 2026-10-25："添加打断动画……保证跟手"）
                    if (nav != FACE && faceFalling && anims.containsKey(face)) landFace();
                    if (nav == FACE && anims.containsKey(face)) {
                        cancel(face); grab = -1; base = Math.min(0f, face.getTranslationY() / s);
                    } else if (notifs.getVisibility() == View.VISIBLE && anims.containsKey(notifs)) {
                        cancel(notifs); grab = 1; base = Math.max(-H, Math.min(0f, notifs.getTranslationY() / s));
                    }
                    if (grab != 0) {
                        drag = grab; fired = true; fireDy = 0f;
                        held = false; lifted = false; liveDrag = false; onLive = false; cardId = null;
                        face.removeCallbacks(hold); face.removeCallbacks(cardHold);
                        record(e.getEventTime(), y);
                        return true;
                    }
                    lastX = x; lastY = y;
                    held = false; lifted = false; liveDrag = false; onLive = false; cardId = null;
                    face.removeCallbacks(hold); face.removeCallbacks(cardHold);
                    if (face.style() == FaceStyles.DATA) {
                        onLive = face.cards().liveAt(x, y);
                        if (!onLive) cardId = face.cards().cardAt(x, y);
                    }
                    // 往下拉的第一帧不现做：通知列表和磨砂底图趁手指刚按下（还没动）先备好
                    if (notifs.getVisibility() != View.VISIBLE) { rebuildNotifs(); notifs.reset(); face.prepareBlur(); prepared = true; }
                    if (cardId != null) face.postDelayed(cardHold, 400);
                    else if (!onLive) face.postDelayed(hold, 400);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (lifted) return true;
                    face.removeCallbacks(hold); face.removeCallbacks(cardHold);
                    if (e.getPointerCount() == 2) { two = true; twoAt = e.getEventTime(); }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    lastX = x; lastY = y;
                    if (grab != 0) {                            // 接住的：从接住的位置跟着手指走
                        record(e.getEventTime(), y);
                        float d = y - y0;
                        if (grab == -1) face.setTranslationY(Math.min(0f, base + d) * s);
                        else poseNotifs(Math.max(-H, Math.min(0f, base + d)) * s);
                        return true;
                    }
                    if (lifted) { face.cards().dragTo(x, y); return true; }
                    if (held || two) return true;
                    float dx = x - x0, dy = y - y0;
                    if (Math.hypot(dx, dy) > 8f) { face.removeCallbacks(hold); face.removeCallbacks(cardHold); }   // --hold-slop 8px
                    if (onLive && !fired && (liveDrag || (Math.abs(dx) > 8f && Math.abs(dx) > Math.abs(dy)))) {
                        liveDrag = true;
                        face.cards().liveMove(dx);
                        return true;
                    }

                    if (!fired && Math.abs(dy) > 12f && Math.abs(dy) > Math.abs(dx)) { fired = true; fireDy = dy; }
                    if (!fired) return true;
                    record(e.getEventTime(), y);
                    int dir = dy < 0 ? -1 : 1;
                    if (dir != drag) {                          // 换方向：把另一边收回去
                        if (drag == -1) { cancel(face); face.setTranslationY(0f); }
                        if (drag == 1) showCenter(false);
                        drag = dir;
                        face.setShown(true);
                        if (drag == 1) { if (!prepared) { rebuildNotifs(); notifs.reset(); } prepared = false; showCenter(true); }
                    }
                    if (drag == -1) face.setTranslationY(Math.min(0f, dy - Math.min(0f, fireDy)) * s);   // 像掀起锁屏：表盘跟着手指往上走，底下是黑的
                    else poseNotifs((-H + Math.min(H, dy)) * s);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    face.removeCallbacks(hold); face.removeCallbacks(cardHold);
                    if (lifted) {
                        lifted = false;
                        java.util.List<String> ids = face.cards().drop();
                        if (ids != null) {
                            data.cardIds.clear();
                            data.cardIds.addAll(ids);
                            data.saveCards();
                            updateHeart();
                        }
                        return true;
                    }
                    if (held) { held = false; return true; }
                    if (liveDrag) {
                        liveDrag = false;
                        String key = face.cards().liveKey();
                        if (face.cards().liveUp() && key != null) {
                            data.dismissedLive = key;
                            if (debugLive != null && key.equals(debugLive.key)) debugLive = null;
                        }
                        return true;
                    }
                    if (two) {
                        if (!cancel && e.getEventTime() - twoAt < 600) face.togglePanelsMode();
                        two = false;
                        return true;
                    }
                    float dx = x - x0, dy = y - y0;
                    if (drag != 0) { release(dy, cancel ? 0 : velocity(e.getEventTime()), H); return true; }
                    if (cancel) return true;
                    if (face.style() == FaceStyles.SECONDS && Math.abs(dx) > 36f && Math.abs(dx) > Math.abs(dy)) {   // 秒轨表盘：横滑切"带活动"
                        data.faceIndex = (data.faceIndex + 1) % 2;
                        data.saveFace();
                        face.setActivity(data.activity());
                        return true;
                    }
                    if (Math.abs(dx) < 8f && Math.abs(dy) < 8f) {
                        if (face.hitPanel(x, y)) { face.tapPanel(x, y); return true; }
                        String key = data.liveKey();
                        if (key != null && y > 110f) bridge.notifOpen(key);
                    }
                    return true;
                }
            }
            return true;
        }

        private void release(float dy, float vy, float H) {
            boolean up = drag == -1;
            float s = scale();
            if (up) {
                // 设计稿 onUp：表盘往上走了超过 50，或超过 12 且往上快甩（< -0.4 设计像素/ms）就进开始屏；否则表盘落回去
                // （按表盘现在的位置算：半路接住的也一样）
                float at = face.getTranslationY() / s;
                boolean go = at < -50f || (at < -12f && vy < -0.4f);
                if (go) enterStart();
                else animate(face, face.getTranslationY(), 0f, EASE_OUT, Curves.FACE_DOWN, face::setTranslationY, null);
            } else {
                float shown = H + notifs.getTranslationY() / s;   // 面板露出来多少（半路接住的也按现在的位置算）
                boolean commit = vy >= FLING ? shown > 8f : vy <= -FLING ? false : shown > H * COMMIT;
                if (!commit && nav != FACE) setNav(FACE, false);
                if (commit) {
                    setNav(NOTIFS, false);
                    animate(notifs, notifs.getTranslationY(), 0f, SETTLE, Curves.NC_SETTLE, NativeShell.this::poseNotifs, NativeShell.this::hideFaceLater);
                } else {
                    animate(notifs, notifs.getTranslationY(), -hPx(), SETTLE, Curves.NC_SETTLE, NativeShell.this::poseNotifs,
                            () -> { showCenter(false); face.setSink(0, 0); });
                }
            }
            drag = 0; grab = 0;
        }

        /** 按下时能不能接住正在动的东西：表盘正落回来、通知中心正在落下 / 收起。 */
        private boolean canGrab() {
            return (faceFalling && anims.containsKey(face)) || (notifs.getVisibility() == View.VISIBLE && anims.containsKey(notifs));
        }

        private void record(long t, float y) {
            if (n == ys.length) { System.arraycopy(ys, 1, ys, 0, n - 1); System.arraycopy(ts, 1, ts, 0, n - 1); n--; }
            ys[n] = y; ts[n] = t; n++;
        }

        private float velocity(long now) {
            if (n < 2 || now - ts[n - 1] > 80) return 0f;
            int i = n - 1;
            while (i > 0 && ts[n - 1] - ts[i - 1] <= 100) i--;
            long dt = ts[n - 1] - ts[i];
            return dt > 0 ? (ys[n - 1] - ys[i]) / dt : 0f;
        }
    }
}
