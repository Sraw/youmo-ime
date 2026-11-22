/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.BackspaceDownAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.BeginSelectionSwipeAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.BeginSwipeSelectAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.HostKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.GlideAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShortcutAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.EndSwipeSelectAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SwipeSelectAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.pack.PackKind
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    TypedComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val inputView by manager.inputView()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()

    private val shortcutRunner by lazy {
        KeyShortcutRunner(
            service, windowManager, bar::tradSimp, bar::chooseKeyboard,
            switchInputMethod = { listener.onKeyAction(LangSwitchAction, KeyActionListener.Source.Keyboard) },
            chooseInputMethod = { listener.onKeyAction(ShowInputMethodPickerAction, KeyActionListener.Source.Keyboard) },
            voice = { voiceTap() },
        ) { !preeditState.isEmpty }
    }

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior

    private val backspaceSwipe = BackspaceSwipeBehavior()

    private val swipeSelection = SwipeSelection()

    // each on its own keyboard, the nine keys' English one of English's
    private suspend fun HostApi.nextInputMethod() = enumerateIme()

    // with one input method there is no next: the prompt to add one rather than nothing
    private suspend fun HostApi.nextInputMethodOrPrompt() {
        if (enabledIme().size < 2) {
            service.lifecycleScope.launch { service.showDialog(AddMoreInputMethodsPrompt.build(context)) }
        } else {
            nextInputMethod()
        }
    }

    /** Whether the field is an address's is read now, at key press, not on fcitx's thread. */
    private fun afterPendingInput(then: suspend HostApi.() -> Unit) {
        val address = service.inAddressField
        service.postFcitxJob {
            with(service) { commitAndReset(address) }
            then()
        }
    }

    // [asIs] is asked on fcitx's side too: whether the field is an address's is read before, as the key is pressed
    private fun typeOrSend(asIs: (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String?, sent: suspend HostApi.() -> Unit) =
        typeAsIsOrSend(asIs, { service.postFcitxJob(it) }, { service.commitOverEngine(it, typed = true) }, sent)

    /** What is said goes in as the emoji's does (a pinyin being typed committed first, not overwritten). */
    fun voiceTap() = VoiceFeature.tap(inputView) { listener.onKeyAction(CommitAction(it, voice = true), KeyActionListener.Source.Keyboard) }

    private fun select(range: CursorRange) {
        lineReversed = false
        service.select(range.start, range.end)
    }

    // a line left the free end on the left: the selection runs from the anchor to it, not in order
    private var lineReversed = false

    // the same field restarted (restartInput after a commit) is the same: only its EditorInfo is new
    private var swipeSelectField: EditorInfo? = null

    private fun inSwipeSelectField() = service.sameField(swipeSelectField, service.startedField)

    // a selection past the field's known end that the editor never took is no longer predicted
    private fun endSwipeSelect(lifted: Boolean = false) {
        val reported = service.reportedSelection
        val settled = service.selectionSettled
        val untaken = if (lifted) swipeSelection.end(reported, settled) else swipeSelection.cancel(reported, settled)
        if (swipeSelection.awaitingLines) linesAfterTouch()
        if (!inSwipeSelectField()) return
        untaken?.let(service::forgetSelection)
        // put in order, as any other is (a line past the anchor left it in order already)
        val reversed = lineReversed && swipeSelection.headAt < swipeSelection.anchorAt
        if (untaken == null && reversed && !swipeSelection.lining) select(CursorRange(swipeSelection.headAt, swipeSelection.anchorAt))
    }

    private val linesTimeout by lazy { Handler(Looper.getMainLooper()) }

    // each unpredicted report is a line landing; none a while on (an editor ignoring shift+arrow), the
    // waiting steps go on, a later report still where the free end is
    private fun awaitLines() {
        service.unpredictedSelection = { reported ->
            if (inSwipeSelectField()) {
                val range = swipeSelection.lineLanded(reported) { service.readForSwipeSelect()?.let { it.field to it.at } }
                if (range != null) select(range) else lineReversed = swipeSelection.headAt < swipeSelection.anchorAt
            }
            if (!swipeSelection.awaitingLines) linesDone()
        }
        linesTimeout.removeCallbacksAndMessages(null)
        linesTimeout.postDelayed({
            if (inSwipeSelectField()) swipeSelection.linesUnreported()?.let(::select)
            if (!swipeSelection.awaitingLines) linesDone()
        }, LINES_WAIT_MS)
    }

    // waited a while more, then a report is the editor's own, not a line to set in order
    private fun linesAfterTouch() {
        linesTimeout.removeCallbacksAndMessages(null)
        linesTimeout.postDelayed({
            if (inSwipeSelectField()) swipeSelection.linesUnreported()?.let(::select)
            swipeSelection.linesForgotten()
            linesDone()
        }, LINES_WAIT_MS)
    }

    private fun linesDone() {
        service.unpredictedSelection = null
        linesTimeout.removeCallbacksAndMessages(null)
    }

    // below Android 12 the field's read has only the tracked place, true once all predicted is reported.
    // Not reported a while on, begun by UTF-16 units
    private fun beginSwipeSelect(touch: Int) = service.whenSelectionSettled(SETTLE_MS) {
        if (inSwipeSelectField()) {
            val read = service.readForSwipeSelect()
            swipeSelection.begin(touch, service.currentInputSelection, read?.field, read?.at ?: -1)?.let(::select)
        }
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    var pet: ((KeyAction) -> Unit)? = null
    val listener: KeyActionListener by lazy {
        KeyActionListener { action, source ->
            pet?.invoke(action)
            // another key acting (a second finger) ends a 滑选 under way
            if (action !is BeginSwipeSelectAction && action !is SwipeSelectAction) endSwipeSelect(lifted = action is EndSwipeSelectAction)
            when (action) {
                // through the engine a digit would pick a candidate, _ be ——
                is HostKeyAction -> {
                    val address = service.inAddressField
                    typeOrSend(keyAsIs(action.act, address, EngineState::shuangpinScheme)) {
                        sendKey(action.act, action.states.states, action.code, touch = action.touch)
                    }
                }
                // a keypad's too: through the engine KP_1 is 1, which picks a candidate
                is SymAction -> {
                    val address = service.inAddressField
                    val hold = action.hold
                    typeOrSend(symAsIs(action.sym.sym, address, EngineState::shuangpinScheme)) {
                        // not sent once what was typed as the hold began is gone (BackspaceHold)
                        if (hold == null || hold.repeat(composingCached)) {
                            sendKey(action.sym, action.states, touch = action.touch)
                        }
                    }
                }
                // a hold stops once what is typed now is deleted (BackspaceHold)
                is BackspaceDownAction -> service.postFcitxJob { action.hold.press(composingCached) }
                is CommitAction -> service.commitOverEngine(action.text, action.replacing, voice = action.voice, typed = true)
                // the engine sends the key that commits its word, carrying the glide, itself (HostApi.offerGlide)
                is GlideAction -> service.postFcitxJob { offerGlide(action.glide) }
                // on the main thread where the field's editor is
                is ShortcutAction -> when (val command = (action.shortcut as? KeyShortcut.Command)?.command) {
                    // 清除 drops what is typed, committing nothing; 恢复 leaves it be. The field is read now: input may
                    // move on before the host answers
                    KeyCommand.Clear -> {
                        val field = service.startedField
                        service.postFcitxJob {
                            val typing = clearTyped(KeyShortcutRunner::keyboardOf)
                            ContextCompat.getMainExecutor(context).execute { shortcutRunner.clear(typing, field) }
                        }
                    }
                    KeyCommand.Restore -> shortcutRunner.restore()
                    // as 符's long press: on the host's thread straight after, no key let in between
                    KeyCommand.QuickInput -> afterPendingInput { triggerQuickPhrase() }
                    // only a swipe's selects (BeginSwipeSelectAction): a tap's is nothing
                    KeyCommand.SwipeSelect -> {}
                    // as 🌐: what is typed left to the host's switch, not committed first
                    KeyCommand.SwitchInputMethod, KeyCommand.ChooseInputMethod -> shortcutRunner.run(action.shortcut)
                    // the engine's stroke key whatever 在键上划 is; it does nothing with nothing typed
                    else -> when (val stroke = command?.stroke) {
                        null -> afterPendingInput {
                            ContextCompat.getMainExecutor(context).execute { shortcutRunner.run(action.shortcut) }
                        }
                        else -> listener.onKeyAction(SymAction.stroke(stroke), source)
                    }
                }
                is QuickPhraseAction -> afterPendingInput { triggerQuickPhrase() }
                is UnicodeAction -> afterPendingInput { triggerUnicode() }
                is LangSwitchAction -> service.postFcitxJob { nextInputMethodOrPrompt() }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is KeyAction.SwitchInputMethodAction -> service.postFcitxJob { switchIme(action.uniqueName) }
                is BeginSelectionSwipeAction -> backspaceSwipe.onTouchDown()
                is MoveSelectionAction -> {
                    // candidates with nothing typed are predictions, which the swipe deletes before as ever
                    val composing = !preeditState.isEmpty
                    when (backspaceSwipe.onMove(composing)) {
                        BackspaceSwipeBehavior.MoveEffect.ExtendSelection ->
                            service.applySelectionOffset(action.start, action.end)
                        BackspaceSwipeBehavior.MoveEffect.None -> {}
                    }
                }
                // what is typed in first; the steps moved meanwhile are kept till it begins
                is BeginSwipeSelectAction -> {
                    endSwipeSelect()
                    swipeSelectField = service.startedField
                    val touch = swipeSelection.reached()
                    afterPendingInput {
                        ContextCompat.getMainExecutor(context).execute { beginSwipeSelect(touch) }
                    }
                }
                // ended above, as by any other key action
                is EndSwipeSelectAction -> {}
                is SwipeSelectAction -> if (!inSwipeSelectField()) {
                    endSwipeSelect()
                } else {
                    val range = if (action.steps != 0) swipeSelection.move(action.steps, service.reportedSelection, service.selectionSettled) else null
                    // a step past the end the editor ignored: no longer predicted
                    swipeSelection.untaken?.let(service::forgetSelection)
                    range?.let(::select)
                    // the editor moves the free end, the anchor kept
                    action.lines.forEach { line ->
                        if (!swipeSelection.lineable) return@forEach
                        // lines already on their way left it so: set again from the unmoved free end, it would undo them
                        val anchored = swipeSelection.lining
                        if (swipeSelection.headAt < swipeSelection.anchorAt) lineReversed = true
                        if (service.extendLine(line, swipeSelection.anchorAt, swipeSelection.headAt, anchored)) {
                            swipeSelection.lineSent()
                            awaitLines()
                        }
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipe.onRelease(action.totalCnt)) {
                        // the engine first forgets what it committed, maybe deleted with the selection: the cursor reports of
                        // both are predicted and bring no Context
                        BackspaceSwipeBehavior.ReleaseEffect.DeleteSelection -> {
                            service.postFcitxJob { reset(drop = true) }
                            service.deleteSelection()
                        }
                        BackspaceSwipeBehavior.ReleaseEffect.ResetComposition ->
                            service.postFcitxJob { reset(drop = true) }
                        BackspaceSwipeBehavior.ReleaseEffect.None -> {}
                    }
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(context).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (val behavior = spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob { nextInputMethodOrPrompt() }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        // hold to talk: the keyboard stays, the key's finger followed till it lifts
                        SpaceLongPressBehavior.Voice -> if (behavior.talks(PackStore.ready(PackKind.VOICE), service.inPasswordField)) {
                            VoiceFeature.hold(inputView, action.pointer) { text ->
                                listener.onKeyAction(KeyAction.CommitAction(text, voice = true), KeyActionListener.Source.Keyboard)
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

// X11's KP_Space to KP_Equal; KP_Multiply to KP_9, and KP_Equal, type a character
private const val KP_SPACE = 0xff80
private const val KP_MULTIPLY = 0xffaa
private const val KP_9 = 0xffb9
private const val KP_EQUAL = 0xffbd
private const val ASCII = 0x7f
private const val LATIN1_END = 0xff

/** The 123 page's, or a digit swiped on a letter. */
internal fun isKeypadSym(sym: Int): Boolean = sym in KP_SPACE..KP_EQUAL

/**
 * As fcitx reads it (Key::keySymToUnicode): a keypad's own (KP_1 is 1) or a Latin-1 one; null for a key
 * that types none and past Latin-1, where the nine keys send their syllables and letters.
 */
internal fun symText(sym: Int): String? = when (sym) {
    in KP_MULTIPLY..KP_9, KP_EQUAL -> Char(sym and ASCII).toString()
    in ' '.code until ASCII, in 0xa0..LATIN1_END -> Char(sym).toString()
    else -> null
}

/**
 * Null for a key sent to the engine. A key the input method types with (InputMethodCatalog.readsAsKey:
 * grouped codes, [scheme]'s ;, pinyin's ' and a table's keys while [composing]) is still a key there.
 * The lambdas are asked only when they may matter.
 */
@Suppress("LongParameterList")
internal fun typedAsIs(key: String, address: Boolean, im: () -> String, scheme: ShuangpinScheme, composing: () -> Boolean = { false }, tableKeys: () -> Set<Char> = { emptySet() }): String? =
    takenAsIs(key, address) { c -> InputMethodCatalog.readsAsKey(im(), scheme, c, composing(), tableKeys) }

/** A keypad's (the 123 page's, a letter's swipe) is no key of the input method's, which is not asked. */
@Suppress("LongParameterList")
internal fun symTypedAsIs(sym: Int, address: Boolean, im: () -> String, scheme: ShuangpinScheme, composing: () -> Boolean = { false }, tableKeys: () -> Set<Char> = { emptySet() }): String? =
    symText(sym)?.let { if (isKeypadSym(sym)) takenAsIs(it, address) { false } else typedAsIs(it, address, im, scheme, composing, tableKeys) }

/** Its tests use it too, so a dropped pass-through, or the engine asked here, fails them. */
internal fun keyAsIs(key: String, address: Boolean, scheme: () -> ShuangpinScheme): (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String? =
    { im, composing, tableKeys -> typedAsIs(key, address, im, scheme(), composing, tableKeys) }

internal fun symAsIs(sym: Int, address: Boolean, scheme: () -> ShuangpinScheme): (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String? =
    { im, composing, tableKeys -> symTypedAsIs(sym, address, im, scheme(), composing, tableKeys) }

// InputMethodCatalog.readsAsKey reads no character as a key for it
private const val NO_INPUT_METHOD = ""

/**
 * [asIs] is told inside the fcitx job, after every earlier key: told at key press, a `'` swiped while a
 * space's commit was queued went to the engine with nothing typed, becoming ‘ in a URL. Table keys are
 * asked of fcitx there ([HostApi.tableKeys]) for a table input method only: the engine's sessions are
 * fcitx's thread's alone. A character read as a key is sent, and its posted commit cancelled.
 */
internal fun typeAsIsOrSend(
    asIs: (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String?,
    post: (suspend HostApi.() -> Unit) -> Unit,
    commit: (String) -> Job,
    sent: suspend HostApi.() -> Unit,
) {
    // as no input method would read it with nothing typed: null is a key whatever fcitx has
    val text = asIs({ NO_INPUT_METHOD }, { false }, { emptySet() })
    if (text == null) {
        post(sent)
        return
    }
    val committing = CompletableDeferred<Job>()
    post {
        val im = inputMethodEntryCached.uniqueName
        // as commitAndReset reads it
        val composing = composingCached
        // only a table's, while something is typed: another input method has none, not asked across threads
        val keys = if (composing && InputMethodCatalog.hasTableKeys(im)) tableKeys(im) else emptySet<Char>()
        if (asIs({ im }, { composing }, { keys }) == null) {
            committing.await().cancel()
            sent()
        }
    }
    committing.complete(commit(text))
}

private fun takenAsIs(key: String, address: Boolean, reads: (Int) -> Boolean): String? =
    key.takeIf { PendingInputPolicy.typesAsIs(it, address, reads) }

// at most, for the editor's report of what was committed first
private const val SETTLE_MS = 200L

// at most, for the editor to report the free end: one that ignores the lines never does
private const val LINES_WAIT_MS = 300L
