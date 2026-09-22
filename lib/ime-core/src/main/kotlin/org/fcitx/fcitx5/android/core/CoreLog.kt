/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/**
 * Debug logging for `:lib:ime-core`.
 *
 * This module is a plain JVM library, so it cannot depend on Timber (an AAR). The app wires
 * [sink] to Timber at startup; in a unit test it stays null and logging is a no-op.
 *
 * The message is a lambda so nothing is formatted when no sink is attached -- which is also
 * the common case on device, since these were `Timber.d` calls behind a verbose-log setting.
 */
object CoreLog {

    @Volatile
    var sink: ((String) -> Unit)? = null

    inline fun d(message: () -> String) {
        sink?.invoke(message())
    }
}
