package com.openipc.camera;

import android.view.Surface;

/**
 * CameraNative — Java-side thin wrapper for the C++ camera pipeline.
 *
 * This class loads the native library and exposes JNI methods
 * to start/stop the native ACamera + AMediaCodec pipeline.
 * All heavy lifting happens in C++ (CameraStreamerNative.cpp).
 */
public class CameraNative {
    private static final String TAG = "CameraNative";

    static {
        System.loadLibrary("camera_native");
    }

    /**
     * Start the native camera → encoder → UDP pipeline.
     *
     * @param videoConfig A CameraConfig object.
     * @param audioConfig An AudioConfig object.
     * @param surface     Optional preview Surface, or null for headless.
     */
    public static native void nativeStartStreaming(Object videoConfig, Object audioConfig, Surface surface);

    /**
     * Stop and release all native resources.
     */
    public static native void nativeStopStreaming();

    /**
     * Check if the native pipeline is currently streaming.
     */
    public static native boolean nativeIsStreaming();
}
