/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.test.core.app.ApplicationProvider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ShiftKeyNameTest {

    private class Row(val why: String, val locale: String, val name: String)

    private fun names() = ApplicationProvider.getApplicationContext<android.content.Context>().let {
        listOf(it.getString(R.string.a11y_key_shift), it.getString(R.string.fn_key_shift))
    }

    @Test
    @Uat("AX-14")
    fun theShiftKeyIsNamedOnItsCapAndToAScreenReader() = assertRows(
        listOf(
            Row("in Chinese it is named for what it does", "zh-rCN", "大小写"),
            Row("the same in traditional characters", "zh-rTW", "大小寫"),
            Row("English keeps the key's own name", "en", "Shift"),
        ),
        { "${it.locale}: ${it.why}" },
    ) {
        RuntimeEnvironment.setQualifiers(it.locale)
        assertEquals(listOf(it.name, it.name), names())
    }
}
