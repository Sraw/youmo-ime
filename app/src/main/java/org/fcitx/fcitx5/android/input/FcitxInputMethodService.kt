/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
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
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.core.plus
import org.fcitx.fcitx5.android.core.toSpannedString
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.bar.Calculation
import org.fcitx.fcitx5.android.input.bar.CalculationOffer
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.editing.ArrowKeys
import org.fcitx.fcitx5.android.input.editing.CodePoints
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.EditingSession
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy
import org.fcitx.fcitx5.android.input.editing.ForwardedKeys
import org.fcitx.fcitx5.android.input.editing.InputConnectionEditor
import org.fcitx.fcitx5.android.input.editing.KeyEventRelay
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.fcitx.fcitx5.android.input.hardware.TakenKey
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import timber.log.Timber

class FcitxInputMethodService : LifecycleInputMethodService() {

    private lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val keyEventRelay = KeyEventRelay { currentInputConnection }

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()

    // a keyboard unplugged or switched off: the configuration is no help, on a device that reports a
    // keyboard built in it stays the same (the emulator's)
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
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
        // typed on in a field the touch keyboard was up in: not the cursor of the last field typed on;
        // the same field's (the touch keyboard called up there and put away) keeps its drag (F1793)
        if (!isVirtualKeyboard && cursorField !== startedWith) forgetCursor()
        val monitoring = currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard) == true
        // typed on in a field the touch keyboard was up in: one that tells no cursor has the corner,
        // as onStartInputView gives it, not the anchor of a field before
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
            // back in a field started on the hardware keyboard (its button, a tap, the keyboard
            // unplugged): its layout, Enter and password the field's, not the field's it was last up in
            if (inputViewStarted) currentInputEditorInfo?.let { inputView?.startInput(it, capabilityFlags) }
            hardwareKeyboard.touchKeyboardBack()
        }
    }

    private val hardwareKeyboard = HardwareKeyboard(
        post = { postFcitxJob(it) },
        typingOnTouch = { inputDeviceMgr.isVirtualKeyboard },
        inTextField = { currentInputEditorInfo?.isTypeNull() == false },
        shortcutsOn = { switchShortcuts },
    )

    // onStartInputView's evaluation done, till onFinishInputView: the touch keyboard brought back
    // since is started above, where onStartInputView does not start it itself
    private var inputViewStarted = false

    private var capabilityFlags = CapabilityFlags.DefaultFlags

    /**
     * [id] said for a moment above the keyboard. Not a toast: one from an input method is dropped
     * while its app may not post notifications, Android 13's default ("Suppressing toast … by user
     * request"); one from no keyboard shown, as the settings' toasts are, still is.
     */
    fun say(@StringRes id: Int) = say(getString(id))

    /**
     * [message] said as [say] says, with 撤销 doing [undo] if there is one; [steady], kept as keys
     * are typed and 6 s (a failure, [EngineNotices.failed]). With no keyboard shown to say it on, a
     * toast (long, steady), and nothing to take back: [undo] lapses.
     */
    fun say(message: String, undo: UndoChip.Undo? = null, steady: Boolean = false) {
        // the view shown, not the one the device mode picks: a key typed with ⋯ open flips that mode
        val view = inputView?.takeIf { it.isShown } ?: candidatesView?.takeIf { it.isShown }
        when {
            view == null -> {
                toast(message, if (steady) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
                undo?.lapsed?.invoke()
            }
            // shown, but its window not yet (or no longer) on the screen: the chip lapsed [undo] (F3530)
            !view.say(message, undo, steady) -> toast(message, if (steady) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        }
    }

    // what the engine says, said here while this keyboard runs
    private val engineSayer = EngineNotices.Sayer { message, steady -> say(message, steady = steady) }

    /** nothing said out loud goes into a password, its characters shown or not */
    val inPasswordField get() = fieldPolicy.password

    /**
     * A URL's or an email's field, where what is typed is what is meant ([PendingInputPolicy]). Read
     * on the main thread as a key is pressed: by the time fcitx gets to it, another field may have started.
     */
    val inAddressField: Boolean
        get() = currentInputEditorInfo?.let { AddressKeys.of(it.toEditorTraits()) != AddressKeys.NONE } == true

    private val editingSession = EditingSession(
        InputConnectionEditor { currentInputConnection },
        checkThread = if (BuildConfig.DEBUG) ({
            if (Looper.myLooper() != Looper.getMainLooper()) {
                // logged rather than thrown: a debug build is also someone's daily keyboard
                Timber.e(IllegalStateException("EditingSession used off the main thread"))
            }
        }) else ({})
    )

    /** The IME's view of the cursor; owned by [editingSession]. */
    private val selection get() = editingSession.selection

    // the EditorInfo input last started with, null between fields: the screen turned, input restarts
    // with it again, its selection the one the field started with (resetStateForNewConfiguration).
    // Set on the main thread, read on fcitx's too (commitOverEngine)
    @Volatile
    private var startedWith: EditorInfo? = null

    // the field the floating window's cursor and drag are of, last typed on with the hardware keyboard
    private var cursorField: EditorInfo? = null

    // what commitOverEngine put in last ([PostedCommit.committed]), forgotten with its field: the next may take its place
    private val lastPosted = LastPosted()

    val currentInputSelection: CursorRange
        get() = selection.latest

    /** The arrow keys, ahead of the editor's cursor reports. */
    private val arrowKeys = ArrowKeys(editingSession.selection)

    private fun resetComposingState() = editingSession.resetComposingState()

    private var highlightColor: Int = DefaultHighlightColor

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor
    private val autoPairs by prefs.keyboard.autoPairs
    private val switchShortcuts by prefs.candidates.switchShortcuts

    /** What was written in each app, the engine's context where a field has none of its own. */
    private val contextMemory = ContextMemory(keep = ContextChars)

    private val fieldPolicy = FieldPolicy(contextMemory)

    // onFinishInputView's focus out dropped the engine's context, and no onStartInput told it since
    private var contextDropped = false

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        // drawn on 九键's keys, and in their long press
        prefs.keyboard.t9Letters,
        prefs.keyboard.t9Symbols,
        prefs.advanced.disableAnimation,
    )

    private fun replaceInputView(theme: Theme, start: Boolean = false): InputView {
        val newInputView = InputView(this, fcitx, theme)
        setInputView(newInputView)
        inputDeviceMgr.setInputView(newInputView)
        inputView = newInputView
        // rebuilt in a field already started (floating toggled from the keyboard): its editor info,
        // as onStartInputView would give it. Not when onStartInputView follows (onCreateInputView, a
        // language picked anew): it starts the view itself, and twice would start input twice
        if (start && inputViewStarted && inputDeviceMgr.isVirtualKeyboard) {
            currentInputEditorInfo?.let { newInputView.startInput(it, capabilityFlags) }
        }
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme, start: Boolean = false) {
        navbarMgr.evaluate(window.window!!, inputDeviceMgr.isVirtualKeyboard)
        replaceInputView(theme, start)
        replaceCandidateView(theme)
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(ThemeManager.activeTheme, start = true)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(ThemeManager.activeTheme)
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        replaceInputViews(it, start = true)
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        return job
    }

    override fun onCreate() {
        EngineNotices.sayer = engineSayer
        pickedLocalesChanged()
        fcitx = FcitxDaemon.connect(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        pkgNameCache = PackageNameCache(this)
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputDeviceListener, null)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        // a fcitx not ready yet syncs them itself once it is, in the app's language as here
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fcitx.runIfReady {
                SubtypeManager.syncWith(enabledIme(), this@FcitxInputMethodService)
            }
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        // the system's, as onConfigurationChanged is given: not the app's language over it
        lastKnownConfig = Configuration(super.getResources().configuration)
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                commitTyped(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(EditorKeyPolicy.Direction.Left)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(EditorKeyPolicy.Direction.Right)
                        else -> if (it.unicode > 0) {
                            commitTyped(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // replay the original event if we still have it
                    if (keyEventRelay.replay(it.timestamp)) return@event
                    // no original event to replay: simulate one
                    when (val action = ForwardedKeys.decide(it.sym.keyCode, it.up, it.unicode)) {
                        is ForwardedKeys.Action.SendKey -> {
                            val eventTime = SystemClock.uptimeMillis()
                            if (action.up) {
                                sendUpKeyEvent(eventTime, action.keyCode, it.states.metaStateOf(action.keyCode, up = true))
                            } else {
                                sendDownKeyEvent(eventTime, action.keyCode, it.states.metaStateOf(action.keyCode, up = false))
                            }
                        }
                        is ForwardedKeys.Action.CommitChar -> commitText(action.text)
                        // not the event: its sym and unicode are the character, a password's too
                        ForwardedKeys.Action.Ignore -> Timber.d("Unhandled Fcitx KeyEvent: no key code, up=${it.up}")
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                updateComposingText(event.data)
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                val im = event.data.uniqueName
                hardwareKeyboard.inputMethodChanged(im)
                // the session switched to knows nothing of the field yet: told it, after fcitx's reset of it (FieldPolicy)
                if (fieldPolicy.inputMethodChanged(im)) tellContext(started = true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // (one not synced yet has no subtype: the status icon below is still updated)
                    val subtype = SubtypeManager.subtypeOf(im)
                    val had = inputMethodManager.currentInputMethodSubtype?.let(SubtypeManager::inputMethodOf)
                    // [^1]: notify system that input method subtype has changed
                    if (subtype != null && subtypeEchoes.shouldTell(im, had)) tellSubtype(im, subtype)
                }
                if (inputDeviceMgr.evaluateOnInputMethodActivate()) {
                    showStatusIcon(StatusIconMapping.fromEntry(event.data))
                }
            }
            is FcitxEvent.SwitchInputMethodEvent -> {
                val (reason) = event.data
                if (reason != FcitxEvent.SwitchInputMethodEvent.Reason.CapabilityChanged &&
                    reason != FcitxEvent.SwitchInputMethodEvent.Reason.Other
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
        contextMemory.backspace(deleting.start, deleting.end)
        // a selection deleted, where a session takes one code point off what it knows (the app's
        // Session.ERASED, or English's own Backspace) and the cursor report, predicted, tells it
        // nothing: read now, for every input method, the selection still there, the text before
        // it is what will be before the cursor
        // (told as no selection: what it reads is the field once the selection is gone, review-1c M2)
        if (deleting.isNotEmpty()) tellContext(selected = false)
        if (pairing) backspacePair()
        if (!editingSession.backspace(currentInputEditorInfo.toEditorTraits(packageName))) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** An empty pair put in goes whole: its closing one here, the opening one as ever. */
    private fun backspacePair() {
        val quoteOpen = editingSession.pairs.quoteOpen
        editingSession.pairs.backspace()
        // an empty “” gone: fcitx's punctuation does not take its “ back on Backspace, and would
        // make the next quote a lone ”. Its context is read now, less the “: the key event sent
        // next may not have landed by a read after it
        if (quoteOpen && !editingSession.pairs.quoteOpen) {
            postFcitxJob { reset() }
            tellContext(before = {
                currentInputConnection?.getTextBeforeCursor(ContextChars + 1, 0)
                    ?.let { it.dropLast(CodePoints.lengthOfLast(it, 1) ?: 1) }
            }, selected = false)
        }
    }

    /** The text-editing panel's Backspace, a key event as ever: what it takes is not kept as context. */
    fun sendBackspaceKey() {
        val deleting = selection.latest
        contextMemory.backspace(deleting.start, deleting.end)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /**
     * The text-editing panel's menu action, done by the editor: a selection a cut or a paste
     * takes away is not kept as context any more than Backspace's.
     */
    fun performContextMenuAction(id: Int) {
        if ((id == android.R.id.cut || id == android.R.id.paste) && selection.latest.isNotEmpty()) contextMemory.cut()
        currentInputConnection?.performContextMenuAction(id)
    }

    /**
     * Brackets and quotes typed in pairs, where the user wants it -- not in a password, where
     * what is typed is all there is, nor where the editor takes key events only.
     */
    private val pairing get() = fieldPolicy.pairs(autoPairs, rawKeys = currentInputEditorInfo.isTypeNull())

    /** What the user typed, a key or the engine's commit: a bracket or quote may go in paired. */
    private fun commitTyped(text: String, cursor: Int = -1) {
        if (cursor != -1 || !pairing || !editingSession.pairs.type(text)) {
            // fcitx's punctuation may put a pair in itself, the cursor between
            val start = selection.latest.start.takeIf { editingSession.composing.isEmpty() }
            commitText(text, cursor)
            if (pairing && start != null) editingSession.pairs.committed(text, cursor, start)
            return
        }
        // paired or stepped over: either way the cursor is after [text]
        contextMemory.committed(text, now = SystemClock.elapsedRealtime())
    }

    private fun handleReturnKey() {
        when (val action = EditorKeyPolicy.onReturn(currentInputEditorInfo.toEditorTraits(packageName))) {
            EditorKeyPolicy.ReturnAction.SendEnter -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            is EditorKeyPolicy.ReturnAction.PerformEditorAction ->
                currentInputConnection.performEditorAction(action.actionId)
        }
    }

    private fun handleArrowKey(direction: EditorKeyPolicy.Direction) {
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

    fun commitText(text: String, cursor: Int = -1) {
        editingSession.commitText(text, cursor)
        contextMemory.committed(text, cursor, SystemClock.elapsedRealtime())
        if (cursor == -1 && (text.endsWith('=') || text.endsWith('＝'))) offerCalculation()
    }

    /**
     * The result of the sum the `=` just put in ends ([Calculation]), for the bar: read off the
     * field, however its digits got there. Not in a field that may not be read; the editor has the
     * commit by the time it answers, its commands taken in order.
     */
    private fun offerCalculation() {
        val worked = CalculationOffer.read(
            fieldPolicy.sensitive,
            currentInputEditorInfo.isTypeNull(),
            CALCULATION_CHARS,
        ) { currentInputConnection?.getTextBeforeCursor(it, 0) } ?: return
        inputView?.offerCalculation(worked, selection.latest.start)
    }

    /**
     * [text] put in by the app itself, not the engine (a paste, a word from a long press): what
     * the engine has typed goes in first, and the engine forgets what it committed, no longer
     * before the cursor (English would put its space behind punctuation typed next, in [text]).
     * With [replacing], [text] takes its place before the cursor: what the app put in just before
     * (九键's 1 pressed again, 。 for ，), if it is still there; the field may have refused it, or
     * the cursor moved. A password or a field that asks not to be learned from is not read to
     * know: there it is known as what the app put in last, the editor having reported all it did
     * and nothing else since ([PostedCommit.takeBack]). Not reported yet when [text] goes in,
     * [replacing] stays: 1 pressed again before the editor reported the first types both, ，。.
     * Posted, it waits for fcitx (starting, or reloading for the nine keys' letters): by then
     * another field may have the focus, or none, and nothing goes in ([PostedCommit]).
     */
    fun commitOverEngine(text: String, replacing: String = ""): Job {
        val address = inAddressField
        val asked = PostedCommit(startedWith, replacing, text)
        return postFcitxJob {
            if (!stillIn(asked, text)) return@postFcitxJob
            commitAndReset(address)
            lifecycleScope.launch {
                if (!stillIn(asked, text)) return@launch
                lastPosted.putIn(asked, postedField, selection)
            }
        }
    }

    // whether input is in the field [text] was asked to go in ([PostedCommit.lands]): else it is dropped
    private fun stillIn(asked: PostedCommit, text: String): Boolean {
        if (asked.lands(startedWith)) return true
        // its length alone: the debug log can be exported, and the text may be what the user typed
        Timber.d("commitOverEngine: the field changed or finished, ${text.length} chars dropped")
        return false
    }

    /**
     * The field as commitOverEngine puts text in it ([LastPosted.putIn]): what the app put in just
     * before is read to know, or, where the field is not to be read, as the editor's cursor report
     * tells, its units deleted unread.
     */
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

    /**
     * What is typed put in as the input method would (its first candidate, or as composed), then
     * fcitx reset. [address]: the field is one ([inAddressField]), as read when the key was pressed.
     */
    // there should be a new fcitx API for this
    suspend fun FcitxAPI.commitAndReset(address: Boolean) {
        val resolution = PendingInputPolicy.resolve(
            inputMethodEntryCached.languageCode,
            hasPreedit = clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty(),
            address = address
        )
        when (resolution) {
            PendingInputPolicy.Resolution.SelectFirstCandidate -> select(0)
            // the engine's Enter while composing: what was typed, committed
            PendingInputPolicy.Resolution.CommitAsTyped -> sendKey(FcitxKeyMapping.FcitxKey_Return)
            // This runs on fcitx's thread pool, and the composing state belongs to the main thread.
            PendingInputPolicy.Resolution.FinishComposing ->
                withContext(Dispatchers.Main) { finishComposing() }
            PendingInputPolicy.Resolution.None -> {}
        }
        reset()
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun deleteSelection() {
        editingSession.deleteSelection()
        // the engine reset first: what is left is read, for what of its commits the selection took (U-03)
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
        editingSession.applySelectionOffset(offsetStart, offsetEnd)

    fun cancelSelection() = editingSession.cancelSelection()

    private lateinit var lastKnownConfig: Configuration

    /** [Locales.picked], as the input view was last made in; null until [onCreate] (and while the
     * service is constructed, should the framework read resources that early). Volatile, as is
     * [localizedResources]: [getResources] is read off the main thread too, by a fcitx job's
     * SubtypeManager.syncWith */
    @Volatile
    private var appLocales: LocaleListCompat? = null

    @Volatile
    private var localizedResources: Resources? = null

    /** The app's and the phone's languages as [appLocales] was read with, from Android 13 on. */
    private var localesReadWith: Pair<LocaleListCompat, LocaleListCompat>? = null

    /**
     * The app's language, where the user picked one: an input method's window takes the
     * display's configuration, the phone's language, even where Android 13 gives the app its
     * own. The keyboard's views are made with this service, and read their strings here.
     */
    override fun getResources(): Resources {
        val base = super.getResources()
        val locales = appLocales
        if (locales == null || locales.isEmpty) return base
        // the locales alone over the base: the system keeps these resources up with any other
        // change (orientation, dark mode), for those that hold on to them, the input window first
        return localizedResources ?: createConfigurationContext(
            Configuration().also { ConfigurationCompat.setLocales(it, locales) }
        ).resources.also { localizedResources = it }
    }

    /** Whether the language picked for the app changed since the input view was made. */
    private fun pickedLocalesChanged(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // reading the picked ones is a binder call there; they lead the app's configuration, so
            // are read again only once it or the phone's changed
            val languages = ConfigurationCompat.getLocales(applicationContext.resources.configuration) to
                ConfigurationCompat.getLocales(Resources.getSystem().configuration)
            if (languages == localesReadWith) return false
            localesReadWith = languages
        }
        val picked = Locales.picked(this)
        if (picked == appLocales) return false
        appLocales = picked
        localizedResources = null
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        postFcitxJob { reset() }
        /**
         * skip keyboard|keyboardHidden changes, because we have [inputDeviceMgr]
         * skip uiMode (system light/dark mode) changes, because we have [onThemeChangeListener]
         * to replace InputView(s) when needed
         * [android.inputmethodservice.InputMethodService.onConfigurationChanged] would call
         * resetStateForNewConfiguration() which calls initViews() causes InputView(s) to be replaced again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        /**
         * perform `super.onConfigurationChanged` only when `newConfig` diff fall outside "skipped" flags
         * we have to calculate the mask ourselves because nobody knows how `handledConfigChanges` works
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (diff and f != diff) {
            super.onConfigurationChanged(newConfig)
        } else if (currentInputStarted && currentInputEditorInfo?.isTypeNull() == false) {
            // the reset above dropped the engine's context, and no input restarts to tell it anew
            tellContext()
        }
        lastKnownConfig = newConfig
    }

    override fun onWindowShown() {
        super.onWindowShown()
        highlightColor =
            styledColorOrDefault(android.R.attr.colorAccent, DefaultHighlightColor).alpha(0.4f)
        InputFeedbacks.syncSystemPrefs()
    }

    override fun onCreateInputView(): View? {
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    private val floatingRegion = Rect()

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard && inputView?.floatingRegion(floatingRegion) == true) {
            // the app laid out as if there were no keyboard, only the keyboard's own place taking touches
            val h = decorView.height
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(floatingRegion)
            }
        } else if (inputDeviceMgr.isVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
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

    /** [handOff]: the key may be typed by another input method ([HardwareKeyboard.mayHandOff]). */
    private fun forwardKeyEvent(event: KeyEvent, handOff: Boolean = false): Boolean {
        val sym = KeySym.fromKeyEvent(event)
        if (sym != null) {
            val timestamp = keyEventRelay.remember(event)
            val states = KeyStates.fromKeyEvent(event)
            val up = event.action == KeyEvent.ACTION_UP
            postFcitxJob {
                if (handOff) hardwareKeyboard.handOff(this)
                sendKey(sym, states, event.scanCode, up, timestamp)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    // the key whose press was taken here, its repeats and release not to go to fcitx alone
    private val keyTaken = TakenKey()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Shift+Delete on the candidate highlighted forgets it, as its long press does (U-82): taken
        // while one is highlighted, the engine's answer too late for the key: with nothing to forget it
        // does nothing, where Delete would have deleted beside a composition (review-E-ui N1)
        val forgets = keyTaken.down(keyCode, repeat = event.repeatCount > 0) {
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL && event.isShiftPressed && candidatesView?.forgetHighlighted() == true
        }
        // told even when taken: the Delete ends a lone Shift tap, else letting go of Shift soon after
        // the forget would switch 中/英 too
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
        val modified = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        val handOff = hardwareKeyboard.mayHandOff(event.unicodeChar, modified)
        return forwardKeyEvent(event, handOff) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyTaken.up(keyCode)) return true
        if (hardwareKeyboard.up(keyCode, event.eventTime)) return true
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
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
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private val subtypeEchoes = SubtypeEchoes()

    /**
     * Tells the system the subtype fcitx is on. fcitx events come at any time, also after the
     * system disabled this keyboard (in its settings, or a test restoring the device's), and the
     * system refuses the switch then: not to throw on the main looper, the refusal is logged and
     * the echo awaited for it forgotten.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tellSubtype(im: String, subtype: InputMethodSubtype) {
        try {
            switchInputMethod(InputMethodUtil.componentName, subtype)
        } catch (expected: RuntimeException) {
            // the system's refusal comes back as a plain RuntimeException, a dead system as one too;
            // named `expected` as detekt's TooGenericExceptionCaught allows a caught one so named
            Timber.w(expected, "IMChangeEvent: the system refused the switch to im=$im")
            subtypeEchoes.refused(im)
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype) ?: return
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            val echo = subtypeEchoes.isEcho(im)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im, echo=$echo")
            if (echo) return
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // the screen turned (resetStateForNewConfiguration): input restarts with the EditorInfo the
        // field first started with, its initial selection and text that start's (CursorTracker.startInput)
        val again = restarting && attribute === startedWith
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.startInput(again, attribute.initialSelStart, attribute.initialSelEnd)
        val flags = CapabilityFlags.forField(attribute, packageName)
        val sensitive = flags.hasAny(CapabilityFlag.PasswordOrSensitive)
        // input restarting (the screen turned, resetStateForNewConfiguration, or the app's own
        // restartInput) has no onFinishInputView, and the focus out below drops the engine's input: the
        // editor's preedit (English's word, handwriting's characters, the text itself) is text from now on
        if (restarting) currentInputConnection?.finishComposingText()
        // restarted, the same field: what it had of the floating window stays its
        if (restarting && cursorField === startedWith) cursorField = attribute
        startedWith = attribute
        // what commitOverEngine put in last, a paste into a password maybe, is not kept: the reset above
        // keeps it from being taken back anyway
        lastPosted.forget()
        resetComposingState()
        capabilityFlags = flags
        editingSession.pairs.forget()
        arrowKeys.forget()
        contextReads.forget()
        // nothing of a password or a field that asks not to be learned from is kept. Another field:
        // what was written before is no longer the engine's to take back (U-03)
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
            // an editor that gives no initial text may still answer the connection. The initial
            // text is the field's as it started: after a turn the first start's, so the editor is asked
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
        // the app's language picked anew in its settings: shown in it from now on
        if (pickedLocalesChanged()) {
            replaceInputViews(ThemeManager.activeTheme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                postFcitxJob { SubtypeManager.syncWith(enabledIme(), this@FcitxInputMethodService) }
            }
        }
        postFcitxJob {
            focus(true)
            // the keyboard shown again in a field: typing paused, the older build's learning put in place if made (uat-0.3.1 recheck)
            enginePaused()
        }
        // the touch keyboard coming up as this evaluates is started below, not in inputDeviceMgr's onChange
        inputViewStarted = false
        val virtual = inputDeviceMgr.evaluateOnStartInputView(info, this)
        inputViewStarted = true
        if (virtual) {
            // because onStartInputView will always be called after onStartInput,
            // editorInfo and capFlags should be up-to-date
            inputView?.startInput(info, capabilityFlags, restarting)
        } else {
            // the same field restarted (a WebView after an edit) keeps its cursor and its drag
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
        val update = editingSession.onCursorUpdate(
            newSelStart, newSelEnd, candidatesStart, candidatesEnd, ignoreSystemCursor
        )
        arrowKeys.cursorReported(newSelStart, newSelEnd)
        // the user left a “” put in without typing its ”, or stepped over its ” with a “: fcitx's
        // punctuation still counts the “ open, and would make the next quote a lone ”. Nothing
        // composed is lost (the cursor left it, finished in place), and the context comes after:
        // the update's, or told here where the update tells none (a reset drops it)
        val quoteReset = editingSession.quoteLeftOpen
        if (quoteReset) postFcitxJob { reset() }
        handleCursorUpdate(update)
        // a report not predicted, so not this service's own commit
        if (update != EditingSession.CursorUpdate.None) inputView?.cursorMoved()
        if (quoteReset && update == EditingSession.CursorUpdate.None) tellContextLater()
        inputView?.updateSelection(newSelStart, newSelEnd)
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

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    /** Passes on to fcitx what a cursor report means for the composition. */
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
            is EditingSession.CursorUpdate.MovePreeditCursor -> postFcitxJob {
                // onUpdateSelection can lag behind when the user types quickly enough, eg. long
                // press backspace; only the latest report moves the cursor
                if (update.updateIndex != editingSession.cursorUpdateIndex) return@postFcitxJob
                Timber.d("handleCursorUpdate: move fcitx cursor to ${update.codePointPosition}")
                moveCursor(update.codePointPosition)
            }
            EditingSession.CursorUpdate.FocusOutIn -> {
                postFcitxJob { focusOutIn() }
                tellContextLater()
            }
        }
    }

    private val textBeforeCursor = { currentInputConnection?.getTextBeforeCursor(ContextChars, 0) }
    // as much as a context holds: what the engine checks its commits by after the cursor (U-03), and whether anything follows it
    private val textAfterCursor = { currentInputConnection?.getTextAfterCursor(Action.Context.HOST_CHARS, 0) }

    // context reads asked for: one still queued behind a newer, or from a field since left, is not made
    private val contextReads = ContextReads()

    /**
     * What the engine's input follows, after fcitx is done with what was there: not read at all
     * in a password or other sensitive field, where the engine learns nothing either. See
     * [FieldPolicy.context].
     */
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

    // [read] asked of the editor once, however often its answer is wanted: each ask is a round trip
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
     * [tellContext] for a cursor report, read once fcitx gets to it rather than on the report, so
     * that of a burst (a held arrow or Backspace, keys typed ahead of a slow editor) the last alone
     * waits on the editor. Told in that job, it still comes before any key queued after the report.
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

    private fun updateComposingText(text: FormattedText) =
        editingSession.updateComposingText(text) { it.toSpannedString(highlightColor) }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() = editingSession.finishComposition()

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return null
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        decorLocationUpdated = false
        inputViewStarted = false
        inputDeviceMgr.onFinishInputView()
        inputView?.finishInput()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        postFcitxJob {
            focusOutIn()
            enginePaused()
        }
        contextDropped = true
        hideStatusIcon()
        showingDialog?.dismiss()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        postFcitxJob {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
        startedWith = null
        // not the finished field's EditorInfo kept; the same field's touch keyboard put away finishes no input (F1793)
        cursorField = null
        lastPosted.forget()
        // a read queued in the field, a password's maybe, is not made once the policy forgot it
        contextReads.forget()
        fieldPolicy.finish()
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
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputDeviceListener)
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
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
        const val DefaultHighlightColor = 0x008577  // material_deep_teal_500
        // what the engine reads of the text before the cursor: its last two words, and what the
        // reranker has room for
        const val ContextChars = Action.Context.HOST_CHARS
        // a sum the bar works out: longer than any typed on a phone, short to read
        const val CALCULATION_CHARS = 128
    }
}

// every flag of [c] passed on: each defaults to false at engineContext, so one left out is silently off (F3198)
internal suspend fun FcitxAPI.tell(c: Action.Context, learning: Boolean) =
    engineContext(
        c.before, learning, cleared = c.cleared, learnFlags = c.learnFlags ?: 0, started = c.started,
        after = c.after, selected = c.selected, said = c.said,
    )
