package com.tessera.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 其他应用上方的一层：右上角状态时间 + 通知横幅。
 * 横幅就是桌面里那个 ToastView（同一套排版、同一份用户钉死的像素位置），在别的应用上用实色（拿不到别的应用的画面做磨砂）。
 * 系统自己的弹出通知（SystemUI 的 heads-up）由这里关掉，免得两套横幅一起出来（用户 2026-10-24："toast 并不是真正替换原通知样式"）。
 */
public class OverlayService extends Service implements Host.Listener {
    private static final String CHANNEL = "overlay";
    /** 平时只占屏幕上方这一条（设计像素 44 = --status-h），不接收触摸。 */
    private static final int IDLE_DESIGN_HEIGHT = 44;
    private static final String HEADS_UP = "heads_up_notifications_enabled";

    private WindowManager wm;
    private FrameLayout root;
    private TimeView timeView;
    private ToastView toast;
    private WindowManager.LayoutParams lp;
    private float scale;
    private static float rowH = -1f;                 // 横幅最低高度 = 壁纸第一行卡片的下沿（同桌面里的横幅）
    private final Handler main = new Handler(Looper.getMainLooper());

    static void start(Context context) {
        quietSystemHeadsUp(context);
        if (!Settings.canDrawOverlays(context)) return;
        context.startForegroundService(new Intent(context, OverlayService.class));
    }

    /** 关掉 SystemUI 的弹出通知：横幅只由 Tessera 画（要 WRITE_SECURE_SETTINGS，adb 授予过）。 */
    static void quietSystemHeadsUp(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), HEADS_UP, 1) != 0)
                Settings.Global.putInt(c.getContentResolver(), HEADS_UP, 0);
        } catch (Throwable t) {
            Log.w("Tessera", "heads-up off failed", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        quietSystemHeadsUp(this);
        wm = getSystemService(WindowManager.class);
        scale = getResources().getDisplayMetrics().widthPixels / 200f;
        buildViews();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Math.round(IDLE_DESIGN_HEIGHT * scale),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("Tessera Overlay");
        wm.addView(root, lp);
        root.setVisibility(Host.shellForeground ? View.GONE : View.VISIBLE);
        Host.add(this);
        tick();
    }

    private int dp(float design) { return Math.round(design * scale); }

    private void buildViews() {
        root = new FrameLayout(this);

        // 右上角时间：和桌面里的页面同一条规则（笔画离屏幕边缘 30 屏幕像素、离上边和右边一样远，落在夹角中心）
        timeView = new TimeView(this);
        root.addView(timeView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(IDLE_DESIGN_HEIGHT)));

        // 横幅：借一个不挂在界面上的 TileGridView 画设计稿图标（同桌面）
        toast = new ToastView(this, new TileGridView(this));
        toast.setListener(new ToastView.Listener() {
            @Override public void onOpen(ShellData.Notif n, int count) {
                if (count > 1) openShellNotifs(); else NotifService.open(n.key);
            }
            @Override public void onAction(ShellData.Notif n, int index) { NotifService.action(n.key, index); }
            @Override public void onRect(float l, float t, float r, float b) {
                if (b <= 0f) bannerGone();
            }
        });
        root.addView(toast, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** 右上角的时间（Inter 13，带一圈淡阴影，压在任何应用上都看得清）。 */
    private static final class TimeView extends View {
        private final android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final SafeEdge.Corner corner = new SafeEdge.Corner(paint, true);
        private String text = "";

        TimeView(Context c) {
            super(c);
            paint.setTypeface(Fonts.sans(c.getAssets(), 400));
            paint.setTextSize(13f);
            paint.setShadowLayer(1.2f, 0f, 0f, 0xCC000000);
        }

        void setText(CharSequence t) { text = String.valueOf(t); invalidate(); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (getWidth() <= 0 || text.isEmpty()) return;
            float sc = getWidth() / 200f;
            c.save();
            c.scale(sc, sc);
            corner.layout(text, sc, 200f, getResources().getDisplayMetrics().heightPixels / sc);
            corner.draw(c, 0xFFF2F4F6);
            c.restore();
        }
    }

    /** "N 条新消息"：回到 Tessera 并拉下通知中心。 */
    private void openShellNotifs() {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("openNotifs", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Throwable t) { Log.w("Tessera", "open notifs", t); }
    }

    private void tick() {
        if (timeView != null) timeView.setText(ShellData.timeText(System.currentTimeMillis()));   // 和桌面里一样：24 小时制（设计规定）
        main.postDelayed(this::tick, 15000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        Host.remove(this);
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (IllegalArgumentException ignored) { }
            root = null;
        }
        super.onDestroy();
    }

    @Override
    public void onEvent(String name, String json) {
        if (root == null) return;
        if ("shell".equals(name)) {
            root.setVisibility(Host.shellForeground ? View.GONE : View.VISIBLE);
            if (Host.shellForeground) toast.hideNow();
        } else if ("notify".equals(name)) {
            showBanner(json);
        }
    }

    private void showBanner(String json) {
        if (json == null || Host.shellForeground) return;
        ShellData.Notif n = ShellData.parseNotif(json);
        if (n == null) return;
        if (rowH < 0f) rowH = new PanelsFace(this).firstRowBottom() * 200f;
        toast.setRowHeight(rowH);
        toast.show(n, ShellData.loadTheme(this), false);
        timeView.setVisibility(View.INVISIBLE);
        // 窗口改成横幅那么高、接收触摸（横幅在窗口里从顶边后面落下来）
        lp.height = (int) Math.ceil(toast.bannerH() * scale) + 1;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(root, lp); } catch (IllegalArgumentException ignored) { }
        vibrate(30);
    }

    /** 横幅收完了：窗口缩回顶上一条、不再挡触摸，时间回来。 */
    private void bannerGone() {
        if (lp == null || (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) return;
        timeView.setVisibility(View.VISIBLE);
        lp.height = Math.round(IDLE_DESIGN_HEIGHT * scale);
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(root, lp); } catch (IllegalArgumentException ignored) { }
    }

    private void vibrate(int ms) {
        android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(1, ms), android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Tessera 状态层", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Tessera")
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }
}
