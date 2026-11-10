/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTrace
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.scene.SceneTyping
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.scene.engine.section
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
    private val punctuation: PunctuationComponent by manager.must()
    private val preedit: PreeditComponent by manager.must()

    companion object : EssentialWindow.Key {
        /**
         * The keyboard shown in place of [keyboard] while quick input lasts: its keys are typed letters,
         * which the handwriting pad has none of and the zhuyin keyboards send none of (a symbol's own
         * code, which quick input takes as no key); null where [keyboard] types them itself.
         */
        fun quickInputStandIn(keyboard: String): String? = when (keyboard) {
            HandwritingKeyboard.Name, ZhuyinKeyboard.NameNine, ZhuyinKeyboard.NameFull -> TextKeyboard.Name
            else -> null
        }

        /**
         * Whether quick input's stand-in for [was], the input method's letters then being [lettersThen],
         * gives way to another input method's [letters] at once: as [was] would, shown
         * ([LettersLayout.inputMethodChanged]). The letters themselves (the pad as handwriting's, a
         * zhuyin keyboard) are the new ones' place; the pad over another's letters stays (review Q1,
         * the user's call 2026-11-02: the same during quick input as out of it).
         */
        fun givesWay(was: String, lettersThen: String, letters: String): Boolean =
            was == lettersThen && letters != lettersThen

        /**
         * The keyboard to show as quick input ends, the full one having stood in for [was] since the
         * input method's letters were [lettersThen]: [was] again, unless it was the letters and they
         * changed meanwhile ([lettersNow]); onImeUpdate shows those at once ([givesWay]), so this is a
         * backstop. The pad over another's letters stays the pad, as it does out of quick input.
         */
        fun afterQuickInput(was: String, lettersThen: String, lettersNow: String): String =
            if (was == lettersThen) lettersNow else was
    }

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
        T9Keyboard.Name to { nineKeys(english = false) },
        T9Keyboard.EnglishName to { nineKeys(english = true) },
        // pinyin's: a theme's layout of its own groups typed as such (060-render-p3 T9b); 双拼's take the built-in groups alone
        GroupedKeyboard.Name14 to { GroupedKeyboard(context, theme, ownGroups = true) },
        GroupedKeyboard.Name18 to { GroupedKeyboard(context, theme, NineKeyLetters.G18) },
        GroupedKeyboard.Name18Plain to { GroupedKeyboard(context, theme, NineKeyLetters.G18, semicolon = false) },
        GroupedKeyboard.NamePinyin18 to { GroupedKeyboard(context, theme, NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false, ownGroups = true) },
        GroupedKeyboard.NameShuangpin14 to { GroupedKeyboard(context, theme, NineKeyLetters.G14, Keyboard::shuangpin14Key) },
        // the user's 按键字符 (its symbols) as 九键拼音 has them; its letters are 全拼's alone
        T9Keyboard.ShuangpinName to { T9Keyboard(context, theme, symbols = NineKeySymbols.parse(AppPrefs.getInstance().keyboard.t9Symbols.getValue()), shuangpin = true, semicolon = true) },
        T9Keyboard.ShuangpinPlainName to { T9Keyboard(context, theme, symbols = NineKeySymbols.parse(AppPrefs.getInstance().keyboard.t9Symbols.getValue()), shuangpin = true, semicolon = false) },
        ZhuyinKeyboard.NameNine to { ZhuyinKeyboard(context, theme, nine = true) },
        ZhuyinKeyboard.NameFull to { ZhuyinKeyboard(context, theme, nine = false) },
        NumberKeyboard.Name to { NumberKeyboard(context, theme) },
        // a number's, phone's or date's field's own (FieldPad)
        *NumberPad.entries.filter { it != NumberPad.FULL }.map { pad -> pad.layout to { NumberKeyboard(context, theme, pad) } }.toTypedArray(),
        HandwritingKeyboard.Name to {
            HandwritingKeyboard(
                context, theme,
                deliver = { reading, piece -> ink { offerInk(reading, piece) } },
                // the character done, for the engine to weigh its sentence again (性能档位 high)
                finished = { piece -> ink { inkFinished(piece) } },
                // 注: the shared setting, as its row in the settings sets it (SharedInputFragment)
                setReading = { style -> service.postFcitxJob { setImOptions(InputMethodCatalog.PINYIN, mapOf(EngineSettings.CANDIDATE_READING to style.key)) } },
            )
        },
    )

    private fun nineKeys(english: Boolean): T9Keyboard {
        val keyboard = AppPrefs.getInstance().keyboard
        return T9Keyboard(context, theme, NineKeyLetters.parse(keyboard.t9Letters.getValue()), NineKeySymbols.parse(keyboard.t9Symbols.getValue()), english, semicolon = false)
    }

    // [tell] the engine of the pad, in order with the keys; the call sends the Ink key carrying it itself (HostApi.offerInk)
    private fun ink(tell: suspend HostApi.() -> Unit) {
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

    // a temporary mode (快速输入, Unicode) is on: its panel is the only one with a line above a
    // preedit (HostCore.showPanel, a TempPanel's auxUp; the input method's name there comes with
    // nothing typed), and what is typed in it is not the input method's
    private var tempMode = false

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        composing = !empty
        keyboards[currentKeyboardName]?.imTyping = composing && !tempMode
    }

    // the keyboard the full one stands in for while quick input lasts ([quickInputStandIn]), shown again
    // after, and the input method's letters then
    private var standingInFor: String? = null
    private var lettersWhenStoodIn = ""

    override fun onInputPanelUpdate(data: HostEvent.InputPanel) {
        tempMode = data.auxUp.isNotEmpty()
        keyboards[currentKeyboardName]?.imTyping = composing && !tempMode
        val standIn = quickInputStandIn(currentKeyboardName)
        if (data.quickPhrase && standIn != null) {
            standingInFor = currentKeyboardName
            lettersWhenStoodIn = lettersLayout.letters
            showLayout(standIn)
        } else if (!data.quickPhrase) {
            val was = standingInFor ?: return
            standingInFor = null
            val back = afterQuickInput(was, lettersWhenStoodIn, lettersLayout.letters)
            if (currentKeyboardName == TextKeyboard.Name && back != TextKeyboard.Name) showLayout(back)
        }
    }

    private val currentKeyboard: BaseKeyboard?
        get() = makers[currentKeyboardName]?.let { keyboards.getOrPut(currentKeyboardName) { SceneTrace.section("make keyboard") { it() } } }

    private val spaceKeys = SpaceKeyInputMethod()

    // the last run's keys read after this keyboard was made (the service's read on its thread, slower): taken now
    private fun known(): Boolean {
        if (!punctuation.known) punctuation.update(PunctuationStartKeys.known)
        return punctuation.known
    }

    // the tap felt once while the punctuation keys are held back
    private var heldBackFelt = false

    // a punctuation key while what it types is not known (a first install, the host not up): held back, a tap felt
    // once, as no face could show what it would type (060-render-p2 S7a)
    private fun heldBack(action: KeyAction): Boolean {
        if (known()) return false
        val c = when (action) {
            is KeyAction.HostKeyAction -> action.act.singleOrNull()?.code
            is KeyAction.SymAction -> action.sym.sym
            else -> null
        } ?: return false
        if (!isAsciiMark(c)) return false
        if (!heldBackFelt) {
            heldBackFelt = true
            InputFeedbacks.hapticFeedback(keyboardView)
        }
        return true
    }

    private val keyActionListener = KeyActionListener { it, source ->
        when {
            heldBack(it) -> {}
            it is KeyAction.LayoutSwitchAction -> switchLayout(it.act)
            else -> commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        val shown = popup.listener
        // nor its bubble
        PopupActionListener { a ->
            if (a !is PopupAction.PreviewAction || known() || a.content.singleOrNull()?.code?.let(::isAsciiMark) != true) shown.onPopupAction(a)
        }
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
            (it as? T9Keyboard)?.noSuggestions = noSuggestions
            it.address = address
            it.imTyping = composing && !tempMode
            stripOwned = it.ownsSyllables
            preedit.ui.syllables.owned = stripHidden()
            spaceKeys.attach(it) {
                keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
                it.onAttach()
                it.onReturnDrawableUpdate(returnKeyDrawable.appearance)
                // the punctuation as it is now: a keyboard kept while another was shown missed what changed
                // meanwhile, and would show , where it types ， (the user's 「显示和输出一致」, 060-render-p2 S4d)
                it.onPunctuationUpdate(punctuation.mapping)
            }
        }
    }

    // whether the keyboard shown offers the syllables itself (九键's column), as it attached
    private var stripOwned = false

    // the code row's strip hidden: 九键's column offers the syllables, or the input method's keyboard
    // shows none (its code row thin); on each attach and input method change
    private fun stripHidden() = syllablesOwned(stripOwned, KawaiiBarComponent.showsStrip(spaceKeys.current?.uniqueName))

    /** Whether the letters are the layout shown, which take the whole width, split. */
    val lettersShown get() = currentKeyboardName == TextKeyboard.Name

    /** Told each layout shown: InputView, which puts the others at a side while the letters split. */
    var onLayoutShown: (() -> Unit)? = null

    // a zhuyin keyboard first shown: Taiwan's users write 繁体, 简繁 is in ⋯ (design 051 Q11 A), said once
    private fun tellZhuyinOnce(target: String) {
        if (target != ZhuyinKeyboard.NameNine && target != ZhuyinKeyboard.NameFull) return
        val told = AppPrefs.getInstance().internal.zhuyinTraditionalTold
        // already traditional (简繁 on for pinyin): nothing to tell, and it is told the first time it is not.
        // Not known yet (the host not up): nor now, it may be; told when shown once it is known
        if (told.getValue()) return
        val traditional = EngineState.traditionalIms ?: return
        if (InputMethodCatalog.PINYIN in traditional) return
        // posted: picked from ⋯, the panel closing as the layout shows would take a notice said now with it.
        // Told only once said (on the keyboard, else as a toast): a post that never runs, the view gone first, tells it next time
        keyboardView.post {
            if (told.getValue()) return@post
            told.setValue(true)
            service.say(R.string.zhuyin_traditional_hint)
        }
    }

    private fun showLayout(target: String) {
        detachCurrentLayout()
        attachLayout(target)
        onLayoutShown?.invoke()
        tellZhuyinOnce(target)
        // the letters back from the symbols, Shift off as they attach: at a sentence start, on again
        updateAutoCaps()
        if (windowManager.isAttached(this)) {
            notifyBarLayoutChanged()
        }
    }

    private val lettersLayout by lazy {
        LettersLayout(makers.keys, TextKeyboard.Name, PickerWindow.Key.Symbol.name)
    }

    // the pad of the number, phone or date field focused, shown for the number keyboard
    private val fieldPad = FieldPad()

    fun switchLayout(to: String, remember: Boolean = true) {
        // read now, as before: the memory is what it was when the key was pressed
        val lastSymbol = lastSymbolType
        ContextCompat.getMainExecutor(context).execute {
            val outcome = lettersLayout.switchTo(to, lastSymbol, fieldPad.known(currentKeyboardName), remember)
            outcome.remember?.let { lastSymbolType = it }
            when (val target = fieldPad.shown(outcome.target, currentKeyboardName)) {
                LayoutSwitchPolicy.Target.Unchanged -> {}
                is LayoutSwitchPolicy.Target.Keyboard -> showLayout(target.name)
                LayoutSwitchPolicy.Target.SymbolPicker ->
                    windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    // the field's, for the letters to show what an address is made of and no layout to glide there: told each layout as it attaches
    private var address = AddressKeys.NONE

    // the field asks for no suggestions (CapabilityFlag.NoSpellCheck), where English spells no word on the nine keys
    private var noSuggestions = false

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        standingInFor = null
        (keyboards[HandwritingKeyboard.Name] as? HandwritingKeyboard)?.restart()
        noSuggestions = capFlags.has(CapabilityFlag.NoSpellCheck)
        (keyboards[T9Keyboard.EnglishName] as? T9Keyboard)?.noSuggestions = noSuggestions
        // the layout may stay as it is, so the one shown is told now
        currentKeyboard?.inPasswordField = service.inPasswordField
        val traits = info.toEditorTraits()
        address = AddressKeys.of(traits)
        typingField = SceneTyping.field(traits, service.inPasswordField)
        typingChanged()
        currentKeyboard?.address = address
        // a number's, phone's or date's field its pad, any other the letters: leaving the one for
        // another, the input method's letters come back, the nine keys, the 14 keys as they were
        fieldPad.field = NumberPad.of(traits.number)
        // English in a password field is the full keyboard whatever it chose, and its own again in the
        // next field: the same input method fires no update, so its letters are picked again here and,
        // shown anew, shown now (switchLayout below would find the letters already there)
        spaceKeys.current?.let {
            lettersLayout.inputMethodChanged(lettersOf(it.uniqueName, keyboardPrefs.imKeyboards(), EngineState.shuangpinScheme, service.inPasswordField), currentKeyboardName)
                ?.takeIf { fieldPad.field == null }?.let(::showLayout)
        }
        switchLayout(if (fieldPad.field != null) NumberKeyboard.Name else TextKeyboard.Name, remember = false)
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
        val keyboard = currentKeyboard as? AutoCapsKeys ?: return
        if (composing || keyboard.capsLocked) return
        // null after the input is bound and before it first starts
        val inputType = service.currentInputEditorInfo?.inputType
        val wants = english && autoCapsPref.getValue() && inputType != null &&
            (service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0) != 0
        keyboard.applyAutoCaps(wants)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    // what is typed, as a theme's scenes (060-render-p3 T5): the input method, the field
    private var typingIm: String? = null
    private var typingField: String? = null

    private fun typingChanged() {
        val scenes = SceneTyping.scenes(typingIm, typingField)
        // the keyboards shown told (BaseKeyboard), this one as it is attached again too
        SceneEnvs.typing = scenes
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        spaceKeys.current = ime
        typingIm = ime.uniqueName
        typingChanged()
        preedit.ui.syllables.owned = stripHidden()
        val letters = lettersOf(ime.uniqueName, keyboardPrefs.imKeyboards(), EngineState.shuangpinScheme, service.inPasswordField)
        // left for another input method while quick input had the place of the pad or a zhuyin keyboard:
        // as that keyboard would, shown. Where it was the letters the new ones are shown now, and quick
        // input's end, told after this, brings it no more back; the pad over another's letters stays,
        // to come back at the end (review Q1, givesWay)
        val stoodIn = standingInFor
        val lent = stoodIn != null && givesWay(stoodIn, lettersWhenStoodIn, letters)
        if (lent) standingInFor = null
        val current = when {
            currentKeyboardName != TextKeyboard.Name || stoodIn == null -> currentKeyboardName
            lent -> lettersLayout.letters
            else -> stoodIn
        }
        val shown = lettersLayout.inputMethodChanged(letters, current)
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
        bar.onKeyboardLayoutSwitched(currentKeyboardName in NumberPad.layouts)
    }
}

/**
 * Whether the code row's syllables are hidden: the keyboard offers them itself ([owns], 九键's
 * column), or its layout [showsStrip] none ([CodeRowHeight.showsStrip]): asked of the layout, not the
 * row's height, which floating is none in the keyboard.
 */
internal fun syllablesOwned(owns: Boolean, showsStrip: Boolean): Boolean = owns || !showsStrip

/**
 * The letters' layout of input method [ime]: its keyboard as [chosen] has it ([ImKeyboards.layoutOf]),
 * 双拼's under [scheme], handwriting's pad; the full keyboard for any other, and for English in a [password] field.
 */
internal fun lettersOf(
    ime: String,
    chosen: ImKeyboards.Chosen = ImKeyboards.Chosen(),
    scheme: ShuangpinScheme = ShuangpinScheme.XIAOHE,
    password: Boolean = false,
): String = nameOf(ImKeyboards.layoutOf(ime, chosen, scheme, password), scheme)

/** The keyboard [layout] is shown on, by its name; 双拼's 18 and nine keys show `;` only where [scheme] types it. */
internal fun nameOf(layout: ImKeyboards.Layout, scheme: ShuangpinScheme = ShuangpinScheme.XIAOHE): String = when (layout) {
    ImKeyboards.Layout.TEXT -> TextKeyboard.Name
    ImKeyboards.Layout.PINYIN14 -> GroupedKeyboard.Name14
    ImKeyboards.Layout.PINYIN18 -> GroupedKeyboard.NamePinyin18
    ImKeyboards.Layout.PINYIN_NINE -> T9Keyboard.Name
    ImKeyboards.Layout.SHUANGPIN18 -> if (scheme.types(';')) GroupedKeyboard.Name18 else GroupedKeyboard.Name18Plain
    ImKeyboards.Layout.SHUANGPIN14 -> GroupedKeyboard.NameShuangpin14
    ImKeyboards.Layout.SHUANGPIN_NINE -> if (scheme.types(';')) T9Keyboard.ShuangpinName else T9Keyboard.ShuangpinPlainName
    ImKeyboards.Layout.ZHUYIN_NINE -> ZhuyinKeyboard.NameNine
    ImKeyboards.Layout.ZHUYIN_FULL -> ZhuyinKeyboard.NameFull
    ImKeyboards.Layout.ENGLISH_NINE -> T9Keyboard.EnglishName
    ImKeyboards.Layout.HANDWRITING -> HandwritingKeyboard.Name
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

/** An ASCII mark (not a letter, digit or space): what a punctuation key sends. */
internal fun isAsciiMark(c: Int): Boolean = c in '!'.code..'~'.code && !Character.isLetterOrDigit(c)
