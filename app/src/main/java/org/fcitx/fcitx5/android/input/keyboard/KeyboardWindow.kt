/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
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
import org.fcitx.fcitx5.android.input.voiceField
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
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

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
         * Quick input types letters, which the zhuyin keyboards send none of (a symbol's own code); null
         * where [keyboard] types them itself.
         */
        fun quickInputStandIn(keyboard: String): String? = when (keyboard) {
            ZhuyinKeyboard.NameNine, ZhuyinKeyboard.NameFull -> TextKeyboard.Name
            else -> null
        }

        fun givesWay(was: String, lettersThen: String, letters: String): Boolean =
            was == lettersThen && letters != lettersThen

        /** [was] again, unless it was the letters and they changed meanwhile; [givesWay] already shows those, so this is a backstop. */
        fun afterQuickInput(was: String, lettersThen: String, lettersNow: String): String =
            if (was == lettersThen) lettersNow else was

        fun rowUnits(keyboard: BaseKeyboard, look: ThemeLook): Double {
            val (layout, env) = keyboard.shownAs(look, keyboard.width.coerceAtLeast(1), keyboard.height.coerceAtLeast(1))
                ?: return KeyboardHeightCap.DEFAULT_ROWS
            return KeyboardHeightCap.rowUnits(layout.layout, layout.fragments, env)
        }
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

    private val makers: Map<String, (String?) -> BaseKeyboard> = keyboardMakers({ context }, { theme })

    private fun ink(tell: suspend HostApi.() -> Unit) {
        // hidden while it was read: the input it was for is over
        if (!service.isInputViewShown) return
        service.postFcitxJob { tell() }
    }

    private val keyboards = hashMapOf<String, BaseKeyboard>()
    private var currentKeyboardName = ""

    fun filtersStrokes(): Boolean = keyboards[currentKeyboardName]?.strokesDrawn == true
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val service by manager.inputMethodService()

    private val autoCapsPref = AppPrefs.getInstance().keyboard.englishAutoCaps

    private var english = false

    private var composing = false

    // its panel is the only one with a line above a preedit (a TempPanel's auxUp), and what is typed in it is
    // not the input method's
    private var tempMode = false

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        composing = !empty
        keyboards[currentKeyboardName]?.imTyping = composing && !tempMode
    }

    private var standingInFor: String? = null
    private var lettersWhenStoodIn = ""

    override fun onInputPanelUpdate(data: HostEvent.InputPanel) {
        tempMode = data.auxUp.isNotEmpty()
        keyboards[currentKeyboardName]?.imTyping = composing && !tempMode
        keyboards[currentKeyboardName]?.tempMode = tempMode
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

    // a user's layout is remade where its file has a new stamp, the older stamp's dropped; another name of
    // the same stamp (a number board's other pads) kept
    private val currentKeyboard: BaseKeyboard?
        get() {
            val name = currentKeyboardName
            val maker = makers[KeyboardNames.base(name)] ?: return null
            keyboards[name]?.let { return it }
            val custom = KeyboardNames.custom(name)
            custom?.let { c -> keyboards.keys.removeAll { KeyboardNames.stale(it, c) } }
            return SceneTrace.section("make keyboard") { maker(custom) }.also { keyboards[name] = it }
        }

    /**
     * 注音's five, a custom layout's own; four where none read. The keys' least height where room is short.
     * Expanded at the last laid-out size, 1 × 1 before: the built-in layouts' rows do not depend on it.
     */
    fun rowUnits(): Double {
        val keyboard = currentKeyboard ?: return KeyboardHeightCap.DEFAULT_ROWS
        return rowUnits(keyboard, ThemeManager.shownLook(theme))
    }

    private val spaceKeys = SpaceKeyInputMethod()

    private fun known(): Boolean {
        if (!punctuation.known) punctuation.update(PunctuationStartKeys.known)
        return punctuation.known
    }

    private var heldBackFelt = false

    // unknown before the host is up (a first install): held back, a tap felt once, as no face could show it
    private fun heldBack(action: KeyAction): Boolean {
        if (known()) return false
        val mark = when (action) {
            is KeyAction.HostKeyAction -> action.act.singleOrNull()?.code?.let(::isAsciiMark) == true
            is KeyAction.SymAction -> isAsciiMark(action.sym.sym)
            is KeyAction.CommitAction -> action.punctuation
            else -> false
        }
        if (!mark) return false
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
            // the listener is every keyboard's: a letter layout's panel-lock locks nothing
            it is KeyAction.PanelLockAction && currentKeyboard is NumberKeyboard -> {
                sticky.toggleLock()
                syncLock()
            }
            else -> commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        val shown = popup.listener
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
            it.fieldPad = fieldPadId
            (it as? NumberKeyboard)?.locked = sticky.locked
            it.inMultiLineField = multiLine
            it.imTyping = composing && !tempMode
            it.tempMode = tempMode
            // the toolbar's 手写: the current input method's own handwriting's
            it.inkSink = { reading, piece -> ink { offerInk(reading, piece) } }
            stripOwned = it.ownsSyllables
            preedit.ui.syllables.owned = stripHidden()
            spaceKeys.attach(it) {
                keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
                it.onAttach()
                it.onReturnDrawableUpdate(returnKeyDrawable.appearance)
                // a keyboard kept while another was shown missed changes, and would show , where it types ，
                it.onPunctuationUpdate(punctuation.mapping)
            }
        }
    }

    private var stripOwned = false

    private fun stripHidden() = syllablesOwned(stripOwned, KawaiiBarComponent.showsStrip(spaceKeys.current?.uniqueName))

    val lettersShown get() = KeyboardNames.base(currentKeyboardName) == TextKeyboard.Name

    var onLayoutShown: (() -> Unit)? = null

    private fun tellZhuyinOnce(target: String) {
        if (target != ZhuyinKeyboard.NameNine && target != ZhuyinKeyboard.NameFull) return
        val told = AppPrefs.getInstance().internal.zhuyinTraditionalTold
        if (told.getValue()) return
        val traditional = EngineState.traditionalIms ?: return
        if (InputMethodCatalog.PINYIN in traditional) return
        // posted: the ⋯ panel closing as the layout shows would take a notice said now with it. Marked told
        // only once said: a post that never runs tells it next time
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
        updateAutoCaps()
        if (windowManager.isAttached(this)) {
            notifyBarLayoutChanged()
        }
    }

    private var lettersLayout = newLettersLayout()

    private fun newLettersLayout() = LettersLayout(makers.keys, TextKeyboard.Name, PickerWindow.Key.Symbol.name)

    fun remakeKeyboards() {
        if (!::keyboardView.isInitialized) return
        detachCurrentLayout()
        keyboards.clear()
        lettersLayout = newLettersLayout()
        standingInFor = null
        lettersWhenStoodIn = ""
        popup.dismissAll()
        attachLayout(TextKeyboard.Name)
        onLayoutShown?.invoke()
        if (windowManager.isAttached(this)) notifyBarLayoutChanged()
    }

    private val fieldPad = FieldPad()

    private val sticky get() = service.sticky

    /** The lock's look: the number keyboard shown's alone, where the state may have changed with no switch. */
    fun syncLock() {
        (keyboards[currentKeyboardName] as? NumberKeyboard)?.locked = sticky.locked
    }

    /** Hidden, or a hardware keyboard took over: the number keyboard kept so far is let go, a locked one not. */
    fun keyboardHidden() {
        sticky.hidden()
        syncLock()
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val lastSymbol = lastSymbolType
        ContextCompat.getMainExecutor(context).execute {
            val outcome = lettersLayout.switchTo(to, lastSymbol, fieldPad.known(currentKeyboardName), remember)
            outcome.remember?.let { lastSymbolType = it }
            when (val target = fieldPad.shown(outcome.target, currentKeyboardName)) {
                LayoutSwitchPolicy.Target.Unchanged -> {}
                is LayoutSwitchPolicy.Target.Keyboard -> showLayout(target.name)
                // the keys under the panel stay: a number keyboard kept stays kept
                LayoutSwitchPolicy.Target.SymbolPicker -> {
                    windowManager.attachWindow(PickerWindow.Key.Symbol)
                    return@execute
                }
            }
            sticky.shown(NumberPad.shownBy(currentKeyboardName) != null, byUser = remember)
            syncLock()
        }
    }

    private var address = AddressKeys.NONE

    /** NumberPad.fieldOf's, which every keyboard's scroll lists read; [fieldPad] is what the switching shows. */
    private var fieldPadId: String? = null
    private var multiLine = false

    private var noSuggestions = false

    private val fieldTrack = FieldTrack()

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        standingInFor = null
        keyboards.values.forEach { it.restartInk() }
        noSuggestions = capFlags.has(CapabilityFlag.NoSpellCheck)
        keyboards.filterKeys { KeyboardNames.base(it) == T9Keyboard.EnglishName }.values.forEach { (it as? T9Keyboard)?.noSuggestions = noSuggestions }
        currentKeyboard?.inPasswordField = service.inPasswordField
        currentKeyboard?.catchUpMinute()
        val traits = info.toEditorTraits()
        val identical = info === service.stickyInfo
        service.stickyInfo = info
        // those kept for later too: a number keyboard shown next must not open where another field's was left
        if (!fieldTrack.started(info.voiceField(), identical, service.restartingInput)) keyboards.values.forEach { it.columns.toStart() }
        val was = sticky.mode
        val kept = sticky.fieldStarted(traits.isPlainText, traits.number, info.voiceField(), identical, service.restartingInput)
        if (kept) Timber.d("number keyboard kept: %s", was) else if (sticky.mode != was) Timber.d("number keyboard let go: %s", was)
        syncLock()
        address = AddressKeys.of(traits)
        fieldPadId = NumberPad.fieldOf(traits)
        multiLine = traits.showsLines
        typingField = SceneTyping.field(traits, service.inPasswordField)
        typingChanged()
        currentKeyboard?.address = address
        currentKeyboard?.fieldPad = fieldPadId
        currentKeyboard?.inMultiLineField = multiLine
        fieldPad.field = NumberPad.of(traits.number)
        fieldPad.numbers = keyboardPrefs.drawnPanelLayout(PanelLayouts.Panel.NUMBER)
        // English in a password is the full keyboard, its own again in the next field: the same input method
        // fires no update, so its letters are picked here (switchLayout would find the letters already there)
        spaceKeys.current?.let {
            lettersLayout.inputMethodChanged(lettersOf(it.uniqueName, keyboardPrefs.imKeyboards(), EngineState.shuangpinScheme, service.inPasswordField), currentKeyboardName)
                ?.takeIf { fieldPad.field == null && !kept }?.let(::showLayout)
        }
        switchLayout(if (fieldPad.field != null || kept) NumberKeyboard.Name else TextKeyboard.Name, remember = false)
        // after the layout, which switchLayout posts
        ContextCompat.getMainExecutor(context).execute(::updateAutoCaps)
    }

    override fun onSelectionUpdate(start: Int, end: Int) = updateAutoCaps()

    fun cursorMoved() {
        (currentKeyboard as? T9Keyboard)?.cursorMoved()
    }

    private fun updateAutoCaps() {
        val keyboard = currentKeyboard as? AutoCapsKeys ?: return
        if (composing || keyboard.capsLocked) return
        val inputType = service.currentInputEditorInfo?.inputType
        val wants = english && autoCapsPref.getValue() && inputType != null &&
            (service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0) != 0
        keyboard.applyAutoCaps(wants)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private var typingIm: String? = null
    private var typingField: String? = null

    private fun typingChanged() {
        val scenes = SceneTyping.scenes(typingIm, typingField)
        SceneEnvs.typing = scenes
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        spaceKeys.current = ime
        typingIm = ime.uniqueName
        typingChanged()
        preedit.ui.syllables.owned = stripHidden()
        val letters = lettersOf(ime.uniqueName, keyboardPrefs.imKeyboards(), EngineState.shuangpinScheme, service.inPasswordField)
        // another input method while quick input stood in for the pad or a zhuyin keyboard: as that keyboard
        // would. The pad over another's letters stays, to come back at the end ([givesWay])
        val stoodIn = standingInFor
        val lent = stoodIn != null && givesWay(stoodIn, lettersWhenStoodIn, letters)
        if (lent) standingInFor = null
        val current = when {
            currentKeyboardName != TextKeyboard.Name || stoodIn == null -> currentKeyboardName
            lent -> lettersLayout.letters
            else -> stoodIn
        }
        val shown = lettersLayout.inputMethodChanged(letters, current)
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
        bar.onKeyboardLayoutSwitched(NumberPad.shownBy(currentKeyboardName) != null)
    }
}

internal fun syllablesOwned(owns: Boolean, showsStrip: Boolean): Boolean = owns || !showsStrip

internal fun keyboardMakers(contextOf: () -> Context, themeOf: () -> Theme): Map<String, (String?) -> BaseKeyboard> {
    fun nineKeys(english: Boolean, custom: String?): T9Keyboard {
        val keyboard = AppPrefs.getInstance().keyboard
        return T9Keyboard(contextOf(), themeOf(), NineKeyLetters.parse(keyboard.t9Letters.getValue()), english = english, semicolon = false, custom = custom)
    }
    return mapOf(
        TextKeyboard.Name to { c: String? -> TextKeyboard(contextOf(), themeOf(), c) },
        T9Keyboard.Name to { c: String? -> nineKeys(english = false, c) },
        T9Keyboard.EnglishName to { c: String? -> nineKeys(english = true, c) },
        GroupedKeyboard.Name14 to { c: String? -> GroupedKeyboard(contextOf(), themeOf(), ownGroups = true, custom = c) },
        GroupedKeyboard.Name18 to { c: String? -> GroupedKeyboard(contextOf(), themeOf(), NineKeyLetters.G18, custom = c) },
        GroupedKeyboard.Name18Plain to { c: String? -> GroupedKeyboard(contextOf(), themeOf(), NineKeyLetters.G18, semicolon = false, custom = c) },
        GroupedKeyboard.NamePinyin18 to { c: String? ->
            GroupedKeyboard(contextOf(), themeOf(), NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false, ownGroups = true, custom = c)
        },
        GroupedKeyboard.NameShuangpin14 to { c: String? -> GroupedKeyboard(contextOf(), themeOf(), NineKeyLetters.G14, Keyboard::shuangpin14Key, custom = c) },
        T9Keyboard.ShuangpinName to { c: String? -> T9Keyboard(contextOf(), themeOf(), shuangpin = true, semicolon = true, custom = c) },
        T9Keyboard.ShuangpinPlainName to { c: String? -> T9Keyboard(contextOf(), themeOf(), shuangpin = true, semicolon = false, custom = c) },
        ZhuyinKeyboard.NameNine to { _: String? -> ZhuyinKeyboard(contextOf(), themeOf(), nine = true) },
        ZhuyinKeyboard.NameFull to { _: String? -> ZhuyinKeyboard(contextOf(), themeOf(), nine = false) },
        *NumberPad.entries.map { pad -> pad.layout to { c: String? -> NumberKeyboard(contextOf(), themeOf(), pad, c) } }.toTypedArray(),
    )
}

internal fun lettersOf(
    ime: String,
    chosen: ImKeyboards.Chosen = ImKeyboards.Chosen(),
    scheme: ShuangpinScheme = ShuangpinScheme.XIAOHE,
    password: Boolean = false,
): String = ImKeyboards.shown(ime, chosen, scheme, password).let { KeyboardNames.of(nameOf(it.layout, scheme), it.custom) }

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
}

/**
 * A layout announces a change only from the name it showed itself, and the user may have seen another
 * layout's: one being shown is told that one first, before it has a parent, as announceForAccessibility
 * says nothing then. Announced once, on the layout shown; never the one being left, nor a window's first.
 */
internal class SpaceKeyInputMethod {

    var current: InputMethodEntry? = null

    private var seen: InputMethodEntry? = null

    fun attach(keyboard: BaseKeyboard, adding: () -> Unit) {
        seen?.let(keyboard::onInputMethodUpdate)
        adding()
        show(keyboard)
    }

    fun show(keyboard: BaseKeyboard) {
        val ime = current ?: return
        keyboard.onInputMethodUpdate(ime)
        keyboard.inputMethodShown()
        if (keyboard !is NumberKeyboard) seen = ime
    }
}

internal fun isAsciiMark(c: Int): Boolean = c in '!'.code..'~'.code && !Character.isLetterOrDigit(c)
