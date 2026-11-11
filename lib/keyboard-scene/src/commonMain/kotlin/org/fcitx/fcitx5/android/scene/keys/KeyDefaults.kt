/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.layout.Popup

/**
 * What a layout's key tells its default long press (060-keys-unify): its tap's character ([text]) and [label],
 * whether it types ([typing]: a letter's kind with a label, a mark's role), its `<popup>`, a group's
 * [letters], the first a theme's layout wrote ([themeFirst]: its `swipe-up=`, else a literal `alt=`),
 * and a group key's [place] among the layout's groups (the built-in corners go by it).
 */
data class NodeFacts(
    val text: String? = null,
    /** Its label as written, where it is no binding (注音九键's groups: `ㄅㄉㄚ`). */
    val label: String? = null,
    val typing: Boolean = false,
    val popup: Popup? = null,
    val letters: String? = null,
    val themeFirst: String? = null,
    val place: Int = -1,
)

/**
 * Every key's long press as built in, for the app and the theme maker alike: a pure function of the
 * key's name, the keyboard's variant and the layout's node. The user's changes are laid over it in
 * ime-core (KeyResolver); none of them is read here.
 */
object KeyDefaults {

    /** 九键's symbols above a digit's letters, by digit; 1's typed as they are (D7: its digit a keypad's). */
    val NINE_SYMBOLS: Map<Char, List<String>> = mapOf(
        '1' to listOf("…", "：", "；", "~", ".", "-", "@", "、", "“", "”"),
        '2' to listOf("@", "!", "\""),
        '3' to listOf("+", "-"),
        '4' to listOf("=", "/"),
        '5' to listOf("#", "(", ")"),
        '6' to listOf("\\", "~"),
        '7' to listOf("*"),
        '8' to listOf("?"),
        '9' to listOf(":", "'"),
    )

    /** English 九键's 1: the marks a word wants besides the column's, none full width. */
    val ENGLISH_ONE_SYMBOLS = listOf(":", ";", "\"", "(", ")", "@", "&", "/", "*", "~")

    /** The 14 keys' corners, group by group (digits a keypad's, then marks); the 18's. */
    val CORNERS14 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "~", "!", "@", "?")
    val CORNERS18 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "(", ")", "-", "~", "!", "@", "?", ":")

    /** ， and 。 on every keyboard (D1): ASCII, shown and typed as the punctuation maps them. */
    val COMMA = listOf(".", "?", "!", ":", ";", "\"", "'", "(", ")", "~")
    val PERIOD = listOf(",", "?", "!", ":", ";", "_", "%", "$", "^", "&")

    /** The function keys' long press actions, the same wherever the key is (§5). */
    val FUNCTION_ACTIONS: Map<String, String> = mapOf(
        "symbols" to "quickphrase",
        "numbers" to "unicode",
        "lang" to "ime-picker",
        "shift" to "caps:lock",
        "space" to "space-long-press",
    )

    private val TYPING_ROLES = setOf("comma", "period", "slash", "at", "dot", "domain", "semicolon", "star")

    /** Whether a key types (a bubble, a long press of its character's): as SceneBehavior reads a node. */
    fun typing(role: String?, kind: String?, label: String?, preview: String?): Boolean = when (preview) {
        "on" -> true
        "off" -> false
        else -> role in TYPING_ROLES || (kind == "letter" && !label.isNullOrEmpty())
    }

    /**
     * [id]'s long press on [variant] as its layout's [node] has it, with Shift on where [caps]; null where
     * the key has none (D9: a 注音大千 key).
     */
    fun of(id: KeyId?, variant: KeyboardVariant, node: NodeFacts, caps: Boolean = false): DefaultList? {
        if (id != null && id.isFunction) return function(id.key)
        val made = made(id, variant, node, caps) ?: return null
        return node.themeFirst?.let { made.withFirst(it) } ?: made
    }

    // by the node's popup: none, a nine digit's, a group's (of letters, else 注音's), a list, a preset; no popup, a typing key's preset
    private fun made(id: KeyId?, variant: KeyboardVariant, node: NodeFacts, caps: Boolean): DefaultList? {
        val p = node.popup
        // the 18 keys' `;` on L only where the scheme types it (OwnLongPress.shown)
        val letters = node.letters?.let { if (variant.form == KeyboardVariant.Form.Grouped18 && !variant.semicolon) it.replace(";", "") else it }
        if (letters != null && (p == null || p.from == "group")) return grouped(letters, node.themeFirst ?: corner(variant.form, node.place))
        return when (p?.from) {
            null -> if (node.typing) node.text?.let { preset(it, caps) } else null
            "nine" -> id?.key?.singleOrNull()?.let { nine(it, variant) }
            "group" -> zhuyinGroup(node.label ?: node.text.orEmpty())
            "list" -> listed(p)
            "preset" -> (p.preset ?: node.text)?.let { preset(it, caps) }
            else -> null
        }
    }

    /** A function key's: ， and 。's lists, any other none (its long press an action, [FUNCTION_ACTIONS]). */
    fun function(name: String): DefaultList? = when (name) {
        "comma" -> plain(COMMA)
        "period" -> plain(PERIOD)
        else -> null
    }

    /** A character's (a full keyboard letter's: its digit or mark first), Shift's capital's where [caps]. */
    fun preset(text: String, caps: Boolean = false): DefaultList? {
        val key = if (caps && text.length == 1 && text[0].isLowerCase()) text.uppercase() else text
        return PresetLists.of(key)?.takeIf { it.isNotEmpty() }?.let(::plain)
    }

    /**
     * Digit key [digit]'s on the nine keys: a row of its letters' capitals, the digit, its letters (A B C 2 a b c),
     * the digit first, a keypad's; its symbols above. 1's: the digit, then its marks as they are.
     */
    fun nine(digit: Char, variant: KeyboardVariant): DefaultList {
        val keypad = Item(digit.toString(), Commit.Keypad(digit))
        if (digit == '1') {
            val english = variant.language == KeyboardVariant.Language.English
            val marks = if (english) ENGLISH_ONE_SYMBOLS else NINE_SYMBOLS.getValue('1')
            return DefaultList(listOf(keypad), marks.map { Item(it, Commit.AsIs) }, keypad)
        }
        val letters = variant.nineLetters[digit].orEmpty()
        val row = letters.map { Item(it.uppercase()) } + keypad + letters.lowercase().map { Item(it.toString()) }
        return DefaultList(row, NINE_SYMBOLS[digit].orEmpty().map(::Item), keypad)
    }

    /**
     * [group]'s on the 14 and 18 keys: Q W 1 q w, the [corner] first; above, its letters' built-in symbols
     * but the corner and any letter or digit (I16: never the user's full keyboard's).
     */
    fun grouped(group: String, corner: String?): DefaultList {
        // ;'s capital is :, as Shift gives it: no second ; beside the lower one
        val upper = group.map { if (it == ';') ":" else it.uppercase() }
        val lower = group.map { it.toString() }
        // a digit corner is a keypad's (D7), whether built in or a theme's
        val first = corner?.takeIf { it.isNotEmpty() }?.let { c -> if (c.length == 1 && c[0] in '0'..'9') Item(c, Commit.Keypad(c[0])) else itemOf(c) }
        val symbols = group.flatMap { PresetLists.of(it.toString()).orEmpty() }
            .filter { s -> s != first?.text && s !in upper && s !in lower && s.none(::isLetterOrDigit) }
            .distinct()
        val row = upper.map(::Item) + listOfNotNull(first) + lower.map(::Item)
        // the corner under the finger, else the first lower case letter
        return DefaultList(row, symbols.map(::Item), first, focus = upper.size)
    }

    /** A 注音九键 group's: its zhuyin symbols, typed through the session; no first. */
    fun zhuyinGroup(label: String): DefaultList? =
        label.filter { it in ZHUYIN }.ifEmpty { null }?.map { Item(it.toString()) }?.let { DefaultList(it, emptyList(), null) }

    /** A `<popup from="list">`'s items: an item's `send=` its action, else as-is where the popup says. */
    fun listed(p: Popup): DefaultList? {
        val asIs = p.options["as-is"] == "true"
        val items = p.items.map { (text, send) -> Item(text, if (send != null) Commit.Action(send) else if (asIs) Commit.AsIs else Commit.AsKey) }
        return if (items.isEmpty()) null else DefaultList(emptyList(), items, items.first())
    }

    /** The built-in corner of the [place]-th group of a grouped [form]. */
    fun corner(form: KeyboardVariant.Form, place: Int): String? = when (form) {
        KeyboardVariant.Form.Grouped14 -> CORNERS14.getOrNull(place)
        KeyboardVariant.Form.Grouped18 -> CORNERS18.getOrNull(place)
        else -> null
    }

    /** A written first (`keypad:3`, `action:tone-5`, `~`) as an item. */
    fun itemOf(written: String): Item = when {
        written.startsWith("keypad:") && written.length == "keypad:".length + 1 ->
            written.last().let { Item(it.toString(), Commit.Keypad(it)) }
        written.startsWith("action:") -> Item("", Commit.Action(written))
        else -> Item(written.removePrefix("key:"))
    }

    // a theme's first: the item it names (by its action, its keypad digit or its text), else put first among the symbols
    private fun DefaultList.withFirst(written: String): DefaultList {
        val want = itemOf(written)
        val found = items.firstOrNull { it == want || (want.commit is Commit.Action && it.commit == want.commit) || (want.commit == Commit.AsKey && it.text == want.text) }
        return when {
            found != null -> copy(first = found, focus = items.indexOf(found))
            want.text.isEmpty() -> this
            else -> copy(symbols = listOf(want) + symbols, first = want, focus = row.size)
        }
    }

    // a cased letter (every letter of a Latin key's presets has a case: ê, ß's SS, ı's I) or an ASCII digit: not
    // Char.isLetterOrDigit, whose Unicode tables would grow the theme maker's engine by tens of KB
    private fun isLetterOrDigit(c: Char): Boolean = c in '0'..'9' || c.toString().let { it.lowercase() != it.uppercase() }

    private fun plain(texts: List<String>): DefaultList = texts.map(::Item).let { DefaultList(emptyList(), it, it.first()) }

    private const val ZHUYIN = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦㄧㄨㄩ"
}
