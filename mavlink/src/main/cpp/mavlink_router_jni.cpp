// mavlink_router_jni.cpp
// JNI bridge: starts/stops a mavlink-router Mainloop on a background thread.
//
// Routing topology:
//   UDP:14550 (Java USB bridge) <-> Mainloop <-> UDP:14551 (wfb-ng TX tunnel to GS)
//                                            <-> UDP:14552 (OSD/EncLat local daemon)
//
// DIAGNOSTICS: logcat -s MavRouter

#include <jni.h>
#include <android/log.h>
#include <sys/epoll.h>
#include <cerrno>
#include <cstring>
#include <unistd.h>
#include <thread>
#include <atomic>

// *** IMPORTANT INCLUDE ORDER ***
// Must include the ardupilot MAVLink dialect BEFORE mainloop.h.
// mainloop.h → binlog.h → uses mavlink_remote_log_data_block_t which is only
// defined in the ardupilot dialect (mavlink_msg_remote_log_data_block.h).
// Use ardupilotmega/mavlink.h (the bootstrapper), NOT ardupilotmega.h directly.
// MAVLINK_ROOT (parent of ardupilotmega/) is in the include path so this resolves.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Waddress-of-packed-member"
#include "mavlink-router/modules/mavlink_c_library_v2/ardupilotmega/mavlink.h"
#pragma GCC diagnostic pop

// mavlink-router headers (compiled as mavlink_router_core static lib)
#include "mavlink-router/src/mainloop.h"
#include "mavlink-router/src/endpoint.h"
#include "mavlink-router/src/common/log.h"

#define TAG "MavlinkRouter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ──────────────────────────────────────────────────────────────────────────────
// Globals
// ──────────────────────────────────────────────────────────────────────────────
static std::atomic<bool> g_router_running{false};
static std::thread       g_router_thread;

// Intercepts stderr so core mavlink-router logs appear in logcat
static int               g_log_pipe[2] = {-1, -1};
static std::thread       g_log_thread;

static void stderr_interceptor_thread() {
    char buf[2048];
    while (g_log_pipe[0] >= 0) {
        ssize_t n = read(g_log_pipe[0], buf, sizeof(buf) - 1);
        if (n <= 0) break;
        buf[n] = '\0';
        // strip trailing newline as logcat adds its own
        if (n > 0 && buf[n-1] == '\n') buf[n-1] = '\0';
        // log as debug from inner library
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "[core] %s", buf);
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Router thread
// ──────────────────────────────────────────────────────────────────────────────
static void router_thread_body() {
    LOGI("Router thread started");

    Mainloop &mainloop = Mainloop::init();

    if (mainloop.open() < 0) {
        LOGE("Mainloop::open() failed");
        Mainloop::teardown();
        g_router_running = false;
        return;
    }
    LOGD("Mainloop epoll opened");

    // ── Configuration ────────────────────────────────────────────────────────
    Configuration conf{};
    conf.tcp_port              = 0;      // disable TCP server
    conf.report_msg_statistics = false;
    conf.debug_log_level       = Log::Level::INFO;

    // UDP Server → Java TelemetryRouter (relays USB Serial data)
    {
        UdpEndpointConfig java_cfg{};
        java_cfg.name    = "JavaUsbBridge";
        java_cfg.mode    = UdpEndpointConfig::Mode::Server;
        java_cfg.address = "127.0.0.1";
        java_cfg.port    = 14550;
        conf.udp_configs.push_back(std::move(java_cfg));
    }

    // UDP → wfb-ng telemetry TX port (sends FC MAVLink to Ground Station)
    {
        UdpEndpointConfig wfb_cfg{};
        wfb_cfg.name    = "WfbTunnel";
        wfb_cfg.mode    = UdpEndpointConfig::Mode::Client;
        wfb_cfg.address = "127.0.0.1";
        wfb_cfg.port    = 14551;
        conf.udp_configs.push_back(std::move(wfb_cfg));
    }

    // UDP → OSD/EncLat daemon (our mavlink.cpp local listener on 14552)
    {
        UdpEndpointConfig osd_cfg{};
        osd_cfg.name    = "OsdDaemon";
        osd_cfg.mode    = UdpEndpointConfig::Mode::Client;
        osd_cfg.address = "127.0.0.1";
        osd_cfg.port    = 14552;
        conf.udp_configs.push_back(std::move(osd_cfg));
    }

    if (!mainloop.add_endpoints(conf)) {
        LOGE("add_endpoints() failed");
        Mainloop::teardown();
        g_router_running = false;
        return;
    }
    LOGI("Endpoints added: UDP:14550 (Server) + UDP:14551 + UDP:14552");

    LOGI("Entering mainloop, routing MAVLink packets");
    int ret = mainloop.loop();
    LOGI("Mainloop exited: code=%d", ret);

    Mainloop::teardown();
    g_router_running = false;
    LOGI("Router thread finished");
}

// ──────────────────────────────────────────────────────────────────────────────
// JNI
// ──────────────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_openipc_mavlink_MavlinkRouter_nativeRouterStart(
        JNIEnv * /*env*/, jclass /*cls*/) {

    if (g_router_running.exchange(true)) {
        LOGW("nativeRouterStart: already running");
        return JNI_FALSE;
    }

    // Setup stderr interceptor so we can see the core mavlink-router library logs
    if (g_log_pipe[0] < 0 && pipe(g_log_pipe) == 0) {
        dup2(g_log_pipe[1], STDERR_FILENO);
        g_log_thread = std::thread(stderr_interceptor_thread);
        g_log_thread.detach();
    }

    LOGI("nativeRouterStart: spinning up native daemon");
    g_router_thread = std::thread(router_thread_body);
    g_router_thread.detach();

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_openipc_mavlink_MavlinkRouter_nativeRouterStop(
        JNIEnv * /*env*/, jclass /*cls*/) {

    if (!g_router_running) {
        LOGD("nativeRouterStop: not running");
        return;
    }
    LOGI("nativeRouterStop: requesting mainloop exit");
    // Mainloop::get_instance() asserts _initialized — guard with running flag
    if (g_router_running) {
        Mainloop::get_instance().request_exit(0);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_openipc_mavlink_MavlinkRouter_nativeIsRunning(
        JNIEnv * /*env*/, jclass /*cls*/) {
    return g_router_running ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
