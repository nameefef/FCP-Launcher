// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import java.util.Locale

/**
 * Chinese is the default copy, matching the Android `values/` resources.
 * English is used when the locale language is `en`, and as a fallback when a
 * Chinese string is blank.
 */
fun tr(zh: String, en: String): String {
    val english = Locale.getDefault().language.equals("en", ignoreCase = true)
    return if (english) en.ifBlank { zh } else zh.ifBlank { en }
}
