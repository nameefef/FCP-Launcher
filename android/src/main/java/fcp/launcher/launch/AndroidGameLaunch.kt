// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import fcp.core.auth.AccountStore
import fcp.core.download.GameRepository
import fcp.core.launch.AndroidNativeRuntime
import fcp.core.launch.ApkNativeLibraries
import fcp.core.launch.AuthlibInjector
import fcp.core.launch.GameLauncher
import fcp.core.launch.GamePaths
import fcp.core.launch.JreLayout
import fcp.core.launch.LaunchException
import fcp.core.launch.LwjglInstaller
import fcp.core.launch.LwjglOverride
import fcp.core.launch.chooseLwjgl
import fcp.core.launch.LaunchLog
import fcp.core.launch.LaunchRequest
import fcp.core.launch.RuntimeInstaller
import fcp.core.launch.loadBundledRuntimeCatalog
import fcp.core.launch.loadResolvedVersion
import fcp.core.launch.toLaunchAccount
import fcp.core.net.UrlHttpClient
import fcp.launcher.BuildConfig
import fcp.launcher.R
import fcp.shared.PRODUCT_NAME
import java.io.File

sealed class LaunchOutcome {
    data class Running(val versionId: String, val accountName: String, val logFile: File) : LaunchOutcome()
    data class Exited(val versionId: String, val exitCode: Int, val logTail: String, val logFile: File) : LaunchOutcome()
    data class Failed(val message: String, val logFile: File) : LaunchOutcome()
}

class AndroidGameLaunch(context: Context) {
    private val app = context.applicationContext

    fun gameDir(): File = File(app.filesDir, "minecraft")

    fun runtimeRoot(): File = File(app.filesDir, "runtime")

    fun launch(versionId: String, onStatus: (String) -> Unit): LaunchOutcome {
        val gameDir = gameDir()
        val log = GamePaths.logFile(gameDir)
        var processLogWritten = false
        try {
            val stored = selectedAccount()
                ?: return fail(log, app.getString(R.string.no_account_for_launch))
            onStatus(app.getString(R.string.launch_checking))
            onStatus(app.getString(R.string.repairing_assets))
            GameRepository().completeInstallation(versionId, gameDir) { event ->
                onStatus(event.label)
            }
            val resolved = loadResolvedVersion(gameDir, versionId)
            val major = resolved.javaMajor ?: 8
            val runtime = runtimeRoot()
            runtime.mkdirs()
            var javaBinary = RuntimeProbe.findJava(runtime, major)
            var runtimeError: String? = null
            if (javaBinary == null || !javaBinary.isFile) {
                onStatus(app.getString(R.string.downloading_runtime, major))
                val installed = runCatching {
                    RuntimeInstaller().ensureJava(
                        catalog = loadBundledRuntimeCatalog(),
                        http = UrlHttpClient(),
                        abi = RuntimeProbe.deviceAbi(),
                        major = major,
                        runtimeRoot = runtime,
                        onStatus = onStatus,
                    )
                }
                javaBinary = installed.getOrNull() ?: javaBinary
                runtimeError = installed.exceptionOrNull()?.message
            }
            val nativeLibraryDir = File(app.applicationInfo.nativeLibraryDir)
            val natives = File(runtime, "natives")
            // extractNativeLibs=false leaves nativeLibraryDir empty. Pull the
            // .so files out of the APK so the probe and the game both see them.
            ApkNativeLibraries.ensureExtracted(
                apk = File(app.applicationInfo.sourceDir),
                abis = Build.SUPPORTED_ABIS.toList(),
                names = AndroidNativeRuntime.shippedLibraryNames,
                nativeLibraryDir = nativeLibraryDir,
                destination = natives,
            )
            val nativeDirs = RuntimeProbe.nativeDirectories(nativeLibraryDir, runtime)
            RuntimeProbe.stageNatives(natives, nativeDirs, AndroidNativeRuntime.shippedLibraryNames)
            val lwjglChoice = chooseLwjgl(resolved.id, resolved.libraries.map { it.name })
            val lwjglDir = runCatching {
                LwjglInstaller().ensure(
                    catalog = loadBundledRuntimeCatalog(),
                    http = UrlHttpClient(),
                    abi = RuntimeProbe.deviceAbi(),
                    choice = lwjglChoice,
                    runtimeRoot = runtime,
                    sharedNatives = natives,
                    onStatus = onStatus,
                )
            }
            if (lwjglDir.isFailure) {
                val message = lwjglDir.exceptionOrNull()?.message ?: "LWJGL 没有准备好"
                return fail(log, message)
            }
            val present = RuntimeProbe.presentLibraries(nativeDirs)
            val authlib = File(runtime, AuthlibInjector.FILE_NAME)
            var authlibError: String? = null
            if (stored.toLaunchAccount().authlibApiRoot != null && !AuthlibInjector.isUsableJar(authlib)) {
                onStatus(app.getString(R.string.downloading_authlib))
                val downloaded = runCatching { AuthlibInjector.ensure(authlib, UrlHttpClient()) }
                if (downloaded.isFailure) {
                    authlibError = downloaded.exceptionOrNull()?.message ?: app.getString(R.string.download_failed, "")
                }
            }
            val request = LaunchRequest(
                gameDir = gameDir,
                versionId = versionId,
                account = stored.toLaunchAccount(),
                javaBinary = javaBinary,
                expectedJavaPath = RuntimeProbe.canonicalJava(runtime, major).absolutePath,
                nativesDirectory = natives,
                authlibInjectorJar = authlib.takeIf { it.isFile },
                authlibDownloadError = authlibError,
                launcherName = PRODUCT_NAME,
                launcherVersion = BuildConfig.VERSION_NAME,
                extraJvmArgs = rendererJvmArgs(gameDir, natives, present),
                environment = RuntimeProbe.environment(javaBinary, natives, gameDir, present),
                runtimeDownloadError = runtimeError,
                checkNativeRuntime = true,
                presentNativeLibraries = present,
                nativeSearchPaths = nativeDirs.map { it.absolutePath },
                // bin/java is not executed. Android 10+ denies execute_no_trans on app data.
                requireExecutableJava = false,
                lwjglOverride = LwjglOverride(
                    directory = lwjglDir.getOrThrow(),
                    includeCompatibilityLayer = lwjglChoice.includeCompatibilityLayer,
                ),
            )
            val plan = GameLauncher().plan(request)
            processLogWritten = true
            val java = request.javaBinary
                ?: return fail(log, "没有找到 Java，无法启动。")
            LaunchLog.write(
                log,
                GameLauncher().describe(plan) + "launchMode: in-process JLI_Launch (:game activity)\n",
            )
            val gameLog = GamePaths.gameOutputLog(gameDir)
            if (gameLog.exists()) gameLog.delete()
            val stateFile = File(runtime, "launch-state.txt")
            stopExistingGame(stateFile)
            LaunchState.write(stateFile, "requested")
            val specFile = File(runtime, "launch-spec.json")
            LaunchSpec(
                versionId = versionId,
                command = plan.command,
                environment = plan.environment,
                workingDirectory = plan.workingDirectory.absolutePath,
                javaHome = JreLayout.javaHomeOf(java).absolutePath,
                nativesDirectory = natives.absolutePath,
                nativeLibraryDir = nativeLibraryDir.absolutePath,
                logFile = log.absolutePath,
                gameLogFile = gameLog.absolutePath,
                secrets = plan.secrets,
                stateFile = stateFile.absolutePath,
            ).write(specFile)
            try {
                app.startActivity(
                    Intent(app, GameActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(GameSessionService.EXTRA_SPEC, specFile.absolutePath)
                        .putExtra(GameSessionService.EXTRA_VERSION, versionId),
                )
            } catch (e: Exception) {
                val message = "游戏画面没有打开：${e.message}"
                LaunchLog.append(log, message + "\n")
                return LaunchOutcome.Failed(message, log)
            }
            return awaitGame(versionId, stored.profileName, log, stateFile)
        } catch (e: LaunchException) {
            val message = e.message ?: app.getString(R.string.launch_failed_title)
            record(log, message, processLogWritten)
            return LaunchOutcome.Failed(message, log)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            record(log, message, processLogWritten)
            return LaunchOutcome.Failed(message, log)
        }
    }

    private fun stopExistingGame(stateFile: File) {
        if (!gameAppearsAlive(stateFile)) return
        try {
            app.startService(
                Intent(app, GameSessionService::class.java).setAction(GameSessionService.ACTION_STOP),
            )
        } catch (_: Exception) {
        }
        val deadline = System.currentTimeMillis() + 8_000
        while (gameAppearsAlive(stateFile) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
    }

    private fun awaitGame(versionId: String, accountName: String, log: File, stateFile: File): LaunchOutcome {
        val started = System.currentTimeMillis()
        val deadline = started + 180_000
        var sawJvmAt = 0L
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val snapshot = LaunchState.read(stateFile)
            if (snapshot == null) {
                Thread.sleep(400)
                continue
            }
            when (snapshot.phase) {
                "failed" -> return LaunchOutcome.Failed(snapshot.detail.ifBlank { app.getString(R.string.launch_failed_title) }, log)
                "exited" -> {
                    val code = snapshot.detail.toIntOrNull() ?: -1
                    return LaunchOutcome.Exited(versionId, code, LaunchLog.tail(log, 2500), log)
                }
                "jvm" -> {
                    if (sawJvmAt == 0L) sawJvmAt = now
                    val stale = snapshot.stampMs > 0 && now - snapshot.stampMs > 8_000
                    if (stale) {
                        return LaunchOutcome.Exited(versionId, -1, LaunchLog.tail(log, 2500), log)
                    }
                    if (now - sawJvmAt >= 3_000 && now - snapshot.stampMs < 4_000) {
                        return LaunchOutcome.Running(versionId, accountName, log)
                    }
                }
                "requested" -> {
                    if (now - started > 90_000) {
                        val message = "游戏进程没有起来。日志：${log.absolutePath}"
                        LaunchLog.append(log, message + "\n")
                        return LaunchOutcome.Failed(message, log)
                    }
                }
                "starting" -> {
                    if (snapshot.stampMs > 0 && now - snapshot.stampMs > 20_000) {
                        val message = "游戏进程在启动虚拟机前退出。\n${LaunchLog.tail(log, 1500)}"
                        return LaunchOutcome.Failed(message, log)
                    }
                }
            }
            Thread.sleep(400)
        }
        val message = "等待虚拟机启动超时。日志：${log.absolutePath}"
        LaunchLog.append(log, message + "\n")
        return LaunchOutcome.Failed(message, log)
    }

    private fun gameAppearsAlive(stateFile: File): Boolean {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val target = app.packageName + ":game"
        if (am.runningAppProcesses.orEmpty().any { it.processName == target }) return true
        val snapshot = LaunchState.read(stateFile) ?: return false
        if (snapshot.phase != "starting" && snapshot.phase != "jvm") return false
        return snapshot.stampMs > 0 && System.currentTimeMillis() - snapshot.stampMs < 4_000
    }

    private fun record(log: File, message: String, headerWritten: Boolean) {
        if (!headerWritten) LaunchLog.writeFailure(log, message) else LaunchLog.append(log, message + "\n")
    }

    private fun rendererJvmArgs(gameDir: File, natives: File, present: Set<String>): List<String> {
        val args = RuntimeProbe.extraJvmArgs(gameDir).toMutableList()
        args += "-Dorg.lwjgl.librarypath=${natives.absolutePath}"
        val renderer = listOf("libgl4es_114.so", "libgl4es.so", "libng_gl4es.so").firstOrNull { it in present }
        if (renderer != null) args += "-Dorg.lwjgl.opengl.libname=$renderer"
        val openal = File(natives, "libopenal.so")
        if (openal.isFile) args += "-Dorg.lwjgl.openal.libname=${openal.absolutePath}"
        val freetype = File(natives, "libfreetype.so")
        if (freetype.isFile) args += "-Dorg.lwjgl.freetype.libname=${freetype.absolutePath}"
        return args
    }

    private fun selectedAccount() = AccountStore(File(app.filesDir, "accounts.json")).load().let { file ->
        file.accounts.firstOrNull { it.id == file.selectedId }
    }

    private fun fail(log: File, message: String): LaunchOutcome.Failed {
        LaunchLog.writeFailure(log, message)
        return LaunchOutcome.Failed(message, log)
    }
}
