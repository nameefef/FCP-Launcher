// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import com.google.gson.JsonParser
import fcp.core.download.sha256Hex
import fcp.core.net.HttpClient
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

data class JreArtifact(
    val major: Int,
    val abi: String,
    val url: String,
    val sha256: String,
    val format: String,
    val entries: List<String>,
    val source: String,
)

data class RuntimeCatalog(val jre: List<JreArtifact>) {
    fun find(major: Int, abi: String): JreArtifact? =
        jre.firstOrNull { it.major == major && it.abi == abi }
}

fun parseRuntimeCatalog(json: String): RuntimeCatalog {
    val root = try {
        JsonParser.parseString(json).asJsonObject
    } catch (e: Exception) {
        throw LaunchException("运行时清单不是合法 JSON", e)
    }
    val artifacts = root.getAsJsonArray("jre")?.map { element ->
        val item = element.asJsonObject
        JreArtifact(
            major = item.get("major")?.asInt ?: throw LaunchException("运行时清单缺少 major"),
            abi = item.get("abi")?.asString ?: throw LaunchException("运行时清单缺少 abi"),
            url = item.get("url")?.asString?.takeIf { it.startsWith("https://") }
                ?: throw LaunchException("运行时清单的下载地址不是 https"),
            sha256 = item.get("sha256")?.asString?.takeIf { it.length == 64 }
                ?: throw LaunchException("运行时清单缺少 sha256"),
            format = item.get("format")?.asString ?: "tar.xz",
            entries = item.getAsJsonArray("entries")?.map { it.asString } ?: emptyList(),
            source = item.get("source")?.asString ?: "",
        )
    } ?: throw LaunchException("运行时清单缺少 jre")
    if (artifacts.isEmpty()) throw LaunchException("运行时清单是空的")
    return RuntimeCatalog(artifacts)
}

fun loadBundledRuntimeCatalog(): RuntimeCatalog {
    val stream = RuntimeCatalog::class.java.getResourceAsStream("/fcp/android-runtime.json")
        ?: throw LaunchException("安装包里没有 Android 运行时清单")
    return stream.use { parseRuntimeCatalog(it.reader().readText()) }
}

/**
 * Downloads a Pojav JRE 17 tarball, or a Fold Craft Launcher APK that contains
 * the Java 8 / 21 runtime, and unpacks it to `runtime/java-<major>/bin/java`.
 */
class RuntimeInstaller {
    fun ensureJava(
        catalog: RuntimeCatalog,
        http: HttpClient,
        abi: String,
        major: Int,
        runtimeRoot: File,
        onStatus: (String) -> Unit = {},
    ): File {
        val artifact = catalog.find(major, abi)
            ?: throw LaunchException("清单里没有 Java $major（$abi）的 Android 运行时。已有：${catalog.jre.joinToString { "${it.major}/${it.abi}" }}")
        val dest = File(runtimeRoot, "java-$major")
        val java = File(dest, "bin/java")
        val marker = File(dest, ".fcp-runtime-sha256")
        if (java.isFile && marker.isFile && marker.readText().trim().equals(artifact.sha256, ignoreCase = true)) {
            java.setExecutable(true, false)
            return java
        }
        val label = "正在下载 Java $major（$abi）"
        onStatus("$label…")
        val cache = File(runtimeRoot, "cache/${artifact.sha256}")
        downloadVerified(http, artifact.url, artifact.sha256, cache, onStatus, label)
        onStatus("正在解压 Java $major…")
        dest.mkdirs()
        when (artifact.format) {
            "tar.xz" -> cache.inputStream().use { extractTarXz(it, dest) }
            "fcl-apk" -> extractFclApk(cache, artifact.entries, dest)
            else -> throw LaunchException("不认识的运行时格式：${artifact.format}")
        }
        if (!java.isFile) {
            throw LaunchException("解压后没有 bin/java。来源：${artifact.source}")
        }
        java.setExecutable(true, false)
        marker.writeText(artifact.sha256)
        return java
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
                throw LaunchException("Java 运行时校验和不匹配（期望 $sha256，实际 $actual）")
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
            throw LaunchException("Java 运行时下载失败：$url ${e.message}", e)
        }
    }
}

internal fun extractTarXz(input: InputStream, dest: File) {
    XZCompressorInputStream(input).use { xz ->
        TarArchiveInputStream(xz).use { tar ->
            while (true) {
                @Suppress("DEPRECATION")
            val entry = tar.nextTarEntry ?: break
                writeTarEntry(tar, entry, dest)
            }
        }
    }
}

internal fun extractFclApk(apk: File, entries: List<String>, dest: File) {
    if (entries.isEmpty()) throw LaunchException("FCL 运行时清单没有给出要解压的 tar.xz")
    ZipFile(apk).use { zip ->
        for (name in entries) {
            val entry = zip.getEntry(name) ?: throw LaunchException("FCL 安装包里没有 $name")
            zip.getInputStream(entry).use { extractTarXz(it, dest) }
        }
    }
}

private fun writeTarEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, dest: File) {
    val name = entry.name.removePrefix("./").trimStart('/')
    if (name.isBlank()) return
    val out = dest.toPath().resolve(name).normalize()
    if (!out.startsWith(dest.toPath().normalize())) {
        throw LaunchException("运行时压缩包包含越界路径：${entry.name}")
    }
    val file = out.toFile()
    if (entry.isSymbolicLink) {
        val link = entry.linkName ?: return
        val parent = file.toPath().parent ?: return
        val target = parent.resolve(link).normalize()
        if (!target.startsWith(dest.toPath().normalize())) return
        file.parentFile?.mkdirs()
        if (Files.exists(file.toPath()) || Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), parent.relativize(target))
        return
    }
    if (entry.isDirectory) {
        file.mkdirs()
        return
    }
    file.parentFile?.mkdirs()
    file.outputStream().use { output -> tar.copyTo(output) }
    if (entry.mode and 0b001_001_001 != 0 || name.startsWith("bin/")) {
        file.setExecutable(true, false)
    }
}

/** Test helper. Not used by the app. */
internal fun writeTarXz(dest: File, files: Map<String, ByteArray>) {
    dest.parentFile?.mkdirs()
    XZCompressorOutputStream(dest.outputStream()).use { xz ->
        TarArchiveOutputStream(xz).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, bytes) in files) {
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                entry.mode = if (name.startsWith("bin/")) 0b111_101_101 else 0b110_100_100
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
}
