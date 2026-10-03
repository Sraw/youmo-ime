/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import java.io.IOException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/**
 * A language model on a server of the user's own (`cloud/server.py`), far larger than a phone
 * holds, asked while the user pauses. Each call returns at once; the answer comes on a thread of
 * the implementation's, or fails, or never comes: what asks waits only so long.
 */
interface RemoteModel {
    /** log P, in nats, of each of [candidates] after [context], in their order. */
    fun score(context: String, candidates: List<String>): Future<FloatArray>

    companion object {
        /**
         * The model [current] gives at each question: the user may turn it off or move it while
         * sessions live. With none, each question fails at once, as an unreachable server would.
         */
        fun deferred(current: () -> RemoteModel?): RemoteModel = object : RemoteModel {
            override fun score(context: String, candidates: List<String>) = current()?.score(context, candidates) ?: none()

            private fun <T> none(): Future<T> = FutureTask<T> { throw IOException("no server") }.also { it.run() }
        }
    }
}
