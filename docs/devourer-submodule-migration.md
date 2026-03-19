# Devourer Submodule Migration: TxDispatcher Architecture

## Problem

The upstream [OpenIPC/devourer](https://github.com/OpenIPC/devourer) RTL8812AU driver was vendored into this project with local modifications across ~14 files. These modifications existed because three TX threads (video, tunnel, telemetry) share a single USB device, but the upstream driver was designed for single-threaded use.

### Vendored Modifications

1. **`RtlUsbAdapter` passed by reference everywhere** — The upstream passes `RtlUsbAdapter` by value (copies). We changed the constructor and all internal holders to use references (`RtlUsbAdapter&`) because we added a `std::mutex` to the class, and mutexes are not copyable.

2. **`RtlUsbAdapter::send_packet()` changed from async to synchronous + mutex** — Upstream uses `libusb_submit_transfer` (async). We replaced it with `libusb_bulk_transfer` (synchronous) wrapped in a `std::lock_guard<std::mutex>` so that three TX threads could safely share one USB endpoint.

3. **HT MCS radiotap parsing additions** — The upstream's `Rtl8812aDevice::send_packet()` was missing the `fixed_rate` assignment in the MCS case. It read `mcs_flags` but never set `fixed_rate = MGN_MCS0 + mcs_index`, causing the rate to stay at `MGN_1M` (1 Mbps). This was the root cause of the throughput bug documented in `docs/wfb-tx-fix.md`. Our vendored fix added the correct MCS index reading plus LDPC/STBC extraction.

### Why This Was a Problem

- **Divergence from upstream** — Every upstream update required manually merging ~14 modified files, with risk of regression.
- **Threading concern in wrong layer** — The mutex and synchronous USB logic was embedded inside the driver (RtlUsbAdapter), but the multi-threaded TX design is an application-level concern. The driver shouldn't need to know about threading.
- **By-reference changes were invasive** — Changing RtlUsbAdapter from value to reference semantics required modifications throughout the driver's internal construction chain (WiFiDriver, Rtl8812aDevice, EepromManager, RadioManagementModule, HalModule all pass adapters around).

## Solution: TxDispatcher

A new `TxDispatcher` class was introduced as the single point of contact between TX threads and the USB hardware. This allowed replacing the vendored devourer with the unmodified upstream as a git submodule.

### New Call Chain

```
WfbngLink::run()
  ├─ WiFiDriver::CreateRtlDevice(dev_handle)    [upstream, by-value copies]
  ├─ TxDispatcher(dev_handle, logger)            [NEW — holds mutex + libusb handle]
  ├─ Rtl8812aDevice stored for Init/RX/should_stop/SetTxPower
  │
  └─ 3 TX threads
      └─ TxFrame::run(txDispatcher, args)
          └─ UsbTransmitter(txDispatcher)
              └─ injectPacket()
                  ├─ Build radiotap + ieee80211 + payload buffer
                  └─ txDispatcher->send_packet(buffer, length)
                      ├─ lock mutex
                      ├─ parse radiotap header
                      ├─ build RTL8812A TX descriptor (40 bytes)
                      ├─ libusb_bulk_transfer(0x02, ...)
                      ├─ retry (10x, 5ms delay)
                      └─ unlock mutex
```

### What TxDispatcher Does

TxDispatcher owns the `libusb_device_handle*` and a `std::mutex`. Its `send_packet()` method:

1. **Locks the mutex** — serializes access from 3 TX threads.
2. **Parses the radiotap header** — extracts rate, bandwidth, SGI, LDPC, STBC using the upstream's correct `mcs_have` flag-checking style (checks `HAVE_MCS`, `HAVE_BW`, `HAVE_GI`, `HAVE_FEC`, `HAVE_STBC` before reading values).
3. **Builds the RTL8812A TX descriptor** — 40-byte hardware descriptor using `SET_TX_DESC_*` macros from devourer headers, with the correct `MRateToHwRate()` mapping.
4. **Sends via synchronous USB bulk transfer** — `libusb_bulk_transfer()` to endpoint 0x02 with 500ms timeout.
5. **Retries on failure** — up to 10 retries with 5ms delay, matching OpenIPC's `-J 10 -E 5000` parameters.

### Why This Works Without Modifying Upstream

| Concern | Before (vendored) | After (TxDispatcher) |
|---------|-------------------|---------------------|
| Thread-safe USB TX | Mutex inside `RtlUsbAdapter` | Mutex inside `TxDispatcher` |
| Synchronous USB send | Modified `RtlUsbAdapter::send_packet()` | `TxDispatcher::send_packet()` calls `libusb_bulk_transfer` directly |
| Correct HT MCS rate | Patched `Rtl8812aDevice::send_packet()` | `TxDispatcher::send_packet()` has correct parsing |
| TX retry logic | In `UsbTransmitter::injectPacket()` calling device | Inside `TxDispatcher::send_packet()` |
| TX descriptor building | In `Rtl8812aDevice::send_packet()` | In `TxDispatcher::send_packet()` |

The upstream `Rtl8812aDevice::send_packet()` and `RtlUsbAdapter::send_packet()` are never called from our code. They still exist in the submodule but are dead code in our build. All TX goes through TxDispatcher.

The upstream `Rtl8812aDevice` is still used for:
- `Init()` — hardware initialization + RX packet loop (blocks on main thread)
- `should_stop` — signals the RX loop to exit
- `SetTxPower()` — control transfers (different USB transfer type, no conflict with bulk TX)

## Thread Safety

| Operation | Thread | Protected by |
|-----------|--------|-------------|
| `send_packet()` (3 TX threads) | video / tunnel / telemetry | `TxDispatcher::tx_mutex_` |
| `Init()` / RX loop | main thread (blocks) | Runs before TX threads start sending |
| `SetTxPower()` | JNI thread | Control transfers (different USB transfer type) |
| `libusb_handle_events` | usb_event_thread | Handles RX async events only; TX is synchronous |

## Files Changed

### New Files
- `wfbngrtl8812/src/main/cpp/TxDispatcher.h` — Class declaration
- `wfbngrtl8812/src/main/cpp/TxDispatcher.cpp` — Radiotap parsing, TX descriptor, USB transfer, retry

### Modified Files
- `wfbngrtl8812/src/main/cpp/TxFrame.h` — `UsbTransmitter` and `TxFrame::run()` accept `TxDispatcher*` instead of `Rtl8812aDevice*`
- `wfbngrtl8812/src/main/cpp/TxFrame.cpp` — `UsbTransmitter::injectPacket()` delegates to `TxDispatcher` (retry loop removed from here), `TxFrame::run()` forwards dispatcher
- `wfbngrtl8812/src/main/cpp/WfbngLink.hpp` — Added `TxDispatcher` member
- `wfbngrtl8812/src/main/cpp/WfbngLink.cpp` — Creates `TxDispatcher` after device init, passes to all 3 TX threads, sets channel before `Init()`
- `wfbngrtl8812/src/main/cpp/CMakeLists.txt` — Added `TxDispatcher.cpp` to shared library sources

### Submodule Change
- `wfbngrtl8812/src/main/cpp/devourer/` — Vendored copy removed, replaced with `git submodule add https://github.com/OpenIPC/devourer.git` (pinned at commit `9aaf0e4`)
- `.gitmodules` — New devourer entry added

## Verification Checklist

1. `./gradlew assembleDebug` — native compilation succeeds
2. Install on phone, connect RTL8812AU, start VTX mode
3. Video TX: `ffplay udp://127.0.0.1:5600` on GS — 0 packet loss, 0 d_err
4. Tunnel TX: `ping 10.5.0.3` from GS — packets flowing
5. Telemetry TX: MAVLink data visible on GS
6. All 3 streams simultaneously for extended period — no crashes or USB errors in logcat
