/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.flow.SharedFlow
import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * API of fcitx that hides lifecycle stuffs from [Fcitx]
 *
 * Functions can be safely used in any coroutine,
 * as the underlying operation is always dispatched in fcitx thread.
 */
interface FcitxAPI {

    enum class AddonDep {
        Required,
        Optional
    }

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

    suspend fun getAddonReverseDependencies(addon: String): List<Pair<String, AddonDep>>

    fun translate(str: String, domain: String = "fcitx5"): String

    suspend fun save()

    suspend fun reloadConfig()

    /** Reads again what the user added to the engine's input methods: custom phrases, dictionaries. */
    suspend fun reloadEngine()

    /** The words the user added, made as they typed, and blocked: see [Engines.userWords]. */
    suspend fun userWords(): List<Engines.UserWord>

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

    /** Adds and blocks the words of a list the user imports, a word a line: see [Engines.importWords]. */
    suspend fun importUserWords(lines: List<String>): Engines.Imported

    /** The user's words as [importUserWords] reads them: see [Engines.exportWords]. */
    suspend fun exportUserWords(): List<String>

    /**
     * Tells the engine [before], the text before the cursor, where the user put the cursor other
     * than by typing (a field focused, a tap): what they type next follows it.
     */
    suspend fun engineContext(before: String)

    suspend fun sendKey(key: String, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(c: Char, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: Int, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun select(idx: Int): Boolean
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

    suspend fun getAddonConfig(addon: String): RawConfig

    suspend fun setAddonConfig(addon: String, config: RawConfig)

    suspend fun getAddonSubConfig(addon: String, path: String): RawConfig

    suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig = RawConfig())

    suspend fun getImConfig(key: String): RawConfig

    suspend fun setImConfig(key: String, config: RawConfig)

    suspend fun addons(): Array<AddonInfo>
    suspend fun setAddonState(name: Array<String>, state: BooleanArray)

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
    suspend fun triggerCandidateAction(idx: Int, actionIdx: Int)

    suspend fun setCandidatePagingMode(mode: Int)
    suspend fun offsetCandidatePage(delta: Int)

    suspend fun triggerCandidateListTabAction(id: Int)

}