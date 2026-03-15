package com.openipc.pixelpilot.service;

import com.openipc.pixelpilot.R;
import com.openipc.pixelpilot.config.*;
import com.openipc.pixelpilot.hardware.*;
import com.openipc.pixelpilot.telemetry.*;
import com.openipc.pixelpilot.ui.*;
import com.openipc.pixelpilot.ui.osd.*;
import com.openipc.pixelpilot.service.*;
import com.openipc.pixelpilot.wfb.*;


import android.content.Context;
import android.util.Log;
import android.view.Surface;

import com.openipc.wfbngrtl8812.WfbNGStatsChanged;
import com.openipc.wfbngrtl8812.WfbNgLink;
import com.openipc.wfbngrtl8812.WfbNGStats;

import com.openipc.mavlink.MavlinkData;
import com.openipc.mavlink.MavlinkUpdate;
import com.openipc.camera.CameraNative;
import android.os.Handler;
import android.os.Looper;

/**
 * VtxEngine
 * 
 * The central orchestrator for the Android VTX Data Plane.
 * This class runs entirely headlessly and holds the references and lifecycles 
 * for the lower-level hardware and networking components:
 * 1. CameraNative (C++ ACamera/AMediaCodec pipeline)
 * 2. WfbNgLink & WfbLinkManager (USB Radio connection & packet routing)
 * 3. TelemetryRouter (USB Serial to UDP Bridge)
 * 4. MavlinkNative (C++ Daemon & MAVLink parsing)
 */
public class VtxEngine implements WfbNGStatsChanged, MavlinkUpdate {
    private static final String TAG = "VtxEngine";

    private final Context context;
    private final WfbNgLink wfbLink;
    private final WfbLinkManager wfbLinkManager;
    private final TelemetryRouter telemetryRouter;

    // Interface for UI to receive parsed MAVLink OSD data
    private MavlinkDataListener mavlinkDataListener;

    public interface MavlinkDataListener {
        void onNewMavlinkData(MavlinkData data);
        void onEncoderLatencyUpdated(float latencyMs);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable mavlinkPoller = new Runnable() {
        public void run() {
            com.openipc.mavlink.MavlinkNative.nativeCallBack(VtxEngine.this);
            // Fetch encoder latency from native whiteboard and update listeners
            float latency = com.openipc.mavlink.MavlinkNative.nativeGetEncoderLatency();
            if (mavlinkDataListener != null) {
                mavlinkDataListener.onEncoderLatencyUpdated(latency);
            }
            handler.postDelayed(this, 100);
        }
    };

    public VtxEngine(Context context) {
        this.context = context.getApplicationContext();

        // 1. Initialize WFB-NG Link
        this.wfbLink = new WfbNgLink(this.context, true); // true = VTX mode
        this.wfbLink.SetWfbNGStatsChanged(this);
        this.wfbLinkManager = new WfbLinkManager(this.context, null, wfbLink);

        // 2. Initialize Telemetry Router (USB Serial -> UDP WFB-NG)
        this.telemetryRouter = new TelemetryRouter(this.context);
    }

    public void start() {
        Log.i(TAG, "Starting VTX Engine Components...");
        
        // Refresh and start WFB adapters
        wfbLinkManager.setChannel(VideoActivity.getChannel(context));
        wfbLinkManager.setBandwidth(VideoActivity.getBandwidth(context));
        wfbLinkManager.refreshAdapters();
        wfbLinkManager.startAdapters();

        // Start Telemetry Bridge
        if (telemetryRouter != null) {
            telemetryRouter.start();
        }

        // Start the native C++ mavlink-router daemon unconditionally
        com.openipc.mavlink.MavlinkRouter.start();
        
        // Start Android MAVLink Parser Listener
        com.openipc.mavlink.MavlinkNative.nativeStart(context);
        handler.post(mavlinkPoller);
    }

    public void stop() {
        Log.i(TAG, "Stopping VTX Engine Components...");
        handler.removeCallbacks(mavlinkPoller);
        if (telemetryRouter != null) {
            telemetryRouter.stop();
        }
        // Stop native camera pipeline
        stopNativeCamera();
        if (wfbLinkManager != null) {
            wfbLinkManager.stopAdapters();
        }
        com.openipc.mavlink.MavlinkRouter.stop();
    }

    // ── Native Camera Pipeline (C++ AMediaCodec) ────────────────────────────

    /**
     * Start the C++ camera pipeline.
     * @param config  CameraConfig with all camera/encoder parameters
     * @param surface Preview Surface, or null for headless
     */
    public void startNativeCamera(CameraConfig config, Surface surface) {
        Log.i(TAG, "Starting NATIVE camera pipeline: " + config.getSummary());
        CameraNative.nativeStartStreaming(config, surface);
    }

    /**
     * Stop the C++ camera pipeline.
     */
    public void stopNativeCamera() {
        if (CameraNative.nativeIsStreaming()) {
            Log.i(TAG, "Stopping NATIVE camera pipeline");
            CameraNative.nativeStopStreaming();
        }
    }

    public void setMavlinkDataListener(MavlinkDataListener listener) {
        this.mavlinkDataListener = listener;
    }

    public WfbLinkManager getWfbLinkManager() {
        return wfbLinkManager;
    }

    public WfbNgLink getWfbLink() {
        return wfbLink;
    }

    // ── DATA PLANE: WFB STATS CALLBACK ───────────────────────────────────────
    @Override
    public void onWfbNgStatsChanged(WfbNGStats data) {
        // Stats are irrelevant in pure VTX mode
    }

    // ── DATA PLANE: MAVLINK PARSED CALLBACK ─────────────────────────────────
    @Override
    public void onNewMavlinkData(MavlinkData data) {
        if (mavlinkDataListener != null) {
            mavlinkDataListener.onNewMavlinkData(data);
        }
    }
}
