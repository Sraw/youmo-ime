/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LevelsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = context.getSharedPreferences("levels", Context.MODE_PRIVATE)

    private val levels = ManagedPreferenceUi.Levels(
        R.string.keyboard_height, "height_level", store,
        listOf("height" to 30, "height_landscape" to 49),
        listOf(
            Level(R.string.level_low, 26, 42),
            Level(R.string.level_standard, 30, 49),
            Level(R.string.level_taller, 38, 60),
        )
    )

    @Test
    fun theLevelShownIsTheNearestToWhatTheKeysHold() {
        assertEquals(1, levels.current())
        // set by an older version, a number at a time
        store.edit {
            putInt("height", 36)
            putInt("height_landscape", 58)
        }
        assertEquals(2, levels.current())
    }

    @Test
    fun aLevelPickedSetsEveryKeyAndTheNearestShownWritesNothing() {
        assertTrue(levels.pick(0))
        assertEquals(26 to 42, store.getInt("height", 0) to store.getInt("height_landscape", 0))
        store.edit { putInt("height", 27) }
        // what the list shows when the page opens: the nearest, left as it is
        assertTrue(levels.pick(0))
        assertEquals(27, store.getInt("height", 0))
        assertFalse(levels.pick(3))
    }
}
