/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.Popup

/** What a layout's key tells its default long press. */
data class NodeFacts(
    val text: String? = null,
    val label: String? = null,
    val typing: Boolean = false,
    val popup: Popup? = null,
    val letters: String? = null,
    val layoutFirst: String? = null,
    val place: Int = -1,
    val function: Boolean = false,
    val markOff: Boolean = false,
)

/** Every key's long press as built in: the user's changes are laid over it in ime-core (KeyResolver), none read here. */
object KeyDefaults {

    /** Written as the punctuation maps them from, as ，'s are: shown and typed as Chinese punctuation has them. */
    val NINE_SYMBOLS: Map<Char, List<String>> = mapOf(
        '1' to listOf("^", ":", ";", "~", ".", "-", "@", "\\", "\""),
        '2' to listOf("@", "!", "\""),
        '3' to listOf("+", "-"),
        '4' to listOf("=", "/"),
        '5' to listOf("#", "(", ")"),
        '6' to listOf("\\", "~"),
        '7' to listOf("*"),
        '8' to listOf("?"),
        '9' to listOf(":", "'"),
    )

    val CORNERS14 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "~", "!", "@", "?")
    val CORNERS18 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "(", ")", "-", "~", "!", "@", "?", ":")

    val COMMA = listOf(".", "?", "!", ":", ";", "\"", "'", "(", ")", "~")
    val PERIOD = listOf(",", "?", "!", ":", ";", "_", "%", "$", "^", "&")

    val FUNCTION_ACTIONS: Map<String, String> = mapOf(
        "symbols" to "quickphrase",
        "numbers" to "unicode",
        "lang" to "ime-picker",
        "shift" to "caps:lock",
        "space" to "space-long-press",
    )

    private val TYPING_ROLES = setOf("comma", "period", "slash", "at", "dot", "domain", "semicolon", "star")

    fun typing(role: String?, kind: String?, label: String?, preview: String?): Boolean = when (preview) {
        "on" -> true
        "off" -> false
        else -> role in TYPING_ROLES || (kind == "letter" && !label.isNullOrEmpty())
    }

    fun of(id: KeyId?, variant: KeyboardVariant, node: NodeFacts, caps: Boolean = false): DefaultList? {
        if (id != null && id.isFunction) return function(id.key)
        // a first written on a key of no list: that first alone; a `<popup from="none"/>` written is none whatever else is
        val made = made(id, variant, node, caps)
            ?: return node.layoutFirst?.takeIf { node.popup?.from != "none" }?.let { NO_LIST.withFirst(it) }?.takeIf { it.first != null }
        return node.layoutFirst?.let { made.withFirst(it) } ?: made
    }

    private fun made(id: KeyId?, variant: KeyboardVariant, node: NodeFacts, caps: Boolean): DefaultList? {
        val p = node.popup
        val letters = node.letters?.let { if (variant.form == KeyboardVariant.Form.Grouped18 && !variant.semicolon) it.replace(";", "") else it }
        if (letters != null && (p == null || p.from == "group")) return grouped(letters, node.layoutFirst?.takeIf { itemOf(it).text.isNotEmpty() } ?: corner(variant.form, node.place))
        return when (p?.from) {
            null -> typed(node, caps)
            "nine" -> id?.key?.singleOrNull()?.let { nine(it, variant) }
            "group" -> zhuyinGroup(node.label ?: node.text.orEmpty())
            "list" -> listed(p)
            "preset" -> (p.preset ?: node.text)?.let { preset(it, caps) }
            else -> null
        }
    }

    private fun typed(node: NodeFacts, caps: Boolean): DefaultList? {
        val list = node.text?.takeIf { node.typing }?.let { preset(it, caps) } ?: return null
        return if (node.function) DefaultList(list.row, list.symbols, null) else list
    }

    fun function(name: String): DefaultList? = when (name) {
        "comma" -> plain(COMMA)
        "period" -> plain(PERIOD)
        else -> null
    }

    fun preset(text: String, caps: Boolean = false): DefaultList? {
        val key = if (caps && text.length == 1 && text[0].isLowerCase()) text.uppercase() else text
        return PresetLists.of(key)?.takeIf { it.isNotEmpty() }?.let(::plain)
    }

    fun nine(digit: Char, variant: KeyboardVariant): DefaultList {
        val keypad = Item(digit.toString(), Commit.Keypad(digit))
        if (digit == '1') return DefaultList(listOf(keypad), NINE_SYMBOLS.getValue('1').map(::Item), keypad)
        val letters = variant.nineLetters[digit].orEmpty()
        val row = letters.map { Item(it.uppercase()) } + keypad + letters.lowercase().map { Item(it.toString()) }
        return DefaultList(row, NINE_SYMBOLS[digit].orEmpty().map(::Item), keypad)
    }

    /** Never the user's full keyboard's symbols. */
    fun grouped(group: String, corner: String?): DefaultList {
        // ;'s capital is :, as Shift gives it: no second ; beside the lower one
        val upper = group.map { if (it == ';') ":" else it.uppercase() }
        val lower = group.map { it.toString() }
        val first = corner?.takeIf { it.isNotEmpty() }?.let { c -> if (c.length == 1 && c[0] in '0'..'9') Item(c, Commit.Keypad(c[0])) else itemOf(c) }
        val symbols = group.flatMap { PresetLists.of(it.toString()).orEmpty() }
            .filter { s -> s != first?.text && s !in upper && s !in lower && s.none(::isLetterOrDigit) }
            .distinct()
        val row = upper.map(::Item) + listOfNotNull(first) + lower.map(::Item)
        return DefaultList(row, symbols.map(::Item), first, focus = upper.size)
    }

    fun zhuyinGroup(label: String): DefaultList? =
        label.filter { it in ZHUYIN }.ifEmpty { null }?.map { Item(it.toString()) }?.let { DefaultList(it, emptyList(), null) }

    fun listed(p: Popup): DefaultList? {
        val asIs = p.options["as-is"] == "true"
        val items = p.items.map { (text, send) -> Item(text, if (send != null) Commit.Action(send) else if (asIs) Commit.AsIs else Commit.AsKey) }
        return if (items.isEmpty()) null else DefaultList(emptyList(), items, items.first())
    }

    fun corner(form: KeyboardVariant.Form, place: Int): String? = when (form) {
        KeyboardVariant.Form.Grouped14 -> CORNERS14.getOrNull(place)
        KeyboardVariant.Form.Grouped18 -> CORNERS18.getOrNull(place)
        else -> null
    }

    fun itemOf(written: String): Item = when {
        written.startsWith("keypad:") && written.length == "keypad:".length + 1 ->
            written.last().let { Item(it.toString(), Commit.Keypad(it)) }
        written.startsWith("commit:") -> Item(written.removePrefix("commit:"), Commit.AsIs)
        written.startsWith("key:") -> Item(written.removePrefix("key:"))
        written.startsWith("code:") -> LayoutReader.codePoint(written)?.let { Item(text(it)) } ?: Item("", Commit.Action(written))
        ACTION.matches(written) -> Item("", Commit.Action(written))
        // a bare word is an action as the reader reads it, never its letters typed
        written == "caps" || written in LayoutReader.WORDS -> Item("", Commit.Action(written))
        else -> Item(written)
    }

    private val ACTION = Regex("[a-z-]+:.+")

    private fun text(cp: Int): String {
        if (cp < SUPPLEMENTARY) return cp.toChar().toString()
        val v = cp - SUPPLEMENTARY
        return charArrayOf((HIGH_SURROGATE + (v shr TEN_BITS)).toChar(), (LOW_SURROGATE + (v and LOW_MASK)).toChar()).concatToString()
    }

    private const val SUPPLEMENTARY = 0x10000
    private const val HIGH_SURROGATE = 0xD800
    private const val LOW_SURROGATE = 0xDC00
    private const val TEN_BITS = 10
    private const val LOW_MASK = 0x3FF

    private fun DefaultList.withFirst(written: String): DefaultList {
        val want = itemOf(written)
        val found = items.firstOrNull { it == want || (want.commit is Commit.Action && it.commit == want.commit) || (textual(want) && it.text == want.text) }
        return when {
            found != null -> copy(first = found, focus = items.indexOf(found))
            want.text.isEmpty() -> this
            else -> copy(symbols = listOf(want) + symbols, first = want, focus = row.size)
        }
    }

    private fun textual(i: Item): Boolean = i.commit == Commit.AsKey || i.commit == Commit.AsIs

    // not Char.isLetterOrDigit, whose Unicode tables would grow the theme maker's engine by tens of KB
    private fun isLetterOrDigit(c: Char): Boolean = c in '0'..'9' || c.toString().let { it.lowercase() != it.uppercase() }

    private val NO_LIST = DefaultList(emptyList(), emptyList(), null)

    private fun plain(texts: List<String>): DefaultList = texts.map(::Item).let { DefaultList(emptyList(), it, it.first()) }

    private const val ZHUYIN = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦㄧㄨㄩ"
}
