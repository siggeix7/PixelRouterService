package org.example.pixelrouter;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Set;

/** System-image controller. Never changes cellular settings or reboots the device. */
public final class RouterService extends Service {
    private static final String TAG = "PixelRouter";
    private static final long POLL_MS = 60_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TetheringManager tether;
    private boolean ethernetActive, wifiActive, ethernetPending, wifiPending;
    private long lastEthernetAttempt, lastWifiAttempt;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            reconcile();
            MessageQueue.processAsync(RouterService.this);
            MessageQueue.pruneDelivered(RouterService.this);
            handler.postDelayed(this, POLL_MS);
        }
    };
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.postDelayed(() -> reconcile(), 1500);
        }
    };
    private final TetheringManager.TetheringEventCallback events =
            new TetheringManager.TetheringEventCallback() {
        @Override public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
            ethernetActive = false;
            wifiActive = false;
            for (TetheringInterface iface : interfaces) {
                if (iface.getType() == TetheringManager.TETHERING_ETHERNET) ethernetActive = true;
                if (iface.getType() == TetheringManager.TETHERING_WIFI) wifiActive = true;
            }
            handler.post(() -> reconcile());
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        tether = getSystemService(TetheringManager.class);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        if (tether != null) {
            tether.registerTetheringEventCallback(handler::post, events);
        }
        handler.post(poll);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(() -> {
            reconcile();
            MessageQueue.processAsync(this);
        });
        return START_STICKY;
    }

    private boolean ethernetPresent() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                String name = interfaces.nextElement().getName();
                if (name.matches("eth[0-9]+|enx[0-9a-fA-F]+")) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to enumerate Ethernet", e);
        }
        return false;
    }

    private void reconcile() {
        if (tether == null) return;
        String mode = Config.mode(this);
        boolean useEthernet = Config.HOME.equals(mode)
                || (Config.AUTO.equals(mode) && ethernetPresent());
        boolean useWifi = Config.TRAVEL.equals(mode)
                || (Config.AUTO.equals(mode) && !useEthernet);
        if (useEthernet && ethernetPresent()) start(TetheringManager.TETHERING_ETHERNET);
        if (useWifi) start(TetheringManager.TETHERING_WIFI);
        if (!useEthernet && ethernetActive) tether.stopTethering(TetheringManager.TETHERING_ETHERNET);
        if (!useWifi && wifiActive) tether.stopTethering(TetheringManager.TETHERING_WIFI);
        Config.prefs(this).edit().putString("status", "mode=" + mode
                + " ethernet=" + ethernetActive + " wifi=" + wifiActive
                + " adapter=" + ethernetPresent()).apply();
    }

    private void start(int type) {
        boolean ethernet = type == TetheringManager.TETHERING_ETHERNET;
        long now = android.os.SystemClock.elapsedRealtime();
        if (ethernet && ethernetPending && now - lastEthernetAttempt > 90_000)
            ethernetPending = false;
        if (!ethernet && wifiPending && now - lastWifiAttempt > 90_000)
            wifiPending = false;
        if (ethernet ? ethernetActive || ethernetPending : wifiActive || wifiPending) return;
        long last = ethernet ? lastEthernetAttempt : lastWifiAttempt;
        if (last != 0 && now - last < 30_000) return;
        if (ethernet) { ethernetPending = true; lastEthernetAttempt = now; }
        else { wifiPending = true; lastWifiAttempt = now; }
        try {
            TetheringManager.TetheringRequest request = new TetheringManager.TetheringRequest.Builder(type)
                    .setShouldShowEntitlementUi(false).build();
            tether.startTethering(request, handler::post,
                    new TetheringManager.StartTetheringCallback() {
                @Override public void onTetheringStarted() {
                    if (ethernet) ethernetPending = false; else wifiPending = false;
                    Log.i(TAG, "Tethering request accepted type=" + type);
                }
                @Override public void onTetheringFailed(int error) {
                    if (ethernet) ethernetPending = false; else wifiPending = false;
                    Log.e(TAG, "Tethering failed type=" + type + " error=" + error);
                    Config.prefs(RouterService.this).edit()
                            .putString("last_error", "Tethering " + type + ": " + error).apply();
                }
            });
        } catch (RuntimeException e) {
            if (ethernet) ethernetPending = false; else wifiPending = false;
            Log.e(TAG, "Tethering start rejected", e);
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        unregisterReceiver(usbReceiver);
        if (tether != null) tether.unregisterTetheringEventCallback(events);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
