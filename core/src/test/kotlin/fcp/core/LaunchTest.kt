// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.download.sha256Hex
import fcp.core.launch.AndroidNativeRuntime
import fcp.core.launch.AuthlibInjector
import fcp.core.launch.GameLauncher
import fcp.core.launch.GamePaths
import fcp.core.launch.LAUNCH_PLACEHOLDER_PATTERN
import fcp.core.launch.LaunchAccount
import fcp.core.launch.LaunchException
import fcp.core.launch.LaunchRequest
import fcp.core.launch.LwjglOverride
import java.util.regex.Pattern
import fcp.core.net.HttpClient
import fcp.core.net.HttpRequest
import fcp.core.net.HttpResponse
import fcp.shared.PRODUCT_NAME
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LaunchTest {
    @Test
    fun placeholderPatternCompilesForTheJvmAndKeepsTheIcuBraceEscape() {
        // Android kotlin.text.Regex uses ICU. `Regex("\\$\\{([^}]+)}")` throws
        // PatternSyntaxException there (Syntax error near the unescaped '}').
        // The desktop java.util.regex engine accepts both forms, so this check
        // locks the escaped pattern the device must use and still proves it substitutes.
        assertTrue(LAUNCH_PLACEHOLDER_PATTERN.endsWith("\\}"))
        val compiled = Pattern.compile(LAUNCH_PLACEHOLDER_PATTERN)
        val matcher = compiled.matcher("\${auth_player_name}")
        assertTrue(matcher.matches())
        assertEquals("auth_player_name", matcher.group(1))
        assertEquals("Steve", compiled.matcher("\${auth_player_name}").replaceAll("Steve"))
    }

    @Test
    fun modernCommandUsesLinuxRulesAccountAndClasspath() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        install(root, "1.20.4", modernJson(), assetHash = ASSET)
        val plan = GameLauncher().plan(request(root, "1.20.4", java))
        val command = plan.command.joinToString("\n")
        assertTrue(command.contains("net.minecraft.client.main.Main"))
        assertTrue(command.contains("--username\nSteve"))
        assertTrue(command.contains("5627dd98e6be3c21b8a8e92344183641"))
        assertFalse(command.contains("5627dd98-e6be-3c21-b8a8-e92344183641"))
        assertTrue(command.contains("--userType\nlegacy"))
        assertTrue(command.contains("--versionType\n$PRODUCT_NAME"))
        assertTrue(command.contains("--assetIndex\n17"))
        assertTrue(command.contains("shared-1.jar"))
        assertTrue(command.contains("1.20.4.jar"))
        assertTrue(command.contains("-Dlinux.only=1"))
        assertTrue(command.contains("-Djava.library.path="))
        assertFalse(command.contains("-XstartOnFirstThread"))
        assertFalse(command.contains("-Xss1M"))
        assertFalse(command.contains("--demo"))
        assertFalse(command.contains("mac-1.jar"))
        assertTrue(command.contains("-Xmx1024M"))
        assertFalse(command.contains("\${"))
        assertEquals("Steve（离线）", plan.accountLabel)
    }

    @Test
    fun legacyArgumentsAndDefaultJvmAreSubstituted() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        install(root, "1.7.10", legacyJson(), assetIndexId = "1.7.10", assetHash = ASSET)
        val plan = GameLauncher().plan(request(root, "1.7.10", java))
        val command = plan.command.joinToString("\n")
        assertTrue(command.contains("--gameDir\n${root.absolutePath}"))
        assertTrue(command.contains("--assetIndex\n1.7.10"))
        assertTrue(command.contains("-Djava.library.path="))
        assertTrue(command.contains("-Dminecraft.launcher.brand=$PRODUCT_NAME"))
        assertFalse(command.contains("\${"))
        assertTrue(command.contains("shared-1.jar"))
    }

    @Test
    fun missingClientLibraryAssetsAndJavaAreOneReadableError() {
        val root = tempGame()
        install(root, "1.20.4", modernJson(), writeClient = false, writeLibrary = false, writeAssetObject = false)
        val error = assertFailsWith<LaunchException> {
            GameLauncher().plan(request(root, "1.20.4", java = null))
        }
        val message = error.message!!
        assertTrue(message.contains("1.20.4.jar"))
        assertTrue(message.contains("shared-1.jar"))
        assertTrue(message.contains("icons/icon.png"))
        assertTrue(message.contains("Java 17"))
        assertTrue(message.contains("java-17"))
        assertTrue(message.contains("Android JRE"))
    }

    @Test
    fun missingAssetIndexNamesTheFile() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        install(root, "1.20.4", modernJson(), writeIndex = false)
        val message = assertFailsWith<LaunchException> {
            GameLauncher().plan(request(root, "1.20.4", java))
        }.message!!
        assertTrue(message.contains("assets/indexes/17.json"))
    }

    @Test
    fun versionWithoutJavaVersionAsksForJava8() {
        val root = tempGame()
        install(root, "1.7.10", legacyJson(), assetIndexId = "1.7.10", assetHash = ASSET)
        val message = assertFailsWith<LaunchException> {
            GameLauncher().plan(
                request(root, "1.7.10", java = null).copy(expectedJavaPath = "/runtime/java-8/bin/java"),
            )
        }.message!!
        assertTrue(message.contains("Java 8"))
        assertTrue(message.contains("/runtime/java-8/bin/java"))
    }

    @Test
    fun androidClasspathDropsMojangLwjglAndAddsTheFclBundle() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        install(root, "1.7.10", legacyJsonWithLwjgl(), assetIndexId = "1.7.10", assetHash = ASSET)
        val bundle = File(root, "runtime/lwjgl2")
        File(bundle, "lwjgl.jar").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        File(bundle, "lwjgl-lwjglx.jar").writeBytes(byteArrayOf(2))
        File(bundle, "lwjgl-stb.jar").writeBytes(byteArrayOf(3))
        GamePaths.library(root, "net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(4))
        }
        val plan = GameLauncher().plan(
            request(root, "1.7.10", java).copy(
                lwjglOverride = LwjglOverride(bundle, includeCompatibilityLayer = true),
            ),
        )
        val command = plan.command.joinToString("\n")
        assertTrue(command.contains("${bundle.absolutePath}/lwjgl.jar"))
        assertTrue(command.contains("lwjgl-lwjglx.jar"))
        assertTrue(command.contains("lwjgl-stb.jar"))
        assertTrue(!command.contains("lwjgl-2.9.1.jar"))
        assertTrue(!command.contains("jinput-platform"))
        assertTrue(command.contains("jinput-2.0.5.jar"))
        assertTrue(command.contains("-Dorg.lwjgl.librarypath="))
        val indexOfBundle = command.indexOf("lwjgl.jar")
        val indexOfClient = command.indexOf("1.7.10.jar")
        assertTrue(indexOfBundle in 0 until indexOfClient)
    }

    @Test
    fun inProcessLaunchAcceptsJavaWithoutTheExecuteBit() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        assertTrue(java.setExecutable(false, false))
        assertFalse(java.canExecute())
        install(root, "1.7.10", legacyJson(), assetIndexId = "1.7.10", assetHash = ASSET)
        val plan = GameLauncher().plan(request(root, "1.7.10", java).copy(requireExecutableJava = false))
        assertEquals(java.absolutePath, plan.command.first())
        assertTrue(plan.command.joinToString("\n").contains("-Djava.library.path="))
    }

    @Test
    fun javaWithoutExecuteBitIsReported() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        assertTrue(java.setExecutable(false, false))
        assertFalse(java.canExecute())
        install(root, "1.20.4", modernJson(), assetHash = ASSET)
        val message = assertFailsWith<LaunchException> {
            GameLauncher().plan(request(root, "1.20.4", java))
        }.message!!
        assertTrue(message.contains("没有执行权限"))
    }

    @Test
    fun yggdrasilAddsAuthlibAgentAndLittleSkinFailureIsReadable() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        install(root, "1.20.4", modernJson(), assetHash = ASSET)
        val jar = File(root, "authlib-injector.jar")
        jar.writeBytes(fakeJar())
        val plan = GameLauncher().plan(
            request(root, "1.20.4", java).copy(
                account = yggdrasil(),
                authlibInjectorJar = jar,
            ),
        )
        val command = plan.command.joinToString("\n")
        assertTrue(command.contains("-javaagent:${jar.absolutePath}=https://littleskin.cn/api/yggdrasil"))
        assertTrue(command.contains("--userType\nmojang"))
        assertFalse(command.contains("\nlegacy"))

        val missing = assertFailsWith<LaunchException> {
            GameLauncher().plan(
                request(root, "1.20.4", java).copy(
                    account = yggdrasil(),
                    authlibInjectorJar = File(root, "missing-authlib.jar"),
                    authlibDownloadError = "mirror down",
                ),
            )
        }.message!!
        assertTrue(missing.contains("authlib-injector"))
        assertTrue(missing.contains("mirror down"))
        assertFalse(missing.contains("littleskin-access-token"))
    }

    @Test
    fun inheritedVersionUsesParentMainClassAndChildLibrary() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 0\n")
        install(root, "1.20.1", parentJson(), assetIndexId = "5", assetHash = ASSET, libraryPath = "com/example/shared/1/shared-1.jar")
        File(GamePaths.versionJson(root, "child").parentFile, "child.json").parentFile.mkdirs()
        GamePaths.versionJson(root, "child").writeText(childJson())
        GamePaths.clientJar(root, "child").writeBytes(byteArrayOf(1))
        GamePaths.library(root, "com/example/shared/2/shared-2.jar").parentFile.mkdirs()
        GamePaths.library(root, "com/example/shared/2/shared-2.jar").writeBytes(byteArrayOf(1))
        GamePaths.library(root, "com/example/extra/1/extra-1.jar").parentFile.mkdirs()
        GamePaths.library(root, "com/example/extra/1/extra-1.jar").writeBytes(byteArrayOf(1))
        File(GamePaths.library(root, "com/example/shared/1/shared-1.jar").path).delete()

        val plan = GameLauncher().plan(request(root, "child", java))
        val command = plan.command.joinToString("\n")
        assertTrue(command.contains("net.minecraft.client.main.Main"))
        assertTrue(command.contains("shared-2.jar"))
        assertTrue(command.contains("extra-1.jar"))
        assertFalse(command.contains("shared-1.jar"))
        assertTrue(command.contains("--version\nchild"))
        assertTrue(command.contains("--username\nSteve"))
        assertEquals("child", plan.versionId)
    }

    @Test
    fun missingParentAndInheritanceCycleAreReadable() {
        val root = tempGame()
        GamePaths.versionJson(root, "child").parentFile.mkdirs()
        GamePaths.versionJson(root, "child").writeText("""{"id":"child","inheritsFrom":"missing-parent"}""")
        val missing = assertFailsWith<LaunchException> { GameLauncher().plan(request(root, "child", java = null)) }
        assertTrue(missing.message!!.contains("missing-parent"))
        assertTrue(missing.message!!.contains("父版本"))

        GamePaths.versionJson(root, "a").parentFile.mkdirs()
        GamePaths.versionJson(root, "b").parentFile.mkdirs()
        GamePaths.versionJson(root, "a").writeText("""{"id":"a","inheritsFrom":"b"}""")
        GamePaths.versionJson(root, "b").writeText("""{"id":"b","inheritsFrom":"a"}""")
        val cycle = assertFailsWith<LaunchException> { GameLauncher().plan(request(root, "a", java = null)) }
        assertTrue(cycle.message!!.contains("循环"))
    }

    @Test
    fun nativeRuntimeGapDoesNotStartTheProcess() {
        val root = tempGame()
        val java = executable(root, "#!/bin/sh\nexit 7\n")
        install(root, "1.20.4", modernJson(), assetHash = ASSET)
        val message = assertFailsWith<LaunchException> {
            GameLauncher().plan(
                request(root, "1.20.4", java).copy(
                    checkNativeRuntime = true,
                    presentNativeLibraries = setOf("libglfw.so"),
                    nativeSearchPaths = listOf("/data/runtime/natives"),
                ),
            )
        }.message!!
        assertTrue(message.contains("OpenAL"))
        assertTrue(message.contains("libopenal.so"))
        assertTrue(message.contains("渲染器"))
        assertTrue(message.contains("/data/runtime/natives"))
        assertFalse(message.contains("缺少：\n- GLFW"))
        assertEquals(
            null,
            AndroidNativeRuntime.problem(
                setOf("libglfw.so", "libopenal.so", "libgl4es.so"),
                emptyList(),
            ),
        )
    }

    @Test
    fun processStartsAndLogRedactsTheAccessToken() {
        val root = tempGame("fcp launch")
        val argv = File(root, "argv.txt")
        val java = executable(
            root,
            "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${argv.absolutePath}'\nexit 0\n",
        )
        install(root, "1.20.4", modernJson(), assetHash = ASSET)
        val plan = GameLauncher().plan(request(root, "1.20.4", java))
        val running = GameLauncher().start(plan, GamePaths.logFile(root))
        assertEquals(0, running.awaitExit(10_000))
        val log = GamePaths.logFile(root).readText()
        assertTrue(log.contains(PRODUCT_NAME))
        assertTrue(log.contains("Steve"))
        assertTrue(log.contains("***"))
        assertFalse(log.contains("offline-access-token-value"))
        val recorded = argv.readText()
        assertTrue(recorded.contains("offline-access-token-value"))
        assertTrue(recorded.contains("Steve"))
        assertTrue(recorded.lines().contains(root.absolutePath))
    }

    @Test
    fun authlibMetadataParsesAndEnsureKeepsAnExistingJar() {
        val parsed = AuthlibInjector.parseBmclLatest(
            """
            {"build_number":56,"version":"1.2.8",
             "download_url":"https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/56/authlib-injector-1.2.8.jar",
             "checksums":{"sha256":"abc"}}
            """.trimIndent(),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/56/authlib-injector-1.2.8.jar",
            parsed.url,
        )
        assertEquals("abc", parsed.sha256)
        val github = AuthlibInjector.parseGithubLatest(
            """{"assets":[{"name":"authlib-injector-1.2.8.jar","browser_download_url":"https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.8/authlib-injector-1.2.8.jar"}]}""",
        )
        assertTrue(github.url.endsWith("authlib-injector-1.2.8.jar"))

        val dest = File(tempGame(), AuthlibInjector.FILE_NAME)
        dest.writeBytes(fakeJar())
        val http = CountingHttp()
        assertEquals(dest, AuthlibInjector.ensure(dest, http))
        assertEquals(0, http.calls)

        val downloaded = File(tempGame(), AuthlibInjector.FILE_NAME)
        val bytes = fakeJar()
        val sha = sha256Hex(bytes)
        val serving = CountingHttp(
            gets = mapOf(
                AuthlibInjector.BMCL_LATEST to """{"download_url":"https://bmclapi2.bangbang93.com/authlib.jar","checksums":{"sha256":"$sha"}}""",
            ),
            files = mapOf("https://bmclapi2.bangbang93.com/authlib.jar" to bytes),
        )
        val saved = AuthlibInjector.ensure(downloaded, serving)
        assertTrue(AuthlibInjector.isUsableJar(saved))
        assertEquals(sha, sha256Hex(saved))
        assertEquals(2, serving.calls)
        AuthlibInjector.ensure(saved, serving)
        assertEquals(2, serving.calls)
    }

    @Test
    fun installedIdsRequireJsonAndJar() {
        val root = tempGame()
        install(root, "1.20.4", modernJson(), assetHash = ASSET)
        GamePaths.versionJson(root, "half").parentFile.mkdirs()
        GamePaths.versionJson(root, "half").writeText("{}")
        assertEquals(setOf("1.20.4"), GamePaths.installedVersionIds(root))
    }
}

private const val ASSET = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

private fun tempGame(prefix: String = "fcp-launch"): File = Files.createTempDirectory(prefix).toFile()

private fun executable(root: File, body: String): File {
    val java = File(root, "runtime/java/bin/java")
    java.parentFile.mkdirs()
    java.writeText(body)
    assertTrue(java.setExecutable(true))
    return java
}

private fun install(
    root: File,
    id: String,
    json: String,
    assetIndexId: String = "17",
    assetHash: String = ASSET,
    libraryPath: String = "com/example/shared/1/shared-1.jar",
    writeClient: Boolean = true,
    writeLibrary: Boolean = true,
    writeIndex: Boolean = true,
    writeAssetObject: Boolean = true,
) {
    GamePaths.versionJson(root, id).parentFile.mkdirs()
    GamePaths.versionJson(root, id).writeText(json)
    if (writeClient) GamePaths.clientJar(root, id).writeBytes(byteArrayOf(1))
    if (writeLibrary) {
        val lib = GamePaths.library(root, libraryPath)
        lib.parentFile.mkdirs()
        lib.writeBytes(byteArrayOf(1))
    }
    if (writeIndex) {
        val index = GamePaths.assetIndex(root, assetIndexId)
        index.parentFile.mkdirs()
        index.writeText("""{"objects":{"icons/icon.png":{"hash":"$assetHash","size":1}}}""")
    }
    if (writeAssetObject) {
        val obj = GamePaths.assetObject(root, assetHash)
        obj.parentFile.mkdirs()
        obj.writeBytes(byteArrayOf(1))
    }
    File(root, "natives").mkdirs()
}

private fun request(root: File, id: String, java: File?): LaunchRequest = LaunchRequest(
    gameDir = root,
    versionId = id,
    account = LaunchAccount(
        name = "Steve",
        uuid = "5627dd98-e6be-3c21-b8a8-e92344183641",
        accessToken = "offline-access-token-value",
        userType = "legacy",
        authlibApiRoot = null,
        kindLabel = "离线",
    ),
    javaBinary = java,
    expectedJavaPath = "/runtime/java-17/bin/java",
    nativesDirectory = File(root, "natives"),
    authlibInjectorJar = null,
)

private fun yggdrasil() = LaunchAccount(
    name = "Alice",
    uuid = "550e8400-e29b-41d4-a716-446655440000",
    accessToken = "littleskin-access-token",
    userType = "mojang",
    authlibApiRoot = "https://littleskin.cn/api/yggdrasil",
    kindLabel = "LittleSkin",
)

private fun fakeJar(): ByteArray {
    val bytes = ByteArray(2048)
    bytes[0] = 0x50
    bytes[1] = 0x4B
    bytes[2] = 3
    bytes[3] = 4
    return bytes
}

private fun modernJson() = """
    {
      "id": "1.20.4",
      "mainClass": "net.minecraft.client.main.Main",
      "assetIndex": {"id": "17", "url": "https://example.invalid/index"},
      "javaVersion": {"majorVersion": 17},
      "libraries": [
        {"name": "com.example:shared:1", "downloads": {"artifact": {
          "path": "com/example/shared/1/shared-1.jar",
          "url": "https://example.invalid/shared.jar"
        }}},
        {"name": "com.example:mac:1", "rules": [{"action": "allow", "os": {"name": "osx"}}],
          "downloads": {"artifact": {
            "path": "com/example/mac/1/mac-1.jar",
            "url": "https://example.invalid/mac.jar"
          }}}
      ],
      "arguments": {
        "game": [
          "--username", "${'$'}{auth_player_name}",
          "--version", "${'$'}{version_name}",
          "--gameDir", "${'$'}{game_directory}",
          "--assetsDir", "${'$'}{assets_root}",
          "--assetIndex", "${'$'}{assets_index_name}",
          "--uuid", "${'$'}{auth_uuid}",
          "--accessToken", "${'$'}{auth_access_token}",
          "--userType", "${'$'}{user_type}",
          "--versionType", "${'$'}{version_type}",
          {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"}
        ],
        "jvm": [
          {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
          {"rules": [{"action": "allow", "os": {"name": "linux"}}], "value": "-Dlinux.only=1"},
          {"rules": [{"action": "allow", "os": {"arch": "x86"}}], "value": "-Xss1M"},
          "-Djava.library.path=${'$'}{natives_directory}",
          "-cp",
          "${'$'}{classpath}"
        ]
      }
    }
""".trimIndent()

private fun legacyJsonWithLwjgl() = """
    {
      "id": "1.7.10",
      "mainClass": "net.minecraft.client.main.Main",
      "assetIndex": {"id": "1.7.10", "url": "https://example.invalid/index"},
      "minecraftArguments": "--username ${'$'}{auth_player_name} --version ${'$'}{version_name} --gameDir ${'$'}{game_directory} --assetsDir ${'$'}{game_assets} --assetIndex ${'$'}{assets_index_name} --uuid ${'$'}{auth_uuid} --accessToken ${'$'}{auth_access_token} --userProperties ${'$'}{user_properties} --userType ${'$'}{user_type}",
      "libraries": [
        {"name": "com.example:shared:1", "downloads": {"artifact": {
          "path": "com/example/shared/1/shared-1.jar",
          "url": "https://example.invalid/shared.jar"
        }}},
        {"name": "org.lwjgl.lwjgl:lwjgl:2.9.1", "downloads": {"artifact": {
          "path": "org/lwjgl/lwjgl/lwjgl/2.9.1/lwjgl-2.9.1.jar",
          "url": "https://example.invalid/lwjgl.jar"
        }}},
        {"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux", "downloads": {"artifact": {
          "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1-natives-linux.jar",
          "url": "https://example.invalid/lwjgl-natives.jar"
        }}},
        {"name": "net.java.jinput:jinput:2.0.5", "downloads": {"artifact": {
          "path": "net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar",
          "url": "https://example.invalid/jinput.jar"
        }}},
        {"name": "net.java.jinput:jinput-platform:2.0.5:natives-linux", "downloads": {"artifact": {
          "path": "net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives-linux.jar",
          "url": "https://example.invalid/jinput-natives.jar"
        }}}
      ]
    }
""".trimIndent()

private fun legacyJson() = """
    {
      "id": "1.7.10",
      "mainClass": "net.minecraft.client.main.Main",
      "assetIndex": {"id": "1.7.10", "url": "https://example.invalid/index"},
      "minecraftArguments": "--username ${'$'}{auth_player_name} --version ${'$'}{version_name} --gameDir ${'$'}{game_directory} --assetsDir ${'$'}{game_assets} --assetIndex ${'$'}{assets_index_name} --uuid ${'$'}{auth_uuid} --accessToken ${'$'}{auth_access_token} --userProperties ${'$'}{user_properties} --userType ${'$'}{user_type}",
      "libraries": [
        {"name": "com.example:shared:1", "downloads": {"artifact": {
          "path": "com/example/shared/1/shared-1.jar",
          "url": "https://example.invalid/shared.jar"
        }}}
      ]
    }
""".trimIndent()

private fun parentJson() = """
    {
      "id": "1.20.1",
      "mainClass": "net.minecraft.client.main.Main",
      "assetIndex": {"id": "5", "url": "https://example.invalid/index"},
      "javaVersion": {"majorVersion": 17},
      "libraries": [
        {"name": "com.example:shared:1", "downloads": {"artifact": {
          "path": "com/example/shared/1/shared-1.jar",
          "url": "https://example.invalid/shared.jar"
        }}}
      ],
      "arguments": {
        "game": ["--username", "${'$'}{auth_player_name}"],
        "jvm": ["-cp", "${'$'}{classpath}"]
      }
    }
""".trimIndent()

private fun childJson() = """
    {
      "id": "child",
      "inheritsFrom": "1.20.1",
      "libraries": [
        {"name": "com.example:shared:2", "downloads": {"artifact": {
          "path": "com/example/shared/2/shared-2.jar",
          "url": "https://example.invalid/shared2.jar"
        }}},
        {"name": "com.example:extra:1", "downloads": {"artifact": {
          "path": "com/example/extra/1/extra-1.jar",
          "url": "https://example.invalid/extra.jar"
        }}}
      ],
      "arguments": {"game": ["--version", "${'$'}{version_name}"]}
    }
""".trimIndent()

private class CountingHttp(
    private val gets: Map<String, String> = emptyMap(),
    private val files: Map<String, ByteArray> = emptyMap(),
) : HttpClient {
    var calls: Int = 0

    override fun execute(request: HttpRequest): HttpResponse {
        calls += 1
        val body = gets[request.url] ?: return HttpResponse(404, emptyMap(), ByteArray(0), request.url)
        return HttpResponse(200, emptyMap(), body.toByteArray(), request.url)
    }

    override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
        calls += 1
        val bytes = files[url] ?: error("missing $url")
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }
}
