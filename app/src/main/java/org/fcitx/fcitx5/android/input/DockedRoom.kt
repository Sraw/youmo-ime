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
 * Null before the view is laid out: then KeyboardArea.predictedRoom, which errs to the app's side; rotating
 * makes the view anew, so the first frame of each turn is a prediction. Floating has no room of its own.
 */
internal class DockedRoom(
    private val view: View,
    private val kept: View,
    private val docked: () -> Boolean,
    private val relayout: () -> Unit,
) {
    // null: predicted
    private var used: Int? = null

    // a layout pass and the insets' coming in one frame post one, not two
    private var posted = false

    init {
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) check()
        }
    }

    private fun now(): Int? = if (view.height > 0) view.height - (kept.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin else null

    fun take(): Int? = now().also { used = it }

    /** [relayout] after this layout, not in it: a layout param changed in a layout pass is lost or loops. */
    fun check() {
        if (!docked()) return
        val now = now() ?: return
        val was = used
        if (was != null && abs(now - was) <= view.dp(1)) return
        if (posted) return
        posted = true
        view.post {
            posted = false
            // the input view replaced meanwhile (detached, its listeners gone): nothing of it to lay out again
            if (docked() && view.isAttachedToWindow) relayout()
        }
    }
}
