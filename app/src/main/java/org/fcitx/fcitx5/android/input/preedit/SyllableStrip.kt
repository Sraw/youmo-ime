/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp

/**
 * The code row's syllables (拼音选择栏) for the 14 keys and 双拼's 18: what the leftmost keys not yet taken may
 * be, as 九键's column offers them ([EngineBridge.syllables]), in a row at the code row's right end,
 * never over [MAX_SHARE] of its width. A tap takes one ([Keyboard.syllableKey], with the list's id:
 * shown before a key typed since replaced it, the engine drops it). Gone while nothing is typed or
 * nothing is left to take, for any input method but pinyin and 双拼, and where the keyboard shown
 * offers them itself ([owned]): 九键's and 九键双拼's column.
 */
@SuppressLint("ViewConstructor")
class SyllableStrip(context: Context, private val theme: Theme) : HorizontalScrollView(context) {

    /** Where a tap's key goes: the keyboard's listener (PreeditComponent). */
    var onPick: ((KeyAction) -> Unit)? = null

    /** What a tap gives before its key: as 九键's column, the listener giving none of its own. */
    internal var feedback: (View) -> Unit = {
        InputFeedbacks.hapticFeedback(it)
        InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
    }

    private val items = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** What the row shows, read by its items when tapped. */
    private var state = EngineBridge.SyllableState()

    /** Whether the keyboard shown offers the syllables itself (BaseKeyboard.ownsSyllables), as KeyboardWindow tells. */
    var owned: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            show(state)
        }

    init {
        id = R.id.code_row_syllables
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(items, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        isVisible = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // unbounded (no parent of the row's yet): nothing to keep for the preedit
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // the row's width is what the parent offers: the preedit keeps the rest
        val cap = (MeasureSpec.getSize(widthMeasureSpec) * MAX_SHARE).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST), heightMeasureSpec)
    }

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineBridge.syllables.collectLatest(::show) }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    /** The row for [state]: its syllables while pinyin or 双拼 types with some to take, else gone; its items kept. */
    internal fun show(state: EngineBridge.SyllableState) {
        // the grouped session answers as its input method (the router's), the full keyboard's never has syllables
        val grouped = state.im == Engines.PINYIN || state.im == Engines.SHUANGPIN
        val labels = if (grouped && state.composing && !owned) state.syllables else emptyList()
        this.state = state
        // every input method's state comes here: hidden and staying so, nothing to cancel or redraw
        if (labels.isEmpty() && !isVisible) return
        // a press on the list replaced is cancelled, and a click released but not run yet (View
        // posts it on ACTION_UP): its pick would take what now sits there, as 九键's column has it
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            items.dispatchTouchEvent(it)
            it.recycle()
        }
        items.cancelPendingInputEvents()
        isVisible = labels.isNotEmpty()
        while (items.childCount < labels.size) items.addView(item(items.childCount))
        for (i in 0 until items.childCount) {
            val view = items.getChildAt(i) as TextView
            view.isVisible = i < labels.size
            if (view.isVisible) {
                if (view.text.toString() != labels[i]) {
                    view.text = labels[i]
                    view.contentDescription = context.getString(R.string.a11y_syllable, labels[i])
                }
            }
        }
        scrollTo(0, 0)
    }

    private fun item(i: Int) = object : TextView(context) {
        // a button to TalkBack, as it acts as one
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
        }
    }.apply {
        // in dp, as 九键's column: the row's height is fixed
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(KawaiiBarComponent.MIN_TOUCH_DP)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(theme.keyTextColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        background = pressHighlightDrawable(theme.keyPressHighlightColor)
        isClickable = true
        setOnClickListener {
            feedback(this)
            onPick?.invoke(pick(state, i))
        }
    }

    companion object {
        /** The most of the code row's width the syllables take. */
        const val MAX_SHARE = 0.6f

        /** What taking syllable [i] of [state] sends: its key, with the list's id. */
        internal fun pick(state: EngineBridge.SyllableState, i: Int): KeyAction =
            KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(i, state.id).code), KeyStates.Virtual)
    }
}
