/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs the writes it is given one at a time on [scope], skipping a write that a newer one replaced
 * while it waited: of edits saved as they are made, the newest lands last, also when a write waits
 * for fcitx to be ready and the next ones pile up behind it. A failed write goes to [onFailure].
 */
class LatestWriter(scope: CoroutineScope, private val onFailure: (Throwable) -> Unit) {

    private val pending = Channel<suspend () -> Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (write in pending) {
                // a failed write must not stop the ones after it
                runCatching { write() }.onFailure { if (it is CancellationException) throw it else onFailure(it) }
            }
        }
    }

    fun submit(write: suspend () -> Unit) {
        pending.trySend(write)
    }

    /** Still runs the write that is waiting, then ends; a write submitted after is dropped. */
    fun close() {
        pending.close()
    }
}
