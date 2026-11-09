// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.launch.ApkNativeLibraries
import fcp.core.launch.LaunchException
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ApkNativeLibrariesTest {
    @Test
    fun extractsFirstMatchingAbiAndSkipsLibsAlreadyInstalled() {
        val root = Files.createTempDirectory("fcp-apk-natives").toFile()
        val apk = File(root, "app.apk")
        zipOf(
            apk,
            mapOf(
                "lib/x86_64/libglfw.so" to "x64-glfw".toByteArray(),
                "lib/arm64-v8a/libglfw.so" to "arm-glfw".toByteArray(),
                "lib/arm64-v8a/libopenal.so" to "arm-al".toByteArray(),
                "lib/x86_64/libfcl.so" to "fcl".toByteArray(),
                "lib/x86_64/readme.txt" to "ignore".toByteArray(),
                "assets/libglfw.so" to "decoy".toByteArray(),
            ),
        )
        val installed = File(root, "nativeLibraryDir").apply { mkdirs() }
        File(installed, "libgl4es_114.so").writeBytes("installed-gl".toByteArray())
        val destination = File(root, "natives").apply { mkdirs() }
        File(destination, "libbytehook.so").writeBytes("kept".toByteArray())

        val extracted = ApkNativeLibraries.ensureExtracted(
            apk = apk,
            abis = listOf("x86_64", "arm64-v8a"),
            names = listOf(
                "libglfw.so",
                "libopenal.so",
                "libgl4es_114.so",
                "libbytehook.so",
                "libfcl.so",
                "libmissing.so",
                "../escape.so",
                "libglfw.so.so",
            ),
            nativeLibraryDir = installed,
            destination = destination,
        )

        assertEquals(setOf("libglfw.so", "libopenal.so", "libfcl.so"), extracted)
        assertEquals("x64-glfw", File(destination, "libglfw.so").readText())
        assertEquals("arm-al", File(destination, "libopenal.so").readText())
        assertEquals("fcl", File(destination, "libfcl.so").readText())
        assertEquals("kept", File(destination, "libbytehook.so").readText())
        assertFalse(File(destination, "libgl4es_114.so").exists())
        assertFalse(File(destination, "libmissing.so").exists())
        assertFalse(File(root, "escape.so").exists())
        assertFalse(File(root.parentFile, "escape.so").exists())
        assertEquals("installed-gl", File(installed, "libgl4es_114.so").readText())
    }

    @Test
    fun keepsAnExtractedCopyWhenTheZipEntrySizeMatches() {
        val root = Files.createTempDirectory("fcp-apk-natives-keep").toFile()
        val apk = File(root, "app.apk")
        zipOf(apk, mapOf("lib/x86_64/libglfw.so" to "fresh".toByteArray()))
        val destination = File(root, "natives").apply { mkdirs() }
        File(destination, "libglfw.so").writeBytes("fresh".toByteArray())

        val extracted = ApkNativeLibraries.ensureExtracted(
            apk = apk,
            abis = listOf("x86_64"),
            names = listOf("libglfw.so"),
            nativeLibraryDir = File(root, "missing-native-dir"),
            destination = destination,
        )

        assertTrue(extracted.isEmpty())
        assertEquals("fresh", File(destination, "libglfw.so").readText())
    }

    @Test
    fun replacesAStaleExtractWhenTheApkEntryChanged() {
        val root = Files.createTempDirectory("fcp-apk-natives-refresh").toFile()
        val apk = File(root, "app.apk")
        zipOf(apk, mapOf("lib/x86_64/libopenal.so" to "new-al".toByteArray()))
        val destination = File(root, "natives").apply { mkdirs() }
        File(destination, "libopenal.so").writeBytes("old".toByteArray())

        val extracted = ApkNativeLibraries.ensureExtracted(
            apk = apk,
            abis = listOf("x86_64"),
            names = listOf("libopenal.so"),
            nativeLibraryDir = File(root, "empty"),
            destination = destination,
        )

        assertEquals(setOf("libopenal.so"), extracted)
        assertEquals("new-al", File(destination, "libopenal.so").readText())
        assertFalse(File(destination, ".libopenal.so.partial").exists())
    }

    @Test
    fun missingApkDoesNotThrowWhenNothingCanBeExtracted() {
        val root = Files.createTempDirectory("fcp-apk-natives-absent").toFile()
        val extracted = ApkNativeLibraries.ensureExtracted(
            apk = File(root, "missing.apk"),
            abis = listOf("x86_64"),
            names = listOf("libglfw.so"),
            nativeLibraryDir = File(root, "native"),
            destination = File(root, "natives"),
        )
        assertTrue(extracted.isEmpty())
        assertTrue(File(root, "natives").isDirectory)
    }

    @Test
    fun corruptApkIsALaunchException() {
        val root = Files.createTempDirectory("fcp-apk-natives-bad").toFile()
        val apk = File(root, "broken.apk").apply { writeText("not a zip") }
        val error = kotlin.test.assertFailsWith<LaunchException> {
            ApkNativeLibraries.ensureExtracted(
                apk = apk,
                abis = listOf("x86_64"),
                names = listOf("libglfw.so"),
                nativeLibraryDir = File(root, "native"),
                destination = File(root, "natives"),
            )
        }
        assertTrue(error.message!!.contains("原生库"))
    }

    private fun zipOf(dest: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(dest.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
