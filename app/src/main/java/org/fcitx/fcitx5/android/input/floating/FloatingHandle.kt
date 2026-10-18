/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp

/**
 * The floating keyboard's handle ([FloatingKeyboard]), the strip along its top: a finger on it
 * drags the keyboard, its button docks it again. [onDrag] is told how far the finger moved since
 * the last, in pixels; [onDrop] when it lets go.
 */
@SuppressLint("ViewConstructor")
class FloatingHandle(
    context: Context,
    theme: Theme,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDrop: () -> Unit,
    onDock: () -> Unit,
) : FrameLayout(context) {

    private var lastX = 0f
    private var lastY = 0f

    // one finger drags: a second ends the drag, or the first lifted would make the keyboard jump
    private var dragging = false

    init {
        setBackgroundColor(theme.barColor)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_drag_handle_24)
            colorFilter = PorterDuffColorFilter(theme.altKeyTextColor, PorterDuff.Mode.SRC_IN)
            // the handle's whole strip drags, this only shows that it does
            isClickable = false
        }, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_open_in_full_24)
            colorFilter = PorterDuffColorFilter(theme.altKeyTextColor, PorterDuff.Mode.SRC_IN)
            background = borderlessRippleDrawable(theme.keyPressHighlightColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.floating_keyboard_dock)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { onDock() }
        }, LayoutParams(dp(48), dp(HEIGHT_DP), Gravity.END or Gravity.CENTER_VERTICAL))
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
                onDrop()
            }
        }
        return true
    }

    companion object {
        // the strip's and so the dock button's height, 48 dp wide: a 48 dp touch target. A TouchDelegate
        // can't make it taller: above the strip is outside the view, below it the bar's own buttons
        const val HEIGHT_DP = 48
    }
}
