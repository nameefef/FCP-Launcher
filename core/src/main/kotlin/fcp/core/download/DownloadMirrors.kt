// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.download

/**
 * Default download order used by HMCL and Fold Craft Launcher for mainland users:
 * BMCLAPI2 first, official Mojang hosts as fallback.
 *
 * Only those two publishers are in the default set. Loader-only or less widely
 * used CDNs (MCIM, third-party file hosts, generic Maven mirrors) are not queried.
 *
 * BMCLAPI is operated by bangbang93. The About screen must keep that credit.
 * See https://bmclapi2.bangbang93.com and https://github.com/bangbang93/BMCLAPI
 */
class DownloadMirrors(
    val bmclApiRoot: String = DEFAULT_BMCLAPI_ROOT,
    /** When false, only Mojang's official hosts are queried. */
    val bmclFirst: Boolean = true,
) {
    fun versionManifestUrls(): List<String> {
        val all = listOf(
            "$bmclApiRoot/mc/game/version_manifest_v2.json",
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "$bmclApiRoot/mc/game/version_manifest.json",
            "https://piston-meta.mojang.com/mc/game/version_manifest.json",
            "https://launchermeta.mojang.com/mc/game/version_manifest.json",
        )
        if (bmclFirst) return all
        return all.filterNot { it.startsWith(bmclApiRoot) }
    }

    fun candidatesFor(officialUrl: String): List<String> {
        if (!bmclFirst) return listOf(officialUrl)
        val mirrored = rewrite(officialUrl)
        if (mirrored == null || mirrored == officialUrl) return listOf(officialUrl)
        return listOf(mirrored, officialUrl)
    }

    fun assetObjectUrls(sha1: String): List<String> {
        require(sha1.length >= 2) { "资源哈希太短" }
        val location = sha1.substring(0, 2) + "/" + sha1
        return candidatesFor("https://resources.download.minecraft.net/$location")
    }

    private fun rewrite(officialUrl: String): String? {
        if (officialUrl.startsWith(bmclApiRoot)) return officialUrl
        val rule = PREFIXES.firstOrNull { officialUrl.startsWith(it.first) } ?: return null
        val suffix = officialUrl.substring(rule.first.length)
        val base = rule.second.replace("\$BMCL", bmclApiRoot.trimEnd('/'))
        return base + suffix
    }

    companion object {
        const val DEFAULT_BMCLAPI_ROOT = "https://bmclapi2.bangbang93.com"

        /**
         * Host prefixes HMCL rewrites onto BMCLAPI. The second value uses `$BMCL`
         * so tests can point the mirror root elsewhere.
         * `libraries.minecraft.net` and `resources.download.minecraft.net` need an
         * extra path segment; piston/launcher meta hosts keep the same path.
         */
        private val PREFIXES = listOf(
            "https://libraries.minecraft.net" to "\$BMCL/libraries",
            "http://libraries.minecraft.net" to "\$BMCL/libraries",
            "https://resources.download.minecraft.net" to "\$BMCL/assets",
            "http://resources.download.minecraft.net" to "\$BMCL/assets",
            "https://piston-meta.mojang.com" to "\$BMCL",
            "https://piston-data.mojang.com" to "\$BMCL",
            "https://launchermeta.mojang.com" to "\$BMCL",
            "https://launcher.mojang.com" to "\$BMCL",
            "http://launcher.mojang.com" to "\$BMCL",
        )
    }
}
