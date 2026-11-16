/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.input.ThemePictureDrawable
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.layout.Layout

/**
 * A layout drawn by the engine in a theme, nothing typed and nothing pressed (061-custom-layout §4.2, §4.3): the preview
 * above the layouts' page and in an import's confirmation. Its keys are not wired: a touch does nothing.
 */
object LayoutBoards {

    /** What [layout]'s keys are drawn for: a number board's first pad, else the keyboard of pinyin. */
    fun input(layout: Layout): Input =
        if (layout.family == "number") Input(pad = layout.pads?.firstOrNull() ?: "full") else Input()

    /** [layout]'s keys in [theme], filling the view's size. */
    fun view(ctx: Context, theme: Theme, layout: SceneLayout): View {
        val look = ThemeManager.shownLook(theme)
        // a pack's every image, else its kinds' (no key names: none of a board's own)
        val images = (theme as? Theme.Pack)?.let { ThemeAssets.boardImages(look, null, ThemePackFiles.images(it.drawn)) }
            ?: ThemeAssets.boardImages(look, null, KeyStyleResolver.imagesOn(look.keys, null, emptyList()))
        val input = input(layout.layout)
        lateinit var canvas: KeyboardCanvasView
        val board = SceneBoard(ctx, theme, layout) { l, _, w, h -> SceneEnvs.of(canvas, l, theme.isDark, w, h, input) }
        canvas = board.view(images, key = { null })
        // the picture under the keys, as the keyboard's
        canvas.background = ThemePictureDrawable.board(look)
        return canvas
    }
}
