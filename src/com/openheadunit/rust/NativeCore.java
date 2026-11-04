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
}
