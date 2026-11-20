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

/** The 按键字符和快捷键 page's tabs: each keyboard's character keys as drawn, the function keys with their two ways, and a tab of each user layout in use. */
object KeyShortcutPages {

    data class Key(val label: String, val name: String)

    /** [form]: a [KeyShortcutPreset] constant, [KeyShortcuts.FUNCTION], or `layout:<id>`; a short row [leftAligned] or centred. */
    data class Page(val form: String, val rows: List<List<Key>>, val leftAligned: Boolean = false, val title: String? = null) {
        val names: List<String> get() = rows.flatten().filterNot(::elsewhere).map { it.name }

        /** Null for the app's. */
        val layout: String? get() = form.takeIf { it.startsWith(LAYOUT) }?.removePrefix(LAYOUT)

        /** Drawn on a keyboard's tab but edited on the function keys' (注音九键's ，). */
        fun elsewhere(key: Key): Boolean = form != KeyShortcuts.FUNCTION && KeyId.of(key.name).isFunction
    }

    private val FULL_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    // 大千's keys by the QWERTY key each sits on: its symbol shown, its letter its name
    private val ZHUYIN_ROWS = listOf(
        "1234567890-" to "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ",
        "qwertyuiop" to "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ",
        "asdfghjkl;" to "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",
        "zxcvbnm,./" to "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",
    )

    /** In the page's order. */
    val FUNCTION_KEYS = listOf("space", "backspace", "return", "shift", "comma", "period", "symbols", "numbers", "lang", "retype", "zero", "separator")

    val Full = Page(KeyShortcutPreset.FULL, FULL_ROWS.map { row -> row.map { Key(it.uppercase(), KeyShortcuts.charKey(KeyShortcutPreset.FULL, "$it")) } })

    val Nine = Page(
        KeyShortcutPreset.NINE,
        listOf("123", "456", "789").map { row -> row.map { Key("$it", KeyShortcuts.charKey(KeyShortcutPreset.NINE, "$it")) } },
    )

    val Grouped14 = grouped(KeyShortcutPreset.GROUPED14, NineKeyLetters.G14, intArrayOf(5, 5, 4))

    val Grouped18 = grouped(KeyShortcutPreset.GROUPED18, NineKeyLetters.G18, intArrayOf(7, 6, 5))

    /** A tone's in the first column, the 11 groups named 1 to 11, ？ ！ ～ last; its ， is the function key's. */
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

    /** A function key's two names, up then down, under its one [Key.label]. */
    val Function = Page(
        KeyShortcuts.FUNCTION,
        FUNCTION_KEYS.map { key -> listOf(true, false).map { Key(key, KeyShortcuts.functionKey(key, it)) } },
    )

    val all = listOf(Full, Nine, Grouped14, Grouped18, ZhuyinNine, ZhuyinFull, Number, Function)

    const val LAYOUT = "layout:"

    /** The function keys' last. */
    fun all(layouts: List<Page>): List<Page> = all.dropLast(1) + layouts + Function

    // each name of the app's tabs, as its tab labels it
    private val builtInLabels: Map<String, String> by lazy {
        all.filter { it.form != KeyShortcuts.FUNCTION }.flatMap { it.rows.flatten() }.associate { it.name to it.label }
    }

    /**
     * A user layout's tab: its character-named keys (included [fragments] and `<variant>`s too) once each, for every
     * `when=`. An app name is labelled as its tab has it (a group key of other letters by its own: 18 键's H drawn HJK
     * is HJK); any other by its letters, its written label or its id.
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

    private fun shown(name: String, a: KeyAttrs, group: Boolean): String {
        val app = builtInLabels[name] ?: return labelOf(a)
        return if (group && !app.equals(labelOf(a), ignoreCase = true)) labelOf(a) else app
    }

    // a group's letters, its label (a binding's argument), else its name
    private fun labelOf(a: KeyAttrs): String {
        val shown = when (val l = a.label) {
            is Text.Literal -> l.text
            is Text.Binding -> l.arg?.let { if (l.name in CAPITALS) it.replace(";", "").uppercase() else it }
            null -> null
        }
        return shown?.takeIf { it.isNotBlank() } ?: a.shortcut.orEmpty().substringAfter('/')
    }

    // `{group:xy}`, `{case:a}`
    private val CAPITALS = setOf("group", "case")

    /** The tab it is shared with (the function keys' none). */
    fun sharedWith(page: Page, name: String, pages: List<Page>): Page? =
        pages.firstOrNull { it.form != page.form && it.form != KeyShortcuts.FUNCTION && name in it.names }

    /** Keys its tab outlines: a slot set or a user long press; a function key counts once for its two ways. */
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
