package com.openipc.pixelpilot.ui.osd;

import com.openipc.pixelpilot.R;
import com.openipc.pixelpilot.config.*;
import com.openipc.pixelpilot.hardware.*;
import com.openipc.pixelpilot.telemetry.*;
import com.openipc.pixelpilot.ui.*;
import com.openipc.pixelpilot.ui.osd.*;
import com.openipc.pixelpilot.service.*;
import com.openipc.pixelpilot.wfb.*;


public class OSDElement {
    public String name;
    public MovableLayout layout;

    public OSDElement(String n, MovableLayout l) {
        name = n;
        layout = l;
    }

    public String prefName() {
        return String.format("%d", name.hashCode());
    }
}
