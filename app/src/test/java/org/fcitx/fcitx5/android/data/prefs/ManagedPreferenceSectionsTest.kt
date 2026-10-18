/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManagedPreferenceSectionsTest {

    private lateinit var ctx: Context
    private lateinit var sp: SharedPreferences

    @Before
    fun setUp() {
        ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        sp = ctx.getSharedPreferences("sections-test", Context.MODE_PRIVATE)
        sp.edit { clear() }
    }

    private fun newScreen(): PreferenceScreen =
        PreferenceManager(ctx).createPreferenceScreen(ctx)

    private class Sample(sp: SharedPreferences) : ManagedPreferenceCategory(R.string.advanced, sp) {
        val loose = switch(R.string.disable_animation, "loose", false)

        init {
            section(R.string.section_keys)
        }

        val first = switch(R.string.popup_on_key_press, "first", true)
        val second = switch(R.string.expand_keypress_area, "second", false)

        init {
            section(R.string.section_candidates)
        }

        val third = switch(R.string.show_lang_switch_key, "third", true)
    }

    private fun PreferenceGroup.children(): List<Preference> =
        (0 until preferenceCount).map { getPreference(it) }

    private fun PreferenceGroup.allKeys(): List<String> = children().flatMap {
        if (it is PreferenceGroup) it.allKeys() else listOfNotNull(it.key)
    }

    @Test
    fun preferencesBeforeTheFirstSectionStayOnTheScreen() {
        val screen = newScreen()
        Sample(sp).createUi(screen)
        val top = screen.children()
        assertEquals(
            listOf("loose", sectionKey(R.string.section_keys), sectionKey(R.string.section_candidates)),
            top.map { it.key }
        )
    }

    @Test
    fun eachSectionHoldsThePreferencesRegisteredAfterIt() {
        val screen = newScreen()
        Sample(sp).createUi(screen)
        val keys = screen.findPreference<PreferenceCategory>(sectionKey(R.string.section_keys))!!
        assertEquals(placeholder(R.string.section_keys), keys.title)
        assertEquals(listOf("first", "second"), keys.children().map { it.key })
        val rest = screen.findPreference<PreferenceCategory>(sectionKey(R.string.section_candidates))!!
        assertEquals(listOf("third"), rest.children().map { it.key })
        // grouping moves preferences, never drops or duplicates them
        assertEquals(listOf("loose", "first", "second", "third"), screen.allKeys())
    }

    // every preference after a section: what a category shown a section a page must be
    private class Sectioned(sp: SharedPreferences) : ManagedPreferenceCategory(R.string.advanced, sp) {
        init {
            section(R.string.section_keys)
        }

        val first = switch(R.string.popup_on_key_press, "first", true)
        val second = switch(R.string.expand_keypress_area, "second", false)

        init {
            section(R.string.section_candidates)
        }

        val third = switch(R.string.show_lang_switch_key, "third", true)
    }

    // one category over two pages (the keys, the sound and vibration): each its section alone, untitled
    @Test
    fun aSectionAloneIsItsPreferencesWithoutItsTitle() {
        val keys = newScreen()
        Sectioned(sp).createUi(keys, R.string.section_keys)
        assertEquals(listOf("first", "second"), keys.children().map { it.key })
        val rest = newScreen()
        Sectioned(sp).createUi(rest, R.string.section_candidates)
        assertEquals(listOf("third"), rest.children().map { it.key })
    }

    // one before the first section would be on neither page: grouping never drops a preference
    @Test(expected = IllegalArgumentException::class)
    fun aSectionAloneOfACategoryWithPreferencesBeforeItsFirstIsRejected() {
        Sample(sp).createUi(newScreen(), R.string.section_keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSectionTheCategoryHasNotIsRejected() {
        Sectioned(sp).createUi(newScreen(), R.string.section_sound_and_vibration)
    }

    private class EmptySection(sp: SharedPreferences) : ManagedPreferenceCategory(R.string.advanced, sp) {
        init {
            section(R.string.section_keys)
            section(R.string.section_candidates)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun twoSectionsInARowAreRejected() {
        EmptySection(sp)
    }

    private companion object {
        fun sectionKey(title: Int) = ManagedPreferenceCategory.sectionKey(title)
    }
}
