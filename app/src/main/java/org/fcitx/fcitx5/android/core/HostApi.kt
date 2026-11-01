/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.flow.SharedFlow
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.ImportLayer
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry

/**
 * The host as the app reaches it, one interface for each of its parts (typing, the input methods,
 * the engine's options, punctuation, the user's words, learning), and what it last told the app.
 *
 * Functions can be safely used in any coroutine, as the underlying operation is always dispatched
 * in fcitx thread.
 */
interface HostApi : TypingApi, InputMethodsApi, EngineSettingsApi, PunctuationApi, LexiconApi, LearningApi {

    /**
     * Subscribe this flow to receive event sent from fcitx
     */
    val eventFlow: SharedFlow<HostEvent>

    val isReady: Boolean

    val inputMethodEntryCached: InputMethodEntry

    val statusAreaCached: List<StatusEntry>

    val clientPreeditCached: FormattedText

    val inputPanelCached: HostEvent.InputPanel

    /**
     * The candidates last sent, for the touch keyboard's bar: a view made anew shows them as the
     * engine has them (A2), not an empty bar under a half-typed code. [HostEvent.Candidates.EMPTY]
     * once a page ([pagedCached]) was sent since.
     */
    val candidatesCached: HostEvent.Candidates

    /** The page last sent, for the hardware keyboard's window; [HostEvent.Paged.EMPTY] once a bulk list was sent since. */
    val pagedCached: HostEvent.Paged

    suspend fun save()

    suspend fun reloadConfig()
}

/** What the keyboard sends and asks while the user types: keys, candidates, the field, the status area. */
interface TypingApi {

    suspend fun sendKey(key: String, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(c: Char, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: Int, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    /**
     * Picks the [idx]th candidate if it still shows [text], what the user tapped: a list refined
     * since may hold another word there, and false is returned. A null [text] (a key's pick) is
     * not checked.
     */
    suspend fun select(idx: Int, text: String? = null): Boolean
    suspend fun isEmpty(): Boolean
    suspend fun reset()
    suspend fun moveCursor(position: Int)

    suspend fun triggerQuickPhrase()
    suspend fun triggerUnicode()

    suspend fun focus(focus: Boolean = true)
    suspend fun focusOutIn()
    suspend fun activate(uid: Int, pkgName: String)
    suspend fun deactivate(uid: Int)
    suspend fun setCapFlags(flags: CapabilityFlags)

    suspend fun statusArea(): List<StatusEntry>

    suspend fun activateAction(id: Int)

    suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord>

    suspend fun getCandidateActions(idx: Int): Array<CandidateAction>

    /** Does nothing once the [idx]th candidate no longer shows [text], the one long-pressed: see [select]. */
    suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String)

    suspend fun setCandidatePagingMode(mode: Int)
    suspend fun offsetCandidatePage(delta: Int)

    /**
     * Tells the engine [before], the text before the cursor, where the user put the cursor other
     * than by typing (a field focused, a tap): what they type next follows it. [learning] off in a
     * field that asks not to be learned from, told "" (not known) there. [cleared]: [before] is
     * what was written in a field now empty (Action.Context.cleared). [started], [after] and
     * [selected] as Action.Context has them: another field started, the text after the cursor
     * where read, text selected; [said], the editor answered [before] itself (Action.Context.said).
     */
    suspend fun engineContext(
        before: String,
        learning: Boolean = true,
        cleared: Boolean = false,
        learnFlags: Int = 0,
        started: Boolean = false,
        after: String? = null,
        selected: Boolean = false,
        said: Boolean = false,
    )

    /**
     * What the handwriting pad read of its [piece], shown by the Ink key that carries it: see
     * [Engines.ink]. Whether the Ink key was sent: not where handwriting cannot load or is not the
     * current input method (the engine told all the same, holding it for its next).
     */
    suspend fun offerInk(reading: InkReading, piece: Int): Boolean

    /**
     * A glide across the letters, read as the current input method reads it and carried by the
     * Glide key that commits its word: see [Engines.glide]. Whether the key was sent: not where the
     * input method does not glide (InputMethodCatalog.glides), cannot load or cannot read the path.
     */
    suspend fun offerGlide(glide: Glide): Boolean

    /** The handwriting pad's [piece] finished, told by the Ink key that carries it: see [Engines.ink]; taken as [offerInk]. */
    suspend fun inkFinished(piece: Int): Boolean

    /** The keys no letter the table input method [im] reads now, as its session has them: see [Engines.tableKeys]. */
    suspend fun tableKeys(im: String): Set<Char>
}

/** The input methods: which there are, which the user enabled, the one in use. */
interface InputMethodsApi {

    suspend fun availableIme(): Array<InputMethodEntry>
    suspend fun enabledIme(): Array<InputMethodEntry>

    suspend fun setEnabledIme(array: Array<String>)

    suspend fun toggleIme()
    suspend fun activateIme(ime: String)
    suspend fun enumerateIme(forward: Boolean = true)

    suspend fun currentIme(): InputMethodEntry
}

/**
 * An input method's options (its settings page): each by its path (`Fuzzy/Name` under a group) to
 * its value as fcitx's INI writes it.
 */
interface EngineSettingsApi {

    /** [im]'s options, every one with its value; null for an input method with none. */
    suspend fun imOptions(im: String): Map<String, String>?

    /**
     * Sets those of [values] [im] has, the rest kept as they are: a page that shows only some
     * passes only those (InputMethodSettings.only), so it never writes back what another changed.
     */
    suspend fun setImOptions(im: String, values: Map<String, String>)
}

/** The punctuation map of a language, as the user edits it; only zh_CN has one. */
interface PunctuationApi {

    /** [lang]'s map in order; empty for a language with none. */
    suspend fun punctuationMap(lang: String): List<PunctuationMapEntry>

    /** Saves [entries] as [lang]'s map and uses it; nothing for a language with none. */
    suspend fun setPunctuationMap(lang: String, entries: List<PunctuationMapEntry>)
}

/** The engine's words: the user's own, added, blocked, imported, and the dictionary's readings of them. */
interface LexiconApi {

    /** Reads again what the user added to the engine's input methods: custom phrases, dictionaries. */
    suspend fun reloadEngine()

    /** The words the user added, made as they typed, and blocked: see [Engines.userWords]. */
    suspend fun userWords(): List<UserWord>

    /** The user's words of [kind] alone: see [Engines.userWords]. */
    suspend fun userWords(kind: UserWord.Kind): List<UserWord>

    /** How many words of each kind the user has: see [Engines.userWordCounts]. */
    suspend fun userWordCounts(): Map<UserWord.Kind, Int>

    /** The words of [texts] the tables among [ims] cannot take: see [Engines.sameCodes]. */
    suspend fun sameCodes(texts: List<String>, ims: List<String>): Map<String, List<Pair<String, String>>>

    /** Where each of [texts] splits into words: see [Engines.wordBoundaries]. */
    suspend fun wordBoundaries(texts: List<String>): Map<String, IntArray>

    /** How likely each of [texts] is: see [Engines.logProbs]. */
    suspend fun logProbs(texts: List<String>): FloatArray

    /** How the dictionary reads [text], for the user to check: see [Engines.pinyinOf]. */
    suspend fun pinyinOf(text: String): String?

    /** Adds [text] read as [pinyin]; false if it does not read so. */
    suspend fun addUserWord(text: String, pinyin: String): Boolean

    /** Blocks [text] read as [pinyin], never offered again; false if it does not read so. */
    suspend fun blockUserWord(text: String, pinyin: String): Boolean

    /** Takes [words] off their lists: see [Engines.removeWords]. */
    suspend fun removeUserWords(words: List<UserWord>)

    /** The English words the user typed: see [Engines.englishWords]. */
    suspend fun englishWords(): List<UserWord>

    /** Makes [text] an English word of the user's; false if it is none: see [Engines.addEnglishWord]. */
    suspend fun addEnglishWord(text: String): Boolean

    /** Forgets the English words [texts]: see [Engines.forgetEnglishWords]. */
    suspend fun forgetEnglishWords(texts: List<String>)

    /**
     * The words of a list the user imports, a word a line, from the file named [fileName]: the
     * user's own export added back, another's imported as a batch: see [Engines.importWords].
     * [lines] are read as the engine takes them, on fcitx's thread: those of a local file, not of a
     * provider's stream, whose slowness would hold up the keyboard.
     */
    suspend fun importUserWords(lines: Sequence<String>, fileName: String): Engines.Imported

    /** The lists imported, oldest first: see [Engines.importBatches]. */
    suspend fun importBatches(): List<ImportLayer.Batch>

    /** The words only imported, of [batch] if given: see [Engines.importedWords]. */
    suspend fun importedWords(batch: Int?): List<ImportLayer.Word>

    /** Undoes the list imported as [batch]: see [Engines.dropImport]. */
    suspend fun dropImport(batch: Int): Int?

    /** Puts back the list [dropImport] took as [batch] (撤销): see [Engines.restoreImport]. */
    suspend fun restoreImport(batch: Int): Boolean
}

/** What was learned: see [learning]. */
interface LearningApi {

    /**
     * What the user asks of what was learned (PLAN §2.8, Engines.learning), of the input method in
     * use. A long press's act goes as the menu's always did, the list it leaves shown; one that
     * changes what is shown otherwise (taken back, cleared, habits on or off) has it shown anew.
     */
    suspend fun learning(op: LearningOp): LearningResult
}
