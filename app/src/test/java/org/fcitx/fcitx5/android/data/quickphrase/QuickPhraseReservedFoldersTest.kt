/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import android.content.res.Configuration
import android.os.LocaleList
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * contract: ime-core's list against the app's strings.xml in every language, which mutation cannot see.
 * audit-061 core-input-12: ime-core's folder names a quick-input folder cannot have (QuickPhraseFiles.RESERVED_FOLDERS)
 * are written out there, every language's at once (a file goes to a phone in another language as it is). They are
 * the shelves the page shows, in each language the app has: a shelf renamed in strings.xml and not there fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class QuickPhraseReservedFoldersTest {

    @Test
    fun theReservedFoldersAreThePagesShelvesInEveryLanguage() {
        val app = RuntimeEnvironment.getApplication()
        val shelves = listOf("en", "zh-CN", "zh-TW").flatMap { tag ->
            val ctx = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) })
            listOf(
                ctx.getString(R.string.quick_phrase_all),
                ctx.getString(R.string.quick_phrase_unfiled),
                // its count left out: "待补关键词 (%d)" read unformatted
                ctx.getString(R.string.quick_phrase_keyless_shelf).substringBefore(" (%d)"),
            )
        }.toSet()
        assertEquals(shelves, QuickPhraseFiles.RESERVED_FOLDERS)
    }
}
