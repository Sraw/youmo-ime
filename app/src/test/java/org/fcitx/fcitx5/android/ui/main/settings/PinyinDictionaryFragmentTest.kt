/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Importing the next youmo-new.words as offered keeps it in the new words. The dialog offered the
 * base for every file, so taking the default moved the monthly pack into the base layer. The
 * dictionaries the migration put aside unread are told of: they left the list without a word.
 */
class PinyinDictionaryFragmentTest {

    @Test
    fun aPackReplacingOneInTheNewWordsIsOfferedTheNewWords() {
        assertTrue(offersNewLayer(PackImport.Action.REPLACE, intoNew = true))
    }

    @Test
    fun anythingElseIsOfferedTheBase() {
        assertFalse(offersNewLayer(PackImport.Action.REPLACE, intoNew = false))
        assertFalse(offersNewLayer(PackImport.Action.IMPORT, intoNew = true))
        assertFalse(offersNewLayer(PackImport.Action.REFUSE, intoNew = true))
        assertFalse(offersNewLayer(PackImport.Action.IMPORT, intoNew = false))
    }

    @Test
    fun nothingPutAsideUnreadIsNoNotice() {
        assertNull(unreadNotice(emptyList(), "intro", "outro") { it.fileName })
    }

    @Test
    fun eachDictionaryPutAsideUnreadIsALineOfTheNotice() {
        val unread = listOf(Unread("zhwiki.dict", Unread.Reason.TOO_LARGE), Unread("old.dict.disable", Unread.Reason.DAMAGED))
        assertEquals(
            "intro\nzhwiki.dict TOO_LARGE\nold.dict.disable DAMAGED\noutro",
            unreadNotice(unread, "intro", "outro") { "${it.fileName} ${it.reason}" },
        )
    }
}
