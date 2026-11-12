/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class DialogSeekBarPreferenceTest {

    private fun pref() = DialogSeekBarPreference(RuntimeEnvironment.getApplication())

    // a step below 1, documented as taken for 1, was kept, and the seek bar divided by it

    @Test
    fun aStepBelowOneIsOne() {
        assertEquals(1, pref().apply { step = 0 }.step)
        assertEquals(1, pref().apply { step = -5 }.step)
    }

    @Test
    fun aStepOfOneOrMoreIsKept() {
        assertEquals(1, pref().step)
        assertEquals(5, pref().apply { step = 5 }.step)
    }

    // 按键音音量 plays a key's sound where the slider is let go: the value there, in the preference's
    // units (min and step applied), not the seek bar's raw progress
    @Test
    fun lettingTheSliderGoPassesTheValueThere() {
        val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat)
        val released = mutableListOf<Int>()
        val pref = DialogSeekBarPreference(ctx).apply {
            min = 10
            max = 100
            step = 5
            onRelease = { released += it }
        }
        pref.performClick()
        val bar = seekBarIn(ShadowDialog.getLatestDialog().window!!.decorView)!!
        val listener = shadowOf(bar).onSeekBarChangeListener
        bar.progress = 4
        listener.onStartTrackingTouch(bar)
        assertEquals("nothing plays while the slider is held", emptyList<Int>(), released)
        listener.onStopTrackingTouch(bar)
        assertEquals(listOf(30), released)
    }

    private fun seekBarIn(v: View): SeekBar? = when (v) {
        is SeekBar -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { seekBarIn(v.getChildAt(it)) }
        else -> null
    }
}
