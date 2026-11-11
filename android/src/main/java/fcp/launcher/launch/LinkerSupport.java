// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch;

/**
 * Null-safe wrappers around two helpers exported by the FCL 1.3.4.4 bridge.
 * Loaded after {@code libpojavexec.so}. The native code does not start a JVM.
 */
public final class LinkerSupport {
    static {
        System.loadLibrary("fcpjvm");
    }

    private LinkerSupport() {
    }

    /** Calls {@code loader_dlopen} once so {@code app_escapeNs} exists for later dlopen. */
    public static native boolean ensureEscapeNamespace();

    /**
     * Same linker update as {@code FCLBridge.setLdLibraryPath}, but returns false
     * when the platform symbol is missing instead of jumping through a null pointer.
     */
    public static native boolean updateLdLibraryPath(String path);

    /**
     * Calls {@code JLI_Launch} on an already-loaded {@code libjli.so}.
     * FCL's {@code VMLauncher} resets {@code SIGSEGV} to {@code SIG_DFL}, which
     * ART rejects, and it dlopens {@code libjli.so} by short name. This keeps
     * the VM in the current process.
     */
    public static native int launchJli(String libjliPath, String[] args);

    /**
     * Runs Pojav's {@code libunpack200.so} main in this process.
     * Returns the tool's status, or a negative code when it could not be called.
     */
    public static native int unpack200(String toolPath, String packPath, String jarPath);
}
