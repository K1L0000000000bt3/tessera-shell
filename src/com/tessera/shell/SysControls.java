package com.tessera.shell;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * 控制中心背后的系统开关：WLAN、蓝牙、勿扰、位置、静音、手电筒、亮度、音量、电量、正在播放的媒体。
 * 读写系统状态全是跨进程调用（一次十几毫秒），全放在后台线程 "tessera-sys"：界面只拿 State 快照，
 * 否则拉下面板、切页那一下主线程会卡住掉帧（用户 2026-10-24 反馈"滑动有点卡"）。
 * 只在面板露出来的时候 start() 监听系统广播，状态一变重新拍一张快照交回主线程。
 */
final class SysControls {
    interface Listener { void onState(State s); }

    /** 某一刻的系统状态（后台线程拍，主线程用）。 */
    static final class State {
        int wifi, bt, ringer, bat = -1, batState, vol, volMax = 1;   // wifi / bt：0 关 1 正在打开 2 开 3 已连接 4 正在关闭；ringer：0 响铃 1 震动 2 静音
        boolean dnd, loc, torch, hasTorch, media, playing;
        float bright;                                                // 0..1
        String mediaTitle = "", mediaArtist = "", mediaApp = "";
    }

    private static final String TAG = "Tessera";
    private final Context ctx;
    private final Bridge bridge;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final AudioManager audio;
    private final CameraManager camera;
    private volatile String torchId;
    private volatile boolean torchOn;
    private boolean started;
    private MediaController media;             // 只在 worker 线程上碰
    private MediaSessionManager sessions;

    SysControls(Context ctx, Bridge bridge, Listener listener) {
        this.ctx = ctx;
        this.bridge = bridge;
        this.listener = listener;
        audio = ctx.getSystemService(AudioManager.class);
        camera = ctx.getSystemService(CameraManager.class);
        HandlerThread t = new HandlerThread("tessera-sys");
        t.start();
        worker = new Handler(t.getLooper());
        worker.post(() -> {
            try {
                if (camera != null) for (String id : camera.getCameraIdList()) {
                    Boolean flash = camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) { torchId = id; break; }
                }
                if (torchId != null) camera.registerTorchCallback(torch, worker);   // 一直挂着：手电筒可能被别的应用开关
            } catch (Throwable e) {
                Log.w(TAG, "torch probe failed", e);
                torchId = null;
            }
        });
    }

    private final CameraManager.TorchCallback torch = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) { if (id.equals(torchId)) { torchOn = enabled; refresh(); } }
        @Override public void onTorchModeUnavailable(String id) { if (id.equals(torchId)) { torchOn = false; refresh(); } }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> { pickMedia(list); refresh(); };

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onSessionDestroyed() { worker.post(() -> { refreshMedia(); refresh(); }); }
    };

    private final Runnable snap = this::snapNow;

    /** 重新拍一张快照（同一时间来的一串广播合成一次）。 */
    void refresh() {
        worker.removeCallbacks(snap);
        worker.postDelayed(snap, 30);
    }

    private void snapNow() {
        State s = new State();
        try {
            s.wifi = wifi(); s.bt = bluetooth(); s.dnd = bridge.dnd(); s.loc = location(); s.ringer = ringer();
            s.hasTorch = torchId != null; s.torch = torchOn;
            s.bright = brightToSlider(bridge.brightness());
            if (audio != null) { s.volMax = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); s.vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC); }
            int[] b = battery(); s.bat = b[0]; s.batState = b[1];
            MediaController m = media;
            MediaMetadata md = m == null ? null : m.getMetadata();
            s.media = md != null;
            if (s.media) {
                CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
                CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (a == null || a.length() == 0) a = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                s.mediaTitle = t == null ? "" : t.toString();
                s.mediaArtist = a == null ? "" : a.toString();
                s.mediaApp = appLabel(m.getPackageName());
                PlaybackState ps = m.getPlaybackState();
                s.playing = ps != null && (ps.getState() == PlaybackState.STATE_PLAYING || ps.getState() == PlaybackState.STATE_BUFFERING);
            }
        } catch (Throwable e) {
            Log.w(TAG, "sys snapshot", e);
        }
        main.post(() -> { if (listener != null) listener.onState(s); });
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 面板露出来：开始听广播（注册也在后台线程做，免得拉下来那一下卡）。 */
    void start() {
        if (started) return;
        started = true;
        worker.post(() -> {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            f.addAction(LocationManager.MODE_CHANGED_ACTION);
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            f.addAction("android.media.VOLUME_CHANGED_ACTION");
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            ctx.registerReceiver(receiver, f, null, worker);
            refreshMedia();
            try {
                if (sessions == null) sessions = ctx.getSystemService(MediaSessionManager.class);
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, new ComponentName(ctx, NotifService.class), worker);
            } catch (Throwable t) {
                Log.w(TAG, "media sessions unavailable", t);
            }
            snapNow();
        });
    }

    void stop() {
        if (!started) return;
        started = false;
        worker.post(() -> {
            try { ctx.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            try { if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged); } catch (Throwable ignored) { }
            setMedia(null);
        });
    }

    // ---------- 读（worker 线程） ----------

    @SuppressWarnings("deprecation")
    private int wifi() {
        WifiManager w = ctx.getApplicationContext().getSystemService(WifiManager.class);
        if (w == null) return 0;
        switch (w.getWifiState()) {
            case WifiManager.WIFI_STATE_ENABLING: return 1;
            case WifiManager.WIFI_STATE_DISABLING: return 4;
            case WifiManager.WIFI_STATE_ENABLED: return onTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 3 : 2;
            default: return 0;
        }
    }

    private boolean onTransport(int transport) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(transport)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private int bluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return 0;
        switch (a.getState()) {
            case BluetoothAdapter.STATE_TURNING_ON: return 1;
            case BluetoothAdapter.STATE_TURNING_OFF: return 4;
            case BluetoothAdapter.STATE_ON:
                try {
                    for (int p : new int[] {BluetoothProfile.A2DP, BluetoothProfile.HEADSET})
                        if (a.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED) return 3;
                } catch (Throwable ignored) { }
                return 2;
            default: return 0;
        }
    }

    private boolean location() {
        return Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF;
    }

    private int ringer() {
        if (audio == null) return 0;
        int m = audio.getRingerMode();
        return m == AudioManager.RINGER_MODE_VIBRATE ? 1 : m == AudioManager.RINGER_MODE_SILENT ? 2 : 0;
    }

    private int[] battery() {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return new int[] {-1, 0};
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int pct = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, scale));
        return new int[] {pct, st == BatteryManager.BATTERY_STATUS_FULL ? 2 : st == BatteryManager.BATTERY_STATUS_CHARGING ? 1 : 0};
    }

    // ---------- 写（都丢到 worker 线程，写完再拍一张） ----------

    private void later(Runnable r, long refreshAfter) {
        worker.post(() -> {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "sys write", t); }
            worker.removeCallbacks(snap);
            worker.postDelayed(snap, refreshAfter);
        });
    }

    void setWifi(boolean on) { later(() -> bridge.setWifi(on), 300); }

    void setBluetooth(boolean on) { later(() -> bridge.setBluetooth(on), 300); }

    /** 勿扰要通知策略权限；没有权限时（主线程上一次轻量检查）返回 false，调用方去打开授权页。 */
    boolean setDnd(boolean on) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) return false;
        later(() -> bridge.setDnd(on), 100);
        return true;
    }

    /** 靠 WRITE_SECURE_SETTINGS 直接写；没有这个权限返回 false。 */
    @SuppressWarnings("deprecation")
    boolean setLocation(boolean on) {
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        later(() -> Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE,
                on ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF), 200);
        return true;
    }

    /** 静音开 = 震动（彻底静音交给勿扰）。 */
    void setMuted(boolean on) {
        if (audio == null) return;
        later(() -> audio.setRingerMode(on ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL), 100);
    }

    void setTorch(boolean on) {
        if (torchId == null) return;
        later(() -> { try { camera.setTorchMode(torchId, on); torchOn = on; } catch (Throwable t) { Log.w(TAG, "torch", t); } }, 100);
    }

    // 亮度滑块按平方曲线走（同系统亮度条的思路）：低端更细，拖到最左也是 4 而不是 1（这块屏 1 几乎全黑）
    private static final int BRIGHT_MIN = 4;

    static int sliderToBright(float v) { v = Math.max(0f, Math.min(1f, v)); return BRIGHT_MIN + Math.round((255 - BRIGHT_MIN) * v * v); }

    static float brightToSlider(int raw) { return (float) Math.sqrt(Math.max(0f, Math.min(1f, (raw - BRIGHT_MIN) / (float) (255 - BRIGHT_MIN)))); }

    /** 拖亮度时一直在调：只留最新的一次，旧的还没写就扔掉。 */
    private volatile float brightPending = -1f;
    private final Runnable brightWrite = this::writeBright;

    private void writeBright() {
        float v = brightPending;
        if (v < 0f) return;
        brightPending = -1f;
        int raw = sliderToBright(v);
        if (!bridge.setBrightness(raw)) {
            try {                                              // 只有 WRITE_SECURE_SETTINGS 时也能写 System 表
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, raw);
            } catch (Throwable ignored) { }
        }
    }

    void setBrightness(float v) {
        brightPending = v;
        worker.removeCallbacks(brightWrite);
        worker.post(brightWrite);
    }

    private volatile int volPending = -1;
    private final Runnable volWrite = this::writeVolume;

    private void writeVolume() {
        int v = volPending;
        if (v < 0 || audio == null) return;
        volPending = -1;
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); } catch (SecurityException e) { Log.w(TAG, "volume", e); }
    }

    void setVolume(int v) {
        volPending = Math.max(0, v);
        worker.removeCallbacks(volWrite);
        worker.post(volWrite);
    }

    // ---------- 正在播放（worker 线程） ----------

    private void refreshMedia() {
        try {
            if (sessions == null) sessions = ctx.getSystemService(MediaSessionManager.class);
            pickMedia(sessions.getActiveSessions(new ComponentName(ctx, NotifService.class)));
        } catch (Throwable t) {
            setMedia(null);
        }
    }

    /** 优先正在播的那个；都没在播就取第一个有内容的（暂停中的也能在控制中心里接着播）。 */
    private void pickMedia(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController m : list) {
                PlaybackState s = m.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = m; break; }
            }
            if (pick == null) for (MediaController m : list) if (m.getMetadata() != null) { pick = m; break; }
        }
        setMedia(pick);
    }

    private void setMedia(MediaController m) {
        if (media != null && (m == null || !media.getSessionToken().equals(m.getSessionToken()))) media.unregisterCallback(mediaCb);
        if (m != null && (media == null || !media.getSessionToken().equals(m.getSessionToken()))) m.registerCallback(mediaCb, worker);
        media = m;
    }

    /** -1 上一首，0 播放 / 暂停，1 下一首。 */
    void mediaKey(int k) {
        later(() -> {
            MediaController m = media;
            if (m == null) return;
            MediaController.TransportControls t = m.getTransportControls();
            PlaybackState ps = m.getPlaybackState();
            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            if (k < 0) t.skipToPrevious();
            else if (k > 0) t.skipToNext();
            else if (playing) t.pause();
            else t.play();
        }, 300);
    }

    /** 打开正在播放的那个应用。 */
    void openMedia() {
        worker.post(() -> {
            MediaController m = media;
            if (m == null) return;
            try {
                if (m.getSessionActivity() != null) { m.getSessionActivity().send(); return; }
            } catch (Throwable ignored) { }
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(m.getPackageName());
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { ctx.startActivity(i); } catch (Throwable ignored) { } }
        });
    }
}
