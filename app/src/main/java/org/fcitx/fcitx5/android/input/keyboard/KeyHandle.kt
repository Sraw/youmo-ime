/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewGroup
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fcitx.fcitx5.android.input.scene.KeyRef

/**
 * A key as its keyboard wires it (BaseKeyboard's wire, 060-render-p2 S1): what its touch does,
 * whatever draws it. A [KeyView], or a key the scene draws (canvas/SceneTouch's SceneKey): the
 * same wiring for both, so the canvas's keys do what the views did.
 */
interface KeyHandle : KeyGesture.Key {
    /** Which key it is, to a popup ([org.fcitx.fcitx5.android.input.popup.PopupAction.key]). */
    val ref: KeyRef

    /** Where a popup over it is placed, in the window: the face across, to the cell's foot down (KeyView.bounds). */
    val bounds: Rect

    /** Its touch: the gesture's settings and where the finger is. */
    val keyGesture: KeyGesture

    /** Told each step of its gesture ([KeyGesture.Key.onGesture]): whether it consumed it. */
    var gestureListener: ((KeyHandle, Event) -> Boolean)?

    fun onClick(listener: (KeyHandle) -> Unit)

    /** Its long press timed from now on: the listener says whether it took the touch. */
    fun onLongClick(listener: (KeyHandle) -> Boolean)
    fun onRepeat(listener: (KeyHandle) -> Unit)
    fun onDoubleTap(listener: (KeyHandle) -> Unit)
}

/** Where a key is in its keyboard: glide typing's letters ([GlideInput]). */
fun interface KeyPlace {
    /** Its touch area in [keyboard]'s pixels, into [out]; empty where it is nowhere now. */
    fun place(keyboard: ViewGroup, out: Rect)
}

/** A finger's key, for the rest of its touch (BaseKeyboard's routing): a key view, or a key the scene draws. */
internal interface TouchTarget {
    val ref: KeyRef

    /** A disabled key takes no touch (a blank one). */
    val enabled: Boolean

    /** Finger [pointerIndex] of [event] to the key, as [action] (a [MotionEvent]'s masked action). */
    fun send(event: MotionEvent, action: Int, pointerIndex: Int)

    /** Its touch taken away (the keyboard's cancelled or a new one began): nothing typed. */
    fun cancel()
}
