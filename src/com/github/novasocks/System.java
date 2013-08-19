package com.github.novasocks;

public class System {
    static {
        java.lang.System.loadLibrary("system");
    }

    public static native void exec(String cmd);
}
