// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package org.lwjgl.glfw;

import android.view.Surface;

/**
 * Class {@code libpojavexec.so}'s {@code JNI_OnLoad} looks up and registers natives on.
 *
 * <p>The static methods are resolved with {@code GetStaticMethodID}. The nine
 * {@code native*} methods are bound with {@code RegisterNatives}. Missing any
 * of them leaves a pending exception and {@code System.loadLibrary("pojavexec")}
 * fails.
 *
 * <p>{@link #setupBridgeWindow(Surface)} is the exported JNI entry
 * {@code libglfw.so} / {@code libpojavexec.so} use to store the
 * {@code ANativeWindow}. {@code glfwInit} acquires that window. Call it
 * before {@code JLI_Launch}.
 */
public class CallbackBridge {
    /** Stores {@code ANativeWindow_fromSurface}. A null surface is not safe. */
    public static native void setupBridgeWindow(Surface surface);

    /** Marks the bridge ready to take cursor and button events. */
    public static native boolean nativeSetInputReady(boolean ready);
    public static native void nativeSetUseInputStackQueue(boolean use);

    public static native boolean nativeSendChar(char codepoint);

    public static native boolean nativeSendCharMods(char codepoint, int mods);

    public static native void nativeSendKey(int key, int scancode, int action, int mods);

    public static native void nativeSendCursorPos(float x, float y);

    public static native void nativeSendMouseButton(int button, int action, int mods);

    public static native void nativeResetInputState();

    public static native void nativeSendScroll(double xOffset, double yOffset);

    public static native void nativeSendScreenSize(int width, int height);

    public static String accessAndroidClipboard(int action, String data) {
        return null;
    }

    public static void onGrabStateChanged(boolean grabbing) {
    }

    public static void onDirectInputEnable() {
    }

    public static boolean notifyLauncher(int type, int[] actions) {
        return false;
    }
}
