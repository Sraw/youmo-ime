/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TwinSeekBarPreferenceTest {

    private lateinit var ctx: Context
    private lateinit var sp: SharedPreferences

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { clear() }
    }

    private fun twin(unit: String, defaultLabel: String? = null): TwinSeekBarPreference {
        sp.edit {
            putInt("primary", 6)
            putInt("secondary", 8)
        }
        val screen = PreferenceManager(ctx).apply {
            sharedPreferencesName = PREFS
        }.createPreferenceScreen(ctx)
        return TwinSeekBarPreference(ctx).apply {
            key = "primary"
            secondaryKey = "secondary"
            label = "Portrait"
            secondaryLabel = "Landscape"
            this.unit = unit
            default = 6
            secondaryDefault = 0
            this.defaultLabel = defaultLabel
            // attaching loads both stored values
            screen.addPreference(this)
        }
    }

    private fun summaryOf(pref: TwinSeekBarPreference) =
        TwinSeekBarPreference.SimpleSummaryProvider.provideSummary(pref)

    @Test
    fun summaryNamesBothValues() {
        assertEquals("Portrait 6 % · Landscape 8 %", summaryOf(twin(unit = "%")))
    }

    @Test
    fun summaryHasNoTrailingSpaceWithoutUnit() {
        assertEquals("Portrait 6 · Landscape 8", summaryOf(twin(unit = "")))
    }

    @Test
    fun summaryShowsDefaultLabelForDefaultValue() {
        assertEquals(
            "Portrait System default · Landscape 8 ms",
            summaryOf(twin(unit = "ms", defaultLabel = "System default"))
        )
    }

    private companion object {
        const val PREFS = "twin-test"
    }
}
