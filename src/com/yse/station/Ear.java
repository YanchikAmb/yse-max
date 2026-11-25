package com.yse.station;

public final class Ear {
    static volatile int openCode = -99;
    static volatile String note = "ear idle";

    static {
        try {
            System.loadLibrary("yseear");
            openCode = nativeOpen();
            note = openCode == 0 ? "vqe ready" : "vqe rc " + openCode;
        } catch (Throwable t) {
            openCode = -100;
            note = t.getClass().getSimpleName();
        }
    }

    static native int nativeOpen();

    static native void nativeClose();

    static native int nativeFeed(short[] frame16);

    static native int nativeCopy(short[] dst);
}