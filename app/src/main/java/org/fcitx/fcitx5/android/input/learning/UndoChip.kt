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
import androidx.annotation.StringRes
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
 * The keyboard's one notice (specs/app.md, "Notices"), with 撤销 when it can be taken back. Its own
 * window, not focusable, so the editor keeps the focus and its composition. A new one takes the
 * place of the one shown, crossfading in the same box. The rules are [NoticeState]; here the drawing.
 */
class UndoChip(
    private val anchor: View,
    private val theme: Theme,
    private val place: (width: Int, height: Int, aboveKeys: Boolean) -> UndoChipPlacement.Place,
    /** Read as each starts: either may change while the keyboard is up. */
    private val motion: () -> Motion.Settings = ::motionSettings,
) {
    /**
     * Exactly one of [undo] and [lapsed] is called, once, on the main thread. [action]: the button's
     * words (撤销, or 语音先问再记's 记住).
     */
    class Undo(val undo: () -> Unit, val lapsed: () -> Unit = {}, @StringRes val action: Int = R.string.learning_undo)

    /** A button of its own after the words, as a table's offered word: [label] shown, [said] to a screen reader. */
    class Choice(val label: String, val said: String, val pick: () -> Unit)

    private var window: PopupWindow? = null

    // two rows while one replaces another
    private var box: FrameLayout? = null

    private var row: View? = null

    private val state = NoticeState<Undo>()

    private var slide = Slide.Up

    private var aboveKeys = false

    private var windowLeft = 0
    private var windowTop = 0
    private var windowWidth = 0
    private var windowHeight = 0

    private var animator: ValueAnimator? = null

    // the choices of the notice shown now
    private var shownChoices: List<Choice> = emptyList()

    private val timeout = Runnable { dismiss(animated = true) }

    // for its time again after [uncovered]
    private var shownFor = 0L

    val showing: Boolean get() = window?.isShowing == true

    /** One said in its place would lapse it. */
    val holdsUndo: Boolean get() = state.holdsUndo

    /**
     * [aboveKeys]: docked above the keyboard, not over the bar. [steady]: kept as keys are typed, for what
     * the engine says as a key fails (the key arrives after it and would put it away unseen). False if not
     * shown: [undo] lapsed, the caller says it otherwise.
     */
    fun show(
        message: String,
        undo: Undo? = null,
        aboveKeys: Boolean = false,
        steady: Boolean = false,
        ms: Long? = null,
        choices: List<Choice> = emptyList(),
    ): Boolean {
        anchor.removeCallbacks(timeout)
        // a keyboard hidden is still attached: a result coming after it went is not shown on it
        if (!anchor.isAttachedToWindow || !anchor.isShown) {
            // the one shown goes untaken: its act stands; this one's lapses unsaid
            state.show(undo, steady).lapsed?.lapsed?.invoke()
            state.dismiss()?.lapsed?.invoke()
            gone()
            return false
        }
        this.aboveKeys = aboveKeys
        shownChoices = choices
        val row = row(message, undo, choices)
        val to = place(row.layoutParams.width, row.layoutParams.height, aboveKeys)
        // said under the edit menu where it meets it, shown as the menu goes
        val hide = menu?.let { Rect.intersects(it, Rect(to.left, to.top, to.left + row.layoutParams.width, to.top + row.layoutParams.height)) } == true
        val (replaced, shownMs) = state.show(undo, steady, hide, ms)
        // the one shown goes now, untaken: its act stands
        replaced?.lapsed?.invoke()
        val shown = window
        val replacing = shown != null && shown.isShowing
        if (replacing) replace(shown, row, to) else open(row, to)
        if (hide || box?.visibility == View.INVISIBLE) shown(!hide)
        // said aloud as a pane appearing (the window is no focus a screen reader follows); a replacement
        // appears again, not a title changed in silence
        box?.let {
            if (replacing) ViewCompat.setAccessibilityPaneTitle(it, null)
            ViewCompat.setAccessibilityPaneTitle(it, message)
        }
        val shownFor = if (shownMs == NoticeState.STAYS) shownMs else anchor.context.getSystemService(AccessibilityManager::class.java)?.let { a ->
            // as long as its user needs: to read it, and to reach its button
            val content = if (undo != null || choices.isNotEmpty()) AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
            else AccessibilityManager.FLAG_CONTENT_TEXT
            a.getRecommendedTimeoutMillis(shownMs.toInt(), content).toLong()
        } ?: shownMs
        this.shownFor = shownFor
        // hidden: timed from when the menu goes ([uncovered])
        if (!hide) timed()
        return true
    }

    /** A steady one stays as keys are typed, and the code strip changing under it would leave it over the code. */
    fun reposition() {
        val shown = window?.takeIf { it.isShowing } ?: return
        val row = row ?: return
        // sliding in or to a new notice: placed again as that ends; going: let go
        if (animator?.isRunning == true) return
        val to = place(row.layoutParams.width, row.layoutParams.height, aboveKeys)
        if (to.left == windowLeft && to.top == windowTop) return
        slide = to.slide
        windowLeft = to.left
        windowTop = to.top
        shown.update(windowLeft, windowTop, windowWidth, windowHeight)
    }

    /** Unless [show] said steady or it is going already. */
    fun typed() {
        if (state.typed()) dismiss(animated = true)
    }

    private var menu: Rect? = null

    /**
     * Where the menu meets this window, one typing puts away goes and a steady one is hidden, its time
     * stopped; one elsewhere is left. One said while the menu is up and meeting it is hidden till it goes.
     */
    fun covered(menu: Rect) {
        this.menu = Rect(menu)
        if (window?.isShowing != true) return
        if (!Rect.intersects(menu, Rect(windowLeft, windowTop, windowLeft + windowWidth, windowTop + windowHeight))) return
        when (state.cover()) {
            NoticeState.Cover.Away -> dismiss(animated = true)
            NoticeState.Cover.Hide -> {
                anchor.removeCallbacks(timeout)
                shown(false)
            }
            NoticeState.Cover.None -> Unit
        }
    }

    /** Shown again, timed from now for as long as at first. */
    fun uncovered() {
        menu = null
        if (!state.uncover() || window?.isShowing != true) return
        shown(true)
        timed()
    }

    // a time past the uptime clock's end would run at once
    private fun timed() {
        if (shownFor != NoticeState.STAYS) anchor.postDelayed(timeout, shownFor)
    }

    // hidden, it neither shows nor takes a touch meant for what is under it
    private fun shown(visible: Boolean) {
        box?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        window?.apply {
            isTouchable = visible
            update()
        }
    }

    /** Not [animated]: at once (the keyboard itself going). */
    fun dismiss(animated: Boolean = false) {
        anchor.removeCallbacks(timeout)
        state.dismiss()?.lapsed?.invoke()
        val shown = window ?: return
        val box = box ?: return gone()
        val ms = if (animated && shown.isShowing) Motion.Medium.exit(motion()) else 0L
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
            // tapped, it goes; one with 撤销 too, off it
            setOnClickListener { dismiss(animated = true) }
            addView(row)
        }
        this.box = box
        this.row = row
        slide = to.slide
        windowLeft = to.left
        windowTop = to.top
        windowWidth = row.layoutParams.width
        windowHeight = row.layoutParams.height
        window = PopupWindow(box, windowWidth, windowHeight, false).apply {
            isTouchable = true
            // the window's own animation off: the box's below is the one
            animationStyle = 0
            showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, windowLeft, windowTop)
        }
        // the window is the chip's size, so what has not slid out is cut at its edge, as if from behind it
        val (fromX, fromY) = offset()
        box.alpha = 0f
        animate(Motion.Medium.enter(motion()), Motion.Curve.Enter, end = ::reposition) { t ->
            box.alpha = t
            box.translationX = fromX * (1 - t)
            box.translationY = fromY * (1 - t)
        }
    }

    private fun replace(shown: PopupWindow, row: View, to: UndoChipPlacement.Place) {
        val box = box ?: return
        stop()
        val old = this.row
        // the old 撤销's area still takes the tap, now to nothing: a finger reaching for it must not fall
        // through to the box, which taps the new one away. The rest of the old row lets the new one's taps by
        if (old?.tag != null) {
            old.tag = View(anchor.context).apply { isClickable = true }
            old.requestLayout()
        }
        // a third before the second was in: only the one most shown fades
        for (i in box.childCount - 1 downTo 0) {
            if (box.getChildAt(i) !== old) box.removeViewAt(i)
        }
        row.alpha = 0f
        box.addView(row)
        this.row = row
        slide = to.slide
        val from = UndoChipPlacement.Box(windowLeft, windowTop, windowLeft + windowWidth, windowTop + windowHeight)
        val target = UndoChipPlacement.Box(to.left, to.top, to.left + row.layoutParams.width, to.top + row.layoutParams.height)
        val oldAlpha = old?.alpha ?: 0f
        // a chip on its way out comes back
        val boxAlpha = box.alpha
        val boxX = box.translationX
        val boxY = box.translationY
        animate(Motion.Short.enter(motion()), Motion.Curve.Move, end = { old?.let(box::removeView); reposition() }) { t ->
            windowLeft = lerp(from.left, target.left, t)
            windowTop = lerp(from.top, target.top, t)
            windowWidth = lerp(from.width, target.width, t)
            windowHeight = lerp(from.height, target.height, t)
            shown.update(windowLeft, windowTop, windowWidth, windowHeight)
            old?.alpha = oldAlpha * (1 - t)
            row.alpha = t
            box.alpha = boxAlpha + (1 - boxAlpha) * t
            box.translationX = boxX * (1 - t)
            box.translationY = boxY * (1 - t)
        }
    }

    // toward the keyboard it slides out of
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

    // 0 ms (animation off): at once, no animator
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

    private fun row(message: String, undo: Undo?, choices: List<Choice>): View {
        val ctx = anchor.context
        val text = ctx.textView {
            text = message
            setTextColor(theme.popupTextColor)
            textSize = 14f
            // the line beside 撤销 is the gap between them
            setPaddingDp(16, 10, if (undo == null && choices.isEmpty()) 16 else if (choices.isEmpty()) 12 else 4, 10)
        }
        val row = ctx.horizontalLayout {
            gravity = gravityCenterVertical
            add(text, lParams(wrapContent, wrapContent))
        }
        // a long one wraps rather than going off screen (the screen's own width before the keyboard's first layout)
        val across = anchor.rootView.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        var room = across - 2 * ctx.dp(MARGIN_DP)
        if (undo != null) {
            val line = View(ctx).apply { setBackgroundColor(theme.dividerColor) }
            val button = undoButton(undo)
            row.add(line, row.lParams(ctx.dp(1), ctx.dp(DIVIDER_DP)))
            // one height for every notice. To the chip's end: a thumb slightly off 撤销 still takes back, not tapping
            // the chip away (which lapses the act and purges the clipboard's deletes for good)
            row.add(button, row.lParams(row.wrapContent, ViewGroup.LayoutParams.MATCH_PARENT))
            row.tag = button
            row.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                // none once replaced: its 撤销 is let go of
                val taker = v.tag as? View ?: return@addOnLayoutChangeListener
                // the divider within: 撤销 is at the start in a right-to-left language
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
        // each its own button, no area beyond it: a tap between two must not take the wrong word
        choices.forEachIndexed { i, choice ->
            if (i > 0) row.add(View(ctx).apply { setBackgroundColor(theme.dividerColor) }, row.lParams(ctx.dp(1), ctx.dp(DIVIDER_DP)))
            val button = button(choice.label) {
                // one replaced and fading, or going: not picked
                if (shownChoices !== choices || !state.choose()) return@button
                shownChoices = emptyList()
                dismiss(animated = true)
                choice.pick()
            }.apply {
                contentDescription = choice.said
                minWidth = ctx.dp(TOUCH_DP)
                minHeight = ctx.dp(TOUCH_DP)
            }
            row.add(button, row.lParams(row.wrapContent, ViewGroup.LayoutParams.MATCH_PARENT))
            button.measure(UNSPECIFIED, UNSPECIFIED)
            room -= button.measuredWidth + ctx.dp(1)
        }
        text.maxWidth = room.coerceAtLeast(ctx.dp(MIN_WORDS_DP))
        row.measure(UNSPECIFIED, UNSPECIFIED)
        // its own size whatever the box's: as the box eases from one to another, neither row re-wraps
        row.layoutParams = FrameLayout.LayoutParams(row.measuredWidth, row.measuredHeight, Gravity.CENTER)
        return row
    }

    private fun undoButton(undo: Undo) = button(anchor.context.getString(undo.action)) {
        // one replaced and fading has lapsed: its act stands, the notice shown now stays
        if (!state.undo(undo)) return@button
        // taken, not lapsed
        dismiss(animated = true)
        undo.undo()
    }

    // a text button in the accent colour, no block of colour
    private fun button(label: String, onClick: () -> Unit) = anchor.context.textView {
        text = label
        setTextColor(undoColour(theme))
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14f
        gravity = gravityCenter
        accessibilityDelegate = ButtonRole
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(CORNER_DP - RIPPLE_INSET_DP).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), null, InsetDrawable(mask, dp(RIPPLE_INSET_DP)))
        // after the background: the inset's own padding (4 dp) would replace it
        setPaddingDp(12, 0, 16, 0)
        setOnClickListener { onClick() }
    }

    private object ButtonRole : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    internal companion object {
        // on a screen too narrow for them and 撤销 beside them
        const val MIN_WORDS_DP = 48

        const val CORNER_DP = 18
        const val RIPPLE_INSET_DP = 4
        const val DIVIDER_DP = 20
        const val MARGIN_DP = 16

        // the least a finger is given
        const val TOUCH_DP = 48

        /** The theme's accent, toward the popup's text till it reads at 4.5:1 on the popup's colour. */
        fun undoColour(theme: Theme): Int =
            Contrast.legible(theme.accentKeyBackgroundColor, theme.popupBackgroundColor, theme.popupTextColor)

        // read when measured, not as the class loads: undoColour is called with no Android under it
        private val UNSPECIFIED get() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    }
}
