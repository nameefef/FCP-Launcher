// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import fcp.core.auth.AccountStore
import fcp.core.download.GameRepository
import fcp.core.download.InstallOptions
import fcp.core.launch.AuthlibInjector
import fcp.core.launch.DesktopNatives
import fcp.core.launch.DesktopOs
import fcp.core.launch.GameLauncher
import fcp.core.launch.GamePaths
import fcp.core.launch.JavaRuntimes
import fcp.core.launch.LaunchException
import fcp.core.launch.LaunchLog
import fcp.core.launch.LaunchRequest
import fcp.core.launch.RunningGame
import fcp.core.launch.loadResolvedVersion
import fcp.core.launch.toLaunchAccount
import fcp.core.launch.usesLwjgl2
import fcp.core.net.UrlHttpClient
import fcp.shared.PRODUCT_NAME
import java.io.File

sealed class DesktopLaunchResult {
    data class Started(
        val versionId: String,
        val accountName: String,
        val javaPath: String,
        val logFile: File,
        val game: RunningGame,
    ) : DesktopLaunchResult()

    data class Failed(val message: String, val logFile: File) : DesktopLaunchResult()
}

/**
 * Starts Minecraft as its own process. Natives come from the version JSON
 * (LWJGL classifiers for this OS), not from the Android FCL runtime.
 */
class DesktopGameLaunch(
    private val settings: DesktopSettings,
    private val os: DesktopOs = DesktopOs.current(),
) {
    fun launch(versionId: String, onStatus: (String) -> Unit): DesktopLaunchResult {
        val gameDir = settings.gameDir()
        val log = GamePaths.logFile(gameDir)
        try {
            gameDir.mkdirs()
            val stored = AccountStore(DesktopSettings.accountsFile()).load().let { file ->
                file.accounts.firstOrNull { it.id == file.selectedId }
            } ?: return DesktopLaunchResult.Failed(
                tr(
                    "请先在「账号」里添加并选中一个账号，然后再启动。",
                    "Add an account and select it on the Accounts tab before launching.",
                ),
                log,
            )
            val http = UrlHttpClient()
            val repository = GameRepository(mirrors = settings.mirrors())
            onStatus(tr("正在补齐缺少的依赖库和资源…", "Filling in missing libraries and assets…"))
            repository.completeInstallation(
                versionId = versionId,
                gameDir = gameDir,
                platform = os.mojangName,
                listener = { event -> onStatus(event.label) },
                assetParallelism = 8,
            )
            val resolved = loadResolvedVersion(gameDir, versionId)
            val required = JavaRuntimes.requiredMajor(versionId, resolved.javaMajor)
            val configured = settings.javaPathFile()
            val detected = JavaRuntimes.detect(listOfNotNull(configured))
            val java = JavaRuntimes.select(required, detected, configured)
                ?: throw LaunchException(
                    JavaRuntimes.missingMessage(versionId, required, detected, settings.javaPath),
                )
            onStatus(
                tr(
                    "使用 Java ${java.major}：${java.javaBinary.path}",
                    "Using Java ${java.major}: ${java.javaBinary.path}",
                ),
            )
            onStatus(tr("正在准备桌面原生库…", "Preparing desktop natives…"))
            val natives = DesktopNatives.prepare(
                gameDir = gameDir,
                versionId = versionId,
                libraries = resolved.libraries,
                os = os,
                mirrors = settings.mirrors(),
                http = http,
                onStatus = onStatus,
            )
            val loggingArg = DesktopNatives.loggingArgument(
                versionJson = GamePaths.versionJson(gameDir, versionId).readText(),
                gameDir = gameDir,
                mirrors = settings.mirrors(),
                http = http,
            )
            val account = stored.toLaunchAccount()
            var authlib: File? = null
            if (account.authlibApiRoot != null) {
                val jar = File(DesktopSettings.configDir(), AuthlibInjector.FILE_NAME)
                onStatus(tr("正在下载 authlib-injector…", "Downloading authlib-injector…"))
                authlib = AuthlibInjector.ensure(jar, http)
            }
            ensureOptions(gameDir)
            val extra = mutableListOf("-Dfile.encoding=UTF-8")
            if (loggingArg != null) extra += loggingArg
            extra += "-Dorg.lwjgl.librarypath=${natives.absolutePath}"
            if (usesLwjgl2(resolved.libraries)) {
                extra += "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true"
            }
            val request = LaunchRequest(
                gameDir = gameDir,
                versionId = versionId,
                account = account,
                javaBinary = java.javaBinary,
                expectedJavaPath = java.javaBinary.absolutePath,
                nativesDirectory = natives,
                authlibInjectorJar = authlib,
                platform = os.mojangName,
                maxMemoryMb = settings.maxMemoryMb,
                launcherName = PRODUCT_NAME,
                launcherVersion = DesktopSettings.VERSION,
                extraJvmArgs = extra,
                environment = libraryPathEnvironment(natives),
                checkNativeRuntime = false,
                requireExecutableJava = true,
                lwjglOverride = null,
            )
            onStatus(tr("正在启动 $versionId…", "Starting $versionId…"))
            val plan = GameLauncher().plan(request)
            val running = GameLauncher().start(plan, log)
            return DesktopLaunchResult.Started(
                versionId = versionId,
                accountName = stored.profileName,
                javaPath = java.javaBinary.absolutePath,
                logFile = log,
                game = running,
            )
        } catch (e: LaunchException) {
            val message = e.message ?: e.javaClass.simpleName
            recordFailure(log, message)
            return DesktopLaunchResult.Failed(message, log)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            recordFailure(log, message)
            return DesktopLaunchResult.Failed(message, log)
        }
    }

    private fun recordFailure(log: File, message: String) {
        if (log.isFile && log.length() > 0) {
            LaunchLog.append(log, message + "\n")
        } else {
            LaunchLog.writeFailure(log, message)
        }
    }

    private fun libraryPathEnvironment(natives: File): Map<String, String> {
        val path = natives.absolutePath
        return when (os.mojangName) {
            "windows" -> mapOf("PATH" to joinPath(path, System.getenv("PATH")))
            "osx" -> mapOf("DYLD_LIBRARY_PATH" to joinPath(path, System.getenv("DYLD_LIBRARY_PATH")))
            else -> mapOf("LD_LIBRARY_PATH" to joinPath(path, System.getenv("LD_LIBRARY_PATH")))
        }
    }

    private fun joinPath(first: String, rest: String?): String =
        listOf(first, rest).filter { !it.isNullOrBlank() }.joinToString(File.pathSeparator)

    private fun ensureOptions(gameDir: File) {
        val file = File(gameDir, "options.txt")
        if (file.isFile) return
        file.writeText(
            """
            lang:en_us
            overrideWidth:960
            overrideHeight:600
            fullscreen:false
            pauseOnLostFocus:false
            tutorialStep:none
            onboardAccessibility:false
            skipMultiplayerWarning:true
            
            """.trimIndent(),
        )
    }
}

fun installVersion(
    settings: DesktopSettings,
    version: fcp.core.download.RemoteVersion,
    os: DesktopOs = DesktopOs.current(),
    onStatus: (String) -> Unit,
): fcp.core.download.InstallReport {
    val report = GameRepository(mirrors = settings.mirrors()).install(
        remote = version,
        gameDir = settings.gameDir(),
        options = InstallOptions(platform = os.mojangName, assetParallelism = 8),
        listener = { event -> onStatus(event.label) },
    )
    val resolved = loadResolvedVersion(settings.gameDir(), report.versionId)
    DesktopNatives.prepare(
        gameDir = settings.gameDir(),
        versionId = report.versionId,
        libraries = resolved.libraries,
        os = os,
        mirrors = settings.mirrors(),
        http = UrlHttpClient(),
        onStatus = onStatus,
    )
    return report
}
