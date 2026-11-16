/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Levels and Choice, stored nowhere of their own, read what they show as their page opens: that is never
 * written back, even when what is stored changes between the read and the write (the settings search makes
 * them off the main thread while the keyboard may change a level or a style). The user's pick still is.
 */
@RunWith(RobolectricTestRunner::class)
class OpeningWritesNothingTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    // added to a screen as a page's or the index's are: its initial value read then
    private fun open(pref: ListPreference) =
        PreferenceManager(context).createPreferenceScreen(context).addPreference(pref)

    @Test
    fun aLevelChangedWhileItOpensIsNotWrittenBack() {
        val real = context.getSharedPreferences("opening", Context.MODE_PRIVATE)
        var reads = 0
        var edits = 0
        // each read of the two keys (one current()) other than the last: the keyboard set the next level between
        val moving = object : SharedPreferences by real {
            override fun getInt(key: String?, defValue: Int) = real.getInt(key, defValue) + if (reads++ / 2 % 2 == 1) 8 else 0

            override fun edit(): SharedPreferences.Editor = real.edit().also { edits++ }
        }
        val levels = ManagedPreferenceUi.Levels(
            R.string.keyboard_height, "height_level", moving,
            listOf("height" to 30, "height_landscape" to 49),
            listOf(Level(R.string.level_low, 26, 42), Level(R.string.level_standard, 30, 49), Level(R.string.level_taller, 38, 60)),
        )
        val pref = levels.createUi(context)
        open(pref)
        assertTrue("read $reads times", reads >= 2)
        assertEquals(0, edits)
        // the user's pick is written
        pref.value = "0"
        assertEquals(1, edits)
        assertEquals(26, real.getInt("height", 0))
    }

    @Test
    fun aChoiceChangedWhileItOpensIsNotWrittenBack() {
        var reads = 0
        val picked = mutableListOf<Int>()
        // 0 read as it opens, 1 by the time it would write
        val choice = ManagedPreferenceUi.Choice(
            R.string.keyboard_style_portrait, "style_list",
            listOf(R.string.keyboard_style_split, R.string.keyboard_style_split, R.string.keyboard_style_split),
            current = { if (reads++ == 0) 0 else 1 },
            pick = { _, i -> picked += i },
        )
        val pref = choice.createUi(context)
        open(pref)
        assertTrue("read $reads times", reads >= 1)
        assertEquals(emptyList<Int>(), picked)
        pref.value = "2"
        assertEquals(listOf(2), picked)
    }
}
