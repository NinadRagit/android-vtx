package com.openipc.pixelpilot;

import android.net.VpnService;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

public class WfbNgVpnService extends VpnService {
    private static final String TAG = "WfbNgVpnService";

    // OpenIPC standard ports
    private static final int TUNNEL_RX_PORT = 5800;
    private static final int TUNNEL_TX_PORT = 5801;

    // Aggregation constants matching wfb_tun behavior
    private static final int AGGREGATION_MTU = 1445;
    private static final int KEEPALIVE_INTERVAL_MS = 500;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread udpToVpnThread;
    private Thread vpnToUdpThread;
    private Thread keepaliveThread;
    private volatile boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            Log.i(TAG, "VPN Service stopping");
            isRunning = false;
            interruptThreads();
            closeVpnInterface();
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "VPN Service started");

        if (isRunning) {
            Log.w(TAG, "VPN Service is already running");
            return START_STICKY;
        }

        try {
            vpnInterface = establishVpnInterface();
            if (vpnInterface == null) {
                throw new Exception("establishVpnInterface returned null (permission likely missing)");
            }
            isRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN interface", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        startVpnThreads(vpnInterface);

        return START_STICKY;
    }

    private ParcelFileDescriptor establishVpnInterface() throws Exception {
        Builder builder = new Builder();
        builder.setSession("wfb-ng");
        builder.addAddress("10.5.0.3", 24);
        builder.addRoute("10.5.0.0", 24);

        ParcelFileDescriptor pfd = builder.establish();
        Log.i(TAG, "VPN interface (wfb-ng) established with IP 10.5.0.3/24");
        return pfd;
    }

    private void startVpnThreads(final ParcelFileDescriptor vpnInterfacePfd) {
        final FileInputStream vpnInput = new FileInputStream(vpnInterfacePfd.getFileDescriptor());
        final FileOutputStream vpnOutput = new FileOutputStream(vpnInterfacePfd.getFileDescriptor());

        // Shared state for keepalive: track when last UDP was sent to tunnel TX
        final AtomicLong lastTxSendTime = new AtomicLong(System.currentTimeMillis());
        // Shared socket reference for keepalive thread
        final DatagramSocket[] txSocketRef = new DatagramSocket[1];

        // Thread: UDP (from wfb_rx via aggregator) -> TUN
        // Receives aggregated packets with 2-byte length prefix framing
        udpToVpnThread = new Thread(() -> {
            Log.i(TAG, "UDP -> VPN thread started (port " + TUNNEL_RX_PORT + ")");
            byte[] buffer = new byte[4096];

            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(TUNNEL_RX_PORT));

                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    int len = packet.getLength();
                    if (len < 1) continue;

                    // Parse aggregated packets: each has 2-byte big-endian length prefix
                    int offset = 0;
                    byte[] data = packet.getData();
                    int dataOffset = packet.getOffset();

                    while (offset + 2 <= len) {
                        int pktLen = ((data[dataOffset + offset] & 0xFF) << 8)
                                   | (data[dataOffset + offset + 1] & 0xFF);
                        offset += 2;

                        if (pktLen == 0) {
                            // Keepalive/empty - skip
                            break;
                        }

                        if (offset + pktLen > len) {
                            Log.w(TAG, "Truncated aggregated packet: need " + pktLen + " have " + (len - offset));
                            break;
                        }

                        try {
                            vpnOutput.write(data, dataOffset + offset, pktLen);
                        } catch (IOException e) {
                            Log.e(TAG, "UDP -> VPN write error", e);
                        }
                        offset += pktLen;
                    }
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "UDP -> VPN thread error", e);
                }
            }
            Log.i(TAG, "UDP -> VPN thread stopped");
        }, "UdpToVpnThread");

        // Thread: TUN -> UDP (to wfb_tx tunnel thread)
        // Reads TUN packets (blocking), wraps each with 2-byte length prefix,
        // and sends immediately. wfb_tun also sends per-packet in practice
        // since TUN reads return one packet at a time.
        vpnToUdpThread = new Thread(() -> {
            Log.i(TAG, "VPN -> UDP thread started (port " + TUNNEL_TX_PORT + ")");
            byte[] tunBuffer = new byte[1500];
            byte[] sendBuffer = new byte[2 + 1500]; // length prefix + packet

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setReuseAddress(true);
                txSocketRef[0] = socket;
                InetSocketAddress dest = new InetSocketAddress("127.0.0.1", TUNNEL_TX_PORT);

                while (isRunning) {
                    // Blocking read from TUN - returns exactly one IP packet
                    int length = vpnInput.read(tunBuffer);
                    if (length == -1) break;
                    if (length == 0) continue;

                    // Wrap with 2-byte big-endian length prefix
                    sendBuffer[0] = (byte) ((length >> 8) & 0xFF);
                    sendBuffer[1] = (byte) (length & 0xFF);
                    System.arraycopy(tunBuffer, 0, sendBuffer, 2, length);

                    DatagramPacket packet = new DatagramPacket(sendBuffer, 2 + length, dest);
                    socket.send(packet);
                    lastTxSendTime.set(System.currentTimeMillis());
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "VPN -> UDP thread error", e);
                }
            }
            Log.i(TAG, "VPN -> UDP thread stopped");
        }, "VpnToUdpThread");

        // Keepalive thread: sends empty UDP to tunnel TX every 500ms when idle
        keepaliveThread = new Thread(() -> {
            Log.i(TAG, "Keepalive thread started");
            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", TUNNEL_TX_PORT);

            while (isRunning) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                    long elapsed = System.currentTimeMillis() - lastTxSendTime.get();
                    if (elapsed >= KEEPALIVE_INTERVAL_MS && txSocketRef[0] != null && !txSocketRef[0].isClosed()) {
                        DatagramPacket packet = new DatagramPacket(new byte[0], 0, dest);
                        txSocketRef[0].send(packet);
                        lastTxSendTime.set(System.currentTimeMillis());
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "Keepalive send error", e);
                    }
                }
            }
            Log.i(TAG, "Keepalive thread stopped");
        }, "KeepaliveThread");

        udpToVpnThread.start();
        vpnToUdpThread.start();
        keepaliveThread.start();
        Log.i(TAG, "VPN threads started");
    }

    private void interruptThreads() {
        if (udpToVpnThread != null) udpToVpnThread.interrupt();
        if (vpnToUdpThread != null) vpnToUdpThread.interrupt();
        if (keepaliveThread != null) keepaliveThread.interrupt();
    }

    private void closeVpnInterface() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close VPN interface", e);
            }
            vpnInterface = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "VPN Service destroyed");
        isRunning = false;
        interruptThreads();
        closeVpnInterface();
    }
}
