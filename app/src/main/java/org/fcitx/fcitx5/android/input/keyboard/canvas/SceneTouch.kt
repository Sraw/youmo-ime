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
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyGesture
import org.fcitx.fcitx5.android.input.keyboard.KeyHandle
import org.fcitx.fcitx5.android.input.keyboard.KeyPlace
import org.fcitx.fcitx5.android.input.keyboard.TouchTarget
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState

/** A key the scene draws: what a key view was to its touch, without the view; its place as drawn last. */
class SceneKey(
    override val ref: KeyRef,
    val def: KeyDef,
    private val canvas: KeyboardCanvasView,
    /** What buzzes, whose window the timers live as long as. */
    private val keyboard: View,
) : KeyHandle, KeyPlace {

    /** Over its [def]'s: a node redrawn so keeps its key (SceneKeys). */
    var bubble: String? = null

    override val keyGesture = KeyGesture(this, ViewConfiguration.get(keyboard.context).scaledTouchSlop.toFloat())

    // the finger's cell: past it the finger is off the key, as a view's was
    private val frame = Rect()

    private var pressed = false
    private var click: ((KeyHandle) -> Unit)? = null
    private var longClick: ((KeyHandle) -> Boolean)? = null
    private var repeatAction: ((KeyHandle) -> Unit)? = null
    private var doubleTapAction: ((KeyHandle) -> Unit)? = null
    override var gestureListener: ((KeyHandle, CustomGestureView.Event) -> Boolean)? = null

    override val keyWidth get() = frame.width()
    override val keyHeight get() = frame.height()

    override val keyEnabled = true

    private val window = Rect()

    // the view's bounds were the face across and the cell down
    override val bounds: Rect?
        get() = window.takeIf { canvas.popupBounds(ref, it) }

    override val previewBounds: Rect?
        get() = Rect().takeIf { canvas.popupBounds(ref, it, head = true) }

    override val feedback: View get() = keyboard

    override val timers: CoroutineScope by lazy { keyboard.findViewTreeLifecycleOwner()!!.lifecycleScope }

    // [frame] is the key's cell in the keyboard, the canvas laid in it at its own place
    override fun hotspot(x: Float, y: Float) = canvas.touchedAt(ref, frame.left + x - canvas.left, frame.top + y - canvas.top)

    override fun showPressed(pressed: Boolean) {
        if (this.pressed == pressed) return
        this.pressed = pressed
        canvas.showPressed(ref, pressed)
    }

    // as a view's performClick: a screen reader told it was clicked after
    override fun click(): Boolean = click(ref)

    // as a view's performLongClick: told first
    override fun longClick(): Boolean = longClick(ref)

    /** A split row's second copy, wired as the first, told on its own node. */
    fun click(node: KeyRef): Boolean {
        val l = click
        l?.invoke(this)
        canvas.tell(node, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return l != null
    }

    fun longClick(node: KeyRef): Boolean {
        canvas.tell(node, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        return longClick?.invoke(this) ?: false
    }

    /** A screen reader's long click offered. */
    val longClickable: Boolean get() = longClick != null && keyGesture.longPressNow()
    override fun doubleTap() {
        doubleTapAction?.invoke(this)
    }

    override fun repeat() {
        repeatAction?.invoke(this)
    }

    override fun onGesture(event: CustomGestureView.Event): Boolean? = gestureListener?.invoke(this, event)

    /** Its touch long cancelled ([KeyGesture.tap]). */
    internal fun tap(cell: Rect, x: Float, y: Float) {
        frame.set(cell)
        keyGesture.tap(x - cell.left, y - cell.top)
    }

    internal fun touch(cell: Rect, action: Int, x: Float, y: Float, pointerId: Int, eventTime: Long) {
        frame.set(cell)
        keyGesture.onTouch(action, x - cell.left, y - cell.top, pointerId, eventTime)
    }

    override fun onClick(listener: (KeyHandle) -> Unit) {
        click = listener
    }

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

    /** Glide typing's key, where its view was. */
    override fun place(keyboard: ViewGroup, out: Rect) {
        if (canvas.cell(ref, out)) out.offset(canvas.left, canvas.top) else out.setEmpty()
    }
}

/**
 * Each finger given, as it goes down, to the key the hit areas of the scene drawn last find there. One
 * the scene drawn last has, hidden since (an address's keys), takes no touch till the next frame's scene.
 */
class SceneTouch(
    private val canvas: KeyboardCanvasView,
    private val keyFor: (KeyNode) -> SceneKey?,
    private val shown: (SceneKey) -> Boolean,
) {

    /**
     * A finger in a row's blank end (before A) is on the key by its hit area, outside its cell. A split
     * row's two copies may be one key, each finger in its own copy's cell.
     */
    internal class Pointer(val key: SceneKey, val cell: Rect, private val disabled: Boolean = false) : TouchTarget {
        override val ref get() = key.ref
        // `:disabled` (展开候选's page that cannot turn) takes no touch, as its view did not
        override val enabled get() = key.keyEnabled && !disabled

        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) {
            key.touch(cell, action, event.getX(pointerIndex), event.getY(pointerIndex), event.getPointerId(pointerIndex), event.eventTime)
        }

        override fun cancel() = key.keyGesture.cancelGestures()
    }

    fun keyOf(node: KeyNode): SceneKey? = keyFor(node)

    /** Null in a gap or on a key not shown. */
    internal fun find(x: Float, y: Float): Pointer? {
        val node = canvas.keyAt(x - canvas.left, y - canvas.top) ?: return null
        val key = keyOf(node)?.takeIf(shown) ?: return null
        val cell = Rect().apply { set(node.bounds) }
        cell.offset(canvas.left, canvas.top)
        return Pointer(key, cell, KeyState.Disabled in node.states)
    }
}
