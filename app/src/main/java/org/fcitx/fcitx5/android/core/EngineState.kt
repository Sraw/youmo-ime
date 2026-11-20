/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.table.TableKeyboard

/** What the keyboards watch of the engine: written on the fcitx thread, read on main. One per process, put back by [EngineBridge.close] ([reset]). */
object EngineState {

    /**
     * Each reader takes only its own input method's state, so another's means none offered, not no news (a switch
     * writes the old and new at once and a reader may see only the last). The 14 and nine keys answer as
     * [InputMethodCatalog.PINYIN], never [InputMethodCatalog.PINYIN_KEYS] or [InputMethodCatalog.T9].
     */
    data class SyllableState(
        val im: String = "",
        val composing: Boolean = false,
        val syllables: List<String> = emptyList(),
        val id: Int = 0,
    )

    @VisibleForTesting
    internal val syllablesState = MutableStateFlow(SyllableState())
    val syllables: StateFlow<SyllableState> = syllablesState

    internal val englishTypingState = MutableStateFlow(false)

    /** A word typed, not a prediction: on the nine keys 1 is then an apostrophe. */
    val englishTyping: StateFlow<Boolean> = englishTypingState

    @Volatile
    var englishSuggests = true
        internal set

    /**
     * The own-layout letter groups the grouped keyboard last showed; null till one is. Written on main as it is
     * attached, before any key can be tapped; read on the fcitx thread before each key ([EngineBridge.syncOwnKeys]).
     * The keyboard's, not the engine's: kept over [reset].
     */
    @Volatile
    var ownKeys: List<String>? = null

    internal val candidateReadingState = MutableStateFlow(ReadingStyle.OFF)

    /** The bar's height goes by it, so the service remakes the input view when it changes. */
    val candidateReading: StateFlow<ReadingStyle> = candidateReadingState

    internal val inkEnded = MutableStateFlow(HandwritingSession.NO_PIECE)

    /** The keys' ink wipes its piece once it is among those ended. */
    val handwritingEnded: StateFlow<Int> = inkEnded

    internal val tableKeyboardState = MutableStateFlow<TableKeyboard?>(null)

    /** Another table's state means nothing is known of the one shown. */
    val tableKeyboard: StateFlow<TableKeyboard?> = tableKeyboardState

    /** For the keyboard to tell its keys from text (InputMethodCatalog.readsAsKey). */
    @Volatile
    var shuangpinScheme: ShuangpinScheme = ShuangpinScheme.XIAOHE
        // a JVM test sets it (no engine there)
        internal set

    /** Null till the host has read it: unknown, not none. */
    @Volatile
    var traditionalIms: Set<String>? = null
        internal set

    /** Left as it was while an event runs, so a read during a refine step (EngineEvalRunner) sees true. */
    @Volatile
    var refining = false
        internal set

    // the config's (the scheme, suggesting) stays, read again at the next start
    internal fun reset() {
        refining = false
        syllablesState.value = SyllableState()
        englishTypingState.value = false
        inkEnded.value = HandwritingSession.NO_PIECE
        tableKeyboardState.value = null
    }
}
