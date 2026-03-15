// xtermios_stub.cpp
// Android NDK stub for xtermios functions from mavlink-router.
//
// xtermios.cpp is excluded from the build because Android's termios struct
// has a different c_cc array size than what Linux-desktop xtermios.cpp
// assumes (size mismatch causes a static_assert failure).
//
// On Android we don't reconfigure UART terminal settings — the USB serial
// fd is already owned by Android's USB host framework and configured via
// the UsbSerialPort Java API. So these stubs returning 0 (success, no-op)
// are correct for our use case.

#include <termios.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "MavRouter"

// Called by UartEndpoint::open() when opening a UART. On Android, the fd
// is pre-configured by the Java layer, so we return success without changes.
int reset_uart(int fd) {
    __android_log_print(ANDROID_LOG_DEBUG, TAG,
        "reset_uart(fd=%d): no-op stub (Android USB serial pre-configured)", fd);
    return 0;
}
