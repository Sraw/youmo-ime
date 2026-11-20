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
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp

/**
 * The 14 keys' and 双拼's 18: what the leftmost keys not yet taken may be. A tap sends the list's id: shown
 * before a key typed since replaced it, the engine drops it. Gone where the keyboard offers them itself ([owned]).
 */
@SuppressLint("ViewConstructor")
class SyllableStrip(context: Context, private val theme: Theme) : HorizontalScrollView(context) {

    private val textColor = SceneBar.of(context, theme).preedit.color.value

    var onPick: ((KeyAction) -> Unit)? = null

    /** The floating strip is there for it alone while the code is in the app's field. */
    var onShownChanged: (() -> Unit)? = null

    /** As 九键's column, the listener giving none of its own. */
    internal var feedback: (View) -> Unit = {
        InputFeedbacks.hapticFeedback(it)
        InputFeedbacks.soundEffect(SoundKey.LETTER)
    }

    private val items = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private var state = EngineState.SyllableState()

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
        // no parent of the row's yet: nothing to keep for the preedit
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val cap = (MeasureSpec.getSize(widthMeasureSpec) * MAX_SHARE).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST), heightMeasureSpec)
    }

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.syllables.collectLatest(::show) }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    internal fun show(state: EngineState.SyllableState) {
        // the grouped session answers as its input method (the router's), the full keyboard's never has syllables
        val grouped = state.im == InputMethodCatalog.PINYIN || state.im == InputMethodCatalog.SHUANGPIN
        val labels = if (grouped && state.composing && !owned) state.syllables else emptyList()
        this.state = state
        // every input method's state comes here: hidden and staying so, nothing to cancel or redraw
        if (labels.isEmpty() && !isVisible) return
        // a click released but not run yet (View posts it on ACTION_UP) would take what now sits there
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            items.dispatchTouchEvent(it)
            it.recycle()
        }
        items.cancelPendingInputEvents()
        val was = isVisible
        isVisible = labels.isNotEmpty()
        if (was != isVisible) onShownChanged?.invoke()
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

    // the keyboard's views are not under an AppCompat theme, its tinting would add nothing
    @SuppressLint("AppCompatCustomView")
    private fun item(i: Int) = object : TextView(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
        }
    }.apply {
        // the row's height is fixed
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(KawaiiBarComponent.MIN_TOUCH_DP)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        background = pressHighlightDrawable(theme.keyPressHighlightColor)
        isClickable = true
        setOnClickListener {
            feedback(this)
            onPick?.invoke(pick(state, i))
        }
    }

    companion object {
        const val MAX_SHARE = 0.6f

        internal fun pick(state: EngineState.SyllableState, i: Int): KeyAction =
            KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(i, state.id).code), KeyStates.Virtual)
    }
}
