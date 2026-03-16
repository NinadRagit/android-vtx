#include <jni.h>
#include <android/native_window_jni.h>
#include "../video/CameraStreamerNative.h"
#include "../audio/AudioStreamerNative.h"

// Singleton instances
static CameraStreamerNative* g_videoStreamer = nullptr;
static AudioStreamerNative* g_audioStreamer = nullptr;

/**
 * Helper: extract a NativeCameraConfig from a Java CameraConfig object.
 * Reads all fields via JNI reflection to populate the C++ struct.
 */
static NativeCameraConfig extractConfig(JNIEnv* env, jobject jConfig) {
    NativeCameraConfig cfg;

    jclass configClass = env->GetObjectClass(jConfig);

    // Get the CameraLayer object
    jfieldID camField = env->GetFieldID(configClass, "camera", "Lcom/openipc/pixelpilot/config/CameraConfig$CameraLayer;");
    jobject camObj = env->GetObjectField(jConfig, camField);
    jclass camClass = env->GetObjectClass(camObj);

    // Camera fields
    jfieldID fid;

    fid = env->GetFieldID(camClass, "cameraId", "Ljava/lang/String;");
    jstring jCameraId = (jstring) env->GetObjectField(camObj, fid);
    if (jCameraId) {
        const char* str = env->GetStringUTFChars(jCameraId, nullptr);
        cfg.cameraId = str;
        env->ReleaseStringUTFChars(jCameraId, str);
    }

    fid = env->GetFieldID(camClass, "width", "I");
    cfg.width = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "height", "I");
    cfg.height = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "fps", "I");
    cfg.fps = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "aeLocked", "Z");
    cfg.aeLocked = env->GetBooleanField(camObj, fid);

    fid = env->GetFieldID(camClass, "aeCompensation", "I");
    cfg.aeCompensation = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "afMode", "I");
    cfg.afMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "edgeMode", "I");
    cfg.edgeMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "noiseReduction", "I");
    cfg.noiseReduction = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "videoStabilization", "Z");
    cfg.videoStabilization = env->GetBooleanField(camObj, fid);

    fid = env->GetFieldID(camClass, "opticalStabilizationMode", "I");
    cfg.opticalStabilizationMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "awbMode", "I");
    cfg.awbMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "awbLocked", "Z");
    cfg.awbLocked = env->GetBooleanField(camObj, fid);

    fid = env->GetFieldID(camClass, "tonemapMode", "I");
    cfg.tonemapMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "lensShadingMode", "I");
    cfg.lensShadingMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "hotPixelMode", "I");
    cfg.hotPixelMode = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "iso", "I");
    cfg.iso = env->GetIntField(camObj, fid);

    fid = env->GetFieldID(camClass, "shutterSpeed", "J");
    cfg.shutterSpeed = env->GetLongField(camObj, fid);

    // Get the EncoderLayer object
    jfieldID encField = env->GetFieldID(configClass, "encoder", "Lcom/openipc/pixelpilot/config/CameraConfig$EncoderLayer;");
    jobject encObj = env->GetObjectField(jConfig, encField);
    jclass encClass = env->GetObjectClass(encObj);

    fid = env->GetFieldID(encClass, "bitrate", "I");
    cfg.bitrate = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "keyframeInterval", "I");
    cfg.keyframeInterval = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "intraRefreshPeriod", "I");
    cfg.intraRefreshPeriod = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "codec", "Ljava/lang/String;");
    jstring jCodec = (jstring) env->GetObjectField(encObj, fid);
    if (jCodec) {
        const char* str = env->GetStringUTFChars(jCodec, nullptr);
        cfg.codec = str;
        env->ReleaseStringUTFChars(jCodec, str);
    }

    fid = env->GetFieldID(encClass, "bitrateMode", "I");
    cfg.bitrateMode = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "bFrames", "I");
    cfg.bFrames = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "encoderLatency", "I");
    cfg.encoderLatency = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "encoderPriority", "I");
    cfg.encoderPriority = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "operatingRate", "I");
    cfg.operatingRate = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "codecProfile", "I");
    cfg.codecProfile = env->GetIntField(encObj, fid);

    fid = env->GetFieldID(encClass, "codecLevel", "I");
    cfg.codecLevel = env->GetIntField(encObj, fid);

    return cfg;
}

static NativeAudioConfig extractAudioConfig(JNIEnv* env, jobject jConfig) {
    NativeAudioConfig cfg;
    jclass configClass = env->GetObjectClass(jConfig);

    jfieldID oboeField = env->GetFieldID(configClass, "oboe", "Lcom/openipc/pixelpilot/config/AudioConfig$OboeLayer;");
    jobject oboeObj = env->GetObjectField(jConfig, oboeField);
    jclass oboeClass = env->GetObjectClass(oboeObj);

    cfg.sampleRate = env->GetIntField(oboeObj, env->GetFieldID(oboeClass, "sampleRate", "I"));
    cfg.channels = env->GetIntField(oboeObj, env->GetFieldID(oboeClass, "channels", "I"));
    cfg.inputPreset = env->GetIntField(oboeObj, env->GetFieldID(oboeClass, "inputPreset", "I"));
    cfg.performanceMode = env->GetIntField(oboeObj, env->GetFieldID(oboeClass, "performanceMode", "I"));
    cfg.sharingMode = env->GetIntField(oboeObj, env->GetFieldID(oboeClass, "sharingMode", "I"));
    cfg.format = env->GetIntField(oboeObj, env->GetFieldID(oboeClass, "format", "I"));

    jfieldID opusField = env->GetFieldID(configClass, "opus", "Lcom/openipc/pixelpilot/config/AudioConfig$OpusLayer;");
    jobject opusObj = env->GetObjectField(jConfig, opusField);
    jclass opusClass = env->GetObjectClass(opusObj);

    cfg.bitrate = env->GetIntField(opusObj, env->GetFieldID(opusClass, "bitrate", "I"));
    cfg.complexity = env->GetIntField(opusObj, env->GetFieldID(opusClass, "complexity", "I"));
    cfg.application = env->GetIntField(opusObj, env->GetFieldID(opusClass, "application", "I"));
    cfg.frameSizeMs = env->GetIntField(opusObj, env->GetFieldID(opusClass, "frameSizeMs", "I"));
    cfg.enableFec = env->GetBooleanField(opusObj, env->GetFieldID(opusClass, "enableFec", "Z"));
    cfg.enableDtx = env->GetBooleanField(opusObj, env->GetFieldID(opusClass, "enableDtx", "Z"));
    cfg.vbrMode = env->GetIntField(opusObj, env->GetFieldID(opusClass, "vbrMode", "I"));

    return cfg;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_openipc_camera_CameraNative_nativeStartStreaming(
        JNIEnv* env, jclass clazz, jobject jVideoConfig, jobject jAudioConfig, jobject jSurface) {

    if (g_videoStreamer && g_videoStreamer->isStreaming()) {
        LOGW("nativeStartStreaming called while already streaming — stopping first");
        g_videoStreamer->stop();
        delete g_videoStreamer;
        
        if (g_audioStreamer) {
            g_audioStreamer->stop();
            delete g_audioStreamer;
        }
    }

    g_videoStreamer = new CameraStreamerNative();
    g_audioStreamer = new AudioStreamerNative();

    NativeCameraConfig vCfg = extractConfig(env, jVideoConfig);
    NativeAudioConfig aCfg = extractAudioConfig(env, jAudioConfig);

    ANativeWindow* previewWindow = nullptr;
    if (jSurface != nullptr) {
        previewWindow = ANativeWindow_fromSurface(env, jSurface);
    }

    bool ok = g_videoStreamer->start(vCfg, previewWindow);
    if (!ok) {
        LOGE("Native video pipeline failed to start");
        delete g_videoStreamer;
        g_videoStreamer = nullptr;
        if (previewWindow) ANativeWindow_release(previewWindow);
    } else {
        // Start audio pipeline
        if (!g_audioStreamer->start(aCfg)) {
            LOGE("Native audio pipeline failed to start (Option E / Oboe/Opus)");
        }
    }
}

JNIEXPORT void JNICALL
Java_com_openipc_camera_CameraNative_nativeStopStreaming(
        JNIEnv* env, jclass clazz) {
    if (g_videoStreamer) {
        g_videoStreamer->stop();
        delete g_videoStreamer;
        g_videoStreamer = nullptr;
        LOGI("Native video pipeline destroyed");
    }
    if (g_audioStreamer) {
        g_audioStreamer->stop();
        delete g_audioStreamer;
        g_audioStreamer = nullptr;
        LOGI("Native audio pipeline destroyed");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_openipc_camera_CameraNative_nativeIsStreaming(
        JNIEnv* env, jclass clazz) {
    return (g_videoStreamer && g_videoStreamer->isStreaming()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
