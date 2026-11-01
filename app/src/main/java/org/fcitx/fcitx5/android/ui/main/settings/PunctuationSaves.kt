/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.utils.LatestWriter

/**
 * The punctuation page's saves, one [LatestWriter] on [scope] per language for all instances of
 * the page: one made anew (a rotation) reads the table once the saves of the one it replaces have
 * landed, and its own land after them. Saves hold the connection from [connect] until the newest
 * lands, so that fcitx keeps running for them: on the page's, which ends with the page, a save
 * waiting for fcitx would land at its next start, after the newer ones had failed as disconnected.
 * A save fcitx's restart catches is made again once it is ready ([landing]).
 * Used on the main thread, where the writes run too.
 */
internal class PunctuationSaves(
    private val scope: CoroutineScope,
    private val connect: () -> HostConnection,
    private val disconnect: () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {

    private val writers = mutableMapOf<String, LatestWriter>()

    // the newest table saved of each language, until it has landed
    private val unsaved = MutableStateFlow(emptyMap<String, List<PunctuationMapEntry>>())

    suspend fun load(fcitx: HostConnection, lang: String): List<PunctuationMapEntry> {
        unsaved.first { lang !in it }
        return fcitx.runOnReady { PunctuationManager.load(this, lang) }
    }

    /** Saves [entries], a copy the caller no longer changes, as [lang]'s table. */
    fun save(lang: String, entries: List<PunctuationMapEntry>) {
        val connection = connect()
        unsaved.update { it + (lang to entries) }
        writers.getOrPut(lang) { LatestWriter(scope, onFailure) }.submit {
            try {
                do {
                    val landed = connection.landing { PunctuationManager.save(this, lang, entries) }
                } while (!landed)
            } finally {
                // one waiting behind it still needs fcitx, landed or failed as this one is
                unsaved.update { if (it[lang] === entries) it - lang else it }
                if (unsaved.value.isEmpty()) disconnect()
            }
        }
    }
}

/**
 * Whether [write] landed once fcitx was ready, or false if fcitx stopped first (a restart). Its
 * stop drops the calls queued for it, and one dropped never returns, cancelled or not: it would
 * hold up every save of the language after it, and every load of the page. So it is not waited
 * for; it is cancelled, and left where it hangs.
 */
private suspend fun HostConnection.landing(write: suspend HostApi.() -> Unit): Boolean {
    // fcitx's scope, whose coroutines its stop cancels
    val stopped = lifecycleScope.launch { awaitCancellation() }
    // not a child of the save's, which would wait for it
    val running = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { runOnReady(write) }
    try {
        return select<Boolean> {
            running.onAwait { true }
            stopped.onJoin {
                running.cancel()
                false
            }
        }
    } finally {
        stopped.cancel()
    }
}
