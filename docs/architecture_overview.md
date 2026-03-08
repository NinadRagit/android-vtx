# PixelPilot Android VTX Architecture Overview

This document describes the high-level architecture of the Android VTX application, focusing on the target "Headless-First" design. The primary goal of this application is to act as a bidirectional video and telemetry router between a Camera, a Flight Controller, and a Ground Station over a WFB-NG wireless link.

## High-Level Data Flow

The architecture is divided into three primary pipelines running over the WFB-NG network:

1.  **Video Pipeline (Unidirectional Data)**
    *   Captures frames from the physical camera sensor.
    *   Encodes frames using the hardware `MediaCodec` block (H.264/H.265).
    *   Fires encoded NAL units over a local UDP socket into the WFB-NG tunnel.
2.  **Telemetry Pipeline (Bidirectional Data)**
    *   Reads Mavlink packets from a USB-connected Flight Controller.
    *   Routes those packets through the WFB-NG tunnel to the Ground Station.
    *   Receives Mavlink commands from the Ground Station and forwards them to the Flight Controller (or intercepts them for local VTX configuration).
3.  **IP Tunnel (Bidirectional Data)**
    *   A generic bidirectional transparent network tunnel provided by WFB-NG.
    *   Allows arbitrary TCP/UDP traffic between the Ground Station and the Android VTX (e.g., SSH, live configuration, remote debugging).
4.  **UI/Control Layer (Detachable)**
    *   A temporary observer layer (`VideoActivity`) used for debugging.
    *   Draws the OSD and provides manual control buttons.
    *   **Crucially**, this layer must be completely detachable so the core pipelines can run in a headless background service.

## Architecture Diagram

```mermaid
flowchart TD
    subgraph Hardware Layer
        Cam[Camera Sensor]
        FC[Flight Controller \n USB Serial]
        RTL[RTL8812au Wi-Fi Card \n USB]
    end

    subgraph Android VtxService (Headless Core)
        CS[CameraStreamer \n Camera2 API & MediaCodec]
        TR[TelemetryRouter \n Mavlink Bidirectional]
        VPN[WfbNgVpnService \n TUN/UDP Aggregator]
        WLM[WfbLinkManager \n Wi-Fi Card Driver Hooks]
    end

    subgraph Temporary UI Layer
        VA[VideoActivity \n UI God Object]
        OSD[OSDManager \n Telemetry Renderer]
    end

    subgraph Ground Station
        GS[wfb-cl & GStreamer]
        GCS[QGroundControl / Mission Planner]
    end

    %% Video Data Flow
    Cam -->|Raw YUV Frames| CS
    CS -->|Encoded H.264/H.265 \n via UDP 5600| VPN

    %% Telemetry Data Flow
    FC <-->|Raw Mavlink| TR
    TR <-->|Mavlink Packets \n via UDP 14550| VPN
    TR -->|Local Intercepts| VA
    
    %% IP Tunnel Data Flow
    VPN <-->|Transparent Network Link \n SSH / Config Tool| GS
    
    %% OSD Flow
    TR -.->|Telemetry State| OSD
    OSD -.->|Draw Overlay| VA

    %% WFB-NG Air Link
    VPN <-->|wfb_tun Encapsulation| WLM
    WLM <-->|Raw 802.11 Packets| RTL
    RTL <-->|Tri-Tunnel 5.8GHz Link \n Video / Mavlink / IP| GS

    %% Ground Station Split
    GS -->|Video Stream| GS_Video[Video Display]
    GS <-->|Mavlink UDP| GCS
```

## Core Component Responsibilities

### 1. `CameraStreamer.java`
**Role:** The Video Engine.
*   Opens the Android `CameraDevice`.
*   Configures the `MediaCodec` hardware encoder (applying low-latency vendor flags).
*   Manages the "Dummy Surface" workaround to bypass Android's 60fps display VSYNC limits.
*   Outputs raw H.264/H.265 NAL units to `127.0.0.1:5600` via a DatagramSocket.
*   *Future Migration*: Should be entirely decoupled from `VideoActivity` and run inside `VtxService`.

### 2. `WfbNgVpnService.java`
**Role:** The Network Tunnel.
*   Establishes a local Android VPN interface (`10.5.0.3/24`) to simulate a local network for the WFB-NG daemons.
*   Aggregates outbound UDP video packets and forwards them to the `wfb_tx` binary.
*   Handles keepalive heartbeats to keep the tunnel awake.

### 3. `WfbLinkManager.java` & `WfbNgLink`
**Role:** The Hardware Driver Layer.
*   Monitors Android USB intent broadcasts to detect when compatible Wi-Fi cards (like the RTL8812au) are connected.
*   Requests USB permissions.
*   Boots the underlying compiled C binaries (`wfb_tx`, `wfb_rx`) associated with WFB-NG to begin RF injection.

### 4. `OSDManager.java`
**Role:** The Overlay Renderer.
*   Takes parsed Mavlink data (Attitude, GPS, Battery) and formats it into human-readable strings.
*   Updates the on-screen Android Views.
*   *Future Migration*: Will be entirely disabled in the final "Headless" drone build, as OSD drawing will be offloaded to the Ground Station goggles.

### 5. `VideoActivity.java`
**Role:** The Temporary Control Surface (Currently a God Object).
*   Bootstraps the entire application stack.
*   Renders the camera preview (if enabled).
*   Handles user tap events for settings and configurations.
*   *Future Migration*: Needs to be stripped of all hardware/networking initialization code. All setup logic should move to a persistent `VtxService`.
