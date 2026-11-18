// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UiLanguageTest {
    @Test
    fun posixLocalesStayChinese() {
        assertNull(UiLanguage.languageOf("C"))
        assertNull(UiLanguage.languageOf("c"))
        assertNull(UiLanguage.languageOf("POSIX"))
        assertNull(UiLanguage.languageOf("C.UTF-8"))
        assertNull(UiLanguage.languageOf("C.utf8"))
        assertNull(UiLanguage.languageOf("  "))
        assertFalse(UiLanguage.useEnglish(lcAll = "C.UTF-8", lang = "C.UTF-8", jvm = Locale.ENGLISH))
        assertFalse(UiLanguage.useEnglish(lcAll = "C", lang = "en_US.UTF-8", jvm = Locale.US))
        assertFalse(UiLanguage.useEnglish(lcAll = null, lang = "POSIX", jvm = Locale.US))
        assertFalse(UiLanguage.useEnglish(lcAll = "", lang = "C.UTF-8", jvm = Locale.US))
    }

    @Test
    fun explicitEnglishIsHonoredAndOtherLanguagesStayChinese() {
        assertTrue(UiLanguage.useEnglish(lcAll = "en_US.UTF-8", lang = "C.UTF-8", jvm = Locale.CHINA))
        assertTrue(UiLanguage.useEnglish(lcAll = null, lang = "en_GB.UTF-8", jvm = Locale.CHINA))
        assertTrue(UiLanguage.useEnglish(lcAll = null, lang = "en", jvm = Locale.CHINA))
        assertFalse(UiLanguage.useEnglish(lcAll = "zh_CN.UTF-8", lang = "en_US.UTF-8", jvm = Locale.US))
        assertFalse(UiLanguage.useEnglish(lcAll = null, lang = "fr_FR.UTF-8", jvm = Locale.US))
        assertEquals("zh", UiLanguage.languageOf("zh_CN.UTF-8"))
        assertEquals("en", UiLanguage.languageOf("en_US.UTF-8"))
    }

    @Test
    fun jvmLocaleIsUsedOnlyWhenTheEnvironmentNamesNothing() {
        assertTrue(UiLanguage.useEnglish(lcAll = null, lang = null, jvm = Locale.US))
        assertTrue(UiLanguage.useEnglish(lcAll = "", lang = "", jvm = Locale.UK))
        assertFalse(UiLanguage.useEnglish(lcAll = null, lang = null, jvm = Locale.SIMPLIFIED_CHINESE))
        assertFalse(UiLanguage.useEnglish(lcAll = null, lang = null, jvm = Locale.ENGLISH))
        assertFalse(UiLanguage.useEnglish(lcAll = null, lang = null, jvm = Locale.ROOT))
    }
}
