/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.english.EnglishUserStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/** Words removed together from the English page leave the log compacted, as pinyin's do (UserLexicon). */
class EnglishLexiconTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun wordsForgottenTogetherLeaveAStartNothingToReplay() {
        val dir = folder.newFolder()
        val errors = ArrayList<IOException>()
        val english = EnglishLexicon(dir, { errors += it }, Executor { it.run() })
        assertTrue(english.add("hello"))
        assertTrue(english.add("world"))
        english.forget(listOf("hello", "world"))
        checkNotNull(english.store).close()
        english.closed()
        assertEquals(emptyList<IOException>(), errors)
        // the log itself is one compacted with no word in it: replay alone may not show it
        val none = File(folder.newFolder(), Engines.USER_ENGLISH)
        EnglishUserStore(none, EnglishUser()).use {
            it.open()
            it.compact()
        }
        assertArrayEquals(none.readBytes(), File(dir, Engines.USER_ENGLISH).readBytes())
        // the log as appended would make both words again at a start, only to forget them
        val replayed = EnglishUser()
        EnglishUserStore(File(dir, Engines.USER_ENGLISH), replayed).use { it.open() }
        assertEquals(0, replayed.changes)
        assertEquals(emptySet<String>(), replayed.words)
    }
}
