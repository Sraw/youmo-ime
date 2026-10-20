/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard private constructor(
    context: Context,
    theme: Theme,
    split: Split?,
) : BaseKeyboard(context, theme, split?.keys ?: Layout, split?.widths) {

    constructor(context: Context, theme: Theme) : this(context, theme, Split.of(context))

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        /** Whether the letters are split here: as the setting is, the screen as wide as it is now; floating, never. */
        fun splits(context: Context): Boolean {
            if (ThemeManager.prefs.floatingKeyboard.getValue()) return false
            val config = context.resources.configuration
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            return SplitKeyboard.splits(ThemeManager.prefs.splitKeyboard.getValue().mode, landscape, config.screenWidthDp)
        }

        /** The keyboard's width, dp: the screen's less its side padding (InputView's), or floating its own. */
        fun widthDp(context: Context): Int {
            val config = context.resources.configuration
            if (ThemeManager.prefs.floatingKeyboard.getValue()) return FloatingKeyboard.widthDp(config.screenWidthDp)
            val keyboard = AppPrefs.getInstance().keyboard
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            val padding = (if (landscape) keyboard.keyboardSidePaddingLandscape else keyboard.keyboardSidePadding).getValue()
            return config.screenWidthDp - 2 * padding
        }

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")"),
                // where a physical keyboard has it: 微软 and 紫光 双拼 type ing on it, hidden else (semicolonKey)
                SymbolKey(";", 0.1f, viewId = R.id.button_semicolon)
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                // Sogou's row: symbols and numbers each a key of their own, the comma and the
                // full stop either side of the space, the language switch after them; held, 符
                // and 123 open quick phrase and Unicode input, which have no key in this row
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, 0.12f, longPress = KeyAction.QuickPhraseAction),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f, longPress = KeyAction.UnicodeAction),
                SymbolKey(",", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_comma),
                // an address field's in place of the comma and the full stop (AddressKeys), hidden else
                AddressKey("/", R.id.button_slash),
                AddressKey("@", R.id.button_at),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_period),
                // as it is: through the engine . would be 。
                AddressKey(".", R.id.button_dot),
                AddressKey(AddressKeys.DOMAINS.first(), R.id.button_domain, 0.14f, AddressKeys.DOMAINS),
                LanguageKey(),
                ReturnKey()
            )
        )
    }

    /**
     * [Layout] in two halves of five keys (SplitKeyboard), Gboard's: G and V on both, which also
     * puts the keys of the middle under either thumb; Shift and backspace as wide as a letter. The
     * space spans the room between the halves, the bottom row's other keys as wide in letters as
     * they were.
     */
    private class Split(sizes: SplitKeyboard.Sizes) {
        val keys: List<List<KeyDef>>
        val widths: List<List<Float>>

        init {
            val rows = listOf(
                sizes.row(Layout[0].take(SplitKeyboard.HALF_KEYS), Layout[0].takeLast(SplitKeyboard.HALF_KEYS), GapKey),
                // without the 双拼 `;`: each half is five keys
                Layout[1].dropLast(1).let { sizes.row(it.take(SplitKeyboard.HALF_KEYS), it.takeLast(SplitKeyboard.HALF_KEYS), GapKey) },
                // Shift Z X C V, V B N M backspace
                sizes.row(Layout[2].take(SplitKeyboard.HALF_KEYS), Layout[2].takeLast(SplitKeyboard.HALF_KEYS), GapKey),
                Layout[3].map { it to sizes.width(it.appearance.percentWidth) },
            )
            keys = rows.map { row -> row.map { it.first } }
            widths = rows.map { row -> row.map { it.second } }
        }

        companion object {
            /**
             * The letters in two halves here, or null: as the setting is, the screen as wide as it is
             * now; sized by the keyboard's width, the screen's less its side padding (InputView's).
             */
            fun of(context: Context): Split? {
                if (!splits(context)) return null
                return Split(SplitKeyboard.Sizes(widthDp(context)))
            }
        }
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    /** What the bottom row has either side of the space, as the field is: see [AddressKeys]. */
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            showAddressKeys()
        }

    private fun showAddressKeys() {
        findViewById<View>(R.id.button_comma).isVisible = address == AddressKeys.NONE
        findViewById<View>(R.id.button_period).isVisible = address == AddressKeys.NONE
        findViewById<View>(R.id.button_dot).isVisible = address != AddressKeys.NONE
        findViewById<View>(R.id.button_slash).isVisible = address == AddressKeys.URL
        findViewById<View>(R.id.button_at).isVisible = address == AddressKeys.EMAIL
        findViewById<View>(R.id.button_domain).isVisible = address != AddressKeys.NONE
    }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    // what the user made of the long press: a letter's first is its swipe and its corner too
    private val popupOverridesPref = AppPrefs.getInstance().internal.popupOverrides

    @Keep
    private val popupOverridesListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        updatePunctuationKeys()
    }

    // the input method last reported, for the `;` key again when the 双拼 scheme changed meanwhile
    private var imeName = ""

    init {
        // hidden until an input method says it types on it: shown, every one's A row had an 11th key
        // at a cold start, before fcitx reported the first
        showSemicolonKey()
        showAddressKeys()
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        popupOverridesPref.registerOnChangeListener(popupOverridesListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    // the input method reads a glide (Engines.glides)
    private var glides = false

    private fun showSemicolonKey() {
        // split, the halves have no room for it: absent there
        findViewById<View>(R.id.button_semicolon)?.let { showKey(it, semicolonKey(imeName, EngineBridge.shuangpinScheme)) }
    }

    private val letterKeys: List<Pair<Char, TextKeyView>> by lazy {
        textKeys.mapNotNull { v ->
            (v.def as? KeyDef.Appearance.Text)?.displayText?.singleOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }?.let { it to v }
        }
    }

    // glide typing (滑行输入) where the input method reads one
    override val glide = glideInput(letters = { letterKeys }, enabled = { glides }) { path, layout ->
        onAction(KeyAction.GlideAction(Glide(path, layout, shift = capsState == CapsState.Once, capsLock = capsState == CapsState.Lock)))
        // as a letter typed: Shift-once used up in any input method, as a tap uses it up (only English reads it)
        if (capsState == CapsState.Once) switchCapsState()
    }

    private val autoCaps = AutoCaps()

    /** Caps lock on: no sentence start changes Shift. */
    val capsLocked: Boolean get() = capsState == CapsState.Lock

    /** Shift on by itself where the editor [wants] a capital next, as [AutoCaps] decides. */
    fun applyAutoCaps(wants: Boolean) {
        val current = when (capsState) {
            CapsState.None -> AutoCaps.Caps.NONE
            CapsState.Once -> AutoCaps.Caps.ONCE
            CapsState.Lock -> AutoCaps.Caps.LOCK
        }
        val next = when (autoCaps.next(current, wants)) {
            AutoCaps.Caps.NONE -> CapsState.None
            AutoCaps.Caps.ONCE -> CapsState.Once
            AutoCaps.Caps.LOCK -> CapsState.Lock
        }
        if (next == capsState) return
        capsState = next
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
    }

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            // a word from the long press (or a letter's swipe, its first) is typed too: Shift-once is used up
            is KeyAction.CommitAction -> if (capsState == CapsState.Once) switchCapsState()
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        autoCaps.changed()
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
        // the scheme may have changed in the settings with 双拼 kept, which reports no input method
        showSemicolonKey()
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
        imeName = ime.uniqueName
        showSemicolonKey()
        glides = Engines.glides(ime.uniqueName)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(viewId: Int, c: String) = previewOf(c, viewId, ::transformAlphabet, ::transformPunctuation)

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.viewId, action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.viewId, action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        autoCaps.changed()
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
        // a letter's corner is its swipe, which follows Shift (swipeOverride)
        updatePunctuationKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
        // The icon carries the state, so accessibility has to as well. As a state description
        // TalkBack speaks it when it changes on the focused key -- the user pressing Shift --
        // but not when Shift lets go by itself after a letter, when another key has focus.
        ViewCompat.setStateDescription(
            caps,
            when (capsState) {
                CapsState.None -> null
                CapsState.Once -> context.getString(R.string.a11y_state_shift_once)
                CapsState.Lock -> context.getString(R.string.a11y_state_caps_lock)
            }
        )
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            // on to the next key, not out: the 双拼 `;` after L is no letter, and Z to M come after it
            if (it.def !is KeyDef.Appearance.AltText) return@forEach
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (keepLettersUppercase) str.uppercase() else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(swipeOverride(it.def) ?: it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                // an address's keys commit as they are, and say so: . not 。
                if (it.id in ADDRESS_KEYS) return@forEach
                it.mainText.text = it.def.displayText.let { str ->
                    if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }


    // a letter's swipe is the first of its long press, as built in (q: 1, then Q) and as the user
    // changed it: one thing, drawn in its corner too (updatePunctuationKeys); none when the user
    // turned its long press off
    override fun swipeText(view: KeyView): SwipeChoice =
        SwipeChoice.of(((view as? AltTextKeyView)?.def as? KeyDef.Appearance.AltText)?.let(::swipeOverride))

    private fun swipeOverride(def: KeyDef.Appearance.AltText): String? {
        val letter = def.displayText.takeIf { it.length == 1 && it[0].isLetter() }?.lowercase() ?: return null
        // under Shift the long press shows the upper-case label's list (onPopupAction): so does the swipe
        return PopupOverridesCache.user.current.swipeOf(letter, transformAlphabet(letter))
    }

}

/** What [label]'s long press offers: the user's list, else the built-in one; null where it is off. */
internal fun PopupOverrides.longPressOf(label: String): Array<String>? = resolve(label, PopupPreset[label])

// here, not in the companion: previewOf's JVM test would otherwise load TextKeyboard, a View;
// the 双拼 `;` is a key of the scheme, shown as it is too (; not ；)
private val ADDRESS_KEYS = setOf(R.id.button_slash, R.id.button_at, R.id.button_dot, R.id.button_domain, R.id.button_semicolon)


/**
 * What the preview bubble of the key [viewId] shows for [content]: a letter as Shift has it
 * ([letter]), punctuation as the input method maps it ([punctuation]); an address's keys what they
 * commit, . not 。, as their labels show (TextKeyboard.updatePunctuationKeys).
 */
internal fun previewOf(content: String, viewId: Int, letter: (String) -> String, punctuation: (String) -> String): String = when {
    content.length != 1 || viewId in ADDRESS_KEYS -> content
    content[0].isLetter() -> letter(content)
    else -> punctuation(content)
}

/**
 * The first of [label]'s long press ([longPressOf]) as the user changed it, [label] being [letter] as
 * Shift shows it: null where the user changed neither (the built-in swipe), "" where that long press is off.
 */
internal fun PopupOverrides.swipeOf(letter: String, label: String): String? {
    if (this[letter] == null && this[label] == null) return null
    return longPressOf(label)?.firstOrNull().orEmpty()
}