/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewGroup
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.KeyRef

interface KeyHandle : KeyGesture.Key {
    val ref: KeyRef

    /** The face across, to the cell's foot down. Null for a scene's key the scene drawn last has not. */
    val bounds: Rect?

    /** A key across rows' its first row's: the bubble over its top, not its foot. */
    val previewBounds: Rect? get() = bounds

    val keyGesture: KeyGesture

    var gestureListener: ((KeyHandle, Event) -> Boolean)?

    fun onClick(listener: (KeyHandle) -> Unit)

    fun onLongClick(listener: (KeyHandle) -> Boolean)
    fun onRepeat(listener: (KeyHandle) -> Unit)
    fun onDoubleTap(listener: (KeyHandle) -> Unit)
}

fun interface KeyPlace {
    /** Empty where it is nowhere now. */
    fun place(keyboard: ViewGroup, out: Rect)
}

/** Nowhere before it is drawn. */
class ScenePlace internal constructor(val ref: KeyRef, private val canvas: () -> KeyboardCanvasView?) : KeyPlace {
    override fun place(keyboard: ViewGroup, out: Rect) {
        val c = canvas()
        if (c != null && c.cell(ref, out)) out.offset(c.left, c.top) else out.setEmpty()
    }
}

internal interface TouchTarget {
    val ref: KeyRef

    val enabled: Boolean

    fun send(event: MotionEvent, action: Int, pointerIndex: Int)

    /** Nothing typed. */
    fun cancel()
}
