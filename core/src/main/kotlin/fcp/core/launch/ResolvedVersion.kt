// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import fcp.core.download.LibrarySpec
import fcp.core.download.parseRules
import fcp.core.download.parseVersionMetadata
import java.io.File

data class ResolvedVersion(
    val id: String,
    val mainClass: String?,
    val assetIndexId: String?,
    val javaMajor: Int?,
    val libraries: List<LibrarySpec>,
    val gameArguments: List<ArgumentSpec>,
    val jvmArguments: List<ArgumentSpec>,
    val legacyMinecraftArguments: String?,
)

internal data class VersionLaunchFile(
    val id: String,
    val inheritsFrom: String?,
    val mainClass: String?,
    val assetIndexId: String?,
    val javaMajor: Int?,
    val libraries: List<LibrarySpec>,
    val gameArguments: List<ArgumentSpec>,
    val jvmArguments: List<ArgumentSpec>,
    val legacyMinecraftArguments: String?,
)

fun loadResolvedVersion(gameDir: File, versionId: String): ResolvedVersion =
    load(gameDir, versionId, mutableSetOf(), root = true)

private fun load(
    gameDir: File,
    versionId: String,
    stack: MutableSet<String>,
    root: Boolean,
): ResolvedVersion {
    if (!stack.add(versionId)) {
        throw LaunchException("版本继承出现循环：$versionId")
    }
    try {
        val file = GamePaths.versionJson(gameDir, versionId)
        if (!file.isFile) {
            val lead = if (root) {
                "还没有安装 $versionId。"
            } else {
                "版本继承自 $versionId，但本地没有这个父版本。"
            }
            throw LaunchException("$lead 缺少 ${file.path}")
        }
        val parsed = try {
            parseVersionLaunchFile(file.readText())
        } catch (e: LaunchException) {
            throw e
        } catch (e: Exception) {
            throw LaunchException("无法解析 $versionId 的版本 JSON：${e.message}", e)
        }
        val parent = parsed.inheritsFrom?.let { load(gameDir, it, stack, root = false) }
        return merge(parsed, parent)
    } finally {
        stack.remove(versionId)
    }
}

internal fun parseVersionLaunchFile(json: String): VersionLaunchFile {
    val metadata = parseVersionMetadata(json)
    val root = JsonParser.parseString(json).asJsonObject
    val arguments = root.getAsJsonObject("arguments")
    val javaMajor = root.getAsJsonObject("javaVersion")?.get("majorVersion")?.takeUnless { it.isJsonNull }?.asInt
    return VersionLaunchFile(
        id = metadata.id,
        inheritsFrom = root.get("inheritsFrom")?.asString?.takeIf { it.isNotBlank() },
        mainClass = root.get("mainClass")?.asString?.takeIf { it.isNotBlank() },
        assetIndexId = metadata.assetIndexId,
        javaMajor = javaMajor,
        libraries = metadata.libraries,
        gameArguments = parseArgumentList(arguments?.getAsJsonArray("game")),
        jvmArguments = parseArgumentList(arguments?.getAsJsonArray("jvm")),
        legacyMinecraftArguments = root.get("minecraftArguments")?.asString?.takeIf { it.isNotBlank() },
    )
}

private fun merge(child: VersionLaunchFile, parent: ResolvedVersion?): ResolvedVersion {
    val childKeys = child.libraries.map { libraryKey(it.name) }.toSet()
    val parentLibs = parent?.libraries.orEmpty().filter { libraryKey(it.name) !in childKeys }
    return ResolvedVersion(
        id = child.id,
        mainClass = child.mainClass ?: parent?.mainClass,
        assetIndexId = child.assetIndexId ?: parent?.assetIndexId,
        javaMajor = child.javaMajor ?: parent?.javaMajor,
        libraries = child.libraries + parentLibs,
        gameArguments = parent?.gameArguments.orEmpty() + child.gameArguments,
        jvmArguments = parent?.jvmArguments.orEmpty() + child.jvmArguments,
        legacyMinecraftArguments = child.legacyMinecraftArguments ?: parent?.legacyMinecraftArguments,
    )
}

private fun parseArgumentList(array: JsonArray?): List<ArgumentSpec> {
    if (array == null) return emptyList()
    return array.map { element ->
        if (element.isJsonPrimitive) {
            ArgumentSpec(values = listOf(element.asString), rules = emptyList())
        } else {
            val obj = element.asJsonObject
            val raw = obj.get("value")
            val values = when {
                raw == null -> emptyList()
                raw.isJsonArray -> raw.asJsonArray.map { it.asString }
                else -> listOf(raw.asString)
            }
            ArgumentSpec(values = values, rules = parseRules(obj.getAsJsonArray("rules")))
        }
    }
}

internal fun libraryKey(coordinate: String): String {
    val parts = coordinate.substringBefore('@').split(':')
    return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else coordinate
}
