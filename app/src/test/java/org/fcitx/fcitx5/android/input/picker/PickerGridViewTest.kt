/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Measured before its first size, the list makes keys for what fills it: with rows 0 high none
 * filled it, and the layout manager made and bound a key for every emoji on the main thread.
 * Under the app, whose preferences the adapter and its keys read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PickerGridViewTest {

    @Test
    fun aMeasureBeforeTheFirstSizeMakesKeysForAScreenNotTheWholeList() {
        val grid = PickerGridView(RuntimeEnvironment.getApplication(), TestThemes.PixelDark, PickerGridView.Density.Medium)
        val items = Array(2000) { "e$it" }
        val adapter = PickerGridAdapter(
            grid, KeyActionListener { _, _ -> }, PopupActionListener {}, listOf(PickerData.Category("a") to items),
            "PickerGridViewTest", bordered = false, policy = DefaultPickerPolicy()
        )
        grid.adapter = adapter
        adapter.rebuild()
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST)
        )
        assertTrue("${grid.childCount} views made for ${items.size} keys", grid.childCount in 1 until 200)
    }
}
