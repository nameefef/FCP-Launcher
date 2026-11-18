// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import com.google.gson.JsonParser
import fcp.core.download.mavenRelativePath
import fcp.shared.PRODUCT_NAME
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Matches `${name}` in a Minecraft argument template.
 * The closing brace is escaped so the same pattern compiles on Android ICU and on a desktop JVM.
 */
internal const val LAUNCH_PLACEHOLDER_PATTERN = "\\$\\{([^}]+)\\}"

class GameLauncher {
    fun plan(request: LaunchRequest): LaunchPlan {
        val problems = mutableListOf<String>()
        val version = try {
            loadResolvedVersion(request.gameDir, request.versionId)
        } catch (e: LaunchException) {
            throw e
        }
        val javaMajor = version.javaMajor ?: 8
        collectFiles(request, version, problems)
        collectAccount(request, problems)
        collectRuntime(request, javaMajor, problems)
        if (problems.isNotEmpty()) {
            throw LaunchException(problems.joinToString("\n\n"))
        }

        val classpath = classpathFiles(request, version).joinToString(File.pathSeparator) { it.absolutePath }
        val vars = variables(request, version, classpath)
        val jvm = jvmArguments(version, request.platform, vars).toMutableList()
        if (jvm.none { it == "-cp" || it == "-classpath" }) {
            jvm += "-cp"
            jvm += classpath
        }
        if (jvm.none { it.startsWith("-Djava.library.path=") }) {
            jvm.add(0, substitute("-Djava.library.path=\${natives_directory}", vars))
        }
        if (request.lwjglOverride != null && jvm.none { it.startsWith("-Dorg.lwjgl.librarypath=") }) {
            jvm.add(0, "-Dorg.lwjgl.librarypath=${request.nativesDirectory.absolutePath}")
        }
        val game = gameArguments(version, request.platform, vars)
        if (game.isEmpty()) {
            throw LaunchException("版本 JSON 没有游戏参数，无法启动 ${request.versionId}。")
        }
        val mainClass = version.mainClass
            ?: throw LaunchException("版本 JSON 没有 mainClass，无法启动 ${request.versionId}。")
        val javaBinary = request.javaBinary
            ?: throw LaunchException("没有找到 Java $javaMajor。")

        val command = mutableListOf<String>()
        command += javaBinary.absolutePath
        authlibAgent(request)?.let { command += it }
        if (command.none { it.startsWith("-Xmx") } && jvm.none { it.startsWith("-Xmx") }) {
            val xms = minOf(512, maxOf(256, request.maxMemoryMb / 2))
            command += "-Xms${xms}M"
            command += "-Xmx${request.maxMemoryMb}M"
        }
        command += jvm
        command += request.extraJvmArgs
        command += mainClass
        command += game

        return LaunchPlan(
            versionId = version.id,
            accountLabel = "${request.account.name}（${request.account.kindLabel}）",
            command = command,
            workingDirectory = request.gameDir,
            environment = request.environment.toMap(),
            secrets = listOf(request.account.accessToken),
        )
    }

    /** Redacted header shared by a desktop process and the Android in-process launch. */
    fun describe(plan: LaunchPlan): String = buildString {
        appendLine(PRODUCT_NAME)
        appendLine(OffsetDateTime.now().toString())
        appendLine("version: ${plan.versionId}")
        appendLine("account: ${plan.accountLabel}")
        appendLine("workingDirectory: ${plan.workingDirectory.absolutePath}")
        appendLine("environment:")
        plan.environment.toSortedMap().forEach { (key, value) ->
            appendLine("$key=${redactSecrets(value, plan.secrets)}")
        }
        appendLine("command:")
        plan.command.forEach { appendLine(redactSecrets(it, plan.secrets)) }
    }

    fun start(plan: LaunchPlan, logFile: File): RunningGame {
        LaunchLog.write(logFile, describe(plan))
        plan.workingDirectory.mkdirs()
        val process = try {
            ProcessBuilder(plan.command)
                .directory(plan.workingDirectory)
                .apply {
                    val env = environment()
                    plan.environment.forEach { (key, value) -> env[key] = value }
                }
                .start()
        } catch (e: Exception) {
            val message = "无法启动游戏进程：${e.message}\n日志：${logFile.absolutePath}"
            LaunchLog.append(logFile, message + "\n")
            throw LaunchException(message, e)
        }
        val pumps = listOf(
            pump(process.inputStream, logFile, "out"),
            pump(process.errorStream, logFile, "err"),
        )
        return RunningGame(process, logFile, pumps)
    }

    private fun collectFiles(request: LaunchRequest, version: ResolvedVersion, problems: MutableList<String>) {
        val client = clientJar(request, version)
        if (client == null) {
            val expected = GamePaths.clientJar(request.gameDir, request.versionId).path
            problems += "没有找到客户端 $expected。请先在版本页下载 ${request.versionId}。"
        }
        val missingLibs = missingLibraries(request, version)
        if (missingLibs.isNotEmpty()) {
            val preview = missingLibs.take(5).joinToString("\n") { "- $it" }
            problems += "依赖库不完整，无法启动。缺少 ${missingLibs.size} 个文件。\n$preview"
        }
        val indexId = version.assetIndexId
        if (indexId.isNullOrBlank()) {
            problems += "版本 JSON 没有资源索引（assetIndex），无法启动。"
        } else {
            assetProblem(request.gameDir, indexId, request.versionId)?.let { problems += it }
        }
        if (version.mainClass.isNullOrBlank()) {
            problems += "版本 JSON 没有 mainClass，无法启动 ${request.versionId}。"
        }
        if (!request.nativesDirectory.isDirectory) {
            problems += "缺少 natives 目录：${request.nativesDirectory.path}"
        }
    }

    private fun collectAccount(request: LaunchRequest, problems: MutableList<String>) {
        if (request.account.name.isBlank()) {
            problems += "账号没有角色名，无法启动。"
        }
        if (request.account.accessToken.isBlank()) {
            problems += "账号没有访问令牌，无法启动。"
        }
        if (request.account.uuid.isBlank()) {
            problems += "账号没有 UUID，无法启动。"
        }
        val api = request.account.authlibApiRoot
        if (api != null) {
            val jar = request.authlibInjectorJar
            if (jar == null || !jar.isFile) {
                val where = jar?.path ?: "(未指定路径)"
                val download = request.authlibDownloadError?.let { "\n下载失败：$it" }.orEmpty()
                problems += "这个${request.account.kindLabel}账号需要 authlib-injector 才能启动，但还没有这个 jar。\n期望路径：$where$download"
            }
        }
    }

    private fun collectRuntime(request: LaunchRequest, javaMajor: Int, problems: MutableList<String>) {
        val java = request.javaBinary
        if (java == null || !java.isFile) {
            val download = request.runtimeDownloadError?.let { "\n$it" }.orEmpty()
            problems += """
                没有找到可用的 Java $javaMajor，无法启动 ${request.versionId}。
                期望的路径：${request.expectedJavaPath}
                同一 runtime 目录下的 jre-$javaMajor、Internal-$javaMajor 也可以。
                Android JRE 会在首次启动时下载；这次没有得到可用的 java。$download
            """.trimIndent()
        } else if (request.requireExecutableJava && !java.canExecute()) {
            problems += "找到了 Java，但没有执行权限：${java.path}"
        }
        if (request.checkNativeRuntime) {
            AndroidNativeRuntime.problem(request.presentNativeLibraries, request.nativeSearchPaths)
                ?.let { problems += it }
        }
    }

    private fun clientJar(request: LaunchRequest, version: ResolvedVersion): File? {
        val ids = listOf(version.id, request.versionId).distinct()
        return ids.map { GamePaths.clientJar(request.gameDir, it) }.firstOrNull { it.isFile }
    }

    private fun classpathFiles(request: LaunchRequest, version: ResolvedVersion): List<File> {
        val files = mutableListOf<File>()
        request.lwjglOverride?.let { files += lwjglClasspathJars(it) }
        for (library in version.libraries) {
            if (!library.includeFor(request.platform)) continue
            if (isNativeLibraryCoordinate(library.name)) continue
            if (request.lwjglOverride != null && replacedByFclLwjgl(library.name)) continue
            val file = libraryFile(request.gameDir, library) ?: continue
            if (file.isFile) files += file
        }
        clientJar(request, version)?.let { files += it }
        return files
    }

    private fun missingLibraries(request: LaunchRequest, version: ResolvedVersion): List<String> {
        val missing = mutableListOf<String>()
        for (library in version.libraries) {
            if (!library.includeFor(request.platform)) continue
            if (request.lwjglOverride != null && replacedByFclLwjgl(library.name)) continue
            val file = libraryFile(request.gameDir, library) ?: continue
            if (!file.isFile) missing += file.relativeTo(request.gameDir).path
        }
        request.lwjglOverride?.let { override ->
            if (!File(override.directory, "lwjgl.jar").isFile) {
                missing += "runtime/${override.directory.name}/lwjgl.jar"
            }
            if (override.includeCompatibilityLayer && !File(override.directory, "lwjgl-lwjglx.jar").isFile) {
                missing += "runtime/${override.directory.name}/lwjgl-lwjglx.jar"
            }
        }
        return missing
    }

    private fun libraryFile(gameDir: File, library: fcp.core.download.LibrarySpec): File? {
        val artifact = library.artifact ?: return null
        val relative = artifact.path ?: mavenRelativePath(library.name)
        return GamePaths.library(gameDir, relative)
    }

    private fun assetProblem(gameDir: File, indexId: String, versionId: String): String? {
        val index = GamePaths.assetIndex(gameDir, indexId)
        if (!index.isFile) {
            return "资源不完整，无法启动。缺少资源索引 ${index.relativeTo(gameDir).path}。请重新下载 $versionId。"
        }
        val objects = try {
            val root = JsonParser.parseString(index.readText()).asJsonObject
            root.getAsJsonObject("objects")
        } catch (e: Exception) {
            return "资源索引无法解析：${e.message}"
        } ?: return null
        var total = 0
        var missing = 0
        val examples = mutableListOf<String>()
        for ((name, value) in objects.entrySet()) {
            total += 1
            val hash = value.asJsonObject.get("hash")?.asString ?: continue
            if (!GamePaths.assetObject(gameDir, hash).isFile) {
                missing += 1
                if (examples.size < 5) examples += name
            }
        }
        if (missing == 0) return null
        val preview = examples.joinToString("\n") { "- $it" }
        return "资源不完整，无法启动。索引 $indexId 共 $total 个文件，还缺 $missing 个。\n$preview\n请重新下载 $versionId。"
    }

    private fun variables(request: LaunchRequest, version: ResolvedVersion, classpath: String): Map<String, String> {
        val assets = File(request.gameDir, "assets").absolutePath
        return mapOf(
            "auth_player_name" to request.account.name,
            "version_name" to version.id,
            "game_directory" to request.gameDir.absolutePath,
            "assets_root" to assets,
            "game_assets" to assets,
            "assets_index_name" to version.assetIndexId.orEmpty(),
            "auth_uuid" to request.account.uuid.filterNot { it == '-' },
            "auth_access_token" to request.account.accessToken,
            "auth_session" to request.account.accessToken,
            "user_type" to request.account.userType,
            "user_properties" to "{}",
            "version_type" to request.launcherName,
            "natives_directory" to request.nativesDirectory.absolutePath,
            "launcher_name" to request.launcherName,
            "launcher_version" to request.launcherVersion,
            "classpath" to classpath,
            "library_directory" to File(request.gameDir, "libraries").absolutePath,
            "classpath_separator" to File.pathSeparator,
            "clientid" to "",
            "auth_xuid" to "",
        )
    }

    private fun jvmArguments(version: ResolvedVersion, platform: String, vars: Map<String, String>): List<String> {
        val source = version.jvmArguments.ifEmpty { defaultJvmArguments() }
        return expand(source, platform, vars)
    }

    private fun gameArguments(version: ResolvedVersion, platform: String, vars: Map<String, String>): List<String> {
        if (version.gameArguments.isNotEmpty()) return expand(version.gameArguments, platform, vars)
        val legacy = version.legacyMinecraftArguments ?: return emptyList()
        return legacy.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { substitute(it, vars) }
    }

    private fun expand(args: List<ArgumentSpec>, platform: String, vars: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        for (arg in args) {
            if (!arg.rules.allows(platform)) continue
            for (value in arg.values) out += substitute(value, vars)
        }
        return out
    }

    private fun authlibAgent(request: LaunchRequest): String? {
        val api = request.account.authlibApiRoot ?: return null
        val jar = request.authlibInjectorJar ?: return null
        return "-javaagent:${jar.absolutePath}=$api"
    }

    private fun defaultJvmArguments(): List<ArgumentSpec> = listOf(
        ArgumentSpec(listOf("-Djava.library.path=\${natives_directory}"), emptyList()),
        ArgumentSpec(listOf("-Djna.tmpdir=\${natives_directory}"), emptyList()),
        ArgumentSpec(listOf("-Dorg.lwjgl.system.SharedLibraryExtractPath=\${natives_directory}"), emptyList()),
        ArgumentSpec(listOf("-Dio.netty.native.workdir=\${natives_directory}"), emptyList()),
        ArgumentSpec(listOf("-Dminecraft.launcher.brand=\${launcher_name}"), emptyList()),
        ArgumentSpec(listOf("-Dminecraft.launcher.version=\${launcher_version}"), emptyList()),
        ArgumentSpec(listOf("-cp"), emptyList()),
        ArgumentSpec(listOf("\${classpath}"), emptyList()),
    )

    private fun substitute(template: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match -> vars[match.groupValues[1]] ?: match.value }

    private fun pump(stream: InputStream, logFile: File, label: String): Thread =
        Thread {
            try {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> LaunchLog.append(logFile, "[$label] $line\n") }
                }
            } catch (_: Exception) {
                // The process closing the pipe ends the reader.
            }
        }.apply {
            isDaemon = true
            name = "fcp-launch-$label"
            start()
        }

    private companion object {
        // Android ICU (kotlin.text.Regex on device) rejects an unescaped '}'
        // that closes `${...}`. java.util.regex on the desktop JVM accepts it,
        // so unit tests must keep the escaped form below.
        val PLACEHOLDER = Regex(LAUNCH_PLACEHOLDER_PATTERN)
    }
}

class RunningGame(
    val process: Process,
    val logFile: File,
    private val pumps: List<Thread>,
) {
    fun exitCodeOrNull(): Int? = if (process.isAlive) null else process.exitValue()

    fun awaitExit(timeoutMs: Long? = null): Int {
        if (timeoutMs == null) {
            val code = process.waitFor()
            pumps.forEach { it.join(2_000) }
            return code
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            pumps.forEach { it.join(1_000) }
            throw LaunchException("游戏进程超时未退出")
        }
        pumps.forEach { it.join(2_000) }
        return process.exitValue()
    }
}
