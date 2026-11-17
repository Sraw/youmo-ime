/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The empty clipboard's page: its icon and words 16 dp apart, as before the icon could shrink (R26 F8363: 32 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ClipboardInstructionUiTest {

    @Test
    fun theIconAndTheWordsAre16DpApart() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = ClipboardInstructionUi.Empty(ctx, ThemeManager.activeTheme).root as ViewGroup
        val exactly = { px: Int -> View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY) }
        root.measure(exactly(1000), exactly(800))
        root.layout(0, 0, 1000, 800)
        val children = (0 until root.childCount).map(root::getChildAt)
        val icon = children.filterIsInstance<ImageView>().single()
        val words = children.filterIsInstance<TextView>().single()
        assertEquals((16 * ctx.resources.displayMetrics.density).toInt(), words.top - icon.bottom)
    }
}
