/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform.jvm

import java.util.concurrent.Executor

object JvmThreads {
    /** A fresh daemon thread per task: none kept, so engines closed and remade still have one. */
    fun each(name: String) = Executor { Thread(it, name).apply { isDaemon = true }.start() }
}
