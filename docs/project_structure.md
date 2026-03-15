# Android-VTX Project Structure

This document outlines the high-level architecture mapped to the physical directory structure of the repository, updated for the NDK-centric reorganization.

## High-Level Layout

```text
android-vtx/
├── app/                  # The UI, Foreground Service, and Data Plane Coordinator (Java)
├── camera/               # Native Media Library (Video & Audio Capture, Hardware Encoding, RTP)
├── mavlink/              # Native MAVLink Library (JNI bridge to mavlink-router daemon)
├── wfbngrtl8812/         # Native Air-Link Library (WFB-NG implementation for RTL8812AU)
└── docs/                 # Architectural documentation and diagrams
```

## Detailed File Map

### `app/` - Application Layer
Coordinates the native libraries and manages the Android lifecycle.

*   `.../ui/`
    *   **`VideoActivity.java`**: The sole Android UI screen. Responsible for binding Android `SurfaceTexture` to the native encoder and overlaying the OSD.
    *   **`osd/OSDManager.java`**: Manages the drawing of the on-device MAVLink telemetry heads-up display.
*   `.../service/`
    *   **`VtxService.java`**: A persistent Android Foreground Service that keeps the video and telemetry pipelines running even when the screen is locked.
    *   **`VtxEngine.java`**: The "brain" of the application. It acts as the central hub, instantiating and wiring together the `CameraNative`, `WfbNgLink`, and `TelemetryRouter`.
    *   **`WfbNgVpnService.java`**: Establishes a local Android VPN interface (`tun0`) necessary to capture outgoing Mavlink/Video UDP traffic and route it out through the custom WFB-NG Wi-Fi driver.
*   `.../telemetry/`
    *   **`TelemetryRouter.java`**: Bridges the USB Serial connection (from the physical Flight Controller hardware via OTG) to the local UDP telemetry sockets used by WFB-NG.
*   `.../hardware/`
    *   **`CameraHelper.java`**: Utilities for querying Android Camera2 API characteristics (resolutions, FPS ranges).
    *   **`UsbDeviceFilter.java`**: Detects and requests user permission when a supported USB Flight Controller is plugged in.
*   `.../wfb/`
    *   **`WfbLinkManager.java`**: High-level Java abstraction for starting, stopping, and monitoring the connection state of the native `wfbngrtl8812` library.
*   `.../config/`
    *   **`CameraConfig.java`**: Data class holding user-defined target settings (FPS, bitrate, H.264/H.265 selection).

---

### `camera/` - Native Media Layer
High-performance C++ pipeline for capturing, encoding, and packetizing media with zero Java overhead.

*   `src/main/cpp/jni/`
    *   **`camera_native_jni.cpp`**: The JNI boundary. Exposes `StartStreaming` and `StopStreaming` to the Java `VtxEngine`.
*   `src/main/cpp/video/`
    *   **`CameraStreamerNative.cpp/h`**: Uses NDK `ACamera` to capture raw frames and passes them to the `AMediaCodec` hardware encoder block. It then fragments the generated Annex-B NAL units into standard RFC 6184/7798 RTP packets and fires them over UDP to WFB-NG.
*   `src/main/cpp/audio/`
    *   **`AudioStreamerNative.cpp/h`**: Uses `Oboe` to capture raw 48kHz audio directly from the device's physical microphones, encodes it into 20ms chunks using statically linked `libopus`, and multiplexes it as an RTP PT=98 stream over the video tunnel.
*   `src/main/cpp/opus/`
    *   Static `libopus` source tree built inline via CMake for minimum-latency audio compression.

---

### `mavlink/` - Native Telemetry Layer
*   `src/main/cpp/telemetry_bridge.cpp`: A JNI wrapper that exposes an IPC method (via `dlsym`) for the `camera` library to inject its hyper-accurate native "Encoder Latency" metric directly into the outgoing MAVLink heartbeat stream, completely bypassing Java.

---

### `wfbngrtl8812/` - Native Air-Link Layer (WFB-NG)
*   `src/main/cpp/wfb-ng/`: Portions of the open-source WFB-NG project.
    *   **`tx.cpp`**: The core transmission engine. Listens on `localhost:8001` for the UDP multiplexed RTP Audio/Video stream (sent by `camera`) and injects it directly into the RTL8812AU Wi-Fi driver using custom 802.11 monitor-mode packet structures with Forward Error Correction (FEC).
*   `src/main/java/`: Simple JNI bindings to start/stop the `tx` process from Java.
