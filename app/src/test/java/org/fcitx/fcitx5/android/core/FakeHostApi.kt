/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

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
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.ImportLayer
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.StatusEntry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory stand-in for the engine, so that code written against [HostApi] runs on the JVM
 * without JNI. Configuration is stored and read back, [addons] and [candidates] are plain
 * fields to set up a scenario, and everything else that changes engine state is appended to
 * [calls] so a test can assert what was asked of the engine.
 *
 * It does not simulate input: no composition, no candidate generation. Push events through
 * [events] to drive listeners instead.
 */
class FakeHostApi : HostApi {

    /**
     * Engine requests in call order, e.g. `"select(2)"`, `"activateIme(pinyin)"`. Copy-on-write: a test
     * polls it while a page's coroutine appends on another thread.
     */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    val events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 64)
    override val eventFlow: SharedFlow<HostEvent> get() = events

    override var isReady = true
    override var inputMethodEntryCached = InputMethodEntry("fake")
    override var statusAreaCached: List<StatusEntry> = emptyList()
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

    /** The user's words, as the engine would list them: kept as given, the pinyin not read. */
    val userWords = ArrayList<UserWord>()

    override suspend fun userWords(): List<UserWord> = userWords.toList()

    override suspend fun userWords(kind: UserWord.Kind): List<UserWord> = userWords.filter { it.kind == kind }

    override suspend fun userWordCounts(): Map<UserWord.Kind, Int> =
        UserWord.Kind.entries.associateWith { kind -> userWords.count { it.kind == kind } }

    // no table to code them by
    override suspend fun sameCodes(texts: List<String>, ims: List<String>): Map<String, List<Pair<String, String>>> = emptyMap()

    /** What [pinyinOf] reads a word as: no dictionary to read it by, so none unless set here. */
    val readings = mutableMapOf<String, String>()

    override suspend fun pinyinOf(text: String): String? = readings[text]

    override suspend fun pinyinsOf(texts: List<String>): Map<String, String?> = texts.associateWith { readings[it] }

    /** What [charCard] answers, by the candidate's text: no data to make one of, so none unless set here. */
    val charCards = mutableMapOf<String, CharCard>()

    override suspend fun charCard(index: Int, text: String, paged: Boolean): CharCard? = charCards[text]

    // no model: each char a word of its own
    override suspend fun wordBoundaries(texts: List<String>) = texts.associateWith { IntArray(it.length + 1) { i -> i } }

    // all alike: the recognizer's order kept
    override suspend fun logProbs(texts: List<String>) = FloatArray(texts.size)

    override suspend fun addUserWord(text: String, pinyin: String): Boolean {
        calls += "addUserWord($text, $pinyin)"
        userWords += UserWord(text, pinyin, UserWord.Kind.ADDED)
        return true
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

    val englishWords = ArrayList<UserWord>()

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
        // once: a file's lines may be read only once
        val count = lines.count()
        calls += "importUserWords($count, $fileName)"
        return Engines.Imported(0, 0, count)
    }

    /** What [importBatches] answers, as set by a test: nothing is imported here. */
    val importBatches = ArrayList<ImportLayer.Batch>()

    override suspend fun importBatches(): List<ImportLayer.Batch> = importBatches.toList()

    override suspend fun importedWords(batch: Int?): List<ImportLayer.Word> = emptyList()

    // the batch's words left, taken out of [importBatches]; null for none such
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

    /** What [tableKeys] answers, by table input method: none for any other, as the engine. */
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
        calls += "offerInk($piece)"
        return true
    }

    /** What [learning] answers: a test puts its own; Done by default. */
    var learned: (LearningOp) -> LearningResult = { LearningResult.Done() }

    override suspend fun learning(op: LearningOp): LearningResult {
        calls += "learning($op)"
        return learned(op)
    }

    /** The glides offered, as given: their paths and the letters' places. */
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

    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        calls += "sendKey(key=$key, states=$states, code=$code, up=$up)"
    }

    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        calls += "sendKey(char=$c, states=$states, code=$code, up=$up)"
    }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        calls += "sendKey(sym=$sym, states=$states, code=$code, up=$up)"
    }

    override suspend fun sendKey(
        sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int
    ) {
        calls += "sendKey(sym=${sym.sym}, states=${states.states}, code=$code, up=$up)"
    }

    override suspend fun select(idx: Int, text: String?): Boolean {
        calls += if (text == null) "select($idx)" else "select($idx, $text)"
        return idx in candidates.indices && (text == null || candidates[idx].text == text)
    }

    override suspend fun isEmpty() = inputPanelCached.preedit.isEmpty() && candidates.isEmpty()

    override suspend fun reset() {
        calls += "reset()"
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
        // the current one taken out: fcitx turns to the first left, as for a context that has no other
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

    override suspend fun setPunctuationMap(lang: String, entries: List<PunctuationMapEntry>) {
        calls += "setPunctuationMap($lang)"
        punctuationMaps[lang] = entries.toList()
    }

    override suspend fun triggerQuickPhrase() {
        calls += "triggerQuickPhrase()"
    }

    /** What [quickPhraseAt] gives, by the candidate's index. */
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

/**
 * A [HostConnection] onto a [FakeHostApi]: what components receive from `manager.fcitx()`.
 * [lifecycleScope] is the scope [runIfReady] launches into; pass the test's scope so that a
 * virtual clock and `advanceUntilIdle` govern it.
 */
class FakeHostConnection(
    override val lifecycleScope: CoroutineScope,
    val api: FakeHostApi = FakeHostApi(),
    /** What the blocks run on: [api], or one delegating to it (EngineBackedHostApi). */
    private val host: HostApi = api,
) : HostConnection {

    override fun <T> runImmediately(block: suspend HostApi.() -> T): T =
        runBlocking { block(host) }

    // unlike the real one this does not wait for [FakeHostApi.isReady]: it runs at once
    override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T = block(host)

    override fun runIfReady(block: suspend HostApi.() -> Unit) {
        if (api.isReady) lifecycleScope.launch { block(host) }
    }
}
