/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManagedPreferenceCategoryTest {

    private lateinit var ctx: Context
    private lateinit var sp: SharedPreferences

    // a list that does not offer every value
    private class Sample(sp: SharedPreferences) : ManagedPreferenceCategory(R.string.virtual_keyboard, sp) {
        val behavior = enumList(
            R.string.space_long_press_behavior,
            "behavior",
            SpaceLongPressBehavior.None,
            entryValues = SpaceLongPressBehavior.entries - SpaceLongPressBehavior.Voice
        )
    }

    @Before
    fun setUp() {
        ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { clear() }
    }

    private fun shown(category: ManagedPreferenceCategory, key: String = "behavior"): ListPreference {
        val screen = PreferenceManager(ctx).apply { sharedPreferencesName = PREFS }.createPreferenceScreen(ctx)
        category.createUi(screen)
        return screen.findPreference(key)!!
    }

    /** As the list's dialog stores a pick. */
    private fun ListPreference.choose(value: String) {
        if (callChangeListener(value)) this.value = value
    }

    // a value the list lacks (a backup's, another build's) shows as the default and is not written over
    @Test
    fun aValueTheListDoesNotOfferReadsAndShowsAsTheDefaultAndIsKept() {
        class Row(val why: String, val stored: String)
        assertRows(
            listOf(
                Row("the enum's value, which the list does not offer (0.5.x's text build and Voice)", SpaceLongPressBehavior.Voice.name),
                // enumValueOf threw IllegalArgumentException on it, failing the settings read
                Row("no name of the enum's at all (a later build's value, an older version installed back)", "SomethingLater"),
            ),
            { it.why },
        ) { row ->
            sp.edit { putString("behavior", row.stored) }
            val sample = Sample(sp)
            assertEquals(SpaceLongPressBehavior.None, sample.behavior.getValue())
            val list = shown(sample)
            assertEquals(SpaceLongPressBehavior.None.name, list.value)
            assertEquals(placeholder(R.string.space_behavior_none), list.entry.toString())
            // shown, not written over
            assertEquals(row.stored, sp.getString("behavior", null))
        }
    }

    @Test
    fun theDefaultPickedOverAValueTheListDoesNotOfferIsStored() {
        sp.edit { putString("behavior", SpaceLongPressBehavior.Voice.name) }
        shown(Sample(sp)).choose(SpaceLongPressBehavior.None.name)
        // the user's None, which a build that offers Voice keeps
        assertEquals(SpaceLongPressBehavior.None.name, sp.getString("behavior", null))
    }

    @Test
    fun anOfferedValueReadsAndShowsAsStored() {
        sp.edit { putString("behavior", SpaceLongPressBehavior.ShowPicker.name) }
        val sample = Sample(sp)
        assertEquals(SpaceLongPressBehavior.ShowPicker, sample.behavior.getValue())
        assertEquals(SpaceLongPressBehavior.ShowPicker.name, shown(sample).value)
    }

    // as the long press's in AppPrefs.Keyboard
    private class Delay(sp: SharedPreferences) : ManagedPreferenceCategory(R.string.virtual_keyboard, sp) {
        init {
            levels(
                R.string.keyboard_long_press_delay, "delay_level",
                listOf("delay" to 300),
                listOf(Level(R.string.level_short, 200), Level(R.string.level_medium, 300), Level(R.string.level_long, 450))
            )
        }
    }

    @Test
    fun theLevelShownForAnOlderValueSetsItsValuesWhenPicked() {
        // an older version's 380 ms, shown as long
        sp.edit { putInt("delay", 380) }
        val list = shown(Delay(sp), "delay_level")
        assertEquals("2", list.value)
        assertEquals("opened, left as it was", 380, sp.getInt("delay", -1))
        list.choose("2")
        assertEquals(450, sp.getInt("delay", -1))
    }

    private class TrailingSection(sp: SharedPreferences) : ManagedPreferenceCategory(R.string.advanced, sp) {
        val first = switch(R.string.disable_animation, "first", false)

        init {
            section(R.string.section_keys)
        }
    }

    @Test
    fun aSectionWithNothingAfterItIsRejected() {
        val category = TrailingSection(sp)
        val screen = PreferenceManager(ctx).createPreferenceScreen(ctx)
        assertThrows(IllegalArgumentException::class.java) { category.createUi(screen) }
    }

    private companion object {
        const val PREFS = "category-test"
    }
}
