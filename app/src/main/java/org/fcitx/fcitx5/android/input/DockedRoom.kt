/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.View
import android.view.ViewGroup
import splitties.dimensions.dp
import kotlin.math.abs

/**
 * The room the docked keyboard and the app share, px, as [view] (the input view) was laid out: its height, the input
 * method's window as the system made it, less the bottom margin of [kept], what it keeps under the keyboard for the
 * navigation bar. Null before the view is laid out: then KeyboardArea.predictedRoom (ImeRoom), which errs to the app's
 * side; rotating makes the view anew, so the first frame of each turn is a prediction (061-landscape-height §5.2).
 * Floating ([docked] false) has no room of its own.
 */
internal class DockedRoom(
    private val view: View,
    private val kept: View,
    private val docked: () -> Boolean,
    private val relayout: () -> Unit,
) {
    // the room the keys were last worked out in (null: predicted)
    private var used: Int? = null

    // a relayout posted and not run yet: a layout pass and the insets' coming in one frame post one, not two
    private var posted = false

    init {
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) check()
        }
    }

    private fun now(): Int? = if (view.height > 0) view.height - (kept.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin else null

    /** The room to work the keys out in now, remembered as the one they were. */
    fun take(): Int? = now().also { used = it }

    /**
     * After a layout or the bars' insets: the room changed (laid out the first time, the insets came, an app went full
     * screen) by more than a dp, [relayout] after this layout, not in it (a layout param changed in a layout pass is
     * lost or loops).
     */
    fun check() {
        if (!docked()) return
        val now = now() ?: return
        val was = used
        if (was != null && abs(now - was) <= view.dp(1)) return
        if (posted) return
        posted = true
        view.post {
            posted = false
            if (docked()) relayout()
        }
    }
}
