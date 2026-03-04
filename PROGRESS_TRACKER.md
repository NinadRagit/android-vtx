# Android VTX: Progress Tracker

This document tracks the milestones achieved during the transformation of PixelPilot into **Android VTX**.

## ✅ Milestones Achieved

### 1. Project Infrastructure & Rebranding (Initial Phase)
- **Status**: Completed
- **Changes**:
    - Renamed root project to `android-vtx` in `settings.gradle`.
    - Updated app name to "Android VTX" in `strings.xml`.
    - Re-initialized and added `devourer` and `wfb-ng` as Git submodules for radio support.
    - Updated `README.md` to reflect the VTX mission.

### 2. Camera Pipeline & Hardware Encoding
- **Status**: Completed
- **Changes**:
    - Implemented `CameraStreamer.java` for full camera/encoding pipeline.
    - Integrated **Camera2 API** for 1280x720 capture.
    - Configured **MediaCodec** (Hardware Accelerated) for low-latency H.264 encoding.
    - Implemented **UDP Bridge** sending data to `127.0.0.1:8001` (Radio Input).
    - Added **MTU Fragmentation** (1450 bytes) to prevent radio link congestion.
    - Fixed `NetworkOnMainThreadException` by offloading all IO to background handlers.

### 3. VTX Interface & System Logic
- **Status**: Completed
- **Changes**:
    - Added **"VTX Mode"** toggle in the main Settings menu.
    - Implemented runtime permission logic for Camera access.
    - Added logic to handle Activity restarts and clean-up of hardware resources.
    - Fixed crash related to double camera initialization and aggressive process termination.

## 🛠️ In Progress: Phase 3
- **Objective**: Directing encoded stream into the `wfb-ng` native transmitter.
- **Current Tasks**:
    - Verifying native radio link activation in VTX mode.
    - Testing transmission over USB WiFi card (RTL8812AU).

---
*Last Updated: 2026-03-05*
