/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform.jvm

import java.util.concurrent.Executor

/** The JVM's threads, as the engine's work off the input thread runs on them unless a test gives its own. */
object JvmThreads {
    /** Each piece of work on a daemon thread of its own named [name]: none kept, so an engines closed and made anew still has one. */
    fun each(name: String) = Executor { Thread(it, name).apply { isDaemon = true }.start() }
}
