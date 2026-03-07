# Android VTX

> [!IMPORTANT]
> This application is a high-performance, ultra-low latency digital video transmitter for Android.
> It transforms a smartphone into a powerful VTX capable of 120 FPS high-speed streaming.

---

## 🚀 Key Features

*   **High-Speed Capture:** Supports **120 FPS** and **240 FPS** via Android's `ConstrainedHighSpeedCaptureSession`.
*   **720p Optimized:** Crisp 1280x720 video encoded with hardware-accelerated **H.264 (Qualcomm OMX)**.
*   **Robust Radio Link:** Integrated `wfb-ng` air link with custom `devourer` userspace driver for **RTL8812AU** adapters.
*   **Intelligent MTU:** Automatic NAL unit fragmentation for reliable transmission over jittery wireless links.
*   **IP Tunneling:** Supports concurrent Mavlink telemetry and IP data over the radio link.

## 🔧 Getting Started

### Hardware Requirements
1.  **Phone:** Rooted Android device with a high-speed camera sensor (e.g., Redmi K20 Pro, Pixel 7, etc.).
2.  **Radio:** RTL8812AU USB WiFi Adapter.
3.  **Cable:** High-quality OTG cable.

### Quick Setup (VTX Mode)
1.  Open **Android VTX**.
2.  Go to **Settings** -> **VTX Mode** -> Toggle **ON**.
3.  Grant **Root** and **Camera** permissions.
4.  Connect your WiFi card and grant USB permissions.
5.  Video will automatically start streaming to your Ground Station!

### Ground Station Pipeline
On your Linux GS, use the following GStreamer command:
```bash
socat -u UDP4-RECV:5600,reuseaddr - | gst-launch-1.0 -v fdsrc ! h264parse ! avdec_h264 ! autovideosink sync=false
```

---

## 🏗️ Build & Install

### Clone
```bash
git clone https://github.com/OpenIPC/android-vtx.git
cd android-vtx
git submodule update --init --recursive
```

### Build
Open the project in **Android Studio v2022.3+**. The `devourer` driver is now vendored directly into the source tree for a simplified build process.

---

## 🗺️ Roadmap & Documentation
*   [ROADMAP.md](ROADMAP.md): Future goals and project milestones.
*   [docs/wfb-tx-fix.md](docs/wfb-tx-fix.md): Technical deep-dive into the RTL8812AU HT MCS bug fix.

---
*Developed by the OpenIPC Community.*
