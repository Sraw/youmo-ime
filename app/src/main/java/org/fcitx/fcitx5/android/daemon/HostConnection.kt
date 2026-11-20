/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import kotlinx.coroutines.CoroutineScope
import org.fcitx.fcitx5.android.core.HostApi

interface HostConnection {

    /** Runs [block] in the caller's thread now; only for non-blocking operations such as [HostApi.eventFlow]. */
    fun <T> runImmediately(block: suspend HostApi.() -> T): T

    /** Runs [block] in the caller's thread, suspending until fcitx is ready. The usual choice. */
    suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T

    /** Runs [block] in a thread pool if fcitx is ready, else does nothing; never blocks the caller. */
    fun runIfReady(block: suspend HostApi.() -> Unit)

    val lifecycleScope: CoroutineScope
}