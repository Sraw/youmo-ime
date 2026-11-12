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
import org.fcitx.fcitx5.android.utils.switchToNextIME
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
        KeyShortcutRunner(service, windowManager, bar::tradSimp, bar::chooseKeyboard, { voiceTap() }) { !preeditState.isEmpty }
    }

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private val backspaceSwipe = BackspaceSwipeBehavior()

    private val swipeSelection = SwipeSelection()

    // the next input method: each on its own keyboard, the nine keys' English one of English's (I1)
    private suspend fun HostApi.nextInputMethod() = enumerateIme()

    /**
     * What is typed put in first ([FcitxInputMethodService.commitAndReset]), then [then]: whether
     * the field is an address's read now, as the key is pressed, not on fcitx's thread.
     */
    private fun afterPendingInput(then: suspend HostApi.() -> Unit) {
        val address = service.inAddressField
        service.postFcitxJob {
            with(service) { commitAndReset(address) }
            then()
        }
    }

    // [asIs] is asked on fcitx's side too: whether the field is an address's is read before, as the key is pressed
    private fun typeOrSend(asIs: (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String?, sent: suspend HostApi.() -> Unit) =
        typeAsIsOrSend(asIs, { service.postFcitxJob(it) }, { service.commitOverEngine(it) }, sent)

    /**
     * Tap to talk, started or stopped: the bar's microphone or a key's shortcut. What is said goes
     * in as the emoji's does (a pinyin being typed committed first, not overwritten).
     */
    fun voiceTap() = VoiceFeature.tap(inputView) { listener.onKeyAction(CommitAction(it), KeyActionListener.Source.Keyboard) }

    private fun select(range: CursorRange) {
        lineReversed = false
        service.select(range.start, range.end)
    }

    // the editor's selection as a line of 滑选's left it, its free end the left one: from the anchor to it, not in order
    private var lineReversed = false

    // the field 滑选 began in: a step in another (the field changed mid-touch) ends it. The same field
    // restarted (an app's restartInput, after a commit maybe) is the same: its EditorInfo alone is new
    private var swipeSelectField: EditorInfo? = null

    private fun inSwipeSelectField() = service.sameField(swipeSelectField, service.startedField)

    // 滑选's touch over, [lifted] (the steps before it began still taken) or not: a selection it set
    // past the field's known end that the editor never took no longer predicted, in its field
    private fun endSwipeSelect(lifted: Boolean = false) {
        val reported = service.reportedSelection
        val settled = service.selectionSettled
        val untaken = if (lifted) swipeSelection.end(reported, settled) else swipeSelection.cancel(reported, settled)
        if (swipeSelection.awaitingLines) linesAfterTouch()
        if (!inSwipeSelectField()) return
        untaken?.let(service::forgetSelection)
        // a line left the editor's selection the other way round (its free end the left one): in order, as any other is
        // (a line past the anchor left it in order already)
        val reversed = lineReversed && swipeSelection.headAt < swipeSelection.anchorAt
        if (untaken == null && reversed && !swipeSelection.lining) select(CursorRange(swipeSelection.headAt, swipeSelection.anchorAt))
    }

    private val linesTimeout by lazy { Handler(Looper.getMainLooper()) }

    // 滑选's lines on their way: each report of the editor's not predicted is one landing, the free end
    // where it says; none a while on (an editor that ignores shift and an arrow), the steps waiting go on,
    // a line the editor reports later still where the free end is. The editor's selection a line leaves
    // goes from the anchor to the free end: the other way round where that is the left one
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

    // the touch over with lines not reported (an editor that ignores shift and an arrow never does): waited for
    // a while more, then none: a report from then on the editor's own, not a line to set in order
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

    // 滑选 begun once the editor reported what was committed first: below Android 12 the field's read
    // has no place of its own, only the tracked one, true once all predicted is reported. Not reported
    // a while on (an editor that never does), begun by UTF-16 units
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

    val listener: KeyActionListener by lazy {
        KeyActionListener { action, _ ->
            // another key acting (a second finger) ends a 滑选 under way: its steps no longer select
            if (action !is BeginSwipeSelectAction && action !is SwipeSelectAction) endSwipeSelect(lifted = action is EndSwipeSelectAction)
            when (action) {
                // as the address keys are: through the engine a digit would pick a candidate, _ be ——
                is HostKeyAction -> {
                    val address = service.inAddressField
                    val n = EngineState.keySent()
                    typeOrSend(keyAsIs(action.act, address, EngineState::shuangpinScheme)) {
                        EngineState.keyTaken(n)
                        sendKey(action.act, action.states.states, action.code)
                    }
                }
                // a keypad's too: through the engine KP_1 is 1, which picks a candidate
                is SymAction -> {
                    val address = service.inAddressField
                    val n = EngineState.keySent()
                    typeOrSend(symAsIs(action.sym.sym, address, EngineState::shuangpinScheme)) {
                        EngineState.keyTaken(n)
                        sendKey(action.sym, action.states)
                    }
                }
                is CommitAction -> service.commitOverEngine(action.text, action.replacing)
                // the engine sends the key that commits its word, carrying the glide, itself (HostApi.offerGlide)
                is GlideAction -> {
                    val n = EngineState.keySent()
                    service.postFcitxJob {
                        EngineState.keyTaken(n)
                        offerGlide(action.glide)
                    }
                }
                // the field's own commands after what is typed is in, on the main thread where its editor is
                is ShortcutAction -> when ((action.shortcut as? KeyShortcut.Command)?.command) {
                    // 清除 drops what is typed, committing nothing; 恢复 leaves it be. The field's then, as
                    // input may move on to another before the host answers
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
                    // only a swipe's, which selects as it moves (BeginSwipeSelectAction): a tap's is nothing
                    KeyCommand.SwipeSelect -> {}
                    else -> afterPendingInput {
                        ContextCompat.getMainExecutor(context).execute { shortcutRunner.run(action.shortcut) }
                    }
                }
                is QuickPhraseAction -> afterPendingInput { triggerQuickPhrase() }
                is UnicodeAction -> afterPendingInput { triggerUnicode() }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    nextInputMethod()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is BeginSelectionSwipeAction -> backspaceSwipe.onTouchDown()
                is MoveSelectionAction -> {
                    // something typed: candidates with nothing typed are the words that may come
                    // next (pinyin's 联想, English's), which the swipe deletes before as ever
                    val composing = !preeditState.isEmpty
                    when (backspaceSwipe.onMove(composing)) {
                        BackspaceSwipeBehavior.MoveEffect.ExtendSelection ->
                            service.applySelectionOffset(action.start, action.end)
                        BackspaceSwipeBehavior.MoveEffect.None -> {}
                    }
                }
                // what is typed in first, as for any shortcut; the steps moved meanwhile are kept till it begins
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
                // felt only where it moved: not at the field's end, nor past it waiting for the editor
                is SwipeSelectAction -> if (!inSwipeSelectField()) {
                    endSwipeSelect()
                } else {
                    val range = if (action.steps != 0) swipeSelection.move(action.steps, service.reportedSelection, service.selectionSettled) else null
                    // a step past the end the editor ignored, given up: no longer predicted, before what is set next
                    swipeSelection.untaken?.let(service::forgetSelection)
                    range?.let(::select)
                    // felt once a sample, sideways and lines together
                    var moved = range != null
                    // then the lines, as the space's slide moves the cursor: the editor moves the free end, the anchor kept
                    action.lines.forEach { line ->
                        if (!swipeSelection.lineable) return@forEach
                        // set from the anchor to the free end where that is the left one, as the editor extends its selection's end
                        // (lines already on their way left it so: set again from the free end not yet moved, it would undo them)
                        val anchored = swipeSelection.lining
                        if (swipeSelection.headAt < swipeSelection.anchorAt) lineReversed = true
                        if (service.extendLine(line, swipeSelection.anchorAt, swipeSelection.headAt, anchored)) {
                            swipeSelection.lineSent()
                            awaitLines()
                            moved = true
                        }
                    }
                    if (moved) action.moved()
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipe.onRelease(action.totalCnt)) {
                        // the engine first forgets what it committed, maybe deleted with the
                        // selection: the cursor reports of both are predicted and bring no Context
                        BackspaceSwipeBehavior.ReleaseEffect.DeleteSelection -> {
                            service.postFcitxJob { reset() }
                            service.deleteSelection()
                        }
                        BackspaceSwipeBehavior.ReleaseEffect.ResetComposition ->
                            service.postFcitxJob { reset() }
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
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            nextInputMethod()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        // hold to talk: the keyboard stays, the key's finger followed till it lifts
                        SpaceLongPressBehavior.Voice -> if (behavior.talks(PackStore.ready(PackKind.VOICE), service.inPasswordField)) {
                            VoiceFeature.hold(inputView, action.pointer) { text ->
                                listener.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

// the keypad's keys, X11's KP_Space to KP_Equal; of them KP_Multiply to KP_9, and KP_Equal, type a character
private const val KP_SPACE = 0xff80
private const val KP_MULTIPLY = 0xffaa
private const val KP_9 = 0xffb9
private const val KP_EQUAL = 0xffbd
private const val ASCII = 0x7f
private const val LATIN1_END = 0xff

/** Whether [sym] is a keypad's key: the 123 page's, or a digit swiped on a letter. */
internal fun isKeypadSym(sym: Int): Boolean = sym in KP_SPACE..KP_EQUAL

/**
 * The character [sym] types, as fcitx reads it (Key::keySymToUnicode): a keypad's own (KP_1 is 1,
 * KP_Decimal .), or a Latin-1 one, its own sym; null for a key that types none (Backspace, an
 * arrow) and past Latin-1, where the nine keys send their syllables and letters.
 */
internal fun symText(sym: Int): String? = when (sym) {
    in KP_MULTIPLY..KP_9, KP_EQUAL -> Char(sym and ASCII).toString()
    in ' '.code until ASCII, in 0xa0..LATIN1_END -> Char(sym).toString()
    else -> null
}

/**
 * [key], the character a key types, if an [address] field takes it as it is (PendingInputPolicy.typesAsIs),
 * committed over the engine; null for a key sent to it. A key the input method [im] types with
 * (InputMethodCatalog.readsAsKey: the grouped keyboards' codes, [scheme]'s ;, pinyin's ' and a table's keys while something is
 * typed, [composing], [tableKeys]) is still a key there. [im] and [composing] are asked only for a character
 * that may be such a key, [tableKeys] (HostApi.tableKeys, read on fcitx's thread) only while something is typed
 * (and, in [typeAsIsOrSend], only for a table input method).
 */
@Suppress("LongParameterList")
internal fun typedAsIs(key: String, address: Boolean, im: () -> String, scheme: ShuangpinScheme, composing: () -> Boolean = { false }, tableKeys: () -> Set<Char> = { emptySet() }): String? =
    takenAsIs(key, address) { c -> InputMethodCatalog.readsAsKey(im(), scheme, c, composing(), tableKeys) }

/**
 * The character [sym] types ([symText]) if an [address] field takes it as it is ([typedAsIs]); null for a key sent to the engine.
 * A [keypad][isKeypadSym]'s (the 123 page's, a letter's swipe) is no key of the input method's, which is not asked.
 */
@Suppress("LongParameterList")
internal fun symTypedAsIs(sym: Int, address: Boolean, im: () -> String, scheme: ShuangpinScheme, composing: () -> Boolean = { false }, tableKeys: () -> Set<Char> = { emptySet() }): String? =
    symText(sym)?.let { if (isKeypadSym(sym)) takenAsIs(it, address) { false } else typedAsIs(it, address, im, scheme, composing, tableKeys) }

/**
 * The listener's `asIs` ([typeAsIsOrSend]) for a key typing [key]: [typedAsIs], told all [typeAsIsOrSend] tells, the 双拼
 * [scheme] read as it is asked. Its tests' too, so a pass-through dropped, or the engine asked here, fails them.
 */
internal fun keyAsIs(key: String, address: Boolean, scheme: () -> ShuangpinScheme): (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String? =
    { im, composing, tableKeys -> typedAsIs(key, address, im, scheme(), composing, tableKeys) }

/** [keyAsIs] for a key sending [sym]: [symTypedAsIs]. */
internal fun symAsIs(sym: Int, address: Boolean, scheme: () -> ShuangpinScheme): (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String? =
    { im, composing, tableKeys -> symTypedAsIs(sym, address, im, scheme(), composing, tableKeys) }

// no input method: InputMethodCatalog.readsAsKey has it read no character as a key
private const val NO_INPUT_METHOD = ""

/**
 * A key's character, if [asIs] (told the input method, whether something is typed and the table's keys, as
 * [typedAsIs]) has an address field take it as it is, [commit]ted over the engine, else [sent] to it, in a job
 * [post]ed to fcitx. [asIs] is told in that job, every key before it handled: told as the key was pressed, a `'`
 * swiped while a space's commit was still queued went to the engine with nothing typed, and was ‘ in a URL. The
 * table's keys are the engine's, asked of fcitx there ([HostApi.tableKeys]) for a table input method only
 * ([InputMethodCatalog.hasTableKeys]): the job itself runs off fcitx's thread, and the engine's sessions are that thread's
 * alone. A character read as a key there is sent, and its commit, posted after the job, cancelled before it starts.
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
        val composing = clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()
        // only a table's, while something is typed, as readsAsKey asks for them: another input method has none, not asked across threads
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

// 滑选's begin waiting at most for the editor's report of what was committed first
private const val SETTLE_MS = 200L

// 滑选's lines waiting at most for the editor to report where it moved the free end: one that ignores them never does
private const val LINES_WAIT_MS = 300L
