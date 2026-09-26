package org.example.pixelrouter;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SentReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id != null) MessageQueue.smsPartResult(context, id,
                getResultCode() == Activity.RESULT_OK);
    }
}
