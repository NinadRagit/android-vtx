# Camera Hardware Reference
> **Generated from:** Live `CameraDiagnostic.java` scan on device  
> **Standard:** Camera2 API — 100% hardware-verified values  
> **Key Note:** Values marked `[HS]` require `createConstrainedHighSpeedCaptureSession`

---

## Quick Reference

| ID | Role | Facing | Focal | Aperture | Max Std FPS | High-Speed FPS |
|---|---|---|---|---|---|---|
| **0** | Main | Back | 4.77 mm | **f/1.75** | 30 | **30/48/60/90/120/180/240** ✅ |
| **1** | Front | Front | 3.52 mm | f/2.2 | 30 | ❌ None |
| **20** | Telephoto | Back | 5.54 mm | f/2.4 | 30 | ❌ None |
| **21** | Ultra Wide | Back | 2.04 mm | f/2.4 | 30 | ❌ None |

> **Standard session** = normal `createCaptureSession`, FPS via `CONTROL_AE_TARGET_FPS_RANGE`  
> **High-speed session** = `createConstrainedHighSpeedCaptureSession`, only Camera ID 0

---

## Camera ID 0 — Main Camera

### Identity & Lens
| Property | Value |
|---|---|
| Facing | Back |
| Hardware Level | **LEVEL_3** |
| Focal Length | **4.77 mm** |
| Aperture | **f/1.75** (brightest on device) |
| Optical Stabilization | OFF only (no hardware OIS) |
| Min Focus Distance | **10.0 diopters = 0.10 m (10 cm)** |

### Sensor
| Property | Value |
|---|---|
| Physical Size | **6.4 × 4.8 mm** |
| Pixel Array | **4000 × 3000** (12 MP) |
| Active Array | (0,0) → (4000, 3000) |
| Color Filter | **RGGB** |
| Flash | ✅ Yes |
| ISO Range | **100 – 12,800** |
| Exposure Range | 14,343 ns (1/69,700 s) → 35.056 s |

### Capabilities
`BACKWARD_COMPATIBLE` · **`CONSTRAINED_HIGH_SPEED_VIDEO`** · `RAW` · `YUV_REPROCESSING` · `PRIVATE_REPROCESSING` · `READ_SENSOR_SETTINGS` · **`MANUAL_SENSOR`** · `BURST_CAPTURE` · `MANUAL_POST_PROCESSING`

### AE / AF / AWB
| Feature | Available Modes |
|---|---|
| AE Modes | `OFF` · `ON` · `ON_AUTO_FLASH` · `ON_ALWAYS_FLASH` · `ON_AUTO_FLASH_REDEYE` |
| AE Compensation | -12 to +12 steps × 1/6 EV = **±2.0 EV** |
| AE Lock | ✅ Yes |
| AF Modes | **`OFF`** · `AUTO` · `MACRO` · **`CONTINUOUS_VIDEO`** · `CONTINUOUS_PICTURE` |
| AWB Modes | `OFF` · `AUTO` · `INCANDESCENT` · `FLUORESCENT` · `WARM_FLUORESCENT` · `DAYLIGHT` · `CLOUDY_DAYLIGHT` · `TWILIGHT` · `SHADE` |
| AWB Lock | ✅ Yes |

### ISP Modes
| Feature | Available Modes |
|---|---|
| Noise Reduction | `OFF` · `FAST` · `HIGH_QUALITY` · `MINIMAL` · `ZERO_SHUTTER_LAG` |
| Edge Enhancement | `OFF` · `FAST` · `HIGH_QUALITY` · `ZERO_SHUTTER_LAG` |
| Video Stabilization | `OFF` · `ON` |
| Tonemap | `CONTRAST_CURVE` · `FAST` · `HIGH_QUALITY` |
| Lens Shading | `OFF` · `FAST` · `HIGH_QUALITY` |
| Hot Pixel | `OFF` · `FAST` · `HIGH_QUALITY` |

### Standard Output Resolutions (AE-controlled, max 30 FPS)
All 26 standard sizes: max 30 FPS via AE. Standard session FPS: **[24, 30]**

| Resolution | Aspect | Notes |
|---|---|---|
| 4000×3000 | 4:3 | Full 12MP |
| 4000×2250 | 16:9 | |
| 3840×2160 | 16:9 | 4K |
| 3264×2448 | 4:3 | |
| 3000×3000 | 1:1 | |
| 2688×1512 | 16:9 | |
| 2592×1940 | 4:3 | |
| 2592×1196 | ~2.1:1 | |
| 2448×2448 | 1:1 | |
| 1920×1440 | 4:3 | |
| 2340×1080 | ~2.1:1 | |
| 2160×1080 | 2:1 | |
| **1920×1080** | **16:9** | **Recommended FPV standard** |
| 1600×1200 | 4:3 | |
| 1440×1080 | 4:3 | |
| 1280×960 | 4:3 | |
| 1560×720 | ~2.1:1 | |
| 1440×720 | 2:1 | |
| **1280×720** | **16:9** | **Recommended FPV low-latency** |
| 800×600 | 4:3 | |
| 720×480 | 3:2 | |
| 640×480 | 4:3 | |
| 640×360 | 16:9 | |
| 352×288 | ~1.2:1 | |
| 320×240 | 4:3 | |
| 176×144 | ~1.2:1 | |

### High-Speed Output (requires `createConstrainedHighSpeedCaptureSession`)
| Resolution | Aspect | Achievable FPS |
|---|---|---|
| **1920×1080** | 16:9 | **30, 48, 60, 90, 120, 180, 240** |
| **1280×720** | 16:9 | **30, 48, 60, 90, 120, 180, 240** |
| 720×480 | 3:2 | 30, 48, 60, 90, 120, 180, 240 |
| 640×480 | 4:3 | 30, 48, 60, 90, 120, 180, 240 |

> Raw HS ranges: `[[30, 120], [120, 120], [30, 240], [240, 240]]`  
> Any integer FPS within [30–240] is valid in a HS session. The listed values are standard ones.

### FPV Recommendations
- ✅ **Best overall FPV camera** — best aperture, only camera with high-speed support
- ✅ **Use 60 FPS @ 1280×720 or 1920×1080** via HS session for smooth FPV
- ✅ **120/240 FPS** available for fast-action racing
- ✅ Manual ISO (100–12800) and shutter speed for full manual exposure control
- ✅ `CONTINUOUS_VIDEO` AF available for tracking subjects

---

## Camera ID 1 — Front Camera

### Identity & Lens
| Property | Value |
|---|---|
| Facing | **Front** |
| Hardware Level | LEVEL_3 |
| Focal Length | 3.52 mm |
| Aperture | f/2.2 |
| Optical Stabilization | OFF only |
| Min Focus Distance | **0.0 — Fixed Focus** |

### Sensor
| Property | Value |
|---|---|
| Physical Size | 4.666 × 3.492 mm |
| Pixel Array | **2592 × 1940** (~5 MP) |
| Active Array | (0,0) → (2592, 1940) |
| Color Filter | **GRBG** |
| Flash | ❌ None |
| ISO Range | **100 – 3,200** |
| Exposure Range | 16,080 ns → 1.054 s |

### Capabilities
`BACKWARD_COMPATIBLE` · `RAW` · `YUV_REPROCESSING` · `PRIVATE_REPROCESSING` · `READ_SENSOR_SETTINGS` · `MANUAL_SENSOR` · `BURST_CAPTURE` · `MANUAL_POST_PROCESSING`  
❌ **No CONSTRAINED_HIGH_SPEED_VIDEO**

### AE / AF / AWB
| Feature | Available Modes |
|---|---|
| AE Modes | `OFF` · `ON` (no flash modes) |
| AE Compensation | ±2.0 EV (same as others) |
| AE Lock | ✅ Yes |
| AF Modes | **`OFF` only** (fixed-focus sensor) |
| AWB Modes | Same 9 modes as other cameras |
| AWB Lock | ✅ Yes |

### ISP Modes
Same full ISP coverage as Main: `OFF/FAST/HQ/MINIMAL/ZSL` for noise reduction & edge; EIS `OFF/ON`; full tonemap/shading/hotpixel modes.

### Standard Output Resolutions (max 30 FPS, no high-speed)
19 sizes. FPS achievable: **[15, 24, 30]**

`2592×1940` · `2592×1196` · `1920×1440` · `2340×1080` · `2160×1080` · **`1920×1080`** · `1600×1200` · `1440×1080` · `1280×960` · `1560×720` · `1440×720` · **`1280×720`** · `800×600` · `720×480` · `640×480` · `640×360` · `352×288` · `320×240` · `176×144`

### High-Speed Output
❌ **Not supported**

### FPV Notes
- 🔶 Front-facing only — limited FPV utility
- ⚠️ Fixed-focus, no AF
- ⚠️ Max 30 FPS, no flash, lower ISO

---

## Camera ID 20 — Telephoto Camera

### Identity & Lens
| Property | Value |
|---|---|
| Facing | Back |
| Hardware Level | LEVEL_3 |
| Focal Length | **5.54 mm** (longest = most zoomed) |
| Aperture | f/2.4 |
| Optical Stabilization | OFF only |
| Min Focus Distance | **3.33 diopters = 0.30 m (30 cm)** |

### Sensor
| Property | Value |
|---|---|
| Physical Size | 3.656 × 2.742 mm |
| Pixel Array | **3264 × 2448** (~8 MP) |
| Active Array | (0,0) → (3264, 2448) |
| Color Filter | **BGGR** |
| Flash | ✅ Yes |
| ISO Range | **100 – 3,410** |
| Exposure Range | 13,430 ns → 0.880 s |

### Capabilities
`BACKWARD_COMPATIBLE` · `RAW` · `YUV_REPROCESSING` · `PRIVATE_REPROCESSING` · `READ_SENSOR_SETTINGS` · `MANUAL_SENSOR` · `BURST_CAPTURE` · `MANUAL_POST_PROCESSING`  
❌ **No CONSTRAINED_HIGH_SPEED_VIDEO**

### AE / AF / AWB
| Feature | Available Modes |
|---|---|
| AE Modes | `OFF` · `ON` · `ON_AUTO_FLASH` · `ON_ALWAYS_FLASH` · `ON_AUTO_FLASH_REDEYE` |
| AE Lock | ✅ Yes |
| AF Modes | **`OFF`** · `AUTO` · `MACRO` · **`CONTINUOUS_VIDEO`** · `CONTINUOUS_PICTURE` |
| AWB Modes | Same 9-mode set |
| AWB Lock | ✅ Yes |

### ISP Modes
Full coverage: same as Main camera. EIS `OFF/ON` supported.

### Standard Output Resolutions (max 30 FPS, no high-speed)
22 sizes. FPS achievable: **[15, 24, 30]**

`3264×2448` · `2688×1512` · `2592×1940` · `2592×1196` · `2448×2448` · `1920×1440` · `2340×1080` · `2160×1080` · **`1920×1080`** · `1600×1200` · `1440×1080` · `1280×960` · `1560×720` · `1440×720` · **`1280×720`** · `800×600` · `720×480` · `640×480` · `640×360` · `352×288` · `320×240` · `176×144`

### High-Speed Output
❌ **Not supported**

### FPV Notes
- ✅ Best for long-range observation / inspection flights (narrowest FOV = magnification)
- ✅ Has full AF including `CONTINUOUS_VIDEO` — good for tracking distant objects
- ⚠️ Smallest sensor (3.66mm) — weakest low-light performance
- ⚠️ Max 30 FPS — no high-speed path
- ⚠️ Narrowest FOV — impractical for general FPV flying

---

## Camera ID 21 — Ultra Wide Camera

### Identity & Lens
| Property | Value |
|---|---|
| Facing | Back |
| Hardware Level | LEVEL_3 |
| Focal Length | **2.04 mm** (widest FOV on device) |
| Aperture | f/2.4 |
| Optical Stabilization | OFF only |
| Min Focus Distance | **0.0 — Fixed Focus (hyperfocal)** |

### Sensor
| Property | Value |
|---|---|
| Physical Size | 4.713 × 3.494 mm |
| Pixel Array | **4224 × 3136** (~13 MP — largest array) |
| Active Array | (8,8) → (4216, 3128) |
| Color Filter | **GRBG** |
| Flash | ✅ Yes |
| ISO Range | **100 – 3,200** |
| Exposure Range | 10,225 ns → 0.670 s |

### Capabilities
`BACKWARD_COMPATIBLE` · `RAW` · `YUV_REPROCESSING` · `PRIVATE_REPROCESSING` · `READ_SENSOR_SETTINGS` · `MANUAL_SENSOR` · `BURST_CAPTURE` · `MANUAL_POST_PROCESSING`  
❌ **No CONSTRAINED_HIGH_SPEED_VIDEO**

### AE / AF / AWB
| Feature | Available Modes |
|---|---|
| AE Modes | `OFF` · `ON` · `ON_AUTO_FLASH` · `ON_ALWAYS_FLASH` · `ON_AUTO_FLASH_REDEYE` |
| AE Lock | ✅ Yes |
| AF Modes | **`OFF` only** — Fixed-focus (hyperfocal, ~0.3m–∞ all in focus) |
| AWB Modes | Same 9-mode set |
| AWB Lock | ✅ Yes |

### ISP Modes
Full coverage: same as Main camera. EIS `OFF/ON` supported.

### Standard Output Resolutions (max 30 FPS, no high-speed)
29 sizes — **largest resolution set on device**. FPS achievable: **[15, 24, 30]**

`4208×3120` · `4096×2160` · `4000×3000` · `4000×2250` · `3840×2160` · `3264×2448` · `3120×3120` · `3000×3000` · `2688×1512` · `2592×1940` · `2592×1196` · `2448×2448` · `1920×1440` · `2340×1080` · `2160×1080` · **`1920×1080`** · `1600×1200` · `1440×1080` · `1280×960` · `1560×720` · `1440×720` · **`1280×720`** · `800×600` · `720×480` · `640×480` · `640×360` · `352×288` · `320×240` · `176×144`

### High-Speed Output
❌ **Not supported — hardware HAL hard-limits to 30 FPS at all resolutions**

### FPV Notes
- ✅ **Best for wide-angle FPV** — widest FOV, most immersive view
- ✅ **Fixed focus = always sharp**, zero AF hunting or jitter
- ✅ Largest pixel array = most detail in good light
- ⚠️ **Absolute 30 FPS ceiling** — HAL confirmed, cannot be bypassed via Camera2
- ⚠️ Lower ISO ceiling (3200) — worse low-light than main (12800)
- ⚠️ Shorter max exposure (0.67s vs 35s on main)

---

## Complete Comparison

| Feature | Main (0) | Front (1) | Telephoto (20) | Ultra Wide (21) |
|---|---|---|---|---|
| Facing | Back | Front | Back | Back |
| Focal Length | 4.77 mm | 3.52 mm | **5.54 mm** | **2.04 mm** |
| Aperture | **f/1.75** | f/2.2 | f/2.4 | f/2.4 |
| Sensor Size | **6.4×4.8 mm** | 4.67×3.49 mm | 3.66×2.74 mm | 4.71×3.49 mm |
| Pixel Array | 4000×3000 | 2592×1940 | 3264×2448 | **4224×3136** |
| Color Filter | RGGB | GRBG | BGGR | GRBG |
| Max ISO | **12,800** | 3,200 | 3,410 | 3,200 |
| Max Exposure | **35.056 s** | 1.054 s | 0.880 s | 0.670 s |
| Min Exposure | 14,343 ns | 16,080 ns | 13,430 ns | 10,225 ns |
| Focus | Variable (10cm+) | Fixed | Variable (30cm+) | Fixed (hyperfocal) |
| AF | ✅ Full | ❌ | ✅ Full | ❌ |
| Flash | ✅ | ❌ | ✅ | ✅ |
| High-Speed | **✅ Up to 240 FPS** | ❌ | ❌ | ❌ |
| Std Max FPS | 30 | 30 | 30 | 30 |
| # Resolutions (Std) | 26 | 19 | 22 | **29** |
| HW Level | LEVEL_3 | LEVEL_3 | LEVEL_3 | LEVEL_3 |

---

## FPV Use Case Matrix

| Use Case | Camera | Resolution | FPS | Session |
|---|---|---|---|---|
| Standard FPV | **Main (0)** | 1280×720 | 30 | Standard |
| Low-latency FPV | **Main (0)** | 1280×720 | **60** | HS |
| Smooth cinematic | **Main (0)** | 1920×1080 | **60** | HS |
| Fast FPV Racing | **Main (0)** | 1280×720 | **120** | HS |
| Extreme slow-mo | **Main (0)** | 1280×720 | **240** | HS |
| Wide-angle FPV | **Ultra Wide (21)** | 1920×1080 | 30 | Standard |
| Wide-angle + quality | **Ultra Wide (21)** | 3840×2160 | 30 | Standard |
| Long-range viewing | **Telephoto (20)** | 1920×1080 | 30 | Standard |
| Pilot-facing camera | **Front (1)** | 1280×720 | 30 | Standard |
| Low-light FPV | **Main (0)** | 1920×1080 | 30 | Standard |
