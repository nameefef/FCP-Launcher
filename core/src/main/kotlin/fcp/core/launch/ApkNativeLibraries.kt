// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Copies native libraries out of an APK when the package manager did not extract
 * them. With `extractNativeLibs=false` (the AGP default) `nativeLibraryDir` is
 * empty even though the APK stores `lib/<abi>/libname.so`, and [File.isFile]
 * probes then report the runtime as missing.
 *
 * A library already present in [nativeLibraryDir] is left for the caller to
 * stage. Otherwise the first `lib/<abi>/<name>` entry matching [abis] (device
 * `Build.SUPPORTED_ABIS` order) is written to [destination].
 */
object ApkNativeLibraries {
    fun ensureExtracted(
        apk: File,
        abis: List<String>,
        names: Collection<String>,
        nativeLibraryDir: File,
        destination: File,
    ): Set<String> {
        destination.mkdirs()
        val wanted = names.map { it.trim() }.filter { isSafeLibraryName(it) }.distinct()
        val missing = wanted.filter { name -> !File(nativeLibraryDir, name).isFile }
        if (missing.isEmpty() || !apk.isFile) return emptySet()
        val extracted = linkedSetOf<String>()
        try {
            ZipFile(apk).use { zip ->
                for (name in missing) {
                    val entryName = entryName(zip, abis, name) ?: continue
                    val entry = zip.getEntry(entryName) ?: continue
                    val out = File(destination, name)
                    if (out.isFile && !Files.isSymbolicLink(out.toPath()) && out.length() == entry.size && out.length() > 0) {
                        continue
                    }
                    writeEntry(zip, entry, out)
                    extracted += name
                }
            }
        } catch (e: LaunchException) {
            throw e
        } catch (e: Exception) {
            throw LaunchException("无法从安装包解出原生库：${e.message}", e)
        }
        return extracted
    }

    internal fun entryName(zip: ZipFile, abis: List<String>, libraryName: String): String? {
        if (!isSafeLibraryName(libraryName)) return null
        for (abi in abis) {
            if (!isSafeAbi(abi)) continue
            val candidate = "lib/$abi/$libraryName"
            if (zip.getEntry(candidate) != null) return candidate
        }
        return null
    }

    internal fun isSafeLibraryName(name: String): Boolean =
        name.endsWith(".so") &&
            name.length > 3 &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..")

    private fun isSafeAbi(abi: String): Boolean =
        abi.isNotEmpty() && !abi.contains('/') && !abi.contains('\\') && !abi.contains("..")

    private fun writeEntry(zip: ZipFile, entry: ZipEntry, out: File) {
        val parent = out.parentFile ?: throw LaunchException("原生库没有目标目录：${out.path}")
        parent.mkdirs()
        val tmp = File(parent, ".${out.name}.partial")
        try {
            zip.getInputStream(entry).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val target = out.toPath()
            if (Files.exists(target) || Files.isSymbolicLink(target)) Files.delete(target)
            Files.move(tmp.toPath(), target)
            out.setReadable(true, false)
        } catch (e: Exception) {
            tmp.delete()
            throw LaunchException("写出 ${out.name} 失败：${e.message}", e)
        }
    }
}
