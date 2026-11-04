package com.openheadunit.rust;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "RustMainActivity";
    private static final UUID AA_WIRELESS_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    private static final String S25_MAC = "C0:D5:E2:74:26:22";

    private SurfaceView surfaceView;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isBtConnected = new AtomicBoolean(false);
    private Thread rfcommServerThread;
    private Thread rfcommClientThread;
    private BluetoothServerSocket serverSocket;

    // Wi-Fi credentials for S25 Ultra
    private static final String P2P_SSID = "DIRECT-GR-OppoA37";
    private static final String P2P_PASS = "zdJkw85SwLG7";
    private static final String P2P_MAC  = "AA:1B:5A:15:B8:3D";
    private static final int AAP_PORT    = 5277;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        setContentView(surfaceView);

        // Touch forwarding to Rust Native Input Channel
        surfaceView.setOnTouchListener((v, event) -> {
            int actionMasked = event.getActionMasked();
            int aapAction = 2; // DRAG
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                aapAction = 0; // PRESS
            } else if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                aapAction = 1; // RELEASE
            }
            NativeCore.sendTouch(aapAction, (int) event.getX(), (int) event.getY());
            return true;
        });

        // Initialize Native Rust Core
        NativeCore.init();

        isRunning.set(true);

        // 1. Listen for incoming Bluetooth RFCOMM from S25 Ultra
        startBluetoothTriggerServer();

        // 2. Actively attempt Bluetooth RFCOMM to S25 Ultra
        startBluetoothConnector();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created, starting Rust native engine on port " + AAP_PORT);
        NativeCore.startEngine(holder.getSurface(), AAP_PORT);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed, stopping Rust native engine");
        NativeCore.stopEngine();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
    }

    private void startBluetoothTriggerServer() {
        rfcommServerThread = new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "Bluetooth not available or disabled");
                return;
            }

            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("AndroidAuto-Sensor", AA_WIRELESS_UUID);
                Log.i(TAG, "Bluetooth RFCOMM server listening on UUID " + AA_WIRELESS_UUID);

                while (isRunning.get()) {
                    try {
                        BluetoothSocket socket = serverSocket.accept();
                        if (socket != null) {
                            Log.i(TAG, "[INCOMING] Phone connected via RFCOMM: " + socket.getRemoteDevice().getName() + " (" + socket.getRemoteDevice().getAddress() + ")");
                            isBtConnected.set(true);
                            handleRfcommConnection(socket);
                        }
                    } catch (IOException e) {
                        if (!isRunning.get()) break;
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "RFCOMM server error: " + e.getMessage(), e);
            }
        });
        rfcommServerThread.setDaemon(true);
        rfcommServerThread.start();
    }

    private void startBluetoothConnector() {
        rfcommClientThread = new Thread(() -> {
            while (isRunning.get() && !isBtConnected.get()) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    for (BluetoothDevice dev : adapter.getBondedDevices()) {
                        if (S25_MAC.equalsIgnoreCase(dev.getAddress())) {
                            try {
                                Log.i(TAG, "[OUTGOING] Attempting RFCOMM connection to " + dev.getName() + " (" + dev.getAddress() + ")");
                                BluetoothSocket clientSocket = dev.createInsecureRfcommSocketToServiceRecord(AA_WIRELESS_UUID);
                                clientSocket.connect();
                                Log.i(TAG, "[OUTGOING] RFCOMM connected to " + dev.getName());
                                isBtConnected.set(true);
                                handleRfcommConnection(clientSocket);
                                return;
                            } catch (Exception e) {
                                Log.d(TAG, "RFCOMM connect retry: " + e.getMessage());
                            }
                        }
                    }
                }
                try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            }
        });
        rfcommClientThread.setDaemon(true);
        rfcommClientThread.start();
    }

    private void handleRfcommConnection(BluetoothSocket socket) {
        new Thread(() -> {
            try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
                String localIp = getLocalWifiIp();
                Log.i(TAG, "Target projection IP: " + localIp + ":" + AAP_PORT);

                // 1. Immediately send SocketInfoRequest (Type 1)
                byte[] socketInfoReq = buildSocketInfoRequest(localIp, AAP_PORT);
                out.write(socketInfoReq);
                out.flush();
                Log.i(TAG, "[TX] Sent SocketInfoRequest (Type 1) to phone: " + localIp + ":" + AAP_PORT);

                // 2. Read incoming messages from phone
                byte[] header = new byte[4];
                while (isRunning.get() && socket.isConnected()) {
                    int read = in.read(header);
                    if (read < 4) break;

                    int len = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
                    int type = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);

                    byte[] payload = new byte[len];
                    if (len > 0) {
                        int pos = 0;
                        while (pos < len) {
                            int r = in.read(payload, pos, len - pos);
                            if (r <= 0) break;
                            pos += r;
                        }
                    }

                    Log.i(TAG, "[RX] Phone sent RFCOMM message type: " + type + ", len: " + len);

                    if (type == 2) {
                        // NetworkInfoRequest -> Reply with NetworkInfo (Type 3)
                        byte[] netInfo = buildNetworkInfo(P2P_SSID, P2P_PASS, P2P_MAC);
                        out.write(netInfo);
                        out.flush();
                        Log.i(TAG, "[TX] Sent NetworkInfo (Type 3) with credentials: " + P2P_SSID);

                        // Re-send SocketInfoRequest to prompt connection
                        out.write(socketInfoReq);
                        out.flush();
                    } else if (type == 7) {
                        // SocketInfoResponse
                        Log.i(TAG, "[RX] SocketInfoResponse received from phone");
                        break;
                    }
                }
            } catch (IOException e) {
                Log.i(TAG, "RFCOMM socket closed: " + e.getMessage());
            } finally {
                isBtConnected.set(false);
            }
        }).start();
    }

    private String getLocalWifiIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nif.getName();
                if (name.startsWith("p2p") || name.startsWith("wlan")) {
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (ip != null && !ip.isEmpty()) {
                                return ip;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.2.181"; // Stable fallback IP observed on device
    }

    private static void writeVarint(OutputStream os, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            os.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        os.write((int) (value & 0x7F));
    }

    private static void writeFieldVarint(OutputStream os, int field, long value) throws IOException {
        writeVarint(os, (field << 3));
        writeVarint(os, value);
    }

    private static void writeFieldString(OutputStream os, int field, String str) throws IOException {
        byte[] b = str.getBytes(StandardCharsets.UTF_8);
        writeVarint(os, (field << 3) | 2);
        writeVarint(os, b.length);
        os.write(b);
    }

    private static byte[] buildSocketInfoRequest(String ip, int port) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeFieldString(baos, 1, ip);
        writeFieldVarint(baos, 2, port);
        byte[] payload = baos.toByteArray();

        ByteBuffer bb = ByteBuffer.allocate(4 + payload.length);
        bb.putShort((short) payload.length);
        bb.putShort((short) 1); // MessageId 1 = SocketInfoRequest
        bb.put(payload);
        return bb.array();
    }

    private static byte[] buildNetworkInfo(String ssid, String psk, String mac) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeFieldString(baos, 1, ssid);
        writeFieldString(baos, 2, psk);
        writeFieldString(baos, 3, mac);
        writeFieldVarint(baos, 4, 8); // SecurityMode = WPA2_PERSONAL (8)
        writeFieldVarint(baos, 5, 1); // AccessPointType = DYNAMIC (1)
        byte[] payload = baos.toByteArray();

        ByteBuffer bb = ByteBuffer.allocate(4 + payload.length);
        bb.putShort((short) payload.length);
        bb.putShort((short) 3); // MessageId 3 = NetworkInfo
        bb.put(payload);
        return bb.array();
    }
}
