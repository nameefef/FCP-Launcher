// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import android.os.Build
import android.os.Process
import com.tungsten.fclauncher.bridge.FCLBridge
import fcp.core.launch.JreLayout
import fcp.core.launch.LaunchException
import fcp.core.launch.LaunchLog
import fcp.core.launch.RuntimePacks
import fcp.core.launch.redactSecrets
import java.io.File
import kotlin.concurrent.thread

/**
 * Starts Minecraft's JVM inside this process.
 *
 * Android 10+ (targetSdk >= 29) denies `exec` of files in app data
 * (`execute_no_trans`), so [ProcessBuilder] on the downloaded `bin/java` fails
 * with error 13. Fold Craft Launcher dlopens `libjli.so` and calls `JLI_Launch`
 * on the same argv a desktop launcher would exec. This runs in the `:game`
 * process: `JLI_Launch` and the JVM's `exit()` end that process, not the UI.
 */
object InProcessJvm {
    fun run(specFile: File) {
        val spec = try {
            LaunchSpec.read(specFile)
        } catch (e: Exception) {
            return
        }
        val logFile = File(spec.logFile)
        val gameLog = File(spec.gameLogFile)
        val stateFile = File(spec.stateFile)
        val secrets = spec.secrets
        fun log(text: String) {
            val line = if (text.endsWith("\n")) text else "$text\n"
            val redacted = redactSecrets(line, secrets)
            LaunchLog.append(logFile, redacted)
            LaunchLog.append(gameLog, redacted)
        }
        val state = Heartbeat(stateFile)
        state.start("starting")
        val bridge = try {
            val created = FCLBridge()
            created
        } catch (e: Throwable) {
            val message = "无法加载 FCL 桥接库（libfcl.so / libpojavexec.so）：${e.message}"
            log(message)
            state.fail(message)
            return
        }
        FCLBridge.setLogSink(object : FCLBridge.LogSink {
            override fun onLog(text: String) {
                log(text)
            }

            override fun onExit(code: Int) {
                log("OpenJDK exit code $code")
                state.update("exited", code.toString())
            }
        })
        try {
            log("游戏进程 pid=${Process.myPid()}")
            val redirect = bridge.redirectStdio(logFile.absolutePath)
            if (redirect != 0) {
                log("redirectStdio 返回 $redirect，标准输出可能只在 logcat。")
            }
            unpackRuntimeJars(File(spec.javaHome), spec.nativeLibraryDir, ::log)
            val layout = try {
                JreLayout.resolve(
                    javaBinary = File(spec.javaHome, "bin/java"),
                    nativesDirectory = File(spec.nativesDirectory),
                    nativeLibraryDir = spec.nativeLibraryDir.takeIf { it.isNotBlank() }?.let { File(it) },
                    systemLibraryDirs = systemLibraryDirs(),
                )
            } catch (e: LaunchException) {
                throw Fail(e.message ?: "无法定位 Java 运行时")
            }
            log("java home: ${layout.javaHome.absolutePath}")
            log("libjli: ${layout.libjli.absolutePath}")
            log("libjvm: ${layout.libjvm.absolutePath}")
            val environment = LinkedHashMap<String, String>()
            environment.putAll(spec.environment)
            val canonicalHome = try {
                layout.javaHome.canonicalPath
            } catch (_: Exception) {
                layout.javaHome.absolutePath
            }
            environment["JAVA_HOME"] = canonicalHome
            environment["POJAV_NATIVEDIR"] = File(spec.nativesDirectory).absolutePath
            if (spec.nativeLibraryDir.isNotBlank()) {
                environment["FCL_NATIVEDIR"] = spec.nativeLibraryDir
            }
            environment["LD_LIBRARY_PATH"] = layout.libraryPath
            val bin = File(layout.javaHome, "bin").absolutePath
            val inherited = System.getenv("PATH").orEmpty()
            environment["PATH"] = if (inherited.isEmpty()) bin else "$bin:$inherited"
            environment.forEach { (key, value) ->
                if (key.isNotEmpty()) bridge.setenv(key, value)
            }
            log("LD_LIBRARY_PATH=${redactSecrets(layout.libraryPath, secrets)}")
            log("setLdLibraryPath")
            val updatedPath = LinkerSupport.updateLdLibraryPath(layout.libraryPath)
            log(if (updatedPath) "setLdLibraryPath done" else "setLdLibraryPath unavailable; continuing with dlopen")
            val namespaceReady = LinkerSupport.ensureEscapeNamespace()
            log(if (namespaceReady) "escape namespace ready" else "escape namespace unavailable")
            val chdir = bridge.chdir(spec.workingDirectory)
            if (chdir != 0) {
                throw Fail("无法进入游戏目录（chdir 返回 $chdir）：${spec.workingDirectory}")
            }
            var libjli = bridge.dlopen(layout.libjli.absolutePath)
            if (libjli == 0L) {
                bridge.dlopen(layout.libjvm.absolutePath)
                libjli = bridge.dlopen(layout.libjli.absolutePath)
            }
            val libjvm = bridge.dlopen(layout.libjvm.absolutePath)
            if (libjli == 0L || libjvm == 0L) {
                throw Fail("dlopen libjli.so / libjvm.so 失败，虚拟机起不来。libjli=$libjli libjvm=$libjvm")
            }
            for (library in layout.preload) {
                if (library.absolutePath == layout.libjli.absolutePath || library.absolutePath == layout.libjvm.absolutePath) {
                    continue
                }
                val handle = bridge.dlopen(library.absolutePath)
                if (handle == 0L) log("dlopen 失败（继续）：${library.absolutePath}")
            }
            bridge.setupExitTrap(bridge)
            log("initializeHooks")
            FCLBridge.initializeHooks()
            log("initializeHooks done")
            if (spec.command.isEmpty()) throw Fail("启动命令是空的。")
            val command = spec.command.toMutableList()
            if (command.none { it.startsWith("-Djava.home=") }) {
                command.add(1, "-Djava.home=$canonicalHome")
            }
            if (command.none { it.startsWith("-Djdk.lang.Process.launchMechanism=") }) {
                command.add(1, "-Djdk.lang.Process.launchMechanism=FORK")
            }
            log("LinkerSupport.launchJli argc=${command.size} java.home=$canonicalHome")
            state.update("jvm")
            val exitCode = LinkerSupport.launchJli(layout.libjli.absolutePath, command.toTypedArray())
            log("JLI_Launch returned $exitCode")
            state.update("exited", exitCode.toString())
        } catch (e: Fail) {
            log(e.message ?: "进程内启动失败")
            state.fail(e.message ?: "进程内启动失败")
        } catch (e: Throwable) {
            val message = "进程内启动失败：${e.javaClass.simpleName} ${e.message}"
            log(message)
            state.fail(message)
        }
    }

    private fun unpackRuntimeJars(home: File, nativeLibraryDir: String, log: (String) -> Unit) {
        val pending = RuntimePacks.jarsToUnpack(home)
        if (pending.isEmpty()) return
        val tool = File(nativeLibraryDir, "libunpack200.so")
        if (!tool.isFile) {
            throw Fail("缺少 libunpack200.so，无法把 ${pending.size} 个 .pack 解成 jar。")
        }
        for ((pack, jar) in pending) {
            log("unpack200 ${pack.name}")
            val code = LinkerSupport.unpack200(tool.absolutePath, pack.absolutePath, jar.absolutePath)
            log("unpack200 ${pack.name} -> $code（${if (jar.isFile) jar.length() else 0} 字节）")
            if (code != 0 || !jar.isFile || jar.length() == 0L) {
                throw Fail("解压 ${pack.name} 失败，代码 $code。Java 8 没有这些 jar 时虚拟机找不到 java.lang.Object。")
            }
        }
    }

    private fun systemLibraryDirs(): List<File> {
        val bits = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "lib64" else "lib"
        return listOf("/system/$bits", "/vendor/$bits", "/vendor/$bits/hw", "/system_ext/$bits").map { File(it) }
    }

    private class Fail(message: String) : Exception(message)

    private class Heartbeat(private val file: File) {
        @Volatile private var phase = "starting"
        @Volatile private var detail = ""

        fun start(initial: String) {
            phase = initial
            LaunchState.write(file, phase, detail)
            thread(name = "fcp-state", isDaemon = true) {
                while (true) {
                    LaunchState.write(file, phase, detail)
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        fun update(next: String, nextDetail: String = "") {
            phase = next
            detail = nextDetail
            LaunchState.write(file, phase, detail)
        }

        fun fail(message: String) {
            update("failed", message)
        }
    }
}
