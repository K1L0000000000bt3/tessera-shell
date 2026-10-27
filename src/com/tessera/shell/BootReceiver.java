package com.tessera.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机或更新后启动状态层。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        OverlayService.start(context);
    }
}
