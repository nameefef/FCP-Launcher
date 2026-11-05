// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.auth.AccountStore
import fcp.core.auth.GameProfile
import fcp.core.auth.LittleSkin
import fcp.core.auth.LoginOutcome
import fcp.core.auth.OfflineAccounts
import fcp.core.auth.YggdrasilClient
import fcp.core.auth.YggdrasilException
import fcp.core.auth.storedYggdrasil
import fcp.core.download.DownloadMirrors
import fcp.core.download.GameRepository
import fcp.core.download.InstallOptions
import fcp.core.download.mavenRelativePath
import fcp.core.download.parseVersionMetadata
import fcp.core.download.sha1Hex
import fcp.core.net.DownloadException
import fcp.core.net.HttpClient
import fcp.core.net.HttpRequest
import fcp.core.net.HttpResponse
import fcp.shared.PRODUCT_FULL_NAME
import fcp.shared.PRODUCT_NAME
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CoreBehaviorTest {
    @Test
    fun productNameStaysPublicAndSeparateFromTheEasterEgg() {
        assertEquals("FCP Launcher", PRODUCT_NAME)
        assertFalse(PRODUCT_NAME.contains(PRODUCT_FULL_NAME))
    }

    @Test
    fun offlineUuidMatchesMinecraftOfflinePlayerRule() {
        val expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".toByteArray(Charsets.UTF_8)).toString()
        assertEquals("5627dd98-e6be-3c21-b8a8-e92344183641", expected)
        val created = OfflineAccounts.create(" Steve ")
        assertIs<fcp.core.auth.OfflineCreateResult.Ok>(created)
        assertEquals(expected, created.account.uuid)
        assertEquals("Steve", created.account.name)
        assertTrue(OfflineAccounts.create("").message().isNotEmpty())
        assertTrue(OfflineAccounts.create("this-name-is-too-long").message().isNotEmpty())
    }

    @Test
    fun mirrorsPreferBmclapiThenMojangOnly() {
        val mirrors = DownloadMirrors()
        assertEquals(
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
            mirrors.versionManifestUrls().first(),
        )
        assertEquals(
            listOf(
                "https://bmclapi2.bangbang93.com/v1/objects/abc/client.jar",
                "https://piston-data.mojang.com/v1/objects/abc/client.jar",
            ),
            mirrors.candidatesFor("https://piston-data.mojang.com/v1/objects/abc/client.jar"),
        )
        assertEquals(
            listOf(
                "https://bmclapi2.bangbang93.com/libraries/com/mojang/brigadier/1.0.17/brigadier-1.0.17.jar",
                "https://libraries.minecraft.net/com/mojang/brigadier/1.0.17/brigadier-1.0.17.jar",
            ),
            mirrors.candidatesFor("https://libraries.minecraft.net/com/mojang/brigadier/1.0.17/brigadier-1.0.17.jar"),
        )
        val asset = "ab".padEnd(40, 'c')
        assertEquals(
            listOf(
                "https://bmclapi2.bangbang93.com/assets/ab/$asset",
                "https://resources.download.minecraft.net/ab/$asset",
            ),
            mirrors.assetObjectUrls(asset),
        )
        assertEquals(listOf("https://example.com/file"), mirrors.candidatesFor("https://example.com/file"))
        val joined = mirrors.versionManifestUrls().joinToString()
        assertFalse(joined.contains("8mi.tech"))
        assertFalse(joined.contains("mcimirror"))
        assertFalse(joined.contains("tencent"))
        assertFalse(joined.contains("login.microsoftonline.com"))
    }

    @Test
    fun libraryRulesSkipOtherOperatingSystemsAndNativesWithoutArtifacts() {
        val version = parseVersionMetadata(
            """
            {
              "id": "1.7.10",
              "downloads": {"client": {"url": "https://piston-data.mojang.com/client.jar", "sha1": "abc"}},
              "libraries": [
                {"name": "com.example:shared:1", "downloads": {"artifact": {
                  "path": "com/example/shared/1/shared-1.jar",
                  "url": "https://libraries.minecraft.net/com/example/shared/1/shared-1.jar"
                }}},
                {"name": "com.example:mac:1", "rules": [{"action": "allow", "os": {"name": "osx"}}],
                  "downloads": {"artifact": {"path": "com/example/mac/1/mac-1.jar",
                    "url": "https://libraries.minecraft.net/com/example/mac/1/mac-1.jar"}}},
                {"name": "org.lwjgl:lwjgl:2.9.0", "natives": {"linux": "natives-linux"},
                  "downloads": {"classifiers": {"natives-linux": {
                    "path": "org/lwjgl/lwjgl/2.9.0/lwjgl-2.9.0-natives-linux.jar",
                    "url": "https://libraries.minecraft.net/natives.jar"
                  }}}}
              ]
            }
            """.trimIndent(),
        )
        val included = version.libraries.filter { it.includeFor("linux") }.map { it.name }
        assertEquals(listOf("com.example:shared:1"), included)
        assertEquals("com/example/demo/1.0/demo-1.0.jar", mavenRelativePath("com.example:demo:1.0"))
    }

    @Test
    fun installerFallsBackToOfficialWhenBmclapiFails() {
        val clientBytes = "client-jar".toByteArray()
        val libraryBytes = "library".toByteArray()
        val assetBytes = "asset".toByteArray()
        val clientSha = sha1Hex(clientBytes)
        val librarySha = sha1Hex(libraryBytes)
        val assetSha = sha1Hex(assetBytes)
        val indexJson = """{"objects":{"icons/icon.png":{"hash":"$assetSha","size":${assetBytes.size}}}}"""
        val indexSha = sha1Hex(indexJson.toByteArray())
        val versionJson = """
            {
              "id": "1.6.4",
              "downloads": {"client": {
                "url": "https://piston-data.mojang.com/v1/objects/$clientSha/client.jar",
                "sha1": "$clientSha", "size": ${clientBytes.size}
              }},
              "assetIndex": {"id": "legacy", "sha1": "$indexSha",
                "url": "https://piston-meta.mojang.com/v1/packages/index/legacy.json"},
              "libraries": [
                {"name": "com.example:demo:1.0", "downloads": {"artifact": {
                  "path": "com/example/demo/1.0/demo-1.0.jar",
                  "sha1": "$librarySha",
                  "url": "https://libraries.minecraft.net/com/example/demo/1.0/demo-1.0.jar"
                }}}
              ]
            }
        """.trimIndent()
        val manifestJson = """
            {"latest":{"release":"1.6.4","snapshot":"13w23b"},"versions":[
              {"id":"1.6.4","type":"release","url":"https://piston-meta.mojang.com/v1/packages/ver/1.6.4.json",
               "sha1":"${sha1Hex(versionJson.toByteArray())}"}
            ]}
        """.trimIndent()
        val http = MapHttp(
            gets = mapOf(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" to manifestJson,
                "https://piston-meta.mojang.com/v1/packages/ver/1.6.4.json" to versionJson,
            ),
            files = mapOf(
                "https://piston-data.mojang.com/v1/objects/$clientSha/client.jar" to clientBytes,
                "https://libraries.minecraft.net/com/example/demo/1.0/demo-1.0.jar" to libraryBytes,
                "https://piston-meta.mojang.com/v1/packages/index/legacy.json" to indexJson.toByteArray(Charsets.UTF_8),
                "https://resources.download.minecraft.net/${assetSha.take(2)}/$assetSha" to assetBytes,
            ),
        )
        val report = GameRepository(http).install(
            versionId = "1.6.4",
            gameDir = Files.createTempDirectory("fcp-install").toFile(),
            options = InstallOptions(),
        )
        assertEquals("1.6.4", report.versionId)
        assertEquals(clientSha, sha1Hex(report.clientJar))
        assertEquals(1, report.librariesDownloaded)
        assertEquals(1, report.assetObjectsDownloaded)
        assertTrue(http.seen.first().startsWith("https://bmclapi2.bangbang93.com/"))
        assertTrue(http.seen.any { it.startsWith("https://piston-meta.mojang.com/") })
        assertTrue(report.clientJar.isFile)
    }

    @Test
    fun littleskinLoginIsThirdPartyYggdrasilAndDoesNotStorePassword() {
        val http = ScriptHttp { request ->
            when {
                request.url == LittleSkin.API_ROOT && request.method == "GET" ->
                    text(200, """{"meta":{"serverName":"LittleSkin","links":{"register":"https://littleskin.cn/auth/register"}}}""")
                request.url == "https://littleskin.cn" ->
                    HttpResponse(
                        code = 200,
                        headers = mapOf("X-Authlib-Injector-API-Location" to listOf(LittleSkin.API_ROOT)),
                        body = ByteArray(0),
                        finalUrl = request.url,
                    )
                request.url == "https://example.com/api/yggdrasil" && request.method == "GET" ->
                    text(200, """{"meta":{"serverName":"Example Skin"}}""")
                request.url.endsWith("/authserver/authenticate") -> {
                    val body = request.body!!.toString(Charsets.UTF_8)
                    assertTrue(body.contains("\"username\":\"alice@example.com\""))
                    assertTrue(body.contains("\"password\":\"secret\""))
                    assertFalse(body.contains("client_id"))
                    assertFalse(body.contains("login.microsoftonline.com"))
                    text(
                        200,
                        """{"accessToken":"token-1","clientToken":"client-1","selectedProfile":{"id":"550e8400e29b41d4a716446655440000","name":"Alice"},"availableProfiles":[{"id":"550e8400e29b41d4a716446655440000","name":"Alice"}]}""",
                    )
                }
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val client = YggdrasilClient(http)
        val resolved = client.resolve("littleskin.cn")
        assertEquals(LittleSkin.API_ROOT, resolved.apiRoot)
        assertEquals("LittleSkin", resolved.serverName)

        val ready = client.login(LittleSkin.API_ROOT, "alice@example.com", "secret")
        val outcome = assertIs<LoginOutcome.Ready>(ready)
        val account = storedYggdrasil(LittleSkin.PRESET_ID, outcome.session, outcome.profile)
        assertEquals("LittleSkin", account.kindLabel)
        assertEquals("Alice", account.profileName)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", account.profileId)

        val file = File(Files.createTempDirectory("fcp-accounts").toFile(), "accounts.json")
        AccountStore(file).upsert(account)
        val saved = file.readText()
        assertFalse(saved.contains("secret"))
        assertFalse(saved.contains("password"))
        assertEquals(account.id, AccountStore(file).load().selectedId)

        val custom = client.resolve("https://example.com/api/yggdrasil")
        assertEquals("Example Skin", custom.serverName)
        assertFailsWith<YggdrasilException> { client.resolve("http://insecure.example") }
    }

    @Test
    fun multipleProfilesAskTheUserToChoose() {
        val http = ScriptHttp { request ->
            when {
                request.method == "GET" ->
                    text(200, """{"meta":{"serverName":"LittleSkin"}}""")
                else -> text(
                    403,
                    """{"error":"ForbiddenOperationException","errorMessage":"Invalid credentials.","availableProfiles":[
                      {"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"One"},
                      {"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","name":"Two"}
                    ]}""",
                ).let {
                    if (request.url.endsWith("/authenticate") && request.body!!.decodeToString().contains("\"password\":\"ok\"")) {
                        text(
                            200,
                            """{"accessToken":"t","clientToken":"c","availableProfiles":[
                              {"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"One"},
                              {"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","name":"Two"}
                            ]}""",
                        )
                    } else {
                        it
                    }
                }
            }
        }
        val client = YggdrasilClient(http)
        val choice = assertIs<LoginOutcome.ChooseProfile>(client.login(LittleSkin.API_ROOT, "user", "ok"))
        assertEquals(listOf("One", "Two"), choice.session.profiles.map(GameProfile::name))
        val failure = assertFailsWith<YggdrasilException> { client.login(LittleSkin.API_ROOT, "user", "bad") }
        assertEquals("Invalid credentials.", failure.message)
        assertFalse(failure.message!!.contains("bad"))
    }
}

private fun fcp.core.auth.OfflineCreateResult.message(): String = when (this) {
    is fcp.core.auth.OfflineCreateResult.Invalid -> message
    is fcp.core.auth.OfflineCreateResult.Ok -> ""
}

private class MapHttp(
    private val gets: Map<String, String>,
    private val files: Map<String, ByteArray>,
) : HttpClient {
    val seen = mutableListOf<String>()

    override fun execute(request: HttpRequest): HttpResponse {
        seen += request.url
        if (request.url.startsWith("https://bmclapi2.bangbang93.com/")) {
            return text(500, "mirror down")
        }
        val body = gets[request.url] ?: return text(404, request.url)
        return text(200, body)
    }

    override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
        seen += url
        if (url.startsWith("https://bmclapi2.bangbang93.com/")) {
            throw DownloadException("HTTP 500：$url")
        }
        val bytes = files[url] ?: throw DownloadException("missing $url")
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }
}

private class ScriptHttp(
    private val handler: (HttpRequest) -> HttpResponse,
) : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse = handler(request)
    override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
        error("unused")
    }
}

private fun text(code: Int, body: String) = HttpResponse(
    code = code,
    headers = emptyMap(),
    body = body.toByteArray(),
    finalUrl = "",
)
