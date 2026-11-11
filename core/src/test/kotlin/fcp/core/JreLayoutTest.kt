// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.launch.JreLayout
import fcp.core.launch.LaunchException
import fcp.core.launch.LwjglOverride
import fcp.core.launch.RuntimePacks
import fcp.core.launch.chooseLwjgl
import fcp.core.launch.lwjglClasspathJars
import fcp.core.launch.replacedByFclLwjgl
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
    fun java17FlatLibTreeMatchesLibjliLdPrefix() {
        val root = Files.createTempDirectory("fcp-jre17").toFile()
        val home = File(root, "java-17")
        touch(File(home, "bin/java"))
        touch(File(home, "lib/libjli.so"))
        touch(File(home, "lib/server/libjvm.so"))
        touch(File(home, "lib/libjava.so"))
        val natives = File(root, "natives").apply { mkdirs() }
        val resolved = JreLayout.resolve(File(home, "bin/java"), natives, null)
        val canonicalHome = home.canonicalPath
        val prefix = listOf(
            File(home, "lib/server").canonicalPath,
            "$canonicalHome/lib",
            "$canonicalHome/../lib",
        ).joinToString(":")
        assertTrue(resolved.libraryPath.startsWith(prefix), resolved.libraryPath)
        assertTrue(resolved.libraryPath.contains(natives.absolutePath))
        assertTrue(resolved.preload.none { it.name == "libjsig.so" })
    }

    @Test
    fun java17LdPrefixUsesLibjliRealpathWhenTheTreeIsLinked() {
        val realRoot = Files.createTempDirectory("fcp-jre17-real").toFile()
        val home = File(realRoot, "java-17")
        touch(File(home, "bin/java"))
        touch(File(home, "lib/libjli.so"))
        touch(File(home, "lib/server/libjvm.so"))
        touch(File(home, "lib/libjava.so"))
        touch(File(home, "lib/libjsig.so"))
        val linkRoot = Files.createTempDirectory("fcp-jre17-link").toFile()
        val link = File(linkRoot, "linked")
        Files.createSymbolicLink(link.toPath(), home.toPath())
        val natives = File(realRoot, "natives").apply { mkdirs() }
        val resolved = JreLayout.resolve(File(link, "bin/java"), natives, null)
        val canonicalServer = File(home, "lib/server").canonicalPath
        assertTrue(resolved.libraryPath.startsWith(canonicalServer), resolved.libraryPath)
        assertTrue(resolved.preload.none { it.name == "libjsig.so" })
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
    fun oldMinecraftUsesLwjgl2WithTheCompatibilityJar() {
        val choice = chooseLwjgl("1.7.10", listOf("org.lwjgl.lwjgl:lwjgl:2.9.1", "net.java.jinput:jinput:2.0.5"))
        assertEquals("lwjgl2", choice.slot)
        assertEquals("3.3.3", choice.bundle)
        assertTrue(choice.includeCompatibilityLayer)
        assertTrue(replacedByFclLwjgl("org.lwjgl.lwjgl:lwjgl:2.9.1"))
        assertTrue(replacedByFclLwjgl("org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux"))
        assertTrue(replacedByFclLwjgl("org.lwjgl:lwjgl-glfw:3.3.1"))
        assertTrue(replacedByFclLwjgl("net.java.jinput:jinput-platform:2.0.5"))
        assertTrue(replacedByFclLwjgl("net.java.jutils:jutils:1.0.0:natives-linux"))
        assertTrue(replacedByFclLwjgl("tv.twitch:twitch-platform:5.16"))
        assertTrue(!replacedByFclLwjgl("net.java.jinput:jinput:2.0.5"))
        assertTrue(!replacedByFclLwjgl("net.java.jutils:jutils:1.0.0"))
    }

    @Test
    fun modernMinecraftUsesLwjgl3WithoutTheCompatibilityJar() {
        val choice = chooseLwjgl("1.20.1", listOf("org.lwjgl:lwjgl:3.3.1", "org.lwjgl:lwjgl-opengl:3.3.1"))
        assertEquals("lwjgl3", choice.slot)
        assertEquals("3.3.3", choice.bundle)
        assertTrue(!choice.includeCompatibilityLayer)
        val newer = chooseLwjgl("1.21.11", listOf("org.lwjgl:lwjgl:3.4.1"))
        assertEquals("3.4.1", newer.bundle)
    }

    @Test
    fun classpathListsLwjglJarBeforeTheCompatibilityLayer() {
        val root = Files.createTempDirectory("fcp-lwjgl-jars").toFile()
        touch(File(root, "lwjgl-stb.jar"))
        touch(File(root, "lwjgl.jar"))
        touch(File(root, "lwjgl-lwjglx.jar"))
        val withLayer = lwjglClasspathJars(LwjglOverride(root, includeCompatibilityLayer = true)).map { it.name }
        assertEquals("lwjgl.jar", withLayer.first())
        assertEquals("lwjgl-lwjglx.jar", withLayer[1])
        assertTrue(withLayer.contains("lwjgl-stb.jar"))
        val modern = lwjglClasspathJars(LwjglOverride(root, includeCompatibilityLayer = false)).map { it.name }
        assertTrue(!modern.contains("lwjgl-lwjglx.jar"))
        assertEquals("lwjgl.jar", modern.first())
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
