/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** A layout whose Shift may come on by itself where a sentence starts (KeyboardWindow.updateAutoCaps). */
interface AutoCapsKeys {
    /** Caps lock on: no sentence start changes Shift. */
    val capsLocked: Boolean

    /** Shift on by itself where the editor [wants] a capital next, as [AutoCaps] decides. */
    fun applyAutoCaps(wants: Boolean)
}
