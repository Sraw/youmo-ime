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
        listOf("a", "b", "c", "d", "e").forEach(recent::insert)
        assertEquals(listOf("e", "d", "c"), recent.items)
        // picked again, it comes first and none goes
        recent.insert("c")
        assertEquals(listOf("c", "e", "d"), recent.items)
        assertEquals(listOf("c", "e", "d"), RecentlyUsed("emoji", 3).items)
    }

    @Test
    fun aListSavedLongerThanTheLimitIsReadAsItsNewest() {
        prefs.edit { putString("symbol", "[\"a\",\"b\",\"c\",\"d\"]") }
        assertEquals(listOf("d", "c"), RecentlyUsed("symbol", 2).items)
    }
}
