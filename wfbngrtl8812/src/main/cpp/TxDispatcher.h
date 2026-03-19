#pragma once

#include <cstdint>
#include <mutex>

#include "devourer/src/SelectedChannel.h"
#include "devourer/src/logger.h"

struct libusb_device_handle;

class TxDispatcher {
public:
    TxDispatcher(libusb_device_handle *dev_handle, Logger_t logger);

    bool send_packet(const uint8_t *packet, size_t length);
    void setChannel(SelectedChannel channel);

private:
    libusb_device_handle *dev_handle_;
    std::mutex tx_mutex_;
    Logger_t logger_;
    SelectedChannel channel_;
};
