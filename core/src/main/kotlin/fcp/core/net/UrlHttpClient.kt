// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.net

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small HTTP client shared by the Android UI and JVM tests.
 * Uses [HttpURLConnection] so the same code runs on API 26 and on a desktop JVM.
 */
class UrlHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val userAgent: String = "FCP-Launcher/0.1 (+https://fcp.easonyin.xyz)",
) : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = open(request.method, request.url, request.followRedirects)
        try {
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(
                code = code,
                headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key },
                body = body,
                finalUrl = connection.url?.toString() ?: request.url,
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit) {
        val connection = open("GET", url, followRedirects = true)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                throw DownloadException("HTTP $code：$url ${detail.take(180)}".trim())
            }
            val total = connection.contentLengthLong
            dest.parentFile?.mkdirs()
            val partial = File(dest.parentFile, dest.name + ".partial")
            try {
                partial.outputStream().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var completed = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            completed += read
                            onProgress(completed, total)
                        }
                    }
                }
                if (dest.exists() && !dest.delete()) {
                    throw DownloadException("无法覆盖已有文件：${dest.path}")
                }
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
            } catch (e: Exception) {
                partial.delete()
                if (e is DownloadException) throw e
                throw DownloadException(e.message ?: "下载中断：$url", e)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, url: String, followRedirects: Boolean): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = followRedirects
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", userAgent)
        return connection
    }
}

open class DownloadException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

class ChecksumMismatchException(
    val url: String,
    val expected: String,
    val actual: String,
) : DownloadException("文件校验失败（SHA-1 不匹配）：$url")
