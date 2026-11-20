/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/** A plain JVM library cannot use Timber (an AAR): the app wires [sink]; null in tests. [d] whenever a sink is set, [v] only with [verbose]; messages are lambdas so dropped ones are never formatted. */
object CoreLog {

    @Volatile
    var sink: ((String) -> Unit)? = null

    @Volatile
    var verbose: Boolean = false

    /** Always passed on (the exported log keeps it), whatever [verbose] and debug logging say. */
    @Volatile
    var errorSink: ((String) -> Unit)? = null

    fun e(message: String) {
        errorSink?.invoke(message)
    }

    inline fun d(message: () -> String) {
        sink?.invoke(message())
    }

    inline fun v(message: () -> String) {
        if (verbose) sink?.invoke(message())
    }
}
