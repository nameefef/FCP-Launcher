// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.auth

import java.util.UUID

/**
 * Offline accounts match HMCL `OfflineAccount` and Fold Craft Launcher:
 * a local name, no password, and the Minecraft offline UUID
 * (`UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`).
 * This is not a cracked-online session and does not talk to Mojang.
 */
object OfflineAccounts {
    const val MAX_NAME_LENGTH = 16

    fun uuidFor(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()

    fun create(rawName: String): OfflineCreateResult {
        val name = rawName.trim()
        if (name.isEmpty()) return OfflineCreateResult.Invalid("请输入角色名")
        if (name.length > MAX_NAME_LENGTH) {
            return OfflineCreateResult.Invalid("离线角色名最多 $MAX_NAME_LENGTH 个字符")
        }
        if (name.any { it.isISOControl() || it == '/' || it == '\\' }) {
            return OfflineCreateResult.Invalid("角色名包含不能使用的字符")
        }
        val uuid = uuidFor(name)
        return OfflineCreateResult.Ok(
            StoredAccount.Offline(
                id = "offline:$uuid",
                name = name,
                uuid = uuid,
            ),
        )
    }
}

sealed class OfflineCreateResult {
    data class Ok(val account: StoredAccount.Offline) : OfflineCreateResult()
    data class Invalid(val message: String) : OfflineCreateResult()
}
