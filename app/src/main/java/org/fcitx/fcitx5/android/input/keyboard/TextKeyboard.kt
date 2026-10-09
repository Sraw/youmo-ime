/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, Layout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

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
                AlphabetKey("L", ")")
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
                SymbolKey(",", 0.1f, KeyDef.Appearance.Variant.Alternative),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                LanguageKey(),
                ReturnKey()
            )
        )
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    // what the user made of the long press: a letter's first is its swipe and its corner too
    private val popupOverridesPref = AppPrefs.getInstance().internal.popupOverrides
    private var popupOverrides = PopupOverrides.parse(popupOverridesPref.getValue())

    @Keep
    private val popupOverridesListener = ManagedPreference.OnChangeListener<String> { _, v ->
        popupOverrides = PopupOverrides.parse(v)
        updatePunctuationKeys()
    }

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        popupOverridesPref.registerOnChangeListener(popupOverridesListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

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
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        val imeName = buildString {
            append(InputMethodNames.of(context, ime))
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        // A change of input method (or of its sub-mode, e.g. an ASCII toggle) is otherwise
        // silent: the name is only on the Space key, which the user is not touching. The first
        // update is the keyboard appearing, which TalkBack announces itself. A change made while
        // another layout was showing is announced on returning to this one.
        if (shownImeName != null && shownImeName != imeName) announce(imeName)
        shownImeName = imeName
        space.mainText.text = imeName
        // TalkBack reads the key's description instead of the text drawn on it, so the name
        // has to be in the description too
        space.contentDescription = context.getString(R.string.a11y_key_space_with_ime, imeName)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
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

    /** The input method name last shown on the Space key; null until the first update. */
    private var shownImeName: String? = null

    /**
     * `announceForAccessibility` is deprecated from API 36 in favour of describing the UI
     * (state descriptions, live regions). Neither fits the input method name: it is on the
     * Space key, which the user is not touching, and a live region there would speak every
     * time the keyboard is rebuilt.
     */
    @Suppress("DEPRECATION")
    private fun announce(text: CharSequence?) {
        if (!text.isNullOrEmpty()) announceForAccessibility(text)
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
            if (it.def !is KeyDef.Appearance.AltText) return
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
    override fun swipeText(view: KeyView): String? =
        ((view as? AltTextKeyView)?.def as? KeyDef.Appearance.AltText)?.let(::swipeOverride)

    private fun swipeOverride(def: KeyDef.Appearance.AltText): String? {
        val letter = def.displayText.takeIf { it.length == 1 && it[0].isLetter() }?.lowercase() ?: return null
        // under Shift the long press shows the upper-case label's list (onPopupAction): so does the swipe
        return popupOverrides.swipeOf(letter, transformAlphabet(letter))
    }

}

/**
 * The first of [label]'s long press as the user changed it, [label] being [letter] as Shift shows it:
 * null where the user changed neither (the built-in swipe), "" where that long press is off.
 */
internal fun PopupOverrides.swipeOf(letter: String, label: String): String? {
    if (this[letter] == null && this[label] == null) return null
    return resolve(label, PopupPreset[label])?.firstOrNull().orEmpty()
}