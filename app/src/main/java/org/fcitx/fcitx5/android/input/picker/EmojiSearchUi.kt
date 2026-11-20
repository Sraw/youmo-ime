/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.SceneKeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp

/** Over the panel while the user searches; its keys' actions go to the panel, which keeps the query. */
@SuppressLint("ViewConstructor")
class EmojiSearchUi(context: Context, private val theme: Theme) : LinearLayout(context) {

    class Keyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme) {

        // ⌫'s commands act on the query (PickerWindow)
        override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
            SceneEnvs.of(this, look, theme.isDark, width, height, Input(), texts = Texts(back = context.getString(R.string.picker_back)), marks = keyMarks(layout))

        // laid on the search's own background, as its views were
        override val sceneDrawsBase: Boolean get() = false

        // ⌫ held is told apart from a tap, so the panel clears the query and stops short of the field
        override val sceneTable: SceneKeyDef.Table get() = SearchKeys

        private object SearchKeys : SceneKeyDef.Table {
            override fun repeat(press: KeyAction, node: KeyNode): KeyAction =
                if (press is KeyAction.SymAction && press.sym == BACKSPACE) press.copy(states = REPEAT) else press
        }

        // last: the scene reads the keyboard's state
        init {
            drawKeysWithScene(LAYOUT)
        }
    }

    private val keyRipple = ThemeManager.shownLook(theme).keys.press == PressEffect.Ripple

    private val query = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setPadding(dp(12), 0, dp(8), 0)
    }

    private val row = LinearLayout(context).apply { orientation = HORIZONTAL }

    private val results = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    val keyboard = Keyboard(context, theme)

    var onPick: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardColor)
        // its keys below are not pressed through the gaps
        isClickable = true
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.barColor)
            addView(query, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(results, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, 0, ROWS.toFloat()))
        show("", emptyList())
    }

    fun show(typed: String, emoji: List<String>) {
        query.text = typed.ifEmpty { context.getString(R.string.emoji_search_hint) }
        query.setTextColor(
            when {
                typed.isEmpty() -> theme.altKeyTextColor
                // letters no word starts with
                emoji.isEmpty() -> theme.keyTextColor.alpha(NOTHING)
                else -> theme.keyTextColor
            }
        )
        row.removeAllViews()
        for (e in emoji) {
            row.addView(TextView(context).apply {
                text = e
                textSize = 24f
                gravity = Gravity.CENTER
                background = if (keyRipple) rippleDrawable(theme.keyPressHighlightColor) else null
                if (!keyRipple) foreground = pressHighlightDrawable(theme.keyPressHighlightColor)
                setOnClickListener { onPick?.invoke(e) }
            }, LayoutParams(dp(RESULT_WIDTH), LayoutParams.MATCH_PARENT))
        }
        results.scrollTo(0, 0)
    }

    companion object {
        private const val ROWS = 3
        private const val RESULT_WIDTH = 48
        private const val NOTHING = 0.5f

        val BACKSPACE = KeySym(FcitxKeyMapping.FcitxKey_BackSpace)

        private val REPEAT = KeyStates(KeyState.Virtual, KeyState.Repeat)

        const val LAYOUT = "emoji-search"
    }
}
