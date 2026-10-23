/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Bounds
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.utils.interpolator
import org.fcitx.fcitx5.android.utils.motionSettings
import splitties.dimensions.dp

/**
 * The input view's floating keyboard ([FloatingKeyboard]) and the docked keyboard's handle
 * (始终显示悬浮条): the [handle], where the floating keyboard is ([FloatingPlacer]) and its frame, the
 * docking zone's mark, and the switches between docked and floating with their motion
 * ([FloatingSwitch]). Docked, the grip held lets the keyboard go floating under the finger, which
 * drags it on ([FloatingHandoff]); floating, docking slides it down first; switched to floating any
 * way while shown, it shrinks from the docked keyboard ([DockedBounds]). [root] is the input view,
 * filling the screen; [find] gives the controller of an input view the service puts in its frame.
 */
class FloatingController(
    private val root: ViewGroup,
    theme: Theme,
    private val find: (View?) -> FloatingController?,
) {
    private val context = root.context
    private val prefs = ThemeManager.prefs

    /** Floating: read once, the view made anew as it changes (ThemeManager), the letters splitting or not. */
    val floating = prefs.floatingKeyboard.getValue()

    private val side = prefs.floatingHandle.getValue()
    val handleAtBottom = side == FloatingHandleSide.Bottom

    /** Floating, its handle; docked, the same strip if 始终显示悬浮条 (read once too), its grip held letting go. */
    val handle: FloatingHandle? = when {
        floating -> FloatingHandle(context, theme, side, FloatingHandle.Mode.Floating(::drag, ::drop, ::dock))
        prefs.alwaysShowHandle.getValue() -> FloatingHandle(context, theme, side, FloatingHandle.Mode.Docked(::detach))
        else -> null
    }

    private val switch = FloatingSwitch()
    private lateinit var keyboard: View

    var placer: FloatingPlacer? = null
        private set

    // across the bottom of the screen while a drag is in the docking zone: where it will go, let go
    private val dockMark = if (!floating) null else View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(4f)
            setColor(ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, DOCK_MARK_ALPHA))
            setStroke(dp(FloatingPlacer.DOCK_MARK_STROKE_DP).toInt(), ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, DOCK_MARK_STROKE_ALPHA))
        }
        alpha = 0f
        isVisible = false
        // it opens up from the bottom and folds back down: its pivot there, once laid out
        addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ -> v.pivotY = (bottom - top).toFloat() }
    }

    /** Floating, what carries on a drag the docked keyboard's handle began ([FloatingHandoff]). */
    val carried: FloatingHandoff.Receiver? = if (!floating) null else object : FloatingHandoff.Receiver {
        override fun drag(dx: Float, dy: Float) = this@FloatingController.drag(dx, dy)
        override fun drop(lifted: Boolean) = this@FloatingController.drop(lifted)
    }

    /** The [keyboard] view, added to the root; floating, framed and placed, the mark under it. */
    fun setUp(keyboard: View) {
        this.keyboard = keyboard
        if (!floating) return
        dockMark?.let {
            root.addView(it, root.indexOfChild(keyboard), ConstraintLayout.LayoutParams(0, context.dp(FloatingPlacer.DOCK_MARK_DP)).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            })
        }
        FloatingCorners.frame(keyboard, ::cornerPx, context.dp(FloatingCorners.ELEVATION_DP).toFloat())
        placer = FloatingPlacer(root, keyboard).also { it.onZone = ::showDockMark }
        keyboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> laidOut() }
    }

    /** Floating, [layer] (the popups) drawn over the lifted keyboard. */
    fun keepAbove(layer: View) {
        if (floating) FloatingCorners.keepAbove(layer, keyboard)
    }

    /** Floating, the navigation bar ([bottom]) it stays above and the status bar ([top]) below. */
    fun insets(bottom: Int, top: Int) {
        val placer = placer ?: return
        placer.bottomInset = bottom
        placer.topInset = top
        dockMark?.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bottom }
    }

    /** Floating, where the keyboard is in the window, all of it that takes touches; false docked. */
    fun region(out: Rect): Boolean {
        placer?.region(out) ?: return false
        return true
    }

    /**
     * Floating, let go of by the docked keyboard [from]: its grip under the finger at [x], [y] in the
     * input view, once laid out; [ended] no finger down to drop it (TalkBack), kept there at once.
     */
    fun grab(x: Float, y: Float, from: Bounds, ended: Boolean = false) {
        if (floating) switch.grab(x, y, from, ended)
    }

    /** The input view put in the service's frame: floating in place of a docked keyboard shown, shrunk from it. */
    fun attached() {
        val docked = DockedBounds.take(root.parent)
        if (floating && docked != null) switch.shrinkFrom(docked)
    }

    /** The input view taken out: docked and shown, a floating one put in its place shrinks from here. */
    fun detached() {
        if (!floating && keyboard.isShown && keyboard.isLaidOut) root.parent?.let { DockedBounds.leave(it, keyboard.bounds()) }
        // made anew under its slide down (a turn, a theme): docked all the same, once this is done with
        if (switch.slideOver()) Handler(Looper.getMainLooper()).post { prefs.floatingKeyboard.setValue(false) }
    }

    /** Hidden, or its field left. */
    fun inputEnded() = switch.inputEnded()

    private fun drag(dx: Float, dy: Float) {
        if (switch.drag(dx, dy)) placer?.drag(dx, dy)
    }

    // let go in the zone at the bottom: docked, as Gboard's
    private fun drop(lifted: Boolean) {
        if (switch.drop() && placer?.drop(lifted) == true) dock()
    }

    // docked, its handle's grip held: a buzz, and the keyboard floating under the finger, which still
    // down drags it on. The view is made anew for floating, so the rest of the touch is passed to the
    // new one from the frame both are in. TalkBack's long press is no touch ([touch] null): the grip
    // goes where it was, nothing carried on, or a later touch would be taken for it; no drop to come,
    // so its place is kept as it is laid out (F3467: else lost on the next turn or theme)
    private fun detach(rawX: Float, rawY: Float, touch: HandoffTouch?) {
        val frame = root.parent as? ViewGroup ?: return
        InputFeedbacks.hapticFeedback(keyboard, longPress = true)
        val at = IntArray(2).also { root.getLocationOnScreen(it) }
        val from = keyboard.bounds()
        prefs.floatingKeyboard.setValue(true)
        val next = { find(frame.getChildAt(0)) }
        next()?.grab(rawX - at[0], rawY - at[1], from, ended = touch == null)
        if (touch != null) FloatingHandoff(frame, touch, rawX, rawY) { next()?.carried }.start()
    }

    private fun laidOut() {
        val placer = placer ?: return
        when (val layout = switch.laidOut()) {
            FloatingSwitch.Layout.Spot -> placer.place()
            FloatingSwitch.Layout.Sliding -> Unit
            is FloatingSwitch.Layout.Placed -> placeGrabbed(placer, layout.grab)
        }
    }

    // in place of the docked keyboard: its grip under the finger if grabbed, else at its spot, and
    // shrunk from the docked keyboard into its own size
    private fun placeGrabbed(placer: FloatingPlacer, g: FloatingSwitch.Grab) {
        val v = keyboard
        val handle = handle ?: return
        if (g.underFinger) placer.grabbed(g.x, g.y, handle.left + handle.width / 2f, handle.top + handle.height / 2f, keep = g.ended)
        else placer.place()
        val ms = Motion.Long.enter(motionSettings())
        if (ms == 0L || v.width == 0 || v.height == 0) return
        v.scaleX = FloatingKeyboard.startScale(g.from.width, v.width.toFloat())
        v.scaleY = FloatingKeyboard.startScale(g.from.height, v.height.toFloat())
        v.pivotX = FloatingKeyboard.pivot(g.from.left, g.from.width, v.left + v.translationX, v.width.toFloat())
        v.pivotY = FloatingKeyboard.pivot(g.from.top, g.from.height, v.top + v.translationY, v.height.toFloat())
        v.animate().scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(Motion.Curve.Move.interpolator).setListener(null).start()
    }

    // floating to docked (放回底部, or let go in the zone): slid down to the bottom, centred, where the
    // docked keyboard will be, then docked; ended or cancelled, it docks
    private fun dock() {
        val ms = Motion.Long.enter(motionSettings())
        when (switch.dock(animates = ms > 0L && keyboard.isLaidOut)) {
            FloatingSwitch.Dock.Now -> prefs.floatingKeyboard.setValue(false)
            FloatingSwitch.Dock.Sliding -> Unit
            // laid out at the bottom in the middle, moved from there by translation (FloatingPlacer)
            FloatingSwitch.Dock.Slide -> keyboard.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(ms).setInterpolator(Motion.Curve.Move.interpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = slid()
                    override fun onAnimationCancel(animation: Animator) = slid()
                })
                .start()
        }
    }

    private fun slid() {
        if (switch.slideOver()) prefs.floatingKeyboard.setValue(false)
    }

    private fun showDockMark(shown: Boolean) {
        val mark = dockMark ?: return
        mark.animate().cancel()
        val motion = motionSettings()
        // opened up from the bottom as it fades in, folded back down as it goes, as Gboard's
        if (shown) {
            InputFeedbacks.hapticFeedback(keyboard)
            if (!mark.isVisible) {
                mark.scaleY = 0f
                mark.isVisible = true
            }
            mark.animate().alpha(1f).scaleY(1f).setInterpolator(Motion.Curve.Enter.interpolator)
                .setDuration(Motion.Long.enter(motion)).withEndAction(null).start()
        } else {
            mark.animate().alpha(0f).scaleY(0f).setInterpolator(Motion.Curve.Exit.interpolator)
                .setDuration(Motion.Medium.exit(motion)).withEndAction { mark.isVisible = false }.start()
        }
    }

    // the keys' radius and margins as KeyView draws them; square where the keys are (按键圆角 直角)
    private fun cornerPx(): Float {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val h = (if (landscape) prefs.keyHorizontalMarginLandscape else prefs.keyHorizontalMargin).getValue()
        val v = (if (landscape) prefs.keyVerticalMarginLandscape else prefs.keyVerticalMargin).getValue()
        return context.dp(FloatingKeyboard.cornerDp(prefs.keyRadius.getValue(), h, v, prefs.keyBorder.getValue())).toFloat()
    }

    private fun View.bounds() = Bounds(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat())

    private companion object {
        // the docking zone's mark: its fill's alpha over the accent colour
        const val DOCK_MARK_ALPHA = 0x33
        const val DOCK_MARK_STROKE_ALPHA = 0x80
    }
}
