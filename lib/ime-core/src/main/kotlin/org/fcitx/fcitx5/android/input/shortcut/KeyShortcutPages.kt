/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.layout.Key as LayoutKey

/**
 * The 按键字符和快捷键 page's tabs: each keyboard's character keys in its rows, as the keyboard
 * draws them, and the function keys with their two ways; a tab of each layout of the user's in use, made from it
 * ([of], 061-custom-layout §9). A key is its label and its name in [KeyShortcuts].
 */
object KeyShortcutPages {

    data class Key(val label: String, val name: String)

    /**
     * A tab: [form] (a [KeyShortcutPreset] constant, or [KeyShortcuts.FUNCTION]; a layout of the user's `layout:<id>`,
     * [layout]) and its [rows], a row shorter than the rest [leftAligned] as the keyboard has it or centred; a layout's
     * its [title], the layout's name (the app's are the app's strings).
     */
    data class Page(val form: String, val rows: List<List<Key>>, val leftAligned: Boolean = false, val title: String? = null) {
        val names: List<String> get() = rows.flatten().filterNot(::elsewhere).map { it.name }

        /** The id of the layout of the user's this is the tab of; null for the app's. */
        val layout: String? get() = form.takeIf { it.startsWith(LAYOUT) }?.removePrefix(LAYOUT)

        /** Whether [key] is a function key drawn in its place on a keyboard's tab, edited on the function keys' (注音九键's ，). */
        fun elsewhere(key: Key): Boolean = form != KeyShortcuts.FUNCTION && KeyId.of(key.name).isFunction

        /** Whether a key of it has a preset: 套用编辑快捷键 has none to put back otherwise (注音九键, the numbers). */
        val hasPresets: Boolean get() = names.any { KeyShortcutPreset.of(it) != KeyShortcut.None }
    }

    private val FULL_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    // 大千's keys by the QWERTY key each sits on (051-zhuyin §1.3): its symbol shown, its letter its name
    private val ZHUYIN_ROWS = listOf(
        "1234567890-" to "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ",
        "qwertyuiop" to "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ",
        "asdfghjkl;" to "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",
        "zxcvbnm,./" to "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",
    )

    /** The function keys a shortcut goes on, by name, in the page's order. */
    val FUNCTION_KEYS = listOf("space", "backspace", "return", "shift", "comma", "period", "symbols", "numbers", "lang", "retype", "zero", "separator")

    val Full = Page(KeyShortcutPreset.FULL, FULL_ROWS.map { row -> row.map { Key(it.uppercase(), KeyShortcuts.charKey(KeyShortcutPreset.FULL, "$it")) } })

    val Nine = Page(
        KeyShortcutPreset.NINE,
        listOf("123", "456", "789").map { row -> row.map { Key("$it", KeyShortcuts.charKey(KeyShortcutPreset.NINE, "$it")) } },
    )

    val Grouped14 = grouped(KeyShortcutPreset.GROUPED14, NineKeyLetters.G14, intArrayOf(5, 5, 4))

    val Grouped18 = grouped(KeyShortcutPreset.GROUPED18, NineKeyLetters.G18, intArrayOf(7, 6, 5))

    /**
     * 注音九键's keys where the keyboard has them: a tone's in the first column, the 11 groups three a row
     * named by their place (1 to 11), ？ ！ ～ in the last; its ， in place is the function key's ([Page.elsewhere]).
     */
    val ZhuyinNine = Page(
        KeyShortcutPreset.ZHUYIN_NINE,
        ZhuyinKeys.GROUP_SYMBOLS.mapIndexed { g, symbols -> Key(symbols, zy9("${g + 1}")) }.chunked(3).mapIndexed { r, groups ->
            listOf(Key("ˉˊˇˋ"[r].toString(), zy9("tone-${r + 1}"))) + groups +
                listOfNotNull(Key("，", "${KeyShortcuts.FUNCTION}/comma").takeIf { r == 3 }) +
                listOfNotNull(listOf(null, "？" to "question", "！" to "exclam", "～" to "tilde")[r]?.let { (label, name) -> Key(label, zy9(name)) })
        },
        // the first row's backspace left off
        leftAligned = true,
    )

    private fun zy9(key: String) = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_NINE, key)

    val ZhuyinFull = Page(
        KeyShortcutPreset.ZHUYIN_FULL,
        ZHUYIN_ROWS.map { (keys, symbols) -> keys.indices.map { Key("${symbols[it]}", KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_FULL, "${keys[it]}")) } },
    )

    val Number = Page(
        KeyShortcutPreset.NUMBER,
        listOf("123", "456", "789", "0").map { row -> row.map { Key("$it", KeyShortcuts.charKey(KeyShortcutPreset.NUMBER, "$it")) } },
    )

    /** A function key's two names, up then down, under its one [Key.label] (the key's name, labelled by the app). */
    val Function = Page(
        KeyShortcuts.FUNCTION,
        FUNCTION_KEYS.map { key -> listOf(true, false).map { Key(key, KeyShortcuts.functionKey(key, it)) } },
    )

    /** Every tab of the app's in order. */
    val all = listOf(Full, Nine, Grouped14, Grouped18, ZhuyinNine, ZhuyinFull, Number, Function)

    /** A layout of the user's tab's [Page.form] before its id. */
    const val LAYOUT = "layout:"

    /** Every tab: the app's keyboards', then those of [layouts] (the user's in use), the function keys' last. */
    fun all(layouts: List<Page>): List<Page> = all.dropLast(1) + layouts + Function

    // each name of the app's tabs, as its tab labels it
    private val builtInLabels: Map<String, String> by lazy {
        all.filter { it.form != KeyShortcuts.FUNCTION }.flatMap { it.rows.flatten() }.associate { it.name to it.label }
    }

    /**
     * Layout [id]'s tab, [name] its title (061-custom-layout §9, L6): its keys of a character's name (its own rows'
     * and those of what it includes, of [fragments]; a `<variant>` of a name of its own too), each once, a row each of
     * its rows that has one, every `when=` of it: each name it may show set here. A name of the app's is labelled as its
     * tab has it (a group key of other letters by its own, L6 review N5: 18 键's H drawn HJK is HJK), any other by its
     * group's letters, its label as written (a binding's argument) or its id.
     */
    fun of(id: String, name: String, layout: Layout, fragments: Map<String, Fragment>): Page {
        val rows = layout.parts.flatMap { part ->
            when (part) {
                is Row -> listOf(part to layout.defaults)
                is Include -> fragments[part.src]?.let { f -> f.rows.map { it to f.defaults.over(layout.defaults) } }.orEmpty()
            }
        }
        val seen = HashSet<String>()
        val keys = rows.map { (row, defaults) ->
            row.cells.filterIsInstance<LayoutKey>().flatMap { k ->
                val a = k.attrs.over(row.defaults.over(defaults))
                (listOf(a) + k.variants.filter { it.attrs.shortcut != null }.map { it.attrs.over(a) }).map { it to (k.letters != null) }
            }.mapNotNull { (a, group) -> a.shortcut?.takeIf { !KeyId.of(it).isFunction && seen.add(it) }?.let { Key(shown(it, a, group), it) } }
        }
        return Page(LAYOUT + id, keys.filter { it.isNotEmpty() }, title = name)
    }

    // a key of [name] as its tab shows it: as the app's tab of the name does, a [group] key whose letters are not that
    // key's by its own
    private fun shown(name: String, a: KeyAttrs, group: Boolean): String {
        val app = builtInLabels[name] ?: return labelOf(a)
        return if (group && !app.equals(labelOf(a), ignoreCase = true)) labelOf(a) else app
    }

    // a key of a name of its own as its tab shows it: a group's letters, its label (a binding's argument), else its name
    private fun labelOf(a: KeyAttrs): String {
        val shown = when (val l = a.label) {
            is Text.Literal -> l.text
            is Text.Binding -> l.arg?.let { if (l.name in CAPITALS) it.replace(";", "").uppercase() else it }
            null -> null
        }
        return shown?.takeIf { it.isNotBlank() } ?: a.shortcut.orEmpty().substringAfter('/')
    }

    // the bindings a key shows its argument's capitals for (a group's `{group:xy}`, a letter's `{case:a}`)
    private val CAPITALS = setOf("group", "case")

    /** The first of [pages] but [page] (the function keys' none) that has [name] too: the tab it is shared with (Q4). */
    fun sharedWith(page: Page, name: String, pages: List<Page>): Page? =
        pages.firstOrNull { it.form != page.form && it.form != KeyShortcuts.FUNCTION && name in it.names }

    /**
     * How many of [page]'s keys are changed, the ones its tab outlines: a slot set ([slots]) or a long press of the
     * user's ([lists]; a function key's where it has one, ， and 。). A function key counts once for its two ways;
     * a key in its place edited on another tab (注音九键's ，) is that tab's.
     */
    fun edited(page: Page, slots: SwipeSlots, lists: KeyLists): Int = if (page.form == KeyShortcuts.FUNCTION) {
        page.rows.count { row ->
            val name = row.first().label
            slots.isSet(SwipeSlots.functionKey(name)) || (KeyDefaults.function(name) != null && lists[KeyId.function(name)] != null)
        }
    } else {
        page.names.count { slots.isSet(it) || lists[KeyId.of(it)] != null }
    }

    private fun grouped(form: String, letters: NineKeyLetters, sizes: IntArray): Page {
        // a key's name as the keyboard's: its letters, the `;` left off (GroupedKeyboard)
        val groups = letters.tag().split(',').map { it.replace(";", "") }
        var at = 0
        return Page(form, sizes.map { n -> groups.subList(at, at + n).also { at += n }.map { Key(it.uppercase(), KeyShortcuts.charKey(form, it)) } })
    }
}
