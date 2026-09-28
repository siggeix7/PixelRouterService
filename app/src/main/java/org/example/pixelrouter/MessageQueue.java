package org.example.pixelrouter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable BFU queue. SMS submission is at-most-once; SMTP is retried. */
final class MessageQueue {
    private static final String TAG = "PixelRouter";
    private static final String STORE = "messages";
    private static final int MAX_PENDING = 250;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static JSONArray read(Context c) throws Exception {
        return new JSONArray(prefs(c).getString("queue", "[]"));
    }

    private static void write(Context c, JSONArray queue) {
        if (!prefs(c).edit().putString("queue", queue.toString()).commit())
            throw new IllegalStateException("SMS queue commit failed");
    }

    static void enqueue(Context c, String dedupKey, String sender, String body) throws Exception {
        synchronized (LOCK) {
            JSONArray queue = read(c);
            String id = hash(dedupKey);
            for (int i = 0; i < queue.length(); i++)
                if (id.equals(queue.getJSONObject(i).getString("id"))) return;
            if (queue.length() >= MAX_PENDING) {
                Log.e(TAG, "SMS queue full; manual intervention required");
                throw new IllegalStateException("SMS queue full");
            }
            JSONObject item = new JSONObject();
            item.put("id", id).put("from", sender).put("body", body)
                    .put("received", System.currentTimeMillis())
                    .put("sms", Config.get(c, "sms_to").isEmpty() ? "disabled" : "queued")
                    .put("mail", Config.mailReady(c) ? "queued" : "disabled")
                    .put("tries", 0).put("next", 0L);
            queue.put(item);
            write(c, queue);
        }
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 12; i++) hex.append(String.format("%02x", digest[i] & 255));
        return hex.toString();
    }

    static void processAsync(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> process(app));
    }

    private static void process(Context c) {
        try {
            JSONArray snapshot;
            synchronized (LOCK) { snapshot = read(c); }
            for (int i = 0; i < snapshot.length(); i++) {
                JSONObject item = snapshot.getJSONObject(i);
                String id = item.getString("id");
                String phone = Config.get(c, "sms_to");
                if ("queued".equals(item.getString("sms")) && !phone.isEmpty())
                    submitSms(c, id, phone, item.getString("from"), item.getString("body"));
                if (("queued".equals(item.getString("mail"))
                        || "sending".equals(item.getString("mail"))) && Config.mailReady(c)
                        && item.getLong("next") <= System.currentTimeMillis()) {
                    // Persist the attempt first. SMTP delivery can be ambiguous if the connection
                    // dies after DATA; on restart we retry with the same Message-ID.
                    mutate(c, id, "mail", "sending");
                    try {
                        Mailer.send(c, id, item.getString("from"), item.getString("body"));
                        mutate(c, id, "mail", "sent");
                    } catch (Exception e) {
                        Log.w(TAG, "SMTP delivery failed id=" + id, e);
                        synchronized (LOCK) {
                            JSONArray q = read(c);
                            JSONObject current = find(q, id);
                            if (current != null) {
                                int tries = Math.min(12, current.optInt("tries") + 1);
                                current.put("tries", tries);
                                current.put("next", System.currentTimeMillis()
                                        + Math.min(3_600_000L, 30_000L << Math.min(tries, 7)));
                                current.put("mail", "queued");
                                write(c, q);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Queue processing failed", e); }
    }

    private static void submitSms(Context c, String id, String phone, String from, String body)
            throws Exception {
        SmsManager sms = c.getSystemService(SmsManager.class);
        if (sms == null) throw new IllegalStateException("SMS manager unavailable");
        ArrayList<String> parts = sms.divideMessage("SMS da " + from + ":\n" + body);
        // This state deliberately does not auto-retry after a crash: the carrier might
        // already have accepted the SMS, and a retry could duplicate an OTP.
        synchronized (LOCK) {
            JSONArray q = read(c);
            JSONObject current = find(q, id);
            if (current == null || !"queued".equals(current.getString("sms"))) return;
            current.put("sms", "submitting").put("parts_left", parts.size());
            write(c, q);
        }
        ArrayList<PendingIntent> results = new ArrayList<>();
        for (int j = 0; j < parts.size(); j++) {
            Intent intent = new Intent(c, SentReceiver.class).putExtra("id", id);
            intent.setAction("org.example.pixelrouter.SENT." + id + "." + j);
            results.add(PendingIntent.getBroadcast(c, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        try {
            sms.sendMultipartTextMessage(phone, null, parts, results, null);
        } catch (RuntimeException e) {
            mutate(c, id, "sms", "uncertain");
            throw e;
        }
    }

    static void smsPartResult(Context c, String id, boolean ok) {
        synchronized (LOCK) {
            try {
                JSONArray q = read(c);
                JSONObject item = find(q, id);
                if (item == null || !"submitting".equals(item.optString("sms"))) return;
                int left = item.optInt("parts_left") - 1;
                item.put("parts_left", left);
                if (!ok) item.put("sms", "failed");
                else if (left == 0) item.put("sms", "sent");
                write(c, q);
            } catch (Exception e) { Log.e(TAG, "Cannot record SMS result", e); }
        }
    }

    private static JSONObject find(JSONArray q, String id) throws Exception {
        for (int i = 0; i < q.length(); i++) {
            JSONObject item = q.getJSONObject(i);
            if (id.equals(item.getString("id"))) return item;
        }
        return null;
    }

    private static void mutate(Context c, String id, String key, String value) throws Exception {
        synchronized (LOCK) {
            JSONArray q = read(c);
            JSONObject item = find(q, id);
            if (item != null) { item.put(key, value); write(c, q); }
        }
    }

    static int[] counts(Context c) {
        int[] counts = new int[3]; // queued mail, uncertain SMS, total retained
        try {
            synchronized (LOCK) {
                JSONArray q = read(c);
                counts[2] = q.length();
                for (int i = 0; i < q.length(); i++) {
                    JSONObject m = q.getJSONObject(i);
                    if ("queued".equals(m.optString("mail"))
                            || "sending".equals(m.optString("mail"))) counts[0]++;
                    String s = m.optString("sms");
                    if ("uncertain".equals(s) || "submitting".equals(s)
                            || "failed".equals(s)) counts[1]++;
                }
            }
        } catch (Exception e) { Log.e(TAG, "Queue status unavailable", e); }
        return counts;
    }

    static void pruneDelivered(Context c) {
        synchronized (LOCK) {
            try {
                JSONArray old = read(c), kept = new JSONArray();
                long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
                for (int i = 0; i < old.length(); i++) {
                    JSONObject m = old.getJSONObject(i);
                    String mail = m.optString("mail"), sms = m.optString("sms");
                    if (m.optLong("received") >= cutoff
                            || !("sent".equals(mail) || "disabled".equals(mail))
                            || !("sent".equals(sms) || "disabled".equals(sms))) kept.put(m);
                }
                if (kept.length() != old.length()) write(c, kept);
            } catch (Exception e) { Log.e(TAG, "Queue pruning failed", e); }
        }
    }
    private MessageQueue() {}
}
