/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [HostApi] for the JVM: config stored and read back, [addons] and [candidates] set up by tests, every
 * other engine request appended to [calls]. Simulates no input: push [events] to drive listeners.
 */
class FakeHostApi : HostApi {

    /** E.g. `"select(2)"`. Copy-on-write, as every list here the calls change: a test polls while a coroutine appends. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** What fcitx hands back with a key it did not take. */
    val keyTimestamps: MutableList<Int> = CopyOnWriteArrayList()

    val events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 64)
    override val eventFlow: SharedFlow<HostEvent> get() = events

    override var isReady = true
    override var inputMethodEntryCached = InputMethodEntry("fake")
    override var statusAreaCached: List<StatusEntry> = emptyList()
    // punctuation off: the keys known, none held back
    override var punctuationCached: Map<String, String>? = emptyMap()
    override var fullWidthCached = false
    override var clientPreeditCached = FormattedText.Empty
    override var inputPanelCached = HostEvent.InputPanel.EMPTY
    override var candidatesCached = HostEvent.Candidates.EMPTY
    override var pagedCached = HostEvent.Paged.EMPTY

    var candidates: Array<CandidateWord> = emptyArray()
    var availableIme: Array<InputMethodEntry> = emptyArray()
    var enabledIme: Array<InputMethodEntry> = emptyArray()

    private val imOptions = mutableMapOf<String, Map<String, String>>()
    private val punctuationMaps = mutableMapOf<String, List<PunctuationMapEntry>>()

    override suspend fun save() {
        calls += "save()"
    }

    override suspend fun reloadConfig() {
        calls += "reloadConfig()"
    }

    override suspend fun reloadEngine() {
        calls += "reloadEngine()"
    }

    val userWords: MutableList<UserWord> = CopyOnWriteArrayList()

    override suspend fun userWords(): List<UserWord> = userWords.toList()

    override suspend fun userWords(kind: UserWord.Kind): List<UserWord> = userWords.filter { it.kind == kind }

    override suspend fun userWordCounts(): Map<UserWord.Kind, Int> =
        UserWord.Kind.entries.associateWith { kind -> userWords.count { it.kind == kind } }

    override suspend fun sameCodes(texts: List<String>, ims: List<String>): Map<String, List<Pair<String, String>>> = emptyMap()

    /** No dictionary here: none unless set. */
    val readings = mutableMapOf<String, String>()

    override suspend fun pinyinOf(text: String): String? = readings[text]

    override suspend fun pinyinsOf(texts: List<String>): Map<String, String?> = texts.associateWith { readings[it] }

    /** By the candidate's text; none unless set. */
    val candidateCards = mutableMapOf<String, CandidateCard>()

    override suspend fun candidateCard(index: Int, text: String, paged: Boolean): CandidateCard? = candidateCards[text]

    val charCards = mutableMapOf<String, CharCard>()
    val charCardsAsked: MutableList<Pair<String, CardReading?>> = CopyOnWriteArrayList()

    /** If set, [charCardOf] answers only once it completes. */
    var charCardGate: CompletableDeferred<Unit>? = null

    override suspend fun charCardOf(char: String, reading: CardReading?): CharCard? {
        charCardsAsked += char to reading
        charCardGate?.await()
        return charCards[char]
    }

    override suspend fun wordBoundaries(texts: List<String>) = texts.associateWith { IntArray(it.length + 1) { i -> i } }

    // all alike: the recognizer's order kept
    override suspend fun logProbs(texts: List<String>) = FloatArray(texts.size)
    override suspend fun voiceHomophones(words: List<UserWord>) = VoiceHomophones.NONE
    override suspend fun voiceHomophonesAllowed(spans: List<VoiceHomophones.Span>) = BooleanArray(spans.size)

    override suspend fun rareHeld(texts: Collection<String>) = emptySet<String>()

    var rareGridAnswer: RareGrid? = null
    override suspend fun rareGrid() = rareGridAnswer

    override suspend fun addUserWord(text: String, pinyin: String): Boolean {
        calls += "addUserWord($text, $pinyin)"
        userWords += UserWord(text, pinyin, UserWord.Kind.ADDED)
        return true
    }

    // no engine to follow voice's text (EngineBackedHostApi has one)
    override suspend fun voiceSaid(text: String, before: String?, hinted: Boolean) {
        calls += "voiceSaid($text, $before, $hinted)"
    }

    /** Confirmed as it is ([voiceConfirm]); none unless set. */
    var voiceAsk: VoiceEdits.Ask? = null

    /** If set, [voicePutIn] answers only once it completes. */
    var voicePutInHeld: CompletableDeferred<Unit>? = null

    override suspend fun voicePutIn(text: String): VoiceEdits.Reply {
        calls += "voicePutIn($text)"
        voicePutInHeld?.await()
        return VoiceEdits.Reply(true, voiceAsk)
    }

    override suspend fun voiceConfirm(before: String?, after: String?): VoiceEdits.Ask? {
        calls += "voiceConfirm($before, $after)"
        return voiceAsk.also { voiceAsk = null }
    }
    override suspend fun voiceEditsEnd() {
        calls += "voiceEditsEnd()"
    }

    override suspend fun voiceRemember(ask: VoiceEdits.Ask) = addUserWord(ask.word, ask.pinyin)
    override suspend fun voiceForget(word: String) {
        calls += "voiceForget($word)"
        userWords.removeAll { it.text == word && it.kind == UserWord.Kind.ADDED }
    }

    override suspend fun blockUserWord(text: String, pinyin: String): Boolean {
        calls += "blockUserWord($text, $pinyin)"
        userWords += UserWord(text, pinyin, UserWord.Kind.BLOCKED)
        return true
    }

    override suspend fun removeUserWords(words: List<UserWord>) {
        calls += "removeUserWords(${words.joinToString(",") { it.text }})"
        userWords -= words.toSet()
    }

    val englishWords: MutableList<UserWord> = CopyOnWriteArrayList()

    override suspend fun englishWords(): List<UserWord> = englishWords.toList()

    override suspend fun addEnglishWord(text: String): Boolean {
        calls += "addEnglishWord($text)"
        englishWords += UserWord(text, "", UserWord.Kind.ADDED, 2f, neverCorrected = true)
        return true
    }

    override suspend fun forgetEnglishWords(texts: List<String>) {
        calls += "forgetEnglishWords(${texts.joinToString(",")})"
        englishWords.removeAll { it.text in texts }
    }

    override suspend fun importUserWords(lines: Sequence<String>, fileName: String): Engines.Imported {
        // a file's lines may be read only once
        val count = lines.count()
        calls += "importUserWords($count, $fileName)"
        return Engines.Imported(0, 0, count)
    }

    val importBatches: MutableList<ImportLayer.Batch> = CopyOnWriteArrayList()

    override suspend fun importBatches(): List<ImportLayer.Batch> = importBatches.toList()

    override suspend fun dropImport(batch: Int): Int? {
        calls += "dropImport($batch)"
        val dropped = importBatches.firstOrNull { it.id == batch } ?: return null
        importBatches.remove(dropped)
        return dropped.left
    }

    override suspend fun restoreImport(batch: Int): Boolean {
        calls += "restoreImport($batch)"
        return false
    }

    /** By table input method; none for any other, as the engine. */
    val tableKeys = HashMap<String, Set<Char>>()

    override suspend fun tableKeys(im: String): Set<Char> = tableKeys[im].orEmpty()

    override suspend fun engineContext(
        before: String,
        learning: Boolean,
        cleared: Boolean,
        learnFlags: Int,
        started: Boolean,
        after: String?,
        selected: Boolean,
        said: Boolean,
    ) {
        calls += "engineContext($before, $learning" + (if (cleared) ", cleared" else "") + (if (learnFlags != 0) ", flags $learnFlags" else "") +
            (if (started) ", started" else "") + (after?.let { ", after $it" } ?: "") + (if (selected) ", selected" else "") + (if (said) ", said)" else ")")
    }

    override suspend fun offerInk(reading: InkReading, piece: Int): Boolean {
        calls += "offerInk($piece, ${reading.texts.firstOrNull()})"
        return true
    }

    var learned: (LearningOp) -> LearningResult = { LearningResult.Done() }

    /** Where a test waits on something else, as the host's thread would. */
    var whileLearning: suspend (LearningOp) -> Unit = {}

    override suspend fun learning(op: LearningOp): LearningResult {
        calls += "learning($op)"
        whileLearning(op)
        return learned(op)
    }

    val glides: MutableList<Glide> = CopyOnWriteArrayList()

    override suspend fun offerGlide(glide: Glide): Boolean {
        calls += "offerGlide"
        glides += glide
        return true
    }

    override suspend fun inkFinished(piece: Int): Boolean {
        calls += "inkFinished($piece)"
        return true
    }

    /** ms by the key's turn: a busy engine (FastTypingTest). */
    var keyDelay: (Int) -> Long = { 0L }
    private var keysTaken = 0

    private suspend fun slow() {
        val ms = keyDelay(keysTaken++)
        if (ms > 0) kotlinx.coroutines.delay(ms)
    }

    val touches: MutableList<Pair<String, Touch>> = CopyOnWriteArrayList()

    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int, touch: Touch?) {
        slow()
        calls += "sendKey(key=$key, states=$states, code=$code, up=$up)"
        if (touch != null) touches += key to touch
    }

    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        slow()
        calls += "sendKey(char=$c, states=$states, code=$code, up=$up)"
    }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        slow()
        calls += "sendKey(sym=$sym, states=$states, code=$code, up=$up)"
    }

    override suspend fun sendKey(
        sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int, touch: Touch?
    ) {
        slow()
        calls += "sendKey(sym=${sym.sym}, states=${states.states}, code=$code, up=$up)"
        if (touch != null) touches += "sym=${sym.sym}" to touch
        keyTimestamps += timestamp
    }

    override suspend fun select(idx: Int, text: String?): Boolean {
        calls += if (text == null) "select($idx)" else "select($idx, $text)"
        return idx in candidates.indices && (text == null || candidates[idx].text == text)
    }

    override suspend fun isEmpty() = inputPanelCached.preedit.isEmpty() && candidates.isEmpty()

    override suspend fun reset(drop: Boolean) {
        calls += if (drop) "reset(drop)" else "reset()"
    }

    override suspend fun dropPredictions(): Boolean {
        calls += "dropPredictions()"
        return candidatesCached.predicting
    }

    override suspend fun clearTyped(keyboard: (im: String) -> Any?): HostCore.Retyping? {
        calls += "clearTyped()"
        return null
    }

    override suspend fun retype(typing: HostCore.Retyping, keyboard: Any?): Boolean {
        calls += "retype(${typing.im})"
        return false
    }

    override suspend fun moveCursor(position: Int) {
        calls += "moveCursor($position)"
    }

    override suspend fun availableIme() = availableIme
    override suspend fun enabledIme() = enabledIme

    override suspend fun setEnabledIme(array: Array<String>) {
        calls += "setEnabledIme(${array.joinToString()})"
        val current = inputMethodEntryCached.uniqueName
        val wasEnabled = enabledIme.any { it.uniqueName == current }
        // in the order given, as fcitx keeps it
        enabledIme = array.mapNotNull { name -> availableIme.find { it.uniqueName == name } }.toTypedArray()
        // the current one removed: fcitx turns to the first left
        if (wasEnabled && enabledIme.none { it.uniqueName == current }) enabledIme.firstOrNull()?.let { inputMethodEntryCached = it }
    }

    override suspend fun toggleIme() {
        calls += "toggleIme()"
    }

    override suspend fun activateIme(ime: String) {
        calls += "activateIme($ime)"
        availableIme.find { it.uniqueName == ime }?.let { inputMethodEntryCached = it }
    }

    override suspend fun enumerateIme(forward: Boolean) {
        calls += "enumerateIme($forward)"
        val enabled = enabledIme.takeIf { it.isNotEmpty() } ?: return
        val at = enabled.indexOfFirst { it.uniqueName == inputMethodEntryCached.uniqueName }
        val step = if (forward) 1 else -1
        inputMethodEntryCached = enabled[(at + step).mod(enabled.size)]
    }

    override suspend fun currentIme() = inputMethodEntryCached

    override suspend fun imOptions(im: String) = imOptions[im]

    override suspend fun setImOptions(im: String, values: Map<String, String>) {
        calls += "setImOptions($im)"
        imOptions[im] = values.toMap()
    }

    override suspend fun punctuationMap(lang: String): List<PunctuationMapEntry> {
        calls += "punctuationMap($lang)"
        return punctuationMaps[lang].orEmpty()
    }

    /** For tests only: no app path saves a table and HostApi has no setter. */
    suspend fun setPunctuationMap(lang: String, entries: List<PunctuationMapEntry>) {
        calls += "setPunctuationMap($lang)"
        punctuationMaps[lang] = entries.toList()
    }

    override suspend fun triggerQuickPhrase() {
        calls += "triggerQuickPhrase()"
    }

    val quickPhrases = mutableMapOf<Int, QuickPhraseBook.Found>()

    override suspend fun quickPhraseAt(index: Int): QuickPhraseBook.Found? = quickPhrases[index]

    override suspend fun triggerUnicode() {
        calls += "triggerUnicode()"
    }

    override suspend fun focus(focus: Boolean) {
        calls += "focus($focus)"
    }

    override suspend fun focusOutIn() {
        calls += "focusOutIn()"
    }

    override suspend fun activate(uid: Int, pkgName: String) {
        calls += "activate($uid, $pkgName)"
    }

    override suspend fun deactivate(uid: Int) {
        calls += "deactivate($uid)"
    }

    override suspend fun setCapFlags(flags: CapabilityFlags) {
        calls += "setCapFlags(${flags.flags})"
    }

    override suspend fun statusArea() = statusAreaCached

    override suspend fun activateAction(id: Int) {
        calls += "activateAction($id)"
    }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        candidates.drop(offset).take(limit).toTypedArray()

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> = emptyArray()

    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String) {
        calls += "triggerCandidateAction($idx, $actionIdx, $text)"
    }

    override suspend fun setCandidatePagingMode(mode: Int) {
        calls += "setCandidatePagingMode($mode)"
    }

    override suspend fun offsetCandidatePage(delta: Int) {
        calls += "offsetCandidatePage($delta)"
    }
}

/** Pass the test's scope as [lifecycleScope] so a virtual clock and `advanceUntilIdle` govern [runIfReady]. */
class FakeHostConnection(
    override val lifecycleScope: CoroutineScope,
    val api: FakeHostApi = FakeHostApi(),
    /** [api], or one delegating to it (EngineBackedHostApi). */
    private val host: HostApi = api,
) : HostConnection {

    override fun <T> runImmediately(block: suspend HostApi.() -> T): T =
        runBlocking { block(host) }

    // unlike the real one this does not wait for [FakeHostApi.isReady]
    override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T = block(host)

    override fun runIfReady(block: suspend HostApi.() -> Unit) {
        if (api.isReady) lifecycleScope.launch { block(host) }
    }
}
