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

/**
 * What the keyboards watch of the engine, as the [EngineBridge] last left it: written on the fcitx
 * thread, read on the main one. One for the process, as there is one host at a time (HostDaemon's
 * KotlinHost, its bridge; a JVM test's hosts one after another): the bridge's [EngineBridge.close]
 * puts it back ([reset]).
 */
object EngineState {

    /**
     * What the nine-key keyboard shows beside its keys: whether anything is typed, what its first
     * digits not yet taken may be, and which list that is (see Snapshot.syllables, syllablesId).
     */
    data class T9State(val composing: Boolean = false, val syllables: List<String> = emptyList(), val id: Int = 0)

    /**
     * The syllables offered, as the last event of input method [im] left them: whether anything is
     * typed, what its first keys not yet taken may be, which list that is. Each reader takes its
     * own input method's alone (the nine keys' column pinyin's or 双拼's, the code row's pinyin's), so
     * none shows another's: another's state means none offered, not no news (switching input
     * methods writes the old one's and the new one's at once, and a reader may see the last alone).
     * [im] is the router's active input method: the 14 keys and the nine keys answer as [InputMethodCatalog.PINYIN], never as
     * [InputMethodCatalog.PINYIN_KEYS] or [InputMethodCatalog.T9]. Set on the fcitx thread, read on the main one.
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

    /**
     * Whether English has a word typed (not a prediction offered), as its last event left it: on
     * the nine keys, 1 is then an apostrophe in the word. Set on the fcitx thread, read on the main one.
     */
    val englishTyping: StateFlow<Boolean> = englishTypingState

    /** Whether English suggests words, as the host's config last said ([EngineSettings.englishSuggest][org.fcitx.fcitx5.android.engine.runtime.EngineSettings.englishSuggest]). */
    @Volatile
    var englishSuggests = true
        internal set

    /**
     * The letter groups of the layout of its own a grouped keyboard shows (060-render-p3 T9b: a theme's 17
     * keys in place of pinyin's 14 or 18; SceneGroups.Taken.Own's written), the last one shown; null till
     * one is. Written on the main thread as that keyboard is made and shown, before any of its keys can be
     * tapped; read on the fcitx thread before each key ([EngineBridge.syncOwnKeys]), so its codes reach
     * the engine read by these groups. The keyboard's, not the engine's: kept over [reset].
     */
    @Volatile
    var ownKeys: List<String>? = null

    internal val candidateReadingState = MutableStateFlow(ReadingStyle.OFF)

    /**
     * 候选词注音, as the host's config last said ([EngineSettings.candidateReading][org.fcitx.fcitx5.android.engine.runtime.EngineSettings.candidateReading]):
     * the bar's height goes by it, so the service makes the input view anew when it changes.
     */
    val candidateReading: StateFlow<ReadingStyle> = candidateReadingState

    internal val inkEnded = MutableStateFlow(HandwritingSession.NO_PIECE)

    /**
     * The latest piece of handwriting the engine ended (Engines.inkEnded): the pad wipes the piece
     * it is writing once it is among them. Set on the fcitx thread, read on the main one.
     */
    val handwritingEnded: StateFlow<Int> = inkEnded

    internal val tableKeyboardState = MutableStateFlow<TableKeyboard?>(null)

    /**
     * What the last table input method answering said of its keyboard (Engines.tableKeyboard): its
     * 拼 key, its roots, the keys of its codes; another's state means none is known of the one shown.
     * Set on the fcitx thread, read on the main one.
     */
    val tableKeyboard: StateFlow<TableKeyboard?> = tableKeyboardState

    /** The 双拼 scheme the user set, for the keyboard to tell its keys from text (InputMethodCatalog.readsAsKey): set on the fcitx thread, read on the main one. */
    @Volatile
    var shuangpinScheme: ShuangpinScheme = ShuangpinScheme.XIAOHE
        // the bridge's to set; a JVM test sets the scheme the engine would have read (no engine there)
        internal set

    /**
     * The input methods 简繁 turns traditional (chttrans' list), for the keyboard's hints: set on the fcitx
     * thread, read on the main one. Null till the host has read it: unknown, not none.
     */
    @Volatile
    var traditionalIms: Set<String>? = null
        internal set

    /**
     * Whether the input method of the last [answer][EngineBridge.answer] has refining left (the bridge's last answer), its snapshot's
     * [refines][org.fcitx.fcitx5.android.engine.session.Snapshot.refines]: the host then sends it [EngineEvent.REFINE][org.fcitx.fcitx5.android.engine.runtime.EngineEvent.REFINE] while the user
     * pauses. Written on the fcitx thread once an event is answered, and left as it was while one
     * runs: read off that thread during a refining step (EngineEvalRunner), it is still true.
     */
    @Volatile
    var refining = false
        internal set

    // the engine's session state, as no engine left it; the config's (the scheme, suggesting) stays, read again at the next start
    internal fun reset() {
        refining = false
        syllablesState.value = SyllableState()
        englishTypingState.value = false
        inkEnded.value = HandwritingSession.NO_PIECE
        tableKeyboardState.value = null
    }
}
