// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.launch

import fcp.core.auth.StoredAccount
import fcp.core.download.RuleSpec
import fcp.shared.PRODUCT_NAME
import java.io.File
import java.io.RandomAccessFile

class LaunchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What the game process needs from the selected account.
 * Offline matches HMCL: user type `legacy` and a local token (the UUID without dashes).
 * Yggdrasil / LittleSkin uses user type `mojang`, the stored access token, and authlib-injector.
 */
data class LaunchAccount(
    val name: String,
    val uuid: String,
    val accessToken: String,
    val userType: String,
    val authlibApiRoot: String?,
    val kindLabel: String,
)

fun StoredAccount.toLaunchAccount(): LaunchAccount = when (this) {
    is StoredAccount.Offline -> LaunchAccount(
        name = name,
        uuid = uuid,
        accessToken = uuid.filterNot { it == '-' },
        userType = "legacy",
        authlibApiRoot = null,
        kindLabel = kindLabel,
    )
    is StoredAccount.Yggdrasil -> LaunchAccount(
        name = profileName,
        uuid = profileId,
        accessToken = accessToken,
        userType = "mojang",
        authlibApiRoot = apiRoot,
        kindLabel = kindLabel,
    )
}

data class LaunchRequest(
    val gameDir: File,
    val versionId: String,
    val account: LaunchAccount,
    /** Null when the Android JRE is not installed under the runtime directory. */
    val javaBinary: File?,
    /** Shown in the error when [javaBinary] is missing. */
    val expectedJavaPath: String,
    val nativesDirectory: File,
    val authlibInjectorJar: File?,
    val authlibDownloadError: String? = null,
    /** Set when the first-run JRE download did not produce a java binary. */
    val runtimeDownloadError: String? = null,
    val platform: String = "linux",
    val maxMemoryMb: Int = 1024,
    val launcherName: String = PRODUCT_NAME,
    val launcherVersion: String = "0.2.0",
    val extraJvmArgs: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    /** Android turns this on. Desktop can leave it off and use a normal JRE. */
    val checkNativeRuntime: Boolean = false,
    val presentNativeLibraries: Set<String> = emptySet(),
    val nativeSearchPaths: List<String> = emptyList(),
    /**
     * Desktop execs `bin/java` and needs the executable bit.
     * Android 10+ rejects that exec (`execute_no_trans` on app data) and
     * starts the JVM with `dlopen` + `JLI_Launch` instead, so the bit is irrelevant.
     */
    val requireExecutableJava: Boolean = true,
    /**
     * When set, Mojang's LWJGL / jinput / jutils native jars stay off the
     * classpath and [LwjglOverride.directory] is added instead.
     */
    val lwjglOverride: LwjglOverride? = null,
)

data class LaunchPlan(
    val versionId: String,
    val accountLabel: String,
    val command: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val secrets: List<String>,
) {
    override fun toString(): String = "LaunchPlan(versionId=$versionId, account=$accountLabel, args=${command.size})"
}

data class ArgumentSpec(
    val values: List<String>,
    val rules: List<RuleSpec>,
)

fun List<RuleSpec>.allows(platform: String): Boolean {
    if (isEmpty()) return true
    var allow = false
    for (rule in this) {
        if (rule.matches(platform)) allow = rule.action.equals("allow", ignoreCase = true)
    }
    return allow
}

object GamePaths {
    fun versionJson(gameDir: File, id: String): File = File(gameDir, "versions/$id/$id.json")

    fun clientJar(gameDir: File, id: String): File = File(gameDir, "versions/$id/$id.jar")

    fun assetIndex(gameDir: File, indexId: String): File = File(gameDir, "assets/indexes/$indexId.json")

    fun assetObject(gameDir: File, hash: String): File = File(gameDir, "assets/objects/${hash.take(2)}/$hash")

    fun library(gameDir: File, relative: String): File = File(gameDir, "libraries/$relative")

    fun logFile(gameDir: File): File = File(gameDir, "logs/fcp-launch.log")

    /** Game stdout/stderr. The launcher header stays in [logFile]. */
    fun gameOutputLog(gameDir: File): File = File(gameDir, "logs/latest-game.log")

    fun installedVersionIds(gameDir: File): Set<String> {
        val root = File(gameDir, "versions")
        if (!root.isDirectory) return emptySet()
        return root.listFiles().orEmpty().mapNotNull { folder ->
            val id = folder.name
            if (versionJson(gameDir, id).isFile && clientJar(gameDir, id).isFile) id else null
        }.toSet()
    }
}

object LaunchLog {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        synchronized(lock(file)) {
            file.writeText(text)
        }
    }

    fun writeFailure(file: File, message: String) {
        write(
            file,
            buildString {
                appendLine(PRODUCT_NAME)
                appendLine(java.time.OffsetDateTime.now().toString())
                appendLine(message)
            },
        )
    }

    fun append(file: File, text: String) {
        file.parentFile?.mkdirs()
        synchronized(lock(file)) {
            file.appendText(text)
        }
    }

    fun tail(file: File, maxChars: Int = 8000): String {
        if (!file.isFile || maxChars <= 0) return ""
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxChars).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun lock(file: File): Any = locks.computeIfAbsent(file.absolutePath) { Any() }
}

fun redactSecrets(text: String, secrets: Collection<String>): String {
    var out = text
    for (secret in secrets) {
        if (secret.length >= 8) out = out.replace(secret, "***")
    }
    return out
}

/**
 * GLFW, OpenAL, and a renderer are the native pieces a Pojav / FCL style
 * runtime uses to open a window. Fold Craft Launcher 1.3.4.4 ships GLFW's
 * JNI entry points inside `libpojavexec.so` and the renderer as
 * `libgl4es_114.so` (also `libng_gl4es.so`). Mojang's LWJGL still asks for
 * `libglfw.so`, so the APK includes that filename as a copy of libpojavexec.
 */
object AndroidNativeRuntime {
    data class Group(val label: String, val libraries: List<String>)

    val groups: List<Group> = listOf(
        Group("GLFW", listOf("libglfw.so", "libpojavexec.so")),
        Group("OpenAL", listOf("libopenal.so")),
        Group(
            "渲染器",
            listOf(
                "libgl4es.so",
                "libgl4es_114.so",
                "libng_gl4es.so",
                "libvgpu.so",
                "libvirglrenderer.so",
                "libOSMesa.so",
            ),
        ),
    )

    val libraryNames: Set<String> = groups.flatMap { it.libraries }.toSet()

    /**
     * Shared objects stored under the APK's `lib/<abi>/`. The probe accepts any
     * one library from each group. The loader also needs the bridge dependencies:
     * `libglfw.so` links `libfcl.so`, `libbytehook.so`, and `libandroidnsbypass.so`,
     * and that last one links `libc++_shared.so`.
     */
    val shippedLibraryNames: Set<String> = libraryNames + setOf(
        "libfcl.so",
        "libbytehook.so",
        "libandroidnsbypass.so",
        "libc++_shared.so",
    )

    fun problem(present: Set<String>, searched: List<String>): String? {
        val missing = groups.filter { group -> group.libraries.none { it in present } }
        if (missing.isEmpty()) return null
        return buildString {
            appendLine("Android 运行时不完整，游戏窗口起不来。缺少：")
            for (group in missing) {
                append("- ")
                append(group.label)
                append("（")
                append(group.libraries.joinToString("、"))
                appendLine("）")
            }
            if (searched.isNotEmpty()) {
                appendLine("已查找：")
                searched.forEach { append("- ").appendLine(it) }
            }
            append("这些库来自 Fold Craft Launcher 1.3.4.4，应在安装包的 native 目录或 runtime/natives。没有找到时不会启动进程。")
        }.trim()
    }
}
