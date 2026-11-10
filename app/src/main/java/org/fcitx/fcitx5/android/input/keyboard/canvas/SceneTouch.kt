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
import android.view.accessibility.AccessibilityEvent
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
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState

/**
 * A key the scene draws (060-render-p2 S1), as its keyboard wires it: what a key view was to its
 * touch, without the view. Its [def] is made from its layout's node ([SceneKeyDef], the plan's U4 B),
 * doing what the view did; its place is the scene's, as drawn last.
 */
class SceneKey(
    override val ref: KeyRef,
    val def: KeyDef,
    private val canvas: KeyboardCanvasView,
    /** The keyboard: what buzzes, whose window the timers live as long as. */
    private val keyboard: View,
) : KeyHandle, KeyPlace {

    /** Its bubble's content as the scene has it now, over its [def]'s: a node redrawn so keeps its key (SceneKeys). */
    var bubble: String? = null

    override val keyGesture = KeyGesture(this, ViewConfiguration.get(keyboard.context).scaledTouchSlop.toFloat())

    // the cell of the finger stepping on it now ([SceneTouch.Pointer.send]): its size the touch's, the
    // finger off the key past it, as a view's was
    private val frame = Rect()

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
    override val bounds: Rect?
        get() = window.takeIf { canvas.popupBounds(ref, it) }

    override val feedback: View get() = keyboard

    override val timers: CoroutineScope by lazy { keyboard.findViewTreeLifecycleOwner()!!.lifecycleScope }

    // where the finger came down, in the canvas: a press effect plays from there (060-render-p3 A3). [frame] is the key's
    // cell in the keyboard, the canvas laid in it at its own place
    override fun hotspot(x: Float, y: Float) = canvas.touchedAt(ref, frame.left + x - canvas.left, frame.top + y - canvas.top)

    override fun showPressed(pressed: Boolean) {
        if (this.pressed == pressed) return
        this.pressed = pressed
        canvas.showPressed(ref, pressed)
    }

    // as a view's performClick: what it does, then a screen reader told it was clicked (a tap's or TalkBack's)
    override fun click(): Boolean = click(ref)

    // as a view's performLongClick: told first, then what it does
    override fun longClick(): Boolean = longClick(ref)

    /**
     * [click] told on [node]'s virtual node: the one a screen reader clicked, a split row's second
     * copy wired as the first ([SceneTouch.keyOf]) told on its own, not the first's.
     */
    fun click(node: KeyRef): Boolean {
        val l = click
        l?.invoke(this)
        canvas.tell(node, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return l != null
    }

    /** [longClick] told on [node]'s virtual node, as [click]. */
    fun longClick(node: KeyRef): Boolean {
        canvas.tell(node, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        return longClick?.invoke(this) ?: false
    }

    /** Whether a long press does something of its own (a view's isLongClickable): a screen reader's long click offered. */
    val longClickable: Boolean get() = longClick != null
    override fun doubleTap() {
        doubleTapAction?.invoke(this)
    }

    override fun repeat() {
        repeatAction?.invoke(this)
    }

    override fun onGesture(event: CustomGestureView.Event): Boolean? = gestureListener?.invoke(this, event)

    // [action] at ([x], [y]) in the keyboard, by a finger that came down in [cell] (the keyboard's)
    internal fun touch(cell: Rect, action: Int, x: Float, y: Float, pointerId: Int, eventTime: Long) {
        frame.set(cell)
        keyGesture.onTouch(action, x - cell.left, y - cell.top, pointerId, eventTime)
    }

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
    /** The key wired for a node of the scene ([keyOf]), made from the node (060-render-p2 U4 B). */
    private val keyFor: (KeyNode) -> SceneKey?,
    private val shown: (SceneKey) -> Boolean,
) {

    /**
     * A finger's key, and its cell in the keyboard as the finger went down: the touch's coordinates
     * from its corner, as a view's were. A finger in a row's blank end (before A) is on the key by
     * its hit area, outside its cell, as it was outside its view. The finger's own: a split row's
     * two copies may be one key ([find]), each finger in its own copy's cell.
     */
    internal class Pointer(val key: SceneKey, val cell: Rect, private val disabled: Boolean = false) : TouchTarget {
        override val ref get() = key.ref
        // a key the scene shows `:disabled` (展开候选's page that cannot turn) takes no touch, as its view did not
        override val enabled get() = key.keyEnabled && !disabled

        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) {
            key.touch(cell, action, event.getX(pointerIndex), event.getY(pointerIndex), event.getPointerId(pointerIndex), event.eventTime)
        }

        override fun cancel() = key.keyGesture.cancelGestures()
    }

    /** The key wired for the scene's [node]: what a touch on it and a screen reader's actions on its node do ([KeyboardCanvasView]). */
    fun keyOf(node: KeyNode): SceneKey? = keyFor(node)

    /** The key at ([x], [y]) in the keyboard, its cell noted; null in a gap or on a key not shown. */
    internal fun find(x: Float, y: Float): Pointer? {
        val node = canvas.keyAt(x - canvas.left, y - canvas.top) ?: return null
        val key = keyOf(node)?.takeIf(shown) ?: return null
        val cell = Rect().apply { set(node.bounds) }
        cell.offset(canvas.left, canvas.top)
        return Pointer(key, cell, KeyState.Disabled in node.states)
    }
}
