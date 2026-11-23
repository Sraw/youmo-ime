/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class LayoutBoardsTest {

    @get:Rule
    val prefs = PrefsRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun drawn(): List<String> {
        val canvas = LayoutBoards.view(context, ThemeManager.activeTheme, SceneLayouts.board(context, "number")!!) as KeyboardCanvasView
        canvas.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
        canvas.layout(0, 0, WIDTH, HEIGHT)
        return canvas.scene!!.slots.single { it.kind == "scroll" }.ops.filterIsInstance<Op.Text>().map { it.text }
    }

    // no native column draws on a preview: the engine draws the user's list there, else the category's own
    @Test
    fun aPreviewsColumnIsTheUsersListElseTheDefault() {
        prefs.set(AppPrefs.getInstance().internal.scrollLists, "")
        assertEquals(ScrollDefaults.of(ScrollDefaults.NUMBER)!!.take(4).map { (it as ScrollItem.Text).text }, drawn())
        prefs.set(AppPrefs.getInstance().internal.scrollLists, ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Text("#"), ScrollItem.Text("="))).serialize())
        assertEquals(listOf("#", "="), drawn())
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 800
    }
}
