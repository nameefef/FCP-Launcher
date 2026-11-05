// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.auth

/**
 * LittleSkin's public Yggdrasil root, from their user manual:
 * https://manual.littleskin.cn/yggdrasil/
 *
 * The manual states the API address is `https://littleskin.cn/api/yggdrasil`,
 * and that authlib-injector API Location Indication on `littleskin.cn`
 * resolves to the same root. LittleSkin is a third-party skin site, not
 * Microsoft or Mojang.
 */
object LittleSkin {
    const val API_ROOT = "https://littleskin.cn/api/yggdrasil"
    const val MANUAL = "https://manual.littleskin.cn/yggdrasil/"
    const val PRESET_ID = "littleskin"
}
