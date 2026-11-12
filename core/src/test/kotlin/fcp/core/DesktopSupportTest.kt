// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.download.DownloadMirrors
import fcp.core.download.parseVersionMetadata
import fcp.core.launch.DesktopOs
import fcp.core.launch.GameLauncher
import fcp.core.launch.GamePaths
import fcp.core.launch.JavaInstallation
import fcp.core.launch.JavaRuntimes
import fcp.core.launch.LaunchAccount
import fcp.core.launch.LaunchRequest
import fcp.core.launch.classifierMatches
import fcp.core.launch.extractNativeJar
import fcp.core.launch.nativeJars
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DesktopSupportTest {
    @Test
    fun nativeJarsFollowOsAndClassifierNotTheAndroidRuntime() {
        val libraries = parseVersionMetadata(nativeJson()).libraries
        val linux = nativeJars(libraries, DesktopOs("linux", "x64", "64")).map { it.relativePath }
        assertTrue(linux.any { it.endsWith("lwjgl-platform-2.9.1-natives-linux.jar") })
        assertTrue(linux.any { it.endsWith("jinput-platform-2.0.5-natives-linux.jar") })
        assertTrue(linux.any { it.endsWith("lwjgl-glfw-3.3.1-natives-linux.jar") })
        assertFalse(linux.any { it.contains("natives-windows") })
        assertFalse(linux.any { it.contains("natives-osx") || it.contains("natives-macos") })
        assertFalse(linux.any { it.contains("twitch") })
        assertFalse(linux.any { it.contains("arm64") })

        val arm = nativeJars(libraries, DesktopOs("linux", "arm64", "64")).map { it.relativePath }
        assertEquals(listOf("org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-linux-arm64.jar"), arm.filter { "glfw" in it })

        val macArm = nativeJars(libraries, DesktopOs("osx", "arm64", "64")).map { it.relativePath }
        assertTrue(macArm.any { it.endsWith("lwjgl-glfw-3.3.1-natives-macos-arm64.jar") })
        assertFalse(macArm.any { it.endsWith("lwjgl-glfw-3.3.1-natives-macos.jar") })

        val windows = nativeJars(libraries, DesktopOs("windows", "x64", "64")).map { it.relativePath }
        assertTrue(windows.any { it.endsWith("lwjgl-glfw-3.3.1-natives-windows.jar") })
        assertTrue(windows.any { it.endsWith("twitch-platform-5.16-natives-windows-64.jar") })
        assertFalse(windows.any { it.endsWith("natives-windows-x86.jar") || it.contains("windows-arm64") })
        assertTrue(classifierMatches("natives-linux", DesktopOs("linux", "x64", "64")))
        assertFalse(classifierMatches("natives-linux", DesktopOs("linux", "arm64", "64")))
    }

    @Test
    fun nativeJarIsExtractedFlatAndKeptOffTheClasspath() {
        val root = Files.createTempDirectory("fcp-desktop-natives").toFile()
        val jar = File(root, "natives.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("linux/x64/org/lwjgl/glfw/libglfw.so"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/NOTICE"))
            zip.write("skip".toByteArray())
            zip.closeEntry()
        }
        val dest = File(root, "out").apply { mkdirs() }
        val names = extractNativeJar(jar, dest, listOf("META-INF/"))
        assertEquals(listOf("libglfw.so"), names)
        assertTrue(File(dest, "libglfw.so").isFile)
        assertFalse(File(dest, "MANIFEST.MF").exists())

        val java = File(root, "bin/java").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        installLaunchFixture(root)
        val plan = GameLauncher().plan(
            LaunchRequest(
                gameDir = root,
                versionId = "1.20.1",
                account = LaunchAccount("Steve", "5627dd98-e6be-3c21-b8a8-e92344183641", "token-value-12345678", "legacy", null, "离线"),
                javaBinary = java,
                expectedJavaPath = java.path,
                nativesDirectory = File(root, "natives").apply { mkdirs() },
                authlibInjectorJar = null,
                platform = "linux",
            ),
        )
        val command = plan.command.joinToString("\n")
        assertTrue(command.contains("shared-1.jar"))
        assertFalse(command.contains("natives-linux.jar"))
    }

    @Test
    fun javaSelectionUsesTheRequiredMajor() {
        assertEquals(8, JavaRuntimes.parseReleaseMajor("JAVA_VERSION=\"1.8.0_512\"\n"))
        assertEquals(17, JavaRuntimes.parseReleaseMajor("JAVA_VERSION=\"17.0.21\"\n"))
        assertEquals(21, JavaRuntimes.majorFromVersion("21.0.12"))
        assertEquals(8, JavaRuntimes.fallbackMajor("1.7.10"))
        assertEquals(8, JavaRuntimes.fallbackMajor("1.16.5"))
        assertEquals(17, JavaRuntimes.fallbackMajor("1.18.2"))
        assertEquals(17, JavaRuntimes.fallbackMajor("1.20.4"))
        assertEquals(21, JavaRuntimes.fallbackMajor("1.20.5"))
        assertEquals(21, JavaRuntimes.fallbackMajor("1.21.1"))
        assertEquals(8, JavaRuntimes.requiredMajor("1.7.10", 8))

        val java8 = JavaInstallation(8, File("/opt/java8/bin/java"), File("/opt/java8"))
        val java17 = JavaInstallation(17, File("/opt/java17/bin/java"), File("/opt/java17"))
        val java21 = JavaInstallation(21, File("/opt/java21/bin/java"), File("/opt/java21"))
        val all = listOf(java8, java17, java21)
        assertEquals(17, JavaRuntimes.select(17, all)?.major)
        assertEquals(8, JavaRuntimes.select(8, all)?.major)
        assertEquals(21, JavaRuntimes.select(21, all)?.major)
        assertEquals(null, JavaRuntimes.select(8, listOf(java17, java21)))
        val message = JavaRuntimes.missingMessage("1.7.10", 8, listOf(java17))
        assertTrue(message.contains("没有找到可用的 Java 8"))
        assertTrue(message.contains("1.7.10"))
        assertTrue(message.contains("/opt/java17/bin/java"))

        val home = Files.createTempDirectory("fcp-jdk").toFile()
        File(home, "release").writeText("JAVA_VERSION=\"17.0.9\"\n")
        val binary = File(home, "bin/java").apply {
            parentFile.mkdirs()
            writeText("not really java")
        }
        val found = JavaRuntimes.detect(listOf(home))
        assertTrue(found.any { it.major == 17 && it.javaBinary.canonicalFile == binary.canonicalFile })
    }

    @Test
    fun officialOnlyMirrorDropsBmclapi() {
        val mirrors = DownloadMirrors(bmclFirst = false)
        assertTrue(mirrors.versionManifestUrls().none { "bmclapi" in it })
        assertEquals(
            listOf("https://libraries.minecraft.net/a.jar"),
            mirrors.candidatesFor("https://libraries.minecraft.net/a.jar"),
        )
        assertTrue(DownloadMirrors().versionManifestUrls().first().contains("bmclapi2.bangbang93.com"))
    }
}

private fun nativeJson() = """
    {
      "id": "sample",
      "libraries": [
        {"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.1",
         "natives": {"linux": "natives-linux", "osx": "natives-osx", "windows": "natives-windows"},
         "extract": {"exclude": ["META-INF/"]},
         "downloads": {
           "artifact": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1.jar", "url": "https://libraries.minecraft.net/platform.jar"},
           "classifiers": {
             "natives-linux": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1-natives-linux.jar", "url": "https://libraries.minecraft.net/linux.jar", "sha1": "abc"},
             "natives-osx": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1-natives-osx.jar", "url": "https://libraries.minecraft.net/osx.jar"},
             "natives-windows": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1-natives-windows.jar", "url": "https://libraries.minecraft.net/win.jar"}
           }
         }},
        {"name": "net.java.jinput:jinput-platform:2.0.5",
         "natives": {"linux": "natives-linux"},
         "downloads": {"classifiers": {
           "natives-linux": {"path": "net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives-linux.jar", "url": "https://libraries.minecraft.net/jinput.jar"}
         }}},
        {"name": "tv.twitch:twitch-platform:5.16",
         "natives": {"linux": "natives-linux", "windows": "natives-windows-${'$'}{arch}"},
         "rules": [{"action": "allow"}, {"action": "disallow", "os": {"name": "linux"}}],
         "downloads": {"classifiers": {
           "natives-windows-64": {"path": "tv/twitch/twitch-platform/5.16/twitch-platform-5.16-natives-windows-64.jar", "url": "https://libraries.minecraft.net/twitch.jar"}
         }}},
        {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-linux",
         "rules": [{"action": "allow", "os": {"name": "linux"}}],
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-linux.jar", "url": "https://libraries.minecraft.net/glfw-linux.jar"}}},
        {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-linux-arm64",
         "rules": [{"action": "allow", "os": {"name": "linux", "arch": "arm64"}}],
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-linux-arm64.jar", "url": "https://libraries.minecraft.net/glfw-arm.jar"}}},
        {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-macos",
         "rules": [{"action": "allow", "os": {"name": "osx"}}],
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-macos.jar", "url": "https://libraries.minecraft.net/glfw-mac.jar"}}},
        {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-macos-arm64",
         "rules": [{"action": "allow", "os": {"name": "osx"}}],
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-macos-arm64.jar", "url": "https://libraries.minecraft.net/glfw-mac-arm.jar"}}},
        {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-windows",
         "rules": [{"action": "allow", "os": {"name": "windows"}}],
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-windows.jar", "url": "https://libraries.minecraft.net/glfw-win.jar"}}},
        {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-windows-x86",
         "rules": [{"action": "allow", "os": {"name": "windows"}}],
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-windows-x86.jar", "url": "https://libraries.minecraft.net/glfw-win32.jar"}}}
      ]
    }
""".trimIndent()

private fun installLaunchFixture(root: File) {
    val json = """
        {
          "id": "1.20.1",
          "mainClass": "net.minecraft.client.main.Main",
          "assetIndex": {"id": "5", "url": "https://example.invalid/index"},
          "javaVersion": {"majorVersion": 17},
          "libraries": [
            {"name": "com.example:shared:1", "downloads": {"artifact": {
              "path": "com/example/shared/1/shared-1.jar",
              "url": "https://example.invalid/shared.jar"
            }}},
            {"name": "org.lwjgl:lwjgl-glfw:3.3.1:natives-linux",
             "rules": [{"action": "allow", "os": {"name": "linux"}}],
             "downloads": {"artifact": {
               "path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-linux.jar",
               "url": "https://example.invalid/natives.jar"
             }}}
          ],
          "arguments": {"game": ["--username", "${'$'}{auth_player_name}"], "jvm": ["-cp", "${'$'}{classpath}"]}
        }
    """.trimIndent()
    GamePaths.versionJson(root, "1.20.1").apply {
        parentFile.mkdirs()
        writeText(json)
    }
    GamePaths.clientJar(root, "1.20.1").writeBytes(byteArrayOf(1))
    GamePaths.library(root, "com/example/shared/1/shared-1.jar").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(1))
    }
    GamePaths.library(root, "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-linux.jar").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(1))
    }
    GamePaths.assetIndex(root, "5").apply {
        parentFile.mkdirs()
        writeText("""{"objects":{"icons/icon.png":{"hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1}}}""")
    }
    GamePaths.assetObject(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(1))
    }
}
