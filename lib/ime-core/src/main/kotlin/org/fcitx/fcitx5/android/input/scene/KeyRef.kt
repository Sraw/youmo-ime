/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

/** A key a touch, popup or press is about: a scene's key by [id], [copy] the second half's of a split row's key (G, V); or an old key view's ([view]). */
data class KeyRef(val id: String, val copy: Boolean = false) {

    companion object {
        private const val VIEW = "view:"

        fun view(viewId: Int) = KeyRef("$VIEW$viewId")

        /** A split row's second G is 1. */
        fun scene(id: String, copy: Int) = KeyRef(id, copy != 0)
    }
}
