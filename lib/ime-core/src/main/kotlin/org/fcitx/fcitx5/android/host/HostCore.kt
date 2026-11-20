/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.util.concurrent.CopyOnWriteArrayList
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.WordOffer

/** The modules plugged into the host, each list in fcitx's registration order (`specs/host.md`). */
data class HostModules(
    val tempModes: List<TempMode> = emptyList(),
    val preHooks: List<PreKeyHook> = emptyList(),
    val postHooks: List<PostKeyHook> = emptyList(),
    val resultListeners: List<KeyResultListener> = emptyList(),
    val forwardListeners: List<ForwardKeyListener> = emptyList(),
    val commitFilters: List<CommitFilter> = emptyList(),
    val outputFilters: List<OutputFilter> = emptyList(),
    val contextListeners: List<ContextListener> = emptyList(),
    val activationListeners: List<ActivationListener> = emptyList(),
    val punctuation: EnginePunctuation? = null,
    /** Whether full width is on for the context (and its input method has the toggle). */
    val fullWidth: ((InputContext) -> Boolean)? = null,
    val statusArea: StatusArea? = null,
    val quickPhrase: QuickPhrase? = null,
    val unicode: UnicodeInput? = null,
)

/** Host state that belongs in a config file, told as it changes (fcitx saved only on `save()`, so a force stop lost it). */
sealed class Persist {
    data class ProfileChanged(val profile: Profile) : Persist()

    /** A module's state ([conf], `conf/chttrans.conf` say) changed. */
    data class ConfChanged(val conf: String) : Persist()
}

/**
 * The Kotlin host: fcitx's `Instance` with `androidfrontend` and `androidengine`, on one host thread
 * (nothing is locked). Every entry point is one event: output as it happens, the panel once at its end.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class HostCore(
    host: EngineHost,
    private val clock: HostClock,
    profile: Profile,
    /** What the engines list (`InputMethodManager::entries_`). */
    private val available: Set<String> = InputMethodCatalog.LISTED.map { it.name }.toSet(),
    private val strings: HostStrings = HostStrings.ENGLISH,
    /** The app's first run (`Fcitx.onFirstRun`): pinyin goes first in the group. */
    firstRun: Boolean = false,
    /** `ShowInputMethodInformation` (off in the instrumented tests). */
    private val showInputMethodInformation: Boolean = true,
    private val persist: (Persist) -> Unit = {},
    modules: (HostServices) -> HostModules = { HostModules() },
) : HostServices {

    /** On the host thread; copied on write, so listeners changed mid-emit apply from the next event. */
    val events = CopyOnWriteArrayList<HostEvents>()

    private fun emit(event: HostEvent) = events.forEach { it.onEvent(event) }

    var profile: Profile = profile
        private set

    val contexts = InputContexts(profile.currentGroup(), ::switched)

    val router = KeyRouter(host, this, clock, strings)

    private val modules = modules(this)
    private val activationListeners = this.modules.activationListeners

    private val pipeline = KeyPipeline(
        router, Output(),
        TempModeManager(this.modules.tempModes),
        preHooks = listOf(PreKeyHook { ic, e -> if (!e.release) hideInputMethodInfo(ic) }) + this.modules.preHooks,
        postHooks = this.modules.postHooks,
        resultListeners = this.modules.resultListeners,
        forwardListeners = this.modules.forwardListeners,
        commitFilters = this.modules.commitFilters,
        outputFilters = this.modules.outputFilters,
        contextListeners = this.modules.contextListeners,
    )

    /** 0 the bulk list, else the hardware keyboard's paged window (`setCandidatePagingMode`). */
    var pagingMode = 0
        private set

    private class Overlay {
        var timer: Cancellable? = null
        var last = ""
    }

    private val overlay = IcProperty { Overlay() }

    // declared above init, which already flushes (setInputMethods)
    private var statusDirty = false
    private var groupInfoTimer: Cancellable? = null

    init {
        router.punctuation = this.modules.punctuation
        if (firstRun && InputMethodCatalog.PINYIN in available) {
            val ims = contexts.group.inputMethods
            setInputMethods(listOf(InputMethodCatalog.PINYIN) + ims.filter { it != InputMethodCatalog.PINYIN })
        }
    }

    /** `AndroidFrontend::activateInputContext`: active (made if new), not focused. */
    fun activate(uid: Int) {
        contexts.activate(uid)
    }

    /** `deactivateInputContext`. */
    fun deactivate(uid: Int) {
        if (uid !in contexts) return
        // its own batch: flushed after, no context is active, and the keyboard keeps showing the dropped code
        pipeline.batch { contexts.focused?.takeIf { it.uid == uid }?.let(::focusOut) }
        contexts.deactivate(uid)
    }

    /** `focusInputContext`. */
    fun focus(focus: Boolean) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        if (focus) {
            if (ic.hasFocus) return@batch
            // another app bound before the focused one unbound: its cleared panel goes nowhere, so send this one's
            val replaced = contexts.focused?.also(::focusOut)
            contexts.focus(ic, true)
            // `FocusIn`, ReservedFirst
            activateInputMethod(ic)
            pipeline.focusIn(ic)
            if (replaced != null) pipeline.updatePanel(ic)
        } else if (ic.hasFocus) {
            focusOut(ic)
        }
    }

    /** `focusOutIn`: out and in again, the engine reset. */
    fun focusOutIn() {
        focus(false)
        focus(true)
    }

    // `FocusOut`
    private fun focusOut(ic: InputContext) {
        contexts.focus(ic, false)
        if (!ic.has(Capability.CLIENT_UNFOCUS_COMMIT)) {
            val commit = forCommit(ic.panel.clientPreedit)
            if (commit.isNotEmpty()) commit(ic, commit)
        }
        router.deactivate(ic, contexts.inputMethod(ic), switching = false)
        pipeline.focusOut(ic)
    }

    /** `setCapabilityFlags`: across the password line, the input method switched out and in. */
    fun setCapabilities(flags: Long) = pipeline.batch {
        contexts.active?.let { contexts.setCapabilities(it, flags) }
    }

    /** A key from the app; whether the host took it (else it went back as a [HostEvent.Key]). */
    fun sendKey(key: Key, release: Boolean = false, virtual: Boolean = false, timestamp: Int = -1, payload: Action? = null): Boolean {
        val ic = contexts.active ?: return false
        val e = KeyEvent(key, release, virtual, timestamp, payload)
        // `RETURN_IF_HAS_NO_FOCUS`: unfocused, the key goes back
        if (!ic.hasFocus) {
            handBack(key, release, timestamp)
            return false
        }
        return pipeline.dispatch(ic, e).accepted
    }

    /** `selectCandidate`: [index] picked if it still shows [text] (null or empty: not checked). */
    fun select(index: Int, text: String? = null): Boolean = pipeline.batch {
        val ic = contexts.active ?: return@batch false
        val list = ic.panel.candidateList ?: return@batch false
        val word = candidateAt(list, index) ?: return@batch false
        if (!shows(ic, word, text.orEmpty())) return@batch false
        word.select(ic)
        true
    }

    /** `triggerQuickPhrase`: its code shown above the candidates whatever the input method. */
    fun triggerQuickPhrase() = pipeline.batch {
        val ic = contexts.active ?: return@batch
        val mode = modules.quickPhrase ?: return@batch
        ic.preeditEnabled = false
        mode.trigger(ic)
    }

    /** Quick input's candidate [index] for a long press's card: null for ＋添加, or none shown. */
    fun quickPhraseAt(index: Int): QuickPhraseBook.Found? {
        val ic = contexts.active ?: return null
        return modules.quickPhrase?.entryAt(ic, index)
    }

    /** Quick input's list reloaded if open, so an entry its card deleted goes at once. */
    fun refreshQuickPhrase() = pipeline.batch {
        val ic = contexts.active ?: return@batch
        modules.quickPhrase?.refresh(ic)
    }

    /** `triggerUnicode`. */
    fun triggerUnicode() = pipeline.batch {
        val ic = contexts.active ?: return@batch
        modules.unicode?.trigger(ic)
    }

    /** `statusAreaActions`. */
    fun statusArea(): List<StatusEntry> {
        val ic = contexts.active ?: return emptyList()
        return modules.statusArea?.entries(ic).orEmpty()
    }

    /** `activateAction`. */
    fun activateAction(id: Int) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        modules.statusArea?.activate(ic, id)
    }

    /** `AndroidEngine::reloadConfig`/`setConfigForInputMethod`. */
    fun configure(options: EngineOptions) {
        router.options = KeyRouter.Options(options.pageSizeValue, options.suggestsEnglish)
        val settings = options.toSettings()
        val schemeChanged = scheme != null && scheme != settings.scheme
        scheme = settings.scheme
        val readingChanged = reading != null && reading != settings.candidateReading
        reading = settings.candidateReading
        router.settings(settings)
        // 双拼's keyboard depends on the scheme: re-announce the keyboard shown, as for a switch
        val active = contexts.active
        if (schemeChanged && active != null && contexts.inputMethod(active) == InputMethodCatalog.SHUANGPIN) {
            emit(HostEvent.InputMethodChanged(InputMethodCatalog.SHUANGPIN))
        }
        // 候选词注音 alone changed: what is typed stays, and what is on show is read again
        if (readingChanged && !isEmpty()) sendKey(Key(UNICODE + Keyboard.RECALL, KeyStates.VIRTUAL), virtual = true)
    }

    private var scheme: ShuangpinScheme? = null

    private var reading: ReadingStyle? = null

    /** `isInputPanelEmpty`. */
    fun isEmpty() = contexts.active?.panel?.empty ?: true

    /** `resetInputContext`: engine, then temp modes and modules; with [drop] what is typed is dropped ([KeyRouter.reset]). */
    fun reset(drop: Boolean = false) = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch
        router.reset(ic, contexts.inputMethod(ic), drop)
        pipeline.reset(ic)
    }

    /** The panel's code committed as Return commits it, no reset, so the next word keeps its 前文; false with none shown. */
    fun commitCode(): Boolean = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus && it.panel.preedit.isNotEmpty() } ?: return@batch false
        router.enter(ic, contexts.inputMethod(ic))
        true
    }

    /** 联想's predictions put away (the bar's ×), the context kept; false where none show. */
    fun dropPredictions(): Boolean = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch false
        router.dropPredictions(ic, contexts.inputMethod(ic))
    }

    fun clearTyped(keyboard: (im: String) -> Any? = { null }): Retyping? = pipeline.batch {
        // 联想's words with nothing typed are not what is typed
        val ic = contexts.active?.takeIf { it.hasFocus && (it.panel.preedit.isNotEmpty() || it.panel.clientPreedit.isNotEmpty()) }
            ?: return@batch null
        val im = contexts.inputMethod(ic)
        Retyping(im, router.clearTyped(ic, im), keyboard(im)).also { pipeline.reset(ic) }
    }

    /** [clearTyped]'s keys typed again on the [keyboard] they were typed with (its codes go to its own session); false if not possible. */
    fun retype(typing: Retyping, keyboard: Any? = null): Boolean = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch false
        val keys = typing.keys ?: return@batch false
        if (contexts.inputMethod(ic) != typing.im || keyboard != typing.keyboard) return@batch false
        router.retype(ic, typing.im, keys)
        true
    }

    /** What 清除 dropped: [keys] null when it cannot be typed again. */
    data class Retyping(val im: String, val keys: List<KeyRouter.Typed>?, val keyboard: Any? = null)

    /** `repositionCursor`: a tap at [position] in the code row. */
    fun moveCursor(position: Int) = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch
        val e = pipeline.invokeAction(ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, position))
        // nobody took it: `defaultInvokeActionBehavior` commits the app's composing text (handwriting's) and resets
        if (!e.filtered) {
            val commit = forCommit(ic.panel.clientPreedit)
            if (commit.isNotEmpty()) commit(ic, commit)
            router.reset(ic, contexts.inputMethod(ic))
            pipeline.reset(ic)
        }
    }

    /** `getCandidates`. */
    fun candidates(offset: Int, limit: Int): List<CandidateEntry> {
        val ic = contexts.active ?: return emptyList()
        val list = ic.panel.candidateList ?: return emptyList()
        val last = offset + limit
        if (!list.bulk) return (offset until minOf(list.size, last)).mapNotNull { i -> list.candidate(i)?.let { entry(ic, list.label(i), it) } }
        val end = if (list.totalSize < 0) last else minOf(list.totalSize, last)
        val out = ArrayList<CandidateEntry>()
        for (i in offset until end) out += entry(ic, "", list.candidateFromAll(i) ?: break)
        return out
    }

    /** `getCandidateAction`. */
    fun candidateActions(index: Int): List<CandidateAction> {
        val list = contexts.active?.panel?.candidateList ?: return emptyList()
        return candidateAt(list, index)?.let(list::actions).orEmpty()
    }

    /** `triggerCandidateAction`: if [index] still shows [text]. */
    fun triggerCandidateAction(index: Int, id: Int, text: String) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        val list = ic.panel.candidateList ?: return@batch
        val word = candidateAt(list, index) ?: return@batch
        if (shows(ic, word, text)) list.triggerAction(word, id)
    }

    /**
     * The engine's long-press act [id] on candidate [index] of all (not of the page: an act returns to the first
     * page), if it shows [text] as this context converts it (国家 as 國家) and offers [id].
     */
    fun actOnCandidate(index: Int, id: Int, text: String): Boolean = pipeline.batch act@{
        val ic = contexts.active ?: return@act false
        val list = ic.panel.candidateList ?: return@act false
        val word = (if (list.bulk) list.candidateFromAll(index) else null) ?: return@act false
        if (text.isEmpty() || shown(ic, word) != filterOutput(ic, text) || list.actions(word).none { it.id == id }) return@act false
        list.triggerAction(word, id)
        true
    }

    // `candidateAt`: on the page when paging, else among all of a bulk list
    private fun candidateAt(list: CandidateList, index: Int) =
        if (pagingMode == 0 && list.bulk) list.candidateFromAll(index) else list.candidate(index)

    /** `setCandidatePagingMode`. */
    fun setCandidatePagingMode(mode: Int) {
        pagingMode = mode
        contexts.active?.let(::sendCandidates)
    }

    /** `offsetCandidatePage`. */
    fun offsetCandidatePage(delta: Int) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        val list = ic.panel.candidateList?.takeIf { it.pageable } ?: return@batch
        if (delta > 0 && list.hasNext) {
            list.next()
            updatePanel(ic)
        } else if (delta < 0 && list.hasPrev) {
            list.prev()
            updatePanel(ic)
        }
    }

    /** `toggle()` on the most recent context. */
    fun toggle() = pipeline.batch { contexts.mostRecent?.let { contexts.toggle(it) }; changedProfile() }

    /** `enumerate(forward)` on the most recent context. */
    fun enumerate(forward: Boolean) = pipeline.batch { contexts.mostRecent?.let { contexts.enumerate(it, forward) }; changedProfile() }

    /** `setInputMethod`: [im] for the active context, local. */
    fun setCurrentInputMethod(im: String) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        contexts.setCurrentInputMethod(ic, im, local = true)
        changedProfile()
    }

    /** `setEnabledInputMethods`: unknown names dropped, the default kept, every focused context switched, saved. */
    fun setInputMethods(list: List<String>) = pipeline.batch {
        contexts.setInputMethods(list, available)
        changedProfile()
        // `InputMethodGroupChanged`: the frontend tells an unfocused active context; fcitx shows the name on focused ones 30 ms after
        contexts.active?.takeIf { !it.hasFocus }?.let { emit(HostEvent.InputMethodChanged(contexts.inputMethod(it))) }
        groupInfoTimer?.cancel()
        groupInfoTimer = clock.schedule(GROUP_INFO_DELAY) {
            pipeline.batch { contexts.focused?.let(::showInputMethodInformation) }
        }
    }

    /** On the active context, or before one is focused under the group's first input method. */
    fun punctuationKeys(): Map<String, String> {
        val p = modules.punctuation ?: return emptyMap()
        return contexts.active?.let(p::keys) ?: startPunctuationKeys()
    }

    /** [punctuationKeys] for a fresh host (full width off, unsaved), so the app can draw keys before the host is up. */
    fun startPunctuationKeys(): Map<String, String> =
        // a new host's first context takes the group's first input method, not the one now on
        modules.punctuation?.keys(contexts.group.inputMethods.firstOrNull()?.let { InputMethodCatalog.listed(it)?.chinese } == true).orEmpty()

    fun <T> batch(block: () -> T): T = pipeline.batch(block)

    /** `currentIme`. */
    fun currentInputMethod(): String = contexts.active?.let(contexts::inputMethod) ?: contexts.group.inputMethods.firstOrNull().orEmpty()

    private fun changedProfile() {
        val next = profile.with(contexts.group)
        // text() writes only the groups and their order: compared as they are, not as INI
        if (next.groups == profile.groups && next.order == profile.order) return
        profile = next
        persist(Persist.ProfileChanged(next))
    }

    override fun persistConf(conf: String) = persist(Persist.ConfChanged(conf))

    // `InputContextSwitchInputMethod` and its kin; the new input method is read again by
    // activateInputMethod, as fcitx's activate does
    @Suppress("UnusedParameter")
    private fun switched(ic: InputContext, reason: SwitchReason, old: String, new: String) {
        if (!ic.hasFocus) return
        // ReservedFirst: the old engine out (a switch commits what was typed), the new one in
        router.deactivate(ic, old, switching = true)
        contexts.applyCapabilities(ic)
        activateInputMethod(ic)
        // a capability or group change posts no InputContextSwitchInputMethod: no temp-mode reset, event or overlay
        if (reason == SwitchReason.CapabilityChanged || reason == SwitchReason.GroupChange) return
        // Default: temp modes leave, the frontend told
        pipeline.switchedInputMethod(ic)
        if (ic === contexts.active) emit(HostEvent.SwitchInputMethod(reason, old))
        // ReservedLast: the name, for every reason fcitx lists
        showInputMethodInformation(ic)
    }

    /** `Instance::activateInputMethod`. */
    private fun activateInputMethod(ic: InputContext) {
        val im = contexts.inputMethod(ic)
        val chinese = InputMethodCatalog.listed(im)?.chinese == true
        activationListeners.forEach { it.onActivated(ic, im, chinese) }
        router.activate(ic, im)
        if (ic === contexts.active) emit(HostEvent.InputMethodChanged(im))
    }

    /** `showInputMethodInformation`: the label as the overlay, hidden after a second or by a key. */
    private fun showInputMethodInformation(ic: InputContext) {
        if (!showInputMethodInformation) return
        val im = contexts.inputMethod(ic)
        val listed = InputMethodCatalog.listed(im)
        val display = listed?.label?.takeIf { it.isNotEmpty() } ?: listed?.text ?: im
        val state = ic.propertyFor(overlay)
        ic.panel.overlayMessage = display
        updatePanel(ic)
        state.last = display
        state.timer?.cancel()
        state.timer = clock.schedule(INFO_MILLIS) { pipeline.batch { hideInputMethodInfo(ic) } }
    }

    /** `InputState::hideInputMethodInfo`: only the overlay this put up. */
    private fun hideInputMethodInfo(ic: InputContext) {
        val state = ic.propertyFor(overlay)
        val timer = state.timer ?: return
        timer.cancel()
        state.timer = null
        if (ic.panel.overlayMessage == state.last) {
            ic.panel.overlayMessage = ""
            updatePanel(ic)
        }
    }

    override fun commit(ic: InputContext, text: String, cursor: Int) = pipeline.commit(ic, text, cursor)

    override fun commitAsIs(ic: InputContext, text: String) = pipeline.commitAsIs(ic, text)

    override fun deleteSurrounding(ic: InputContext, before: Int, after: Int) = pipeline.deleteSurrounding(ic, before, after)

    override fun forwardKey(ic: InputContext, key: Key, release: Boolean) = pipeline.forwardKey(ic, key, release)

    override fun updatePreedit(ic: InputContext) {
        if (!ic.has(Capability.PREEDIT)) return
        val empty = ic.panel.clientPreedit.isEmpty()
        if (empty && ic.lastPreeditUpdateIsEmpty) return
        ic.lastPreeditUpdateIsEmpty = empty
        emit(HostEvent.ClientPreedit(pipeline.filterOutput(ic, ic.panel.clientPreedit)))
    }

    override fun updatePanel(ic: InputContext) = pipeline.updatePanel(ic)

    override fun inputMethod(ic: InputContext) = contexts.inputMethod(ic)

    override fun tip(text: String) = emit(HostEvent.Tip(text))

    override fun showPanel(ic: InputContext, panel: TempPanel) {
        // a temp mode's panel replaces the context's whole panel, sent at the event's end
        val p = ic.panel
        p.reset()
        p.preedit = panel.preedit
        p.clientPreedit = panel.clientPreedit
        p.auxUp = panel.auxUp
        p.candidateList = panel.candidates?.let(::TempModeCandidates)
        p.quickPhrase = panel.quickPhrase
        updatePreedit(ic)
        updatePanel(ic)
    }

    override fun wordOffer(ic: InputContext, offer: WordOffer?) {
        if (ic === contexts.active) emit(HostEvent.WordOffer(offer))
    }

    override fun wordRemembered(ic: InputContext, word: OfferedWord) {
        if (ic === contexts.active) emit(HostEvent.WordRemembered(word))
    }

    override fun punctuationAsTyped(ic: InputContext, marks: Set<String>) {
        if (ic === contexts.active) emit(HostEvent.PunctuationAsTyped(marks))
    }

    override fun updateStatusArea(ic: InputContext) {
        if (ic !== contexts.active) return
        statusDirty = true
        // an empty batch is the pipeline's flush
        pipeline.batch {}
    }

    // most texts pass unchanged: not wrapped nor joined back for nothing
    override fun filterOutput(ic: InputContext, text: String): String {
        if (text.isEmpty()) return text
        val given = FormattedText(arrayOf(text), intArrayOf(0), -1)
        val out = pipeline.filterOutput(ic, given)
        return if (out === given) text else out.toString()
    }

    private inner class Output : HostOutput {
        override fun commit(ic: InputContext, text: String, cursor: Int) = emit(HostEvent.Commit(text, cursor))

        override fun deleteSurrounding(ic: InputContext, before: Int, after: Int) =
            emit(HostEvent.DeleteSurrounding(before, after))

        override fun handBack(ic: InputContext, key: Key, release: Boolean, timestamp: Int) = this@HostCore.handBack(key, release, timestamp)

        override fun flushed() {
            if (!statusDirty) return
            statusDirty = false
            val ic = contexts.active ?: return
            emit(
                HostEvent.StatusArea(
                    modules.statusArea?.entries(ic).orEmpty(), contexts.inputMethod(ic), modules.punctuation?.keys(ic).orEmpty(),
                    modules.fullWidth?.invoke(ic) == true,
                ),
            )
        }

        /** `InputContextFlushUI`. */
        override fun panel(ic: InputContext) {
            if (ic !== contexts.active) return
            val p = ic.panel
            if (p.empty) {
                emit(HostEvent.InputPanel(FormattedText.Empty, text(p.overlayMessage), FormattedText.Empty))
            } else {
                emit(HostEvent.InputPanel(pipeline.filterOutput(ic, p.preedit), pipeline.filterOutput(ic, p.auxUp), pipeline.filterOutput(ic, p.auxDown), p.quickPhrase, p.code && p.preedit.isNotEmpty()))
            }
            sendCandidates(ic)
        }
    }

    private fun handBack(key: Key, release: Boolean, timestamp: Int) =
        emit(HostEvent.Key(key.sym, key.states, KeySyms.toUnicode(key.sym), release, timestamp))

    private fun sendCandidates(ic: InputContext) {
        val list = ic.panel.candidateList
        if (pagingMode != 0) {
            if (list == null) {
                emit(HostEvent.Paged.EMPTY)
                return
            }
            val page = (0 until list.size).mapNotNull { i -> list.candidate(i)?.let { entry(ic, list.label(i), it) } }
            emit(HostEvent.Paged(page, list.cursorIndex, list.pageable && list.hasPrev, list.pageable && list.hasNext, list.layoutHint))
            return
        }
        if (list == null) {
            emit(HostEvent.Candidates(0, emptyList()))
            return
        }
        if (!list.bulk) {
            emit(HostEvent.Candidates(list.size, (0 until list.size).mapNotNull { i -> list.candidate(i)?.let { entry(ic, "", it) } }, list.predicting, list.lead))
            return
        }
        var total = list.totalSize
        val limit = if (total < 0) BULK_FIRST else minOf(total, BULK_FIRST)
        val out = ArrayList<CandidateEntry>()
        for (i in 0 until limit) {
            val word = list.candidateFromAll(i)
            if (word == null) {
                total = out.size
                break
            }
            out += entry(ic, "", word)
        }
        emit(HostEvent.Candidates(total, out, list.predicting, list.lead))
    }

    // 候选词注音's readings kept while each code point still has one (a rewritten phrase gets none)
    private fun entry(ic: InputContext, label: String, word: PanelCandidate): CandidateEntry {
        val text = shown(ic, word)
        val readings = word.readings.takeIf { it.size == text.codePointCount(0, text.length) }.orEmpty()
        return CandidateEntry(if (label.isEmpty()) "" else filterOutput(ic, label), text, filterOutput(ic, word.comment), readings = readings)
    }

    /** [text] filtered as the active context shows a candidate (繁體: chttrans). */
    fun shownText(text: String): String = contexts.active?.let { filterOutput(it, text) } ?: text

    // `shows`: the tapped word by its filtered text; an empty text (a key's pick) is not checked
    private fun shows(ic: InputContext, word: PanelCandidate, text: String) = text.isEmpty() || shown(ic, word) == text

    private fun shown(ic: InputContext, word: PanelCandidate) = if (word.asIs) word.text else filterOutput(ic, word.text)

    companion object {
        const val INFO_MILLIS = 1000L

        /** The group change's overlay delay. */
        const val GROUP_INFO_DELAY = 30L

        private const val BULK_FIRST = 16

        private fun text(s: String) = if (s.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(s), intArrayOf(0), -1)

        // `Text::toStringForCommit`: the segments not DontCommit
        private fun forCommit(t: FormattedText) =
            t.strings.indices.filter { t.flags.getOrElse(it) { 0 } and DONT_COMMIT == 0 }.joinToString("") { t.strings[it] }

        private const val DONT_COMMIT = 1 shl 5
    }
}
