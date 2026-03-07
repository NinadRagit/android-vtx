# Android VTX: Project Roadmap

This document outlines the journey of Android VTX from a basic video player (PixelPilot) to a high-performance digital video transmitter.

## 🏁 Phase 0: The PixelPilot Extraction (Completed)
*   Forked and rebranded legacy codebase.
*   Fixed critical RTL8812AU transmitter bug (The fallback MCS limit).
*   Corrected thread-safety and memory corruption (Use-After-Free) bugs in the `devourer` driver.

## ✅ Phase 1: 120 FPS High-Speed VTX (Completed)
*   **Video Mastery:** Reached **720p @ 120 FPS** using the `ConstrainedHighSpeedCaptureSession`.
*   **Packet Perfection:** Implemented SPS/PPS header caching and NAL unit fragmentation to prevent GS-side freezing.
*   **Build Optimization:** Vendored the driver source directly to simplify the development environment.

## 🚧 Phase 2: Performance & Stability (In Progress)
*   **Thermal Management:** Optimize encoder profiles to reduce CPU/GPU heat during 120FPS sessions.
*   **Adaptive Link:** Enhance the adaptive FEC logic to respond faster to packet loss at high frame rates.

## 🔮 Phase 3: Future Goals
*   **Extreme FPS:** Explore **240 FPS** mode for 480p/720p.
*   **OSD Overlay:** Implement direct telemetry overlay (battery, signal, speed) on the VTX preview.
*   **HEVC (H.265) Support:** Switch to H.265 for better image quality at identical bitrates (device dependent).
*   **Multiple Adapters:** Support for dual-radio diversity transmission on the VTX side.

---
*Created: March 2026*
