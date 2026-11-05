// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import android.content.Context
import android.content.Intent
import fcp.core.auth.AccountStore
import fcp.core.launch.AuthlibInjector
import fcp.core.launch.GameLauncher
import fcp.core.launch.GamePaths
import fcp.core.launch.LaunchException
import fcp.core.launch.LaunchLog
import fcp.core.launch.LaunchRequest
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
            val resolved = loadResolvedVersion(gameDir, versionId)
            val major = resolved.javaMajor ?: 8
            val runtime = runtimeRoot()
            runtime.mkdirs()
            val javaBinary = RuntimeProbe.findJava(runtime, major)
            val nativeDirs = RuntimeProbe.nativeDirectories(
                File(app.applicationInfo.nativeLibraryDir),
                runtime,
            )
            val present = RuntimeProbe.presentLibraries(nativeDirs)
            val natives = File(runtime, "natives")
            RuntimeProbe.stageNatives(natives, nativeDirs, present)
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
                extraJvmArgs = RuntimeProbe.extraJvmArgs(gameDir),
                environment = RuntimeProbe.environment(javaBinary, natives, gameDir, present),
                checkNativeRuntime = true,
                presentNativeLibraries = present,
                nativeSearchPaths = nativeDirs.map { it.absolutePath },
            )
            val plan = GameLauncher().plan(request)
            processLogWritten = true
            val running = GameLauncher().start(plan, log)
            LaunchSession.replace(running)
            try {
                app.startForegroundService(
                    Intent(app, GameSessionService::class.java)
                        .putExtra(GameSessionService.EXTRA_VERSION, versionId),
                )
            } catch (e: Exception) {
                LaunchLog.append(log, "前台服务没有启动：${e.message}\n")
            }
            val code = running.exitCodeOrNull() ?: run {
                Thread.sleep(1_200)
                running.exitCodeOrNull()
            }
            return if (code == null) {
                LaunchOutcome.Running(versionId, stored.profileName, log)
            } else {
                LaunchOutcome.Exited(versionId, code, LaunchLog.tail(log, 2500), log)
            }
        } catch (e: LaunchException) {
            val message = e.message ?: app.getString(R.string.launch_failed_title)
            if (!processLogWritten) LaunchLog.writeFailure(log, message)
            return LaunchOutcome.Failed(message, log)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (!processLogWritten) LaunchLog.writeFailure(log, message)
            return LaunchOutcome.Failed(message, log)
        }
    }

    private fun selectedAccount() = AccountStore(File(app.filesDir, "accounts.json")).load().let { file ->
        file.accounts.firstOrNull { it.id == file.selectedId }
    }

    private fun fail(log: File, message: String): LaunchOutcome.Failed {
        LaunchLog.writeFailure(log, message)
        return LaunchOutcome.Failed(message, log)
    }
}
