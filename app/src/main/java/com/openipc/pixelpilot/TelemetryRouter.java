package com.openipc.pixelpilot;

import android.content.Context;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;

/**
 * Handles Bidirectional telemetry bridging between a USB Serial connected Flight Controller
 * and the local MavlinkRouter C++ daemon (which handles the actual routing to WFB-NG/OSD).
 */
public class TelemetryRouter implements SerialInputOutputManager.Listener {

    private static final String TAG = "TelemetryRouter";
    // MAVLink router daemon listens here for the USB serial stream
    private static final int ROUTER_PORT = 14550;
    private static final String UDP_HOST = "127.0.0.1";
    private static final int SERIAL_BAUD_RATE = 115200;

    private static final String ACTION_USB_PERMISSION = "com.openipc.pixelpilot.USB_PERMISSION";

    private final Context context;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;

    private DatagramSocket udpSocket;
    private InetAddress udpAddress;
    private Thread udpListenerThread;
    private volatile boolean isRunning = false;

    public TelemetryRouter(Context context) {
        this.context = context;

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
    }

    /**
     * Starts the telemetry router, attempting to connect to the Flight Controller
     * and opening the UDP relay socket.
     */
    public void start() {
        if (isRunning) return;
        isRunning = true;

        setupUdpSocket();
        connectToFlightController();
    }

    /**
     * Stops the bridging and cleans up resources.
     */
    public void stop() {
        isRunning = false;
        disconnectFlightController();
        closeUdpSocket();
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException e) {
            // Ignored if not registered
        }
    }

    private void setupUdpSocket() {
        try {
            // Bind to an ephemeral port
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.bind(new InetSocketAddress(0));
            
            udpAddress = InetAddress.getByName(UDP_HOST);

            udpListenerThread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                while (isRunning && udpSocket != null && !udpSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        udpSocket.receive(packet);
                        
                        // Data from MavlinkRouter C++ daemon -> Write back to USB Serial (Flight Controller)
                        if (usbSerialPort != null && usbSerialPort.isOpen()) {
                            usbSerialPort.write(packet.getData(), 200);
                        }
                    } catch (IOException e) {
                        if (isRunning) Log.e(TAG, "UDP Receive Error", e);
                    }
                }
            });
            udpListenerThread.start();
            Log.d(TAG, "UDP Relay Socket setup complete.");

        } catch (Exception e) {
            Log.e(TAG, "Failed to setup UDP Relay Socket", e);
        }
    }

    private void closeUdpSocket() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }
        if (udpListenerThread != null) {
            udpListenerThread.interrupt();
            udpListenerThread = null;
        }
    }

    private void connectToFlightController() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        
        if (availableDrivers.isEmpty()) {
            Log.w(TAG, "No USB Serial devices found. Telemetry bridging inactive.");
            return;
        }

        // Ideally, we filter by VID/PID. For now, we take the first available.
        // We shouldn't grab the RTL8812AU wifi card though, as it's not a serial port!
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();
        
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "Requesting USB permission for Flight Controller: " + device.getDeviceName());
            int flags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ? 
                        PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            manager.requestPermission(device, permissionIntent);
            return;
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        
        if (connection == null) {
            // Permissions are missing, or device is busy.
            Log.e(TAG, "Failed to open connection to USB Serial Device. Missing permissions?");
            return;
        }

        usbSerialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        try {
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(SERIAL_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            usbIoManager.start();
            Log.d(TAG, "Successfully connected to Flight Controller USB Serial.");
            
        } catch (IOException e) {
            Log.e(TAG, "Error opening USB Serial Port", e);
            disconnectFlightController();
        }
    }

    private void disconnectFlightController() {
        if (usbIoManager != null) {
            usbIoManager.stop();
            usbIoManager = null;
        }
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                // Ignored
            }
            usbSerialPort = null;
        }
    }

    // ------------------------------------------------------------------------
    // SerialInputOutputManager.Listener (Flight Controller -> Android -> UDP)
    // ------------------------------------------------------------------------

    @Override
    public void onNewData(byte[] data) {
        // Data received from the Flight Controller (USB Serial)
        // Forward it raw to the native mavlink-router C++ daemon over UDP
        if (udpSocket != null && !udpSocket.isClosed() && udpAddress != null) {
            try {
                // Send raw serial stream to the router port
                DatagramPacket txPacket = new DatagramPacket(data, data.length, udpAddress, ROUTER_PORT);
                udpSocket.send(txPacket);
            } catch (IOException e) {
                Log.e(TAG, "Error forwarding telemetry to MavlinkRouter UDP", e);
            }
        }
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "USB Serial Disconnected / Error", e);
        disconnectFlightController();
    }

    // ------------------------------------------------------------------------
    // USB Broadcast Receiver
    // ------------------------------------------------------------------------

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectToFlightController();
                        }
                    } else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // Determine if it's a serial device (let the prober check)
                connectToFlightController();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (usbSerialPort != null && device != null && device.equals(usbSerialPort.getDevice())) {
                    Log.d(TAG, "Flight Controller detached!");
                    disconnectFlightController();
                }
            }
        }
    };
}
