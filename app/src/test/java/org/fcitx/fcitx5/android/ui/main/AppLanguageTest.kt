/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    private fun tagOf(tag: String) = AppLanguage.tagOf(Locale.forLanguageTag(tag))

    @Test
    fun inAppLanguagesMapToTheirTags() {
        assertEquals("zh-CN", tagOf("zh"))
        assertEquals("zh-CN", tagOf("zh-SG"))
        assertEquals("zh-TW", tagOf("zh-HK"))
        assertEquals("zh-TW", tagOf("zh-Hant-CN"))
        assertEquals("en", tagOf("en-GB"))
        assertEquals("", AppLanguage.tagOf(null))
    }

    @Test
    fun aScriptNamedOutweighsTheRegion() {
        assertEquals("zh-TW", tagOf("zh-Hant"))
        assertEquals("zh-TW", tagOf("zh-Hant-SG"))
        assertEquals("zh-CN", tagOf("zh-Hans-TW"))
        assertEquals("zh-TW", tagOf("zh-MO"))
    }

    @Test
    fun everyLanguageTheDialogOffersIsTickedWhenPicked() {
        // a tag read back as another would tick the wrong entry
        AppLanguage.tags.forEach { assertEquals(it, tagOf(it)) }
    }

    @Test
    fun anotherLanguageKeepsItsOwnTag() {
        // not "": that would show as the phone's, its entry ticked and a pick of it ignored
        assertEquals("de", tagOf("de"))
        assertEquals("ja", tagOf("ja"))
        assertEquals("ja-JP", tagOf("ja-JP"))
        assertEquals(-1, AppLanguage.tags.indexOf(tagOf("de")))
    }

    @Test
    fun anotherLanguageIsNamedInItself() {
        // strict: a name the app has no string for must not reach for its resources
        val ctx = mockk<Context>()
        assertEquals("Deutsch", AppLanguage.name(ctx, "de"))
        assertEquals("Español", AppLanguage.name(ctx, "es"))
    }
}
