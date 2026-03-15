package com.openipc.pixelpilot.config;

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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;

/**
 * Minimal camera + encoder configuration.
 * Configurable via JSON import.
 */
public class CameraConfig {

    private static final String TAG = "CameraConfig";
    private static final String CONFIG_FILENAME = "camera_config.json";

    public CameraLayer camera = new CameraLayer();
    public EncoderLayer encoder = new EncoderLayer();

    public static class CameraLayer {
        public String cameraId = "0";
        public int width = 1280;
        public int height = 720;
        public int fps = 30;

        /** Lock AE to current value — reduces flicker for FPV */
        public boolean aeLocked = false;

        /** EV compensation in 1/6 EV steps, range -12..+12 */
        public int aeCompensation = 0;

        /** AF mode: 0=off, 3=continuous-video */
        public int afMode = 0;

        /** Edge enhancement: 0=off, 1=fast, 2=HQ */
        public int edgeMode = 0;

        /** Noise reduction: 0=off, 1=fast, 2=HQ, 3=MINIMAL, 4=ZSL */
        public int noiseReduction = 0;

        /** EIS: false=off (recommended for FPV, adds latency when on) */
        public boolean videoStabilization = false;

        /** OIS: 0=off, 1=on (few FPV sensors have this) */
        public int opticalStabilizationMode = 0;

        /** Auto White Balance Mode: 0=off, 1=auto, 2=incandescent, 3=fluorescent, 5=daylight, 6=cloudy */
        public int awbMode = 1;

        /** Lock AWB to current values */
        public boolean awbLocked = false;

        /** Tonemap mapping curve: 0=contrast_curve, 1=fast, 2=high_quality */
        public int tonemapMode = 1;

        /** Lens shading correction: 0=off, 1=fast, 2=high_quality */
        public int lensShadingMode = 1;

        /** Hot pixel correction: 0=off, 1=fast, 2=high_quality */
        public int hotPixelMode = 1;

        /** Manual ISO. 0 = auto. */
        public int iso = 0;

        /** Manual shutter speed in nanoseconds. 0 = auto. */
        public long shutterSpeed = 0;
    }

    public static class EncoderLayer {
        public int bitrate = 4_000_000;
        public int keyframeInterval = 1;
        public int intraRefreshPeriod = 0; // 0 = disabled; set to fps/2 for smooth intra-refresh
        public String codec = "h264";      // "h264" or "h265"
        public int bitrateMode = 0;        // 0=CBR, 1=VBR
        
        /** Number of B-frames. Use 0 for FPV to minimize look-ahead latency. */
        public int bFrames = 0;
        
        /** Encoder Latency mode: 0=Realtime (minimum latency), 1=Normal. */
        public int encoderLatency = 0;
        
        /** Encoder Priority: 0=Realtime, 1=Best effort. */
        public int encoderPriority = 0;
        
        /** Operating Rate (fps). Usually match this to the camera fps. 0 = auto/ignore. */
        public int operatingRate = 0;

        /** Codec Profile. Depends on codec. 0=Auto. H264: 1=Baseline, 2=Main, 8=High. H265: 1=Main, 2=Main10. */
        public int codecProfile = 0;

        /** Codec Level. Depends on codec. 0=Auto. (Warning: May crash some HALs) */
        public int codecLevel = 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Returns true if a constrained high-speed session is needed (fps > 30) */
    public boolean requiresHighSpeedSession() {
        return camera.fps > 30;
    }

    public String getSummary() {
        return "Cam " + camera.cameraId + " · " + camera.width + "×" + camera.height + " · " + camera.fps + " FPS · " + (encoder.bitrate / 1_000_000) + " Mbps"
                + (requiresHighSpeedSession() ? " [HS]" : "")
                + (camera.aeLocked ? " · AE lock" : "");
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    /** Load config from internal storage. Returns defaults if file not found. */
    public static CameraConfig load(Context ctx) {
        File f = new File(ctx.getFilesDir(), CONFIG_FILENAME);
        if (!f.exists()) return new CameraConfig();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return fromJson(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load config, using defaults", e);
            return new CameraConfig();
        }
    }

    /** Import a config from an external URI into internal storage. */
    public static CameraConfig importFrom(Context ctx, android.net.Uri uri) throws Exception {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open: " + uri);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        CameraConfig config = fromJson(sb.toString());
        // Save to internal storage so it persists
        config.save(ctx);
        return config;
    }

    /** Save current config to internal storage. */
    public void save(Context ctx) {
        try (FileWriter fw = new FileWriter(new File(ctx.getFilesDir(), CONFIG_FILENAME))) {
            fw.write(toJson());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save config", e);
        }
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public String toJson() throws JSONException {
        JSONObject o = new JSONObject();

        JSONObject cam = new JSONObject();
        cam.put("cameraId", camera.cameraId);
        cam.put("width", camera.width);
        cam.put("height", camera.height);
        cam.put("fps", camera.fps);
        cam.put("aeLocked", camera.aeLocked);
        cam.put("aeCompensation", camera.aeCompensation);
        cam.put("afMode", camera.afMode);
        cam.put("edgeMode", camera.edgeMode);
        cam.put("noiseReduction", camera.noiseReduction);
        cam.put("videoStabilization", camera.videoStabilization);
        cam.put("opticalStabilizationMode", camera.opticalStabilizationMode);
        cam.put("awbMode", camera.awbMode);
        cam.put("awbLocked", camera.awbLocked);
        cam.put("tonemapMode", camera.tonemapMode);
        cam.put("lensShadingMode", camera.lensShadingMode);
        cam.put("hotPixelMode", camera.hotPixelMode);
        cam.put("iso", camera.iso);
        cam.put("shutterSpeed", camera.shutterSpeed);
        o.put("camera", cam);

        JSONObject enc = new JSONObject();
        enc.put("bitrate", encoder.bitrate);
        enc.put("keyframeInterval", encoder.keyframeInterval);
        enc.put("intraRefreshPeriod", encoder.intraRefreshPeriod);
        enc.put("codec", encoder.codec);
        enc.put("bitrateMode", encoder.bitrateMode);
        enc.put("bFrames", encoder.bFrames);
        enc.put("encoderLatency", encoder.encoderLatency);
        enc.put("encoderPriority", encoder.encoderPriority);
        enc.put("operatingRate", encoder.operatingRate);
        enc.put("codecProfile", encoder.codecProfile);
        enc.put("codecLevel", encoder.codecLevel);
        o.put("encoder", enc);

        return o.toString(2);
    }

    public static CameraConfig fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        CameraConfig c = new CameraConfig();

        JSONObject cam = o.optJSONObject("camera");
        if (cam != null) {
            c.camera.cameraId = cam.optString("cameraId", "0");
            c.camera.width = cam.optInt("width", 1280);
            c.camera.height = cam.optInt("height", 720);
            c.camera.fps = cam.optInt("fps", 30);
            c.camera.aeLocked = cam.optBoolean("aeLocked", false);
            c.camera.aeCompensation = cam.optInt("aeCompensation", 0);
            c.camera.afMode = cam.optInt("afMode", 0);
            c.camera.edgeMode = cam.optInt("edgeMode", 0);
            c.camera.noiseReduction = cam.optInt("noiseReduction", 0);
            c.camera.videoStabilization = cam.optBoolean("videoStabilization", false);
            c.camera.opticalStabilizationMode = cam.optInt("opticalStabilizationMode", 0);
            c.camera.awbMode = cam.optInt("awbMode", 1);
            c.camera.awbLocked = cam.optBoolean("awbLocked", false);
            c.camera.tonemapMode = cam.optInt("tonemapMode", 1);
            c.camera.lensShadingMode = cam.optInt("lensShadingMode", 1);
            c.camera.hotPixelMode = cam.optInt("hotPixelMode", 1);
            c.camera.iso = cam.optInt("iso", 0);
            c.camera.shutterSpeed = cam.optLong("shutterSpeed", 0);
        }

        JSONObject enc = o.optJSONObject("encoder");
        if (enc != null) {
            c.encoder.bitrate = enc.optInt("bitrate", 4_000_000);
            c.encoder.keyframeInterval = enc.optInt("keyframeInterval", 1);
            c.encoder.intraRefreshPeriod = enc.optInt("intraRefreshPeriod", 0);
            c.encoder.codec = enc.optString("codec", "h264");
            c.encoder.bitrateMode = enc.optInt("bitrateMode", 0);
            c.encoder.bFrames = enc.optInt("bFrames", 0);
            c.encoder.encoderLatency = enc.optInt("encoderLatency", 0);
            c.encoder.encoderPriority = enc.optInt("encoderPriority", 0);
            c.encoder.operatingRate = enc.optInt("operatingRate", 0);
            c.encoder.codecProfile = enc.optInt("codecProfile", 0);
            c.encoder.codecLevel = enc.optInt("codecLevel", 0);
        }
        
        return c;
    }
}
