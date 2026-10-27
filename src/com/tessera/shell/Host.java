package com.tessera.shell;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内的小事件总线：通知服务、广播把变化推给桌面和悬浮层。 */
final class Host {
    interface Listener {
        void onEvent(String name, String json);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 桌面（表盘/开始屏）当前是否在前台；在前台时由桌面自己画状态与横幅。 */
    static volatile boolean shellForeground;

    private Host() {}

    static void add(Listener l) { LISTENERS.addIfAbsent(l); }

    static void remove(Listener l) { LISTENERS.remove(l); }

    static void emit(String name, String json) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onEvent(name, json);
        });
    }
}
