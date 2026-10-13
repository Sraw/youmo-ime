/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.BeginSelectionSwipeAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val inputView by manager.inputView()
    private val popup: PopupComponent by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private val backspaceSwipe = BackspaceSwipeBehavior()

    /**
     * What is typed put in first ([FcitxInputMethodService.commitAndReset]), then [then]: whether
     * the field is an address's read now, as the key is pressed, not on fcitx's thread.
     */
    private fun afterPendingInput(then: suspend FcitxAPI.() -> Unit) {
        val address = service.inAddressField
        service.postFcitxJob {
            with(service) { commitAndReset(address) }
            then()
        }
    }

    // [asIs] is asked on fcitx's side too: whether the field is an address's is read before, as the key is pressed
    private fun typeOrSend(asIs: (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String?, sent: suspend FcitxAPI.() -> Unit) =
        typeAsIsOrSend(asIs, { service.postFcitxJob(it) }, { service.commitOverEngine(it) }, sent)

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    val listener: KeyActionListener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                // as the address keys are: through the engine a digit would pick a candidate, _ be ——
                is FcitxKeyAction -> {
                    val address = service.inAddressField
                    typeOrSend(keyAsIs(action.act, address, EngineBridge::shuangpinScheme)) {
                        sendKey(action.act, action.states.states, action.code)
                    }
                }
                // a keypad's too: through the engine KP_1 is 1, which picks a candidate
                is SymAction -> {
                    val address = service.inAddressField
                    typeOrSend(symAsIs(action.sym.sym, address, EngineBridge::shuangpinScheme)) {
                        sendKey(action.sym, action.states)
                    }
                }
                is CommitAction -> service.commitOverEngine(action.text, action.replacing)
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
                                    enumerateIme()
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
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        // hold to talk: the keyboard stays, the key's finger followed till it lifts
                        SpaceLongPressBehavior.Voice -> if (behavior.talks(VoiceFeature.AVAILABLE, service.inPasswordField)) {
                            VoiceFeature.hold(inputView, popup.root, action.pointer) { text ->
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
 * (Engines.readsAsKey: the nine keys' digits, [scheme]'s ;, pinyin's ' and a table's keys while something is
 * typed, [composing], [tableKeys]) is still a key there. [im] and [composing] are asked only for a character
 * that may be such a key, [tableKeys] (FcitxAPI.tableKeys, read on fcitx's thread) only while something is typed
 * (and, in [typeAsIsOrSend], only for a table input method).
 */
@Suppress("LongParameterList")
internal fun typedAsIs(key: String, address: Boolean, im: () -> String, scheme: ShuangpinScheme, composing: () -> Boolean = { false }, tableKeys: () -> Set<Char> = { emptySet() }): String? =
    takenAsIs(key, address) { c -> Engines.readsAsKey(im(), scheme, c, composing(), tableKeys) }

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

// no input method: Engines.readsAsKey has it read no character as a key
private const val NO_INPUT_METHOD = ""

/**
 * A key's character, if [asIs] (told the input method, whether something is typed and the table's keys, as
 * [typedAsIs]) has an address field take it as it is, [commit]ted over the engine, else [sent] to it, in a job
 * [post]ed to fcitx. [asIs] is told in that job, every key before it handled: told as the key was pressed, a `'`
 * swiped while a space's commit was still queued went to the engine with nothing typed, and was ‘ in a URL. The
 * table's keys are the engine's, asked of fcitx there ([FcitxAPI.tableKeys]) for a table input method only
 * ([Engines.hasTableKeys]): the job itself runs off fcitx's thread, and the engine's sessions are that thread's
 * alone. A character read as a key there is sent, and its commit, posted after the job, cancelled before it starts.
 */
internal fun typeAsIsOrSend(
    asIs: (im: () -> String, composing: () -> Boolean, tableKeys: () -> Set<Char>) -> String?,
    post: (suspend FcitxAPI.() -> Unit) -> Unit,
    commit: (String) -> Job,
    sent: suspend FcitxAPI.() -> Unit,
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
        val keys = if (composing && Engines.hasTableKeys(im)) tableKeys(im) else emptySet<Char>()
        if (asIs({ im }, { composing }, { keys }) == null) {
            committing.await().cancel()
            sent()
        }
    }
    committing.complete(commit(text))
}

private fun takenAsIs(key: String, address: Boolean, reads: (Int) -> Boolean): String? =
    key.takeIf { PendingInputPolicy.typesAsIs(it, address, reads) }
