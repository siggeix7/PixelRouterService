package org.example.pixelrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/** Receives ordinary text SMS; MMS, RCS and class-0 messages are out of scope. */
public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) body.append(part.getMessageBody());
        // Short-lived deduplication handles repeated broadcasts, not messages sent twice later.
        String sender = parts[0].getOriginatingAddress();
        if (sender == null) sender = "unknown";
        String key = sender + "|" + parts[0].getTimestampMillis() + "|" + body;
        try {
            MessageQueue.enqueue(context, key, sender, body.toString());
            context.startService(new Intent(context, RouterService.class));
        } catch (Exception e) {
            Log.e("PixelRouter", "Unable to queue incoming SMS", e);
        }
    }
}
