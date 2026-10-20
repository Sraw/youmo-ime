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
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow

val NumLockState = KeyStates(KeyState.NumLock, KeyState.Virtual)

class SymbolKey(
    val symbol: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    popup: Array<Popup>? = null,
    viewId: Int = -1,
) : KeyDef(
    Appearance.Text(
        displayText = symbol,
        textSize = 23f,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId,
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(symbol))
    ),
    popup ?: arrayOf(
        Popup.Preview(symbol),
        Popup.Keyboard.Preset(symbol)
    )
)

/**
 * What an address is made of ([AddressKeys]), committed as it is: through the engine `/` would
 * be 、 in Chinese. Its long press offers [more], hidden till an address field shows it.
 */
class AddressKey(text: String, viewId: Int, percentWidth: Float = 0.1f, more: List<String> = emptyList()) : KeyDef(
    Appearance.Text(
        displayText = text,
        textSize = if (text.length > 1) 16f else 23f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        viewId = viewId,
    ),
    setOf(Behavior.Press(KeyAction.CommitAction(text))),
    if (more.isEmpty()) arrayOf(Popup.Preview(text)) else arrayOf(Popup.Preview(text), Popup.Keyboard.Explicit(more.toTypedArray())),
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
        Behavior.Press(KeyAction.FcitxKeyAction(character)),
        Behavior.Swipe(KeyAction.FcitxKeyAction(punctuation))
    ),
    popup ?: arrayOf(
        Popup.AltPreview(character, punctuation),
        Popup.Keyboard.Preset(character)
    )
)

class AlphabetDigitKey(
    val character: String,
    altText: String,
    val sym: Int,
    popup: Array<Popup>? = null
) : KeyDef(
    Appearance.AltText(
        displayText = character,
        altText = altText,
        textSize = 23f
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(character)),
        Behavior.Swipe(KeyAction.SymAction(KeySym(sym), NumLockState))
    ),
    popup ?: arrayOf(
        Popup.AltPreview(character, altText),
        Popup.Keyboard.Preset(character)
    )
) {
    constructor(
        char: String,
        digit: Int,
        popup: Array<Popup>? = null
    ) : this(
        char,
        digit.toString(),
        FcitxKeyMapping.FcitxKey_KP_0 + digit,
        popup
    )
}

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
    )
)

class LayoutSwitchKey(
    displayText: String,
    val to: String = "",
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative,
    longPress: KeyAction? = null
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
    )
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
    )
)

class QuickPhraseKey : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_format_quote_24,
        variant = Variant.Alternative,
        viewId = R.id.button_quickphrase,
        contentDescription = R.string.a11y_key_quick_phrase
    ),
    setOf(
        Behavior.Press(KeyAction.QuickPhraseAction),
        Behavior.LongPress(KeyAction.UnicodeAction)
    )
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
    )
)

/** 九键's 中/英, beside the space as 搜狗's: [KeyAction.NineKeyLanguageAction]; its label T9Keyboard.languageLabel. */
class NineKeyLanguageKey(percentWidth: Float) : KeyDef(
    Appearance.Text(
        displayText = "中/英",
        textSize = 16f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        viewId = R.id.button_nine_language,
        contentDescription = R.string.a11y_key_nine_language,
    ),
    setOf(Behavior.Press(KeyAction.NineKeyLanguageAction)),
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
    )
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
)

class ImageLayoutSwitchKey(
    @DrawableRes
    icon: Int,
    to: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    /** Required: the key goes somewhere different at each call site, so no default fits. */
    @StringRes contentDescription: Int
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
    )
)

class ImagePickerSwitchKey(
    @DrawableRes
    icon: Int,
    to: PickerWindow.Key,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    /** Required: the key goes somewhere different at each call site, so no default fits. */
    @StringRes contentDescription: Int
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
    )
)

class TextPickerSwitchKey(
    text: String,
    to: PickerWindow.Key,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1
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
    )
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
    )
)

class NumPadKey(
    displayText: String,
    val sym: Int,
    textSize: Float = 16f,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal
) : KeyDef(
    Appearance.Text(
        displayText,
        textSize = textSize,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(sym), NumLockState))
    )
)
