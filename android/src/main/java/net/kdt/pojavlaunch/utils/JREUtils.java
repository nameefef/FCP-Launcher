// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package net.kdt.pojavlaunch.utils;

/**
 * JNI name required by {@code libpojavexec.so}'s
 * {@code Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow}.
 *
 * <p>Releases the {@code ANativeWindow} stored by
 * {@link org.lwjgl.glfw.CallbackBridge#setupBridgeWindow}. Calling it when
 * no window was stored passes NULL to {@code ANativeWindow_release}.
 */
public final class JREUtils {
    private JREUtils() {
    }

    public static native void releaseBridgeWindow();
}
