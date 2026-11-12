// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.download

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fcp.core.net.DownloadException

data class RemoteVersion(
    val id: String,
    val type: String,
    val url: String,
    val sha1: String?,
    val releaseTime: String?,
)

data class VersionManifest(
    val latestRelease: String?,
    val latestSnapshot: String?,
    val versions: List<RemoteVersion>,
    val fetchedFrom: String,
) {
    fun find(id: String): RemoteVersion =
        versions.firstOrNull { it.id == id }
            ?: throw DownloadException("版本清单里没有 $id")
}

data class RemoteFile(
    val url: String,
    val sha1: String?,
    val size: Long?,
    val path: String?,
)

data class RuleSpec(
    val action: String,
    val osName: String?,
    val osArch: String?,
    val hasFeatures: Boolean,
    val osVersion: String? = null,
) {
    fun matches(platform: String): Boolean = matches(platform, arch = null)

    /**
     * [arch] is the launcher arch (`x64`, `x86`, `arm64`, `arm32`).
     * A missing [arch] does not satisfy an arch-specific rule, which is the
     * Android install path. Version constraints are not evaluated.
     */
    fun matches(platform: String, arch: String?): Boolean {
        if (hasFeatures || !osVersion.isNullOrBlank()) return false
        if (osArch != null && !osArchEquals(osArch, arch)) return false
        if (osName == null) return true
        return osName.equals(platform, ignoreCase = true)
    }
}

private fun osArchEquals(ruleArch: String, actual: String?): Boolean {
    if (actual == null) return false
    fun norm(value: String): String = when (value.lowercase()) {
        "x86_64", "amd64", "x86-64", "x64" -> "x64"
        "i386", "i686", "x86" -> "x86"
        "aarch64", "arm64" -> "arm64"
        "arm", "armv7", "armv7l", "arm32" -> "arm32"
        else -> value.lowercase()
    }
    return norm(ruleArch) == norm(actual)
}

data class LibrarySpec(
    val name: String,
    val rules: List<RuleSpec>,
    val artifact: RemoteFile?,
    val nativeOnly: Boolean,
    /** Legacy `downloads.classifiers`, keyed by classifier such as `natives-linux`. */
    val classifiers: Map<String, RemoteFile> = emptyMap(),
    /** Legacy `natives` map: Mojang OS name to classifier, which may contain `${arch}`. */
    val nativeClassifiers: Map<String, String> = emptyMap(),
    val extractExcludes: List<String> = emptyList(),
) {
    /**
     * Classpath and install filter. A legacy natives-only entry (a `natives`
     * map and no artifact) stays out of this list; desktop launch downloads
     * that classifier separately and extracts it. Modern `natives-*` artifacts
     * are included so the jar is downloaded, then kept off the game classpath.
     * [platform] is Mojang's library rule name. Android passes `linux`.
     */
    fun includeFor(platform: String): Boolean {
        if (nativeOnly || artifact == null) return false
        if (rules.isEmpty()) return true
        var allow = false
        for (rule in rules) {
            if (rule.matches(platform)) allow = rule.action.equals("allow", ignoreCase = true)
        }
        return allow
    }
}

data class VersionMetadata(
    val id: String,
    val client: RemoteFile?,
    val assetIndex: RemoteFile?,
    val assetIndexId: String?,
    val libraries: List<LibrarySpec>,
)

fun parseVersionManifest(json: String, fetchedFrom: String): VersionManifest {
    val root = parseObject(json, "版本清单")
    val latest = root.getAsJsonObject("latest")
    val versions = root.getAsJsonArray("versions")?.map { element ->
        val item = element.asJsonObject
        RemoteVersion(
            id = item.requireString("id"),
            type = item.get("type")?.asString ?: "release",
            url = item.requireString("url"),
            sha1 = item.get("sha1")?.asString,
            releaseTime = item.get("releaseTime")?.asString,
        )
    } ?: throw DownloadException("版本清单缺少 versions")
    if (versions.isEmpty()) throw DownloadException("版本清单是空的")
    return VersionManifest(
        latestRelease = latest?.get("release")?.asString,
        latestSnapshot = latest?.get("snapshot")?.asString,
        versions = versions,
        fetchedFrom = fetchedFrom,
    )
}

fun parseVersionMetadata(json: String): VersionMetadata {
    val root = parseObject(json, "版本 JSON")
    val id = root.requireString("id")
    val downloads = root.getAsJsonObject("downloads")
    val client = downloads?.getAsJsonObject("client")?.toRemoteFile(path = null)
    val assetIndexObject = root.getAsJsonObject("assetIndex")
    val assetIndex = assetIndexObject?.toRemoteFile(path = null)
    val libraries = root.getAsJsonArray("libraries").orEmpty().map { element ->
        parseLibrary(element.asJsonObject)
    }
    return VersionMetadata(
        id = id,
        client = client,
        assetIndex = assetIndex,
        assetIndexId = assetIndexObject?.get("id")?.asString ?: root.get("assets")?.asString,
        libraries = libraries,
    )
}

private fun parseLibrary(lib: JsonObject): LibrarySpec {
    val name = lib.requireString("name")
    val rules = parseRules(lib.getAsJsonArray("rules"))
    val downloads = lib.getAsJsonObject("downloads")
    val artifactObject = downloads?.getAsJsonObject("artifact")
    val artifact = artifactObject?.toRemoteFile(path = artifactObject.get("path")?.asString) ?: mavenArtifact(lib, name)
    val natives = lib.get("natives")
    val nativeOnly = natives != null && artifact == null
    val nativeClassifiers = if (natives != null && natives.isJsonObject) {
        natives.asJsonObject.entrySet().associate { (key, value) -> key to value.asString }
    } else {
        emptyMap()
    }
    val classifiers = downloads?.getAsJsonObject("classifiers")?.entrySet()?.associate { (key, value) ->
        val obj = value.asJsonObject
        key to obj.toRemoteFile(path = obj.get("path")?.asString)
    }.orEmpty()
    val excludes = lib.getAsJsonObject("extract")?.getAsJsonArray("exclude")?.mapNotNull { element ->
        element.takeIf { it.isJsonPrimitive }?.asString
    }.orEmpty()
    return LibrarySpec(
        name = name,
        rules = rules,
        artifact = artifact,
        nativeOnly = nativeOnly,
        classifiers = classifiers,
        nativeClassifiers = nativeClassifiers,
        extractExcludes = excludes,
    )
}

private fun mavenArtifact(lib: JsonObject, name: String): RemoteFile? {
    if (lib.get("natives") != null && lib.getAsJsonObject("downloads")?.get("artifact") == null) {
        return null
    }
    val base = (lib.get("url")?.asString ?: "https://libraries.minecraft.net/").trimEnd('/') + "/"
    val path = mavenRelativePath(name)
    return RemoteFile(url = base + path, sha1 = null, size = null, path = path)
}

fun mavenRelativePath(coordinate: String): String {
    val extParts = coordinate.split("@", limit = 2)
    val extension = extParts.getOrElse(1) { "jar" }
    val parts = extParts[0].split(":")
    if (parts.size < 3) throw DownloadException("无法解析库坐标：$coordinate")
    val groupPath = parts[0].replace('.', '/')
    val artifact = parts[1]
    val version = parts[2]
    val classifier = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
    val fileName = buildString {
        append(artifact).append('-').append(version)
        if (classifier != null) append('-').append(classifier)
        append('.').append(extension)
    }
    return "$groupPath/$artifact/$version/$fileName"
}

private fun JsonObject.toRemoteFile(path: String?): RemoteFile {
    val url = requireString("url")
    return RemoteFile(
        url = url,
        sha1 = get("sha1")?.asString,
        size = get("size")?.asLong,
        path = path ?: get("path")?.asString,
    )
}

private fun parseObject(json: String, what: String): JsonObject {
    val element = try {
        JsonParser.parseString(json)
    } catch (e: Exception) {
        throw DownloadException("$what 不是合法 JSON", e)
    }
    if (!element.isJsonObject) throw DownloadException("$what 不是 JSON 对象")
    return element.asJsonObject
}

fun parseRules(array: com.google.gson.JsonArray?): List<RuleSpec> =
    array.orEmpty().map { element ->
        val rule = element.asJsonObject
        val os = rule.getAsJsonObject("os")
        RuleSpec(
            action = rule.get("action")?.asString ?: "allow",
            osName = os?.get("name")?.asString,
            osArch = os?.get("arch")?.asString,
            hasFeatures = rule.get("features") != null,
            osVersion = os?.get("version")?.asString,
        )
    }

private fun JsonObject.requireString(name: String): String =
    get(name)?.asString?.takeIf { it.isNotBlank() }
        ?: throw DownloadException("JSON 缺少 $name")

private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
    this?.toList() ?: emptyList()
