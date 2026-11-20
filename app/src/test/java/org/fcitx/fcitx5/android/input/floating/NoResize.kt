/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

object NoResize : FloatingHandle.Resizing {
    override fun start() = Unit
    override fun move(dx: Float, dy: Float) = Unit
    override fun end() = Unit
    override fun step(bigger: Boolean): Boolean = false
}
