/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.scene.Parsed
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.gravityCenter

/**
 * A key's preview bubble: its views, or, with [scenes], the engine's scene of it drawn (a key the
 * canvas draws, 060-render-p2 S2): [sceneView] then, sized by the engine, its text shown [showScene].
 */
class PopupEntryUi(override val ctx: Context, theme: Theme, keyHeight: Int, radius: Float, private val scenes: PopupScenes? = null) : Ui {

    var lastShowTime = -1L

    /** The key it is shown over, to size it again as its text changes. */
    val trigger = Rect()

    val textView = view(::AutoScaleTextView) {
        // where wider than the bubble can be, not drawn past its sides
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = PreviewLabel.SYMBOL_SP
        gravity = gravityCenter
        setTextColor(theme.popupTextColor)
    }

    /** The scene drawn in the views' place; null, the views. */
    val sceneView: PopupSceneView? = scenes?.view(ctx)

    /** The text and size (px; null a character's) the scene shows: what is asked again changes nothing. */
    var sceneText: String? = null
        private set
    var sceneSize: Double? = null
        private set

    override val root: View = sceneView ?: constraintLayout {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.popupBackgroundColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        add(textView, lParams(matchParent, keyHeight) {
            topOfParent()
            centerHorizontally()
        })
    }

    /** [text] shown at [px]: a character's size or a shortcut's words' ([PreviewLabel]), as the keys' text is scaled. */
    fun setText(text: String, px: Float) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        textView.text = text
    }

    /** The scene of [text] at [size] px (null: a character's) over a key [keyWidth] px across: the bubble sized by the engine. */
    fun showScene(text: String, size: Double?, keyWidth: Int) {
        sceneText = text
        sceneSize = size
        sceneView?.scene = (scenes?.builder()?.preview(text, keyWidth.toDouble(), size) as? Parsed.Ok)?.value
    }

    /** How wide [text] is drawn, in px. */
    fun textWidth(text: String) = textView.paint.measureText(text)
}
