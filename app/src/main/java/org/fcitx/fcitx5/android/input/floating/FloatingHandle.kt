/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneWindow
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp

/**
 * The floating keyboard's handle ([FloatingKeyboard]), a strip along its bottom, as Gboard's, or its
 * top ([FloatingHandleSide]): a finger on it drags the keyboard, its button at the right end docks it again,
 * the one at the left end resizes it ([Mode.Floating], 061-floating-resize). At the bottom it is the keyboard's own colour, under the keys; at the top a
 * shade of the bar's, told apart from the bar under it.
 *
 * Docked (始终显示悬浮条, [Mode.Docked]): nothing at its right end (the user's choice), no drag;
 * its grip held for the system's long-press time ([GripPress]) lets the keyboard go floating.
 */
@SuppressLint("ViewConstructor")
class FloatingHandle(
    context: Context,
    private val theme: Theme,
    side: FloatingHandleSide,
    private val mode: Mode,
    look: WindowLooks.Handle = SceneWindow.plainHandle(theme, side == FloatingHandleSide.Top),
) : FrameLayout(context) {

    /** Which of the two it is: one or the other's callbacks, never a mix. */
    sealed interface Mode {
        /**
         * The floating keyboard's: [onDrag] told how far the finger moved since the last, in pixels;
         * [onDrop] when the drag ends, lifted if the finger let go (not a second finger, nor the
         * system taking the touch); [onDock] its button pressed; [resize] its resize button's.
         */
        class Floating(
            val onDrag: (dx: Float, dy: Float) -> Unit,
            val onDrop: (lifted: Boolean) -> Unit,
            val onDock: () -> Unit,
            val resize: Resizing,
        ) : Mode

        /**
         * The docked keyboard's: [onDetach] told where the finger holding the grip is (raw) and which
         * touch and finger it is, to let the keyboard go floating under it; TalkBack's long press, no
         * touch, the grip's middle and none.
         */
        class Docked(val onDetach: (rawX: Float, rawY: Float, touch: HandoffTouch?) -> Unit) : Mode
    }

    /**
     * The floating keyboard resized by the button at the strip's left end: [start] as a finger goes down on it, [move]
     * by how far it moved since, px, [end] as it lifts or is taken (a second finger, the system); TalkBack's 放大 and
     * 缩小 a [step] each, false at its limit already.
     */
    interface Resizing {
        fun start()
        fun move(dx: Float, dy: Float)
        fun end()
        fun step(bigger: Boolean): Boolean
    }

    private var lastX = 0f
    private var lastY = 0f

    // one finger drags: a second ends the drag, or the first lifted would make the keyboard jump
    private var dragging = false

    private val press = GripPress(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), dp(GripPress.GRIP_TOUCH_DP).toFloat())

    // the touch holding the grip: its finger (the keys may hold others, split away from here)
    private var touch: HandoffTouch? = null

    private val detach = Runnable { if (press.fire()) (mode as? Mode.Docked)?.run { onDetach(lastX, lastY, touch) } }

    // a short bar, a pill: the handle's whole strip drags, this only shows that it does
    private val grip = View(context).apply { isClickable = false }

    // floating, the button that docks the keyboard again
    private val dock = (mode as? Mode.Floating)?.let { floating ->
        ImageView(context).apply {
            background = borderlessRippleDrawable(theme.keyPressHighlightColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.floating_keyboard_dock)
            setPadding(dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP))
            setOnClickListener { floating.onDock() }
        }
    }

    // floating, the button that resizes the keyboard, at the left end (a physical left: the keyboard's corner there
    // moves, the opposite one stays), as wide as the dock button: with the grip's touch between, 232 of the 260 dp it
    // can shrink to (061-floating-resize §2.4)
    private val resizer = (mode as? Mode.Floating)?.resize?.let { resize ->
        ImageView(context).apply {
            background = borderlessRippleDrawable(theme.keyPressHighlightColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.floating_keyboard_resize)
            setPadding(dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP))
            setOnTouchListener(ResizeTouch(resize))
            ViewCompat.addAccessibilityAction(this, context.getString(R.string.floating_keyboard_bigger)) { _, _ -> resize.step(bigger = true) }
            ViewCompat.addAccessibilityAction(this, context.getString(R.string.floating_keyboard_smaller)) { _, _ -> resize.step(bigger = false) }
        }
    }

    // one finger resizes: a second ends it, as a drag of the strip
    private class ResizeTouch(private val resize: Resizing) : OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var resizing = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    resizing = true
                    downX = e.rawX
                    downY = e.rawY
                    resize.start()
                }
                MotionEvent.ACTION_MOVE -> if (resizing) resize.move(e.rawX - downX, e.rawY - downY)
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (resizing) {
                    v.isPressed = false
                    resizing = false
                    resize.end()
                }
            }
            return true
        }
    }

    // the look it is dressed in last ([dress])
    private var dressed: WindowLooks.Handle? = null

    init {
        // one finger at a time on the whole strip: split, a second finger on the strip while the first resizes (or on
        // the resize button while it drags) would start a drag (a resize) of its own beside the first, and the two
        // fight over the keyboard; unsplit it comes to the first's view as a second finger, which ends it
        isMotionEventSplittingEnabled = false
        addView(grip, LayoutParams(0, 0, Gravity.CENTER))
        dress(look)
        // TalkBack's long press is no touch: the grip's middle stands for the finger
        if (mode is Mode.Docked) {
            contentDescription = context.getString(R.string.floating_keyboard)
            setOnLongClickListener {
                val at = IntArray(2).also { getLocationOnScreen(it) }
                mode.onDetach(at[0] + width / 2f, at[1] + height / 2f, null)
                true
            }
        }
        dock?.let { addView(it, LayoutParams(dp(DOCK_WIDTH_DP), dp(HEIGHT_DP), Gravity.END or Gravity.CENTER_VERTICAL)) }
        resizer?.let { addView(it, LayoutParams(dp(DOCK_WIDTH_DP), dp(HEIGHT_DP), Gravity.LEFT or Gravity.CENTER_VERTICAL)) }
    }

    /**
     * As [look], the theme's `handle` (060-render-p3 T7a, `SceneWindow.handle`) worked out by who holds
     * it: the docked one's as its board is placed (`@one-handed`, `@split`). Worked out there, as the
     * scenes are of the settings, which a view of its own does not read. Not set, at the top a shade of
     * the bar (in a theme whose bar is the keyboard's colour it was not told apart), at the bottom the
     * keyboard's own under it.
     */
    fun dress(look: WindowLooks.Handle) {
        if (look == dressed) return
        dressed = look
        val fill = look.fill
        if (fill != null) setBackgroundColor(fill.value) else background = null
        grip.background = GradientDrawable().apply {
            cornerRadius = dp(look.heightDp.toFloat() / 2)
            setColor(look.color.value)
        }
        grip.layoutParams = (grip.layoutParams as LayoutParams).apply {
            width = SceneWindow.px(context, look.widthDp).toInt()
            height = SceneWindow.px(context, look.heightDp).toInt()
        }
        drawDock(look.iconColor.value)
    }

    // the dock and resize buttons' icons, a theme's `floating-dock` and `floating-resize` too (061-toolbar-icons), in the
    // handle's icon colour
    private fun drawDock(@ColorInt color: Int) {
        listOf(dock to BarIcons.FLOATING_DOCK, resizer to BarIcons.FLOATING_RESIZE).forEach { (view, icon) ->
            view ?: return@forEach
            BarIconDrawables.show(view, PackIcons.of(theme), icon, color, dp(HEIGHT_DP - 2 * DOCK_PADDING_DP)) {
                if (dressed?.iconColor?.value == color) drawDock(color)
            }
        }
    }

    /** The dock button's icon as drawn now (null: none, or the theme's not read yet). */
    @get:VisibleForTesting
    internal val dockDrawable: Drawable? get() = dock?.drawable

    /** The resize button (null docked). */
    @get:VisibleForTesting
    internal val resizeButton: View? get() = resizer

    // raw (screen) places: the handle moves with the keyboard under the finger
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val floating = mode as? Mode.Floating ?: return hold(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                floating.onDrag(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                floating.onDrop(e.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    // docked: only a still press on the grip, timed as the system's long press, does anything
    private fun hold(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch = HandoffTouch(e.getPointerId(0))
                lastX = e.rawX
                lastY = e.rawY
                if (press.down(e.x, e.y, width.toFloat())) postDelayed(detach, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val i = touch?.let { e.findPointerIndex(it.pointerId) } ?: -1
                if (i < 0) return true
                lastX = e.getRawX(i)
                lastY = e.getRawY(i)
                press.move(e.getX(i), e.getY(i))
                if (!press.armed) removeCallbacks(detach)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                press.end()
                removeCallbacks(detach)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        press.end()
        removeCallbacks(detach)
        super.onDetachedFromWindow()
    }

    companion object {
        // the strip's and so the dock button's height: 48 took a row's room from the keyboard. The
        // button is wider for it, the strip having nothing else beside the bar
        const val HEIGHT_DP = 24
        const val DOCK_WIDTH_DP = 56
        private const val DOCK_PADDING_DP = 3
    }
}
