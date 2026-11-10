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

/**
 * The modules plugged into the host, each list in fcitx's registration order (`specs/host.md`):
 * S2's punctuation, full width, chttrans and status area, quick phrase and Unicode. Made by
 * [HostCore] with its [HostServices], so a module can commit, show and tip.
 */
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
    /** Whether full width is on for [InputContext] (on and its input method has the toggle): sent with the status. */
    val fullWidth: ((InputContext) -> Boolean)? = null,
    /** The status area the modules put their toggles in: what [HostCore.statusArea] lists. */
    val statusArea: StatusArea? = null,
    /** Quick input, for the keyboard button ([HostCore.triggerQuickPhrase]). */
    val quickPhrase: QuickPhrase? = null,
    /** Unicode input, for the keyboard button ([HostCore.triggerUnicode]). */
    val unicode: UnicodeInput? = null,
)

/**
 * State the host changed that belongs in a config file: the writer (S2④'s `IniFile`, S3's merged
 * background writes) saves it. fcitx saved such state only on `save()` (the settings page stopping,
 * the process exiting), so a force stop lost it; here every change is told as it happens.
 */
sealed class Persist {
    /** `config/profile` anew, as [Profile.text] writes it. */
    data class ProfileChanged(val profile: Profile) : Persist()

    /** A module's state ([conf], `conf/chttrans.conf` say) changed: write it. */
    data class ConfChanged(val conf: String) : Persist()
}

/**
 * The Kotlin host: fcitx's `Instance` with the `androidfrontend` and `androidengine` addons, on one
 * host thread (every call here is on it; nothing is locked). Keys go through the [KeyPipeline],
 * the engine is the [KeyRouter] on [host], contexts and input methods are [InputContexts] on the
 * [Profile]'s current group, the output goes to [events].
 *
 * Every entry point is one event: commits, deletes and keys go out as they happen, the panel once
 * at its end. Input method switches deactivate the old engine and activate the new one
 * (`fcitx/instance.cpp:1178-1188,2454-2531`); the input method name overlay is shown for the
 * reasons fcitx shows it (`:1189-1212`), not for a capability change.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class HostCore(
    host: EngineHost,
    private val clock: HostClock,
    profile: Profile,
    /** What the engines list (`InputMethodManager::entries_`). */
    private val available: Set<String> = InputMethodCatalog.LISTED.map { it.name }.toSet(),
    private val strings: HostStrings = HostStrings.ENGLISH,
    /** The app's first run (`Fcitx.onFirstRun`, `native-last:app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:437-447`): pinyin goes first in the group. */
    firstRun: Boolean = false,
    /** `ShowInputMethodInformation`, a constructor switch here (the instrumented tests turn it off through `KotlinHost.showInputMethodInformation`). */
    private val showInputMethodInformation: Boolean = true,
    private val persist: (Persist) -> Unit = {},
    modules: (HostServices) -> HostModules = { HostModules() },
) : HostServices {

    /**
     * Who hears the host's events ([HostEvents]), in turn, on the host thread: the app's host, a test's
     * log. Copied on write, so a listener added or removed while an event is told (or from another
     * thread) is heard from the next event on, never a ConcurrentModificationException mid-emit.
     */
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

    // the status area to send at the event's end; above init, which already flushes (setInputMethods)
    private var statusDirty = false
    private var groupInfoTimer: Cancellable? = null

    init {
        router.punctuation = this.modules.punctuation
        if (firstRun && InputMethodCatalog.PINYIN in available) {
            val ims = contexts.group.inputMethods
            setInputMethods(listOf(InputMethodCatalog.PINYIN) + ims.filter { it != InputMethodCatalog.PINYIN })
        }
    }

    // ---- the frontend's calls (`androidfrontend.cpp:385-535`, `native-lib.cpp`)

    /** `AndroidFrontend::activateInputContext`: [uid]'s context active (made if new), not focused. */
    fun activate(uid: Int) {
        contexts.activate(uid)
    }

    /** `deactivateInputContext` (`:485-490`): [uid]'s context loses focus and is no longer active (only its: [InputContexts.deactivate]). */
    fun deactivate(uid: Int) {
        if (uid !in contexts) return
        // its own batch: the cleared panel flushes while the context is still the active one; flushed
        // after, no context is active, nothing goes out, and the keyboard keeps showing the dropped code
        pipeline.batch { contexts.focused?.takeIf { it.uid == uid }?.let(::focusOut) }
        contexts.deactivate(uid)
    }

    /** `focusInputContext` (`:460-467`). */
    fun focus(focus: Boolean) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        if (focus) {
            if (ic.hasFocus) return@batch
            // another app bound before the focused one unbound: that one's cleared panel is not the
            // active context's and goes nowhere, so this one's panel is sent instead (once: a set)
            val replaced = contexts.focused?.also(::focusOut)
            contexts.focus(ic, true)
            // `FocusIn`, ReservedFirst: the input method activated (`instance.cpp:1069-1091`)
            activateInputMethod(ic)
            pipeline.focusIn(ic)
            if (replaced != null) pipeline.updatePanel(ic)
        } else if (ic.hasFocus) {
            focusOut(ic)
        }
    }

    /** `focusOutIn` (`native-lib.cpp`): out and in again, the engine reset. */
    fun focusOutIn() {
        focus(false)
        focus(true)
    }

    // `FocusOut` (`instance.cpp:1125-1153`): what the app does not commit itself, committed; the
    // input method deactivated (no switch: what was typed goes); temp modes and modules after
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

    /** A key from the app, [payload] with it ([KeyEvent.payload]); whether the host took it (else it went back as a [HostEvent.Key]). */
    fun sendKey(key: Key, release: Boolean = false, virtual: Boolean = false, timestamp: Int = -1, payload: Action? = null): Boolean {
        val ic = contexts.active ?: return false
        val e = KeyEvent(key, release, virtual, timestamp, payload)
        // `RETURN_IF_HAS_NO_FOCUS` (`inputcontext.cpp:333-335`): unfocused, nobody sees it, it goes back
        if (!ic.hasFocus) {
            handBack(key, release, timestamp)
            return false
        }
        return pipeline.dispatch(ic, e).accepted
    }

    /** `selectCandidate` (`:150-187,420-427`): [index] picked if it still shows [text] (null or empty: not checked). */
    fun select(index: Int, text: String? = null): Boolean = pipeline.batch {
        val ic = contexts.active ?: return@batch false
        val list = ic.panel.candidateList ?: return@batch false
        val word = candidateAt(list, index) ?: return@batch false
        if (!shows(ic, word, text.orEmpty())) return@batch false
        word.select(ic)
        true
    }

    /**
     * `triggerQuickPhrase` (`native-lib.cpp:286-296`): quick input on the active context, its code
     * above the candidates whatever the input method (the engine's next event sets the flag back).
     */
    fun triggerQuickPhrase() = pipeline.batch {
        val ic = contexts.active ?: return@batch
        val mode = modules.quickPhrase ?: return@batch
        ic.preeditEnabled = false
        mode.trigger(ic)
    }

    /** What quick input's candidate [index] is, for the card a long press opens: null for ＋添加, or none shown. */
    fun quickPhraseAt(index: Int): QuickPhraseBook.Found? {
        val ic = contexts.active ?: return null
        return modules.quickPhrase?.entryAt(ic, index)
    }

    /**
     * Quick input's list shown again from its book as [QuickPhrase.reload] left it, if it is open: an
     * entry its card deleted goes at once, not at the next key.
     */
    fun refreshQuickPhrase() = pipeline.batch {
        val ic = contexts.active ?: return@batch
        modules.quickPhrase?.refresh(ic)
    }

    /** `triggerUnicode` (`native-lib.cpp:298-303`): Unicode search on the active context. */
    fun triggerUnicode() = pipeline.batch {
        val ic = contexts.active ?: return@batch
        modules.unicode?.trigger(ic)
    }

    /** `statusAreaActions` (`native-lib.cpp:330-342`): the active context's status area. */
    fun statusArea(): List<StatusEntry> {
        val ic = contexts.active ?: return emptyList()
        return modules.statusArea?.entries(ic).orEmpty()
    }

    /** `activateAction` (`native-lib.cpp:344-352`): a tap on [id] in the active context's status area. */
    fun activateAction(id: Int) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        modules.statusArea?.activate(ic, id)
    }

    /**
     * The engine options read or changed (`AndroidEngine::reloadConfig`/`setConfigForInputMethod`,
     * `androidengine.cpp:405-490`): the router's page size and English suggestions, and the engines'
     * settings ([EngineOptions.toSettings]).
     */
    fun configure(options: EngineOptions) {
        router.options = KeyRouter.Options(options.pageSizeValue, options.suggestsEnglish)
        val settings = options.toSettings()
        val schemeChanged = scheme != null && scheme != settings.scheme
        scheme = settings.scheme
        val readingChanged = reading != null && reading != settings.candidateReading
        reading = settings.candidateReading
        router.settings(settings)
        // 双拼's keyboard is the scheme's too (ImKeyboards: no 14 keys under 微软): the keyboard on show
        // told anew, as for a switch, with the engine's settings in place
        val active = contexts.active
        if (schemeChanged && active != null && contexts.inputMethod(active) == InputMethodCatalog.SHUANGPIN) {
            emit(HostEvent.InputMethodChanged(InputMethodCatalog.SHUANGPIN))
        }
        // 候选词注音 alone changed (the handwriting pad's 注): what is typed stays (Engines.settings), and what
        // is on show is read again to be annotated the new way
        if (readingChanged && !isEmpty()) sendKey(Key(UNICODE + Keyboard.RECALL, KeyStates.VIRTUAL), virtual = true)
    }

    // the 双拼 scheme [configure] last gave the engine, null before the first
    private var scheme: ShuangpinScheme? = null

    // 候选词注音 as [configure] last gave it, null before the first
    private var reading: ReadingStyle? = null

    /** `isInputPanelEmpty`. */
    fun isEmpty() = contexts.active?.panel?.empty ?: true

    /** `resetInputContext`: the engine reset, then the temp modes and modules (`instance.cpp:1155-1176`). */
    fun reset() = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch
        router.reset(ic, contexts.inputMethod(ic))
        pipeline.reset(ic)
    }

    /**
     * The code the panel shows (pinyin's: never the field's composing text, English's word or handwriting's,
     * which stays where it is) committed as Return commits it ([KeyRouter.enter]), no reset: the next word
     * keeps its 前文 as after any commit. False with no code shown (联想's words alone are none).
     */
    fun commitCode(): Boolean = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus && it.panel.preedit.isNotEmpty() } ?: return@batch false
        router.enter(ic, contexts.inputMethod(ic))
        true
    }

    /** What is typed dropped, nothing committed ([KeyRouter.clearTyped]): null with nothing typed. */
    fun clearTyped(keyboard: (im: String) -> Any? = { null }): Retyping? = pipeline.batch {
        // 联想's words with nothing typed are not what is typed
        val ic = contexts.active?.takeIf { it.hasFocus && (it.panel.preedit.isNotEmpty() || it.panel.clientPreedit.isNotEmpty()) }
            ?: return@batch null
        val im = contexts.inputMethod(ic)
        Retyping(im, router.clearTyped(ic, im), keyboard(im)).also { pipeline.reset(ic) }
    }

    /**
     * What [clearTyped] dropped typed again, in the input method it was typed in on the [keyboard] it
     * was typed with; false when it cannot be. The keyboard is the app's to tell: its keys' codes go to
     * a session of their own (KeyboardPair), so another's would type the one before's, shown on none.
     */
    fun retype(typing: Retyping, keyboard: Any? = null): Boolean = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch false
        val keys = typing.keys ?: return@batch false
        if (contexts.inputMethod(ic) != typing.im || keyboard != typing.keyboard) return@batch false
        router.retype(ic, typing.im, keys)
        true
    }

    /**
     * What 清除 dropped while something was typed: [keys] null when it cannot be typed again; [keyboard]
     * the one [im] was typed with, as the app told it ([clearTyped]).
     */
    data class Retyping(val im: String, val keys: List<KeyRouter.Typed>?, val keyboard: Any? = null)

    /** `repositionCursor` (`:454-458`): a tap at [position] in the code row. */
    fun moveCursor(position: Int) = pipeline.batch {
        val ic = contexts.active?.takeIf { it.hasFocus } ?: return@batch
        val e = pipeline.invokeAction(ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, position))
        // nobody took it: `defaultInvokeActionBehavior` (`inputmethodengine.cpp:46-54`), the app's
        // composing text (handwriting's) committed as shown, then reset
        if (!e.filtered) {
            val commit = forCommit(ic.panel.clientPreedit)
            if (commit.isNotEmpty()) commit(ic, commit)
            router.reset(ic, contexts.inputMethod(ic))
            pipeline.reset(ic)
        }
    }

    /** `getCandidates` (`:189-213`): [limit] from [offset], filtered as shown. */
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

    /** `getCandidateAction` (`:233-244`): the long-press menu of [index]. */
    fun candidateActions(index: Int): List<CandidateAction> {
        val list = contexts.active?.panel?.candidateList ?: return emptyList()
        return candidateAt(list, index)?.let(list::actions).orEmpty()
    }

    /** `triggerCandidateAction` (`:246-256`): [id] of [index]'s menu, if it still shows [text]. */
    fun triggerCandidateAction(index: Int, id: Int, text: String) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        val list = ic.panel.candidateList ?: return@batch
        val word = candidateAt(list, index) ?: return@batch
        if (shows(ic, word, text)) list.triggerAction(word, id)
    }

    /**
     * The engine's long-press act ([org.fcitx.fcitx5.android.engine.runtime.Engines.learning]'s):
     * [id] on the candidate at [index] of all, the engine's own, if it shows [text] (the engine's,
     * shown as this context converts it: 国家 as 國家) and offers [id]. Of all, not of the page:
     * an act takes the list back to its first page, and another candidate would be at a page's
     * index after it (review R3). Whether it was done.
     */
    fun actOnCandidate(index: Int, id: Int, text: String): Boolean = pipeline.batch act@{
        val ic = contexts.active ?: return@act false
        val list = ic.panel.candidateList ?: return@act false
        val word = (if (list.bulk) list.candidateFromAll(index) else null) ?: return@act false
        // an empty text is no check here: the engine always names one
        if (text.isEmpty() || shown(ic, word) != filterOutput(ic, text) || list.actions(word).none { it.id == id }) return@act false
        list.triggerAction(word, id)
        true
    }

    // `candidateAt` (`:220-231`): on the page when paging, else among all of a bulk list
    private fun candidateAt(list: CandidateList, index: Int) =
        if (pagingMode == 0 && list.bulk) list.candidateFromAll(index) else list.candidate(index)

    /** `setCandidatePagingMode` (`:514-523`): the active context's candidates sent anew in [mode]. */
    fun setCandidatePagingMode(mode: Int) {
        pagingMode = mode
        contexts.active?.let(::sendCandidates)
    }

    /** `offsetCandidatePage` (`:270-289`). */
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

    // ---- input methods (`native-lib.cpp:152-206`)

    /** `toggle()` on the most recent context (`instance.cpp:2176-2182`). */
    fun toggle() = pipeline.batch { contexts.mostRecent?.let { contexts.toggle(it) }; changedProfile() }

    /** `enumerate(forward)` on the most recent context (`:2184-2190`). */
    fun enumerate(forward: Boolean) = pipeline.batch { contexts.mostRecent?.let { contexts.enumerate(it, forward) }; changedProfile() }

    /** `setInputMethod` (`native-lib.cpp:179-185`): [im] for the active context, local. */
    fun setCurrentInputMethod(im: String) = pipeline.batch {
        val ic = contexts.active ?: return@batch
        contexts.setCurrentInputMethod(ic, im, local = true)
        changedProfile()
    }

    /**
     * `setEnabledInputMethods` (`native-lib.cpp:196-206`): the current group's list, unknown names
     * dropped, the default kept; every focused context switched; saved at once.
     */
    fun setInputMethods(list: List<String>) = pipeline.batch {
        contexts.setInputMethods(list, available)
        changedProfile()
        // `InputMethodGroupChanged` (`instance.cpp:711`): the frontend tells an unfocused active
        // context's change (`androidfrontend.cpp:341-348`); fcitx shows the name on the focused
        // ones 30 ms after (`instance.cpp:1213-1229`)
        contexts.active?.takeIf { !it.hasFocus }?.let { emit(HostEvent.InputMethodChanged(contexts.inputMethod(it))) }
        groupInfoTimer?.cancel()
        groupInfoTimer = clock.schedule(GROUP_INFO_DELAY) {
            pipeline.batch { contexts.focused?.let(::showInputMethodInformation) }
        }
    }

    /**
     * What each punctuation key types now: on the active context as its status says, or before one is focused, under
     * the group's first input method (the keyboard's at the host's start, and after a new table).
     */
    fun punctuationKeys(): Map<String, String> {
        val p = modules.punctuation ?: return emptyMap()
        return contexts.active?.let(p::keys) ?: startPunctuationKeys()
    }

    /**
     * [punctuationKeys] as a host started now would have them before any context: under the group's first input method, full
     * width off (it is not saved). What the app keeps for its next start, to draw the keys before the host is up.
     */
    fun startPunctuationKeys(): Map<String, String> =
        // a new host's first context takes the group's first input method (InputContexts), not the one now on
        modules.punctuation?.keys(contexts.group.inputMethods.firstOrNull()?.let { InputMethodCatalog.listed(it)?.chinese } == true).orEmpty()

    /** [block]'s changes as one event: the status, however often changed in it, sent once at its end. */
    fun <T> batch(block: () -> T): T = pipeline.batch(block)

    /** The input method of the active context (`currentIme`). */
    fun currentInputMethod(): String = contexts.active?.let(contexts::inputMethod) ?: contexts.group.inputMethods.firstOrNull().orEmpty()

    private fun changedProfile() {
        val next = profile.with(contexts.group)
        // what text() writes is the groups and their order: compared as they are, not as INI
        if (next.groups == profile.groups && next.order == profile.order) return
        profile = next
        persist(Persist.ProfileChanged(next))
    }

    override fun persistConf(conf: String) = persist(Persist.ConfChanged(conf))

    // `InputContextSwitchInputMethod` and its kin (`instance.cpp:725-764,1178-1212`, `:687-711`)
    // the new input method is read again by activateInputMethod, as fcitx's activate reads it
    @Suppress("UnusedParameter")
    private fun switched(ic: InputContext, reason: SwitchReason, old: String, new: String) {
        if (!ic.hasFocus) return
        // ReservedFirst: the old engine out (a switch commits what was typed), the new one in
        router.deactivate(ic, old, switching = true)
        // a capability change's new flags, for the new input method only
        contexts.applyCapabilities(ic)
        activateInputMethod(ic)
        // a capability change and a group change post no InputContextSwitchInputMethod: no temp
        // mode reset, no frontend event, no overlay (S1 deviation 4; the group's comes by its timer)
        if (reason == SwitchReason.CapabilityChanged || reason == SwitchReason.GroupChange) return
        // Default: temp modes leave (`tempmodemanager.cpp:47-49`), the frontend told (`androidfrontend.cpp:349-359`)
        pipeline.switchedInputMethod(ic)
        if (ic === contexts.active) emit(HostEvent.SwitchInputMethod(reason, old))
        // ReservedLast: the name, for every reason fcitx lists (`instance.cpp:1203-1211`)
        showInputMethodInformation(ic)
    }

    /** `Instance::activateInputMethod` (`instance.cpp:2454-2494`, `androidengine.cpp:492-513`). */
    private fun activateInputMethod(ic: InputContext) {
        val im = contexts.inputMethod(ic)
        val chinese = InputMethodCatalog.listed(im)?.chinese == true
        activationListeners.forEach { it.onActivated(ic, im, chinese) }
        router.activate(ic, im)
        if (ic === contexts.active) emit(HostEvent.InputMethodChanged(im))
    }

    /**
     * `showInputMethodInformation` (`instance.cpp:414-452,521-531`): the input method's label (fcitx's
     * compact information, its default) as the overlay, hidden after a second or by a key.
     */
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

    /** `InputState::hideInputMethodInfo` (`instance.cpp:612-623`): only the overlay this put up. */
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

    // ---- HostServices

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
        // a temp mode's panel replaces the context's whole panel (`inputPanel().reset()` first, as
        // `quickphrasetempmode.cpp:345-428` and `unicodetempmode.cpp` do), sent at the event's end
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

    override fun punctuationAsTyped(ic: InputContext, marks: Set<String>) {
        if (ic === contexts.active) emit(HostEvent.PunctuationAsTyped(marks))
    }

    override fun updateStatusArea(ic: InputContext) {
        if (ic !== contexts.active) return
        statusDirty = true
        // sent once at the end of the event (`InputContextFlushUI(StatusArea)`), now outside one:
        // an empty batch is the pipeline's flush
        pipeline.batch {}
    }

    // three a candidate a key, most of them an empty comment or a text no filter changes: neither
    // wrapped for nothing nor joined back into a string it already is (S15 A8.4)
    override fun filterOutput(ic: InputContext, text: String): String {
        if (text.isEmpty()) return text
        val given = FormattedText(arrayOf(text), intArrayOf(0), -1)
        val out = pipeline.filterOutput(ic, given)
        return if (out === given) text else out.toString()
    }

    // ---- HostOutput: what the pipeline sends, as the frontend's callbacks

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

        /** `InputContextFlushUI` (`androidfrontend.cpp:360-382`): the active context's panel and candidates. */
        override fun panel(ic: InputContext) {
            if (ic !== contexts.active) return
            val p = ic.panel
            if (p.empty) {
                emit(HostEvent.InputPanel(FormattedText.Empty, text(p.overlayMessage), FormattedText.Empty))
            } else {
                emit(HostEvent.InputPanel(pipeline.filterOutput(ic, p.preedit), pipeline.filterOutput(ic, p.auxUp), pipeline.filterOutput(ic, p.auxDown), p.quickPhrase))
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
            emit(HostEvent.Candidates(list.size, (0 until list.size).mapNotNull { i -> list.candidate(i)?.let { entry(ic, "", it) } }))
            return
        }
        var total = list.totalSize
        // limit candidate count to 16 (for paging)
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
        emit(HostEvent.Candidates(total, out))
    }

    // 候选词注音's readings go with the text as shown, kept while it has a code point for each (traditional
    // output converts character for character; a phrase it rewrites otherwise is shown none)
    private fun entry(ic: InputContext, label: String, word: PanelCandidate): CandidateEntry {
        val text = shown(ic, word)
        val readings = word.readings.takeIf { it.size == text.codePointCount(0, text.length) }.orEmpty()
        return CandidateEntry(if (label.isEmpty()) "" else filterOutput(ic, label), text, filterOutput(ic, word.comment), readings = readings)
    }

    /** [text] as the active context shows a candidate of it: filtered (繁體: chttrans), the long press's menu naming it so. */
    fun shownText(text: String): String = contexts.active?.let { filterOutput(it, text) } ?: text

    // `shows` (`androidfrontend.cpp:303-307`): the word the user tapped, by the text sent for it,
    // filtered (traditional, say); an empty text (a key's pick) is not checked
    private fun shows(ic: InputContext, word: PanelCandidate, text: String) = text.isEmpty() || shown(ic, word) == text

    // the text sent for [word]: filtered, one put in as it is ([PanelCandidate.asIs]) as it is
    private fun shown(ic: InputContext, word: PanelCandidate) = if (word.asIs) word.text else filterOutput(ic, word.text)

    /**
     * A temp mode's [TempCandidateList] as the panel's list (fcitx's `CommonCandidateList`: bulk,
     * pageable, labelled): a pick is the mode's candidate's own select.
     */
    private class TempModeCandidates(private val list: TempCandidateList) : CandidateList {
        private inner class Word(val candidate: TempCandidate) : PanelCandidate {
            override val text get() = candidate.text
            override val comment get() = candidate.comment
            override val asIs get() = candidate.asIs
            override fun select(ic: InputContext) {
                candidate.select?.invoke(ic)
            }
        }

        override val size get() = list.size
        override fun label(index: Int) = if (index in 0 until list.size) list.label(index) else ""
        override fun candidate(index: Int): PanelCandidate? = if (index in 0 until list.size) Word(list.candidate(index)) else null
        override val cursorIndex get() = list.cursorIndex
        override val bulk get() = true
        override val totalSize get() = list.totalSize
        override fun candidateFromAll(index: Int): PanelCandidate? = list.candidates.getOrNull(index)?.let(::Word)
        override val pageable get() = true
        override val hasPrev get() = list.hasPrev
        override val hasNext get() = list.hasNext
        override fun prev() = list.prev()
        override fun next() = list.next()
        override val layoutHint get() = list.layoutHint
    }

    companion object {
        /** The overlay's time (`instance.cpp:526`). */
        const val INFO_MILLIS = 1000L

        /** The group change's overlay delay (`instance.cpp:1218`). */
        const val GROUP_INFO_DELAY = 30L

        private const val BULK_FIRST = 16

        private fun text(s: String) = if (s.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(s), intArrayOf(0), -1)

        // `Text::toStringForCommit` (`fcitx-utils/text.cpp:111-121`): the segments not DontCommit
        private fun forCommit(t: FormattedText) =
            t.strings.indices.filter { t.flags.getOrElse(it) { 0 } and DONT_COMMIT == 0 }.joinToString("") { t.strings[it] }

        private const val DONT_COMMIT = 1 shl 5
    }
}
