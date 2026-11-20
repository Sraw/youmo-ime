/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FcitxApplicationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("fcitx-application-test", Context.MODE_PRIVATE)

    private fun prefs() = AppPrefs(sp).also { sp.edit { putBoolean("punctuation_reset_061", true) } }

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    @Test
    fun anImportsMarkerClearsThePunctuationMarkAndGoes() {
        val prefs = prefs()
        val marker = folder.newFile("user_data_imported")
        FcitxApplication.afterImport(prefs, directBoot = false) { marker }
        assertFalse(prefs.internal.punctuationReset061.getValue())
        assertFalse(marker.exists())
    }

    @Test
    fun withoutAMarkerNothingIsCleared() {
        val prefs = prefs()
        FcitxApplication.afterImport(prefs, directBoot = false) { folder.root.resolve("user_data_imported") }
        assertTrue(prefs.internal.punctuationReset061.getValue())
    }

    @Test
    fun inDirectBootTheMarkerIsLeftForTheNextStart() {
        val prefs = prefs()
        val marker = folder.newFile("user_data_imported")
        FcitxApplication.afterImport(prefs, directBoot = true) { marker }
        assertTrue(prefs.internal.punctuationReset061.getValue())
        assertTrue(marker.exists())
    }
}
