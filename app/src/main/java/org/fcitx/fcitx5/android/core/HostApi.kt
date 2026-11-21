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
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CardReading
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.ImportLayer
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones

/** Every call is dispatched to the fcitx thread, so any coroutine may call. */
interface HostApi : TypingApi, InputMethodsApi, EngineSettingsApi, PunctuationApi, LexiconApi, LearningApi {

    val eventFlow: SharedFlow<HostEvent>

    val isReady: Boolean

    val inputMethodEntryCached: InputMethodEntry

    /** [InputMethodsApi.enabledIme] as last changed: 选输入法's menu opens at a swipe's threshold, no time to ask the host. */
    val enabledImeCached: List<InputMethodEntry>

    val statusAreaCached: List<StatusEntry>

    /** Null while no host has said (none started, or stopped). */
    val punctuationCached: Map<String, String>?

    val fullWidthCached: Boolean

    val clientPreeditCached: FormattedText

    val inputPanelCached: HostEvent.InputPanel

    /** The one test of "something typed" (联想's words alone are not); exact in a fcitx job, while main's mirror lags. */
    val composingCached: Boolean get() = clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()

    /** So a remade view shows the engine's candidates, not an empty bar; EMPTY once a page ([pagedCached]) was sent since. */
    val candidatesCached: HostEvent.Candidates

    /** For the hardware keyboard's window; EMPTY once a bulk list was sent since. */
    val pagedCached: HostEvent.Paged

    suspend fun save()

    suspend fun reloadConfig()
}

interface TypingApi {

    /** [touch]: where a tap fell and its neighbours, carried with the key. */
    suspend fun sendKey(key: String, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1, touch: Touch? = null)

    suspend fun sendKey(c: Char, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: Int, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int = 0, up: Boolean = false, timestamp: Int = -1, touch: Touch? = null)

    /** False if a refine moved another word to [idx]; a null [text] (a key's pick) is not checked. */
    suspend fun select(idx: Int, text: String? = null): Boolean
    suspend fun isEmpty(): Boolean

    /** [drop] drops what is typed instead of committing it. */
    suspend fun reset(drop: Boolean = false)

    /** False where none show; the 联想 setting stays. */
    suspend fun dropPredictions(): Boolean

    /** Null with nothing typed. */
    suspend fun clearTyped(keyboard: (im: String) -> Any?): HostCore.Retyping?

    suspend fun retype(typing: HostCore.Retyping, keyboard: Any?): Boolean
    suspend fun moveCursor(position: Int)

    suspend fun triggerQuickPhrase()

    /** Null for ＋添加, or none shown. */
    suspend fun quickPhraseAt(index: Int): QuickPhraseBook.Found?
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

    /** Does nothing once candidate [idx] no longer shows [text]: see [select]. */
    suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String)

    suspend fun setCandidatePagingMode(mode: Int)
    suspend fun offsetCandidatePage(delta: Int)

    /** For a cursor moved other than by typing; told "" where [learning] is off. The rest as Action.Context has them. */
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

    /** False where handwriting cannot load or the input method writes on no keys. */
    suspend fun offerInk(reading: InkReading, piece: Int): Boolean

    /** False where the input method does not glide, cannot load or cannot read the path. */
    suspend fun offerGlide(glide: Glide): Boolean

    suspend fun inkFinished(piece: Int): Boolean

    /** A table's offered word tapped: word [index] of offer [id], [before] the field's text before the cursor as tapped. */
    suspend fun rememberWord(id: Int, index: Int, before: String)

    /** The notice's 撤销 of [rememberWord]: [text] unsaved from [code] and its commit taken back. */
    suspend fun unrememberWord(text: String, code: String)

    /** Offer [id] stayed up on the notice strip long enough to read (WordOfferGate.SEEN_MS): one never seen is offered again. */
    suspend fun offerShown(id: Int)

    suspend fun tableKeys(im: String): Set<Char>
}

interface InputMethodsApi {

    suspend fun availableIme(): Array<InputMethodEntry>
    suspend fun enabledIme(): Array<InputMethodEntry>

    suspend fun setEnabledIme(array: Array<String>)

    suspend fun toggleIme()
    suspend fun activateIme(ime: String)

    /** [ime] made the default and saved, as [enumerateIme] does; [activateIme] keeps it to this field. */
    suspend fun switchIme(ime: String)
    suspend fun enumerateIme(forward: Boolean = true)

    suspend fun currentIme(): InputMethodEntry
}

/** Options are keyed by path (`Fuzzy/Name`), valued as fcitx's INI writes them. */
interface EngineSettingsApi {

    /** Null for an input method with none. */
    suspend fun imOptions(im: String): Map<String, String>?

    /** Only those given are set, so a page never writes back what another changed. */
    suspend fun setImOptions(im: String, values: Map<String, String>)
}

/** Read only: no page edits it any more. */
interface PunctuationApi {

    suspend fun punctuationMap(lang: String): List<PunctuationMapEntry>
}

interface LexiconApi {

    suspend fun reloadEngine()

    suspend fun userWords(): List<UserWord>

    suspend fun userWords(kind: UserWord.Kind): List<UserWord>

    suspend fun userWordCounts(): Map<UserWord.Kind, Int>

    suspend fun sameCodes(texts: List<String>, ims: List<String>): Map<String, List<Pair<String, String>>>

    suspend fun wordBoundaries(texts: List<String>): Map<String, IntArray>

    suspend fun logProbs(texts: List<String>): FloatArray

    suspend fun voiceHomophones(words: List<UserWord>): VoiceHomophones

    suspend fun voicePick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>): String

    suspend fun voiceStretches(texts: List<String>)

    suspend fun rareHeld(texts: Collection<String>): Set<String>

    suspend fun rareGrid(): RareGrid?

    suspend fun pinyinOf(text: String): String?

    suspend fun pinyinsOf(texts: List<String>): Map<String, String?>

    /** Null if candidate [index] no longer shows [text], or has no Han character, or the input method is not the engine's. */
    suspend fun candidateCard(index: Int, text: String, paged: Boolean): CandidateCard?

    suspend fun charCardOf(char: String, reading: CardReading?): CharCard?

    /** False if it does not read so. */
    suspend fun addUserWord(text: String, pinyin: String): Boolean

    suspend fun voiceSaid(text: String, before: String?, hinted: Boolean)

    suspend fun voicePutIn(text: String): VoiceEdits.Reply

    suspend fun voiceConfirm(before: String?, after: String?): VoiceEdits.Ask?

    suspend fun voiceEditsEnd()

    suspend fun voiceRemember(ask: VoiceEdits.Ask): Boolean

    suspend fun voiceForget(ask: VoiceEdits.Ask)

    /** False if it does not read so. */
    suspend fun blockUserWord(text: String, pinyin: String): Boolean

    suspend fun removeUserWords(words: List<UserWord>)

    suspend fun englishWords(): List<UserWord>

    suspend fun addEnglishWord(text: String): Boolean

    suspend fun forgetEnglishWords(texts: List<String>)

    /** [lines] are read on fcitx's thread: give a local file, not a provider's stream, whose slowness would hold up the keyboard. */
    suspend fun importUserWords(lines: Sequence<String>, fileName: String): Engines.Imported

    suspend fun importBatches(): List<ImportLayer.Batch>

    suspend fun dropImport(batch: Int): Int?

    suspend fun restoreImport(batch: Int): Boolean
}

interface LearningApi {

    /** A long press's act leaves its list shown; one that changes what is shown otherwise has it shown anew. */
    suspend fun learning(op: LearningOp): LearningResult
}
