/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home page is one flat list in the order decided (2026-10-18), each row opening its page.
 * The data is as old as the day its release was made: an old tree built today is warned of too.
 */
class MainFragmentTest {

    @Test
    fun theHomePageListsThePagesInTheirOrder() {
        assertEquals(
            listOf(
                R.string.input_methods, R.string.shared_input, R.string.word_packs, R.string.theme_and_size,
                R.string.virtual_keyboard, R.string.section_sound_and_vibration, R.string.clipboard_and_emoji,
                R.string.hardware_keyboard, R.string.user_data,
            ),
            MainFragment.HOME.map { it.title },
        )
        assertEquals(
            listOf(
                SettingsRoute.InputMethodList, SettingsRoute.SharedInput, SettingsRoute.PinyinDict(""), SettingsRoute.Theme,
                SettingsRoute.Keys, SettingsRoute.SoundAndVibration, SettingsRoute.Clipboard,
                SettingsRoute.HardwareKeyboard, SettingsRoute.Backup,
            ),
            MainFragment.HOME.map { it.route },
        )
        // each says what is behind it
        MainFragment.HOME.forEach { assertTrue(it.summary != 0) }
    }

    @Test
    fun theDayIsReadFromTheReleasesTag() {
        // 2026-10-05T00:00Z
        assertEquals(1_791_158_400_000L, dataDay("engine-data-20261005-3"))
        assertEquals(1_791_158_400_000L, dataDay("engine-data-20261005"))
    }

    @Test
    fun aTagWithNoDayHasNone() {
        assertNull(dataDay("engine-data-latest"))
        assertNull(dataDay("engine-data-20261399-1"))
    }

    @Test
    fun theReleaseBuiltInHasADay() {
        // a tag renamed out of the pattern would have the warning fall back to the build time unnoticed
        assertNotNull(BuildConfig.ENGINE_DATA, dataDay(BuildConfig.ENGINE_DATA))
    }

    @Test
    fun aMonthsTagIsReadFromItsFirstDay() {
        // 2026-10-01T00:00Z
        assertEquals(1_790_812_800_000L, dataDay("words-202610"))
        assertNull(dataDay("words-202613"))
        assertNotNull(BuildConfig.ENGINE_WORDS, dataDay(BuildConfig.ENGINE_WORDS))
    }

    @Test
    fun theDataIsAsNewAsTheNewestOfItsParts() {
        // a word pack of this month over a model of January
        assertEquals(1_790_812_800_000L, dataDay("engine-data-20260101-1", "words-202610"))
        assertEquals(1_791_158_400_000L, dataDay("engine-data-20261005-3", "words-202610"))
        assertEquals(1_790_812_800_000L, dataDay("engine-data-latest", "words-202610"))
        assertNull(dataDay("engine-data-latest", "words-latest"))
    }
}
