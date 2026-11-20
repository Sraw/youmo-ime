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

/** A layout drawn by the engine as a preview: keys not wired, a touch does nothing. */
object LayoutBoards {

    /** A number board's first pad, else pinyin's keyboard. */
    fun input(layout: Layout): Input =
        if (layout.family == "number") Input(pad = layout.pads?.firstOrNull() ?: "full") else Input()

    fun view(ctx: Context, theme: Theme, layout: SceneLayout): View {
        val look = ThemeManager.shownLook(theme)
        // a pack's every image, else its kinds' (a board has none of its own)
        val images = (theme as? Theme.Pack)?.let { ThemeAssets.boardImages(look, null, ThemePackFiles.images(it.drawn)) }
            ?: ThemeAssets.boardImages(look, null, KeyStyleResolver.imagesOn(look.keys, null, emptyList()))
        val input = input(layout.layout)
        lateinit var canvas: KeyboardCanvasView
        val board = SceneBoard(ctx, theme, layout) { l, _, w, h -> SceneEnvs.of(canvas, l, theme.isDark, w, h, input) }
        canvas = board.view(images, key = { null })
        canvas.background = ThemePictureDrawable.board(look)
        return canvas
    }
}
