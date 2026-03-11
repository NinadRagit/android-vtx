# Android-VTX Headless Architecture

This document describes the decoupled, headless-first backend architecture for `android-vtx`, optimized for high-performance NDK execution.

## Flow Diagram

```mermaid
flowchart TD
    %% Hardware Inputs/Outputs
    hw_camera[[Phone Camera Hardware]]
    hw_wfb[[RTL8812AU Wi-Fi Adapter]]
    hw_fc[[Flight Controller USB-OTG]]
    
    %% Headless Service Core
    subgraph VtxService [Headless VtxService (Background Process)]
        subgraph DataPlane [NDK Data Plane]
            cam_native(CameraNative C++<br>ACamera / AMediaCodec)
            udp_vid>UDP Socket<br>localhost:8001]
            wfb_daemon((libwfbng driver))
        end
        
        subgraph ControlPlane [Java Control Plane]
            engine{VtxEngine}
            tele_router[Telemetry Router<br>USB-Serial Bridge]
        end
        
        subgraph Telemetry [Telemetry Stack]
            mav_router((mavlink-router daemon))
            mav_native(MavlinkNative C++<br>Scheduler & Whiteboard)
        end
    end

    %% UI Observation Layer
    subgraph UI [VideoActivity (Passive UI)]
        surf_preview[Preview Surface<br>Optional Display]
        osd_view[OSD Overlay View]
    end

    %% Routing
    hw_camera -- NDK ACamera --> cam_native
    cam_native -- AMediaCodec --> udp_vid
    cam_native -. Optional Preview .-> surf_preview
    
    udp_vid -- Inject --> wfb_daemon
    wfb_daemon -- Raw Radio --> hw_wfb
    
    %% Telemetry Routing
    hw_fc -- "USB Serial" --> tele_router
    tele_router -- UDP 14550 --> mav_router
    mav_router -- UDP 14551 --> wfb_daemon
    
    %% Cross-Library Telemetry (Zero JNI)
    cam_native -- "dlsym (Latency)" --> mav_native
    mav_native -- "Injection" --> mav_router
    
    mav_native -. "JNI Polling" .-> engine
    engine -. Update .-> osd_view
```

## Core Components Overview

### 1. `VtxService` & `VtxEngine`
The persistent infrastructure layer. `VtxService` is a foreground service that prevents the OS from killing the telemetry and video streams. `VtxEngine` orchestrates the lifecycle of all native components.

### 2. NDK Data Plane (`libcamera_native.so`)
Directly interfaces with `ACameraManager` and `AMediaCodec`. By bypassing the Java `Camera2` framework for the capture loop, we eliminate Garbage Collection (GC) jitter and achieve deterministic 240 FPS encoding. Encoded NAL units are handed off to the WFB-NG pipeline via a local UDP socket.

### 3. Telemetry Stack (`mavlink-router`)
We use `mavlink-router` as the central MAVLink hub. 
- **Internal Routing**: Telemetry data from the Flight Controller is bridged to UDP 14550 by the Java `TelemetryRouter`.
- **Native Scheduler**: A C++ thread in `libmavlink.so` provides the high-frequency telemetry (Heartbeats, Encoder Latency).
- **Zero-JNI Bridge**: The camera library resolves a symbol in the MAVLink library via `dlsym` to push latency updates into the native whiteboard without crossing back into Java.

### 4. `VideoActivity`
A purely optional UI binding. It allows for on-device preview and OSD display but can be closed or minimized without affecting the transmission.
