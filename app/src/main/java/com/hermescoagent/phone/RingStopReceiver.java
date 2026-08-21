package com.hermescoagent.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles the "Stop" tap on the Find My Phone notification. Silences the
 * ring immediately instead of waiting for the 30s auto-stop or a remote
 * stop_ring command.
 */
public class RingStopReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP_RING = "com.hermescoagent.phone.STOP_RING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_STOP_RING.equals(intent.getAction())) {
            CommandExecutor.stopRingFromNotification(context);
        }
    }
}
