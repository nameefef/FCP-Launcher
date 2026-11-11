// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import fcp.core.download.sha256Hex
import fcp.core.net.HttpClient
import java.io.File
import java.util.zip.ZipFile

/**
 * Which Fold Craft Launcher LWJGL bundle a Minecraft version should run with.
 *
 * FCL 1.3.4.4 does not ship LWJGL 2. Games that declare LWJGL 2 (Minecraft
 * 1.12.2 and older) use the 3.3.3 jars plus `lwjgl-lwjglx.jar`. Newer games
 * use 3.3.3, or 3.4.1 when the version JSON asks for LWJGL 3.4.1 or newer.
 */
data class LwjglChoice(
    /** Directory name under `files/runtime/`: `lwjgl2` or `lwjgl3`. */
    val slot: String,
    /** Bundle directory inside the FCL APK, `3.3.3` or `3.4.1`. */
    val bundle: String,
    /** Add `lwjgl-lwjglx.jar` so LWJGL 2 call sites resolve. */
    val includeCompatibilityLayer: Boolean,
)

data class LwjglOverride(
    val directory: File,
    val includeCompatibilityLayer: Boolean,
)

fun chooseLwjgl(versionId: String, libraryNames: List<String>): LwjglChoice {
    val declared = highestLwjglVersion(libraryNames)
    val legacy = (declared != null && versionLess(declared, "3.0")) || legacyMinecraft(versionId)
    if (legacy) return LwjglChoice(slot = "lwjgl2", bundle = "3.3.3", includeCompatibilityLayer = true)
    if (declared != null && !versionLess(declared, "3.4.1")) {
        return LwjglChoice(slot = "lwjgl3", bundle = "3.4.1", includeCompatibilityLayer = false)
    }
    return LwjglChoice(slot = "lwjgl3", bundle = "3.3.3", includeCompatibilityLayer = false)
}

/** Maven libraries FCL removes before it adds `app_runtime/lwjgl`. */
fun replacedByFclLwjgl(mavenName: String): Boolean {
    val name = mavenName.lowercase()
    if ("org.lwjgl" in name || "lwjgl-platform" in name) return true
    if ("jinput-platform" in name || "twitch-platform" in name) return true
    val nativeBits = "natives" in name || "platform" in name
    return nativeBits && ("jinput" in name || "jutils" in name)
}

fun lwjglClasspathJars(override: LwjglOverride): List<File> {
    val jars = override.directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".jar") }
        ?.sortedBy { it.name }
        ?: emptyList()
    val ordered = mutableListOf<File>()
    jars.firstOrNull { it.name == "lwjgl.jar" }?.let { ordered += it }
    if (override.includeCompatibilityLayer) {
        jars.firstOrNull { it.name == "lwjgl-lwjglx.jar" }?.let { ordered += it }
    }
    for (jar in jars) {
        if (jar.name == "lwjgl.jar" || jar.name == "lwjgl-lwjglx.jar") continue
        ordered += jar
    }
    return ordered
}

/**
 * Downloads the pinned FCL APK (reusing the Java 8 cache) and unpacks one
 * LWJGL bundle to [dest] plus its `.so` files into [sharedNatives].
 */
class LwjglInstaller {
    fun ensure(
        catalog: RuntimeCatalog,
        http: HttpClient,
        abi: String,
        choice: LwjglChoice,
        runtimeRoot: File,
        sharedNatives: File,
        onStatus: (String) -> Unit = {},
    ): File {
        val artifact = catalog.findLwjgl(choice.bundle, abi)
            ?: throw LaunchException("清单里没有 LWJGL ${choice.bundle}（$abi）。")
        val dest = File(runtimeRoot, choice.slot)
        val marker = File(dest, ".fcp-lwjgl-bundle")
        val expected = "${artifact.version} ${artifact.sha256}"
        if (marker.isFile && marker.readText().trim() == expected && File(dest, "lwjgl.jar").isFile) {
            copyNatives(File(dest, "natives"), sharedNatives)
            return dest
        }
        onStatus("正在准备 LWJGL ${artifact.version}…")
        val cache = File(runtimeRoot, "cache/${artifact.sha256}")
        downloadVerified(http, artifact.url, artifact.sha256, cache, onStatus, "正在下载 LWJGL ${artifact.version}")
        dest.mkdirs()
        ZipFile(cache).use { zip ->
            for (file in artifact.jars) {
                writeChecked(zip, file, File(dest, file.name))
            }
            val nativeDir = File(dest, "natives")
            nativeDir.mkdirs()
            for (file in artifact.natives) {
                writeChecked(zip, file, File(nativeDir, file.name))
            }
        }
        if (!File(dest, "lwjgl.jar").isFile) {
            throw LaunchException("解压后没有 lwjgl.jar。来源：${artifact.source}")
        }
        marker.writeText(expected)
        copyNatives(File(dest, "natives"), sharedNatives)
        return dest
    }

    private fun copyNatives(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        for (file in from.listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".so")) continue
            val dest = File(to, file.name)
            if (dest.isFile && dest.length() == file.length() && sha256Hex(dest).equals(sha256Hex(file), ignoreCase = true)) {
                continue
            }
            file.copyTo(dest, overwrite = true)
        }
    }

    private fun writeChecked(zip: ZipFile, file: LwjglFile, dest: File) {
        val entry = zip.getEntry(file.entry) ?: throw LaunchException("FCL 安装包里没有 ${file.entry}")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.partial")
        try {
            zip.getInputStream(entry).use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            val actual = sha256Hex(tmp)
            if (!actual.equals(file.sha256, ignoreCase = true)) {
                throw LaunchException("${file.name} 校验和不匹配（期望 ${file.sha256}，实际 $actual）")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: LaunchException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw LaunchException("写出 ${file.name} 失败：${e.message}", e)
        }
    }

    private fun downloadVerified(
        http: HttpClient,
        url: String,
        sha256: String,
        dest: File,
        onStatus: (String) -> Unit,
        label: String,
    ) {
        if (dest.isFile && sha256Hex(dest).equals(sha256, ignoreCase = true)) return
        val part = File(dest.parentFile, dest.name + ".download")
        var lastPercent = -1
        try {
            http.download(url, part) { completed, total ->
                val percent = if (total > 0) ((completed * 100) / total).toInt() else -1
                if (percent != lastPercent) {
                    lastPercent = percent
                    onStatus(if (percent >= 0) "$label $percent%" else "$label…")
                }
            }
            val actual = sha256Hex(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                throw LaunchException("LWJGL 安装包校验和不匹配（期望 $sha256，实际 $actual）")
            }
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } catch (e: LaunchException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            throw LaunchException("LWJGL 安装包下载失败：$url ${e.message}", e)
        }
    }
}

private fun highestLwjglVersion(names: List<String>): String? {
    var best: String? = null
    for (name in names) {
        val parts = name.split(":")
        if (parts.size < 3 || !parts[0].startsWith("org.lwjgl")) continue
        if (parts[1] != "lwjgl" && parts[1] != "lwjgl-opengl") continue
        val version = parts[2]
        if (best == null || versionLess(best, version)) best = version
    }
    return best
}

private fun legacyMinecraft(versionId: String): Boolean {
    val match = Regex("""(?:^|[^0-9])1\.(\d+)""").find(versionId) ?: return false
    return (match.groupValues[1].toIntOrNull() ?: return false) <= 12
}

private fun versionLess(left: String, right: String): Boolean {
    val a = left.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val b = right.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val count = maxOf(a.size, b.size)
    for (i in 0 until count) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av < bv
    }
    return false
}
