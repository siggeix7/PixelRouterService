package org.example.pixelrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            context.startService(new Intent(context, RouterService.class));
        } catch (RuntimeException e) {
            Log.e("PixelRouter", "Cannot start controller at boot", e);
        }
    }
}
