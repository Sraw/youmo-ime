/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManagedPreferenceCategoryTest {

    private lateinit var ctx: Context
    private lateinit var sp: SharedPreferences

    // no app resources in :app unit tests: a string reads "#<id>", as in ManagedPreferenceSectionsTest
    private class PlaceholderStrings(base: Context) : ContextWrapper(base) {
        private val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "#$id"
            override fun getString(id: Int): String = "#$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String = "#$id"
        }

        override fun getResources(): Resources = res
    }

    // a text build's: Voice is not offered
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

    /** [sample]'s list as the settings page shows it, read from what is stored. */
    private fun shown(sample: Sample): ListPreference {
        val screen = PreferenceManager(ctx).apply { sharedPreferencesName = PREFS }.createPreferenceScreen(ctx)
        sample.createUi(screen)
        return screen.findPreference("behavior")!!
    }

    @Test
    fun aValueTheListDoesNotOfferReadsAndShowsAsTheDefault() {
        // a voice build's, carried over by a backup or by the text build installed over it
        sp.edit { putString("behavior", SpaceLongPressBehavior.Voice.name) }
        val sample = Sample(sp)
        assertEquals(SpaceLongPressBehavior.None, sample.behavior.getValue())
        val list = shown(sample)
        assertEquals(SpaceLongPressBehavior.None.name, list.value)
        assertEquals("#${R.string.space_behavior_none}", list.entry.toString())
    }

    @Test
    fun anOfferedValueReadsAndShowsAsStored() {
        sp.edit { putString("behavior", SpaceLongPressBehavior.ShowPicker.name) }
        val sample = Sample(sp)
        assertEquals(SpaceLongPressBehavior.ShowPicker, sample.behavior.getValue())
        assertEquals(SpaceLongPressBehavior.ShowPicker.name, shown(sample).value)
    }

    private companion object {
        const val PREFS = "category-test"
    }
}
