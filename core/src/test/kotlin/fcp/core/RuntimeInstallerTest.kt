// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.download.GameRepository
import fcp.core.download.sha1Hex
import fcp.core.download.sha256Hex
import fcp.core.launch.LaunchException
import fcp.core.launch.extractFclApk
import fcp.core.launch.loadBundledRuntimeCatalog
import fcp.core.launch.parseRuntimeCatalog
import fcp.core.launch.writeTarXz
import fcp.core.net.HttpClient
import fcp.core.net.HttpRequest
import fcp.core.net.HttpResponse
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RuntimeInstallerTest {
    @Test
    fun bundledCatalogHasJava17ForEveryAbiAndChecksums() {
        val catalog = loadBundledRuntimeCatalog()
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        for (abi in abis) {
            val jre17 = catalog.find(17, abi)
            assertTrue(jre17 != null, abi)
            assertEquals("tar.xz", jre17!!.format)
            assertTrue(jre17.url.contains("android-openjdk-build-multiarch"))
            assertEquals(64, jre17.sha256.length)
            val jre8 = catalog.find(8, abi)
            assertTrue(jre8 != null, "java 8 $abi")
            assertEquals("fcl-apk", jre8!!.format)
            assertTrue(jre8.entries.any { it.contains("jre8") && it.endsWith("universal.tar.xz") })
            assertTrue(jre8.entries.any { it.contains("jre8/bin-") })
            val jre21 = catalog.find(21, abi)
            assertTrue(jre21 != null, "java 21 $abi")
            assertEquals("fcl-apk", jre21!!.format)
        }
        assertFailsWith<LaunchException> {
            parseRuntimeCatalog("""{"jre":[{"major":17,"abi":"x86_64","url":"http://insecure","sha256":"abc"}]}""")
        }
    }

    @Test
    fun fclApkEntriesUnpackToJavaBinary() {
        val root = Files.createTempDirectory("fcp-rt").toFile()
        val universal = File(root, "universal.tar.xz")
        val binary = File(root, "bin.tar.xz")
        writeTarXz(universal, mapOf("lib/libjli.so" to byteArrayOf(1, 2, 3)))
        writeTarXz(binary, mapOf("bin/java" to "echo java".toByteArray()))
        val apk = File(root, "fcl.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/app_runtime/java/jre8/universal.tar.xz"))
            zip.write(universal.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/app_runtime/java/jre8/bin-x86_64.tar.xz"))
            zip.write(binary.readBytes())
            zip.closeEntry()
        }
        val dest = File(root, "java-8")
        extractFclApk(
            apk,
            listOf(
                "assets/app_runtime/java/jre8/universal.tar.xz",
                "assets/app_runtime/java/jre8/bin-x86_64.tar.xz",
            ),
            dest,
        )
        val java = File(dest, "bin/java")
        assertTrue(java.isFile)
        assertTrue(java.canExecute())
        assertTrue(File(dest, "lib/libjli.so").isFile)
        assertEquals("echo java", java.readText())
    }

    @Test
    fun installerRejectsABadChecksum() {
        val root = Files.createTempDirectory("fcp-rt-bad").toFile()
        val archive = File(root, "jre.tar.xz")
        writeTarXz(archive, mapOf("bin/java" to byteArrayOf(9)))
        val sha = sha256Hex(archive)
        val catalog = parseRuntimeCatalog(
            """
            {"jre":[{
              "major":17,"abi":"x86_64",
              "url":"https://example.invalid/jre.tar.xz",
              "sha256":"${"0".repeat(64)}",
              "format":"tar.xz",
              "entries":[],
              "source":"test"
            }]}
            """.trimIndent(),
        )
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = error("unused")
            override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
                archive.copyTo(dest, overwrite = true)
                onProgress(dest.length(), dest.length())
            }
        }
        val error = assertFailsWith<LaunchException> {
            fcp.core.launch.RuntimeInstaller().ensureJava(catalog, http, "x86_64", 17, root)
        }
        assertTrue(error.message!!.contains("校验和"))
        assertTrue(sha.length == 64)
    }

    @Test
    fun relaunchDownloadsOnlyTheMissingAssetObjects() {
        val root = Files.createTempDirectory("fcp-repair").toFile()
        val present = "aa".repeat(20)
        val presentBytes = byteArrayOf(7)
        val missingBytes = "zh_CN".toByteArray()
        val missing = sha1Hex(missingBytes)
        val indexJson = """{"objects":{
          "realms/lang/en_US.lang":{"hash":"$present","size":${presentBytes.size}},
          "realms/lang/zh_CN.lang":{"hash":"$missing","size":${missingBytes.size}}
        }}"""
        val indexSha = sha256Hex(indexJson.toByteArray())
        val versionJson = """
            {
              "id":"1.7.10",
              "assetIndex":{"id":"1.7.10","sha1":"$indexSha","size":${indexJson.toByteArray().size},
                "url":"https://piston-meta.mojang.com/index.json"},
              "libraries":[]
            }
        """.trimIndent()
        val versionDir = File(root, "versions/1.7.10")
        versionDir.mkdirs()
        File(versionDir, "1.7.10.json").writeText(versionJson)
        val indexFile = File(root, "assets/indexes/1.7.10.json")
        indexFile.parentFile.mkdirs()
        indexFile.writeText(indexJson)
        val have = File(root, "assets/objects/${present.take(2)}/$present")
        have.parentFile.mkdirs()
        have.writeBytes(presentBytes)
        val seen = mutableListOf<String>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = error("unused")
            override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
                seen += url
                dest.parentFile?.mkdirs()
                dest.writeBytes(missingBytes)
                onProgress(missingBytes.size.toLong(), missingBytes.size.toLong())
            }
        }
        GameRepository(http).completeInstallation("1.7.10", root)
        assertEquals(listOf("https://bmclapi2.bangbang93.com/assets/${missing.take(2)}/$missing"), seen)
        assertTrue(File(root, "assets/objects/${missing.take(2)}/$missing").isFile)
        assertEquals(presentBytes.toList(), have.readBytes().toList())
    }
}
