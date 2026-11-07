/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

/**
 * A scene's making told phase by phase ([SceneBuilder]: expand, place, cascade, paint), for a
 * profiler to show where the time goes (the app's: Android's Trace sections). Sections nest: one
 * begun is ended before the one around it.
 */
interface Phases {
    fun begin(name: String)

    fun end()

    /** Told nothing. */
    object None : Phases {
        override fun begin(name: String) = Unit

        override fun end() = Unit
    }
}

/** [block] as the section [name] of [this]. */
inline fun <T> Phases.section(name: String, block: () -> T): T {
    begin(name)
    try {
        return block()
    } finally {
        end()
    }
}
