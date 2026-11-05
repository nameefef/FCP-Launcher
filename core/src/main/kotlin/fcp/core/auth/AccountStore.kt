// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.auth

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Local account file. Tokens are stored so the user does not have to type a
 * password again; passwords themselves are never written.
 */
class AccountStore(private val file: File) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    fun load(): AccountFile {
        if (!file.isFile) return AccountFile(selectedId = null, accounts = emptyList())
        val root = try {
            JsonParser.parseString(file.readText()).asJsonObject
        } catch (_: Exception) {
            return AccountFile(selectedId = null, accounts = emptyList())
        }
        val accounts = root.getAsJsonArray("accounts").orEmpty().mapNotNull { element ->
            parseAccount(element.asJsonObject)
        }
        val selected = root.get("selectedId")?.asString?.takeIf { id -> accounts.any { it.id == id } }
        return AccountFile(selectedId = selected, accounts = accounts)
    }

    @Synchronized
    fun save(data: AccountFile) {
        file.parentFile?.mkdirs()
        val root = JsonObject()
        root.addProperty("version", 1)
        if (data.selectedId != null) root.addProperty("selectedId", data.selectedId)
        val array = JsonArray()
        data.accounts.forEach { array.add(it.toJson()) }
        root.add("accounts", array)
        val text = gson.toJson(root)
        check(!text.contains("\"password\"")) { "账号文件不能写入密码" }
        file.writeText(text)
    }

    fun upsert(account: StoredAccount, select: Boolean = true): AccountFile {
        val current = load()
        val accounts = current.accounts.filterNot { it.id == account.id } + account
        val selected = if (select) account.id else current.selectedId
        val next = AccountFile(selectedId = selected, accounts = accounts)
        save(next)
        return next
    }

    fun select(id: String): AccountFile {
        val current = load()
        val next = current.copy(selectedId = id.takeIf { selected -> current.accounts.any { it.id == selected } })
        save(next)
        return next
    }

    fun delete(id: String): AccountFile {
        val current = load()
        val accounts = current.accounts.filterNot { it.id == id }
        val selected = current.selectedId?.takeIf { selected -> accounts.any { it.id == selected } }
            ?: accounts.firstOrNull()?.id
        val next = AccountFile(selectedId = selected, accounts = accounts)
        save(next)
        return next
    }
}

data class AccountFile(
    val selectedId: String?,
    val accounts: List<StoredAccount>,
)

sealed class StoredAccount {
    abstract val id: String
    abstract val kindLabel: String
    abstract val profileName: String

    data class Offline(
        override val id: String,
        val name: String,
        val uuid: String,
    ) : StoredAccount() {
        override val kindLabel: String = "离线"
        override val profileName: String = name
    }

    data class Yggdrasil(
        override val id: String,
        val preset: String,
        val apiRoot: String,
        val serverName: String,
        val username: String,
        val accessToken: String,
        val clientToken: String,
        val profileId: String,
        override val profileName: String,
    ) : StoredAccount() {
        override val kindLabel: String = if (preset == LittleSkin.PRESET_ID) "LittleSkin" else "第三方"
    }
}

fun storedYggdrasil(
    preset: String,
    session: YggdrasilSession,
    profile: GameProfile,
): StoredAccount.Yggdrasil = StoredAccount.Yggdrasil(
    id = "yggdrasil:${session.apiRoot}:${profile.id}",
    preset = preset,
    apiRoot = session.apiRoot,
    serverName = session.serverName,
    username = session.username,
    accessToken = session.accessToken,
    clientToken = session.clientToken,
    profileId = profile.id,
    profileName = profile.name,
)

private fun StoredAccount.toJson(): JsonObject = when (this) {
    is StoredAccount.Offline -> JsonObject().apply {
        addProperty("type", "offline")
        addProperty("id", id)
        addProperty("name", name)
        addProperty("uuid", uuid)
    }
    is StoredAccount.Yggdrasil -> JsonObject().apply {
        addProperty("type", "yggdrasil")
        addProperty("id", id)
        addProperty("preset", preset)
        addProperty("apiRoot", apiRoot)
        addProperty("serverName", serverName)
        addProperty("username", username)
        addProperty("accessToken", accessToken)
        addProperty("clientToken", clientToken)
        addProperty("profileId", profileId)
        addProperty("profileName", profileName)
    }
}

private fun parseAccount(json: JsonObject): StoredAccount? = when (json.get("type")?.asString) {
    "offline" -> StoredAccount.Offline(
        id = json.require("id"),
        name = json.require("name"),
        uuid = json.require("uuid"),
    )
    "yggdrasil" -> StoredAccount.Yggdrasil(
        id = json.require("id"),
        preset = json.get("preset")?.asString ?: "custom",
        apiRoot = json.require("apiRoot"),
        serverName = json.get("serverName")?.asString ?: "Yggdrasil",
        username = json.require("username"),
        accessToken = json.require("accessToken"),
        clientToken = json.require("clientToken"),
        profileId = json.require("profileId"),
        profileName = json.require("profileName"),
    )
    else -> null
}

private fun JsonObject.require(name: String): String =
    get(name)?.asString ?: throw IllegalArgumentException("账号文件缺少 $name")

private fun com.google.gson.JsonArray?.orEmpty() = this?.toList() ?: emptyList()
