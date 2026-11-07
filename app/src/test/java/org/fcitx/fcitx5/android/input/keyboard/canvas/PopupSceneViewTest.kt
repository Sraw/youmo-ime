/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A popup's scene as a view: sized to it and the room round it its shadow takes. */
@RunWith(RobolectricTestRunner::class)
// the scenes read the theme's look (ThemeManager, so AppPrefs)
@Config(application = FcitxApplication::class)
class PopupSceneViewTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun scene(shadow: Double) = Scene(
        40.0, 50.0,
        listOfNotNull(Op.Shadow(Shape.Rect(Box(0.0, 0.0, 40.0, 50.0)), shadow, Argb(0x40000000)).takeIf { shadow > 0 }),
        emptyList(), emptyList(),
    )

    // a scene of the same size with a shadow reaching further is a view of another size: laid out again
    @Test
    fun aShadowsReachOfItsOwnLaysItOutAgain() {
        val view = PopupScenes(View(context), ThemePreset.PixelDark).view(context)
        view.scene = scene(0.0)
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertFalse(view.isLayoutRequested)
        val bare = view.outset.left
        view.scene = scene(8.0)
        assertTrue(view.outset.left > bare)
        assertTrue(view.isLayoutRequested)
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        assertEquals(40 + view.outset.left + view.outset.right, view.measuredWidth)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        // the same again (a focus moved): drawn, not laid out
        view.scene = scene(8.0)
        assertFalse(view.isLayoutRequested)
    }
}
