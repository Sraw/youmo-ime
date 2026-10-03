/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/** Answers when the test says: each question's future is kept for the test to complete or fail. */
internal class FakeRemoteModel : RemoteModel {
    val scored = ArrayList<Pair<String, List<String>>>()
    val scores = ArrayList<CompletableFuture<FloatArray>>()

    override fun score(context: String, candidates: List<String>): Future<FloatArray> {
        scored += context to candidates
        return CompletableFuture<FloatArray>().also { scores += it }
    }
}

/** A clock the test moves. */
internal class FakeClock(var now: Long = 0) : () -> Long {
    override fun invoke() = now
}
