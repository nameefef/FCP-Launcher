// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import com.google.gson.JsonParser
import fcp.core.download.sha256Hex
import fcp.core.net.HttpClient
import fcp.core.net.get
import java.io.File

/**
 * LittleSkin and other Yggdrasil accounts need authlib-injector on the JVM
 * command line. The jar is downloaded on launch (BMCLAPI first, then the
 * GitHub release) and is not vendored in this repository. The upstream project
 * is AGPL-3.0.
 */
object AuthlibInjector {
    const val FILE_NAME = "authlib-injector.jar"
    const val BMCL_LATEST = "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"
    const val GITHUB_LATEST = "https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest"

    data class Artifact(val url: String, val sha256: String?)

    fun isUsableJar(file: File): Boolean {
        if (!file.isFile || file.length() < 1024) return false
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) != 4) return false
        }
        return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
    }

    fun parseBmclLatest(json: String): Artifact {
        val root = parseObject(json, "BMCLAPI")
        val url = root.get("download_url")?.asString?.takeIf { it.startsWith("https://") }
            ?: throw LaunchException("BMCLAPI 没有给出 authlib-injector 下载地址")
        val sha256 = root.getAsJsonObject("checksums")?.get("sha256")?.asString
        return Artifact(url, sha256)
    }

    fun parseGithubLatest(json: String): Artifact {
        val root = parseObject(json, "GitHub")
        val assets = root.getAsJsonArray("assets")
            ?: throw LaunchException("GitHub 发布信息没有附件")
        val jar = assets.firstOrNull { element ->
            val name = element.asJsonObject.get("name")?.asString.orEmpty()
            name.startsWith("authlib-injector") && name.endsWith(".jar")
        }?.asJsonObject ?: throw LaunchException("GitHub 发布信息里没有 authlib-injector jar")
        val url = jar.get("browser_download_url")?.asString?.takeIf { it.startsWith("https://") }
            ?: throw LaunchException("GitHub 附件没有下载地址")
        return Artifact(url, sha256 = null)
    }

    fun ensure(dest: File, http: HttpClient): File {
        if (isUsableJar(dest)) return dest
        dest.parentFile?.mkdirs()
        val errors = mutableListOf<String>()
        val bmcl = runCatching { downloadFrom(dest, http, bmclArtifact(http)) }
        if (bmcl.isSuccess) return bmcl.getOrThrow()
        errors += "BMCLAPI：${bmcl.exceptionOrNull()?.message}"
        val github = runCatching { downloadFrom(dest, http, githubArtifact(http)) }
        if (github.isSuccess) return github.getOrThrow()
        errors += "GitHub：${github.exceptionOrNull()?.message}"
        throw LaunchException("authlib-injector 下载失败。\n" + errors.joinToString("\n"))
    }

    private fun bmclArtifact(http: HttpClient): Artifact {
        val response = http.get(BMCL_LATEST)
        if (response.code !in 200..299) throw LaunchException("HTTP ${response.code}")
        return parseBmclLatest(response.bodyString())
    }

    private fun githubArtifact(http: HttpClient): Artifact {
        val response = http.get(GITHUB_LATEST)
        if (response.code !in 200..299) throw LaunchException("HTTP ${response.code}")
        return parseGithubLatest(response.bodyString())
    }

    private fun downloadFrom(dest: File, http: HttpClient, artifact: Artifact): File {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            http.download(artifact.url, part) { _, _ -> }
            val sha = artifact.sha256
            if (sha != null) {
                val actual = sha256Hex(part)
                if (!actual.equals(sha, ignoreCase = true)) {
                    throw LaunchException("校验和不匹配（期望 $sha，实际 $actual）")
                }
            }
            if (!isUsableJar(part)) throw LaunchException("下载到的文件不是 jar")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return dest
        } catch (e: LaunchException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            throw LaunchException(e.message ?: "下载失败", e)
        }
    }

    private fun parseObject(json: String, source: String) = try {
        val element = JsonParser.parseString(json)
        if (!element.isJsonObject) throw LaunchException("$source 的 authlib-injector 信息不是 JSON 对象")
        element.asJsonObject
    } catch (e: LaunchException) {
        throw e
    } catch (e: Exception) {
        throw LaunchException("$source 的 authlib-injector 信息不是合法 JSON", e)
    }
}
