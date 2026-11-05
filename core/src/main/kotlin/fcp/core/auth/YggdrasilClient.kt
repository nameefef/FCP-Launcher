// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fcp.core.net.HttpClient
import fcp.core.net.get
import fcp.core.net.postJson
import java.net.URI
import java.util.UUID

/**
 * authlib-injector / Yggdrasil login used by HMCL and Fold Craft Launcher for
 * third-party servers. v0.1 highlights LittleSkin and still accepts another
 * API root the same way FCL's external-auth form does.
 *
 * Passwords are sent only to the resolved server and are not returned or stored.
 * This is not Microsoft account login.
 */
class YggdrasilClient(private val http: HttpClient) {
    fun fetchMetadata(apiRoot: String): YggdrasilServer {
        val root = normalizeHttps(apiRoot)
        val response = http.get(root)
        if (response.code !in 200..299) {
            throw YggdrasilException("无法读取认证服务器（HTTP ${response.code}）")
        }
        return parseMetadata(root, response.bodyString())
    }

    /**
     * Follows authlib-injector's `X-Authlib-Injector-API-Location` header (ALI)
     * when the user pastes a site host instead of the API root.
     */
    fun resolve(rawInput: String): YggdrasilServer {
        var url = normalizeHttps(rawInput)
        repeat(5) {
            val response = http.get(url, followRedirects = false)
            val indicated = response.header("X-Authlib-Injector-API-Location")?.trim()
            if (!indicated.isNullOrEmpty()) {
                return fetchMetadata(absolutize(url, indicated))
            }
            if (response.code in 300..399) {
                val location = response.header("Location")
                    ?: throw YggdrasilException("认证服务器重定向缺少 Location")
                url = absolutize(url, location)
                return@repeat
            }
            if (response.code !in 200..299) {
                throw YggdrasilException("无法读取认证服务器（HTTP ${response.code}）")
            }
            return parseMetadata(url, response.bodyString())
        }
        throw YggdrasilException("认证服务器重定向次数过多")
    }

    fun login(apiRoot: String, username: String, password: String): LoginOutcome {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            throw YggdrasilException("请输入账号和密码")
        }
        val server = fetchMetadata(apiRoot)
        val clientToken = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            add("agent", JsonObject().apply {
                addProperty("name", "Minecraft")
                addProperty("version", 1)
            })
            addProperty("username", user)
            addProperty("password", password)
            addProperty("clientToken", clientToken)
            addProperty("requestUser", true)
        }
        val endpoint = server.apiRoot.trimEnd('/') + "/authserver/authenticate"
        val response = http.postJson(endpoint, payload.toString())
        val body = response.bodyString()
        val json = parseJsonOrNull(body)
        if (response.code !in 200..299) {
            val message = json?.get("errorMessage")?.asString
                ?: "登录失败（HTTP ${response.code}）"
            throw YggdrasilException(message)
        }
        if (json == null) throw YggdrasilException("认证服务器没有返回 JSON")
        if (json.get("error") != null) {
            throw YggdrasilException(json.get("errorMessage")?.asString ?: "登录失败")
        }
        val accessToken = json.get("accessToken")?.asString?.takeIf { it.isNotBlank() }
            ?: throw YggdrasilException("认证服务器没有返回 accessToken")
        val echoedClient = json.get("clientToken")?.asString?.takeIf { it.isNotBlank() } ?: clientToken
        val profiles = json.getAsJsonArray("availableProfiles").orEmpty().map { parseProfile(it.asJsonObject) }
        val selected = json.getAsJsonObject("selectedProfile")?.let { parseProfile(it) }
        val session = YggdrasilSession(
            apiRoot = server.apiRoot,
            serverName = server.serverName,
            username = user,
            accessToken = accessToken,
            clientToken = echoedClient,
            profiles = profiles.ifEmpty { listOfNotNull(selected) },
        )
        val chosen = selected ?: session.profiles.singleOrNull()
        if (chosen != null) return LoginOutcome.Ready(session, chosen)
        if (session.profiles.isEmpty()) throw YggdrasilException("这个账号没有可用角色")
        return LoginOutcome.ChooseProfile(session)
    }

    private fun parseMetadata(apiRoot: String, json: String): YggdrasilServer {
        val root = parseJsonOrNull(json) ?: throw YggdrasilException("认证服务器没有返回 JSON")
        val meta = root.getAsJsonObject("meta")
            ?: throw YggdrasilException("这不是 Yggdrasil API 根地址（响应里没有 meta）")
        val name = meta.get("serverName")?.asString?.takeIf { it.isNotBlank() } ?: "Yggdrasil"
        val register = meta.getAsJsonObject("links")?.get("register")?.asString
        return YggdrasilServer(apiRoot = apiRoot.trimEnd('/'), serverName = name, registerUrl = register)
    }
}

data class YggdrasilServer(
    val apiRoot: String,
    val serverName: String,
    val registerUrl: String?,
)

data class GameProfile(val id: String, val name: String)

data class YggdrasilSession(
    val apiRoot: String,
    val serverName: String,
    val username: String,
    val accessToken: String,
    val clientToken: String,
    val profiles: List<GameProfile>,
)

sealed class LoginOutcome {
    data class Ready(val session: YggdrasilSession, val profile: GameProfile) : LoginOutcome()
    data class ChooseProfile(val session: YggdrasilSession) : LoginOutcome()
}

class YggdrasilException(message: String) : java.io.IOException(message)

fun normalizeHttps(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) throw YggdrasilException("请填写认证服务器地址")
    if (trimmed.startsWith("http://")) {
        throw YggdrasilException("请使用 https 认证服务器")
    }
    val withScheme = if (trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (e: Exception) {
        throw YggdrasilException("认证服务器地址无效")
    }
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
        throw YggdrasilException("认证服务器地址无效")
    }
    return withScheme
}

private fun absolutize(base: String, reference: String): String {
    val resolved = if (reference.startsWith("https://") || reference.startsWith("http://")) {
        reference
    } else {
        URI(base).resolve(reference).toString()
    }
    return normalizeHttps(resolved)
}

private fun parseProfile(json: JsonObject): GameProfile {
    val id = json.get("id")?.asString ?: throw YggdrasilException("角色信息缺少 id")
    val name = json.get("name")?.asString ?: throw YggdrasilException("角色信息缺少名称")
    return GameProfile(id = canonicalUuid(id), name = name)
}

fun canonicalUuid(raw: String): String {
    val hex = raw.replace("-", "").lowercase()
    if (hex.length != 32 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
        throw YggdrasilException("角色 UUID 无效")
    }
    return hex.substring(0, 8) + "-" +
        hex.substring(8, 12) + "-" +
        hex.substring(12, 16) + "-" +
        hex.substring(16, 20) + "-" +
        hex.substring(20)
}

private fun parseJsonOrNull(json: String): JsonObject? = try {
    val element = JsonParser.parseString(json)
    if (element.isJsonObject) element.asJsonObject else null
} catch (_: Exception) {
    null
}

private fun com.google.gson.JsonArray?.orEmpty() =
    this?.map { it } ?: emptyList()
