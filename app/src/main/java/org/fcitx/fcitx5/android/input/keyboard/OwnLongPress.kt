/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.scene.SceneBehavior.LongPopup
import org.fcitx.fcitx5.android.scene.KeyNode

/**
 * What a key pops up on its keyboard when nothing is set by its name (`key_popups`): the long press its
 * layout gives it ([LongPopup]), made as the keyboards make it (the full keyboard's 按键字符 for a preset,
 * [GroupedKeyboard.longPressOf], [T9Keyboard.ninePopup], [ZhuyinKeyboard.groupPopup]), in the order the
 * settings' editor has it: the one under the finger first, what letting go and the swipe type and the
 * key's small character; empty where the key has none. The 按键字符和快捷键 page starts a key's editor from it.
 */
object OwnLongPress {

    fun of(popup: LongPopup?, node: KeyNode): List<String> = when (popup) {
        null -> emptyList()
        is LongPopup.Preset -> PopupOverridesCache.user.current.longPressOf(popup.label)?.toList().orEmpty()
        is LongPopup.Listed -> popup.items
        // pinyin's grouped keys: no `;` shown (GroupedKeyboard.shown, its semicolon off)
        is LongPopup.Letters -> firstFirst(GroupedKeyboard.longPressOf(popup.letters.replace(";", ""), GroupedKeyboard.cornerOf(node), GroupedKeyboard::symbolsOf))
        is LongPopup.Composed -> composed(popup.from, node)?.let(::firstFirst).orEmpty()
    }

    // a keyboard's own made as it opens: 九键's digits as pinyin's (KeyboardWindow), 注音九键's groups
    private fun composed(from: String, node: KeyNode): KeyDef.Popup.Keyboard? = when (from) {
        "nine" -> {
            val prefs = AppPrefs.getInstance().keyboard
            val symbols = NineKeySymbols.parse(prefs.t9Symbols.getValue())
            val d = node.id.singleOrNull()
            when (d) {
                '1' -> T9Keyboard.onePopup(english = false, symbols)
                in '2'..'9' -> {
                    val letters = NineKeyLetters.parse(prefs.t9Letters.getValue())
                    T9Keyboard.ninePopup(d!!, T9Keyboard.lettersOn(d, letters, english = false, shuangpin = false), symbols.symbols(d))
                }
                else -> null
            }
        }
        "group" -> ZhuyinKeyboard.groupPopup(node.label)
        else -> null
    }

    /** [popup]'s items, the one first under the finger ([KeyDef.Popup.Keyboard.Explicit.inRow]) first. */
    internal fun firstFirst(popup: KeyDef.Popup.Keyboard): List<String> = when (popup) {
        is KeyDef.Popup.Keyboard.Preset -> PopupOverridesCache.user.current.longPressOf(popup.label)?.toList().orEmpty()
        is KeyDef.Popup.Keyboard.Explicit -> popup.items.toList().let { items ->
            if (popup.inRow < 0) items else listOf(items[popup.inRow]) + items.filterIndexed { i, _ -> i != popup.inRow }
        }
    }
}
