/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.engine.assertRows
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

    private class Row(val why: String, val stored: SharedPreferences.Editor.() -> Unit, val read: () -> Any?, val written: () -> Any?, val default: Any)

    @Test
    fun anUnreadableValueReadsAsTheDefaultWrittenBack() {
        val codec = object : ManagedPreference.StringLikeCodec<Mode> {
            override fun decode(raw: String): Mode = enumValueOf(raw)
        }
        assertRows(
            listOf(
                Row(
                    "a key whose type changed between versions", { putInt("flag", 3) },
                    { ManagedPreference.PBool(sp, "flag", true).getValue() }, { sp.getBoolean("flag", false) }, true,
                ),
                Row(
                    "a name the codec cannot decode", { putString("mode", "Bogus") },
                    { ManagedPreference.PStringLike(sp, "mode", Mode.Off, codec).getValue() }, { sp.getString("mode", null)?.let(codec::decode) }, Mode.Off,
                ),
            ),
            { it.why },
        ) { row ->
            // each row its own file state, not what the row before left
            sp.edit { clear(); row.stored(this) }
            assertEquals(row.default, row.read())
            assertEquals(row.default, row.written())
        }
    }
}
