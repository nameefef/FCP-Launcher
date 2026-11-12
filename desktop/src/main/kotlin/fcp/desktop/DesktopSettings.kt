// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import fcp.core.download.DownloadMirrors
import java.io.File
import java.util.Properties

data class DesktopSettings(
    val gameDirectory: String,
    val maxMemoryMb: Int,
    val javaPath: String,
    val downloadSource: String,
) {
    fun gameDir(): File = File(gameDirectory)

    fun javaPathFile(): File? = javaPath.trim().takeIf { it.isNotEmpty() }?.let { File(it) }

    fun mirrors(): DownloadMirrors = DownloadMirrors(bmclFirst = downloadSource != SOURCE_MOJANG)

    companion object {
        const val SOURCE_BMCLAPI = "bmclapi"
        const val SOURCE_MOJANG = "mojang"
        const val VERSION = "0.3.0"

        fun configDir(): File = File(System.getProperty("user.home"), ".fcp-launcher")

        fun file(): File = File(configDir(), "settings.properties")

        fun accountsFile(): File = File(configDir(), "accounts.json")

        fun defaults(): DesktopSettings = DesktopSettings(
            gameDirectory = File(configDir(), "minecraft").absolutePath,
            maxMemoryMb = 2048,
            javaPath = "",
            downloadSource = SOURCE_BMCLAPI,
        )

        fun load(): DesktopSettings {
            val stored = file()
            if (!stored.isFile) return defaults()
            val props = Properties()
            stored.reader(Charsets.UTF_8).use { props.load(it) }
            val base = defaults()
            val source = props.getProperty("downloadSource", base.downloadSource)
            return DesktopSettings(
                gameDirectory = props.getProperty("gameDirectory", base.gameDirectory).ifBlank { base.gameDirectory },
                maxMemoryMb = props.getProperty("maxMemoryMb")?.toIntOrNull()?.coerceIn(512, 16384) ?: base.maxMemoryMb,
                javaPath = props.getProperty("javaPath", ""),
                downloadSource = if (source == SOURCE_MOJANG) SOURCE_MOJANG else SOURCE_BMCLAPI,
            )
        }

        fun save(settings: DesktopSettings) {
            val dir = configDir()
            dir.mkdirs()
            val props = Properties()
            props.setProperty("gameDirectory", settings.gameDirectory)
            props.setProperty("maxMemoryMb", settings.maxMemoryMb.coerceIn(512, 16384).toString())
            props.setProperty("javaPath", settings.javaPath)
            props.setProperty("downloadSource", settings.downloadSource)
            file().writer(Charsets.UTF_8).use { props.store(it, "FCP Launcher desktop settings") }
        }
    }
}
