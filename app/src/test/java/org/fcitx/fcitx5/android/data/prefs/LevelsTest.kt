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
import org.junit.Assert.assertNotEquals
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

    @Test
    fun keysOfOtherUnitsWeighAlike() {
        // as the vibration's: ms, and amplitudes of 0..255 where 0 is the device's own
        val vibration = ManagedPreferenceUi.Levels(
            R.string.vibration_strength, "vibration_level", store,
            listOf("press" to 0, "long_press" to 0, "press_amplitude" to 0, "long_press_amplitude" to 0),
            listOf(
                Level(R.string.system_default, 0, 0, 0, 0),
                Level(R.string.level_light, 10, 20, 60, 90),
                Level(R.string.level_medium, 20, 30, 128, 160),
                Level(R.string.level_strong, 35, 45, 220, 255),
            )
        )
        // an older version's 35/45 ms, which vibrates: not shown as the system's, nor left so when it is picked
        store.edit {
            putInt("press", 35)
            putInt("long_press", 45)
        }
        assertNotEquals(0, vibration.current())
        assertTrue(vibration.pick(0))
        assertEquals(0 to 0, store.getInt("press", -1) to store.getInt("long_press", -1))
    }
}
