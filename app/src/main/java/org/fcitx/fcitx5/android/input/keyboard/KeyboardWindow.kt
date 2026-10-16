/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    // each made when first shown: every new input view made 九键 too, which most never use
    private val makers: Map<String, () -> BaseKeyboard> = mapOf(
        TextKeyboard.Name to { TextKeyboard(context, theme) },
        T9Keyboard.Name to { T9Keyboard(context, theme, NineKeyLetters.parse(AppPrefs.getInstance().keyboard.t9Letters.getValue())) },
        NumberKeyboard.Name to { NumberKeyboard(context, theme) },
        HandwritingKeyboard.Name to {
            HandwritingKeyboard(
                context, theme,
                deliver = { reading, piece -> ink { offerInk(reading, piece) } },
                // the character done, for the engine to weigh its sentence again (性能档位 high)
                finished = { piece -> ink { inkFinished(piece) } },
            )
        },
    )

    // [tell] the engine of the pad, in order with the keys; the call sends the Ink key itself (Fcitx.offerInk)
    private fun ink(tell: suspend FcitxAPI.() -> Unit) {
        // the keyboard hidden while it was read: the input it was for is over
        if (!service.isInputViewShown) return
        service.postFcitxJob { tell() }
    }

    private val keyboards = hashMapOf<String, BaseKeyboard>()
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    // the space's badge asks what the hold to talk asks (CommonKeyActionListener), a shown password too
    private val service by manager.inputMethodService()

    private val autoCapsPref = AppPrefs.getInstance().keyboard.englishAutoCaps

    // English is typed: Shift may come on by itself at a sentence start
    private var english = false

    // a word is being composed: no sentence starts within it, and the editor is not asked
    private var composing = false

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        composing = !empty
    }

    private val currentKeyboard: BaseKeyboard?
        get() = makers[currentKeyboardName]?.let { keyboards.getOrPut(currentKeyboardName, it) }

    private val spaceKeys = SpaceKeyInputMethod()

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.inPasswordField = service.inPasswordField
            (it as? TextKeyboard)?.address = address
            spaceKeys.attach(it) {
                keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
                it.onAttach()
                it.onReturnDrawableUpdate(returnKeyDrawable.appearance)
            }
        }
    }

    /** Whether the letters are the layout shown, which take the whole width, split. */
    val lettersShown get() = currentKeyboardName == TextKeyboard.Name

    /** Told each layout shown: InputView, which puts the others at a side while the letters split. */
    var onLayoutShown: (() -> Unit)? = null

    private fun showLayout(target: String) {
        detachCurrentLayout()
        attachLayout(target)
        onLayoutShown?.invoke()
        // the letters back from the symbols, Shift off as they attach: at a sentence start, on again
        updateAutoCaps()
        if (windowManager.isAttached(this)) {
            notifyBarLayoutChanged()
        }
    }

    private val lettersLayout by lazy {
        LettersLayout(makers.keys, TextKeyboard.Name, PickerWindow.Key.Symbol.name)
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        // read now, as before: the memory is what it was when the key was pressed
        val lastSymbol = lastSymbolType
        ContextCompat.getMainExecutor(context).execute {
            val outcome = lettersLayout.switchTo(to, lastSymbol, currentKeyboardName, remember)
            outcome.remember?.let { lastSymbolType = it }
            when (val target = outcome.target) {
                LayoutSwitchPolicy.Target.Unchanged -> {}
                is LayoutSwitchPolicy.Target.Keyboard -> showLayout(target.name)
                LayoutSwitchPolicy.Target.SymbolPicker ->
                    windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    // the field's, for the letters to show what an address is made of: told each layout as it attaches
    private var address = AddressKeys.NONE

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        (keyboards[HandwritingKeyboard.Name] as? HandwritingKeyboard)?.restart()
        // the layout may stay as it is, so the one shown is told now
        currentKeyboard?.inPasswordField = service.inPasswordField
        address = AddressKeys.of(info.toEditorTraits())
        (currentKeyboard as? TextKeyboard)?.address = address
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
        // after the layout, which switchLayout posts
        ContextCompat.getMainExecutor(context).execute(::updateAutoCaps)
    }

    override fun onSelectionUpdate(start: Int, end: Int) = updateAutoCaps()

    // the cursor moved by other than the service's own commit: the nine keys' 1 starts anew
    fun cursorMoved() {
        (currentKeyboard as? T9Keyboard)?.cursorMoved()
    }

    // what the field's caps flags make of the text before the cursor: a capital where a sentence starts
    private fun updateAutoCaps() {
        val keyboard = currentKeyboard as? TextKeyboard ?: return
        if (composing || keyboard.capsLocked) return
        // null after the input is bound and before it first starts
        val inputType = service.currentInputEditorInfo?.inputType
        val wants = english && autoCapsPref.getValue() && inputType != null &&
            (service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0) != 0
        keyboard.applyAutoCaps(wants)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        spaceKeys.current = ime
        val letters = when (ime.uniqueName) {
            Engines.T9 -> T9Keyboard.Name
            Engines.HANDWRITING -> HandwritingKeyboard.Name
            else -> TextKeyboard.Name
        }
        val shown = lettersLayout.inputMethodChanged(letters, currentKeyboardName)
        // a layout shown anew is told the input method as it is attached
        if (shown != null) showLayout(shown) else currentKeyboard?.let(spaceKeys::show)
        english = ime.uniqueName == InputMethodNames.KEYBOARD
        updateAutoCaps()
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(appearance: ReturnKeyAppearance) {
        currentKeyboard?.onReturnDrawableUpdate(appearance)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}

/**
 * The input method on the layouts' space keys. A layout announces a change of it only from the name
 * it showed itself (BaseKeyboard.showInputMethodName), and the user may last have seen another
 * layout's: one being shown is first told that one, before it has a parent, as
 * View.announceForAccessibility says nothing then. The change is announced once, on the layout
 * shown, and never the input method being left, nor a window's first.
 */
internal class SpaceKeyInputMethod {

    // as onImeUpdate last told it: asking fcitx would block the main thread on the fcitx one
    var current: InputMethodEntry? = null

    // what the user last saw on a space key (NumberKeyboard has none)
    private var seen: InputMethodEntry? = null

    /** Shows [keyboard], which [adding] puts in the window, the [current] input method. */
    fun attach(keyboard: BaseKeyboard, adding: () -> Unit) {
        seen?.let(keyboard::onInputMethodUpdate)
        adding()
        show(keyboard)
    }

    /** Shows [keyboard], in the window, the [current] input method. */
    fun show(keyboard: BaseKeyboard) {
        val ime = current ?: return
        keyboard.onInputMethodUpdate(ime)
        if (keyboard !is NumberKeyboard) seen = ime
    }
}