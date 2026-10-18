/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.glide.Glide
import kotlinx.coroutines.flow.SharedFlow
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * API of fcitx that hides lifecycle stuffs from [Fcitx]
 *
 * Functions can be safely used in any coroutine,
 * as the underlying operation is always dispatched in fcitx thread.
 */
interface FcitxAPI {

    /**
     * Subscribe this flow to receive event sent from fcitx
     */
    val eventFlow: SharedFlow<FcitxEvent<*>>

    val isReady: Boolean

    val inputMethodEntryCached: InputMethodEntry

    val statusAreaActionsCached: Array<Action>

    val clientPreeditCached: FormattedText

    val inputPanelCached: FcitxEvent.InputPanelEvent.Data

    fun setLogRule(verbose: Boolean)

    fun translate(str: String, domain: String = "fcitx5"): String

    suspend fun save()

    suspend fun reloadConfig()

    /** Reads again what the user added to the engine's input methods: custom phrases, dictionaries. */
    suspend fun reloadEngine()

    /** The words the user added, made as they typed, and blocked: see [Engines.userWords]. */
    suspend fun userWords(): List<Engines.UserWord>

    /** The user's words of [kind] alone: see [Engines.userWords]. */
    suspend fun userWords(kind: Engines.UserWord.Kind): List<Engines.UserWord>

    /** How many words of each kind the user has: see [Engines.userWordCounts]. */
    suspend fun userWordCounts(): Map<Engines.UserWord.Kind, Int>

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
    suspend fun removeUserWords(words: List<Engines.UserWord>)

    /** The English words the user typed: see [Engines.englishWords]. */
    suspend fun englishWords(): List<Engines.UserWord>

    /** Makes [text] an English word of the user's; false if it is none: see [Engines.addEnglishWord]. */
    suspend fun addEnglishWord(text: String): Boolean

    /** Forgets the English words [texts]: see [Engines.forgetEnglishWords]. */
    suspend fun forgetEnglishWords(texts: List<String>)

    /**
     * Adds and blocks the words of a list the user imports, a word a line: see [Engines.importWords].
     * [lines] are read as the engine takes them, on fcitx's thread: those of a local file, not of a
     * provider's stream, whose slowness would hold up the keyboard.
     */
    suspend fun importUserWords(lines: Sequence<String>): Engines.Imported

    /** The user's words as [importUserWords] reads them: see [Engines.exportWords]. */
    suspend fun exportUserWords(): List<String>

    /** The keys no letter the table input method [im] reads now, as its session has them: see [Engines.tableKeys]. */
    suspend fun tableKeys(im: String): Set<Char>

    /**
     * Tells the engine [before], the text before the cursor, where the user put the cursor other
     * than by typing (a field focused, a tap): what they type next follows it. [learning] off in a
     * field that asks not to be learned from, told "" (not known) there.
     */
    suspend fun engineContext(before: String, learning: Boolean = true)

    /**
     * What the handwriting pad read of its [piece], shown by the Ink key sent with it in the same
     * fcitx-thread turn: see [Engines.offerInk]. Whether the Ink key was sent: not where handwriting
     * cannot load or is not the current input method (the engine told all the same).
     */
    suspend fun offerInk(reading: InkReading, piece: Int): Boolean

    /**
     * A glide across the letters, read by English's or full pinyin's session at the Glide key sent with
     * it in the same fcitx-thread turn: see [Engines.offerGlide]. Whether the key was sent: not where
     * the current input method is neither or cannot load.
     */
    suspend fun offerGlide(glide: Glide): Boolean

    /** The handwriting pad's [piece] finished, told by the Ink key sent with it: see [Engines.inkFinished]; taken as [offerInk]. */
    suspend fun inkFinished(piece: Int): Boolean

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

    suspend fun availableIme(): Array<InputMethodEntry>
    suspend fun enabledIme(): Array<InputMethodEntry>

    suspend fun setEnabledIme(array: Array<String>)

    suspend fun toggleIme()
    suspend fun activateIme(ime: String)
    suspend fun enumerateIme(forward: Boolean = true)

    suspend fun currentIme(): InputMethodEntry

    suspend fun getGlobalConfig(): RawConfig

    suspend fun setGlobalConfig(config: RawConfig)

    suspend fun getAddonSubConfig(addon: String, path: String): RawConfig

    suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig = RawConfig())

    suspend fun getImConfig(key: String): RawConfig

    suspend fun setImConfig(key: String, config: RawConfig)

    suspend fun triggerQuickPhrase()
    suspend fun triggerUnicode()

    suspend fun focus(focus: Boolean = true)
    suspend fun focusOutIn()
    suspend fun activate(uid: Int, pkgName: String)
    suspend fun deactivate(uid: Int)
    suspend fun setCapFlags(flags: CapabilityFlags)

    suspend fun statusArea(): Array<Action>

    suspend fun activateAction(id: Int)

    suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord>

    suspend fun getCandidateActions(idx: Int): Array<CandidateAction>

    /** Does nothing once the [idx]th candidate no longer shows [text], the one long-pressed: see [select]. */
    suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String)

    suspend fun setCandidatePagingMode(mode: Int)
    suspend fun offsetCandidatePage(delta: Int)

    suspend fun triggerCandidateListTabAction(id: Int)

}