/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

/**
 * A key a touch, a popup or a press is about, whichever draws it (060-render-p2 S1): a scene's key by
 * its [id] (`q`, `space`), [copy] the second of a key a split row draws on both halves (G, V); or a
 * key view of the old drawing by its view id ([view]), until P2 takes the views away.
 */
data class KeyRef(val id: String, val copy: Boolean = false) {

    /** The view id this names ([view]), or [NO_VIEW] for a scene's key. */
    val viewId: Int
        get() = if (id.startsWith(VIEW)) id.substring(VIEW.length).toIntOrNull() ?: NO_VIEW else NO_VIEW

    companion object {
        private const val VIEW = "view:"

        /** What [viewId] is for a key no view names: Android's `View.NO_ID`. */
        const val NO_VIEW = -1

        /** The key drawn by the view of id [viewId]. */
        fun view(viewId: Int) = KeyRef("$VIEW$viewId")

        /** A scene's key [id], the [copy]th of it (a split row's second G is 1). */
        fun scene(id: String, copy: Int) = KeyRef(id, copy != 0)
    }
}
