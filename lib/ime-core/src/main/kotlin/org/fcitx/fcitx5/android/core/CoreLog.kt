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
 * Two levels, matching what the app logged before this module existed:
 * - [d]: plain debug logging, emitted whenever a sink is attached (debug builds, or release
 *   builds with verbose logging on).
 * - [v]: chatty tracing such as every state machine transition, emitted only when [verbose]
 *   is also set -- the "verbose log" developer setting, in any build.
 *
 * The message is a lambda so nothing is formatted when it would be dropped.
 */
object CoreLog {

    @Volatile
    var sink: ((String) -> Unit)? = null

    @Volatile
    var verbose: Boolean = false

    inline fun d(message: () -> String) {
        sink?.invoke(message())
    }

    inline fun v(message: () -> String) {
        if (verbose) sink?.invoke(message())
    }
}
