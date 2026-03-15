package com.openipc.pixelpilot.hardware;

import com.openipc.pixelpilot.R;
import com.openipc.pixelpilot.config.*;
import com.openipc.pixelpilot.hardware.*;
import com.openipc.pixelpilot.telemetry.*;
import com.openipc.pixelpilot.ui.*;
import com.openipc.pixelpilot.ui.osd.*;
import com.openipc.pixelpilot.service.*;
import com.openipc.pixelpilot.wfb.*;


import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

public class CameraHelper {
    private static final String TAG = "CameraHelper";
    private final CameraManager manager;

    public CameraHelper(Context context) {
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public static class CameraInfo {
        public String id;
        public String name;
        public int facing; // CameraCharacteristics.LENS_FACING_FRONT/BACK

        public CameraInfo(String id, String name, int facing) {
            this.id = id;
            this.name = name;
            this.facing = facing;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public List<CameraInfo> getAvailableCameras() {
        List<CameraInfo> cameras = new ArrayList<>();
        // Track (facing, focalLength) pairs to avoid duplicate sensors
        java.util.Set<String> seenSignatures = new java.util.HashSet<>();

        try {
            String[] ids = manager.getCameraIdList();

            // Priority 1: Standard IDs — always add, mark their signatures
            for (String id : ids) {
                discoverCamera(id, cameras, seenSignatures, false);
            }

            // Priority 2: Physical sensors behind logical cameras (Android 9+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                for (String id : ids) {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    for (String pid : chars.getPhysicalCameraIds()) {
                        discoverCamera(pid, cameras, seenSignatures, true);
                    }
                }
            }

            // Priority 3: Scan for OEM-hidden IDs (e.g., ultra-wide on many Qualcomm phones)
            for (int i = 0; i < 100; i++) {
                discoverCamera(String.valueOf(i), cameras, seenSignatures, true);
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to list cameras", e);
        }
        return cameras;
    }

    private void discoverCamera(String id, List<CameraInfo> list,
                                java.util.Set<String> seenSignatures, boolean isAuxiliary) {
        for (CameraInfo info : list) {
            if (info.id.equals(id)) return; // already added
        }

        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);

            // Build a signature to detect duplicate physical sensors presented under different IDs
            float focal = (focalLengths != null && focalLengths.length > 0) ? focalLengths[0] : 0f;
            String sig = facing + ":" + focal;

            if (isAuxiliary && seenSignatures.contains(sig)) {
                return; // same sensor already exposed under a standard ID
            }
            seenSignatures.add(sig);

            String name = buildCameraName(id, facing, focal);
            Log.d(TAG, "Discovered camera: " + name + " (ID:" + id + ")");
            list.add(new CameraInfo(id, name, facing != null ? facing : -1));

        } catch (Exception ignored) {
            // Not a valid camera ID
        }
    }

    private String buildCameraName(String id, Integer facing, float focal) {
        if (facing == null) return "Camera " + id;
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "Front Camera";
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "External Camera";

        // Back camera: classify by focal length
        if (focal < 2.5f && focal > 0f) return "Ultra Wide Camera";
        if (focal > 5.0f) return "Telephoto Camera";
        return "Main Camera";
    }

    /** Returns the display name for a camera by ID (e.g. "Main Camera", "Ultra Wide Camera") */
    public String getCameraDisplayName(String cameraId) {
        for (CameraInfo info : getAvailableCameras()) {
            if (info.id.equals(cameraId)) return info.name;
        }
        return "Camera " + cameraId;
    }

    public List<Size> getSupportedSizes(String cameraId) {
        List<Size> sizes = new ArrayList<>();
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] outputSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                if (outputSizes != null) {
                    for (Size s : outputSizes) sizes.add(s);
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get sizes for " + cameraId, e);
        }
        return sizes;
    }

    public List<Integer> getSupportedFps(String cameraId, Size size) {
        java.util.Set<Integer> fpsSet = new java.util.HashSet<>();
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map != null) {
                // 1. Standard AE target FPS ranges
                Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null) {
                    for (Range<Integer> r : ranges) {
                        fpsSet.add(r.getUpper());
                    }
                }

                // 2. High-speed video ranges (for 60/90/120/240 FPS etc.)
                // A range like [30, 120] means ANY fps from 30 to 120 is supported
                // in a constrained high-speed session — including 60, 90, etc.
                try {
                    Size[] hsSizes = map.getHighSpeedVideoSizes();
                    if (hsSizes != null) {
                        for (Size s : hsSizes) {
                            if (s.equals(size)) {
                                Range<Integer>[] hsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                                int[] standardFps = {30, 48, 60, 90, 120, 240};
                                for (Range<Integer> r : hsRanges) {
                                    // Always add the explicit upper bound
                                    fpsSet.add(r.getUpper());
                                    // Also add any standard FPS that falls within this range
                                    for (int fps : standardFps) {
                                        if (fps >= r.getLower() && fps <= r.getUpper()) {
                                            fpsSet.add(fps);
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // 3. Derive max FPS from minimum frame duration at the requested resolution
                try {
                    long minDur = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture.class, size);
                    if (minDur > 0) {
                        int derivedMaxFps = (int) (1_000_000_000L / minDur);
                        // Add standard intermediate FPS levels the sensor can sustain
                        for (int level : new int[]{60, 50, 48}) {
                            if (derivedMaxFps >= level) fpsSet.add(level);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get FPS for " + cameraId, e);
        }

        List<Integer> fpsList = new ArrayList<>(fpsSet);
        java.util.Collections.sort(fpsList, java.util.Collections.reverseOrder());
        return fpsList;
    }
}
