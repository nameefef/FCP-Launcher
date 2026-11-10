// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package com.oracle.dalvik;

/**
 * Entry point exported by Fold Craft Launcher 1.3.4.4's {@code libpojavexec.so}.
 *
 * <p>{@code Java_com_oracle_dalvik_VMLauncher_launchJVM} dlopens {@code libjli.so}
 * and calls {@code JLI_Launch}. The call blocks until the game JVM exits.
 * {@code libpojavexec.so} is loaded by {@code com.tungsten.fclauncher.bridge.FCLBridge}.
 */
public final class VMLauncher {
    private VMLauncher() {
    }

    public static native int launchJVM(String[] args);
}
