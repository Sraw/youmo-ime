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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
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

    /**
     * :app unit tests run without the app's resources (see isIncludeAndroidResources in
     * build.gradle.kts), yet every preference resolves its title eagerly; answer string lookups
     * with "#<id>" so titles can still be compared.
     */
    private class PlaceholderStrings(base: Context) : ContextWrapper(base) {
        private val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = placeholder(id)
            override fun getString(id: Int): String = placeholder(id)
            override fun getString(id: Int, vararg formatArgs: Any?): String = placeholder(id)
        }

        override fun getResources(): Resources = res
    }

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
        fun placeholder(id: Int) = "#$id"
    }
}
