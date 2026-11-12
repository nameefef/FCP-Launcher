// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import com.google.gson.JsonParser
import fcp.core.download.DownloadMirrors
import fcp.core.download.LibrarySpec
import fcp.core.download.RemoteFile
import fcp.core.download.sha1Hex
import fcp.core.net.HttpClient
import java.io.File
import java.util.zip.ZipFile

/**
 * Mojang OS and CPU, used to pick LWJGL native jars out of a version JSON.
 * Android keeps using Fold Craft Launcher's `.so` files instead of this.
 */
data class DesktopOs(
    /** `linux`, `osx`, or `windows`, matching version-json rules. */
    val mojangName: String,
    /** `x64`, `x86`, `arm64`, `arm32`, or `riscv64`. */
    val arch: String,
    /** Value substituted for `${arch}` in legacy classifier names: `64` or `32`. */
    val archToken: String,
) {
    companion object {
        fun current(): DesktopOs {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val mojang = when {
                "mac" in osName || "darwin" in osName -> "osx"
                "win" in osName -> "windows"
                else -> "linux"
            }
            val archProp = System.getProperty("os.arch").orEmpty().lowercase()
            val arch = when {
                archProp == "aarch64" || archProp == "arm64" -> "arm64"
                archProp.startsWith("arm") -> "arm32"
                archProp == "x86" || archProp == "i386" || archProp == "i686" -> "x86"
                "riscv64" in archProp -> "riscv64"
                else -> "x64"
            }
            val token = if (arch == "x86" || arch == "arm32") "32" else "64"
            return DesktopOs(mojang, arch, token)
        }
    }
}

data class NativeJar(
    val coordinate: String,
    val relativePath: String,
    val url: String,
    val sha1: String?,
    val excludes: List<String>,
)

/** Maven coordinate whose classifier is a native bundle (`natives-linux`, …). */
fun isNativeLibraryCoordinate(coordinate: String): Boolean {
    val classifier = coordinate.substringBefore('@').split(':').getOrNull(3) ?: return false
    return classifier.startsWith("natives-")
}

fun usesLwjgl2(libraries: List<LibrarySpec>): Boolean =
    libraries.any { library ->
        val parts = library.name.substringBefore('@').split(':')
        parts.size >= 3 && parts[0] == "org.lwjgl.lwjgl" && parts[1] == "lwjgl"
    }

/**
 * Desktop native jars declared by a version JSON.
 * Legacy entries use the `natives` map and `downloads.classifiers`.
 * Modern entries are separate libraries whose classifier starts with `natives-`.
 * Rules and the classifier name both have to match [os]. 1.20.x publishes
 * several macOS and Windows native jars under the same OS rule and distinguishes
 * them by the classifier (`natives-macos` vs `natives-macos-arm64`).
 */
fun nativeJars(libraries: List<LibrarySpec>, os: DesktopOs): List<NativeJar> {
    val out = mutableListOf<NativeJar>()
    for (library in libraries) {
        if (!rulesAllow(library.rules, os)) continue
        if (library.nativeClassifiers.isNotEmpty()) {
            val template = library.nativeClassifiers[os.mojangName] ?: continue
            val classifier = template.replace("\${arch}", os.archToken)
            val remote = library.classifiers[classifier]
                ?: throw LaunchException(
                    "版本库 ${library.name} 声明了 $classifier，但版本 JSON 里没有这个原生包。",
                )
            out += nativeJar(library.name, remote, library.extractExcludes)
            continue
        }
        val classifier = library.name.substringBefore('@').split(':').getOrNull(3) ?: continue
        if (!classifier.startsWith("natives-")) continue
        if (!classifierMatches(classifier, os)) continue
        val artifact = library.artifact ?: continue
        out += nativeJar(library.name, artifact, library.extractExcludes)
    }
    return out
}

object DesktopNatives {
    fun prepare(
        gameDir: File,
        versionId: String,
        libraries: List<LibrarySpec>,
        os: DesktopOs,
        mirrors: DownloadMirrors,
        http: HttpClient,
        onStatus: (String) -> Unit = {},
    ): File {
        val jars = nativeJars(libraries, os)
        if (jars.isEmpty()) {
            throw LaunchException(
                "没有找到适用于当前系统（${os.mojangName} / ${os.arch}）的原生库，无法启动 $versionId。\n" +
                    "桌面端使用版本 JSON 里的 LWJGL 原生包，不使用 Android 的 Fold Craft Launcher 库。",
            )
        }
        ensureDownloaded(gameDir, jars, mirrors, http, onStatus)
        return extract(gameDir, versionId, jars, onStatus)
    }

    /**
     * Log4j config referenced by `logging.client` in the version JSON.
     * Returns the JVM argument, or null when the version has none or the file
     * could not be downloaded. Launch still proceeds without it.
     */
    fun loggingArgument(
        versionJson: String,
        gameDir: File,
        mirrors: DownloadMirrors,
        http: HttpClient,
    ): String? {
        val root = try {
            JsonParser.parseString(versionJson).asJsonObject
        } catch (_: Exception) {
            return null
        }
        val client = root.getAsJsonObject("logging")?.getAsJsonObject("client") ?: return null
        val file = client.getAsJsonObject("file") ?: return null
        val id = file.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val url = file.get("url")?.asString?.takeIf { it.startsWith("https://") } ?: return null
        val sha1 = file.get("sha1")?.asString
        val template = client.get("argument")?.asString?.takeIf { it.contains("\${path}") } ?: return null
        val dest = File(gameDir, "assets/log_configs/$id")
        try {
            download(mirrors.candidatesFor(url), dest, sha1, "日志配置 $id", http) {}
        } catch (_: Exception) {
            return null
        }
        return template.replace("\${path}", dest.absolutePath)
    }

    private fun ensureDownloaded(
        gameDir: File,
        jars: List<NativeJar>,
        mirrors: DownloadMirrors,
        http: HttpClient,
        onStatus: (String) -> Unit,
    ) {
        for (jar in jars) {
            val dest = File(gameDir, "libraries/${jar.relativePath}")
            download(
                urls = mirrors.candidatesFor(jar.url),
                dest = dest,
                sha1 = jar.sha1,
                label = "原生库 ${jar.relativePath}",
                http = http,
                onStatus = onStatus,
            )
        }
    }

    private fun extract(gameDir: File, versionId: String, jars: List<NativeJar>, onStatus: (String) -> Unit): File {
        val dest = File(gameDir, "versions/$versionId/natives")
        dest.mkdirs()
        val fingerprint = jars.joinToString("\n") { jar ->
            val file = File(gameDir, "libraries/${jar.relativePath}")
            "${jar.relativePath}:${if (file.isFile) file.length() else 0}:${jar.sha1.orEmpty()}"
        }
        val marker = File(dest, ".fcp-natives")
        val already = dest.listFiles().orEmpty().any { it.isFile && isNativeBinary(it.name) }
        if (already && marker.isFile && marker.readText() == fingerprint) return dest
        dest.listFiles()?.forEach { file ->
            if (file.name != ".fcp-natives") file.delete()
        }
        val written = mutableListOf<String>()
        for (jar in jars) {
            onStatus("正在解压 ${File(jar.relativePath).name}")
            val file = File(gameDir, "libraries/${jar.relativePath}")
            if (!file.isFile) {
                throw LaunchException("原生库还没有下载：${file.path}")
            }
            written += extractNativeJar(file, dest, jar.excludes)
        }
        if (written.none { isNativeBinary(it) }) {
            throw LaunchException("原生库解压后没有 .so、.dll 或 .dylib，无法启动 $versionId。")
        }
        marker.writeText(fingerprint)
        return dest
    }
}

internal fun extractNativeJar(jar: File, dest: File, excludes: List<String>): List<String> {
    val written = mutableListOf<String>()
    try {
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (name.isBlank() || name.endsWith("/")) continue
                if (name == "META-INF" || name.startsWith("META-INF/")) continue
                if (excludes.any { exclude -> name == exclude || name.startsWith(exclude) }) continue
                val fileName = name.substringAfterLast('/')
                if (fileName.isBlank() || fileName == ".fcp-natives" || fileName.contains("..")) continue
                val out = File(dest, fileName)
                if (out.parentFile != dest) continue
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setReadable(true, false)
                out.setExecutable(true, false)
                written += fileName
            }
        }
    } catch (e: LaunchException) {
        throw e
    } catch (e: Exception) {
        throw LaunchException("无法解压原生库 ${jar.name}：${e.message}", e)
    }
    return written
}

private fun download(
    urls: List<String>,
    dest: File,
    sha1: String?,
    label: String,
    http: HttpClient,
    onStatus: (String) -> Unit,
) {
    if (dest.isFile && sha1 != null && sha1Hex(dest).equals(sha1, ignoreCase = true)) return
    if (dest.isFile && sha1 == null && dest.length() > 0) return
    var last: Exception? = null
    for (url in urls) {
        try {
            onStatus(label)
            http.download(url, dest) { _, _ -> }
            if (sha1 != null) {
                val actual = sha1Hex(dest)
                if (!actual.equals(sha1, ignoreCase = true)) {
                    dest.delete()
                    throw LaunchException("文件校验失败（SHA-1 不匹配）：$url")
                }
            }
            return
        } catch (e: Exception) {
            dest.delete()
            last = e
        }
    }
    throw LaunchException(
        "$label 下载失败。已尝试 BMCLAPI，并在需要时回退官方源。${last?.message.orEmpty()}",
        last,
    )
}

private fun nativeJar(name: String, remote: RemoteFile, excludes: List<String>): NativeJar {
    val relative = remote.path?.takeIf { it.isNotBlank() }
        ?: throw LaunchException("原生库 $name 没有路径。")
    return NativeJar(
        coordinate = name,
        relativePath = relative,
        url = remote.url,
        sha1 = remote.sha1,
        excludes = excludes,
    )
}

private fun rulesAllow(rules: List<fcp.core.download.RuleSpec>, os: DesktopOs): Boolean {
    if (rules.isEmpty()) return true
    var allow = false
    for (rule in rules) {
        if (rule.matches(os.mojangName, os.arch)) {
            allow = rule.action.equals("allow", ignoreCase = true)
        }
    }
    return allow
}

internal fun classifierMatches(classifier: String, os: DesktopOs): Boolean {
    val body = classifier.lowercase().removePrefix("natives-")
    return when (os.mojangName) {
        "linux" -> when (os.arch) {
            "arm64" -> body == "linux-arm64"
            "arm32" -> body == "linux-arm32" || body == "linux-arm"
            "riscv64" -> body == "linux-riscv64"
            "x86" -> body == "linux-x86"
            else -> body == "linux"
        }
        "windows" -> when (os.arch) {
            "arm64" -> body == "windows-arm64"
            "x86" -> body == "windows-x86" || body == "windows-32"
            else -> body == "windows" || body == "windows-64"
        }
        "osx" -> when (os.arch) {
            "arm64" -> body == "macos-arm64" || body == "osx-arm64"
            else -> body == "macos" || body == "osx"
        }
        else -> false
    }
}

private fun isNativeBinary(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")
}
