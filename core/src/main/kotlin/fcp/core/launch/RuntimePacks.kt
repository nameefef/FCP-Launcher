// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import java.io.File

/**
 * Java 8 Android runtimes ship `rt.jar.pack` instead of `rt.jar`.
 * The VM cannot see `java.lang.Object` until those packs are unpacked.
 */
object RuntimePacks {
    fun jarsToUnpack(root: File): List<Pair<File, File>> {
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf<Pair<File, File>>()
        val pending = ArrayDeque<File>()
        val seen = HashSet<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val key = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            if (!seen.add(key)) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    pending.add(child)
                } else if (child.isFile && child.name.endsWith(".pack")) {
                    val jar = File(child.path.removeSuffix(".pack"))
                    if (!jar.isFile || jar.length() == 0L) found += child to jar
                }
            }
        }
        return found.sortedBy { it.first.absolutePath }
    }
}
