# Android-VTX Headless Architecture

This document describes the decoupled, headless-first backend architecture for `android-vtx`, as implemented in Phase 4 and Phase 5 of the architectural refactor.

## Flow Diagram

```mermaid
flowchart TD
    %% Hardware Inputs/Outputs
    hw_camera[[Phone Camera Hardware]]
    hw_wfb[[RTL8812AU Wi-Fi Adapter]]
    hw_fc[[Flight Controller USB-OTG]]
    
    %% Headless Service Core
    subgraph VtxService [Headless VtxService (Background Process)]
        subgraph Pipeline [Video Pipeline]
            cam_mgr(Camera Manager)
            surf_dummy[Dummy Surface<br>Provides Hardware Unlock]
            hw_codec(MediaCodec<br>H264/H265 Hardware Encoder)
            udp_vid>UDP Socket<br>:5600]
        end
        
        subgraph WfbNg [WFB-NG Tunnel]
            wfb_daemon((WFB-NG Daemon))
        end
        
        subgraph Telemetry [Bidirectional Telemetry]
            tele_router{Telemetry Router}
            mavlink((MavlinkNative OSD Daemon))
        end
    end

    %% UI Observation Layer
    subgraph UI [VideoActivity (Passive UI)]
        surf_preview[Preview Surface<br>Optional Display]
        osd_view[OSD Overlay View]
    end

    %% Routing
    hw_camera -- Camera2 API --> cam_mgr
    cam_mgr -- Frame Data --> surf_dummy
    cam_mgr -. Optional Preview .-> surf_preview
    
    surf_dummy -- Encodes to --> hw_codec
    hw_codec -- NAL Units --> udp_vid
    udp_vid -- Inject to --> wfb_daemon
    
    wfb_daemon -- Raw Radio Packets --> hw_wfb
    
    %% Telemetry Routing (The UDP Proxy Pattern)
    hw_fc -- "USB Serial" <br> (Flight Controller Data) --> tele_router
    tele_router -- UDP 14551 <br> (Drone -> GS) --> wfb_daemon
    wfb_daemon -- UDP 14550 <br> (GS -> Drone) --> tele_router
    tele_router -- "USB Serial" <br> (Execute GS Commands) --> hw_fc
    
    tele_router -. "UDP 14552" <br> Local Telemetry Sync .-> mavlink
    mavlink -. Render state .-> osd_view
```

## Core Components Overview

### 1. `VtxService`
The persistent foreground service orchestrating the entire system. Because it is completely decoupled from Android `Activity` lifecycles, it allows the VTX to remain active when the screen shuts off, or when running on a screenless stripped-down motherboard.

### 2. `VideoActivity`
A purely passive debugging UI. It connects to the `VtxService` using a `ServiceConnection`. If a user explicitly turns on the "Preview" button, a surface is supplied to `VtxService` and appended to the camera pipeline so the user can see what's happening. The `VideoActivity` can be destroyed natively at any time without impacting the VTX video or telemetry transmission.

### 3. `TelemetryRouter`
The central hub for bidirectional Mavlink parsing. 
WFB-NG acts as a transparent network bridge mapping local Android UDP Ports to the Ground Station's IP. The `TelemetryRouter` sits in the middle:
- It opens a Native USB CDC serial connection to a physically attached Flight Controller.
- Ground station commands received from the WFB-NG tunnel (e.g., Camera changes, RC overrides) are piped down to the FC.
- FC Telemetry (Altitude, GPS) is piped up to the WFB-NG tunnel toward the Ground Station.
- Simultaneously, telemetry state is copied locally to `127.0.0.1:14552`, giving the on-device `MavlinkNative` daemon current data for rendering the local OSD debug canvas.
