/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import java.lang.ref.WeakReference

/**
 * The input view is made anew for floating, and the old one, taken out of [frame], is sent a cancel: the
 * rest of the touch comes to [frame] itself, no child holding it, and is passed on here to the new floating
 * keyboard. Only the grip's finger counts: the touch comes unsplit, a thumb resting on a key in it too. A
 * new touch coming down ([newTouch]) means this one's end was never seen.
 */
class FloatingHandoff(
    private val frame: ViewGroup,
    private val touch: HandoffTouch,
    private var lastX: Float,
    private var lastY: Float,
    private val receiver: () -> Receiver?,
) : View.OnTouchListener {

    interface Receiver {
        fun drag(dx: Float, dy: Float)
        fun drop(lifted: Boolean)
    }

    fun start() {
        active = WeakReference(this)
        frame.setOnTouchListener(this)
    }

    private fun stop() {
        frame.setOnTouchListener(null)
        if (active?.get() === this) active = null
    }

    // the touch's raw places, as the handle's: the keyboard moves under the finger
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        val i = e.findPointerIndex(touch.pointerId)
        when (touch.step(action(e), e.getPointerId(e.actionIndex), i >= 0)) {
            HandoffTouch.Step.Move -> {
                val x = e.getRawX(i)
                val y = e.getRawY(i)
                receiver()?.drag(x - lastX, y - lastY)
                lastX = x
                lastY = y
            }
            HandoffTouch.Step.Lift -> end(lifted = true)
            HandoffTouch.Step.End -> end(lifted = false)
            HandoffTouch.Step.Ignore -> Unit
            HandoffTouch.Step.Abandon -> {
                stop()
                return false
            }
        }
        return true
    }

    private fun end(lifted: Boolean) {
        stop()
        receiver()?.drop(lifted)
    }

    companion object {
        private var active: WeakReference<FloatingHandoff>? = null

        /** A handoff whose end never came (a touch the frame missed) is over, no drop; else a view made anew would pass it here. */
        fun newTouch(frame: ViewParent?) {
            val handoff = active?.get() ?: return
            if (handoff.frame === frame) handoff.stop()
        }

        fun action(e: MotionEvent) = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> HandoffTouch.Action.Down
            MotionEvent.ACTION_MOVE -> HandoffTouch.Action.Move
            MotionEvent.ACTION_POINTER_DOWN -> HandoffTouch.Action.PointerDown
            MotionEvent.ACTION_POINTER_UP -> HandoffTouch.Action.PointerUp
            MotionEvent.ACTION_UP -> HandoffTouch.Action.Up
            MotionEvent.ACTION_CANCEL -> HandoffTouch.Action.Cancel
            else -> HandoffTouch.Action.Other
        }
    }
}
