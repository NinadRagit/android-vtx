# Camera Configuration Reference
> **File:** `camera_config.json` (Import via "Import Camera Config" in settings)
> This file directly configures the hardware Camera2 capture session and the MediaCodec encoder.

## Base Structure

```json
{
  "camera": {
    "cameraId": "0",
    "width": 1280,
    "height": 720,
    "fps": 60,
    "aeLocked": false,
    "aeCompensation": 0,
    "afMode": 0,
    "edgeMode": 0,
    "noiseReduction": 0,
    "videoStabilization": false,
    "opticalStabilizationMode": 0,
    "awbMode": 1,
    "awbLocked": false,
    "tonemapMode": 1,
    "lensShadingMode": 1,
    "hotPixelMode": 1,
    "iso": 0,
    "shutterSpeed": 0
  },
  "encoder": {
    "bitrate": 4000000,
    "keyframeInterval": 1,
    "intraRefreshPeriod": 0,
    "codec": "h264",
    "bitrateMode": 0,
    "bFrames": 0,
    "encoderLatency": 0,
    "encoderPriority": 0,
    "operatingRate": 0,
    "codecProfile": 0,
    "codecLevel": 0
  }
}
```

---

## `camera` Layer

Controls the physical sensor and Image Signal Processor (ISP).

### Core Settings
*   **`cameraId`** (String): Defines the physical sensor. `"0"`=Main, `"1"`=Front, `"20"`=Telephoto, `"21"`=Ultra Wide.
*   **`width`** (Int): Output width (e.g. 1280, 1920)
*   **`height`** (Int): Output height (e.g. 720, 1080)
*   **`fps`** (Int): Target framerate. Values > 30 will automatically trigger a Constrained High-Speed Session (only supported on Camera 0).

### Exposure & Focus
*   **`aeLocked`** (Boolean): `true` to lock Auto Exposure, `false` to let it adjust dynamically.
*   **`aeCompensation`** (Int): EV bias. `-12` to `+12`. `0` is neutral. Used to darken sky out in FPV (`-3` or `-6`).
*   **`afMode`** (Int): `0`=Off (Manual), `1`=Auto, `3`=Continuous Video, `4`=Continuous Picture. Use `0` for FPV on main camera.
*   **`iso`** (Int): Manual ISO value (e.g. 100-12800). `0` means auto.
*   **`shutterSpeed`** (Long): Manual shutter in nanoseconds. (e.g. 16666667 for 1/60s). `0` means auto.

### White Balance & Color (AWB)
*   **`awbMode`** (Int): Auto White Balance mode. 
    *   `0` = OFF
    *   `1` = AUTO (Default)
    *   `2` = INCANDESCENT
    *   `3` = FLUORESCENT
    *   `5` = DAYLIGHT
    *   `6` = CLOUDY_DAYLIGHT
*   **`awbLocked`** (Boolean): Lock white balance so colors don't shift mid-flight.

### ISP Tuning & Filters
*   **`edgeMode`** (Int): Sharpening filter. `0`=Off (raw), `1`=Fast (low latency), `2`=High Quality (adds latency).
*   **`noiseReduction`** (Int): Denoising. `0`=Off, `1`=Fast, `2`=High Quality, `3`=Minimal, `4`=Zero Shutter Lag.
*   **`tonemapMode`** (Int): Color mapping. `0`=Contrast Curve, `1`=Fast, `2`=High Quality.
*   **`lensShadingMode`** (Int): Vignette correction. `0`=Off, `1`=Fast, `2`=High Quality.
*   **`hotPixelMode`** (Int): Dead pixel correction. `0`=Off, `1`=Fast, `2`=High Quality.

### Stabilization
*   **`videoStabilization`** (Boolean): Software Electronic Image Stabilization (EIS). `false` (off) is recommended to prevent latency.
*   **`opticalStabilizationMode`** (Int): Hardware OIS (if your camera has it). `0`=Off, `1`=On.

---

## `encoder` Layer

Controls the MediaCodec hardware video compression pipeline.

### Core Settings
*   **`codec`** (String): `"h264"` (Recommended) or `"h265"`.
*   **`bitrate`** (Int): Target bits per second. E.g., `4000000` (4 Mbps).
*   **`bitrateMode`** (Int): `0`=CBR (Constant Bitrate - Recommended for FPV), `1`=VBR (Variable), `2`=CQ (Constant Quality).

### Keyframes & Refresh
*   **`keyframeInterval`** (Int): Seconds between full IDR frames. `1` is recommended for fast recovery.
*   **`intraRefreshPeriod`** (Int): Number of frames to spread an intra-refresh over. `0` disables. Set to `fps/2` for smooth streaming without IDR spikes.

### Advanced Latency Controls
*   **`bFrames`** (Int): Number of B-frames. **Always `0` for FPV**. Values > 0 require look-ahead buffering, which adds severe latency.
*   **`encoderLatency`** (Int): `0`=Realtime limit (output instantly), `1`=Normal (might buffer frames).
*   **`encoderPriority`** (Int): `0`=Realtime (steal resources from OS), `1`=Best-Effort.
*   **`operatingRate`** (Int): FPS hint to pre-allocate hardware. Set `0` to automatically match `camera.fps`.

### Codec Profile and Level Constraints
*   **`codecProfile`** (Int): `0`=Auto. H.264: `1`=Baseline, `2`=Main, `8`=High. H.265/HEVC: `1`=Main, `2`=Main10. **Warning:** Setting this aggressively can crash some device HALs (e.g. Qualcomm). Use `0` unless necessary.
*   **`codecLevel`** (Int): `0`=Auto. Represents the level (e.g. for H.264 `40`=4.0, `41`=4.1. For H.265 `1048576`=HEVCMainTierLevel41). **Warning:** Setting this aggressively can crash some device HALs. Use `0` unless necessary.
