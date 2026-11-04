package com.openheadunit.rust;

import android.view.Surface;

public class NativeCore {
    static {
        System.loadLibrary("headunit_core");
    }

    public static native int init();
    public static native void startEngine(Surface surface, int port);
    public static native void sendTouch(int action, int x, int y);
    public static native void stopEngine();

    public static native boolean isProjecting();
    public static native int getSessionState();
    public static native long getRenderedFrames();
    public static native void setSettings(int width, int height, int codecRes, int density, int vehicleType, int fps);
}
