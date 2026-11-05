// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.net

import java.io.File

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
)

data class HttpResponse(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val finalUrl: String,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun bodyString(): String = body.toString(Charsets.UTF_8)
}

interface HttpClient {
    fun execute(request: HttpRequest): HttpResponse

    fun download(url: String, dest: File, onProgress: (completed: Long, total: Long) -> Unit)
}

fun HttpClient.get(url: String, followRedirects: Boolean = true): HttpResponse =
    execute(
        HttpRequest(
            method = "GET",
            url = url,
            headers = mapOf("Accept" to "application/json, */*"),
            followRedirects = followRedirects,
        ),
    )

fun HttpClient.postJson(url: String, json: String): HttpResponse =
    execute(
        HttpRequest(
            method = "POST",
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            body = json.toByteArray(Charsets.UTF_8),
        ),
    )
