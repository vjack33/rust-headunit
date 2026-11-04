package com.openheadunit.rust;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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

    // Official Android Auto Wireless Protocol UUID
    private static final UUID AA_WIRELESS_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    // Bluetooth Hands-Free Profile (Hands-Free Unit server)
    private static final UUID HFP_HF_UUID      = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb");
    // Bluetooth Audio Gateway (phone's Hands-Free target for wakeup poke)
    private static final UUID HFP_AG_UUID      = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb");

    private static final String S25_MAC = "C0:D5:E2:74:26:22";

    private SurfaceView surfaceView;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isAaConnected = new AtomicBoolean(false);
    private final AtomicBoolean isHfpConnected = new AtomicBoolean(false);
    private final AtomicBoolean hasPoked = new AtomicBoolean(false);

    private BluetoothServerSocket aaServerSocket;
    private BluetoothServerSocket hfpServerSocket;
    private BluetoothSocket activeHfpSocket;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;

    // Wi-Fi credentials for S25 Ultra (Wi-Fi Direct Group Owner)
    private volatile String p2pSsid = "DIRECT-GR-OppoA37";
    private volatile String p2pPass = "zdJkw85SwLG7";
    private volatile String p2pBssid = "AA:1B:5A:15:B8:3D";
    private volatile String p2pGoIp  = "192.168.37.100";
    private static final int AAP_PORT    = 5288;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        hideSystemUI();

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        // Pin the Surface buffer to the exact video resolution so SurfaceFlinger
        // composites at 1:1 pixel ratio — prevents DPI-scaled cropping on hdpi displays.
        surfaceView.getHolder().setFixedSize(1280, 720);
        setContentView(surfaceView,
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Touch forwarding to Rust Native Input Channel (scaled 1:1 to 1280x720)
        surfaceView.setOnTouchListener((v, event) -> {
            int actionMasked = event.getActionMasked();
            int aapAction = 2; // DRAG
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                aapAction = 0; // PRESS
            } else if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                aapAction = 1; // RELEASE
            }
            int viewW = v.getWidth();
            int viewH = v.getHeight();
            int touchX = (int) event.getX();
            int touchY = (int) event.getY();
            if (viewW > 0 && viewH > 0 && (viewW != 1280 || viewH != 720)) {
                touchX = (int) (event.getX() * 1280f / viewW);
                touchY = (int) (event.getY() * 720f / viewH);
            }
            NativeCore.sendTouch(aapAction, touchX, touchY);
            return true;
        });

        // Initialize Native Rust Core
        NativeCore.init();
        isRunning.set(true);

        // Register mDNS/NSD service _aawireless._tcp on port 5288
        registerNsd();

        // Start Wi-Fi Direct Autonomous Group
        startWifiDirectGroup();

        // 1. Listen for Android Auto Wireless Trigger (UUID 4de17a00-52cb-11e6-bdf4-0800200c9a66)
        startAaRfcommServer();

        // 2. Stand-in Hands-Free Profile Server (UUID 0000111e-0000-1000-8000-00805f9b34fb)
        startHfpServer();

        // 3. Bluetooth Wakeup Poke Loop to S25 Ultra Audio Gateway (UUID 0000111f-...)
        startWakeupPokeLoop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
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
        unregisterNsd();
        unregisterP2pReceiver();
        if (p2pManager != null && p2pChannel != null) {
            try {
                p2pManager.removeGroup(p2pChannel, null);
            } catch (Exception ignored) {}
        }
        closeQuietly(aaServerSocket);
        closeQuietly(hfpServerSocket);
        closeQuietly(activeHfpSocket);
    }

    private void registerNsd() {
        try {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                Log.w(TAG, "[NSD] NsdManager not available on this device");
                return;
            }
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("AAWireless");
            serviceInfo.setServiceType("_aawireless._tcp");
            serviceInfo.setPort(AAP_PORT);

            nsdListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "[NSD] Registered: " + serviceInfo.getServiceName());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "[NSD] Registration failed: " + errorCode);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "[NSD] Unregistered");
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "[NSD] Unregistration failed: " + errorCode);
                }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener);
            Log.i(TAG, "[NSD] Registering AAWireless on port " + AAP_PORT);
        } catch (Exception e) {
            Log.e(TAG, "[NSD] Error registering service: " + e.getMessage());
        }
    }

    private void unregisterNsd() {
        if (nsdManager != null && nsdListener != null) {
            try {
                nsdManager.unregisterService(nsdListener);
                Log.i(TAG, "[NSD] Service unregistered");
            } catch (Exception ignored) {}
            nsdListener = null;
        }
    }

    private void registerP2pReceiver() {
        if (p2pReceiver != null) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

            p2pReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                        if (networkInfo != null && networkInfo.isConnected()) {
                            Log.i(TAG, "[P2P] Broadcast: Connection changed -> Connected! Querying info...");
                            queryP2pLiveInfo();
                        }
                    }
                }
            };
            registerReceiver(p2pReceiver, filter);
            Log.i(TAG, "[P2P] BroadcastReceiver registered for connection changes.");
        } catch (Exception e) {
            Log.e(TAG, "[P2P] Error registering receiver: " + e.getMessage());
        }
    }

    private void unregisterP2pReceiver() {
        if (p2pReceiver != null) {
            try {
                unregisterReceiver(p2pReceiver);
            } catch (Exception ignored) {}
            p2pReceiver = null;
        }
    }

    private void queryP2pLiveInfo() {
        if (p2pManager == null || p2pChannel == null) return;
        p2pManager.requestConnectionInfo(p2pChannel, info -> {
            if (info != null && info.groupFormed) {
                if (info.groupOwnerAddress != null) {
                    p2pGoIp = info.groupOwnerAddress.getHostAddress();
                    Log.i(TAG, "[P2P LIVE] Group Owner IP: " + p2pGoIp);
                }
                if (info.isGroupOwner) {
                    p2pManager.requestGroupInfo(p2pChannel, group -> {
                        if (group != null) {
                            p2pSsid = group.getNetworkName();
                            String pass = group.getPassphrase();
                            if (pass != null && !pass.isEmpty()) {
                                p2pPass = pass;
                            } else {
                                fetchPassphraseFromSupplicant(p2pSsid);
                            }
                            p2pBssid = getP2pMac();
                            Log.i(TAG, "[P2P LIVE GROUP] SSID=" + p2pSsid + " Pass=" + p2pPass + " BSSID=" + p2pBssid + " Iface=" + group.getInterface());
                        }
                    });
                }
            }
        });
    }

    private void fetchPassphraseFromSupplicant(String targetSsid) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/vendor/wifi/wpa/p2p_supplicant.conf"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String curSsid = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("ssid=\"")) {
                    curSsid = line.substring(6, line.length() - 1);
                } else if (line.startsWith("psk=\"") && curSsid != null) {
                    String psk = line.substring(5, line.length() - 1);
                    if (curSsid.equals(targetSsid)) {
                        p2pPass = psk;
                        Log.i(TAG, "[P2P SUPPLICANT] Matched PSK for " + targetSsid + " = " + psk);
                        break;
                    }
                }
            }
            reader.close();
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "[P2P SUPPLICANT] Error reading supplicant: " + e.getMessage());
        }
    }

    private void startWifiDirectGroup() {
        try {
            p2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            if (p2pManager == null) {
                Log.w(TAG, "[P2P] WifiP2pManager not available");
                return;
            }
            p2pChannel = p2pManager.initialize(this, getMainLooper(), () -> {
                Log.w(TAG, "[P2P] Channel disconnected");
            });

            registerP2pReceiver();

            // Populate live credentials from any currently active group
            queryP2pLiveInfo();

            // Remove any stale group first, then create fresh group
            p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() { createP2pGroup(); }
                @Override
                public void onFailure(int reason) { createP2pGroup(); }
            });
        } catch (Exception e) {
            Log.e(TAG, "[P2P] Error initializing P2P: " + e.getMessage());
        }
    }

    private void createP2pGroup() {
        if (p2pManager == null || p2pChannel == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiP2pConfig.Builder configBuilder = new WifiP2pConfig.Builder();
                configBuilder.setNetworkName("DIRECT-GR-OppoA37");
                configBuilder.setPassphrase("zdJkw85SwLG7");
                configBuilder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ);
                configBuilder.enablePersistentMode(true);
                WifiP2pConfig config = configBuilder.build();

                Log.i(TAG, "[P2P] Creating group with fixed credentials: DIRECT-GR-OppoA37 / zdJkw85SwLG7...");
                p2pManager.createGroup(p2pChannel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "[P2P] createGroup with config SUCCESS!");
                        queryP2pLiveInfo();
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.w(TAG, "[P2P] createGroup with config failed (" + reason + "), falling back to standard createGroup...");
                        createStandardP2pGroup();
                    }
                });
                return;
            } catch (Exception e) {
                Log.w(TAG, "[P2P] createGroup with config threw: " + e.getMessage());
            }
        }
        createStandardP2pGroup();
    }

    private void createStandardP2pGroup() {
        p2pManager.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "[P2P] standard createGroup SUCCESS! Waiting for group formation...");
                new Thread(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    queryP2pLiveInfo();
                }).start();
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "[P2P] standard createGroup failed with reason: " + reason);
                queryP2pLiveInfo();
            }
        });
    }

    private String getP2pMac() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.getName().startsWith("p2p")) {
                    byte[] mac = nif.getHardwareAddress();
                    if (mac != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < mac.length; i++) {
                            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "AA:1B:5A:15:B8:3D";
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 1. Android Auto RFCOMM Server
     * Listens on 4de17a00-52cb-11e6-bdf4-0800200c9a66
     */
    private void startAaRfcommServer() {
        new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "Bluetooth not available or disabled");
                return;
            }

            try {
                aaServerSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("AndroidAuto", AA_WIRELESS_UUID);
                Log.i(TAG, "[AA-SERVER] Listening on UUID " + AA_WIRELESS_UUID + " with service record 'AndroidAuto'");

                while (isRunning.get()) {
                    try {
                        BluetoothSocket socket = aaServerSocket.accept();
                        if (socket != null) {
                            Log.i(TAG, "[AA-SERVER INCOMING] Phone connected via RFCOMM: " 
                                + socket.getRemoteDevice().getName() + " (" + socket.getRemoteDevice().getAddress() + ")");
                            isAaConnected.set(true);
                            handleAaRfcommSession(socket);
                        }
                    } catch (IOException e) {
                        if (!isRunning.get()) break;
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "AA RFCOMM server error: " + e.getMessage(), e);
            }
        }, "AaRfcommServer").start();
    }

    /**
     * 2. Stand-in Hands-Free Profile (HFP-HF) Server
     * Listens on 0000111e-0000-1000-8000-00805f9b34fb
     * Responds to standard AT commands to establish Service Level Connection.
     */
    private void startHfpServer() {
        new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;

            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_HF_UUID);
                Log.i(TAG, "[HFP-SERVER] Listening on UUID " + HFP_HF_UUID + " with service record 'Hands-Free Unit'");

                while (isRunning.get()) {
                    try {
                        BluetoothSocket socket = hfpServerSocket.accept();
                        if (socket != null) {
                            Log.i(TAG, "[HFP-SERVER INCOMING] Phone connected to Hands-Free Unit: " 
                                + socket.getRemoteDevice().getName());
                            activeHfpSocket = socket;
                            isHfpConnected.set(true);
                            handleHfpSession(socket);
                        }
                    } catch (IOException e) {
                        if (!isRunning.get()) break;
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "HFP server error: " + e.getMessage(), e);
            }
        }, "HfpServer").start();
    }

    private void handleHfpSession(BluetoothSocket socket) {
        new Thread(() -> {
            try (InputStream in = socket.getInputStream();
                 OutputStream out = socket.getOutputStream()) {

                byte[] buf = new byte[1024];
                int n;
                while (isRunning.get() && socket.isConnected() && (n = in.read(buf)) > 0) {
                    String str = new String(buf, 0, n, StandardCharsets.US_ASCII);
                    for (String line : str.split("[\r\n]+")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        Log.i(TAG, "[HFP AT RX] " + line);

                        if (line.startsWith("AT+BRSF")) {
                            out.write("\r\n+BRSF: 20\r\n\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else if (line.startsWith("AT+CIND=?")) {
                            out.write("\r\n+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2)),(\"signal\",(0,5)),(\"roam\",(0,1)),(\"battchg\",(0,5))\r\n\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else if (line.startsWith("AT+CIND?")) {
                            out.write("\r\n+CIND: 1,0,0,0,5,0,5\r\n\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else if (line.startsWith("AT+CHLD=?")) {
                            out.write("\r\n+CHLD: (0,1,2,3)\r\n\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else if (line.startsWith("AT+BIND=?")) {
                            out.write("\r\n+BIND: (1,2)\r\n\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else if (line.startsWith("AT+BIND?")) {
                            out.write("\r\n+BIND: 1,1\r\n+BIND: 2,1\r\n\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else {
                            out.write("\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII));
                        }
                        out.flush();
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "HFP session ended: " + e.getMessage());
            } finally {
                isHfpConnected.set(false);
            }
        }, "HfpSession").start();
    }

    /**
     * 3. Bluetooth Wakeup Poke Loop
     * Pokes the S25 Ultra Audio Gateway on 0000111f-0000-1000-8000-00805f9b34fb
     * Signals the phone's Android Auto background service to wake up.
     */
    private void startWakeupPokeLoop() {
        new Thread(() -> {
            while (isRunning.get()) {
                if (!isAaConnected.get() && !isHfpConnected.get()) {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null && adapter.isEnabled()) {
                        for (BluetoothDevice dev : adapter.getBondedDevices()) {
                            if (S25_MAC.equalsIgnoreCase(dev.getAddress()) || (dev.getName() != null && dev.getName().contains("S25 Ultra"))) {
                                try {
                                    Log.i(TAG, "[POKE] Poking " + dev.getName() + " on HFP-AG (" + HFP_AG_UUID + ")...");
                                    BluetoothSocket pokeSocket = dev.createRfcommSocketToServiceRecord(HFP_AG_UUID);
                                    pokeSocket.connect();
                                    Log.i(TAG, "[POKE SUCCESS] Connected to S25 Ultra Audio Gateway! Holding 2.5s...");
                                    Thread.sleep(2500);
                                    pokeSocket.close();
                                    Log.i(TAG, "[POKE] Released poke socket, waiting for phone incoming connection...");
                                } catch (Exception e) {
                                    Log.d(TAG, "[POKE INFO] Connect attempt: " + e.getMessage());
                                }
                                break;
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ignored) {}
            }
        }, "WakeupPoke").start();
    }

    /**
     * 4. Android Auto Wireless Projection Protocol (WPP) Handshake
     */
    private void handleAaRfcommSession(BluetoothSocket socket) {
        new Thread(() -> {
            try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
                String localIp = getLocalWifiIp();
                Log.i(TAG, "[WPP] Target projection endpoint: " + localIp + ":" + AAP_PORT);

                // Step 1: Send WifiVersionRequest (Type 4)
                byte[] verReq = buildWifiVersionRequest();
                out.write(verReq);
                out.flush();
                Log.i(TAG, "[WPP TX] Sent WifiVersionRequest (Type 4, len=" + verReq.length + ") to phone");

                // Step 2: Loop reading messages from phone
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

                    Log.i(TAG, "[WPP RX] Received message Type " + type + ", payload len=" + len);

                    if (type == 5) {
                        // Type 5: WifiVersionResponse -> Send WifiStartRequest (Type 1)
                        Log.i(TAG, "[WPP RX] WifiVersionResponse received from phone");
                        String targetIp = getLocalWifiIp();
                        byte[] startReq = buildWifiStartRequest(targetIp, AAP_PORT);
                        out.write(startReq);
                        out.flush();
                        Log.i(TAG, "[WPP TX] Sent WifiStartRequest (Type 1) -> " + targetIp + ":" + AAP_PORT);

                    } else if (type == 2) {
                        // Type 2: WifiInfoRequest -> Send WifiInfoResponse (Type 3) with Wi-Fi Direct credentials
                        Log.i(TAG, "[WPP RX] WifiInfoRequest received, supplying Wi-Fi Direct credentials");
                        if (p2pPass == null || p2pPass.isEmpty()) {
                            fetchPassphraseFromSupplicant(p2pSsid);
                        }
                        byte[] netInfo = buildWifiInfoResponse(p2pSsid, p2pPass, p2pBssid);
                        out.write(netInfo);
                        out.flush();
                        Log.i(TAG, "[WPP TX] Sent WifiInfoResponse (Type 3) SSID: " + p2pSsid + " Pass: " + p2pPass + " BSSID: " + p2pBssid);

                    } else if (type == 6) {
                        // Type 6: WifiConnectStatus
                        Log.i(TAG, "[WPP RX] WifiConnectStatus received! Phone joined Wi-Fi network.");
                    } else if (type == 7) {
                        // Type 7: WifiStartResponse
                        Log.i(TAG, "[WPP RX] WifiStartResponse received! Phone is joining Wi-Fi Direct...");
                    } else if (type == 8) {
                        // Type 8: Ping -> Echo back as Type 9 (Pong)
                        byte[] pong = wrapMessage(9, payload);
                        out.write(pong);
                        out.flush();
                        Log.i(TAG, "[WPP TX] Echoed WifiPingResponse (Type 9)");
                    }
                }
            } catch (IOException e) {
                Log.i(TAG, "WPP RFCOMM socket closed: " + e.getMessage());
            } finally {
                isAaConnected.set(false);
            }
        }, "AaRfcommSession").start();
    }

    private String getLocalWifiIp() {
        if (p2pGoIp != null && !p2pGoIp.isEmpty() && !"0.0.0.0".equals(p2pGoIp)) {
            return p2pGoIp;
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.getName().startsWith("p2p")) {
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
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.getName().startsWith("wlan")) {
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
        return "192.168.37.100";
    }

    // --- Protobuf wire-format builders ---

    private static void writeVarint(OutputStream os, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            os.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        os.write((int) (value & 0x7F));
    }

    private static void writeFieldVarint(OutputStream os, int field, long value) throws IOException {
        writeVarint(os, (field << 3) | 0);
        writeVarint(os, value);
    }

    private static void writeFieldString(OutputStream os, int field, String str) throws IOException {
        if (str == null) str = "";
        byte[] b = str.getBytes(StandardCharsets.UTF_8);
        writeVarint(os, (field << 3) | 2);
        writeVarint(os, b.length);
        os.write(b);
    }

    private static void writeFieldBytes(OutputStream os, int field, byte[] b) throws IOException {
        writeVarint(os, (field << 3) | 2);
        writeVarint(os, b.length);
        os.write(b);
    }

    private static byte[] wrapMessage(int type, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(4 + payload.length);
        bb.putShort((short) payload.length);
        bb.putShort((short) type);
        bb.put(payload);
        return bb.array();
    }

    // WifiVersionRequest (Type 4)
    private static byte[] buildWifiVersionRequest() throws IOException {
        // WppCarInfo
        ByteArrayOutputStream carBaos = new ByteArrayOutputStream();
        writeFieldString(carBaos, 1, "Kawasaki");
        writeFieldString(carBaos, 2, "A37");
        writeFieldString(carBaos, 3, "2024");
        writeFieldString(carBaos, 5, "Kawasaki");
        writeFieldString(carBaos, 6, "RustHeadunit");
        writeFieldString(carBaos, 7, "1.0");
        writeFieldString(carBaos, 8, "1.0");
        byte[] carBytes = carBaos.toByteArray();

        ByteArrayOutputStream reqBaos = new ByteArrayOutputStream();
        writeFieldVarint(reqBaos, 1, 4); // major = 4
        writeFieldVarint(reqBaos, 2, 2); // minor = 2
        writeFieldVarint(reqBaos, 3, 1); // supported_wifi_channel_type = CHANNELS_24GHZ_ONLY (1)
        writeFieldBytes(reqBaos, 5, carBytes); // car_info (field 5)
        // NOTE: On Wi-Fi Direct, wpp_info (field 6) is deliberately omitted to prevent the phone
        // from treating AAP projection port 5288 as a WPP-over-TCP control endpoint.
        byte[] payload = reqBaos.toByteArray();

        return wrapMessage(4, payload);
    }

    // WifiStartRequest (Type 1)
    private static byte[] buildWifiStartRequest(String ip, int port) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeFieldString(baos, 1, ip);
        writeFieldVarint(baos, 2, port);
        writeFieldVarint(baos, 3, 0); // START_REQUEST_REASON_USER_REQUEST = 0 (matches open-headunit)
        byte[] payload = baos.toByteArray();
        return wrapMessage(1, payload);
    }

    // WifiInfoResponse (Type 3)
    private static byte[] buildWifiInfoResponse(String ssid, String psk, String bssid) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeFieldString(baos, 1, ssid);
        writeFieldString(baos, 2, psk);
        writeFieldString(baos, 3, bssid != null ? bssid : "");
        writeFieldVarint(baos, 4, 8); // SecurityMode = WPA2_PERSONAL (8)
        writeFieldVarint(baos, 5, 1); // AccessPointType = DYNAMIC (1)
        byte[] payload = baos.toByteArray();
        return wrapMessage(3, payload);
    }
}
