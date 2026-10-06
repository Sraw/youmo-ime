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
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * In-memory stand-in for the engine, so that code written against [FcitxAPI] runs on the JVM
 * without JNI. Configuration is stored and read back, [addons] and [candidates] are plain
 * fields to set up a scenario, and everything else that changes engine state is appended to
 * [calls] so a test can assert what was asked of the engine.
 *
 * It does not simulate input: no composition, no candidate generation. Push events through
 * [events] to drive listeners instead.
 */
class FakeFcitxAPI : FcitxAPI {

    /** Engine requests in call order, e.g. `"select(2)"`, `"activateIme(pinyin)"`. */
    val calls = mutableListOf<String>()

    val events = MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity = 64)
    override val eventFlow: SharedFlow<FcitxEvent<*>> get() = events

    override var isReady = true
    override var inputMethodEntryCached = InputMethodEntry("fake")
    override var statusAreaActionsCached: Array<Action> = emptyArray()
    override var clientPreeditCached = FormattedText.Empty
    override var inputPanelCached = FcitxEvent.InputPanelEvent.Data()

    var addons: Array<AddonInfo> = emptyArray()
    var candidates: Array<CandidateWord> = emptyArray()
    var availableIme: Array<InputMethodEntry> = emptyArray()
    var enabledIme: Array<InputMethodEntry> = emptyArray()

    private var dependencyGraph: AddonDependencyGraph? = null
    private var dependencyGraphAddons: Array<AddonInfo>? = null

    private val globalConfig = RawConfig()
    private val addonConfigs = mutableMapOf<String, RawConfig>()
    private val addonSubConfigs = mutableMapOf<Pair<String, String>, RawConfig>()
    private val imConfigs = mutableMapOf<String, RawConfig>()

    override fun setLogRule(verbose: Boolean) {
        calls += "setLogRule($verbose)"
    }

    override suspend fun getAddonReverseDependencies(addon: String): List<Pair<String, FcitxAPI.AddonDep>> {
        // rebuilt when the test replaces [addons]
        val graph = dependencyGraph.takeIf { dependencyGraphAddons === addons }
            ?: AddonDependencyGraph(addons).also {
                dependencyGraph = it
                dependencyGraphAddons = addons
            }
        return graph.reverseDependencies(addon)
    }

    override fun translate(str: String, domain: String) = str

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
    val userWords = ArrayList<Engines.UserWord>()

    override suspend fun userWords(): List<Engines.UserWord> = userWords.toList()

    // no dictionary to read it by
    override suspend fun pinyinOf(text: String): String? = null

    // no model: each char a word of its own
    override suspend fun wordBoundaries(texts: List<String>) = texts.associateWith { IntArray(it.length + 1) { i -> i } }

    // all alike: the recognizer's order kept
    override suspend fun logProbs(texts: List<String>) = FloatArray(texts.size)

    override suspend fun addUserWord(text: String, pinyin: String): Boolean {
        calls += "addUserWord($text, $pinyin)"
        userWords += Engines.UserWord(text, pinyin, Engines.UserWord.Kind.ADDED)
        return true
    }

    override suspend fun blockUserWord(text: String, pinyin: String): Boolean {
        calls += "blockUserWord($text, $pinyin)"
        userWords += Engines.UserWord(text, pinyin, Engines.UserWord.Kind.BLOCKED)
        return true
    }

    override suspend fun removeUserWords(words: List<Engines.UserWord>) {
        calls += "removeUserWords(${words.joinToString(",") { it.text }})"
        userWords -= words.toSet()
    }

    override suspend fun importUserWords(lines: List<String>): Engines.Imported {
        calls += "importUserWords(${lines.size})"
        return Engines.Imported(0, 0, lines.size)
    }

    override suspend fun exportUserWords(): List<String> = userWords.map { "${it.text} ${it.pinyin}" }

    override suspend fun engineContext(before: String) {
        calls += "engineContext($before)"
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

    override suspend fun select(idx: Int): Boolean {
        calls += "select($idx)"
        return idx in candidates.indices
    }

    override suspend fun isEmpty() = inputPanelCached.preedit.isEmpty() && candidates.isEmpty()

    override suspend fun reset() {
        calls += "reset()"
    }

    override suspend fun moveCursor(position: Int) {
        calls += "moveCursor($position)"
    }

    override suspend fun availableIme() = availableIme
    override suspend fun enabledIme() = enabledIme

    override suspend fun setEnabledIme(array: Array<String>) {
        calls += "setEnabledIme(${array.joinToString()})"
        enabledIme = availableIme.filter { it.uniqueName in array }.toTypedArray()
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

    override suspend fun getGlobalConfig() = globalConfig

    override suspend fun setGlobalConfig(config: RawConfig) {
        calls += "setGlobalConfig()"
        globalConfig.subItems = config.subItems
    }

    override suspend fun getAddonConfig(addon: String) = addonConfigs[addon] ?: RawConfig()

    override suspend fun setAddonConfig(addon: String, config: RawConfig) {
        calls += "setAddonConfig($addon)"
        addonConfigs[addon] = config
    }

    override suspend fun getAddonSubConfig(addon: String, path: String) =
        addonSubConfigs[addon to path] ?: RawConfig()

    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) {
        calls += "setAddonSubConfig($addon, $path)"
        addonSubConfigs[addon to path] = config
    }

    override suspend fun getImConfig(key: String) = imConfigs[key] ?: RawConfig()

    override suspend fun setImConfig(key: String, config: RawConfig) {
        calls += "setImConfig($key)"
        imConfigs[key] = config
    }

    override suspend fun addons() = addons

    override suspend fun setAddonState(name: Array<String>, state: BooleanArray) {
        calls += "setAddonState(${name.zip(state.toList()).joinToString { (n, s) -> "$n=$s" }})"
        addons = addons.map {
            val i = name.indexOf(it.uniqueName)
            if (i >= 0) it.copy(enabled = state[i]) else it
        }.toTypedArray()
    }

    override suspend fun triggerQuickPhrase() {
        calls += "triggerQuickPhrase()"
    }

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

    override suspend fun statusArea() = statusAreaActionsCached

    override suspend fun activateAction(id: Int) {
        calls += "activateAction($id)"
    }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        candidates.drop(offset).take(limit).toTypedArray()

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> = emptyArray()

    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int) {
        calls += "triggerCandidateAction($idx, $actionIdx)"
    }

    override suspend fun setCandidatePagingMode(mode: Int) {
        calls += "setCandidatePagingMode($mode)"
    }

    override suspend fun offsetCandidatePage(delta: Int) {
        calls += "offsetCandidatePage($delta)"
    }

    override suspend fun triggerCandidateListTabAction(id: Int) {
        calls += "triggerCandidateListTabAction($id)"
    }
}

/**
 * A [FcitxConnection] onto a [FakeFcitxAPI]: what components receive from `manager.fcitx()`.
 * [lifecycleScope] is the scope [runIfReady] launches into; pass the test's scope so that a
 * virtual clock and `advanceUntilIdle` govern it.
 */
class FakeFcitxConnection(
    override val lifecycleScope: CoroutineScope,
    val api: FakeFcitxAPI = FakeFcitxAPI()
) : FcitxConnection {

    override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T =
        runBlocking { block(api) }

    // unlike the real one this does not wait for [FakeFcitxAPI.isReady]: it runs at once
    override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(api)

    override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
        if (api.isReady) lifecycleScope.launch { block(api) }
    }
}
