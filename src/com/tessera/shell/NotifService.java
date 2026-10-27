package com.tessera.shell;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 把系统通知整理成页面要的样子：磁贴计数、横幅、通知中心都从这里取。 */
public class NotifService extends NotificationListenerService {
    private static volatile NotifService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        Host.emit("notifs", snapshot());
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        Host.emit("notifs", snapshot());
        if (!shouldAlert(sbn, rankingMap)) return;
        JSONObject o = toJson(this, sbn);
        if (o != null) Host.emit("notify", o.toString());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Host.emit("notifs", snapshot());
    }

    static boolean connected() {
        return instance != null;
    }

    static String snapshot() {
        NotifService s = instance;
        JSONArray arr = new JSONArray();
        if (s == null) return arr.toString();
        StatusBarNotification[] all;
        try {
            all = s.getActiveNotifications();
        } catch (SecurityException e) {
            return arr.toString();
        }
        if (all == null) return arr.toString();
        for (StatusBarNotification sbn : all) {
            if (isGroupSummary(sbn)) continue;
            JSONObject o = toJson(s, sbn);
            if (o != null) arr.put(o);
        }
        return arr.toString();
    }

    static void open(String key) { open(key, null); }

    /** options：ActivityOptions（转门动画）；通知的 PendingIntent 是界面时系统会套用它。 */
    static void open(String key, Bundle options) {
        StatusBarNotification sbn = find(key);
        if (sbn == null) return;
        PendingIntent pi = sbn.getNotification().contentIntent;
        try {
            if (pi != null) {
                if (options != null) pi.send(instance, 0, null, null, null, null, options);
                else pi.send();
            }
        } catch (PendingIntent.CanceledException e) {
            Log.w("Tessera", "content intent canceled", e);
        }
        if ((sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) dismiss(key);
    }

    /** 带输入框的动作（回复）直接打开会话；其余动作原样触发。 */
    static void action(String key, int index) {
        StatusBarNotification sbn = find(key);
        if (sbn == null) return;
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null || index < 0 || index >= actions.length) return;
        Notification.Action a = actions[index];
        if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) {
            open(key);
            return;
        }
        try {
            if (a.actionIntent != null) a.actionIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.w("Tessera", "action canceled", e);
        }
    }

    static void dismiss(String key) {
        NotifService s = instance;
        if (s != null) s.cancelNotification(key);
    }

    static void clearAll() {
        NotifService s = instance;
        if (s != null) s.cancelAllNotifications();
    }

    private static StatusBarNotification find(String key) {
        NotifService s = instance;
        if (s == null || key == null) return null;
        StatusBarNotification[] arr = s.getActiveNotifications(new String[] {key});
        return arr == null || arr.length == 0 ? null : arr[0];
    }

    private static boolean isGroupSummary(StatusBarNotification sbn) {
        return (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private boolean shouldAlert(StatusBarNotification sbn, RankingMap map) {
        Notification n = sbn.getNotification();
        if (sbn.getPackageName().equals(getPackageName())) return false;
        if (sbn.isOngoing() || isGroupSummary(sbn)) return false;
        if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 && sbn.getPostTime() != n.when && n.when != 0
                && System.currentTimeMillis() - sbn.getPostTime() > 2000) return false;
        Ranking r = new Ranking();
        if (map != null && map.getRanking(sbn.getKey(), r)) {
            return r.getImportance() >= android.app.NotificationManager.IMPORTANCE_DEFAULT && !r.isSuspended();
        }
        return true;
    }

    static JSONObject toJson(NotifService s, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        JSONObject o = new JSONObject();
        try {
            o.put("key", sbn.getKey());
            o.put("pkg", sbn.getPackageName());
            o.put("app", appLabel(s, sbn.getPackageName()));
            o.put("title", str(ex.getCharSequence(Notification.EXTRA_TITLE)));
            CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            o.put("text", str(big != null ? big : ex.getCharSequence(Notification.EXTRA_TEXT)));
            o.put("when", n.when != 0 ? n.when : sbn.getPostTime());
            o.put("ongoing", sbn.isOngoing());
            // 前台服务通知（录音、运动记录这类"一直在跑"的）不应该当成实时活动占卡片
            o.put("fgs", (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
            o.put("clearable", sbn.isClearable());
            o.put("content", n.contentIntent != null);   // 点了有没有界面可开（没有的话通知中心里点它就不转门）
            o.put("category", n.category == null ? "" : n.category);
            o.put("media", ex.containsKey(Notification.EXTRA_MEDIA_SESSION));
            o.put("progress", ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                    ? ex.getInt(Notification.EXTRA_PROGRESS, 0) * 100 / ex.getInt(Notification.EXTRA_PROGRESS_MAX, 1) : -1);
            JSONArray acts = new JSONArray();
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    JSONObject ao = new JSONObject();
                    ao.put("label", str(a.title));
                    ao.put("input", a.getRemoteInputs() != null && a.getRemoteInputs().length > 0);
                    acts.put(ao);
                }
            }
            o.put("actions", acts);
        } catch (JSONException e) {
            return null;
        }
        return o;
    }

    private static String appLabel(NotifService s, String pkg) {
        try {
            PackageManager pm = s.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
