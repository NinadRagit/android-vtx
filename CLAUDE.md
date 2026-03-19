# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# First-time setup: initialize git submodules (wfb-ng, mavlink-router, opus)
git submodule update --init --recursive

# Build debug APK
./gradlew assembleDebug

# Build release APK (signed with ../fpv.jks)
./gradlew assembleRelease

# Clean build
./gradlew clean

# Format C++ sources (excludes submodules and .cxx dirs)
./scripts/format_sources.sh

# Install debug APK to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No tests or CI pipelines exist in this project.

## Architecture

Android FPV video transmitter with a **Java control plane** and **C++ NDK data plane**. The app captures camera video, encodes it, and transmits over WiFi using an RTL8812AU USB adapter via the wfb-ng (WiFiBroadcast) protocol.

### Gradle Modules

| Module | Native Library | Purpose |
|--------|---------------|---------|
| `app` | — | Java services, UI, configuration |
| `camera` | `libcamera_native.so` | ACamera2 capture + AMediaCodec encoding + Opus audio |
| `wfbngrtl8812` | `libWfbngRtl8812.so` | RTL8812AU userspace USB driver + wfb-ng FEC/encryption TX/RX |
| `mavlink` | `libmavlink.so` | MAVLink telemetry router (cross-compiled mavlink-router) |

### Headless Design

`VtxService` (foreground service) → `VtxEngine` (orchestrator) → starts all native components via JNI. The UI (`VideoActivity`) is optional and binds to the service when visible.

### Data Flow (TX Mode)

```
ACamera2 → AMediaCodec → UDP:8001 → TxFrame → FEC → ChaCha20Poly1305 → Radiotap → USB → RTL8812AU
```

Three independent TX threads share the USB device (serialized by mutex):
- **Video**: radio_port=0, UDP 8001, FEC 8/12, MCS 2
- **Tunnel**: radio_port=0x20, UDP 5801, FEC 8/12, MCS 0
- **Telemetry**: radio_port=0x10, UDP 14551, FEC 1/2, MCS 1

### Key Classes

**Java control plane** (`app/src/main/java/com/openipc/pixelpilot/`):
- `service/VtxService.java` — Foreground service, key management, USB receivers
- `service/VtxEngine.java` — Orchestrates all native components
- `service/WfbNgVpnService.java` — VPN tunnel (TUN at 10.5.0.3/24, ports 5800/5801)
- `wfb/WfbLinkManager.java` — USB adapter enumeration and lifecycle
- `telemetry/TelemetryRouter.java` — USB serial ↔ UDP bridge (115200 baud)
- `config/CameraConfig.java` — JSON-driven camera/encoder settings
- `config/AudioConfig.java` — JSON-driven Oboe/Opus settings

**C++ data plane**:
- `wfbngrtl8812/src/main/cpp/WfbngLink.cpp` — Radio TX orchestrator, JNI bridge, spawns 3 TX threads
- `wfbngrtl8812/src/main/cpp/TxFrame.cpp` — UDP→FEC→encrypt→inject pipeline
- `wfbngrtl8812/src/main/cpp/devourer/src/Rtl8812aDevice.cpp` — USB WiFi device (send_packet parses radiotap, maps MCS)
- `wfbngrtl8812/src/main/cpp/devourer/src/RtlUsbAdapter.cpp` — libusb bulk transfers (endpoint 0x02, 10 retries)
- `camera/src/main/cpp/video/CameraStreamerNative.cpp` — ACamera2 + AMediaCodec pipeline
- `camera/src/main/cpp/audio/AudioStreamerNative.cpp` — Oboe capture + Opus encoding

## Native Build

Each native module uses CMake (invoked by Gradle). Target ABIs: `arm64-v8a`, `armeabi-v7a`.

- **wfbngrtl8812** uses C++20. Pre-built libs in `wfbngrtl8812/src/main/cpp/libs/{ABI}/` (libusb, libsodium, libpcap)
- **camera** uses C++17. Opus built from vendored submodule. Oboe via Prefab.
- **mavlink** uses C++17. Cross-compiles mavlink-router with Android stubs (no POSIX AIO, custom termios)

Git submodules:
- `wfbngrtl8812/src/main/cpp/wfb-ng` — svpcom/wfb-ng
- `mavlink/src/main/cpp/mavlink-router` — mavlink-router/mavlink-router
- `camera/src/main/cpp/opus` — xiph/opus

## Configuration

- `app/src/main/assets/camera_config.json` — Camera resolution, FPS, AE/AF/AWB, encoder bitrate/codec/profile
- `app/src/main/assets/audio_config.json` — Oboe input settings, Opus encoding params
- `app/src/main/assets/{gs,drone}.key` — WFB encryption keypairs (32-byte secret + 32-byte public)

## Code Style

- C++: `.clang-format` at repo root (LLVM base, 4-space indent, 120-char column limit). Run `./scripts/format_sources.sh`.
- Java: No explicit formatter configured. Standard Android conventions.
- ProGuard keeps all classes with `native` methods and MAVLink JNI interfaces (see `app/proguard-rules.pro`).

## Important Conventions

- The WiFi driver is **userspace** (devourer + libusb) — there is no kernel WiFi stack involved
- `RtlUsbAdapter` uses `shared_ptr<mutex>` for TX serialization because the class is copied by value
- Encryption uses `crypto_aead_chacha20poly1305` (original, NOT ietf variant — 8-byte nonce)
- `channel_id = (link_id << 8) + radio_port` where `link_id = 7669206`
- VPN service uses 2-byte length-prefix framing and 500ms keepalives (matching wfb_tun)
