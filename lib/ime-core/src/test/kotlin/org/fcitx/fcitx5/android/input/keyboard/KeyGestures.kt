/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** A recognizer as a key sets it up: a 100 × 50 key, an 8 px slop and 24 px steps unless given. */
internal fun recognizer(
    swipe: Boolean = true,
    longPress: Boolean = false,
    repeat: Boolean = false,
    swipeRepeat: Boolean = false,
    doubleTap: Boolean = false,
    width: Int = 100,
    height: Int = 50,
    thresholdX: Float = 24f,
    thresholdY: Float = 24f,
) = KeyGestureRecognizer().apply {
    touchSlop = 8f
    viewWidth = width
    viewHeight = height
    swipeEnabled = swipe
    longPressEnabled = longPress
    repeatEnabled = repeat
    swipeRepeatEnabled = swipeRepeat
    doubleTapEnabled = doubleTap
    swipeThresholdX = thresholdX
    swipeThresholdY = thresholdY
    doubleTapTimeoutMs = 300L
}

internal fun KeyGestureRecognizer.move(x: Float, y: Float) = onMove(x, y, longPressTriggered = false, repeatStarted = false)
