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
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;

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

    // UI Progress & Status Components
    private FrameLayout rootLayout;
    private View overlayView;
    private TextView tvMainStatus;
    private TextView tvSubStatus;
    private TextView tvStepWifi;
    private TextView tvStepBt;
    private TextView tvStepWpp;
    private TextView tvStepAap;
    private TextView tvLastLog;
    private TextView tvBadge;
    private TextView tvBottomInfo;
    private ProgressBar progressBar;
    private Button btnStartStopAa;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isOverlayHidden = false;
    private final AtomicBoolean isConnectionRequested = new AtomicBoolean(false);

    // User Settings & Preferences
    private static final String PREFS_NAME = "HeadunitPrefs";
    private static final String PREF_RES_WIDTH = "res_width";
    private static final String PREF_RES_HEIGHT = "res_height";
    private static final String PREF_CODEC_RES = "codec_res";
    private static final String PREF_DENSITY = "density";
    private static final String PREF_VEHICLE_TYPE = "vehicle_type";
    private static final String PREF_FPS = "fps";

    private volatile int curWidth = 1280;
    private volatile int curHeight = 720;
    private volatile int curCodecRes = 2; // 1 = 800x480, 2 = 1280x720, 3 = 1920x1080
    private volatile int curDensity = 160;
    private volatile int curVehicleType = 3; // 3 = Motorcycle / Bike, 1 = Car
    private volatile int curFps = 60;

    private View settingsOverlayView;
    private Button btnSettings;
    private LinearLayout floatingControlsView;

    private BluetoothServerSocket aaServerSocket;
    private BluetoothServerSocket hfpServerSocket;
    private BluetoothSocket activeAaSocket;
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

        loadSettings();

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        // Pin the Surface buffer to the configured video resolution
        surfaceView.getHolder().setFixedSize(curWidth, curHeight);

        rootLayout = new FrameLayout(this);
        rootLayout.addView(surfaceView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = buildOverlayView();
        rootLayout.addView(overlayView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Floating Quick Controls during active AA video streaming: [ ⚙ Settings ] [ ✕ Exit AA ]
        floatingControlsView = new LinearLayout(this);
        floatingControlsView.setOrientation(LinearLayout.HORIZONTAL);
        floatingControlsView.setGravity(Gravity.CENTER_VERTICAL);
        floatingControlsView.setBackground(makeRoundedDrawable(Color.parseColor("#CC0B1220"), Color.parseColor("#334155"), dp(1), dp(18)));
        floatingControlsView.setPadding(dp(6), dp(4), dp(6), dp(4));

        FrameLayout.LayoutParams floatLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        floatLp.gravity = Gravity.TOP | Gravity.START;
        floatLp.setMargins(dp(12), dp(12), 0, 0);
        floatingControlsView.setLayoutParams(floatLp);
        floatingControlsView.setVisibility(View.GONE);

        // 1. Settings button
        Button btnFloatSettings = new Button(this);
        btnFloatSettings.setText("⚙ Settings");
        btnFloatSettings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnFloatSettings.setTypeface(Typeface.DEFAULT_BOLD);
        btnFloatSettings.setTextColor(Color.WHITE);
        btnFloatSettings.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#475569"), dp(1), dp(14)));
        btnFloatSettings.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnFloatSettings.setOnClickListener(v -> showSettings());
        floatingControlsView.addView(btnFloatSettings);

        // 2. Kill / Exit AA button
        Button btnFloatExit = new Button(this);
        btnFloatExit.setText("✕ Exit AA");
        btnFloatExit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnFloatExit.setTypeface(Typeface.DEFAULT_BOLD);
        btnFloatExit.setTextColor(Color.parseColor("#FCA5A5"));
        btnFloatExit.setBackground(makeRoundedDrawable(Color.parseColor("#7F1D1D"), Color.parseColor("#DC2626"), dp(1), dp(14)));
        btnFloatExit.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        exitLp.setMargins(dp(6), 0, 0, 0);
        btnFloatExit.setLayoutParams(exitLp);
        btnFloatExit.setOnClickListener(v -> killAndroidAuto());
        floatingControlsView.addView(btnFloatExit);

        rootLayout.addView(floatingControlsView);

        settingsOverlayView = buildSettingsOverlayView();
        settingsOverlayView.setVisibility(View.GONE);
        rootLayout.addView(settingsOverlayView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);

        // Touch forwarding to Rust Native Input Channel (scaled 1:1 to configured resolution)
        surfaceView.setOnTouchListener((v, event) -> {
            int actionMasked = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            // 3-finger tap: summon Headunit Settings during active AA projection
            if (pointerCount >= 3) {
                if (actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_DOWN) {
                    showSettings();
                    return true;
                }
            }

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
            if (viewW > 0 && viewH > 0 && (viewW != curWidth || viewH != curHeight)) {
                touchX = (int) (event.getX() * (float) curWidth / viewW);
                touchY = (int) (event.getY() * (float) curHeight / viewH);
            }
            NativeCore.sendTouch(aapAction, touchX, touchY);
            return true;
        });

        // Initialize Native Rust Core & apply settings
        NativeCore.init();
        NativeCore.setSettings(curWidth, curHeight, curCodecRes, curDensity, curVehicleType, curFps);
        isRunning.set(true);

        // Boost CPU & GPU hardware clocks for smooth 60 FPS presentation
        boostPerformance();

        // Start periodic UI sync with native state
        uiHandler.post(uiSyncRunnable);

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

    private void boostPerformance() {
        new Thread(() -> {
            try {
                // Boost CPU governor to performance (lock all 4 cores at 1.21 GHz) and Adreno GPU
                Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "for c in /sys/devices/system/cpu/cpu[0-3]/cpufreq/scaling_governor; do echo performance > $c; done; " +
                    "echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true"
                });
                p.waitFor();
                Log.i(TAG, "[PERF] Maximum CPU (1.21 GHz) & GPU (400 MHz) performance governors locked.");
            } catch (Exception e) {
                Log.w(TAG, "[PERF] Could not lock performance governors: " + e.getMessage());
            }
        }, "PerfBooster").start();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created, starting Rust native engine on port " + AAP_PORT);
        NativeCore.setSettings(curWidth, curHeight, curCodecRes, curDensity, curVehicleType, curFps);
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
        uiHandler.removeCallbacksAndMessages(null);
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

    private View buildOverlayView() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#EE0B0F19")); // Sleek dark slate backdrop

        // Center card wrapper
        LinearLayout centerCard = new LinearLayout(this);
        centerCard.setOrientation(LinearLayout.VERTICAL);
        centerCard.setGravity(Gravity.CENTER_HORIZONTAL);
        centerCard.setBackground(makeRoundedDrawable(Color.parseColor("#161F30"), Color.parseColor("#22334F"), dp(1), dp(16)));
        centerCard.setPadding(dp(28), dp(16), dp(28), dp(16));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            dp(560),
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.gravity = Gravity.CENTER;
        centerCard.setLayoutParams(cardLp);

        // Header inside card
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerRow.setLayoutParams(headerLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleCol.setLayoutParams(titleColLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("OPEN HEADUNIT RUST");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        tvTitle.setTextColor(Color.parseColor("#38BDF8"));
        titleCol.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Android Auto Wireless • MSM8916 Snapdragon 410");
        tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSubtitle.setTextColor(Color.parseColor("#64748B"));
        titleCol.addView(tvSubtitle);

        headerRow.addView(titleCol);

        tvBadge = new TextView(this);
        tvBadge.setText("STANDBY");
        tvBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tvBadge.setTextColor(Color.parseColor("#38BDF8"));
        tvBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        tvBadge.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#38BDF8"), dp(1), dp(12)));
        btnSettings = new Button(this);
        btnSettings.setText("⚙ Settings");
        btnSettings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        btnSettings.setTypeface(Typeface.DEFAULT_BOLD);
        btnSettings.setTextColor(Color.parseColor("#94A3B8"));
        btnSettings.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#334155"), dp(1), dp(12)));
        btnSettings.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        setLp.setMargins(0, 0, dp(8), 0);
        btnSettings.setLayoutParams(setLp);
        btnSettings.setOnClickListener(v -> showSettings());

        headerRow.addView(btnSettings);
        headerRow.addView(tvBadge);

        centerCard.addView(headerRow);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1E293B"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        );
        divLp.setMargins(0, dp(14), 0, dp(14));
        centerCard.addView(divider, divLp);

        // Spinner + Main Status
        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pbLp.setMargins(0, 0, 0, dp(10));
        progressBar.setVisibility(View.GONE);
        centerCard.addView(progressBar, pbLp);

        tvMainStatus = new TextView(this);
        tvMainStatus.setText("Android Auto Standby");
        tvMainStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvMainStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvMainStatus.setTextColor(Color.parseColor("#F8FAFC"));
        tvMainStatus.setGravity(Gravity.CENTER);
        centerCard.addView(tvMainStatus);

        tvSubStatus = new TextView(this);
        tvSubStatus.setText("Tap 'Start Android Auto' to connect wirelessly to your phone");
        tvSubStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSubStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvSubStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subLp.setMargins(0, dp(4), 0, dp(14));
        centerCard.addView(tvSubStatus, subLp);

        // 4-Step Checklist container
        LinearLayout stepBox = new LinearLayout(this);
        stepBox.setOrientation(LinearLayout.VERTICAL);
        stepBox.setBackground(makeRoundedDrawable(Color.parseColor("#0F172A"), Color.parseColor("#1E293B"), dp(1), dp(8)));
        stepBox.setPadding(dp(14), dp(10), dp(14), dp(10));

        tvStepWifi = createStepTextView("● Wi-Fi Direct: Active (" + p2pSsid + ")");
        tvStepBt   = createStepTextView("● Bluetooth: Standby");
        tvStepWpp  = createStepTextView("● WPP Handshake: Standby");
        tvStepAap  = createStepTextView("● AAP Stream: Port " + AAP_PORT + " listening");

        stepBox.addView(tvStepWifi);
        stepBox.addView(tvStepBt);
        stepBox.addView(tvStepWpp);
        stepBox.addView(tvStepAap);
        centerCard.addView(stepBox);

        // Action Button: Primary Start / Stop Android Auto trigger
        btnStartStopAa = new Button(this);
        btnStartStopAa.setText("🚀 Start Android Auto");
        btnStartStopAa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnStartStopAa.setTypeface(Typeface.DEFAULT_BOLD);
        btnStartStopAa.setTextColor(Color.WHITE);
        btnStartStopAa.setBackground(makeRoundedDrawable(Color.parseColor("#2563EB"), 0, 0, dp(8)));
        btnStartStopAa.setPadding(dp(24), dp(10), dp(24), dp(10));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        btnLp.setMargins(0, dp(14), 0, dp(8));
        btnStartStopAa.setLayoutParams(btnLp);
        btnStartStopAa.setOnClickListener(v -> {
            if (!isConnectionRequested.get()) {
                startAndroidAuto();
            } else {
                killAndroidAuto();
            }
        });
        centerCard.addView(btnStartStopAa);

        // Last log line
        tvLastLog = new TextView(this);
        tvLastLog.setText("Service initialized, listening on RFCOMM");
        tvLastLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvLastLog.setTypeface(Typeface.MONOSPACE);
        tvLastLog.setTextColor(Color.parseColor("#64748B"));
        tvLastLog.setGravity(Gravity.CENTER);
        centerCard.addView(tvLastLog);

        // Network / Engine Info
        tvBottomInfo = new TextView(this);
        tvBottomInfo.setText("SSID: " + p2pSsid + " | Port: " + AAP_PORT + " | Venus HW Decoder (1280x720)");
        tvBottomInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvBottomInfo.setTypeface(Typeface.MONOSPACE);
        tvBottomInfo.setTextColor(Color.parseColor("#475569"));
        tvBottomInfo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        botLp.setMargins(0, dp(6), 0, 0);
        centerCard.addView(tvBottomInfo, botLp);

        container.addView(centerCard);

        return container;
    }

    private TextView createStepTextView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.parseColor("#94A3B8"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(2), 0, dp(2));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static GradientDrawable makeRoundedDrawable(int fillColor, int strokeColor, int strokeWidth, float cornerRadius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        if (strokeWidth > 0 && strokeColor != 0) {
            gd.setStroke(strokeWidth, strokeColor);
        }
        gd.setCornerRadius(cornerRadius);
        return gd;
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setStatus(String main, String sub, String badge, int badgeColor) {
        uiHandler.post(() -> {
            if (tvMainStatus != null && main != null) tvMainStatus.setText(main);
            if (tvSubStatus != null && sub != null) tvSubStatus.setText(sub);
            if (tvBadge != null && badge != null) {
                tvBadge.setText(badge);
                tvBadge.setTextColor(badgeColor);
                tvBadge.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), badgeColor, dp(1), dp(12)));
            }
        });
    }

    private void updateStep(TextView tv, String text, int color) {
        uiHandler.post(() -> {
            if (tv != null) {
                tv.setText(text);
                tv.setTextColor(color);
            }
        });
    }

    private void addLog(String msg) {
        uiHandler.post(() -> {
            if (tvLastLog != null) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
                String time = sdf.format(new java.util.Date());
                tvLastLog.setText("[" + time + "] " + msg);
            }
        });
    }

    private void updateBottomInfo() {
        uiHandler.post(() -> {
            if (tvBottomInfo != null) {
                String modeName = curVehicleType == 3 ? "🏍 Bike (Ninja)" : "🚗 Car (Civic)";
                tvBottomInfo.setText(modeName + " | " + curWidth + "x" + curHeight + "@" + curFps + "fps (" + curDensity + " DPI) | " + p2pSsid);
            }
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        curWidth = prefs.getInt(PREF_RES_WIDTH, 1280);
        curHeight = prefs.getInt(PREF_RES_HEIGHT, 720);
        curCodecRes = prefs.getInt(PREF_CODEC_RES, 2);
        curDensity = prefs.getInt(PREF_DENSITY, 160);
        curVehicleType = prefs.getInt(PREF_VEHICLE_TYPE, 3); // 3 = Bike / Motorcycle
        curFps = prefs.getInt(PREF_FPS, 60);
    }

    private void saveSettings(int width, int height, int codecRes, int density, int vehicleType, int fps) {
        curWidth = width;
        curHeight = height;
        curCodecRes = codecRes;
        curDensity = density;
        curVehicleType = vehicleType;
        curFps = fps;

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt(PREF_RES_WIDTH, width);
        editor.putInt(PREF_RES_HEIGHT, height);
        editor.putInt(PREF_CODEC_RES, codecRes);
        editor.putInt(PREF_DENSITY, density);
        editor.putInt(PREF_VEHICLE_TYPE, vehicleType);
        editor.putInt(PREF_FPS, fps);
        editor.apply();

        NativeCore.setSettings(curWidth, curHeight, curCodecRes, curDensity, curVehicleType, curFps);
        if (surfaceView != null) {
            surfaceView.getHolder().setFixedSize(curWidth, curHeight);
        }
        updateBottomInfo();
        String modeName = curVehicleType == 3 ? "🏍 Bike" : "🚗 Car";
        addLog("Saved: " + modeName + " • " + curWidth + "x" + curHeight + "@" + curFps + "fps (" + curDensity + " DPI)");
    }

    // Selected options in settings dialog
    private int optVehicleType;
    private int optCodecRes;
    private int optWidth;
    private int optHeight;
    private int optDensity;
    private int optFps;

    private Button btnModeBike;
    private Button btnModeCar;
    private Button btnRes720;
    private Button btnRes480;
    private Button btnRes1080;
    private Button btnDpi140;
    private Button btnDpi160;
    private Button btnDpi180;
    private Button btnDpi210;
    private Button btnDpi240;
    private Button btnFps60;
    private Button btnFps30;

    private View buildSettingsOverlayView() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#D9090D16")); // Translucent dark backdrop

        // Close on tap outside
        container.setOnClickListener(v -> hideSettings());

        // Center card with scroll view
        ScrollView scrollView = new ScrollView(this);
        scrollView.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
            dp(560),
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        scrollLp.gravity = Gravity.CENTER;
        scrollLp.setMargins(0, dp(8), 0, dp(8));
        scrollView.setLayoutParams(scrollLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeRoundedDrawable(Color.parseColor("#131B2E"), Color.parseColor("#253550"), dp(1), dp(14)));
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        card.setOnClickListener(v -> {}); // Prevent tap-through dismissal

        // Header
        TextView tvTitle = new TextView(this);
        tvTitle.setText("⚙ HEADUNIT SETTINGS");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        tvTitle.setTextColor(Color.parseColor("#38BDF8"));
        card.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Configure Android Auto vehicle profile, resolution and density scaling");
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvSub.setTextColor(Color.parseColor("#64748B"));
        card.addView(tvSub);

        // Divider
        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor("#1E293B"));
        LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp1.setMargins(0, dp(6), 0, dp(6));
        card.addView(div1, divLp1);

        // 1. VEHICLE MODE
        addSectionTitle(card, "VEHICLE MODE");
        LinearLayout rowMode = new LinearLayout(this);
        rowMode.setOrientation(LinearLayout.HORIZONTAL);

        btnModeBike = createChoiceButton("🏍 Bike / Motorcycle", false, v -> {
            optVehicleType = 3;
            updateSettingsUI();
        });
        btnModeCar = createChoiceButton("🚗 Car", false, v -> {
            optVehicleType = 1;
            updateSettingsUI();
        });
        rowMode.addView(btnModeBike);
        rowMode.addView(btnModeCar);
        card.addView(rowMode);

        TextView tvBikeNote = new TextView(this);
        tvBikeNote.setText("• Bike mode uses phone mic (bypasses car mic check) & reports Kawasaki Ninja");
        tvBikeNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tvBikeNote.setTextColor(Color.parseColor("#64748B"));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.setMargins(dp(4), dp(2), 0, dp(6));
        card.addView(tvBikeNote, noteLp);

        // 2. RESOLUTION
        addSectionTitle(card, "SCREEN RESOLUTION");
        LinearLayout rowRes = new LinearLayout(this);
        rowRes.setOrientation(LinearLayout.HORIZONTAL);

        btnRes720 = createChoiceButton("720p (1280x720) ★", false, v -> {
            optWidth = 1280; optHeight = 720; optCodecRes = 2;
            updateSettingsUI();
        });
        btnRes480 = createChoiceButton("480p (800x480)", false, v -> {
            optWidth = 800; optHeight = 480; optCodecRes = 1;
            updateSettingsUI();
        });
        btnRes1080 = createChoiceButton("1080p (1920x1080)", false, v -> {
            optWidth = 1920; optHeight = 1080; optCodecRes = 3;
            updateSettingsUI();
        });
        rowRes.addView(btnRes720);
        rowRes.addView(btnRes480);
        rowRes.addView(btnRes1080);
        card.addView(rowRes);

        // 3. DISPLAY DENSITY (DPI)
        addSectionTitle(card, "DISPLAY DENSITY (UI SCALING)");
        LinearLayout rowDpi = new LinearLayout(this);
        rowDpi.setOrientation(LinearLayout.HORIZONTAL);

        btnDpi140 = createChoiceButton("140", false, v -> { optDensity = 140; updateSettingsUI(); });
        btnDpi160 = createChoiceButton("160 Default", false, v -> { optDensity = 160; updateSettingsUI(); });
        btnDpi180 = createChoiceButton("180 Medium", false, v -> { optDensity = 180; updateSettingsUI(); });
        btnDpi210 = createChoiceButton("210 Large", false, v -> { optDensity = 210; updateSettingsUI(); });
        btnDpi240 = createChoiceButton("240 XL", false, v -> { optDensity = 240; updateSettingsUI(); });
        rowDpi.addView(btnDpi140);
        rowDpi.addView(btnDpi160);
        rowDpi.addView(btnDpi180);
        rowDpi.addView(btnDpi210);
        rowDpi.addView(btnDpi240);
        card.addView(rowDpi);

        TextView tvDpiNote = new TextView(this);
        tvDpiNote.setText("• Higher DPI (210/240) renders larger buttons and text, ideal for glove touch on bikes");
        tvDpiNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tvDpiNote.setTextColor(Color.parseColor("#64748B"));
        LinearLayout.LayoutParams dpiNoteLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dpiNoteLp.setMargins(dp(4), dp(2), 0, dp(6));
        card.addView(tvDpiNote, dpiNoteLp);

        // 4. FRAME RATE
        addSectionTitle(card, "TARGET FRAME RATE");
        LinearLayout rowFps = new LinearLayout(this);
        rowFps.setOrientation(LinearLayout.HORIZONTAL);

        btnFps60 = createChoiceButton("60 FPS (Ultra Smooth)", false, v -> { optFps = 60; updateSettingsUI(); });
        btnFps30 = createChoiceButton("30 FPS (Power Saver)", false, v -> { optFps = 30; updateSettingsUI(); });
        rowFps.addView(btnFps60);
        rowFps.addView(btnFps30);
        card.addView(rowFps);

        // Action Buttons Row
        // Action Buttons Row: [ 🛑 Kill Android Auto ] ---- [ Cancel ] [ Save & Apply ]
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.setMargins(0, dp(10), 0, 0);
        actionRow.setLayoutParams(actLp);

        Button btnKill = new Button(this);
        btnKill.setText("🛑 Kill Android Auto");
        btnKill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnKill.setTypeface(Typeface.DEFAULT_BOLD);
        btnKill.setTextColor(Color.parseColor("#FCA5A5"));
        btnKill.setBackground(makeRoundedDrawable(Color.parseColor("#450A0A"), Color.parseColor("#DC2626"), dp(1), dp(8)));
        btnKill.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnKill.setOnClickListener(v -> {
            hideSettings();
            killAndroidAuto();
        });
        actionRow.addView(btnKill);

        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, dp(1), 1.0f);
        actionRow.addView(spacer, spLp);

        Button btnCancel = new Button(this);
        btnCancel.setText("Cancel");
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnCancel.setTextColor(Color.parseColor("#94A3B8"));
        btnCancel.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#334155"), dp(1), dp(8)));
        btnCancel.setPadding(dp(16), dp(6), dp(16), dp(6));
        btnCancel.setOnClickListener(v -> hideSettings());
        actionRow.addView(btnCancel);

        Button btnSave = new Button(this);
        btnSave.setText("Save & Apply");
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnSave.setTextColor(Color.WHITE);
        btnSave.setBackground(makeRoundedDrawable(Color.parseColor("#2563EB"), 0, 0, dp(8)));
        btnSave.setPadding(dp(20), dp(6), dp(20), dp(6));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(dp(10), 0, 0, 0);
        btnSave.setLayoutParams(saveLp);
        btnSave.setOnClickListener(v -> {
            saveSettings(optWidth, optHeight, optCodecRes, optDensity, optVehicleType, optFps);
            hideSettings();
            Toast.makeText(MainActivity.this, "Settings applied!", Toast.LENGTH_SHORT).show();
        });
        actionRow.addView(btnSave);

        card.addView(actionRow);

        scrollView.addView(card);
        container.addView(scrollView);
        return container;
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#94A3B8"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(2));
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private Button createChoiceButton(String text, boolean selected, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        styleChoiceButton(btn, selected);
        btn.setPadding(dp(6), dp(4), dp(6), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0,
            dp(32),
            1.0f
        );
        lp.setMargins(dp(3), 0, dp(3), 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private void styleChoiceButton(Button btn, boolean selected) {
        if (btn == null) return;
        if (selected) {
            btn.setBackground(makeRoundedDrawable(Color.parseColor("#2563EB"), Color.parseColor("#60A5FA"), dp(1), dp(8)));
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#334155"), dp(1), dp(8)));
            btn.setTextColor(Color.parseColor("#94A3B8"));
        }
    }

    private void updateSettingsUI() {
        styleChoiceButton(btnModeBike, optVehicleType == 3);
        styleChoiceButton(btnModeCar, optVehicleType == 1);

        styleChoiceButton(btnRes720, optCodecRes == 2);
        styleChoiceButton(btnRes480, optCodecRes == 1);
        styleChoiceButton(btnRes1080, optCodecRes == 3);

        styleChoiceButton(btnDpi140, optDensity == 140);
        styleChoiceButton(btnDpi160, optDensity == 160);
        styleChoiceButton(btnDpi180, optDensity == 180);
        styleChoiceButton(btnDpi210, optDensity == 210);
        styleChoiceButton(btnDpi240, optDensity == 240);

        styleChoiceButton(btnFps60, optFps == 60);
        styleChoiceButton(btnFps30, optFps == 30);
    }

    private void showSettings() {
        optVehicleType = curVehicleType;
        optCodecRes = curCodecRes;
        optWidth = curWidth;
        optHeight = curHeight;
        optDensity = curDensity;
        optFps = curFps;

        updateSettingsUI();

        if (settingsOverlayView != null) {
            settingsOverlayView.setVisibility(View.VISIBLE);
            settingsOverlayView.setAlpha(0f);
            settingsOverlayView.animate().alpha(1f).setDuration(250);
        }
        if (floatingControlsView != null) {
            floatingControlsView.setVisibility(View.GONE);
        }
    }

    private void hideSettings() {
        if (settingsOverlayView != null) {
            settingsOverlayView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                settingsOverlayView.setVisibility(View.GONE);
            });
        }
        if (floatingControlsView != null && isOverlayHidden) {
            floatingControlsView.setVisibility(View.VISIBLE);
            floatingControlsView.setAlpha(0f);
            floatingControlsView.animate().alpha(1f).setDuration(250);
        }
    }

    private void startAndroidAuto() {
        runOnUiThread(() -> {
            isConnectionRequested.set(true);
            tvBadge.setText("CONNECTING");
            tvBadge.setTextColor(Color.parseColor("#FBBF24"));
            tvBadge.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#FBBF24"), dp(1), dp(12)));
            progressBar.setVisibility(View.VISIBLE);
            tvMainStatus.setText("Connecting to Phone...");
            tvSubStatus.setText("Waking phone & negotiating wireless connection...");
            btnStartStopAa.setText("🛑 Stop Connecting");
            btnStartStopAa.setBackground(makeRoundedDrawable(Color.parseColor("#DC2626"), 0, 0, dp(8)));
            updateStep(tvStepBt, "● Bluetooth: Sending wake signal...", Color.parseColor("#FBBF24"));
            addLog("User started Android Auto wireless link");
            pokeOnce();
        });
    }

    private void killAndroidAuto() {
        runOnUiThread(() -> {
            Log.i(TAG, "[KILL] Stopping Android Auto session & returning to home screen");
            isConnectionRequested.set(false);

            // 1. Terminate native AAP session & hardware decoder
            NativeCore.killSession();

            // 2. Close Bluetooth RFCOMM sockets if open
            try {
                if (activeAaSocket != null) {
                    activeAaSocket.close();
                    activeAaSocket = null;
                }
            } catch (Exception ignored) {}
            try {
                if (activeHfpSocket != null) {
                    activeHfpSocket.close();
                    activeHfpSocket = null;
                }
            } catch (Exception ignored) {}
            isAaConnected.set(false);
            isHfpConnected.set(false);

            // 3. Hide floating controls and settings modal
            if (floatingControlsView != null) {
                floatingControlsView.setVisibility(View.GONE);
            }
            if (settingsOverlayView != null) {
                settingsOverlayView.setVisibility(View.GONE);
            }

            // 4. Restore connection dashboard
            isOverlayHidden = false;
            if (overlayView != null) {
                overlayView.setVisibility(View.VISIBLE);
                overlayView.setAlpha(0f);
                overlayView.animate().alpha(1f).setDuration(250);
            }

            // 5. Reset UI to Standby state
            resetToStandbyUI("Android Auto Stopped", "Session ended. Tap 'Start Android Auto' to connect again.");
            addLog("Android Auto session stopped by user");
            Toast.makeText(MainActivity.this, "Android Auto stopped. Returned to Home.", Toast.LENGTH_SHORT).show();
        });
    }

    private void resetToStandbyUI(String mainText, String subText) {
        if (tvBadge != null) {
            tvBadge.setText("STANDBY");
            tvBadge.setTextColor(Color.parseColor("#38BDF8"));
            tvBadge.setBackground(makeRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#38BDF8"), dp(1), dp(12)));
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        if (tvMainStatus != null) {
            tvMainStatus.setText(mainText);
        }
        if (tvSubStatus != null) {
            tvSubStatus.setText(subText);
        }
        if (btnStartStopAa != null) {
            btnStartStopAa.setText("🚀 Start Android Auto");
            btnStartStopAa.setBackground(makeRoundedDrawable(Color.parseColor("#2563EB"), 0, 0, dp(8)));
        }
        updateStep(tvStepBt, "● Bluetooth: Standby", Color.parseColor("#64748B"));
        updateStep(tvStepWpp, "● WPP Handshake: Standby", Color.parseColor("#64748B"));
        updateStep(tvStepAap, "● AAP Stream: Port " + AAP_PORT + " listening", Color.parseColor("#64748B"));
    }

    private int lastReportedState = -1;

    private final Runnable uiSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;
            try {
                int state = NativeCore.getSessionState();
                boolean projecting = NativeCore.isProjecting();
                long frames = NativeCore.getRenderedFrames();

                if (projecting || state == 4) {
                    if (!isOverlayHidden) {
                        isOverlayHidden = true;
                        Log.i(TAG, "[UI] Video projecting! Fading out connection overlay (frames=" + frames + ")");
                        overlayView.animate()
                            .alpha(0f)
                            .setDuration(400)
                            .withEndAction(() -> overlayView.setVisibility(View.GONE));
                        if (floatingControlsView != null) {
                            floatingControlsView.setVisibility(View.VISIBLE);
                            floatingControlsView.setAlpha(0f);
                            floatingControlsView.animate().alpha(1f).setDuration(400);
                        }
                    }
                } else {
                    if (isOverlayHidden) {
                        isOverlayHidden = false;
                        Log.i(TAG, "[UI] Projection ended, restoring connection overlay");
                        overlayView.setVisibility(View.VISIBLE);
                        overlayView.setAlpha(0f);
                        overlayView.animate().alpha(1f).setDuration(300);
                        if (floatingControlsView != null) {
                            floatingControlsView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                                floatingControlsView.setVisibility(View.GONE);
                            });
                        }
                        if (!isConnectionRequested.get()) {
                            resetToStandbyUI("Android Auto Standby", "Tap 'Start Android Auto' to connect wirelessly to your phone");
                        }
                    }

                    if (state != lastReportedState) {
                        lastReportedState = state;
                        if (state == 1) { // TCP_CONNECTED
                            setStatus("Phone Connected (TCP)!", "Establishing encrypted AAP channel...", "TCP CONNECTED", Color.parseColor("#38BDF8"));
                            updateStep(tvStepAap, "✓ TCP: Connected on port " + AAP_PORT, Color.parseColor("#4ADE80"));
                            addLog("TCP connection established from phone");
                        } else if (state == 2) { // TLS_HANDSHAKE
                            setStatus("Securing Connection...", "Negotiating TLS cryptographic certificates...", "TLS HANDSHAKE", Color.parseColor("#FBBF24"));
                            updateStep(tvStepAap, "● TLS: Exchanging certificates...", Color.parseColor("#FBBF24"));
                            addLog("TLS handshake in progress...");
                        } else if (state == 3) { // PROTOCOL_SETUP
                            setStatus("Starting Projection...", "Configuring video stream & touch channels...", "PROTOCOL SETUP", Color.parseColor("#A855F7"));
                            updateStep(tvStepAap, "● AAP: Configuring 1280x720 video...", Color.parseColor("#A855F7"));
                            addLog("AAP Protocol Setup / Service Discovery");
                        } else if (state == 5 || state == 0) { // DISCONNECTED or IDLE
                            if (!isConnectionRequested.get()) {
                                resetToStandbyUI("Android Auto Standby", "Tap 'Start Android Auto' to connect wirelessly to your phone");
                            } else {
                                setStatus("Session Disconnected", "Waiting for phone reconnection...", "DISCONNECTED", Color.parseColor("#F87171"));
                                updateStep(tvStepAap, "○ AAP Stream: Disconnected", Color.parseColor("#94A3B8"));
                                addLog("AAP Session disconnected");
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "uiSyncRunnable error: " + t.getMessage());
            }
            uiHandler.postDelayed(this, 250);
        }
    };

    private void pokeOnce() {
        new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setStatus("Bluetooth Disabled", "Please enable Bluetooth", "BT ERROR", Color.parseColor("#EF4444"));
                addLog("Poke failed: Bluetooth is off");
                return;
            }
            uiHandler.post(() -> {
                updateStep(tvStepBt, "● Poking phone via Bluetooth...", Color.parseColor("#FBBF24"));
                setStatus("Waking Phone...", "Sending HFP wakeup poke to S25 Ultra...", "WAKING", Color.parseColor("#FBBF24"));
                addLog("Sending HFP wakeup poke...");
            });

            boolean found = false;
            for (BluetoothDevice dev : adapter.getBondedDevices()) {
                if (S25_MAC.equalsIgnoreCase(dev.getAddress()) || (dev.getName() != null && dev.getName().contains("S25 Ultra"))) {
                    found = true;
                    try {
                        Log.i(TAG, "[POKE] Poking " + dev.getName() + " on HFP-AG (" + HFP_AG_UUID + ")...");
                        BluetoothSocket pokeSocket = dev.createRfcommSocketToServiceRecord(HFP_AG_UUID);
                        pokeSocket.connect();
                        Log.i(TAG, "[POKE SUCCESS] Connected to S25 Ultra Audio Gateway! Holding 2.5s...");
                        uiHandler.post(() -> {
                            updateStep(tvStepBt, "✓ Poke ACK: S25 Ultra Audio Gateway reached", Color.parseColor("#4ADE80"));
                            addLog("Wake poke delivered to S25 Ultra");
                        });
                        Thread.sleep(2500);
                        pokeSocket.close();
                        Log.i(TAG, "[POKE] Released poke socket, waiting for phone incoming connection...");
                        uiHandler.post(() -> {
                            setStatus("Waiting for Phone...", "Wake signal sent, waiting for phone to initiate connection...", "WAITING", Color.parseColor("#38BDF8"));
                        });
                    } catch (Exception e) {
                        Log.d(TAG, "[POKE INFO] Connect attempt: " + e.getMessage());
                        uiHandler.post(() -> {
                            updateStep(tvStepBt, "○ Poke sent (waiting for response)", Color.parseColor("#94A3B8"));
                            addLog("Poke sent (" + e.getMessage() + ")");
                        });
                    }
                    break;
                }
            }
            if (!found) {
                uiHandler.post(() -> {
                    setStatus("S25 Ultra Not Paired", "Pair phone in Bluetooth settings", "NOT PAIRED", Color.parseColor("#EF4444"));
                    addLog("S25 Ultra not found in bonded devices");
                });
            }
        }, "PokeThread").start();
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
                            updateStep(tvStepWifi, "✓ Wi-Fi Direct: " + p2pSsid + " (Active)", Color.parseColor("#4ADE80"));
                            updateBottomInfo();
                            addLog("Wi-Fi Direct ready (" + p2pSsid + ")");
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
                            activeAaSocket = socket;
                            isAaConnected.set(true);
                            isConnectionRequested.set(true);
                            updateStep(tvStepBt, "✓ BT RFCOMM: Phone connected (" + socket.getRemoteDevice().getName() + ")", Color.parseColor("#4ADE80"));
                            setStatus("Phone Connected (BT)", "Starting Wireless Projection Protocol handshake...", "BT PAIRED", Color.parseColor("#38BDF8"));
                            addLog("Phone connected to RFCOMM: " + socket.getRemoteDevice().getName());
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
                if (isConnectionRequested.get() && !isAaConnected.get() && !isHfpConnected.get() && !NativeCore.isProjecting()) {
                    pokeOnce();
                }
                try {
                    Thread.sleep(12000);
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
                updateStep(tvStepWpp, "● WPP: Sent WifiVersionRequest", Color.parseColor("#FBBF24"));
                addLog("Sent WPP WifiVersionRequest");

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
                        updateStep(tvStepWpp, "● WPP: Verified Version -> Sent StartRequest", Color.parseColor("#FBBF24"));
                        addLog("WPP: Version verified, sent StartRequest");

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
                        setStatus("Exchanging Wi-Fi Credentials", "Sending credentials for " + p2pSsid + " to phone...", "HANDSHAKE", Color.parseColor("#38BDF8"));
                        updateStep(tvStepWpp, "✓ WPP: Exchanged Wi-Fi credentials", Color.parseColor("#4ADE80"));
                        addLog("WPP: Sent Wi-Fi credentials to phone");

                    } else if (type == 6) {
                        // Type 6: WifiConnectStatus
                        Log.i(TAG, "[WPP RX] WifiConnectStatus received! Phone joined Wi-Fi network.");
                        setStatus("Phone Joining Wi-Fi Direct", "Phone connected to Wi-Fi Direct group", "CONNECTING", Color.parseColor("#38BDF8"));
                        updateStep(tvStepWpp, "✓ WPP: Phone joined Wi-Fi Direct network", Color.parseColor("#4ADE80"));
                        addLog("WPP: Phone joined Wi-Fi network");
                    } else if (type == 7) {
                        // Type 7: WifiStartResponse
                        Log.i(TAG, "[WPP RX] WifiStartResponse received! Phone is joining Wi-Fi Direct...");
                        addLog("WPP: WifiStartResponse received");
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
                addLog("RFCOMM session closed");
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
    private byte[] buildWifiVersionRequest() throws IOException {
        String make = curVehicleType == 3 ? "Kawasaki" : "Honda";
        String model = curVehicleType == 3 ? "Ninja" : "Civic";
        // WppCarInfo
        ByteArrayOutputStream carBaos = new ByteArrayOutputStream();
        writeFieldString(carBaos, 1, make);
        writeFieldString(carBaos, 2, model);
        writeFieldString(carBaos, 3, "2025");
        writeFieldString(carBaos, 5, make);
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
