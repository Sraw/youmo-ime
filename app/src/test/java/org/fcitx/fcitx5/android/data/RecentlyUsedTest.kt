/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// the real application, whose preferences keep the items; the picker shows every one kept
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class RecentlyUsedTest {

    private val prefs get() = FcitxApplication.getInstance().directBootAwareContext
        .getSharedPreferences(RecentlyUsed.PREFERENCE_NAME, Context.MODE_PRIVATE)

    @Test
    fun theOldestGoOnceThereAreMoreThanTheLimit() {
        val recent = RecentlyUsed("emoji", 3)
        listOf("a", "b", "c", "d", "e").forEach { recent.insert(it, habits = true) }
        assertEquals(listOf("e", "d", "c"), recent.items(habits = true))
        // picked again, it comes first and none goes
        recent.insert("c", habits = true)
        assertEquals(listOf("c", "e", "d"), recent.items(habits = true))
        assertEquals(listOf("c", "e", "d"), RecentlyUsed("emoji", 3).items(habits = true))
    }

    @Test
    fun aListSavedLongerThanTheLimitIsReadAsItsNewest() {
        prefs.edit { putString("symbol", "[\"a\",\"b\",\"c\",\"d\"]") }
        assertEquals(listOf("d", "c"), RecentlyUsed("symbol", 2).items(habits = true))
    }

    // U-67: not while the habits are off; cleared with what was learned, every picker's, the one open too
    @Test
    fun noneWhileTheHabitsAreOffAndAllClearedWithWhatWasLearned() {
        val emoji = RecentlyUsed("emoji", 5)
        emoji.insert("a", habits = true)
        emoji.insert("b", habits = false)
        assertEquals(emptyList<String>(), emoji.items(habits = false))
        assertEquals(listOf("a"), emoji.items(habits = true))
        // kept by an older build, with no times: as of long ago
        prefs.edit { putString("symbol", "[\"x\"]") }
        val symbol = RecentlyUsed("symbol", 5)
        RecentlyUsed.clear(System.currentTimeMillis() - 60_000)
        assertEquals(emptyList<String>(), emoji.items(habits = true))
        assertEquals(listOf("x"), symbol.items(habits = true))
        RecentlyUsed.clear(0)
        assertEquals(emptyList<String>(), RecentlyUsed("symbol", 5).items(habits = true))
    }

    // review-E-engine M1: a picker open while 清掉最近 ran saves what it last read, the cleared items with it, at its next pick
    @Test
    fun aPickAfterAClearKeepsOnlyItself() {
        val open = RecentlyUsed("emoji", 5)
        open.insert("a", habits = true)
        open.insert("b", habits = true)
        RecentlyUsed.clear(0)
        open.insert("c", habits = true)
        assertEquals(listOf("c"), open.items(habits = true))
        assertEquals(listOf("c"), RecentlyUsed("emoji", 5).items(habits = true))
    }
}
