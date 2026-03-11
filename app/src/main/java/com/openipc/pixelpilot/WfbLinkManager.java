package com.openipc.pixelpilot;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import com.openipc.wfbngrtl8812.WfbNgLink;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class WfbLinkManager extends BroadcastReceiver {
    public interface LinkStatusListener {
        void onAdapterMessage(String message);
        void onNoAdaptersFound();
    }
    public static final String ACTION_USB_PERMISSION = "com.openipc.pixelpilot.USB_PERMISSION";
    private static final String TAG = "pixelpilot";
    static Map<String, UsbDevice> activeWifiAdapters = new HashMap<>();
    private final WfbNgLink wfbLink;
    private LinkStatusListener listener;
    private final Context context;
    private int wifiChannel;
    private Bandwidth bandWidth;

    public enum Bandwidth {
        BANDWIDTH_20(20),
        BANDWIDTH_40(40);

        private final int value;

        Bandwidth(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public WfbLinkManager(Context context, LinkStatusListener listener, WfbNgLink wfbNgLink) {
        this.listener = listener;
        this.context = context;
        this.wfbLink = wfbNgLink;
    }

    public void setListener(LinkStatusListener listener) {
        this.listener = listener;
    }

    public void refreshKey() {
        wfbLink.refreshKey();
    }

    public void setChannel(int channel) {
        wifiChannel = channel;
    }
    public void setBandwidth(int bw) {
        switch(bw)
        {
            case 20:
                bandWidth = Bandwidth.BANDWIDTH_20;
                break;
            case 40:
                bandWidth = Bandwidth.BANDWIDTH_40;
                break;
            default:
                break;
        }
    }

    @Override
    public synchronized void onReceive(Context context, Intent intent) {
        UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        String action = intent.getAction();

        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            if (dev == null) return;
            Log.d(TAG, "usb device detached: " + dev.getVendorId() + "/" + dev.getProductId());
            refreshAdapters();
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            if (dev == null) return;
            Log.d(TAG, "usb device attached: " + dev.getVendorId() + "/" + dev.getProductId());
            refreshAdapters(); // Headless requirement: refresh immediately
        } else if (ACTION_USB_PERMISSION.equals(action)) {
            Log.d(TAG, "USB Permission handled - refreshing adapters");
            refreshAdapters();
        }
    }

    public Map<String, UsbDevice> getAttachedAdapters() {
        android.hardware.usb.UsbManager manager =
                (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);

        List<UsbDeviceFilter> filters;
        try {
            filters = UsbDeviceFilter.parseXml(context, R.xml.usb_device_filter);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        Map<String, UsbDevice> res = new HashMap<>();
        for (UsbDevice dev : manager.getDeviceList().values()) {
            boolean allowed = false;
            for (UsbDeviceFilter filter : filters) {
                if (filter.productId == dev.getProductId() && filter.vendorId == dev.getVendorId()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                continue;
            }
            res.put(dev.getDeviceName(), dev);
        }
        return res;
    }

    public synchronized void refreshAdapters() {
        Map<String, UsbDevice> attachedAdapters = getAttachedAdapters();

        boolean missingPermissions = false;
        android.hardware.usb.UsbManager usbManager =
                (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (Map.Entry<String, UsbDevice> entry : attachedAdapters.entrySet()) {
            if (!usbManager.hasPermission(entry.getValue())) {
                if (listener != null) {
                    listener.onAdapterMessage("No permission for wifi adapter(s) " + entry.getValue().getDeviceName());
                }
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0,
                        new Intent(WfbLinkManager.ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                usbManager.requestPermission(entry.getValue(), pendingIntent);
                missingPermissions = true;
            }
        }

        if (missingPermissions) {
            return;
        }

        // Stops newly detached adapters.
        Iterator<Map.Entry<String, UsbDevice>> iterator = activeWifiAdapters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, UsbDevice> entry = iterator.next();
            if (attachedAdapters.containsKey(entry.getKey())) {
                continue;
            }
            stopAdapter(entry.getValue());
            iterator.remove();
        }

        // Starts newly attached adapters.
        for (Map.Entry<String, UsbDevice> entry : attachedAdapters.entrySet()) {
            if (activeWifiAdapters.containsKey(entry.getKey())) {
                continue;
            }
            startAdapter(entry.getValue());
            activeWifiAdapters.put(entry.getKey(), entry.getValue());
        }

        if (activeWifiAdapters.isEmpty()) {
            if (listener != null) {
                listener.onNoAdaptersFound();
            }
        }
    }

    public synchronized void stopAdapters() {
        try {
            wfbLink.stopAll();
        } catch (InterruptedException ignored) {
        }
    }

    public synchronized void stopAdapter(UsbDevice dev) {
        try {
            wfbLink.stop(dev);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public synchronized void startAdapters() {
        if (wfbLink.isRunning()) {
            return;
        }
        for (Map.Entry<String, UsbDevice> entry : activeWifiAdapters.entrySet()) {
            if (!startAdapter(entry.getValue())) {
                break;
            }
        }
    }

    public synchronized boolean startAdapter(UsbDevice dev) {
        String text = "Starting wfb-ng channel " + wifiChannel + " with " + String.format(
                "[%04X", dev.getVendorId()) + ":" + String.format("%04X]", dev.getProductId());
        if (listener != null) {
            listener.onAdapterMessage(text);
        }
        wfbLink.start(wifiChannel, bandWidth.getValue(), dev);
        return true;
    }
}
