/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.ViewParent
import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Bounds
import java.lang.ref.WeakReference

/**
 * Where the docked keyboard was as its input view was taken out while shown: a floating one made in
 * its place (悬浮键盘 switched on, from the status area or anywhere) shrinks from there. The service
 * takes the old view out of its frame and puts the new one in at once, so only the next view put in
 * that same [ViewParent] [take]s it, and whatever comes next clears it: a view put in later, in
 * another frame (the service made anew), never shrinks from a keyboard long gone.
 */
object DockedBounds {

    private var frame: WeakReference<ViewParent>? = null
    private var bounds: Bounds? = null

    /** The docked keyboard at [bounds] in [frame], its view being taken out. */
    fun leave(frame: ViewParent, bounds: Bounds) {
        this.frame = WeakReference(frame)
        this.bounds = bounds
    }

    /** A view put in [frame]: the bounds left there just before, if any; cleared either way. */
    fun take(frame: ViewParent?): Bounds? {
        val left = bounds.takeIf { frame != null && this.frame?.get() === frame }
        this.frame = null
        bounds = null
        return left
    }
}
