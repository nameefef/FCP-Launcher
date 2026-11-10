// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import java.io.File

/**
 * Finds `libjli.so` and `libjvm.so` inside an unpacked Android JRE and builds
 * the library list Fold Craft Launcher dlopens before `JLI_Launch`.
 *
 * Java 8 (FCL's `jre/lib/<arch>/...`) and Java 17 (Pojav's `lib/jli` + `lib/server`)
 * unpack to different trees. The search walks the tree instead of assuming one layout.
 */
object JreLayout {
    /** Loaded first, in this order, when the file exists. Matches FCL's `setUpJavaRuntime`. */
    val preferredRuntimeLibraries: List<String> = listOf(
        "libjli.so",
        "libjvm.so",
        "libfreetype.so",
        "libverify.so",
        "libjava.so",
        "libnet.so",
        "libnio.so",
        "libawt.so",
        "libawt_headless.so",
        "libfontmanager.so",
        "libjsig.so",
        "libjimage.so",
        "libzip.so",
        "libextnet.so",
    )

    /** Bridge and renderer libraries staged next to the JRE, after the runtime itself. */
    val preferredNativeLibraries: List<String> = listOf(
        "libc++_shared.so",
        "libbytehook.so",
        "libandroidnsbypass.so",
        "libfcl.so",
        "libopenal.so",
        "libgl4es_114.so",
        "libgl4es.so",
        "libng_gl4es.so",
        "libglfw.so",
        "libpojavexec.so",
    )

    data class Resolved(
        val javaHome: File,
        val libjli: File,
        val libjvm: File,
        /** Absolute paths to dlopen, runtime libraries first. */
        val preload: List<File>,
        /** Value for `LD_LIBRARY_PATH` and the linker's library-path update. */
        val libraryPath: String,
    )

    fun javaHomeOf(javaBinary: File): File {
        val parent = javaBinary.parentFile
        if (parent != null && parent.name == "bin") {
            return parent.parentFile ?: parent
        }
        return parent ?: javaBinary
    }

    fun resolve(
        javaBinary: File,
        nativesDirectory: File,
        nativeLibraryDir: File?,
        systemLibraryDirs: List<File> = emptyList(),
    ): Resolved {
        val home = javaHomeOf(javaBinary)
        val runtimeLibraries = sharedObjects(home)
        val libjli = pick(runtimeLibraries, "libjli.so", preferPath = "/jli/")
            ?: throw LaunchException(
                "没有找到 libjli.so，无法在进程内启动虚拟机。已查找：${home.path}。Android 10 及以上不能执行 bin/java。",
            )
        val libjvm = pick(runtimeLibraries, "libjvm.so", preferPath = "/server/")
            ?: throw LaunchException(
                "没有找到 libjvm.so，无法在进程内启动虚拟机。已查找：${home.path}。",
            )
        val natives = sharedObjects(nativesDirectory)
        val fromApk = nativeLibraryDir?.let { sharedObjects(it) }.orEmpty()
        val staged = natives + fromApk
        val preload = mutableListOf<File>()
        fun add(file: File?) {
            if (file == null) return
            if (preload.any { it.absolutePath == file.absolutePath }) return
            preload += file
        }
        for (name in preferredRuntimeLibraries) {
            when (name) {
                "libjli.so" -> add(libjli)
                "libjvm.so" -> add(libjvm)
                else -> add(pick(runtimeLibraries, name, preferPath = null))
            }
        }
        val used = preload.map { it.absolutePath }.toSet()
        runtimeLibraries
            .filter { it.absolutePath !in used }
            .filter { it.name != "libjli.so" && it.name != "libjvm.so" }
            .sortedBy { it.absolutePath }
            .forEach { add(it) }
        for (name in preferredNativeLibraries) {
            add(pick(staged, name, preferPath = null))
        }
        val dirs = linkedSetOf<String>()
        // JRE 8's libjli re-execs bin/java unless LD_LIBRARY_PATH starts with the
        // directory that contains libjvm.so. Android denies that exec.
        dirs += libjvm.parentFile.absolutePath
        preload.forEach { file -> dirs += file.parentFile.absolutePath }
        if (nativesDirectory.isDirectory) dirs += nativesDirectory.absolutePath
        if (nativeLibraryDir != null && nativeLibraryDir.isDirectory) dirs += nativeLibraryDir.absolutePath
        systemLibraryDirs.filter { it.isDirectory }.forEach { dirs += it.absolutePath }
        return Resolved(
            javaHome = home,
            libjli = libjli,
            libjvm = libjvm,
            preload = preload,
            libraryPath = dirs.joinToString(File.pathSeparator),
        )
    }

    private fun pick(files: List<File>, name: String, preferPath: String?): File? {
        val matches = files.filter { it.name == name }
        if (matches.isEmpty()) return null
        if (preferPath != null) {
            matches.firstOrNull { it.absolutePath.replace('\\', '/').contains(preferPath) }?.let { return it }
        }
        return matches.minBy { it.absolutePath }
    }

    private fun sharedObjects(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        val seen = HashSet<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val key = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            if (!seen.add(key)) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    pending.add(child)
                } else if (child.isFile && child.name.endsWith(".so")) {
                    found += child
                }
            }
        }
        return found
    }
}
