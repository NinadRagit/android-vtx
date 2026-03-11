package com.openipc.mavlink;

import android.util.Log;

/**
 * Controls the native mavlink-router instance running on a background C++ thread.
 *
 * Routing topology:
 *   UDP:14550 (Java USB bridge) ↔ mavlink-router ↔ UDP:14551 (wfb-ng TX tunnel)
 *                                                ↔ UDP:14552 (OSD/EncLat daemon)
 *
 * Usage:
 *   MavlinkRouter.start();
 *   MavlinkRouter.stop();
 */
public class MavlinkRouter {

    private static final String TAG = "MavlinkRouter";

    static {
        System.loadLibrary("mavlink"); // same shared lib as MavlinkNative
    }

    /**
     * Starts the mavlink-router on a background C++ thread.
     *
     * @return true if started successfully, false if already running or error
     */
    public static boolean start() {
        Log.i(TAG, "Starting mavlink-router daemon");
        boolean ok = nativeRouterStart();
        if (!ok) {
            Log.w(TAG, "nativeRouterStart returned false — router may already be running");
        }
        return ok;
    }

    /**
     * Stops the mavlink-router mainloop. Non-blocking; the C++ thread exits asynchronously.
     */
    public static void stop() {
        Log.i(TAG, "Stopping mavlink-router daemon");
        nativeRouterStop();
    }

    /**
     * Returns true if the router is currently running.
     */
    public static boolean isRunning() {
        return nativeIsRunning();
    }

    // ── JNI declarations ─────────────────────────────────────────────────────
    private static native boolean nativeRouterStart();
    private static native void    nativeRouterStop();
    private static native boolean nativeIsRunning();
}
