// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.launch.JreLayout
import fcp.core.launch.LaunchException
import fcp.core.launch.RuntimePacks
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class JreLayoutTest {
    @Test
    fun pojvStyleTreePrefersJliAndServerAndKeepsNativesOnThePath() {
        val root = Files.createTempDirectory("fcp-jre").toFile()
        val home = File(root, "java-17")
        touch(File(home, "bin/java"))
        touch(File(home, "lib/jli/libjli.so"))
        touch(File(home, "lib/server/libjvm.so"))
        touch(File(home, "lib/client/libjvm.so"))
        touch(File(home, "lib/libjava.so"))
        touch(File(home, "lib/libfreetype.so"))
        touch(File(home, "lib/extra/libzip.so"))
        val natives = File(root, "natives")
        touch(File(natives, "libopenal.so"))
        touch(File(natives, "libglfw.so"))
        val apkLibs = File(root, "apk-libs")
        touch(File(apkLibs, "libfcl.so"))
        val system = File(root, "system/lib64")
        system.mkdirs()

        val resolved = JreLayout.resolve(File(home, "bin/java"), natives, apkLibs, listOf(system))
        assertEquals(home.absolutePath, resolved.javaHome.absolutePath)
        assertTrue(resolved.libjli.path.endsWith("lib/jli/libjli.so"))
        assertTrue(resolved.libjvm.path.endsWith("lib/server/libjvm.so"))
        val names = resolved.preload.map { it.name }
        assertEquals("libjli.so", names[0])
        assertEquals("libjvm.so", names[1])
        assertEquals("libfreetype.so", names[2])
        assertEquals("libjava.so", names[3])
        assertTrue(names.contains("libzip.so"))
        assertTrue(names.contains("libopenal.so"))
        assertTrue(names.contains("libglfw.so"))
        assertTrue(names.contains("libfcl.so"))
        assertEquals(1, names.count { it == "libjvm.so" })
        assertTrue(resolved.libraryPath.startsWith(File(home, "lib/server").absolutePath))
        assertTrue(resolved.libraryPath.contains(File(home, "lib/jli").absolutePath))
        assertTrue(resolved.libraryPath.contains(natives.absolutePath))
        assertTrue(resolved.libraryPath.contains(apkLibs.absolutePath))
        assertTrue(resolved.libraryPath.contains(system.absolutePath))
        assertTrue(!resolved.libraryPath.contains("client"))
    }

    @Test
    fun java8TreeFindsArchSpecificLibraries() {
        val root = Files.createTempDirectory("fcp-jre8").toFile()
        val home = File(root, "java-8")
        touch(File(home, "bin/java"))
        touch(File(home, "jre/lib/amd64/jli/libjli.so"))
        touch(File(home, "jre/lib/amd64/server/libjvm.so"))
        touch(File(home, "jre/lib/amd64/libjava.so"))
        val natives = File(root, "natives").apply { mkdirs() }
        val resolved = JreLayout.resolve(File(home, "bin/java"), natives, null)
        assertTrue(resolved.libjli.path.endsWith("jre/lib/amd64/jli/libjli.so"))
        assertTrue(resolved.libjvm.path.endsWith("jre/lib/amd64/server/libjvm.so"))
        assertTrue(resolved.preload.any { it.name == "libjava.so" })
    }

    @Test
    fun missingJliNamesTheJavaHome() {
        val root = Files.createTempDirectory("fcp-jre-missing").toFile()
        val home = File(root, "java-8")
        touch(File(home, "bin/java"))
        val message = assertFailsWith<LaunchException> {
            JreLayout.resolve(File(home, "bin/java"), File(root, "natives"), null)
        }.message!!
        assertTrue(message.contains("libjli.so"))
        assertTrue(message.contains(home.path))
    }

    @Test
    fun packFilesBecomeJarsUntilTheJarExists() {
        val root = Files.createTempDirectory("fcp-pack").toFile()
        touch(File(root, "lib/rt.jar.pack"))
        touch(File(root, "lib/charsets.jar.pack"))
        val pending = RuntimePacks.jarsToUnpack(root)
        assertEquals(listOf("charsets.jar", "rt.jar"), pending.map { it.second.name })
        pending[0].second.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(listOf("rt.jar"), RuntimePacks.jarsToUnpack(root).map { it.second.name })
    }

    private fun touch(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
    }
}
