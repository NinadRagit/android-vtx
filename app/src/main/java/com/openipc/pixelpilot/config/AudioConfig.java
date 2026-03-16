package com.openipc.pixelpilot.config;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Comprehensive Audio configuration mapping to Oboe and Opus capabilities.
 */
public class AudioConfig {

    private static final String TAG = "AudioConfig";
    private static final String CONFIG_FILENAME = "audio_config.json";

    public OboeLayer oboe = new OboeLayer();
    public OpusLayer opus = new OpusLayer();

    public static class OboeLayer {
        public int sampleRate = 48000;
        public int channels = 1;
        
        /** 1=Generic, 5=Camcorder(default), 6=VoiceRecognition, 7=VoiceCommunication(AEC+NS), 9=Unprocessed */
        public int inputPreset = 5;
        
        /** 10=None, 11=PowerSaving, 12=LowLatency(default) */
        public int performanceMode = 12;
        
        /** 0=Exclusive(default), 1=Shared */
        public int sharingMode = 0;
        
        /** 1=I16(default), 2=Float */
        public int format = 1;
    }

    public static class OpusLayer {
        public int bitrate = 64000;
        
        /** 0 (fastest) to 10 (highest quality) */
        public int complexity = 5;
        
        /** 2048=VOIP, 2049=AUDIO, 2051=RESTRICTED_LOWDELAY(default) */
        public int application = 2051;
        
        /** Frame duration in milliseconds (e.g. 5, 10, 20, 40, 60) */
        public int frameSizeMs = 20;
        
        /** Forward Error Correction (telemetry recovery) */
        public boolean enableFec = false;
        
        /** Discontinuous Transmission (stops sending packets during absolute silence) */
        public boolean enableDtx = false;
        
        /** 0=CBR, 1=VBR, 2=Constrained VBR */
        public int vbrMode = 0;
    }

    public static AudioConfig load(Context ctx) {
        File f = new File(ctx.getFilesDir(), CONFIG_FILENAME);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return fromJson(sb.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to load user config, falling back to asset", e);
            }
        }
        
        return loadDefaultAsset(ctx);
    }

    private static AudioConfig loadDefaultAsset(Context ctx) {
        try (InputStream is = ctx.getAssets().open(CONFIG_FILENAME);
             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return fromJson(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load default assets/audio_config.json! Using hardcoded defaults.", e);
            return new AudioConfig();
        }
    }

    public void save(Context ctx) {
        try (FileWriter fw = new FileWriter(new File(ctx.getFilesDir(), CONFIG_FILENAME))) {
            fw.write(toJson());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save audio config", e);
        }
    }

    public String toJson() throws JSONException {
        JSONObject o = new JSONObject();

        JSONObject oboeJson = new JSONObject();
        oboeJson.put("sampleRate", oboe.sampleRate);
        oboeJson.put("channels", oboe.channels);
        oboeJson.put("inputPreset", oboe.inputPreset);
        oboeJson.put("performanceMode", oboe.performanceMode);
        oboeJson.put("sharingMode", oboe.sharingMode);
        oboeJson.put("format", oboe.format);
        o.put("oboe", oboeJson);

        JSONObject opusJson = new JSONObject();
        opusJson.put("bitrate", opus.bitrate);
        opusJson.put("complexity", opus.complexity);
        opusJson.put("application", opus.application);
        opusJson.put("frameSizeMs", opus.frameSizeMs);
        opusJson.put("enableFec", opus.enableFec);
        opusJson.put("enableDtx", opus.enableDtx);
        opusJson.put("vbrMode", opus.vbrMode);
        o.put("opus", opusJson);

        return o.toString(2);
    }

    public static AudioConfig fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        AudioConfig c = new AudioConfig();

        JSONObject oboeJson = o.optJSONObject("oboe");
        if (oboeJson != null) {
            c.oboe.sampleRate = oboeJson.optInt("sampleRate", 48000);
            c.oboe.channels = oboeJson.optInt("channels", 1);
            c.oboe.inputPreset = oboeJson.optInt("inputPreset", 5);
            c.oboe.performanceMode = oboeJson.optInt("performanceMode", 12);
            c.oboe.sharingMode = oboeJson.optInt("sharingMode", 0);
            c.oboe.format = oboeJson.optInt("format", 1);
        }

        JSONObject opusJson = o.optJSONObject("opus");
        if (opusJson != null) {
            c.opus.bitrate = opusJson.optInt("bitrate", 64000);
            c.opus.complexity = opusJson.optInt("complexity", 5);
            c.opus.application = opusJson.optInt("application", 2051);
            c.opus.frameSizeMs = opusJson.optInt("frameSizeMs", 20);
            c.opus.enableFec = opusJson.optBoolean("enableFec", false);
            c.opus.enableDtx = opusJson.optBoolean("enableDtx", false);
            c.opus.vbrMode = opusJson.optInt("vbrMode", 0);
        }
        
        return c;
    }
}
