package com.openipc.pixelpilot.config;

import com.openipc.pixelpilot.R;
import com.openipc.pixelpilot.config.*;
import com.openipc.pixelpilot.hardware.*;
import com.openipc.pixelpilot.telemetry.*;
import com.openipc.pixelpilot.ui.*;
import com.openipc.pixelpilot.ui.osd.*;
import com.openipc.pixelpilot.service.*;
import com.openipc.pixelpilot.wfb.*;


public interface SettingsChanged {
    void onChannelSettingChanged(final int channel);
    void onBandwidthSettingChanged(final int bw);
}
