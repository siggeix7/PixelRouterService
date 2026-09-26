package org.example.pixelrouter;

import android.content.Context;
import android.content.SharedPreferences;

/** All values needed before unlock live in device-protected storage. */
final class Config {
    static final String HOME = "home";
    static final String TRAVEL = "travel";
    static final String AUTO = "auto";
    private static final String PREFS = "router_config";

    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String get(Context context, String key) {
        return prefs(context).getString(key, "");
    }

    static String mode(Context context) {
        return prefs(context).getString("mode", HOME);
    }

    static boolean mailReady(Context context) {
        return !get(context, "mail_host").isEmpty()
                && !get(context, "mail_from").isEmpty()
                && !get(context, "mail_password").isEmpty()
                && !get(context, "mail_to").isEmpty();
    }

    private Config() {}
}
