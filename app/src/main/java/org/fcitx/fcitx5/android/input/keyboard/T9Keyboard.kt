/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.core.add
import splitties.views.existingOrNewId

/**
 * Pinyin on nine keys (九键), laid out as 搜狗 does: a column on the left offering what the first
 * digits not yet taken may be (the engine's [EngineBridge.t9] syllables), or punctuation while
 * nothing is typed; 1 is the separator (分词); backspace, start over (重输) and 0 on the right;
 * symbols, numbers, space, the language switch and return below. Swiping a digit key types the
 * digit itself; 1 is the engine's to read, a separator while something is typed, else the digit.
 *
 * A syllable is sent to the engine as a character of [Keyboard.SYLLABLES], the first for the
 * first offered.
 */
@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "T9"

        // the column's share of the width, and of the right one
        private const val SIDE = 0.16f
        private const val RIGHT = 0.18f
        private const val DIGIT = (1f - SIDE - RIGHT) / 3

        /** Shown in the column while nothing is typed, committed as they are. */
        private val PUNCTUATION = listOf("，", "。", "？", "！", "…", "、", "：", "~", "@")

        /** How many items of the column show at once. */
        private const val SHOWN = 4

        private fun digitKey(digit: Char, letters: String) = KeyDef(
            KeyDef.Appearance.AltText(displayText = letters, altText = digit.toString(), textSize = 20f, percentWidth = DIGIT),
            setOf(
                KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(digit.toString())),
                KeyDef.Behavior.Swipe(KeyAction.CommitAction(digit.toString())),
            ),
            arrayOf(KeyDef.Popup.AltPreview(letters, digit.toString())),
        )

        // under the column, never touched: it covers them
        private fun spacer() = KeyDef(
            KeyDef.Appearance.Text("", 16f, percentWidth = SIDE, border = KeyDef.Appearance.Border.Off, margin = false),
            emptySet(),
        )

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                spacer(),
                KeyDef(
                    KeyDef.Appearance.AltText(displayText = "分词", altText = "1", textSize = 16f, percentWidth = DIGIT),
                    setOf(
                        KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("1")),
                        KeyDef.Behavior.Swipe(KeyAction.CommitAction("1")),
                    ),
                ),
                digitKey('2', "ABC"),
                digitKey('3', "DEF"),
                BackspaceKey(RIGHT),
            ),
            listOf(
                spacer(),
                digitKey('4', "GHI"),
                digitKey('5', "JKL"),
                digitKey('6', "MNO"),
                KeyDef(
                    KeyDef.Appearance.Text("重输", 16f, percentWidth = RIGHT, variant = KeyDef.Appearance.Variant.Alternative),
                    setOf(KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape)))),
                ),
            ),
            listOf(
                spacer(),
                digitKey('7', "PQRS"),
                digitKey('8', "TUV"),
                digitKey('9', "WXYZ"),
                KeyDef(
                    KeyDef.Appearance.Text("0", 20f, percentWidth = RIGHT, variant = KeyDef.Appearance.Variant.Alternative),
                    setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("0"))),
                ),
            ),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, SIDE),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.14f),
                SpaceKey(),
                LanguageKey(),
                ReturnKey(RIGHT),
            ),
        )
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val column = object : ScrollView(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            itemHeight = (h - paddingTop - paddingBottom) / SHOWN
            for (i in 0 until items.childCount) items.getChildAt(i).layoutParams.height = itemHeight
            post { items.requestLayout() }
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        val prefs = ThemeManager.prefs
        val h = dp(prefs.keyHorizontalMargin.getValue())
        val v = dp(prefs.keyVerticalMargin.getValue())
        background = insetRadiusDrawable(h, v, dp(prefs.keyRadius.getValue().toFloat()), theme.altKeyBackgroundColor)
        setPadding(h, v, h, v)
        addView(items)
    }

    private var itemHeight = 0

    init {
        add(column, lParams {
            val rows = (0 until childCount).map { getChildAt(it) }.filterIsInstance<ConstraintLayout>()
            topToTop = rows[0].existingOrNewId
            bottomToBottom = rows[2].existingOrNewId
            leftOfParent()
            width = 0
            height = 0
            matchConstraintPercentWidth = SIDE
        })
        show(EngineBridge.T9State())
    }

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineBridge.t9.collectLatest(::show) }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private val columnHit = Rect()

    // the vivo workaround takes every touch for a key: the column's are the column's
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        column.getHitRect(columnHit)
        if (columnHit.contains(ev.x.toInt(), ev.y.toInt())) return false
        return super.onInterceptTouchEvent(ev)
    }

    /** The column for [state]: its syllables while typing, else punctuation. */
    private fun show(state: EngineBridge.T9State) {
        items.removeAllViews()
        val syllables = state.composing && state.syllables.isNotEmpty()
        val labels = if (syllables) state.syllables else PUNCTUATION
        labels.forEachIndexed { i, label ->
            items.addView(item(label) {
                val action = if (syllables) {
                    KeyAction.SymAction(KeySym(UNICODE + Keyboard.SYLLABLES.first.code + i), KeyStates.Virtual)
                } else {
                    KeyAction.CommitAction(label)
                }
                onAction(action)
            })
        }
        column.scrollTo(0, 0)
    }

    private fun item(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemHeight.takeIf { it > 0 } ?: dp(40))
        background = pressHighlight()
        isClickable = true
        setOnClickListener {
            InputFeedbacks.hapticFeedback(this)
            InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            onClick()
        }
    }

    private fun pressHighlight() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        val name = InputMethodNames.of(context, ime)
        space.mainText.text = name
        space.contentDescription = context.getString(R.string.a11y_key_space_with_ime, name)
    }
}

/** fcitx's key for a Unicode character: this plus its code point. */
private const val UNICODE = 0x01000000
