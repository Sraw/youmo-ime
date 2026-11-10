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
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
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

/**
 * The emoji panel's search: the pinyin typed and the emoji its words stand for above
 * ([org.fcitx.fcitx5.android.engine.phrase.EmojiData.search]), letters below. Over the panel while
 * the user searches; its keys' actions go to the panel ([PickerWindow]), which keeps the query.
 */
@SuppressLint("ViewConstructor")
class EmojiSearchUi(context: Context, private val theme: Theme) : LinearLayout(context) {

    class Keyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme, rows(context)) {

        // 返回 in the app's words; no marks (no key of the search has a shortcut)
        override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
            SceneEnvs.of(this, look, theme.isDark, width, height, Input(), texts = Texts(back = context.getString(R.string.picker_back)), marks = SceneEnvs.NO_MARKS)

        // laid on the search's own background (the keyboard's colour), as its views were
        override val sceneDrawsBase: Boolean get() = false

        // ⌫ held: told apart from a tap ([BACKSPACE]'s Repeat), so the panel clears the query and stops short of the field
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

    private class LetterKey(letter: String) : KeyDef(
        Appearance.Text(displayText = letter, textSize = 23f),
        setOf(Behavior.Press(KeyAction.HostKeyAction(letter))),
        arrayOf(Popup.Preview(letter))
    )

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

    /** What a tap on a result does: the emoji as listed. */
    var onPick: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardColor)
        // over the panel: its keys below are not pressed through the gaps
        isClickable = true
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.barColor)
            addView(query, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(results, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        // as the panel's own keys are to its list
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, 0, ROWS.toFloat()))
        show("", emptyList())
    }

    /** [typed] so far, and the [emoji] it finds. */
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

        private fun rows(context: Context) = listOf(
            "qwertyuiop".map { LetterKey(it.toString()) },
            "asdfghjkl".map { LetterKey(it.toString()) },
            // back to the emoji where the shift is on the letters, so the row spans as theirs
            listOf(
                KeyDef(
                    KeyDef.Appearance.Text(
                        displayText = context.getString(R.string.picker_back),
                        textSize = 16f,
                        textStyle = Typeface.BOLD,
                        percentWidth = 0.15f,
                        variant = KeyDef.Appearance.Variant.Alternative
                    ),
                    setOf(KeyDef.Behavior.Press(KeyAction.EmojiSearchAction(false)))
                )
            ) + "zxcvbnm".map { LetterKey(it.toString()) } + Backspace
        )

        /** Its repeat told apart ([KeyState.Repeat]): a hold clears the query, then stops short of the field. */
        val BACKSPACE = KeySym(FcitxKeyMapping.FcitxKey_BackSpace)

        private val REPEAT = KeyStates(KeyState.Virtual, KeyState.Repeat)

        /** The search's letters' layout, drawn by the scene (060-render-p2 S6). */
        const val LAYOUT = "emoji-search"

        private val Backspace = KeyDef(
            BackspaceKey().appearance,
            setOf(
                KeyDef.Behavior.Press(KeyAction.SymAction(BACKSPACE)),
                KeyDef.Behavior.Repeat(KeyAction.SymAction(BACKSPACE, REPEAT))
            )
        )
    }
}
