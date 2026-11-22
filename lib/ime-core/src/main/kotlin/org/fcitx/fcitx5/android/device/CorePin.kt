/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

/**
 * Runs a stretch of pure computation on chosen cores. Only stretches that start no threads and hand off no
 * tasks may run here, as a new thread inherits the pinned mask.
 */
interface CorePin {
    fun <T> run(block: () -> T): T

    companion object {
        val NONE = object : CorePin {
            override fun <T> run(block: () -> T): T = block()
        }
    }
}
