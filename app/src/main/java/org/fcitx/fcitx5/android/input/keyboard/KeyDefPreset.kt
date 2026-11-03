/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.KeyRouter
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow

val NumLockState = KeyStates(KeyState.NumLock, KeyState.Virtual)

// a theme's names of keys by what they type (KeyNames' roles): the same key on every keyboard
private val SYMBOL_NAMES = mapOf(";" to "semicolon", "*" to "star", "," to "comma", "." to "period")

// an address's own keys, beside the comma and period they are shown in place of
private val ADDRESS_NAMES = mapOf("/" to "slash", "@" to "at", "." to "dot")

/** A theme's name of a key that goes to [to]: the symbols, the number keyboard, back to the letters. */
private fun switchName(to: String) = when (to) {
    PickerWindow.Key.Symbol.name -> "symbols"
    NumberKeyboard.Name -> "numbers"
    TextKeyboard.Name -> "back"
    else -> null
}

class SymbolKey(
    val symbol: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    popup: Array<Popup>? = null,
    viewId: Int = -1,
    name: String? = SYMBOL_NAMES[symbol],
    shortcut: String? = null,
) : KeyDef(
    Appearance.Text(
        displayText = symbol,
        textSize = 23f,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId,
    ),
    setOf(
        Behavior.Press(KeyAction.HostKeyAction(symbol))
    ),
    popup ?: arrayOf(
        Popup.Preview(symbol),
        Popup.Keyboard.Preset(symbol)
    ),
    name,
    shortcut,
)

/** A key left blank ([NumberPad]'s), so the keys around it stay where they are: no press, nothing for TalkBack. */
class BlankKey(percentWidth: Float, variant: Variant = Variant.Alternative) : KeyDef(
    Appearance.Text("", 23f, percentWidth = percentWidth, variant = variant),
    emptySet(),
)

/**
 * What an address is made of ([AddressKeys]), committed as it is: through the engine `/` would
 * be 、 in Chinese. Its long press offers [more], hidden till an address field shows it.
 */
class AddressKey(
    text: String,
    viewId: Int,
    percentWidth: Float = 0.1f,
    more: List<String> = emptyList(),
    name: String = ADDRESS_NAMES[text] ?: "domain",
) : KeyDef(
    Appearance.Text(
        displayText = text,
        textSize = if (text.length > 1) 16f else 23f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        viewId = viewId,
    ),
    setOf(Behavior.Press(KeyAction.CommitAction(text))),
    if (more.isEmpty()) arrayOf(Popup.Preview(text)) else arrayOf(Popup.Preview(text), Popup.Keyboard.Explicit(more.toTypedArray())),
    name,
)

/** Room between a split keyboard's halves ([SplitKeyboard]): no key, nothing drawn. */
object GapKey : KeyDef(Appearance.Text("", 0f, percentWidth = 0f, border = Border.Off, margin = false), emptySet())

class AlphabetKey(
    val character: String,
    val punctuation: String,
    variant: Variant = Variant.Normal,
    popup: Array<Popup>? = null
) : KeyDef(
    Appearance.AltText(
        displayText = character,
        altText = punctuation,
        textSize = 23f,
        variant = variant
    ),
    setOf(
        Behavior.Press(KeyAction.HostKeyAction(character)),
        Behavior.Swipe(KeyAction.HostKeyAction(punctuation))
    ),
    popup ?: arrayOf(
        Popup.AltPreview(character, punctuation),
        Popup.Keyboard.Preset(character)
    ),
    character.lowercase(),
    "full/${character.lowercase()}",
)

class CapsKey(percentWidth: Float = 0.15f) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_capslock_none,
        viewId = R.id.button_caps,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        // updated to the state-specific label by TextKeyboard.updateCapsButtonIcon
        contentDescription = R.string.a11y_key_shift
    ),
    setOf(
        Behavior.Press(KeyAction.CapsAction(false)),
        Behavior.LongPress(KeyAction.CapsAction(true)),
        Behavior.DoubleTap(KeyAction.CapsAction(true))
    ),
    name = "shift",
    shortcut = "shift",
)

/**
 * A table's 拼 key, in Shift's place (TextKeyboard): its pinyin lookup turned on or off
 * ([Keyboard.LOOKUP]). Two, one shown: [on] is dark, as the lookup is then.
 */
class LookUpKey(on: Boolean, percentWidth: Float = 0.15f) : KeyDef(
    Appearance.Text(
        displayText = KeyRouter.LOOKING_UP,
        textSize = 18f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = if (on) Variant.Accent else Variant.Alternative,
        // drawn whatever the theme's borders: dark is its point
        border = if (on) Border.On else Border.Default,
        viewId = if (on) R.id.button_lookup_on else R.id.button_lookup,
    ),
    setOf(Behavior.Press(KeyAction.SymAction(KeySym(UNICODE + Keyboard.LOOKUP), KeyStates.Virtual))),
    name = if (on) "lookup-on" else "lookup",
)

class LayoutSwitchKey(
    displayText: String,
    val to: String = "",
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative,
    longPress: KeyAction? = null,
    name: String? = switchName(to),
) : KeyDef(
    Appearance.Text(
        displayText,
        textSize = 16f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOfNotNull(
        Behavior.Press(KeyAction.LayoutSwitchAction(to)),
        longPress?.let { Behavior.LongPress(it) }
    ),
    name = name,
    // the symbols' and the digits' (a page of their own on the keyboard); back to the letters has none
    shortcut = when (to) {
        PickerWindow.Key.Symbol.name -> "symbols"
        NumberKeyboard.Name -> "numbers"
        else -> null
    },
)

class BackspaceKey(
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative
) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_backspace_24,
        percentWidth = percentWidth,
        variant = variant,
        viewId = R.id.button_backspace,
        soundEffect = InputFeedbacks.SoundEffect.Delete,
        contentDescription = R.string.a11y_key_backspace
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))),
        Behavior.Repeat(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)))
    ),
    name = "backspace",
    shortcut = "backspace",
)

class LanguageKey : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_language_24,
        variant = Variant.AltForeground,
        viewId = R.id.button_lang,
        contentDescription = R.string.a11y_key_switch_language
    ),
    setOf(
        Behavior.Press(KeyAction.LangSwitchAction),
        Behavior.LongPress(KeyAction.ShowInputMethodPickerAction)
    ),
    name = "lang",
    shortcut = "lang",
)

class SpaceKey : KeyDef(
    Appearance.Text(
        displayText = " ",
        textSize = 13f,
        percentWidth = 0f,
        border = Border.Special,
        viewId = R.id.button_space,
        soundEffect = InputFeedbacks.SoundEffect.SpaceBar,
        // displayText is a single space; without this the key is unlabelled. TalkBack speaks a
        // focusable parent's description instead of its children's text, so a keyboard that
        // draws the input method name on the bar must put it in the description as well
        // (TextKeyboard.onInputMethodUpdate does).
        contentDescription = R.string.a11y_key_space
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))),
        Behavior.LongPress(KeyAction.SpaceLongPressAction())
    ),
    name = "space",
    shortcut = "space",
)

class ReturnKey(percentWidth: Float = 0.15f) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_keyboard_return_24,
        percentWidth = percentWidth,
        variant = Variant.Accent,
        border = Border.Special,
        viewId = R.id.button_return,
        soundEffect = InputFeedbacks.SoundEffect.Return,
        contentDescription = R.string.a11y_key_enter
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)))
    ),
    arrayOf(
        Popup.Menu(
            arrayOf(
                Popup.Menu.Item(
                    "Emoji", R.drawable.ic_baseline_tag_faces_24, KeyAction.PickerSwitchAction()
                )
            )
        )
    ),
    name = "enter",
    shortcut = "return",
)

/**
 * Return as a table shows it while a code is typed: 确定 (its text the keyboard's to set, a
 * string's), what it does the same ([ReturnKey]'s); shown in its place (TextKeyboard).
 */
class ConfirmKey(percentWidth: Float = 0.15f) : KeyDef(
    Appearance.Text(
        displayText = "",
        textSize = 16f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = Variant.Accent,
        border = Border.On,
        viewId = R.id.button_confirm,
        soundEffect = InputFeedbacks.SoundEffect.Return,
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)))
    ),
    arrayOf(
        Popup.Menu(
            arrayOf(
                Popup.Menu.Item(
                    "Emoji", R.drawable.ic_baseline_tag_faces_24, KeyAction.PickerSwitchAction()
                )
            )
        )
    ),
    name = "confirm",
    shortcut = "return",
)

class ImageLayoutSwitchKey(
    @DrawableRes
    icon: Int,
    to: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    /** Required: the key goes somewhere different at each call site, so no default fits. */
    @StringRes contentDescription: Int,
    name: String? = switchName(to),
) : KeyDef(
    Appearance.Image(
        src = icon,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId,
        contentDescription = contentDescription
    ),
    setOf(
        Behavior.Press(KeyAction.LayoutSwitchAction(to))
    ),
    name = name,
)

class ImagePickerSwitchKey(
    @DrawableRes
    icon: Int,
    to: PickerWindow.Key,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    /** Required: the key goes somewhere different at each call site, so no default fits. */
    @StringRes contentDescription: Int,
    name: String? = to.name.lowercase(),
) : KeyDef(
    Appearance.Image(
        src = icon,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId,
        contentDescription = contentDescription
    ),
    setOf(
        Behavior.Press(KeyAction.PickerSwitchAction(to))
    ),
    name = name,
)

class TextPickerSwitchKey(
    text: String,
    to: PickerWindow.Key,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    name: String? = to.name.lowercase(),
) : KeyDef(
    Appearance.Text(
        displayText = text,
        textSize = 16f,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId,
        textStyle = Typeface.BOLD
    ),
    setOf(
        Behavior.Press(KeyAction.PickerSwitchAction(to))
    ),
    name = name,
)

class MiniSpaceKey : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_space_bar_24,
        percentWidth = 0.15f,
        variant = Variant.Alternative,
        viewId = R.id.button_mini_space,
        contentDescription = R.string.a11y_key_space
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space)))
    ),
    name = "space",
    shortcut = "space",
)

class NumPadKey(
    displayText: String,
    val sym: Int,
    textSize: Float = 16f,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    shortcut: String? = null,
) : KeyDef(
    Appearance.Text(
        displayText,
        textSize = textSize,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(sym), NumLockState))
    ),
    name = displayText,
    shortcut = shortcut,
)
