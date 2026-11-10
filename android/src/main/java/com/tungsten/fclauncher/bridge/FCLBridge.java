// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package com.tungsten.fclauncher.bridge;

/**
 * JNI surface required by Fold Craft Launcher 1.3.4.4's shipped bridge.
 *
 * <p>{@code libfcl.so} exports {@code redirectStdio}, {@code setenv}, and
 * {@code setupExitTrap}. {@code libpojavexec.so} exports {@code chdir},
 * {@code dlopen}, {@code setLdLibraryPath}, and {@code initializeHooks}.
 * The package and method names are the JNI symbol names; renaming them
 * makes {@code System.loadLibrary} fail to bind.
 *
 * <p>{@code redirectStdio} dup2s stdout and stderr onto a pipe and calls
 * {@link #receiveLog(String)} from a logger thread. {@code setupExitTrap}
 * looks up {@link #onExit(int)} and runs it when the JVM calls {@code exit}.
 */
public class FCLBridge {
    public interface LogSink {
        void onLog(String text);

        void onExit(int code);
    }

    private static volatile LogSink sink;

    static {
        System.loadLibrary("fcl");
        System.loadLibrary("pojavexec");
    }

    public static void setLogSink(LogSink logSink) {
        sink = logSink;
    }

    /** Called by libfcl's logger thread. The name and signature are fixed. */
    public void receiveLog(String text) {
        LogSink current = sink;
        if (current == null || text == null) {
            return;
        }
        try {
            current.onLog(text);
        } catch (Throwable ignored) {
            // A throw here kills the logger thread and blocks the JVM on the pipe.
        }
    }

    /** Called by libfcl when the hooked libc exit runs with a non-zero code. */
    public void onExit(int code) {
        LogSink current = sink;
        if (current == null) {
            return;
        }
        try {
            current.onExit(code);
        } catch (Throwable ignored) {
        }
    }

    public native int redirectStdio(String path);

    public native void setenv(String key, String value);

    public native void setupExitTrap(FCLBridge bridge);

    public native int chdir(String path);

    public native long dlopen(String path);

    public native void setLdLibraryPath(String path);

    public static native void initializeHooks();
}
