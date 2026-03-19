#include "TxDispatcher.h"

#include <chrono>
#include <cstring>
#include <thread>

#include <libusb.h>

#include "devourer/src/Rtl8812aDevice.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

static constexpr int TX_MAX_RETRIES = 10;
static constexpr int TX_RETRY_DELAY_US = 5000;
static constexpr int USB_TX_TIMEOUT = 500;

TxDispatcher::TxDispatcher(libusb_device_handle *dev_handle, Logger_t logger)
    : dev_handle_(dev_handle), logger_(std::move(logger)), channel_{} {}

void TxDispatcher::setChannel(SelectedChannel channel) { channel_ = channel; }

bool TxDispatcher::send_packet(const uint8_t *packet, size_t length) {
    std::lock_guard<std::mutex> lock(tx_mutex_);

    bool vht = false;
    int ret = 0;
    u8 fixed_rate = MGN_1M, sgi = 0, bwidth = 0, ldpc = 0, stbc = 0;
    u16 txflags = 0;
    int rate_id = 0;
    int radiotap_length = int(packet[2]);
    int real_packet_length = static_cast<int>(length) - radiotap_length;

    if (radiotap_length != 0x0d)
        vht = true;

    int usb_frame_length = real_packet_length + TXDESC_SIZE;

    // Parse radiotap header
    struct ieee80211_radiotap_header *rtap_hdr;
    rtap_hdr = (struct ieee80211_radiotap_header *)packet;
    struct ieee80211_radiotap_iterator iterator;
    ret = ieee80211_radiotap_iterator_init(&iterator, rtap_hdr, radiotap_length, NULL);
    while (!ret) {
        ret = ieee80211_radiotap_iterator_next(&iterator);

        if (ret)
            continue;

        switch (iterator.this_arg_index) {
        case IEEE80211_RADIOTAP_RATE:
            fixed_rate = *iterator.this_arg;
            break;

        case IEEE80211_RADIOTAP_TX_FLAGS:
            txflags = get_unaligned_le16(iterator.this_arg);
            break;

        case IEEE80211_RADIOTAP_MCS: {
            u8 mcs_have = iterator.this_arg[0];
            u8 mcs_flags = iterator.this_arg[1];
            u8 mcs_index = iterator.this_arg[2];

            if (mcs_have & IEEE80211_RADIOTAP_MCS_HAVE_MCS) {
                fixed_rate = mcs_index & 0x7f;
                if (fixed_rate > 31)
                    fixed_rate = 0;
                fixed_rate += MGN_MCS0;
            }

            if ((mcs_have & IEEE80211_RADIOTAP_MCS_HAVE_BW)) {
                uint8_t mcs_bw_field = mcs_flags & IEEE80211_RADIOTAP_MCS_BW_MASK;
                if (mcs_bw_field == IEEE80211_RADIOTAP_MCS_BW_40) {
                    bwidth = CHANNEL_WIDTH_40;
                } else {
                    bwidth = CHANNEL_WIDTH_20;
                }
            }

            if ((mcs_have & IEEE80211_RADIOTAP_MCS_HAVE_GI) && (mcs_flags & IEEE80211_RADIOTAP_MCS_SGI))
                sgi = 1;

            if ((mcs_have & IEEE80211_RADIOTAP_MCS_HAVE_FEC) && (mcs_flags & IEEE80211_RADIOTAP_MCS_FEC_LDPC))
                ldpc = 1;

            if (mcs_have & IEEE80211_RADIOTAP_MCS_HAVE_STBC)
                stbc = (mcs_flags >> IEEE80211_RADIOTAP_MCS_STBC_SHIFT) & 0x3;
        } break;

        case IEEE80211_RADIOTAP_VHT: {
            u8 known = iterator.this_arg[0];
            u8 flags = iterator.this_arg[2];
            unsigned int mcs, nss;
            if ((known & 4) && (flags & 4))
                sgi = 1;
            if ((known & 1) && (flags & 1))
                stbc = 1;
            if (known & 0x40) {
                auto bw = iterator.this_arg[3] & 0x1f;
                if (bw >= 1 && bw <= 3)
                    bwidth = CHANNEL_WIDTH_40;
                else if (bw >= 4 && bw <= 10)
                    bwidth = CHANNEL_WIDTH_80;
                else
                    bwidth = CHANNEL_WIDTH_20;
            }

            if (iterator.this_arg[8] & 1)
                ldpc = 1;
            mcs = (iterator.this_arg[4] >> 4) & 0x0f;
            nss = iterator.this_arg[4] & 0x0f;
            if (nss > 0) {
                if (nss > 4)
                    nss = 4;
                if (mcs > 9)
                    mcs = 9;
                fixed_rate = MGN_VHT1SS_MCS0 + ((nss - 1) * 10 + mcs);
            }
        } break;

        default:
            break;
        }
    }
    (void)txflags;

    // Build USB TX descriptor frame
    auto usb_frame = std::make_unique<uint8_t[]>(usb_frame_length);
    std::memset(usb_frame.get(), 0, usb_frame_length);

    logger_->debug("fixed rate:{}, sgi:{}, radiotap_bwidth:{}, ldpc:{}, stbc:{}", (int)fixed_rate, (int)sgi,
                   (int)bwidth, (int)ldpc, (int)stbc);

    uint8_t BWSettingOfDesc;
    if (bwidth == CHANNEL_WIDTH_40)
        BWSettingOfDesc = 1;
    else if (bwidth == CHANNEL_WIDTH_80)
        BWSettingOfDesc = 2;
    else
        BWSettingOfDesc = 0;

    uint8_t *desc = usb_frame.get();

    SET_TX_DESC_DATA_BW_8812(desc, BWSettingOfDesc);
    SET_TX_DESC_FIRST_SEG_8812(desc, 1);
    SET_TX_DESC_LAST_SEG_8812(desc, 1);
    SET_TX_DESC_OWN_8812(desc, 1);
    SET_TX_DESC_PKT_SIZE_8812(desc, static_cast<uint32_t>(real_packet_length));
    SET_TX_DESC_OFFSET_8812(desc, static_cast<uint8_t>(TXDESC_SIZE + OFFSET_SZ));
    SET_TX_DESC_MACID_8812(desc, static_cast<uint8_t>(0x01));

    if (!vht)
        rate_id = 7;
    else
        rate_id = 9;

    SET_TX_DESC_BMC_8812(desc, 1);
    SET_TX_DESC_RATE_ID_8812(desc, static_cast<uint8_t>(rate_id));
    SET_TX_DESC_QUEUE_SEL_8812(desc, 0x12);
    SET_TX_DESC_HWSEQ_EN_8812(desc, static_cast<uint8_t>(0));
    SET_TX_DESC_SEQ_8812(desc, GetSequence(packet + radiotap_length));
    SET_TX_DESC_RETRY_LIMIT_ENABLE_8812(desc, static_cast<uint8_t>(1));
    SET_TX_DESC_DATA_RETRY_LIMIT_8812(desc, static_cast<uint8_t>(0));

    if (sgi)
        SET_TX_DESC_DATA_SHORT_8812(desc, 1);

    SET_TX_DESC_DISABLE_FB_8812(desc, 1);
    SET_TX_DESC_USE_RATE_8812(desc, 1);
    SET_TX_DESC_TX_RATE_8812(desc, static_cast<uint8_t>(MRateToHwRate(fixed_rate)));

    if (ldpc)
        SET_TX_DESC_DATA_LDPC_8812(desc, ldpc);

    SET_TX_DESC_DATA_STBC_8812(desc, stbc & 3);

    rtl8812a_cal_txdesc_chksum(desc);

    // Copy packet data (after radiotap header) into USB frame after TX descriptor
    std::memcpy(desc + TXDESC_SIZE, packet + radiotap_length, real_packet_length);

    // USB bulk transfer with retry
    bool success = false;
    for (int attempt = 0; attempt <= TX_MAX_RETRIES; ++attempt) {
        int actual_length = 0;
        int rc = libusb_bulk_transfer(dev_handle_, 0x02, desc, usb_frame_length, &actual_length, USB_TX_TIMEOUT);
        if (rc == LIBUSB_SUCCESS && actual_length == usb_frame_length) {
            success = true;
            break;
        }
        if (attempt < TX_MAX_RETRIES) {
            logger_->error("TX retry {}/{}, rc={}, actual={}", attempt + 1, TX_MAX_RETRIES, rc, actual_length);
            std::this_thread::sleep_for(std::chrono::microseconds(TX_RETRY_DELAY_US));
        }
    }

    return success;
}
