/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
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
 * The floating keyboard's handle, a strip along its bottom (as Gboard's) or top: a finger on it drags the
 * keyboard, its right button docks it, its left one resizes it. Docked (始终显示悬浮条): no button, no
 * drag; its grip held for the system's long-press time lets the keyboard go floating.
 */
@SuppressLint("ViewConstructor")
class FloatingHandle(
    context: Context,
    private val theme: Theme,
    side: FloatingHandleSide,
    private val mode: Mode,
    look: WindowLooks.Handle = SceneWindow.plainHandle(theme, side == FloatingHandleSide.Top),
) : FrameLayout(context) {

    /** One or the other's callbacks, never a mix. */
    sealed interface Mode {
        /** [onDrop] lifted if the finger let go (not a second finger, nor the system taking the touch). */
        class Floating(
            val onDrag: (dx: Float, dy: Float) -> Unit,
            val onDrop: (lifted: Boolean) -> Unit,
            val onDock: () -> Unit,
            val resize: Resizing,
        ) : Mode

        /** TalkBack's long press, no touch: the grip's middle and none. */
        class Docked(val onDetach: (rawX: Float, rawY: Float, touch: HandoffTouch?) -> Unit) : Mode
    }

    /** [end] as it lifts or is taken (a second finger, the system); [step] false at its limit already. */
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

    // the keys may hold other fingers, split away from here
    private var touch: HandoffTouch? = null

    private val detach = Runnable { if (press.fire()) (mode as? Mode.Docked)?.run { onDetach(lastX, lastY, touch) } }

    // the whole strip drags, this only shows that it does
    private val grip = View(context).apply { isClickable = false }

    private val dock = (mode as? Mode.Floating)?.let { floating ->
        ImageView(context).apply {
            background = borderlessRippleDrawable(theme.keyPressHighlightColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.floating_keyboard_dock)
            setPadding(dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP), dp(DOCK_PADDING_DP))
            setOnClickListener { floating.onDock() }
        }
    }

    // a physical left: the keyboard's corner there moves, the opposite one stays
    private val resizer = (mode as? Mode.Floating)?.resize?.let { resize ->
        MirroredIcon(context).apply {
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

    private var dressed: WindowLooks.Handle? = null

    init {
        // split, a second finger on the strip while the first resizes (or the reverse) would start one of its own and
        // the two fight over the keyboard; unsplit it comes to the first's view as a second finger, which ends it
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
        // physical right: END is the left in a right-to-left language
        dock?.let { addView(it, LayoutParams(dp(DOCK_WIDTH_DP), dp(HEIGHT_DP), Gravity.RIGHT or Gravity.CENTER_VERTICAL)) }
        resizer?.let { addView(it, LayoutParams(dp(DOCK_WIDTH_DP), dp(HEIGHT_DP), Gravity.LEFT or Gravity.CENTER_VERTICAL)) }
    }

    /**
     * Worked out by who holds it (the docked one's as its board is placed): a view of its own does not read
     * the settings the scenes are of. Not set, at the top a shade of the bar (told apart from a bar of the
     * keyboard's colour), at the bottom the keyboard's own.
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

    private fun drawDock(@ColorInt color: Int) {
        listOf(dock to BarIcons.FLOATING_DOCK, resizer to BarIcons.FLOATING_RESIZE).forEach { (view, icon) ->
            view ?: return@forEach
            BarIconDrawables.show(view, PackIcons.of(theme), icon, color, dp(HEIGHT_DP - 2 * DOCK_PADDING_DP)) {
                if (dressed?.iconColor?.value == color) drawDock(color)
            }
        }
    }

    @SuppressLint("AppCompatCustomView")
    class MirroredIcon(context: Context) : ImageView(context) {
        var mirrored = false
            set(value) {
                if (value == field) return
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            if (!mirrored) return super.onDraw(canvas)
            val at = canvas.save()
            canvas.scale(-1f, 1f, width / 2f, height / 2f)
            super.onDraw(canvas)
            canvas.restoreToCount(at)
        }
    }

    /**
     * Told as it is placed, dragged or resized, so it is right before the next touch. The image is mirrored
     * as drawn, not the view: a mirrored view's touches come mirrored too (rawX with them on Android 10).
     */
    fun showGrowth(down: Boolean) {
        resizer?.mirrored = !down
    }

    /** Null: none, or the theme's not read yet. */
    @get:VisibleForTesting
    internal val dockDrawable: Drawable? get() = dock?.drawable

    @get:VisibleForTesting
    internal val dockButton: ImageView? get() = dock

    @get:VisibleForTesting
    internal val resizeButton: MirroredIcon? get() = resizer

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
        // 48 took a row's room from the keyboard
        const val HEIGHT_DP = 24
        const val DOCK_WIDTH_DP = 56
        private const val DOCK_PADDING_DP = 3
    }
}
