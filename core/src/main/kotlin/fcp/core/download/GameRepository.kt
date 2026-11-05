// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.download

import fcp.core.net.ChecksumMismatchException
import fcp.core.net.DownloadException
import fcp.core.net.HttpClient
import fcp.core.net.UrlHttpClient
import fcp.core.net.get
import java.io.File

data class InstallOptions(
    /** Mojang library-rule OS name. Android/FCL's Java runtime matches `linux`. */
    val platform: String = "linux",
    val libraryLimit: Int? = null,
    val assetObjectLimit: Int? = null,
    val downloadLibraries: Boolean = true,
    val downloadAssetIndex: Boolean = true,
    val downloadAssetObjects: Boolean = true,
)

data class InstallEvent(val label: String, val completed: Long, val total: Long)

fun interface InstallListener {
    fun onEvent(event: InstallEvent)
}

data class InstallReport(
    val versionId: String,
    val versionJson: File,
    val clientJar: File,
    val librariesDownloaded: Int,
    val librariesSkipped: Int,
    val assetIndex: File?,
    val assetObjectsDownloaded: Int,
    val usedUrls: List<String>,
)

class GameRepository(
    private val http: HttpClient = UrlHttpClient(),
    val mirrors: DownloadMirrors = DownloadMirrors(),
) {
    fun fetchManifest(): VersionManifest {
        var last: Exception? = null
        for (url in mirrors.versionManifestUrls()) {
            try {
                val response = http.get(url)
                if (response.code !in 200..299) {
                    throw DownloadException("HTTP ${response.code}：$url")
                }
                return parseVersionManifest(response.bodyString(), fetchedFrom = url)
            } catch (e: Exception) {
                last = e
            }
        }
        throw DownloadException(
            "无法获取 Minecraft 版本清单。已依次尝试 BMCLAPI 和 Mojang 官方源。${last?.message.orEmpty()}",
            last,
        )
    }

    fun install(
        versionId: String,
        gameDir: File,
        options: InstallOptions = InstallOptions(),
        listener: InstallListener? = null,
    ): InstallReport {
        val manifest = fetchManifest()
        val remote = manifest.find(versionId)
        return install(remote, gameDir, options, listener)
    }

    fun install(
        remote: RemoteVersion,
        gameDir: File,
        options: InstallOptions = InstallOptions(),
        listener: InstallListener? = null,
    ): InstallReport {
        val used = mutableListOf<String>()
        val versionBytes = fetchBytes(mirrors.candidatesFor(remote.url), remote.sha1, "版本 JSON ${remote.id}", listener)
        used += "version-json"
        val metadata = parseVersionMetadata(versionBytes.toString(Charsets.UTF_8))
        val versionDir = File(gameDir, "versions/${metadata.id}")
        versionDir.mkdirs()
        val versionJson = File(versionDir, "${metadata.id}.json")
        versionJson.writeBytes(versionBytes)

        val client = metadata.client
            ?: throw DownloadException("版本 ${metadata.id} 没有客户端下载地址，无法安装")
        val clientJar = File(versionDir, "${metadata.id}.jar")
        val clientUrl = downloadFile(
            urls = mirrors.candidatesFor(client.url),
            dest = clientJar,
            sha1 = client.sha1,
            label = "客户端 ${metadata.id}",
            listener = listener,
        )
        used += clientUrl

        var librariesDownloaded = 0
        var librariesSkipped = 0
        if (options.downloadLibraries) {
            for (library in metadata.libraries) {
                if (!library.includeFor(options.platform)) {
                    librariesSkipped += 1
                    continue
                }
                if (options.libraryLimit != null && librariesDownloaded >= options.libraryLimit) {
                    librariesSkipped += 1
                    continue
                }
                val artifact = library.artifact ?: continue
                val relative = artifact.path ?: mavenRelativePath(library.name)
                val dest = File(gameDir, "libraries/$relative")
                used += downloadFile(
                    urls = mirrors.candidatesFor(artifact.url),
                    dest = dest,
                    sha1 = artifact.sha1,
                    label = "依赖库 $relative",
                    listener = listener,
                )
                librariesDownloaded += 1
            }
        }

        var assetIndexFile: File? = null
        var assetObjects = 0
        val index = metadata.assetIndex
        if (options.downloadAssetIndex && index != null && metadata.assetIndexId != null) {
            assetIndexFile = File(gameDir, "assets/indexes/${metadata.assetIndexId}.json")
            used += downloadFile(
                urls = mirrors.candidatesFor(index.url),
                dest = assetIndexFile,
                sha1 = index.sha1,
                label = "资源索引 ${metadata.assetIndexId}",
                listener = listener,
            )
            if (options.downloadAssetObjects) {
                assetObjects = downloadAssetObjects(
                    indexFile = assetIndexFile,
                    gameDir = gameDir,
                    limit = options.assetObjectLimit,
                    listener = listener,
                    used = used,
                )
            }
        }

        return InstallReport(
            versionId = metadata.id,
            versionJson = versionJson,
            clientJar = clientJar,
            librariesDownloaded = librariesDownloaded,
            librariesSkipped = librariesSkipped,
            assetIndex = assetIndexFile,
            assetObjectsDownloaded = assetObjects,
            usedUrls = used,
        )
    }

    /**
     * Fills gaps in an install that stopped early. Existing files whose size
     * matches the metadata are left in place. Missing client, libraries, the
     * asset index, and asset objects are downloaded again (BMCLAPI, then Mojang).
     * One failed object does not skip the rest of the index.
     */
    fun completeInstallation(
        versionId: String,
        gameDir: File,
        platform: String = "linux",
        listener: InstallListener? = null,
    ) {
        val seen = mutableSetOf<String>()
        fun walk(id: String) {
            if (!seen.add(id)) throw DownloadException("版本继承出现循环：$id")
            val file = File(gameDir, "versions/$id/$id.json")
            if (!file.isFile) throw DownloadException("还没有安装 $id。缺少 ${file.path}")
            val json = file.readText()
            val metadata = parseVersionMetadata(json)
            val inherits = com.google.gson.JsonParser.parseString(json).asJsonObject
                .get("inheritsFrom")?.asString?.takeIf { it.isNotBlank() }
            if (inherits != null) walk(inherits)
            completeOne(metadata, gameDir, platform, listener, downloadClient = id == versionId)
        }
        walk(versionId)
    }

    private fun completeOne(
        metadata: VersionMetadata,
        gameDir: File,
        platform: String,
        listener: InstallListener?,
        downloadClient: Boolean,
    ) {
        if (downloadClient) {
            val client = metadata.client
            val jar = File(gameDir, "versions/${metadata.id}/${metadata.id}.jar")
            if (client != null && !isReady(jar, client.sha1, client.size)) {
                downloadFile(
                    urls = mirrors.candidatesFor(client.url),
                    dest = jar,
                    sha1 = client.sha1,
                    label = "客户端 ${metadata.id}",
                    listener = listener,
                )
            }
        }
        for (library in metadata.libraries) {
            if (!library.includeFor(platform)) continue
            val artifact = library.artifact ?: continue
            val relative = artifact.path ?: mavenRelativePath(library.name)
            val dest = File(gameDir, "libraries/$relative")
            if (isReady(dest, artifact.sha1, artifact.size)) continue
            downloadFile(
                urls = mirrors.candidatesFor(artifact.url),
                dest = dest,
                sha1 = artifact.sha1,
                label = "依赖库 $relative",
                listener = listener,
            )
        }
        val index = metadata.assetIndex
        val indexId = metadata.assetIndexId
        if (index != null && indexId != null) {
            val indexFile = File(gameDir, "assets/indexes/$indexId.json")
            if (!isReady(indexFile, index.sha1, index.size)) {
                downloadFile(
                    urls = mirrors.candidatesFor(index.url),
                    dest = indexFile,
                    sha1 = index.sha1,
                    label = "资源索引 $indexId",
                    listener = listener,
                )
            }
            downloadAssetObjects(indexFile, gameDir, limit = null, listener = listener, used = mutableListOf())
        }
    }

    private fun isReady(file: File, sha1: String?, size: Long?): Boolean {
        if (!file.isFile) return false
        if (size != null && file.length() != size) return false
        if (sha1 == null) return true
        if (size != null) return true
        return sha1Hex(file).equals(sha1, ignoreCase = true)
    }

    private fun downloadAssetObjects(
        indexFile: File,
        gameDir: File,
        limit: Int?,
        listener: InstallListener?,
        used: MutableList<String>,
    ): Int {
        val root = com.google.gson.JsonParser.parseString(indexFile.readText()).asJsonObject
        val objects = root.getAsJsonObject("objects") ?: return 0
        var count = 0
        val failed = mutableListOf<String>()
        for ((name, value) in objects.entrySet()) {
            if (limit != null && count >= limit) break
            val obj = value.asJsonObject
            val hash = obj.get("hash")?.asString ?: continue
            val size = obj.get("size")?.takeUnless { it.isJsonNull }?.asLong
            val dest = File(gameDir, "assets/objects/${hash.substring(0, 2)}/$hash")
            if (isReady(dest, hash, size)) {
                count += 1
                continue
            }
            try {
                used += downloadFile(
                    urls = mirrors.assetObjectUrls(hash),
                    dest = dest,
                    sha1 = hash,
                    label = "资源 $name",
                    listener = listener,
                )
                count += 1
            } catch (e: Exception) {
                failed += "$name（${e.message}）"
            }
        }
        if (failed.isNotEmpty()) {
            val sample = failed.take(6).joinToString("；")
            throw DownloadException("有 ${failed.size} 个资源下载失败，已保留其余文件。例如：$sample")
        }
        return count
    }

    private fun fetchBytes(
        urls: List<String>,
        sha1: String?,
        label: String,
        listener: InstallListener?,
    ): ByteArray {
        var last: Exception? = null
        for (url in urls) {
            try {
                listener?.onEvent(InstallEvent(label, 0, -1))
                val response = http.get(url)
                if (response.code !in 200..299) throw DownloadException("HTTP ${response.code}：$url")
                if (sha1 != null) {
                    val actual = sha1Hex(response.body)
                    if (!actual.equals(sha1, ignoreCase = true)) {
                        throw ChecksumMismatchException(url, sha1, actual)
                    }
                }
                listener?.onEvent(InstallEvent(label, response.body.size.toLong(), response.body.size.toLong()))
                return response.body
            } catch (e: Exception) {
                last = e
            }
        }
        throw DownloadException("$label 下载失败。已尝试 BMCLAPI，并在需要时回退官方源。${last?.message.orEmpty()}", last)
    }

    private fun downloadFile(
        urls: List<String>,
        dest: File,
        sha1: String?,
        label: String,
        listener: InstallListener?,
    ): String {
        var last: Exception? = null
        for (url in urls) {
            try {
                if (dest.isFile && sha1 != null && sha1Hex(dest).equals(sha1, ignoreCase = true)) {
                    listener?.onEvent(InstallEvent("$label（已存在）", dest.length(), dest.length()))
                    return url
                }
                http.download(url, dest) { completed, total ->
                    listener?.onEvent(InstallEvent(label, completed, total))
                }
                if (sha1 != null) {
                    val actual = sha1Hex(dest)
                    if (!actual.equals(sha1, ignoreCase = true)) {
                        dest.delete()
                        throw ChecksumMismatchException(url, sha1, actual)
                    }
                }
                return url
            } catch (e: Exception) {
                dest.delete()
                last = e
            }
        }
        throw DownloadException("$label 下载失败。已尝试 BMCLAPI，并在需要时回退官方源。${last?.message.orEmpty()}", last)
    }
}
