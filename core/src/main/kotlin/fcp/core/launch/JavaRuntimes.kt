// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import java.io.File
import java.util.concurrent.TimeUnit

data class JavaInstallation(
    val major: Int,
    val javaBinary: File,
    val home: File,
)

/**
 * Finds system JDKs and picks one whose major matches the version JSON
 * (`javaVersion.majorVersion`). Java 8 is required for 1.16 and older.
 * 1.17 accepts 16 or 17. 1.18 through 1.20.4 need 17. Newer versions need 21.
 * A path from settings is one more candidate, preferred when its major matches.
 */
object JavaRuntimes {
    fun requiredMajor(versionId: String, jsonMajor: Int?): Int = jsonMajor ?: fallbackMajor(versionId)

    fun fallbackMajor(versionId: String): Int {
        val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""").find(versionId) ?: return 8
        val minor = match.groupValues[2].toIntOrNull() ?: return 8
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        val release = match.groupValues[1].toIntOrNull() ?: return 8
        if (release >= 2) return 21
        if (minor >= 21) return 21
        if (minor == 20 && patch >= 5) return 21
        if (minor >= 18) return 17
        if (minor == 17) return 16
        return 8
    }

    fun acceptableMajors(required: Int): Set<Int> = when {
        required <= 8 -> setOf(8)
        required == 16 -> setOf(16, 17)
        else -> setOf(required)
    }

    fun detect(extra: List<File> = emptyList()): List<JavaInstallation> {
        val candidates = mutableListOf<File>()
        extra.forEach { addCandidate(candidates, it) }
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { addCandidate(candidates, File(it)) }
        addCandidate(candidates, File(System.getProperty("java.home")))
        val pathName = if (isWindows()) "java.exe" else "java"
        for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val binary = File(dir, pathName)
            if (binary.isFile) candidates += binary
        }
        for (root in scanRoots()) {
            if (!root.isDirectory) continue
            addCandidate(candidates, root)
            root.listFiles()?.forEach { child -> addCandidate(candidates, child) }
        }
        val seen = mutableSetOf<String>()
        val found = mutableListOf<JavaInstallation>()
        for (binary in candidates) {
            val canon = runCatching { binary.canonicalFile }.getOrNull() ?: continue
            if (!canon.isFile || !seen.add(canon.path)) continue
            val major = probeMajor(canon) ?: continue
            val home = canon.parentFile?.parentFile ?: continue
            found += JavaInstallation(major, canon, home)
        }
        return found.sortedWith(compareBy({ it.major }, { it.javaBinary.path }))
    }

    fun select(requiredMajor: Int, detected: List<JavaInstallation>, preferred: File? = null): JavaInstallation? {
        val acceptable = acceptableMajors(requiredMajor)
        val matches = detected.filter { it.major in acceptable }
        if (matches.isEmpty()) return null
        val preferredCanon = preferred?.let { canonicalizePreferred(it) }
        if (preferredCanon != null) {
            matches.firstOrNull { it.javaBinary.canonicalFile == preferredCanon }?.let { return it }
        }
        return matches.sortedWith(
            compareByDescending<JavaInstallation> { it.major == requiredMajor }.thenByDescending { it.major },
        ).first()
    }

    fun missingMessage(versionId: String, requiredMajor: Int, detected: List<JavaInstallation>, configuredPath: String = ""): String {
        val found = if (detected.isEmpty()) {
            "没有检测到已安装的 Java。"
        } else {
            "已检测到：" + detected.joinToString("、") { "Java ${it.major}（${it.javaBinary.path}）" }
        }
        val configured = configuredPath.trim().takeIf { it.isNotEmpty() }?.let {
            "\n设置里的 Java 路径：$it"
        }.orEmpty()
        return """
            没有找到可用的 Java $requiredMajor，无法启动 $versionId。
            该版本需要 Java $requiredMajor。$found$configured
            请安装 Java $requiredMajor，或在「设置」中填写 java 可执行文件（或 JDK 目录）的路径。
        """.trimIndent()
    }

    fun parseReleaseMajor(text: String): Int? {
        val raw = Regex("""JAVA_VERSION\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: return null
        return majorFromVersion(raw)
    }

    fun parseJavaVersionOutput(text: String): Int? {
        val raw = Regex("""version\s+"([^"]+)"""").find(text)?.groupValues?.get(1) ?: return null
        return majorFromVersion(raw)
    }

    fun majorFromVersion(raw: String): Int? {
        val numbers = Regex("""\d+""").findAll(raw).map { it.value.toInt() }.toList()
        if (numbers.isEmpty()) return null
        return if (numbers[0] == 1 && numbers.size >= 2) numbers[1] else numbers[0]
    }

    private fun probeMajor(binary: File): Int? {
        val home = binary.parentFile?.parentFile
        val release = home?.let { File(it, "release") }
        if (release != null && release.isFile) {
            parseReleaseMajor(release.readText())?.let { return it }
        }
        if (!binary.canExecute()) return null
        return try {
            val process = ProcessBuilder(binary.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            parseJavaVersionOutput(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun canonicalizePreferred(file: File): File? {
        val binary = when {
            file.isFile -> file
            File(file, "bin/java").isFile -> File(file, "bin/java")
            File(file, "bin/java.exe").isFile -> File(file, "bin/java.exe")
            File(file, "Contents/Home/bin/java").isFile -> File(file, "Contents/Home/bin/java")
            else -> return null
        }
        return runCatching { binary.canonicalFile }.getOrNull()
    }

    private fun addCandidate(out: MutableList<File>, file: File) {
        if (!file.exists()) return
        val name = file.name.lowercase()
        if (file.isFile && (name == "java" || name == "java.exe")) {
            out += file
            return
        }
        if (!file.isDirectory) return
        listOf(
            File(file, "bin/java"),
            File(file, "bin/java.exe"),
            File(file, "Contents/Home/bin/java"),
        ).filter { it.isFile }.forEach { out += it }
    }

    private fun scanRoots(): List<File> {
        val home = File(System.getProperty("user.home"))
        val roots = mutableListOf<File>()
        if (isWindows()) {
            val programFiles = listOf(
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" },
            )
            for (base in programFiles) {
                if (base.isNullOrBlank()) continue
                roots += File(base, "Java")
                roots += File(base, "Eclipse Adoptium")
                roots += File(base, "Microsoft")
                roots += File(base, "Zulu")
                roots += File(base, "Amazon Corretto")
                roots += File(base, "BellSoft")
            }
        } else {
            roots += File("/usr/lib/jvm")
            roots += File("/usr/java")
            roots += File("/usr/local/java")
            roots += File("/opt/java")
            roots += File("/opt/jdk")
            roots += File("/Library/Java/JavaVirtualMachines")
            roots += File("/opt/homebrew/opt")
            roots += File("/usr/local/opt")
            roots += File(home, "Library/Java/JavaVirtualMachines")
            roots += File(home, ".sdkman/candidates/java")
            roots += File(home, ".jdks")
        }
        return roots
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
