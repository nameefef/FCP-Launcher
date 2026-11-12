// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import java.util.Locale

/**
 * Chinese is the default copy. English is used only when the process locale
 * explicitly names English (`en`, `en_US`, `en_GB`, …).
 *
 * `C`, `POSIX`, and `C.UTF-8` do not name a language. The JVM still reports
 * those as `en` or `en_US` via [Locale.getDefault], so the UI reads `LC_ALL`
 * and then `LANG` before trusting that default. A missing Chinese string
 * falls back to English.
 */
fun tr(zh: String, en: String): String {
    val english = UiLanguage.useEnglish()
    return if (english) en.ifBlank { zh } else zh.ifBlank { en }
}

internal object UiLanguage {
    fun useEnglish(
        lcAll: String? = System.getenv("LC_ALL"),
        lang: String? = System.getenv("LANG"),
        jvm: Locale = Locale.getDefault(),
    ): Boolean {
        val named = sequenceOf(lcAll, lang).firstOrNull { !it.isNullOrBlank() }
        val language = if (named != null) languageOf(named) else languageOfJvm(jvm)
        return language.equals("en", ignoreCase = true)
    }

    /**
     * Language subtag, or null when [raw] is blank or a POSIX locale that
     * does not name one (`C`, `POSIX`, `C.UTF-8`).
     */
    fun languageOf(raw: String): String? {
        val token = raw.trim().substringBefore('.').substringBefore('@').trim()
        if (token.isEmpty()) return null
        val lower = token.lowercase()
        if (lower == "c" || lower == "posix") return null
        val language = lower.substringBefore('_').substringBefore('-')
        if (language.length !in 2..3 || language.any { !it.isLetter() }) return null
        return language
    }

    private fun languageOfJvm(jvm: Locale): String? {
        val language = jvm.language.trim()
        if (language.isEmpty()) return null
        if (language.equals("c", ignoreCase = true) || language.equals("posix", ignoreCase = true)) return null
        // OpenJDK maps C.UTF-8 to a bare `en` with no country. A real English
        // locale from the operating system carries a region (`en_US`) or is
        // caught from LANG above. A country-less `en` is not treated as an
        // explicit English request.
        if (language.equals("en", ignoreCase = true) && jvm.country.isBlank()) return null
        return language
    }
}
