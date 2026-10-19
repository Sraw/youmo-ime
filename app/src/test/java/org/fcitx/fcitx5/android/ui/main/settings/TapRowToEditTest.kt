/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** WD-18: tapping a custom phrase's row did nothing; only its pen edited it. */
@RunWith(RobolectricTestRunner::class)
class TapRowToEditTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun aTapOnTheRowEditsAsThePenDoes() {
        var edits = 0
        val pen = View(context).apply { setOnClickListener { edits++ } }
        val row = TextView(context)
        tapRowToEdit(row, pen, multiselect = false)
        assertTrue(row.performClick())
        assertEquals(1, edits)
    }

    @Test
    fun inMultiSelectTheRowKeepsItsOwnTap() {
        var edits = 0
        var ticks = 0
        val pen = View(context).apply { setOnClickListener { edits++ } }
        val row = TextView(context).apply { setOnClickListener { ticks++ } }
        tapRowToEdit(row, pen, multiselect = true)
        row.performClick()
        assertEquals(0, edits)
        assertEquals(1, ticks)
    }
}
