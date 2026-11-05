// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core

import fcp.core.auth.LittleSkin
import fcp.core.auth.YggdrasilClient
import fcp.core.download.GameRepository
import fcp.core.download.InstallOptions
import fcp.core.download.parseVersionMetadata
import fcp.core.download.sha1Hex
import fcp.core.net.HttpClient
import fcp.core.net.HttpRequest
import fcp.core.net.HttpResponse
import fcp.core.net.UrlHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class LiveDownloadTest {
    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    fun manifestListsHistoricalVersionsAndInstallsOldAndModernClients() {
        val http = RecordingHttpClient(UrlHttpClient())
        val repository = GameRepository(http)
        val manifest = repository.fetchManifest()
        assertTrue(http.urls.first().startsWith("https://bmclapi2.bangbang93.com/"), http.urls.first())
        assertTrue(manifest.fetchedFrom.startsWith("https://bmclapi2.bangbang93.com/"), manifest.fetchedFrom)
        val ids = manifest.versions.map { it.id }.toSet()
        assertTrue("1.0" in ids)
        assertTrue("1.7.10" in ids)
        assertTrue("1.20.4" in ids)
        assertTrue(manifest.versions.any { it.type == "snapshot" })
        assertTrue(manifest.versions.any { it.type == "old_alpha" })
        assertTrue(manifest.versions.any { it.type == "old_beta" })

        val gameDir = Files.createTempDirectory("fcp-live").toFile()
        val options = InstallOptions(libraryLimit = 2, assetObjectLimit = 2)
        for (versionId in listOf("1.7.10", "1.20.4")) {
            val report = repository.install(versionId, gameDir, options)
            val metadata = parseVersionMetadata(report.versionJson.readText())
            assertEquals(versionId, report.versionId)
            assertEquals(metadata.client!!.sha1, sha1Hex(report.clientJar))
            assertEquals(2, report.librariesDownloaded)
            assertEquals(2, report.assetObjectsDownloaded)
            assertTrue(report.assetIndex!!.isFile)
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    fun littleSkinPublicMetadataMatchesTheManual() {
        val client = YggdrasilClient(UrlHttpClient())
        val direct = client.fetchMetadata(LittleSkin.API_ROOT)
        assertEquals(LittleSkin.API_ROOT, direct.apiRoot)
        assertTrue(direct.serverName.contains("LittleSkin"))
        val indicated = client.resolve("https://littleskin.cn")
        assertEquals(LittleSkin.API_ROOT, indicated.apiRoot)
        assertEquals("LittleSkin", indicated.serverName)
    }
}

private class RecordingHttpClient(private val delegate: HttpClient) : HttpClient {
    val urls: MutableList<String> = CopyOnWriteArrayList()

    override fun execute(request: HttpRequest): HttpResponse {
        urls += request.url
        return delegate.execute(request)
    }

    override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
        urls += url
        delegate.download(url, dest, onProgress)
    }
}
