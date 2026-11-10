/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import splitties.views.dsl.core.Ui

/**
 * A key's preview bubble: the engine's scene of it drawn (060-render-p2 S2), [sceneView] sized by
 * the engine, its text shown [showScene].
 */
class PopupEntryUi(override val ctx: Context, val scenes: PopupScenes) : Ui {

    var lastShowTime = -1L

    /** The key it is shown over, to size it again as its text changes. */
    val trigger = Rect()

    /** The scene drawn. */
    val sceneView: PopupSceneView = scenes.view(ctx)

    /** The text and size (px; null a character's) the scene shows: what is asked again changes nothing. */
    var sceneText: String? = null
        private set
    var sceneSize: Double? = null
        private set

    override val root: View = sceneView

    /**
     * The scene of [text] at [size] px (null: a character's) over a key [keyWidth] px across: the
     * bubble sized by the engine. False where it could not be made, the scene before kept.
     */
    fun showScene(text: String, size: Double?, keyWidth: Int): Boolean {
        val scene = scenes.made("bubble", scenes.builder()?.preview(text, keyWidth.toDouble(), size)) ?: return false
        sceneText = text
        sceneSize = size
        sceneView.scene = scene
        return true
    }
}
