/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.core.fromKeyEvent
import org.fcitx.fcitx5.android.core.keyCode
import org.fcitx.fcitx5.android.core.metaStateOf
import org.fcitx.fcitx5.android.core.plus
import org.fcitx.fcitx5.android.core.toSpannedString
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.KeyCodes
import org.fcitx.fcitx5.android.host.SwitchReason
import org.fcitx.fcitx5.android.input.address.AddressLearning
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.fcitx.fcitx5.android.input.bar.Calculation
import org.fcitx.fcitx5.android.input.bar.CalculationOffer
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.editing.ArrowKeys
import org.fcitx.fcitx5.android.input.editing.CodeInField
import org.fcitx.fcitx5.android.input.editing.CodePoints
import org.fcitx.fcitx5.android.input.editing.CommitCheck
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.EditingSession
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy
import org.fcitx.fcitx5.android.input.editing.ForwardedKeys
import org.fcitx.fcitx5.android.input.editing.InputConnectionEditor
import org.fcitx.fcitx5.android.input.editing.KeyEventRelay
import org.fcitx.fcitx5.android.input.editing.NumberKind
import org.fcitx.fcitx5.android.input.editing.WatchedEditor
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.fcitx.fcitx5.android.input.hardware.TakenKey
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.keyboard.BackspaceHold
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleState
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.SpaceCursor
import org.fcitx.fcitx5.android.input.keyboard.StickyNumbers
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.fcitx.fcitx5.android.input.keyboard.SwipeSelection
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTrace
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.input.shortcut.ClearedText
import org.fcitx.fcitx5.android.input.voice.VoiceAsk
import org.fcitx.fcitx5.android.input.voice.VoiceAsking
import org.fcitx.fcitx5.android.input.voice.VoiceCommit
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.scene.engine.section
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class FcitxInputMethodService : LifecycleInputMethodService() {

    private lateinit var fcitx: HostConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val keyEventRelay = KeyEventRelay { currentInputConnection }

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()

    // a keyboard unplugged: on a device reporting a built-in keyboard the configuration stays the same
    @VisibleForTesting
    internal val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) {
            Timber.d("onInputDeviceRemoved: deviceId=$deviceId")
            inputDeviceMgr.onDeviceRemoved(deviceId)
        }
    }
    private val inputDeviceMgr: InputDeviceManager = InputDeviceManager { isVirtualKeyboard ->
        postFcitxJob {
            setCandidatePagingMode(if (isVirtualKeyboard) 0 else 1)
        }
        // the same field keeps its floating cursor and drag
        if (!isVirtualKeyboard && cursorField !== startedWith) forgetCursor()
        val monitoring = currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard) == true
        // a field that reports no cursor gets the corner, not the last field's anchor
        if (!isVirtualKeyboard && !monitoring) anchorToCorner()
        if (isVirtualKeyboard) {
            hideStatusIcon()
        } else {
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        window.window?.let {
            navbarMgr.evaluate(it, isVirtualKeyboard)
        }
        if (isVirtualKeyboard) {
            if (inputViewStarted) currentInputEditorInfo?.let { inputView?.startInput(it, capabilityFlags) }
        } else {
            // to the user the keys went as if hidden, without onFinishInputView
            inputView?.keyboardHidden()
        }
    }

    private val hardwareKeyboard = HardwareKeyboard(
        post = { postFcitxJob(it) },
        inTextField = { currentInputEditorInfo?.isTypeNull() == false },
        shortcutsOn = { switchShortcuts },
    )

    // true from onStartInputView to onFinishInputView: a touch keyboard brought back meanwhile is started here
    private var inputViewStarted = false

    private var capabilityFlags = CapabilityFlags.DefaultFlags

    fun boardPlaced(placement: Set<String>) {
        window.window?.let { navbarMgr.place(it, placement) }
    }

    /**
     * Said above the keyboard, not as a toast: Android 13 drops an IME's toasts while its app may not
     * post notifications.
     */
    fun say(@StringRes id: Int) {
        say(getString(id))
    }

    /**
     * With no keyboard shown, a toast if [toast] and [undo] lapses. Returns whether it was said on the keyboard.
     */
    fun say(message: String, undo: UndoChip.Undo? = null, steady: Boolean = false, toast: Boolean = true, ms: Long? = null): Boolean {
        // the view shown, not the device mode's: a key typed with ⋯ open flips that mode
        val view = inputView?.takeIf { it.isShown } ?: candidatesView?.takeIf { it.isShown }
        val length = if (steady) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        return when {
            view == null -> {
                if (toast) toast(message, length)
                undo?.lapsed?.invoke()
                false
            }
            !view.say(message, undo, steady = steady, ms = ms) -> {
                if (toast) toast(message, length)
                false
            }
            else -> true
        }
    }

    private val engineSayer = object : EngineNotices.Sayer {
        override fun say(message: String, steady: Boolean) = this@FcitxInputMethodService.say(message, steady = steady, toast = false)
        override fun say(message: String, steady: Boolean, ms: Long?) = this@FcitxInputMethodService.say(message, steady = steady, toast = false, ms = ms)
    }

    val inPasswordField get() = fieldPolicy.password

    val inSensitiveField get() = capabilityFlags.hasAny(CapabilityFlag.PasswordOrSensitive)

    /** Read on the main thread at key press: by the time fcitx handles it another field may have started. */
    val inAddressField: Boolean
        get() = currentInputEditorInfo?.let { AddressKeys.of(it.toEditorTraits()) != AddressKeys.NONE } == true

    private val fieldEditor = WatchedEditor(InputConnectionEditor { currentInputConnection }) { clearedText.edited() }

    private val editingSession = EditingSession(
        fieldEditor,
        checkThread = if (BuildConfig.DEBUG) ({
            if (Looper.myLooper() != Looper.getMainLooper()) {
                // logged rather than thrown: a debug build is also someone's daily keyboard
                Timber.e(IllegalStateException("EditingSession used off the main thread"))
            }
        }) else ({})
    )

    @get:VisibleForTesting
    internal val commitCheck: CommitCheck get() = editingSession.commits

    private val selection get() = editingSession.selection

    private val codeInField = CodeInField()

    private var codePlaceTold = false

    /**
     * The views hear of a change through their codePlaceChanged: they take panel events apart from the
     * service and may have drawn before the service wrote it.
     */
    val codeInFieldNow: Boolean get() = editingSession.codeInField

    fun forStrip(panel: HostEvent.InputPanel): HostEvent.InputPanel {
        val drawn = if (panel.code && codeInFieldNow) panel.copy(preedit = FormattedText.Empty) else panel
        val line = keptLine ?: return drawn
        // first, for the row to tell a tap on it (CodeRowUi.notice)
        val aux = drawn.auxDown
        if (aux.isEmpty()) return drawn.copy(auxDown = FormattedText(arrayOf(line), intArrayOf(0), -1))
        val head = "$line  "
        val cursor = if (aux.cursor < 0) -1 else head.length + aux.cursor
        return drawn.copy(auxDown = FormattedText(arrayOf(head) + aux.strings, intArrayOf(0) + aux.flags, cursor))
    }

    val keptLine: String? get() = kept.line

    fun writeKept() = kept.write()

    private val kept = KeptCommits(this, editingSession, started = { startedWith != null }) { line ->
        inputView?.codePlaceChanged()
        candidatesView?.codePlaceChanged()
        line?.let(::announce)
    }

    /**
     * The row is no focus a screen reader follows. The deprecated announce is kept: a live region
     * would speak every code as it is typed.
     */
    @Suppress("DEPRECATION")
    private fun announce(line: String) {
        listOfNotNull(inputView, candidatesView).firstOrNull { it.isAttachedToWindow && it.isShown }?.announceForAccessibility(line)
    }

    private fun syncCodePlace() {
        val now = codeInFieldNow
        if (now == codePlaceTold) return
        codePlaceTold = now
        inputView?.codePlaceChanged()
        candidatesView?.codePlaceChanged()
    }

    private var placedPanel = HostEvent.InputPanel.EMPTY

    /** A write the field would not take floats the code from then on. */
    private fun placeCode(panel: HostEvent.InputPanel) {
        placedPanel = panel
        val route = codeInField.route(
            inField = ThemeManager.prefs.codeRow.getValue() == CodeRowStyle.InField,
            code = if (panel.code) panel.preedit.toString() else null,
            selection = selection.latest,
            othersComposing = editingSession.composing.isNotEmpty() && !editingSession.codeInField,
        )
        if (route == CodeInField.Route.FIELD) {
            if (!editingSession.writeCode(panel.preedit) { it.toSpannedString(highlightColor) }) codeInField.floats()
        } else if (editingSession.codeInField) {
            editingSession.leaveComposition()
        }
        syncCodePlace()
    }

    // set on the main thread, read on fcitx's (commitOverEngine); on a screen turn input restarts with
    // this same object
    @Volatile
    private var startedWith: EditorInfo? = null

    // kept through onFinishInput: CommitCheck keeps this field's drops
    private var commitsField: EditorInfo? = null

    // a field that says it is another (app, id, type, name or hint) is not offered the drops; WebView
    // fields tell nothing apart, so there they stay
    private fun commitsStartIn(attribute: EditorInfo, sensitive: Boolean) {
        // the same field restarted (restartInput): a waiting commit is not judged by what a read finds there
        if (commitsField?.fieldIdentity() != attribute.fieldIdentity()) editingSession.commits.forget() else editingSession.commits.moved()
        commitsField = attribute
        editingSession.commits.on = !sensitive && !CapabilityFlags.isPassword(attribute) && !attribute.isTypeNull()
    }

    val startedField: EditorInfo? get() = startedWith

    // here, not in the keyboard window: replacing the input view (a turn, floating, a theme) keeps the lock
    val sticky = StickyNumbers()

    /** The field the keys last started in; [startedWith] already holds the next one when they start. */
    var stickyInfo: EditorInfo? = null

    /** onStartInputView's, for the keys starting in it. */
    var restartingInput = false
        private set

    val clearedText = ClearedText()

    fun <T> quietly(block: () -> T): T = fieldEditor.quietly(block)

    private var cursorField: EditorInfo? = null

    private val lastPosted = LastPosted()

    val currentInputSelection: CursorRange
        get() = selection.latest

    private val arrowKeys = ArrowKeys(editingSession.selection)

    private var highlightColor: Int = DefaultHighlightColor

    private val prefs = AppPrefs.getInstance()
    private val autoPairs by prefs.keyboard.autoPairs
    private val switchShortcuts by prefs.candidates.switchShortcuts

    private val contextMemory = ContextMemory(keep = ContextChars)

    private val fieldPolicy = FieldPolicy(contextMemory)

    // onFinishInputView's focus out dropped the engine's context; no onStartInput since
    private var contextDropped = false

    // read by the keyboards alone: they are remade, the rest of the view kept (InputView.remakeKeyboards)
    private val remakeKeyboardsPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.t9Letters,
        prefs.keyboard.pinyinKeyboard,
        prefs.keyboard.shuangpinKeyboard,
        prefs.keyboard.englishKeyboard,
        prefs.keyboard.pinyinKeyboardLayout,
        prefs.keyboard.shuangpinKeyboardLayout,
        prefs.keyboard.englishKeyboardLayout,
        prefs.keyboard.tableKeyboardLayout,
        // the symbol panel's layout is read as it opens, not here
        prefs.keyboard.numberKeyboardLayout,
        prefs.keyboard.swipeUpPosition,
        prefs.keyboard.swipeDownPosition,
        prefs.keyboard.letterCase,
    )

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.advanced.disableAnimation,
    )

    private val keyboardHeightPrefs = with(prefs.keyboard) {
        listOf(keyboardHeightPercent, keyboardHeightPercentLandscape, keyboardHeightFollowTheme)
    }

    // made under PackGateView without it: remade when that changes
    private var textBuilt = false

    private var voiceBuilt = false

    private fun replaceInputView(theme: Theme, start: Boolean = false): InputView = SceneTrace.section("replaceInputView") {
        compactBuilt = keyboardCompact()
        screenBuilt = KeyboardArea.screen(this).also { styleBuilt = KeyboardStyles.of(it) }
        readingBuilt = EngineState.candidateReading.value
        val newInputView = SceneTrace.section("new InputView") { InputView(this, fcitx, theme) }
        val text = PackStore.status(PackKind.TEXT)
        textBuilt = text is PackStatus.Ready
        voiceBuilt = PackStore.ready(PackKind.VOICE)
        if (!textBuilt) newInputView.overKeys(PackGateView(this, theme, text), underBar = false)
        // end the hold first: detaching sends it a cancel that would drop what was heard
        endVoiceHold()
        SceneTrace.section("setInputView") {
            setInputView(newInputView)
            inputDeviceMgr.setInputView(newInputView)
        }
        inputView = newInputView
        // rebuilt in a started field (floating toggled): start it as onStartInputView would. Not when
        // onStartInputView follows: twice would start input twice
        if (start && inputViewStarted && inputDeviceMgr.isVirtualKeyboard) {
            currentInputEditorInfo?.let { SceneTrace.section("startInput") { newInputView.startInput(it, capabilityFlags) } }
        }
        newInputView
    }

    private fun endVoiceHold() {
        inputView?.hold?.inputEnded(BaseInputView.Hold.InputEnd.LEFT)
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView = SceneTrace.section("replaceCandidateView") {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        newCandidatesView
    }

    private fun replaceInputViews(theme: Theme, start: Boolean = false) {
        navbarMgr.evaluate(window.window!!, inputDeviceMgr.isVirtualKeyboard)
        replaceInputView(theme, start)
        replaceCandidateView(theme)
    }

    private fun rebuilding(why: ViewRebuild) {
        if (why.endsComposition) SceneTrace.section("endComposition") { endComposition() }
    }

    /**
     * The engine reset (focusOutIn) dropped its context and no input restarts, so it is told again
     * here, after the reset in fcitx's queue. Two rebuilds in a row (dark mode) still end with a tell.
     */
    private fun endComposition() {
        if (!currentInputStarted) {
            postFcitxJob { reset() }
            return
        }
        // with ClientUnfocusCommit fcitx drops its client preedit on focus out, so the session commits it.
        // Voice's tail is taken away instead: the old view's end types it once
        editingSession.leaveComposition()
        syncCodePlace()
        postFcitxJob { focusOutIn() }
        if (currentInputEditorInfo?.isTypeNull() == false) tellContext()
    }

    fun dropPredictions() {
        postFcitxJob { dropPredictions() }
    }

    /**
     * After fcitx restarts under the app (a pack imported), redo what onBindInput and onStartInput did:
     * without the focus the space reads （不可用） till another app. Queued until the new fcitx is ready.
     */
    private fun refocus() {
        val binding = currentInputBinding ?: return
        val uid = binding.uid
        val pkgName = pkgNameCache.forUid(uid)
        val info = currentInputEditorInfo
        val started = currentInputStarted && info != null
        val field = info != null && started && !info.isTypeNull()
        val flags = capabilityFlags
        postFcitxJob {
            activate(uid, pkgName)
            if (started) setCapFlags(flags)
            if (field) focus(true)
        }
        if (field) tellContext()
    }

    internal var screenBuilt: KeyboardArea.Screen? = null
        private set
    private var styleBuilt: KeyboardStyleState? = null

    internal var focusAfterStyleRebuild: ToolbarButton? = null

    @Keep
    private val keyboardStyleListener = ManagedPreference.OnChangeListener<String> { key, _ ->
        val landscape = screenBuilt?.landscape ?: return@OnChangeListener
        val built = styleBuilt ?: return@OnChangeListener
        if (key != KeyboardStyles.styleKey(landscape).key) return@OnChangeListener
        val screen = KeyboardArea.screen(this)
        if (screen.landscape != landscape) return@OnChangeListener
        val now = KeyboardStyles.of(screen)
        if (now.floating == built.floating && now.splits == built.splits) return@OnChangeListener
        rebuilding(ViewRebuild.STYLE)
        replaceInputViews(ThemeManager.activeTheme, start = true)
        focusAfterStyleRebuild?.let { inputView?.kawaiiBar?.focusForTalkBack(it) }
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        rebuilding(ViewRebuild.SETTING)
        replaceInputView(ThemeManager.activeTheme, start = true)
    }

    @Keep
    private val remakeKeyboardsListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        val view = inputView ?: return@OnChangeListener
        rebuilding(ViewRebuild.SETTING)
        SceneTrace.section("remakeKeyboards") {
            val started = inputViewStarted && inputDeviceMgr.isVirtualKeyboard
            view.remakeKeyboards(if (started) currentInputEditorInfo else null, capabilityFlags)
        }
    }

    private var compactBuilt = keyboardCompact()

    private var readingBuilt = EngineState.candidateReading.value

    // 紧凑 in or out remakes the keys; another level only resizes. A level sets both numbers in one edit
    @Keep
    private val keyboardHeightListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        val compact = keyboardCompact()
        if (compact == compactBuilt) return@OnChangeListener
        rebuilding(ViewRebuild.SETTING)
        replaceInputView(ThemeManager.activeTheme, start = true)
    }

    private val watchedPrefs: List<Pair<ManagedPreference<*>, ManagedPreference.OnChangeListener<Any>>> =
        recreateInputViewPrefs.map { it to recreateInputViewListener } + remakeKeyboardsPrefs.map { it to remakeKeyboardsListener } +
            keyboardHeightPrefs.map { it to keyboardHeightListener }
    private val keyboardStylePrefs = listOf(ThemeManager.prefs.keyboardStylePortrait, ThemeManager.prefs.keyboardStyleLandscape)

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        rebuilding(ViewRebuild.SETTING)
        replaceCandidateView(ThemeManager.activeTheme)
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        rebuilding(ViewRebuild.SETTING)
        replaceInputViews(it, start = true)
    }

    /**
     * Unlike `fcitx.runOnReady`/`launchOnReady`, where the next operation may start while one is
     * suspended, jobs posted here run strictly in order.
     */
    fun postFcitxJob(block: suspend HostApi.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        return job
    }

    override fun onCreate() {
        editingSession.codeFellBack = {
            codeInField.fallBack()
            syncCodePlace()
        }
        editingSession.commits.changed = kept::changed
        editingSession.commits.clock = SystemClock::uptimeMillis
        EngineNotices.sayer = engineSayer
        localeResources?.pickedChanged()
        PunctuationStartKeys.preload(this)
        // listed off the main thread: an input start (KeyboardPicker.offers) reads no file
        CustomKeyboards.relist()
        fcitx = HostDaemon.connect(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        // 候选词注音 is set on the fcitx thread; the bar's height follows it. Compared with the style the view was
        // made with, not the flow's first value: the engine's first configure sets the kept style
        lifecycleScope.launch {
            EngineState.candidateReading.collect {
                if (inputView == null || it == readingBuilt) return@collect
                rebuilding(ViewRebuild.READING)
                replaceInputView(ThemeManager.activeTheme, start = true)
            }
        }
        lifecycleScope.launch {
            var generation = PackStore.state.value.generation
            PackStore.state.collect {
                if (it.generation != generation) {
                    generation = it.generation
                    refocus()
                }
                if (inputView == null || (it.ready(PackKind.TEXT) == textBuilt && it.ready(PackKind.VOICE) == voiceBuilt)) return@collect
                rebuilding(ViewRebuild.SETTING)
                replaceInputView(ThemeManager.activeTheme, start = true)
            }
        }
        pkgNameCache = PackageNameCache(this)
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputDeviceListener, null)
        watchedPrefs.forEach { (pref, listener) -> pref.registerOnChangeListener(listener) }
        keyboardStylePrefs.forEach { it.registerOnChangeListener(keyboardStyleListener) }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fcitx.runIfReady {
                SubtypeManager.syncWith(enabledIme(), this@FcitxInputMethodService)
            }
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        // the system's configuration, as onConfigurationChanged gets it, not the app-language one
        lastKnownConfig = Configuration(super.getResources().configuration)
    }

    private fun handleFcitxEvent(event: HostEvent) {
        when (event) {
            is HostEvent.Commit -> {
                commitTyped(event.text, event.cursor)
            }
            is HostEvent.Key -> event.let event@{
                if (it.keyStates.virtual) {
                    when (it.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(EditorKeyPolicy.Direction.Left)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(EditorKeyPolicy.Direction.Right)
                        FcitxKeyMapping.FcitxKey_Up -> sendLine(SpaceCursor.Line.Up, null)
                        FcitxKeyMapping.FcitxKey_Down -> sendLine(SpaceCursor.Line.Down, null)
                        // a keyboard's own code handed back unhandled (no focused context): never text
                        else -> if (it.unicode > 0 && !KeyCodes.isInternal(it.unicode)) {
                            commitTyped(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    clearedText.edited()
                    if (keyEventRelay.replay(it.timestamp)) {
                        // fcitx hands back the up of every key it took: treating it as a move would forget every waiting
                        // commit on a hardware keyboard. An up that changes the field shows in its cursor report
                        if (!it.up) editingSession.commits.moved()
                        return@event
                    }
                    when (val action = ForwardedKeys.decide(it.keySym.keyCode, it.up, it.unicode)) {
                        is ForwardedKeys.Action.SendKey -> {
                            val eventTime = SystemClock.uptimeMillis()
                            if (action.up) {
                                sendUpKeyEvent(eventTime, action.keyCode, it.keyStates.metaStateOf(action.keyCode, up = true))
                            } else {
                                sendDownKeyEvent(eventTime, action.keyCode, it.keyStates.metaStateOf(action.keyCode, up = false))
                            }
                        }
                        is ForwardedKeys.Action.CommitChar -> commitText(action.text)
                        // not the event: its sym and unicode are the character, a password's too
                        ForwardedKeys.Action.Ignore -> Timber.d("Unhandled Fcitx KeyEvent: no key code, up=${it.up}")
                    }
                }
            }
            is HostEvent.ClientPreedit -> {
                updateComposingText(event.text)
            }
            is HostEvent.DeleteSurrounding -> {
                handleDeleteSurrounding(event.before, event.after)
            }
            is HostEvent.InputPanel -> {
                if (event.preedit.isNotEmpty()) clearedText.edited()
                placeCode(event)
            }
            is HostEvent.InputMethodChanged -> {
                val im = event.im
                hardwareKeyboard.inputMethodChanged(im)
                // the new session knows nothing of the field: told after fcitx's reset of it (FieldPolicy). Not when
                // the same one is refocused (screen turn)
                if (fieldPolicy.inputMethodChanged(im)) {
                    clearedText.edited()
                    tellContext(started = true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val subtype = SubtypeManager.subtypeOf(im)
                    val had = inputMethodManager.currentInputMethodSubtype?.let(SubtypeManager::inputMethodOf)
                    // [^1]: notify system that input method subtype has changed
                    if (subtype != null && subtypeEchoes.shouldTell(im, had)) tellSubtype(im, subtype)
                }
                if (inputDeviceMgr.evaluateOnInputMethodActivate()) {
                    showStatusIcon(StatusIconMapping.fromEntry(event.entry))
                }
            }
            is HostEvent.SwitchInputMethod -> {
                val reason = event.reason
                if (reason != SwitchReason.CapabilityChanged &&
                    reason != SwitchReason.Other
                ) {
                    if (inputDeviceMgr.evaluateOnInputMethodSwitch()) {
                        // show inputView for [CandidatesView] when input method switched by user
                        forceShowSelf()
                    }
                }
            }
            else -> {}
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        editingSession.deleteSurrounding(before, after, inCodePoints = true)
        repeat(before) { contextMemory.deleted() }
    }

    private fun handleBackspaceKey() {
        val deleting = selection.latest
        keepDeleted(deleting.isNotEmpty())
        contextMemory.backspace(deleting.start, deleting.end)
        // the cursor report is predicted and tells the session nothing, so read now, as with no selection:
        // what it reads is the field once the selection is gone
        if (deleting.isNotEmpty()) tellContext(selected = false)
        fieldEditor.quietly {
            if (pairing) backspacePair()
            if (!editingSession.backspace(currentInputEditorInfo.toEditorTraits(packageName))) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
    }

    /**
     * Not in a field kept from learning, nor one taking key events alone (a terminal), whose text is
     * not what it shows.
     */
    private fun keepDeleted(selection: Boolean) {
        val field = startedWith ?: return
        if (inSensitiveField || currentInputEditorInfo.isTypeNull()) return
        clearedText.deleting(field, selection) { before, after -> readAround(before, after, selected = selection) }
    }

    private fun readAround(before: Int, after: Int, selected: Boolean): ClearedText.Read? {
        val ic = currentInputConnection ?: return null
        return readAround(ic, selection.latest.let { minOf(it.start, it.end) }, before, after, selected)
    }

    private fun backspacePair() {
        val quoteOpen = editingSession.pairs.quoteOpen
        editingSession.pairs.backspace()
        // fcitx does not take back its “ on Backspace and would make the next quote a lone ”. Read now,
        // less the “: the key event sent next may not have landed by a later read
        if (quoteOpen && !editingSession.pairs.quoteOpen) {
            postFcitxJob { reset(drop = true) }
            tellContext(before = {
                currentInputConnection?.getTextBeforeCursor(ContextChars + 1, 0)
                    ?.let { it.dropLast(CodePoints.lengthOfLast(it, 1) ?: 1) }
            }, selected = false)
        }
    }

    fun sendBackspaceKey() {
        val deleting = selection.latest
        keepDeleted(deleting.isNotEmpty())
        contextMemory.backspace(deleting.start, deleting.end)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    fun changedPastSession() = editingSession.commits.moved()

    fun performContextMenuAction(id: Int) {
        fieldEditor.changing()
        editingSession.commits.moved()
        if ((id == android.R.id.cut || id == android.R.id.paste) && selection.latest.isNotEmpty()) contextMemory.cut()
        currentInputConnection?.performContextMenuAction(id)
    }

    private val pairing get() = fieldPolicy.pairs(autoPairs, rawKeys = currentInputEditorInfo.isTypeNull(), phone = phoneField)

    // read per commit and per Backspace repeat: cheaper than remaking the traits each time
    private var phoneField = false

    private fun commitTyped(text: String, cursor: Int = -1) {
        kept.writeBeforeCommit()
        if (cursor != -1 || !pairing || !editingSession.pairs.type(text)) {
            val start = editingSession.commitStart
            commitText(text, cursor)
            if (pairing && start != null) editingSession.pairs.committed(text, cursor, start)
            return
        }
        contextMemory.committed(text, now = SystemClock.elapsedRealtime())
        voiceAsking.put(text)
    }

    private fun handleReturnKey() {
        clearedText.edited()
        // a waiting commit is not read back after the editor's action (a chat's send clears the field)
        editingSession.commits.moved()
        when (val action = EditorKeyPolicy.onReturn(currentInputEditorInfo.toEditorTraits(packageName))) {
            EditorKeyPolicy.ReturnAction.SendEnter -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            is EditorKeyPolicy.ReturnAction.PerformEditorAction ->
                currentInputConnection.performEditorAction(action.actionId)
        }
    }

    // a slide may send two lines at once: the editor applies keys later, so the second reads where the first was
    private val lineRush = SpaceCursor.LineRush()

    /** The selection is first set from [anchor] to [head] where [head] is the left end, unless [anchored]. */
    fun extendLine(line: SpaceCursor.Line, anchor: Int, head: Int, anchored: Boolean = false): Boolean {
        currentInputConnection ?: return false
        if (head < anchor && !anchored) editingSession.selectFrom(anchor, head)
        return sendLine(line, CursorRange(anchor, head))
    }

    var unpredictedSelection: ((CursorRange) -> Unit)? = null

    // none at the field's very start or end, where the key would move the focus away (SpaceCursor.sendable)
    private fun sendLine(line: SpaceCursor.Line, ends: CursorRange?): Boolean {
        val ic = currentInputConnection ?: return false
        val up = line == SpaceCursor.Line.Up
        val facing = ends == null || (if (up) ends.end <= ends.start else ends.end >= ends.start)
        if (facing) {
            val now = SystemClock.uptimeMillis()
            val before = if (up) ic.getTextBeforeCursor(LINE_REACH, 0) else null
            val after = if (!up) ic.getTextAfterCursor(LINE_REACH, 0) else null
            val side = before ?: after
            // only rushed lines not landed yet: a read is not stale by those the editor already applied
            val rushed = lineRush.unlanded(line, now, side)
            if (!SpaceCursor.sendable(line, before, after, rushed)) {
                // the edge only while none of the rushed lines landed: read after them, it would skip lines
                return side != null && lineRush.unmoved(line, now, side.length) &&
                    SpaceCursor.toEdge(line, before, after, rushed, whole = side.length < LINE_REACH) &&
                    moveToFieldEdge(ic, line, ends?.start)
            }
            lineRush.sent(line, now, read = side?.length ?: -1, lines = side?.count { it == '\n' } ?: 0)
        }
        clearedText.edited()
        val code = if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        if (ends == null) sendDownUpKeyEvents(code) else sendShifted(ic, code)
        return true
    }

    private fun sendShifted(ic: InputConnection, code: Int) {
        editingSession.commits.moved()
        val t = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        fun key(action: Int, key: Int, state: Int) = sendKeyEvent(ic, action, key, state, t, scancode = 0)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, meta)
        key(KeyEvent.ACTION_DOWN, code, meta)
        key(KeyEvent.ACTION_UP, code, meta)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
    }

    private fun moveToFieldEdge(ic: InputConnection, line: SpaceCursor.Line, anchor: Int? = null): Boolean {
        val end = if (line == SpaceCursor.Line.Up) 0
        else ic.getExtractedText(ExtractedTextRequest(), 0)?.let { x -> x.text?.let { x.startOffset + it.length } } ?: return false
        clearedText.edited()
        editingSession.commits.moved()
        ic.setSelection(anchor ?: end, end)
        return true
    }

    private fun handleArrowKey(direction: EditorKeyPolicy.Direction) {
        clearedText.edited()
        editingSession.commits.moved()
        val keyCode = when (direction) {
            EditorKeyPolicy.Direction.Left -> KeyEvent.KEYCODE_DPAD_LEFT
            EditorKeyPolicy.Direction.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        val action = arrowKeys.onArrow(
            currentInputEditorInfo.toEditorTraits(packageName), direction,
            textBefore = { currentInputConnection?.getTextBeforeCursor(it, 0) },
            textAfter = { currentInputConnection?.getTextAfterCursor(it, 0) },
        )
        when (action) {
            EditorKeyPolicy.ArrowAction.SendKey -> sendDownUpKeyEvents(keyCode)
            is EditorKeyPolicy.ArrowAction.MoveCursor ->
                currentInputConnection.setSelection(action.position, action.position)
        }
    }

    /** Kept here because the bar is remade with the view. */
    val addressLearning = AddressLearning()

    fun putAround(before: Int, after: Int, text: String, cursor: Int) {
        if (editingSession.composing.isNotEmpty()) return
        // no keptFirst: the units to replace were counted without the kept text, written once its wait ends
        val at = if (cursor == text.length) -1 else cursor
        editingSession.replaceAround(before, after, text, at)
        contextMemory.committed(text, at, SystemClock.elapsedRealtime())
        voiceAsking.put(text)
    }

    fun commitText(text: String, cursor: Int = -1) {
        kept.writeBeforeCommit()
        val landed = editingSession.commitText(text, cursor)
        contextMemory.committed(landed, if (landed.length == text.length) cursor else -1, SystemClock.elapsedRealtime())
        voiceAsking.put(landed)
        if (cursor == -1 && (landed.endsWith('=') || landed.endsWith('＝'))) offerCalculation()
    }

    /** Read off the field, however its digits got there; the editor has the commit by then, its commands in order. */
    private fun offerCalculation() {
        val worked = CalculationOffer.read(
            fieldPolicy.sensitive,
            currentInputEditorInfo.isTypeNull(),
            CALCULATION_CHARS,
        ) { currentInputConnection?.getTextBeforeCursor(it, 0) } ?: return
        inputView?.offerCalculation(worked, selection.latest.start)
    }

    /**
     * The engine's typed text goes in first, and the engine forgets its commit (else English would put its space
     * behind punctuation in [text]). In a password [replacing] is known only from the editor's reports, so pressed
     * again before the first report both stay. Posted behind fcitx: by then another field may have the focus.
     */
    fun commitOverEngine(text: String, replacing: String = "", voice: Boolean = false, typed: Boolean = false): Job {
        val address = inAddressField
        val asked = PostedCommit(startedWith, replacing, text)
        val talk = if (voice) voiceAsk.posting() else 0
        return postFcitxJob {
            if (!stillIn(asked, text)) {
                if (voice) lifecycleScope.launch(Dispatchers.Main) { voiceAsk.landed(talk, inField = false) }
                return@postFcitxJob
            }
            commitAndReset(address)
            // Main, never in place: must land after the engine's own commit event (in place it typed `.你` for 你)
            lifecycleScope.launch(Dispatchers.Main) {
                if (!stillIn(asked, text)) {
                    if (voice) voiceAsk.landed(talk, inField = false)
                    return@launch
                }
                if (!voice) {
                    val field = if (typed && replacing.isEmpty() && text.length == 1) typedField else postedField
                    return@launch lastPosted.putIn(asked, field, selection)
                }
                voiceAsk.voice(talk) { lastPosted.putIn(asked, postedField, selection) }
                voiceAsk.landed(talk, inField = true)?.let(voiceAsking::tell)
            }
        }
    }

    private val voiceAsk = VoiceAsk()

    private val voiceAsking = VoiceAsking(this, voiceAsk, editingSession, sensitive = { fieldPolicy.sensitive }, started = { startedWith != null })

    val voiceAsks: Boolean get() = voiceAsking.asks

    /** Whether a talk's stretches go to the engine: only where it follows voice's text and learns, else no spoken text is held. */
    val voiceKeepsStretches: Boolean get() = voiceAsks && AppPrefs.getInstance().internal.useHabits.getValue()

    fun voiceTalkBegan() = voiceAsk.began()

    fun voiceTalkEnded() {
        voiceAsk.ended()?.let(voiceAsking::tell)
    }

    private fun stillIn(asked: PostedCommit, text: String): Boolean {
        if (asked.lands(startedWith)) return true
        // its length alone: the debug log can be exported
        Timber.d("commitOverEngine: the field changed or finished, ${text.length} chars dropped")
        return false
    }

    /** Where the field may not be read, what the app put in is known from the cursor report and deleted unread. */
    private val postedField = object : LastPosted.Field {
        override val sensitive: Boolean get() = fieldPolicy.sensitive
        override val composing: Boolean get() = editingSession.composing.isNotEmpty()

        override fun textBefore(length: Int): CharSequence? = currentInputConnection?.getTextBeforeCursor(length, 0)

        override fun delete(deletion: PostedCommit.Deletion) {
            if (deletion.inCodePoints) {
                handleDeleteSurrounding(deletion.count, 0)
            } else {
                editingSession.deleteSurrounding(deletion.count, 0, inCodePoints = false)
            }
        }

        override fun commit(text: String) = commitText(text)
    }

    private val typedField = object : LastPosted.Field by postedField {
        override fun commit(text: String) = commitTyped(text)
    }

    // there should be a new fcitx API for this
    suspend fun HostApi.commitAndReset(address: Boolean) {
        val resolution = PendingInputPolicy.resolve(
            inputMethodEntryCached.languageCode,
            hasPreedit = composingCached,
            address = address
        )
        when (resolution) {
            PendingInputPolicy.Resolution.SelectFirstCandidate -> select(0)
            PendingInputPolicy.Resolution.CommitAsTyped -> sendKey(FcitxKeyMapping.FcitxKey_Return)
            // on fcitx's thread pool; the composing state is the main thread's. Voice's is not finished (the
            // commit replaces it); the code in the field is taken away
            PendingInputPolicy.Resolution.FinishComposing ->
                withContext(Dispatchers.Main) {
                    if (!editingSession.provisional) editingSession.leaveComposition()
                    syncCodePlace()
                }
            PendingInputPolicy.Resolution.None -> {}
        }
        reset()
    }

    override fun sendDownUpKeyEvents(keyEventCode: Int) {
        editingSession.commits.moved()
        super.sendDownUpKeyEvents(keyEventCode)
    }

    private fun sendKeyEvent(
        ic: InputConnection?, action: Int, code: Int, metaState: Int, downTime: Long, eventTime: Long = downTime,
        scancode: Int = ScancodeMapping.keyCodeToScancode(code),
    ) {
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic?.sendKeyEvent(KeyEvent(downTime, eventTime, action, code, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, scancode, flags))
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        clearedText.edited()
        editingSession.commits.moved()
        sendKeyEvent(currentInputConnection, KeyEvent.ACTION_DOWN, keyEventCode, metaState, eventTime)
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) =
        sendKeyEvent(currentInputConnection, KeyEvent.ACTION_UP, keyEventCode, metaState, eventTime, SystemClock.uptimeMillis())

    fun deleteSelection() {
        if (selection.latest.isNotEmpty()) keepDeleted(selection = true)
        fieldEditor.quietly { editingSession.deleteSelection() }
        tellContext(selected = false)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) =
        fieldEditor.quietly { editingSession.applySelectionOffset(offsetStart, offsetEnd) }

    fun cancelSelection() = editingSession.cancelSelection()

    fun select(start: Int, end: Int) = editingSession.select(start, end)

    // none where the read is placed by the tracked cursor (below Android 12) and a predicted selection is not yet reported
    fun readForSwipeSelect(): ClearedText.Read? {
        if (inPasswordField) return null
        val ic = currentInputConnection ?: return null
        val tracked = if (selection.settled) selection.latest.let { minOf(it.start, it.end) } else -1
        return readAround(ic, tracked, SwipeSelection.READ, SwipeSelection.READ, selected = selection.latest.isNotEmpty())?.takeIf { it.at >= 0 }
    }

    val reportedSelection: CursorRange
        get() = selection.current

    val selectionSettled: Boolean
        get() = selection.settled

    private val settleWaiters = SettleWaiters { selection.settled }

    /**
     * Or after [timeoutMs] where it never does. Below Android 12 滑选's read is placed by the tracked
     * cursor, true only once reported.
     */
    fun whenSelectionSettled(timeoutMs: Long, block: () -> Unit) = settleWaiters.add(timeoutMs, block)

    /**
     * The editor ignores a selection past the end without a report: else [currentInputSelection]
     * would stay on it for Backspace and the next 滑选.
     */
    fun forgetSelection(range: CursorRange) {
        val reported = selection.current
        if (selection.latest.rangeEquals(range) && !reported.rangeEquals(range)) selection.resetTo(reported.start, reported.end)
    }

    private lateinit var lastKnownConfig: Configuration

    // null while the service is constructed, should the framework read its resources that early
    private val localeResources: ServiceLocaleResources? = ServiceLocaleResources(this)

    override fun getResources(): Resources = super.getResources().let { localeResources?.resources(it) ?: it }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // the field's composing text committed, not wiped. If super restarts input the context is told twice
        endComposition()
        /**
         * skip keyboard|keyboardHidden (we have [inputDeviceMgr]) and uiMode (we have
         * [onThemeChangeListener]): super would call resetStateForNewConfiguration() -> initViews() and
         * replace the views again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        /**
         * the mask is computed here: how `handledConfigChanges` works is unclear
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (diff and f != diff) {
            // super throws the views away (setContentView) before it makes them anew
            endVoiceHold()
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig = newConfig
    }

    override fun onWindowShown() {
        super.onWindowShown()
        highlightColor =
            styledColorOrDefault(android.R.attr.colorAccent, DefaultHighlightColor).alpha(0.4f)
        InputFeedbacks.syncSystemPrefs()
        InputFeedbacks.syncSounds()
        EngineNotices.keyboardShown()
    }

    override fun onCreateInputView(): View? {
        rebuilding(ViewRebuild.SYSTEM)
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // expand inputArea to fullscreen; the layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        // setInputView sets the height to WRAP_CONTENT
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    private val floatingRegion = Rect()
    private val codeStripRegion = Rect()

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard && inputView?.floatingRegion(floatingRegion) == true) {
            // the app laid out as if there were no keyboard; only the keyboard's own place takes touches
            val h = decorView.height
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(floatingRegion)
                if (inputView?.codeStripRegion(codeStripRegion) == true) touchableRegion.union(codeStripRegion)
            }
        } else if (inputDeviceMgr.isVirtualKeyboard) {
            val keyboard = inputView?.keyboardView
            keyboard?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                if (keyboard != null && inputView?.codeStripRegion(codeStripRegion) == true) {
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(0, inputViewLocation[1], decorView.width, decorView.height)
                    touchableRegion.union(codeStripRegion)
                } else {
                    touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                }
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    fun showTouchKeyboard() = inputDeviceMgr.showTouchKeyboard()

    override fun onEvaluateFullscreenMode() = false

    private var backspaceHold: BackspaceHold? = null

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val sym = KeySym.fromKeyEvent(event)
        if (sym != null) {
            val up = event.action == KeyEvent.ACTION_UP
            if (event.keyCode == KeyEvent.KEYCODE_DEL && up) backspaceHold = null
            val hold = if (event.keyCode != KeyEvent.KEYCODE_DEL || up) {
                null
            } else {
                if (event.repeatCount == 0 || backspaceHold == null) backspaceHold = BackspaceHold()
                backspaceHold
            }
            val timestamp = keyEventRelay.remember(event)
            val states = KeyStates.fromKeyEvent(event)
            postFcitxJob {
                if (hold == null || hold.repeat(composingCached)) {
                    sendKey(sym, states, event.scanCode, up, timestamp)
                }
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    // its repeats and release must not reach fcitx alone
    private val keyTaken = TakenKey()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Shift+Delete forgets the highlighted candidate: taken while one is highlighted, since the engine's
        // answer comes too late for the key
        val forgets = keyTaken.down(keyCode, repeat = event.repeatCount > 0) {
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL && event.isShiftPressed && candidatesView?.forgetHighlighted() == true
        }
        // told even when taken: else letting go of Shift soon after would switch 中/英
        val taken = hardwareKeyboard.down(
            keyCode, event.isCtrlPressed, event.isShiftPressed, others = event.isAltPressed || event.isMetaPressed,
            repeat = event.repeatCount > 0, timeMs = event.eventTime
        )
        if (forgets || taken) return true
        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(event, this)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyTaken.up(keyCode)) return true
        if (hardwareKeyboard.up(keyCode, event.eventTime)) return true
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // deprecated in 29, but editors still use it on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        // Backspace starts another run. WebView and Compose editors do not report it
        if (!focusChanged) clearedText.moved()
        inputDeviceMgr.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceMgr.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true

    override fun onBindInput() {
        val uid = currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        Timber.d("onBindInput: uid=$uid pkg=$pkgName")
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype) ?: return
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * An input method change made inside fcitx is told to the system (see [^1]) and the echoing
     * [onCurrentInputMethodSubtypeChanged] ignored: only the system picker changes the input method.
     */
    private val subtypeEchoes = SubtypeEchoes()

    /**
     * The system refuses the switch after it disabled this keyboard (its settings, a test restoring the
     * device's): logged, not thrown on the main looper, and the awaited echo forgotten.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tellSubtype(im: String, subtype: InputMethodSubtype) {
        try {
            switchInputMethod(InputMethodUtil.componentName, subtype)
        } catch (expected: RuntimeException) {
            // a plain RuntimeException; named `expected` as detekt's TooGenericExceptionCaught allows
            Timber.w(expected, "IMChangeEvent: the system refused the switch to im=$im")
            subtypeEchoes.refused(im)
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype) ?: return
            val echo = subtypeEchoes.isEcho(im)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im, echo=$echo")
            if (echo) return
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // the screen turned (resetStateForNewConfiguration): input restarts with the first start's EditorInfo
        val again = restarting && attribute === startedWith
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated: the client app's job, a workaround would add bugs
        selection.startInput(again, attribute.initialSelStart, attribute.initialSelEnd)
        val flags = CapabilityFlags.forField(attribute, packageName)
        val sensitive = flags.hasAny(CapabilityFlag.PasswordOrSensitive)
        // a restart has no onFinishInputView and the focus out below drops the engine's input: the editor's
        // preedit becomes text. Voice's tail is taken away (its talk composes it anew); the code in the field
        // is taken away when proven ours, else it floats from now on
        if (restarting) {
            when (editingSession.kind) {
                EditingSession.Kind.PROVISIONAL ->
                    editingSession.provisionalRestarted(sameField(startedWith, attribute), attribute.initialSelStart, attribute.initialSelEnd)
                EditingSession.Kind.CODE -> editingSession.codeRestarted(again)
                EditingSession.Kind.TEXT -> currentInputConnection?.finishComposingText()
            }
        }
        if (restarting && cursorField === startedWith) cursorField = attribute
        startedWith = attribute
        commitsStartIn(attribute, sensitive)
        // clears are never put back in another field; a screen turn is the same input
        if (!again) clearedText.forget()
        lastPosted.forget()
        editingSession.resetComposingState()
        val traits = attribute.toEditorTraits()
        phoneField = traits.number == NumberKind.PHONE
        codeInField.startInput(traits, restarting)
        editingSession.inlineCompletion = traits.isUri || traits.isEmail
        syncCodePlace()
        capabilityFlags = flags
        editingSession.pairs.forget()
        arrowKeys.forget()
        contextReads.forget()
        val started = fieldPolicy.focusAnother(
            ContextMemory.field(
                attribute.packageName, attribute.fieldId, attribute.inputType, attribute.fieldName, attribute.hintText
            ),
            password = CapabilityFlags.isPassword(attribute),
            sensitive = sensitive,
            learnFlags = FieldPolicy.learnFlags(attribute.toEditorTraits()),
        )
        // EditorInfo may change between onStartInput and onStartInputView
        inputDeviceMgr.notifyOnStartInput(attribute)
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        settleWaiters.wake()
        val isNullType = attribute.isTypeNull()
        if (!isNullType) hardwareKeyboard.fieldStarted()
        // wait until InputContext created/activated
        postFcitxJob {
            if (restarting) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(flags)
            // for hardware keyboard, focus to allow switching input methods before onStartInputView
            if (!isNullType) {
                focus(true)
            }
        }
        contextDropped = false
        if (!isNullType) {
            // an editor without initial text may still answer; after a turn the initial text is the first start's, so ask
            val initial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !again
            tellContext(
                before = {
                    (if (initial) attribute.getInitialTextBeforeCursor(ContextChars, 0) else null)
                        ?: currentInputConnection?.getTextBeforeCursor(ContextChars, 0)
                },
                after = {
                    (if (initial) attribute.getInitialTextAfterCursor(Action.Context.HOST_CHARS, 0) else null)
                        ?: currentInputConnection?.getTextAfterCursor(Action.Context.HOST_CHARS, 0)
                },
                started = started,
            )
        }
    }

    private fun forgetCursor() {
        cursorField = startedWith
        candidatesView?.forgetCursor()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        if (localeResources?.pickedChanged() == true) {
            rebuilding(ViewRebuild.LANGUAGE)
            replaceInputViews(ThemeManager.activeTheme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                postFcitxJob { SubtypeManager.syncWith(enabledIme(), this@FcitxInputMethodService) }
            }
        }
        postFcitxJob { focus(true) }
        inputViewStarted = false
        val virtual = inputDeviceMgr.evaluateOnStartInputView(info, this)
        // a browser's field may say what it is anew here: the later wins
        info.toEditorTraits().let {
            codeInField.startInputView(it)
            editingSession.inlineCompletion = it.isUri || it.isEmail
        }
        inputViewStarted = true
        restartingInput = restarting
        if (virtual) {
            // onStartInputView always follows onStartInput: editorInfo and capFlags are up to date
            inputView?.startInput(info, capabilityFlags)
        } else {
            // a WebView restarted after an edit keeps its cursor and drag
            if (!restarting) forgetCursor()
            if (currentInputConnection?.monitorCursorAnchor() != true) anchorToCorner()
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        // shown again in the field it was hidden in, no onStartInput between to tell it anew
        if (contextDropped) {
            contextDropped = false
            if (!info.isTypeNull()) tellContext()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd] cand=[$candidatesStart,$candidatesEnd]")
        val update = editingSession.onCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        arrowKeys.cursorReported(newSelStart, newSelEnd)
        // fcitx still counts a “ left open (or stepped over) and would make the next quote a lone ”. The
        // context comes after: the update's, or told here where it tells none
        val quoteReset = editingSession.quoteLeftOpen
        if (quoteReset) postFcitxJob { reset() }
        handleCursorUpdate(update)
        syncCodePlace()
        if (update != EditingSession.CursorUpdate.None) inputView?.cursorMoved()
        if (update != EditingSession.CursorUpdate.None) unpredictedSelection?.invoke(CursorRange(newSelStart, newSelEnd))
        if (quoteReset && update == EditingSession.CursorUpdate.None) tellContextLater()
        inputView?.updateSelection(newSelStart, newSelEnd)
        settleWaiters.wake()
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    // anchor CandidatesView to bottom-left corner in case InputConnection does not
    // support monitoring CursorAnchorInfo
    private fun anchorToCorner() {
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        candidatesView?.updateCursorAnchor(contentSize)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        candidatesView?.updateCursorAnchor(info, decorLocation, contentSize)
    }

    private fun handleCursorUpdate(update: EditingSession.CursorUpdate) {
        when (update) {
            EditingSession.CursorUpdate.None -> {}
            EditingSession.CursorUpdate.ResetIfNotEmpty -> {
                postFcitxJob {
                    if (!isEmpty()) {
                        Timber.d("handleCursorUpdate: reset")
                        reset()
                    }
                }
                tellContextLater()
            }
            is EditingSession.CursorUpdate.MovePreeditCursor -> {
                // only then is a hop back here worth the keys queued behind it on fcitx's thread
                val codeInField = editingSession.codeInField
                postFcitxJob {
                    // onUpdateSelection lags behind fast typing (long-press backspace): only the latest report moves the cursor
                    if (update.updateIndex != editingSession.cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to ${update.codePointPosition}")
                    moveCursor(update.codePointPosition)
                    if (!codeInField) return@postFcitxJob
                    withContext(Dispatchers.Main) {
                        // the panel placed last, on this thread: one taken on fcitx's may be a key behind
                        val panel = placedPanel
                        if (panel.code && editingSession.codeInField) editingSession.writeCode(panel.preedit) { it.toSpannedString(highlightColor) }
                    }
                }
            }
            EditingSession.CursorUpdate.FocusOutIn -> {
                postFcitxJob { focusOutIn() }
                tellContextLater()
            }
        }
    }

    private val textBeforeCursor = { editingSession.beforeCode(currentInputConnection?.getTextBeforeCursor(ContextChars, 0)) }
    private val textAfterCursor = { editingSession.afterCode(currentInputConnection?.getTextAfterCursor(Action.Context.HOST_CHARS, 0)) }

    // a read queued behind a newer one, or from a field since left, is not made
    private val contextReads = ContextReads()

    /** Not read at all in a password or other sensitive field. See [FieldPolicy.context]. */
    private fun tellContext(
        before: () -> CharSequence? = textBeforeCursor,
        after: () -> CharSequence? = textAfterCursor,
        started: Boolean = false,
        selected: Boolean = selection.latest.isNotEmpty(),
    ) {
        contextReads.ask()
        val context = fieldPolicy.told(SystemClock.elapsedRealtime(), before, readOnce(after), started, selected) ?: return
        val learning = !fieldPolicy.sensitive
        postFcitxJob { tell(context, learning) }
    }

    private fun readOnce(read: () -> CharSequence?): () -> CharSequence? {
        var asked = false
        var text: CharSequence? = null
        return {
            if (!asked) {
                asked = true
                text = read()
            }
            text
        }
    }

    /**
     * Read once fcitx gets to it, so of a burst (held arrow or Backspace) only the last waits on the
     * editor; still ahead of any key queued after the report.
     */
    private fun tellContextLater() {
        val read = contextReads.ask()
        postFcitxJob {
            val told = withContext(Dispatchers.Main) {
                if (!contextReads.isLatest(read)) null
                else {
                    val selected = selection.latest.isNotEmpty()
                    fieldPolicy.told(SystemClock.elapsedRealtime(), textBeforeCursor, readOnce(textAfterCursor), selected = selected)
                        ?.let { it to !fieldPolicy.sensitive }
                }
            }
            if (told != null) tell(told.first, told.second)
        }
    }

    private fun updateComposingText(text: FormattedText) {
        // an empty preedit (a reset before voice's commit) leaves voice's text for the commit to replace
        if (text.isEmpty() && editingSession.kind != EditingSession.Kind.TEXT) return
        if (editingSession.codeInField) {
            editingSession.leaveComposition()
            syncCodePlace()
        }
        editingSession.updateComposingText(text) { it.toSpannedString(highlightColor) }
    }

    val voiceComposes get() = editingSession.composingText.isEmpty() && !inPasswordField &&
        currentInputEditorInfo?.isTypeNull() == false

    val voiceOwnsComposing get() = editingSession.composingText.isEmpty() || editingSession.provisional

    fun sameField(said: EditorInfo?, now: EditorInfo? = currentInputEditorInfo): Boolean = said != null && now != null &&
        VoiceCommit.sameField(said.fieldIdentity(), now.fieldIdentity(), said === now)


    fun watchVoice(taken: ((how: EditingSession.Taken) -> Unit)?) {
        editingSession.provisionalTaken = taken
    }

    /** One batch, so the app sees one change, not the tail gone for a moment. */
    fun voiceHeard(type: String, composing: String) = fieldEditor.batchEdit {
        if (type.isNotEmpty()) commitVoice(type)
        composeVoice(composing)
    }

    /** The text itself, as English's word is (specs/pitfalls.md); its commit through fcitx replaces it. */
    fun composeVoice(text: String) {
        if (editingSession.composingText.isNotEmpty() && !editingSession.provisional) return
        editingSession.updateComposingText(
            if (text.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(text), intArrayOf(0), -1),
            provisional = true,
        )
    }

    /**
     * Not through fcitx: posted, it would land after the tail composed next or after the connection
     * went. An engine's preedit is finished in place first.
     */
    fun commitVoice(text: String) {
        if (editingSession.codeInField) {
            editingSession.leaveComposition(fallBack = true)
        } else if (editingSession.composing.isNotEmpty() && !editingSession.provisional) {
            finishComposing()
        }
        voiceAsk.voice(voiceAsk.talkNow) { commitText(text) }
    }

    fun finishComposing() = editingSession.finishComposition()

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inputDeviceMgr.isVirtualKeyboard) return null
        return InlineSuggestionsUi.request(this, ThemeManager.activeTheme)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceMgr.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        editingSession.commits.forget()
        decorLocationUpdated = false
        inputViewStarted = false
        inputDeviceMgr.onFinishInputView()
        inputView?.finishInput(finishingInput)
        currentInputConnection?.apply {
            // voice's tail is taken away, not left as text: its talk types or drops it. The field's own
            // composing region is let go too, whatever the session holds
            if (editingSession.kind == EditingSession.Kind.TEXT) finishComposingText() else editingSession.leaveComposition()
            monitorCursorAnchor(false)
        }
        editingSession.resetComposingState()
        syncCodePlace()
        postFcitxJob { focusOutIn() }
        contextDropped = true
        hideStatusIcon()
        showingDialog?.dismiss()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        // a talk with the keyboard already hidden: what was heard typed now, while the connection is that field's
        inputView?.hold?.inputEnded(BaseInputView.Hold.InputEnd.LEFT)
        // before the connection goes (the framework would keep it as text)
        if (editingSession.provisional) editingSession.updateComposingText(FormattedText.Empty)
        if (editingSession.codeInField) editingSession.leaveComposition()
        codeInField.finishInput()
        syncCodePlace()
        postFcitxJob {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
        startedWith = null
        clearedText.forget()
        // the same field's touch keyboard put away finishes no input
        cursorField = null
        lastPosted.forget()
        contextReads.forget()
        fieldPolicy.finish()
        settleWaiters.drop()
        voiceAsk.reset()
        postFcitxJob { voiceEditsEnd() }
    }

    override fun onUnbindInput() {
        keyEventRelay.clear()
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxJob {
            deactivate(uid)
        }
    }

    override fun onDestroy() {
        if (EngineNotices.sayer === engineSayer) EngineNotices.sayer = null
        kept.cancel()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputDeviceListener)
        watchedPrefs.forEach { (pref, listener) -> pref.unregisterOnChangeListener(listener) }
        keyboardStylePrefs.forEach { it.unregisterOnChangeListener(keyboardStyleListener) }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        InputFeedbacks.releaseSounds()
        // learned as the service goes, not at a hide: the same field may come back. A killed process loses it
        addressLearning.end(AddressStore.forgotten)?.let(AddressStore::learn)
        settleWaiters.drop()
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        HostDaemon.disconnect(javaClass.name)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    @Suppress("ConstPropertyName")
    companion object {
        private const val LINE_REACH = 256

        const val DefaultHighlightColor = 0x008577  // material_deep_teal_500
        const val ContextChars = Action.Context.HOST_CHARS
        const val CALCULATION_CHARS = 128
    }
}

// every flag passed: each defaults to false at engineContext, so one left out is silently off
internal suspend fun HostApi.tell(c: Action.Context, learning: Boolean) =
    engineContext(
        c.before, learning, cleared = c.cleared, learnFlags = c.learnFlags ?: 0, started = c.started,
        after = c.after, selected = c.selected, said = c.said,
    )

internal fun EditorInfo.fieldIdentity() =
    VoiceCommit.Field(packageName, fieldId, inputType, fieldName, hintText?.toString())
