/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import org.fcitx.fcitx5.android.input.keyboard.BlankKey
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyGesture
import org.fcitx.fcitx5.android.input.keyboard.KeyHandle
import org.fcitx.fcitx5.android.input.keyboard.KeyPlace
import org.fcitx.fcitx5.android.input.keyboard.TouchTarget
import org.fcitx.fcitx5.android.input.scene.KeyRef

/**
 * A key the scene draws (060-render-p2 S1), as its keyboard wires it: what a key view was to its
 * touch, without the view. Its [def] is the old key's, so it does what the view did (the plan's
 * U4 A, until each keyboard's layout says it, S4/S5); its place is the scene's, as drawn last.
 */
class SceneKey(
    override val ref: KeyRef,
    val def: KeyDef,
    private val canvas: KeyboardCanvasView,
    /** The keyboard: what buzzes, whose window the timers live as long as. */
    private val keyboard: View,
) : KeyHandle, KeyPlace {

    override val keyGesture = KeyGesture(this, ViewConfiguration.get(keyboard.context).scaledTouchSlop.toFloat())

    // its cell as the finger came down ([SceneTouch.find]), in the keyboard: where its view was, the touch's
    // coordinates from its corner and the finger off the key past its size, as a view's were. A finger in
    // a row's blank end (before A) is on the key by its hit area, outside its cell, as it was outside its view
    internal val frame = Rect()

    private var pressed = false
    private var click: ((KeyHandle) -> Unit)? = null
    private var longClick: ((KeyHandle) -> Boolean)? = null
    private var repeatAction: ((KeyHandle) -> Unit)? = null
    private var doubleTapAction: ((KeyHandle) -> Unit)? = null
    override var gestureListener: ((KeyHandle, CustomGestureView.Event) -> Boolean)? = null

    override val keyWidth get() = frame.width()
    override val keyHeight get() = frame.height()

    // a blank key takes no touch, as its view did not
    override val keyEnabled = def !is BlankKey

    private val window = Rect()

    // as a popup over it is placed: the view's bounds were the face across and the cell down
    override val bounds: Rect
        get() = window.also { if (!canvas.popupBounds(ref, it)) it.setEmpty() }

    override val feedback: View get() = keyboard

    override val timers: CoroutineScope by lazy { keyboard.findViewTreeLifecycleOwner()!!.lifecycleScope }

    override fun showPressed(pressed: Boolean) {
        if (this.pressed == pressed) return
        this.pressed = pressed
        canvas.showPressed(ref, pressed)
    }

    override fun click(): Boolean {
        val l = click ?: return false
        l(this)
        return true
    }

    override fun longClick(): Boolean = longClick?.invoke(this) ?: false
    override fun doubleTap() {
        doubleTapAction?.invoke(this)
    }

    override fun repeat() {
        repeatAction?.invoke(this)
    }

    override fun onGesture(event: CustomGestureView.Event): Boolean? = gestureListener?.invoke(this, event)

    override fun onClick(listener: (KeyHandle) -> Unit) {
        click = listener
    }

    // as a view's setOnLongClickListener: a long press timed from now on
    override fun onLongClick(listener: (KeyHandle) -> Boolean) {
        longClick = listener
        keyGesture.longPressEnabled = true
    }

    override fun onRepeat(listener: (KeyHandle) -> Unit) {
        repeatAction = listener
    }

    override fun onDoubleTap(listener: (KeyHandle) -> Unit) {
        doubleTapAction = listener
    }

    /** Where its cell is in [keyboard], as the scene drawn last has it: glide typing's key, where its view was. */
    override fun place(keyboard: ViewGroup, out: Rect) {
        if (canvas.cell(ref, out)) out.offset(canvas.left, canvas.top) else out.setEmpty()
    }
}

/**
 * The fingers on a keyboard whose keys a scene draws (060-render-p2 S1): each given, as it goes
 * down, to the key the hit areas of the scene drawn last find there ([KeyboardCanvasView.keyAt]),
 * and its steps to that key's touch ([KeyGesture]) from the corner of the key's cell, as a key view's
 * were from its own. The keyboard routes them as it did to the views (BaseKeyboard's touch
 * targets): this finds the target. [shown] says whether a key is shown now: one the scene drawn
 * last has, hidden since (an address's keys), takes no touch till the next frame's scene.
 */
class SceneTouch(
    private val canvas: KeyboardCanvasView,
    val keys: List<SceneKey>,
    private val shown: (SceneKey) -> Boolean,
) {
    private val byRef = keys.associateBy { it.ref }

    /** A finger's key, from where its cell's corner was as it went down. */
    internal class Pointer(val key: SceneKey) : TouchTarget {
        override val ref get() = key.ref
        override val enabled get() = key.keyEnabled

        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) {
            key.keyGesture.onTouch(
                action, event.getX(pointerIndex) - key.frame.left, event.getY(pointerIndex) - key.frame.top,
                event.getPointerId(pointerIndex), event.eventTime,
            )
        }

        override fun cancel() = key.keyGesture.cancelGestures()
    }

    /** The key at ([x], [y]) in the keyboard, its cell noted; null in a gap or on a key not shown. */
    internal fun find(x: Float, y: Float): Pointer? {
        val node = canvas.keyAt(x - canvas.left, y - canvas.top) ?: return null
        // a split row's second copy with no key of its own is the first's, as its view was (Host.keyView)
        val key = (byRef[KeyRef.scene(node.id, node.copy)] ?: byRef[KeyRef(node.id)])?.takeIf(shown) ?: return null
        key.frame.set(node.bounds)
        key.frame.offset(canvas.left, canvas.top)
        return Pointer(key)
    }
}
