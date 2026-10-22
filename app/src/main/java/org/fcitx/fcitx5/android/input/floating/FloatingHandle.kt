/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp

/**
 * The floating keyboard's handle ([FloatingKeyboard]), a strip along its bottom, as Gboard's, or its
 * top ([FloatingHandleSide]): a finger on it drags the keyboard, its button docks it again. [onDrag]
 * is told how far the finger moved since the last, in pixels; [onDrop] when the drag ends, lifted
 * if the finger let go (not a second finger, nor the system taking the touch). At the
 * bottom it is the keyboard's own colour, under the keys; at the top a shade of the bar's, told
 * apart from the bar under it.
 */
@SuppressLint("ViewConstructor")
class FloatingHandle(
    context: Context,
    theme: Theme,
    side: FloatingHandleSide,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDrop: (lifted: Boolean) -> Unit,
    onDock: () -> Unit,
) : FrameLayout(context) {

    private var lastX = 0f
    private var lastY = 0f

    // one finger drags: a second ends the drag, or the first lifted would make the keyboard jump
    private var dragging = false

    init {
        // a shade of the bar: in a theme whose bar is the keyboard's colour it was not told apart
        if (side == FloatingHandleSide.Top) setBackgroundColor(FloatingKeyboard.handleColor(theme.barColor, theme.keyTextColor))
        // a short bar: the handle's whole strip drags, this only shows that it does
        addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2f)
                setColor(theme.altKeyTextColor)
            }
            isClickable = false
        }, LayoutParams(dp(BAR_WIDTH_DP), dp(BAR_HEIGHT_DP), Gravity.CENTER))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_open_in_full_24)
            colorFilter = PorterDuffColorFilter(theme.altKeyTextColor, PorterDuff.Mode.SRC_IN)
            background = borderlessRippleDrawable(theme.keyPressHighlightColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.floating_keyboard_dock)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { onDock() }
        }, LayoutParams(dp(DOCK_WIDTH_DP), dp(HEIGHT_DP), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    // raw (screen) places: the handle moves with the keyboard under the finger
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                onDrag(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onDrop(e.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    companion object {
        // the strip's and so the dock button's height: 48 took a row's room from the keyboard. The
        // button is wider for it, the strip having nothing else beside the bar
        const val HEIGHT_DP = 24
        const val DOCK_WIDTH_DP = 56
        private const val BAR_WIDTH_DP = 36
        private const val BAR_HEIGHT_DP = 4
    }
}
