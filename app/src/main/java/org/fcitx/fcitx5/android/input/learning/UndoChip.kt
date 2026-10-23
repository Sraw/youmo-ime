/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.Contrast
import org.fcitx.fcitx5.android.input.NoticeState
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.input.UndoChipPlacement.Slide
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.utils.interpolator
import org.fcitx.fcitx5.android.utils.motionSettings
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.gravityCenterVertical
import splitties.views.setPaddingDp

/**
 * The keyboard's one notice (dev/design/motion-notices-handle.md §3): what was done, said for a
 * moment, with 撤销 when it can be taken back. A window of its own, not focusable, where [place]
 * says, so the editor keeps the focus and what is composing in it. It slides out of the keyboard
 * as it comes and back into it as it goes; a tap puts it away. One at a time: a new one takes the
 * place of the one shown, its words crossfading in the same box as the box eases to their size.
 * What it is shown for, which of an act's ends is reached and when it goes: [NoticeState]; here the drawing.
 */
class UndoChip(
    private val anchor: View,
    private val theme: Theme,
    /** Where a chip that big goes and which way it slides out; [show]'s aboveKeys passed on. */
    private val place: (width: Int, height: Int, aboveKeys: Boolean) -> UndoChipPlacement.Place,
    /** Animation on or off, read as each starts: either may change while the keyboard is up. */
    private val motion: () -> Motion.Settings = ::motionSettings,
) {
    /**
     * What 撤销 takes back, and [lapsed] what follows when the chip goes without it: timed out,
     * tapped away, another notice in its place, the keyboard gone. One of the two is called, once,
     * on the main thread.
     */
    class Undo(val undo: () -> Unit, val lapsed: () -> Unit = {})

    private var window: PopupWindow? = null

    // the window's content: its background, and the rows of what it says (two while one replaces another)
    private var box: FrameLayout? = null

    // the row shown, the newest
    private var row: View? = null

    private val state = NoticeState<Undo>()

    private var slide = Slide.Up

    // where the window is and how big, as last set
    private var x = 0
    private var y = 0
    private var w = 0
    private var h = 0

    private var animator: ValueAnimator? = null

    private val timeout = Runnable { dismiss(animated = true) }

    /** Whether one is up, coming or going. */
    val showing: Boolean get() = window?.isShowing == true

    /**
     * [message] said, with 撤销 doing [undo] if there is one. [aboveKeys]: docked, above the keyboard
     * whether or not the bar shows candidates (what the keyboard says, BaseInputView.say), not over the bar.
     * [steady]: kept as keys are typed ([typed]), what the engine says as a key fails: the key it
     * failed on comes to the keyboard after it, and would put it away unseen. False if not shown
     * (the keyboard not on the screen): [undo] lapsed, the words for the caller to say otherwise.
     */
    fun show(message: String, undo: Undo? = null, aboveKeys: Boolean = false, steady: Boolean = false): Boolean {
        anchor.removeCallbacks(timeout)
        val (replaced, shownMs) = state.show(undo, steady)
        // the one shown goes now, untaken: its act stands
        replaced?.lapsed?.invoke()
        // a keyboard hidden is still attached: a result coming after it went is not shown on it
        if (!anchor.isAttachedToWindow || !anchor.isShown) {
            state.dismiss()?.lapsed?.invoke()
            gone()
            return false
        }
        val row = row(message, undo)
        val to = place(row.layoutParams.width, row.layoutParams.height, aboveKeys)
        val shown = window
        val replacing = shown != null && shown.isShowing
        if (replacing) replace(shown, row, to) else open(row, to)
        // said aloud as a pane that appears, the window no focus a screen reader follows; one in
        // the place of another (the same words too) appears again, not a title changed in silence
        box?.let {
            if (replacing) ViewCompat.setAccessibilityPaneTitle(it, null)
            ViewCompat.setAccessibilityPaneTitle(it, message)
        }
        val shownFor = anchor.context.getSystemService(AccessibilityManager::class.java)?.let { a ->
            // as long as its user needs
            a.getRecommendedTimeoutMillis(shownMs.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong()
        } ?: shownMs
        anchor.postDelayed(timeout, shownFor)
        return true
    }

    /** A key typed, a word put in: put away as a tap would, unless [show] said steady or it is going already. */
    fun typed() {
        if (state.typed()) dismiss(animated = true)
    }

    /** Put away: [animated], back into the keyboard; else at once (the keyboard itself going). */
    fun dismiss(animated: Boolean = false) {
        anchor.removeCallbacks(timeout)
        state.dismiss()?.lapsed?.invoke()
        val shown = window ?: return
        val box = box ?: return gone()
        val ms = if (animated && shown.isShowing) Motion.Short.exit(motion()) else 0L
        val fromAlpha = box.alpha
        val fromX = box.translationX
        val fromY = box.translationY
        val (toX, toY) = offset()
        animate(ms, Motion.Curve.Exit, end = ::gone) { t ->
            box.alpha = fromAlpha * (1 - t)
            box.translationX = fromX + (toX - fromX) * t
            box.translationY = fromY + (toY - fromY) * t
        }
    }

    private fun gone() {
        stop()
        state.gone()
        window?.dismiss()
        window = null
        box = null
        row = null
    }

    private fun open(row: View, to: UndoChipPlacement.Place) {
        val box = FrameLayout(anchor.context).apply {
            background = GradientDrawable().apply {
                setColor(theme.popupBackgroundColor)
                setStroke(dp(1), theme.dividerColor)
                cornerRadius = dp(CORNER_DP).toFloat()
            }
            // one that only says goes when tapped (the user, 2026-10-22); one with 撤销 too, off it
            setOnClickListener { dismiss(animated = true) }
            addView(row)
        }
        this.box = box
        this.row = row
        slide = to.slide
        x = to.left
        y = to.top
        w = row.layoutParams.width
        h = row.layoutParams.height
        window = PopupWindow(box, w, h, false).apply {
            isTouchable = true
            // the window's own animation off: the box's below is the one
            animationStyle = 0
            showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
        }
        // out of the keyboard: from the offset toward it, faded in. The window is the chip's size,
        // so what has not slid out yet is cut off at its edge, as if from behind it
        val (fromX, fromY) = offset()
        box.alpha = 0f
        animate(Motion.Medium.enter(motion()), Motion.Curve.Enter) { t ->
            box.alpha = t
            box.translationX = fromX * (1 - t)
            box.translationY = fromY * (1 - t)
        }
    }

    // the new row over the old, the window eased to its size and place, the old faded out
    private fun replace(shown: PopupWindow, row: View, to: UndoChipPlacement.Place) {
        val box = box ?: return
        stop()
        val old = this.row
        // what fades is said no more: its 撤销, lapsed already, takes nothing back (its handler sees
        // the Undo is not pending) but still takes the tap, and the row takes any other: a finger
        // reaching for the old 撤销 must not fall through to the box, which taps the new one away
        old?.tag = null
        (old as? ViewGroup)?.touchDelegate = null
        old?.isClickable = true
        // a third before the second was in: only the one most shown fades
        for (i in box.childCount - 1 downTo 0) {
            if (box.getChildAt(i) !== old) box.removeViewAt(i)
        }
        row.alpha = 0f
        box.addView(row)
        this.row = row
        slide = to.slide
        val from = UndoChipPlacement.Box(x, y, x + w, y + h)
        val target = UndoChipPlacement.Box(to.left, to.top, to.left + row.layoutParams.width, to.top + row.layoutParams.height)
        val oldAlpha = old?.alpha ?: 0f
        // a chip on its way out comes back
        val boxAlpha = box.alpha
        val boxX = box.translationX
        val boxY = box.translationY
        animate(Motion.Short.enter(motion()), Motion.Curve.Move, end = { old?.let(box::removeView) }) { t ->
            x = lerp(from.left, target.left, t)
            y = lerp(from.top, target.top, t)
            w = lerp(from.width, target.width, t)
            h = lerp(from.height, target.height, t)
            shown.update(x, y, w, h)
            old?.alpha = oldAlpha * (1 - t)
            row.alpha = t
            box.alpha = boxAlpha + (1 - boxAlpha) * t
            box.translationX = boxX * (1 - t)
            box.translationY = boxY * (1 - t)
        }
    }

    // where the chip starts out from, and goes back to: toward the keyboard it slides out of
    private fun offset(): Pair<Float, Float> {
        val d = anchor.dp(Motion.NOTICE_SLIDE_DP).toFloat()
        return when (slide) {
            Slide.Up -> 0f to d
            Slide.Down -> 0f to -d
            Slide.Left -> d to 0f
            Slide.Right -> -d to 0f
        }
    }

    private fun lerp(from: Int, to: Int, t: Float) = from + ((to - from) * t).toInt()

    // [frame] from 0 to 1 over [ms], then [end]; 0 ms (animation off) at once, no animator
    private fun animate(ms: Long, curve: Motion.Curve, end: () -> Unit = {}, frame: (Float) -> Unit) {
        stop()
        if (ms <= 0L) {
            frame(1f)
            end()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = curve.interpolator
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    end()
                }
            })
            start()
        }
    }

    // the one running left where it is, its end not run: what follows starts from there
    private fun stop() {
        animator?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        animator = null
    }

    private fun row(message: String, undo: Undo?): View {
        val ctx = anchor.context
        val text = ctx.textView {
            text = message
            setTextColor(theme.popupTextColor)
            textSize = 14f
            // the line beside 撤销 is the gap between them
            setPaddingDp(16, 10, if (undo == null) 16 else 12, 10)
        }
        val row = ctx.horizontalLayout {
            gravity = gravityCenterVertical
            add(text, lParams(wrapContent, wrapContent))
        }
        // as wide as the screen less a margin each side: a long one wraps rather than going off it
        var room = anchor.rootView.width - 2 * ctx.dp(MARGIN_DP)
        if (undo != null) {
            val line = View(ctx).apply { setBackgroundColor(theme.dividerColor) }
            val button = undoButton(undo)
            row.add(line, row.lParams(ctx.dp(1), ctx.dp(DIVIDER_DP)))
            // to the chip's end: a thumb a little off 撤销 still takes back, it does not tap the chip
            // away (which lapses the act, and purges the clipboard's deletes for good)
            row.add(button, row.lParams(row.wrapContent, row.wrapContent))
            row.tag = button
            row.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                // none once replaced: its 撤销 is let go of
                val taker = v.tag as? View ?: return@addOnLayoutChangeListener
                // from the words' end padding to the chip's end, the divider within: 撤销 is at the
                // start in a right-to-left language
                val area = if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    Rect(0, 0, text.left + text.paddingEnd, bottom - top)
                } else {
                    Rect(text.right - text.paddingEnd, 0, right - left, bottom - top)
                }
                (v as ViewGroup).touchDelegate = TouchDelegate(area, taker)
            }
            button.measure(UNSPECIFIED, UNSPECIFIED)
            room -= button.measuredWidth + ctx.dp(1)
        }
        text.maxWidth = room.coerceAtLeast(ctx.dp(MIN_TOUCH_DP))
        row.measure(UNSPECIFIED, UNSPECIFIED)
        // its own size whatever the box's: as the box eases from one to another, neither row re-wraps
        row.layoutParams = FrameLayout.LayoutParams(row.measuredWidth, row.measuredHeight, Gravity.CENTER)
        return row
    }

    // a text button in the accent colour, a rounded ripple as it is pressed: no block of colour (§4)
    private fun undoButton(undo: Undo) = anchor.context.textView {
        setText(R.string.learning_undo)
        setTextColor(undoColour(theme))
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14f
        setPaddingDp(12, 0, 16, 0)
        // a button to a screen reader, and one a finger finds (review-E-ui S7)
        gravity = gravityCenter
        minHeight = dp(MIN_TOUCH_DP)
        minWidth = dp(MIN_TOUCH_DP)
        accessibilityDelegate = ButtonRole
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(CORNER_DP - RIPPLE_INSET_DP).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), null, InsetDrawable(mask, dp(RIPPLE_INSET_DP)))
        setOnClickListener {
            // one replaced, fading, has lapsed: its act stands, and the notice now shown stays
            if (!state.undo(undo)) return@setOnClickListener
            // taken, not lapsed
            dismiss(animated = true)
            undo.undo()
        }
    }

    // a TextView read as a button
    private object ButtonRole : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    internal companion object {
        // the smallest a target is to be touched surely (Material's accessibility guidance)
        const val MIN_TOUCH_DP = 48

        const val CORNER_DP = 18
        const val RIPPLE_INSET_DP = 4
        const val DIVIDER_DP = 20
        const val MARGIN_DP = 16

        /**
         * 撤销's colour: the theme's accent, toward the popup's text till it reads at 4.5:1 on the
         * popup's colour (MaterialLight's accent there is 1.75:1, PixelLight's 3.07).
         */
        fun undoColour(theme: Theme): Int =
            Contrast.legible(theme.accentKeyBackgroundColor, theme.popupBackgroundColor, theme.popupTextColor)

        // read when measured, not as the class loads: undoColour is called with no Android under it
        private val UNSPECIFIED get() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    }
}
