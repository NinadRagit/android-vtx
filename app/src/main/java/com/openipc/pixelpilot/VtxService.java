package com.openipc.pixelpilot;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.openipc.wfbngrtl8812.WfbNGStats;
import com.openipc.wfbngrtl8812.WfbNGStatsChanged;
import com.openipc.wfbngrtl8812.WfbNgLink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.util.Base64;

/**
 * Headless Core: VtxService
 * 
 * This service is responsible for keeping the Android VTX engine alive in the background
 * regardless of whether the UI is active or the screen is off.
 * It manages:
 * 1. The Camera Streamer
 * 2. The WFB-NG Link Manager (USB Wi-Fi cards)
 * 3. The WFB-NG VPN Tunnel (UDP Video aggregation)
 * 4. Mavlink Telemetry Routing (Future)
 */
public class VtxService extends Service implements WfbNGStatsChanged {
    private static final String TAG = "VtxService";
    private static final String CHANNEL_ID = "VtxServiceChannel";

    private WfbNgLink wfbLink;
    private WfbLinkManager wfbLinkManager;
    private BroadcastReceiver batteryReceiver;
    
    // Core Engine Components
    private CameraStreamer cameraStreamer;
    private CameraConfig cameraConfig;
    
    // Battery tracking for telemetry
    private int currentBatteryPct = 100;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "VtxService Created - Initializing Headless Engine");

        // 1. Initialize Default Keys for WFB-NG
        ensureWfbKeys();

        // 2. Initialize WFB-NG Link
        wfbLink = new WfbNgLink(this, true); // true = VTX mode
        wfbLink.SetWfbNGStatsChanged(this);
        // Note: WfbLinkManager currently requires a binding, we may need to refactor it to accept null or an interface
        wfbLinkManager = new WfbLinkManager(this, null, wfbLink); 
        
        applyDefaultWfbOptions();

        // 3. Register Receivers
        setupBatteryReceiver();
        registerReceivers();

        // 4. Start VPN Service for the IP tunnel and Video
        startVpnService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, VideoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PixelPilot VTX Active")
                .setContentText("Video & Telemetry Router is running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // Refresh adapters on start
        wfbLinkManager.setChannel(VideoActivity.getChannel(this));
        wfbLinkManager.setBandwidth(VideoActivity.getBandwidth(this));
        wfbLinkManager.refreshAdapters();
        wfbLinkManager.startAdapters();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "VtxService Destroyed - Tearing down Headless Engine");
        if (cameraStreamer != null) {
            cameraStreamer.stopStreaming();
        }
        unregisterReceivers();
        if (wfbLinkManager != null) {
            wfbLinkManager.stopAdapters();
        }
        Intent intent = new Intent(this, WfbNgVpnService.class);
        intent.setAction("STOP_SERVICE");
        startService(intent);
        
        super.onDestroy();
    }

    // ── Binding for UI Debugging ──────────────────────────────────────────────
    
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends android.os.Binder {
        public VtxService getService() {
            return VtxService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ── Public API for Headless Control OR UI Delegate ────────────────────────

    public CameraStreamer getCameraStreamer() {
        if (cameraStreamer == null) {
            cameraStreamer = new CameraStreamer(this);
        }
        return cameraStreamer;
    }

    public WfbLinkManager getWfbLinkManager() {
        return wfbLinkManager;
    }

    public WfbNgLink getWfbLink() {
        return wfbLink;
    }

    // ── Helper Methods Migrated from VideoActivity ────────────────────────────
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "VTX Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void startVpnService() {
        Intent serviceIntent = new Intent(this, WfbNgVpnService.class);
        startService(serviceIntent);
    }

    private void ensureWfbKeys() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean gsKeyExists = !prefs.getString("gs.key", "").isEmpty();
        boolean droneKeyExists = !prefs.getString("drone.key", "").isEmpty();

        if (!gsKeyExists || !droneKeyExists) {
            try {
                Log.d(TAG, "Importing default keys from assets...");
                InputStream gsStream = getAssets().open("gs.key");
                setKeyInPrefs("gs.key", gsStream);
                gsStream.close();

                InputStream droneStream = getAssets().open("drone.key");
                setKeyInPrefs("drone.key", droneStream);
                droneStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to import default keys", e);
            }
        }
        copyKeyToFile("gs.key");
        copyKeyToFile("drone.key");
    }

    private void setKeyInPrefs(String prefKey, InputStream inputStream) throws IOException {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        prefs.edit().putString(prefKey, Base64.encodeToString(result.toByteArray(), Base64.DEFAULT)).apply();
    }

    private void copyKeyToFile(String keyName) {
        File file = new File(getFilesDir(), keyName);
        String pref = getSharedPreferences("general", MODE_PRIVATE).getString(keyName, "");
        if (pref.isEmpty()) return;
        
        byte[] keyBytes = Base64.decode(pref, Base64.DEFAULT);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(keyBytes);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy " + keyName, e);
        }
    }

    private void applyDefaultWfbOptions() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        wfbLink.nativeSetAdaptiveLinkEnabled(prefs.getBoolean("adaptive_link_enabled", true));
        wfbLink.nativeSetTxPower(prefs.getInt("adaptive_tx_power", 20));
        wfbLink.nativeSetUseFec(prefs.getBoolean("custom_fec_enabled", true) ? 1 : 0);
        wfbLink.nativeSetUseLdpc(prefs.getBoolean("custom_ldpc_enabled", true) ? 1 : 0);
        wfbLink.nativeSetUseStbc(prefs.getBoolean("custom_stbc_enabled", true) ? 1 : 0);
        wfbLink.setFecThresholds(
                prefs.getInt("fec_lost_to_5", 2),
                prefs.getInt("fec_recovered_to_4", 30),
                prefs.getInt("fec_recovered_to_3", 24),
                prefs.getInt("fec_recovered_to_2", 14),
                prefs.getInt("fec_recovered_to_1", 8)
        );
    }
    
    private void setupBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent batteryStatus) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    currentBatteryPct = (level * 100) / scale;
                }
            }
        };
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceivers() {
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbFilter.addAction(WfbLinkManager.ACTION_USB_PERMISSION);
        
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wfbLinkManager, usbFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(batteryReceiver, batFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wfbLinkManager, usbFilter);
            registerReceiver(batteryReceiver, batFilter);
        }
    }

    private void unregisterReceivers() {
        try { unregisterReceiver(wfbLinkManager); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void onWfbNgStatsChanged(WfbNGStats data) {
        // Ignored in VTX mode
    }
}
