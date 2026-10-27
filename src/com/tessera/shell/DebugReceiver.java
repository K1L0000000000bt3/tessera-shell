package com.tessera.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 迁移期间的调试开关：adb shell am broadcast -a com.tessera.shell.TOGGLE_GRID */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        android.util.Log.i("Tessera", "DebugReceiver " + intent.getAction() + " current=" + (MainActivity.current != null));
        if ("com.tessera.shell.FACE_MODE".equals(intent.getAction())) {
            MainActivity mm = MainActivity.current;
            if (mm != null && mm.face() != null) mm.runOnUiThread(() -> mm.face().togglePanelsMode());
            return;
        }
        if (!"com.tessera.shell.TOGGLE_GRID".equals(intent.getAction())) return;
        MainActivity m = MainActivity.current;
        if (m != null) {
            m.runOnUiThread(m::toggleGrid);
        } else {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }
}
