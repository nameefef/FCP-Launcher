// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package com.bytedance.android.bytehook;

/**
 * Class required by Fold Craft Launcher 1.3.4.4's {@code libbytehook.so}.
 *
 * <p>{@code JNI_OnLoad} calls {@code FindClass("com/bytedance/android/bytehook/ByteHook")}
 * and {@code RegisterNatives} for the ten methods below. A missing class leaves a
 * pending {@code ClassNotFoundException} that aborts the process during
 * {@code System.loadLibrary}.
 */
public final class ByteHook {
    private ByteHook() {
    }

    public static native String nativeGetVersion();

    public static native int nativeInit(int mode, boolean debug);

    public static native int nativeAddIgnore(String caller);

    public static native int nativeGetMode();

    public static native boolean nativeGetDebug();

    public static native void nativeSetDebug(boolean debug);

    public static native boolean nativeGetRecordable();

    public static native void nativeSetRecordable(boolean recordable);

    public static native String nativeGetRecords(int itemFlags);

    public static native String nativeGetArch();
}
