/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.scene.SceneBehavior.LongPopup
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.KeyNode

/**
 * A key's long press with nothing set by its name (`key_popups`), and a list set by its name as it opens.
 *
 * [of]: what the key pops up on its keyboard, its layout's long press ([LongPopup]) made as the keyboard
 * makes it (the full keyboard's 按键字符 for a preset, [GroupedKeyboard.longPressOf], [T9Keyboard.ninePopup],
 * [ZhuyinKeyboard.groupPopup]) for the input method whose keyboard the key's tab is ([Variant]), in the
 * settings editor's order: the corner (what the up swipe and letting go type, the key's small character)
 * first. The 按键字符和快捷键 page starts a key's editor from it.
 *
 * [shaped]: a list set by name shown in the shape of the key's own (its row and symbols above, committed
 * as it is where the own is), so an edited list looks as the original did.
 */
object OwnLongPress {

    /** Which input method's keyboard a grouped or nine-key tab is now: the first of pinyin, 双拼, English to use it. */
    internal data class Variant(val im: String, val scheme: ShuangpinScheme)

    internal fun variant(form: String, chosen: ImKeyboards.Chosen, scheme: ShuangpinScheme): Variant {
        val layouts = when (form) {
            KeyShortcutPreset.NINE -> setOf(ImKeyboards.Layout.PINYIN_NINE, ImKeyboards.Layout.SHUANGPIN_NINE, ImKeyboards.Layout.ENGLISH_NINE)
            KeyShortcutPreset.GROUPED18 -> setOf(ImKeyboards.Layout.PINYIN18, ImKeyboards.Layout.SHUANGPIN18)
            KeyShortcutPreset.GROUPED14 -> setOf(ImKeyboards.Layout.PINYIN14, ImKeyboards.Layout.SHUANGPIN14)
            else -> emptySet()
        }
        val im = listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH)
            .firstOrNull { ImKeyboards.layoutOf(it, chosen, scheme) in layouts } ?: InputMethodCatalog.PINYIN
        return Variant(im, scheme)
    }

    fun of(popup: LongPopup?, node: KeyNode): List<String> {
        val form = node.shortcut?.let(KeyShortcuts::formOf).orEmpty()
        val v = variant(form, AppPrefs.getInstance().keyboard.imKeyboards(), EngineState.shuangpinScheme)
        val items = when (popup) {
            null -> emptyList()
            is LongPopup.Preset -> PopupOverridesCache.user.current.longPressOf(popup.label)?.toList().orEmpty()
            is LongPopup.Listed -> popup.items
            is LongPopup.Letters -> firstFirst(GroupedKeyboard.longPressOf(shown(popup.letters, form, v), GroupedKeyboard.cornerOf(node), GroupedKeyboard::symbolsOf))
            is LongPopup.Composed -> composed(popup.from, node, v)?.let(::firstFirst).orEmpty()
        }
        return cornerFirst(items, GroupedKeyboard.cornerOf(node))
    }

    // a group's letters as its keyboard shows them: 18 keys' `;` only on 双拼's under a scheme typing it (KeyboardWindow.nameOf)
    private fun shown(letters: String, form: String, v: Variant): String {
        val semicolon = form != KeyShortcutPreset.GROUPED18 || (v.im == InputMethodCatalog.SHUANGPIN && v.scheme.types(';'))
        return if (semicolon) letters else letters.replace(";", "")
    }

    /** [items] with the key's [corner] first, put there where the popup has it not (九键's 1): the small character is the first. */
    internal fun cornerFirst(items: List<String>, corner: String): List<String> = when {
        items.isEmpty() || corner.isEmpty() || items.first() == corner -> items
        corner in items -> listOf(corner) + (items - corner)
        else -> listOf(corner) + items
    }

    // a keyboard's own made as it opens: 九键's digits as the variant's T9Keyboard (KeyboardWindow), 注音九键's groups
    private fun composed(from: String, node: KeyNode, v: Variant): KeyDef.Popup.Keyboard? = when (from) {
        "nine" -> {
            val prefs = AppPrefs.getInstance().keyboard
            val symbols = NineKeySymbols.parse(prefs.t9Symbols.getValue())
            val english = v.im == InputMethodCatalog.ENGLISH
            val shuangpin = v.im == InputMethodCatalog.SHUANGPIN
            val letters = if (shuangpin) NineKeyLetters.STANDARD else NineKeyLetters.parse(prefs.t9Letters.getValue())
            when (val d = node.id.singleOrNull()) {
                '1' -> T9Keyboard.onePopup(english, symbols)
                in '2'..'9' -> T9Keyboard.ninePopup(d!!, T9Keyboard.lettersOn(d, letters, english, shuangpin), symbols.symbols(d))
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
        // the resolver's, which the keyboards make as they open (S6 drops this file)
        is KeyDef.Popup.Keyboard.Resolved, is KeyDef.Popup.Keyboard.Named -> emptyList()
    }

    /**
     * The list [user] set by name, first first, as a popup in the shape of the key's [own]: committed as it is
     * where the own is (九键's 1); where the own has a row with the key's own under the finger (Q W 1 q w), the
     * first and the row's items following it make the row, the first in its middle, the rest the symbols above.
     */
    fun shaped(user: Array<String>, own: KeyDef.Popup.Keyboard): KeyDef.Popup.Keyboard.Explicit {
        val e = own as? KeyDef.Popup.Keyboard.Explicit ?: return KeyDef.Popup.Keyboard.Explicit(user)
        if (e.asIs || e.inRow < 0 || user.isEmpty()) return KeyDef.Popup.Keyboard.Explicit(user, asIs = e.asIs)
        val row = e.items.take(e.symbolsFrom).toSet()
        var n = 1
        while (n < user.size && user[n] in row) n++
        val half = (n - 1) / 2
        val shapedRow = user.slice(1..half) + user[0] + user.slice(half + 1 until n)
        return KeyDef.Popup.Keyboard.Explicit((shapedRow + user.drop(n)).toTypedArray(), symbolsFrom = n, inRow = half)
    }
}
