# WFB-TX Fix: HT MCS Rate Not Applied in USB Transmitter

## Problem

Android VTX was transmitting all packets at 1Mbps legacy rate instead of the configured HT MCS rate (e.g., MCS1 = 6.5Mbps at 20MHz bandwidth). This caused:

- **~100Kbit/s throughput** on the GS instead of the expected ~4Mbps
- **Decrypt errors (d_err)** on the GS due to long air time causing frame corruption
- **Increasing packet loss** as corrupted frames fail FCS checks

OpenIPC air units connected to the same GS showed zero packet loss and zero decrypt errors, confirming the GS setup was correct.

## Root Cause

In `devourer/src/Rtl8812aDevice.cpp`, the `send_packet()` function parses the radiotap header to extract TX parameters (rate, bandwidth, SGI, LDPC, STBC) and builds a hardware TX descriptor for the RTL8812AU USB adapter.

The HT MCS case (`IEEE80211_RADIOTAP_MCS`) only extracted bandwidth and SGI from the radiotap flags but **never read the MCS index** from `iterator.this_arg[2]`. The `fixed_rate` variable was initialized to `MGN_1M` (1Mbps legacy CCK rate) and was never updated in the HT path.

```cpp
// BEFORE (broken):
case IEEE80211_RADIOTAP_MCS: {
    u8 mcs_flags = iterator.this_arg[1];
    // ... reads BW and SGI only ...
    // fixed_rate remains MGN_1M!
} break;
```

The VHT case correctly extracted the MCS/NSS from its radiotap field, so VHT mode would have worked. But the default HT mode (used by OpenIPC and this project) was completely broken.

## Fix

Read the MCS index from `iterator.this_arg[2]` and map it to the correct `MGN_MCSx` hardware rate constant. Also extract LDPC and STBC from the MCS flags (previously only handled in VHT path).

```cpp
// AFTER (fixed):
case IEEE80211_RADIOTAP_MCS: {
    u8 mcs_known = iterator.this_arg[0];
    u8 mcs_flags = iterator.this_arg[1];
    u8 mcs_index = iterator.this_arg[2];
    // ... reads BW and SGI (existing) ...

    // Extract LDPC and STBC from flags
    if (mcs_flags & IEEE80211_RADIOTAP_MCS_FEC_LDPC) ldpc = 1;
    u8 stbc_val = (mcs_flags >> IEEE80211_RADIOTAP_MCS_STBC_SHIFT) & 0x3;
    if (stbc_val) stbc = stbc_val;

    // Map MCS index to hardware rate
    if (mcs_index <= 7)        fixed_rate = MGN_MCS0 + mcs_index;
    else if (mcs_index <= 15)  fixed_rate = MGN_MCS8 + (mcs_index - 8);
    else if (mcs_index <= 23)  fixed_rate = MGN_MCS16 + (mcs_index - 16);
} break;
```

## Files Changed

- `app/wfbngrtl8812/src/main/cpp/devourer/src/Rtl8812aDevice.cpp` - Fixed HT MCS rate extraction

## Additional Fixes in This Branch

1. **USB TX thread safety** - Added mutex in `RtlUsbAdapter` to serialize `send_packet()` calls from multiple TX threads (video, tunnel, telemetry)
2. **UDP port alignment** - Changed tunnel ports to OpenIPC standard (5800/5801)
3. **VPN packet aggregation** - Implemented wfb_tun-compatible 2-byte length prefix framing
4. **Keepalive pings** - Added 500ms keepalive to tunnel TX
5. **TX retry with backpressure** - 10 retries with 5ms delay matching OpenIPC `-J 10 -E 5000`
6. **Bitrate cap** - 8Mbps cap matching OpenIPC `-C 8000`
7. **Telemetry TX thread** - Added radio_port=0x10, UDP 14551, FEC k=1 n=2
8. **I-frame logging** - Added keyframe size/timing logging in CameraStreamer

## Verification

After applying the fix:
- GS video bitrate: ~4Mbps (matching encoder CBR setting)
- Decrypt errors: 0
- Packet loss: 0
- TX stats: ~215-228 packets/sec injected, 0 dropped

## Why This Only Affects USB (devourer) Path

The kernel WiFi driver path (`RawSocketTransmitter`) sends frames via `PF_PACKET` raw sockets where the kernel driver handles the radiotap-to-TX-descriptor conversion. The USB userspace path (`UsbTransmitter` -> `Rtl8812aDevice::send_packet`) must do this conversion manually, and the HT MCS index extraction was simply never implemented.
