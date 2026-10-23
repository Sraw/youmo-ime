/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A value that can't be read (a key whose type changed between versions, a name no enum has) reads as
 * the default and is written back, instead of crashing the settings or the keyboard after an upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class ManagedPreferenceReadOrResetTest {

    private enum class Mode { On, Off }

    private lateinit var sp: SharedPreferences

    @Before
    fun setUp() {
        sp = RuntimeEnvironment.getApplication().getSharedPreferences("read-or-reset-test", Context.MODE_PRIVATE)
        sp.edit { clear() }
    }

    @Test
    fun aValueOfAnotherTypeReadsAsTheDefaultWrittenBack() {
        sp.edit { putInt("flag", 3) }
        val pref = ManagedPreference.PBool(sp, "flag", true)
        assertEquals(true, pref.getValue())
        assertEquals(true, sp.getBoolean("flag", false))
    }

    @Test
    fun aNameTheCodecCannotDecodeReadsAsTheDefaultWrittenBack() {
        sp.edit { putString("mode", "Bogus") }
        val codec = object : ManagedPreference.StringLikeCodec<Mode> {
            override fun decode(raw: String): Mode = enumValueOf(raw)
        }
        val pref = ManagedPreference.PStringLike(sp, "mode", Mode.Off, codec)
        assertEquals(Mode.Off, pref.getValue())
        assertEquals("Off", sp.getString("mode", null))
    }
}
