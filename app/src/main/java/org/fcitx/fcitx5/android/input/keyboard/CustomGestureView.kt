/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope

/**
 * A view taking a key's touch: the adapter of [KeyGesture], which has the gesture's bookkeeping and
 * timers, to a view's touch, its listeners and its pressed state.
 */
open class CustomGestureView(ctx: Context) : FrameLayout(ctx), KeyGesture.Key {

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int,
        /** an Up for a touch the system took away (ACTION_CANCEL): end what it shows, type nothing */
        val cancelled: Boolean = false,
        /** the vertical swipe reached a step at some point this touch, though [totalY] may be back at 0 */
        val swipedY: Boolean = false,
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    override val timers: CoroutineScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    /** The touch's bookkeeping and timers, whatever draws the key. */
    val keyGesture = KeyGesture(this, ViewConfiguration.get(ctx).scaledTouchSlop.toFloat())

    /** the finger on this key, by the id the keyboard's touch gives it */
    val touchPointer get() = keyGesture.touchPointer

    /** Where across the key the touch that clicks it came down, for the click listener alone ([KeyGesture.clickAt]). */
    val clickAt get() = keyGesture.clickAt

    var longPressEnabled by keyGesture::longPressEnabled
    var longPressFeedbackEnabled by keyGesture::longPressFeedbackEnabled
    var repeatEnabled by keyGesture::repeatEnabled
    var swipeEnabled by keyGesture::swipeEnabled
    var swipeRepeatEnabled by keyGesture::swipeRepeatEnabled
    var swipeThresholdX by keyGesture::swipeThresholdX
    var swipeThresholdY by keyGesture::swipeThresholdY

    /** One axis a touch ([KeyGestureRecognizer.axisLock]): a key swiped sideways and up. */
    var axisLock by keyGesture::axisLock
    var doubleTapEnabled by keyGesture::doubleTapEnabled
    var soundEffect by keyGesture::soundEffect
    var systemSound by keyGesture::systemSound

    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    fun cancelGestures() = keyGesture.cancelGestures()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        keyGesture.onTouch(event.actionMasked, event.x, event.y, event.getPointerId(0), event.eventTime)

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    // the touch's ends, as a view has them
    final override val keyWidth get() = width
    final override val keyHeight get() = height
    final override val keyEnabled get() = isEnabled

    final override fun showPressed(pressed: Boolean) {
        isPressed = pressed
    }

    final override fun hotspot(x: Float, y: Float) = drawableHotspotChanged(x, y)
    final override val feedback: View get() = this
    final override fun click() = performClick()
    final override fun longClick() = performLongClick()
    final override fun doubleTap() {
        onDoubleTapListener?.invoke(this)
    }

    final override fun repeat() {
        onRepeatListener?.invoke(this)
    }

    final override fun onGesture(event: Event): Boolean? = onGestureListener?.onGesture(this, event)
}
