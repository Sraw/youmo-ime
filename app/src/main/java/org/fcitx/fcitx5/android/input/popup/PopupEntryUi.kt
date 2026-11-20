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

class PopupEntryUi(override val ctx: Context, val scenes: PopupScenes) : Ui {

    var lastShowTime = -1L

    /** To size it again as its text changes. */
    val trigger = Rect()

    val sceneView: PopupSceneView = scenes.view(ctx)

    /** Null size: a character's. What is asked again changes nothing. */
    var sceneText: String? = null
        private set
    var sceneSize: Double? = null
        private set

    override val root: View = sceneView

    /** False where it could not be made, the scene before kept. */
    fun showScene(text: String, size: Double?, keyWidth: Int): Boolean {
        val scene = scenes.made("bubble", scenes.builder()?.preview(text, keyWidth.toDouble(), size)) ?: return false
        sceneText = text
        sceneSize = size
        sceneView.scene = scene
        return true
    }
}
