// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import fcp.core.launch.AndroidNativeRuntime
import java.io.File

/**
 * Looks for a Pojav / Fold Craft Launcher style runtime under app storage.
 * The debug APK does not ship the JRE or the native renderer.
 */
object RuntimeProbe {
    fun canonicalJava(runtimeRoot: File, major: Int): File =
        File(runtimeRoot, "java-$major/bin/java")

    fun javaCandidates(runtimeRoot: File, major: Int): List<File> {
        val names = listOf(
            "java-$major",
            "jre-$major",
            "jre$major",
            "Internal-$major",
            "internal-$major",
            "j2re-$major",
        )
        return names.map { File(runtimeRoot, "$it/bin/java") }
    }

    fun findJava(runtimeRoot: File, major: Int): File? {
        val existing = javaCandidates(runtimeRoot, major).firstOrNull { it.isFile } ?: return null
        if (!existing.canExecute()) existing.setExecutable(true)
        return existing
    }

    fun nativeDirectories(nativeLibraryDir: File, runtimeRoot: File): List<File> = listOf(
        nativeLibraryDir,
        File(runtimeRoot, "natives"),
        runtimeRoot,
    )

    fun presentLibraries(directories: List<File>): Set<String> {
        val found = mutableSetOf<String>()
        for (dir in directories) {
            if (!dir.isDirectory) continue
            for (name in AndroidNativeRuntime.libraryNames) {
                if (File(dir, name).isFile) found += name
            }
        }
        return found
    }

    fun stageNatives(target: File, sources: List<File>, names: Set<String>) {
        target.mkdirs()
        for (name in names) {
            val dest = File(target, name)
            if (dest.exists()) continue
            val source = sources.firstNotNullOfOrNull { dir ->
                File(dir, name).takeIf { it.isFile && it.absolutePath != dest.absolutePath }
            } ?: continue
            try {
                java.nio.file.Files.createSymbolicLink(dest.toPath(), source.toPath())
            } catch (_: Exception) {
                source.copyTo(dest, overwrite = true)
            }
        }
    }

    fun extraJvmArgs(gameDir: File): List<String> {
        val tmp = File(gameDir, "tmp").apply { mkdirs() }
        return listOf(
            "-Duser.home=${gameDir.absolutePath}",
            "-Djava.io.tmpdir=${tmp.absolutePath}",
            "-Dos.name=Linux",
            "-Dfile.encoding=UTF-8",
        )
    }

    fun environment(
        javaBinary: File?,
        nativesDir: File,
        gameDir: File,
        present: Set<String>,
    ): Map<String, String> {
        val env = linkedMapOf<String, String>()
        val binDir = javaBinary?.parentFile
        val javaHome = binDir?.parentFile
        val libDirs = mutableListOf(nativesDir.absolutePath)
        if (binDir != null && javaHome != null) {
            env["JAVA_HOME"] = javaHome.absolutePath
            env["PATH"] = binDir.absolutePath
            val lib = File(javaHome, "lib")
            libDirs += listOf(
                lib.absolutePath,
                File(lib, "jli").absolutePath,
                File(lib, "server").absolutePath,
                File(lib, "client").absolutePath,
            )
        }
        env["LD_LIBRARY_PATH"] = libDirs.joinToString(":")
        env["HOME"] = gameDir.absolutePath
        env["TMPDIR"] = File(gameDir, "tmp").apply { mkdirs() }.absolutePath
        env["POJAV_NATIVEDIR"] = nativesDir.absolutePath
        val renderer = when {
            "libgl4es.so" in present || "libgl4es_114.so" in present -> "gl4es"
            "libvgpu.so" in present || "libvirglrenderer.so" in present -> "virgl"
            "libOSMesa.so" in present -> "zink"
            else -> null
        }
        if (renderer != null) {
            env["POJAV_RENDERER"] = renderer
            if (renderer == "gl4es") {
                env["LIBGL_ES"] = "2"
                env["LIBGL_MIPMAP"] = "3"
                env["LIBGL_NORMALIZE"] = "1"
                env["LIBGL_NOERROR"] = "1"
            }
        }
        return env
    }
}
