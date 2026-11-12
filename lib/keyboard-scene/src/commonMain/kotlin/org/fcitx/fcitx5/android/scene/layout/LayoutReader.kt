/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.rules.KeyNames
import org.fcitx.fcitx5.android.scene.xml.Attributes
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/**
 * A layout file read and checked: what layout-1.xsd says of its words, plus what the XSD cannot (060
 * README: ids unique, an icon key's a11y, a letters board's 26 letters, one fill a row at most is not
 * asked: fills share). What depends on the environment (a row's widths, an include's fragment) is the
 * engine's to check as it expands.
 */
object LayoutReader {
    const val NAMESPACE = "https://youmo-ime/layout/1"

    fun layout(source: String): Parsed<Layout> = when (val xml = Xml.parse(source)) {
        is Parsed.Failed -> xml
        is Parsed.Ok -> layout(xml.value)
    }

    fun fragment(source: String): Parsed<Fragment> = when (val xml = Xml.parse(source)) {
        is Parsed.Failed -> xml
        is Parsed.Ok -> fragment(xml.value)
    }

    /** [root] read; whatever goes wrong reading it is a problem of the file (as [org.fcitx.fcitx5.android.scene.theme.ThemeReader.theme]). */
    @Suppress("TooGenericExceptionCaught")
    fun layout(root: XmlElement): Parsed<Layout> = try {
        readLayout(root)
    } catch (e: Exception) {
        Parsed.Failed(root.line, "the layout could not be read: $e")
    }

    @Suppress("TooGenericExceptionCaught")
    fun fragment(root: XmlElement): Parsed<Fragment> = try {
        readFragment(root)
    } catch (e: Exception) {
        Parsed.Failed(root.line, "the fragment could not be read: $e")
    }

    private fun readLayout(root: XmlElement): Parsed<Layout> {
        val p = Problems()
        if (root.name != "keyboard") return Parsed.Failed(root.line, "the root is <${root.name}>, not <keyboard>")
        val a = Attributes(root, p)
        namespace(a)
        val id = a.required("id")?.also { if (!IDENT.matches(it)) a.problem("id=\"$it\" is not a board name") }
        val name = a.required("name")
        val family = a.required("family")?.also { if (it !in FAMILIES) a.problem("family=\"$it\" is not one of ${FAMILIES.joinToString("|")}") }
        val columns = a.decimal("columns", 0.0, MAX_UNITS, minIncluded = false) ?: DEFAULT_COLUMNS
        a.oneOf("arrange", listOf("rows"))
        val onSplit = when (a.oneOf("on-split", listOf("split", "side", "none"))) {
            "split" -> OnSplit.Split
            "none" -> OnSplit.None
            else -> OnSplit.Side
        }
        val preview = a.oneOf("preview", ON_OFF) != "off"
        val popups = a.oneOf("popups", ON_OFF) != "off"
        a.done()
        val defaults = defaults(root, p)
        val parts = root.children.filter { it.name != "defaults" }.mapNotNull { child ->
            when (child.name) {
                "row" -> row(child, p)
                "include" -> include(child, p)
                else -> null.also { p.add(child.line, "<${child.name}> is not allowed in <keyboard>") }
            }
        }
        if (parts.isEmpty()) p.add(root.line, "<keyboard> has no rows")
        checkKeys(parts.filterIsInstance<Row>(), p)
        if (onSplit == OnSplit.Split) splitSides(parts.filterIsInstance<Row>(), p)
        if (family == "letters") {
            val ids = parts.filterIsInstance<Row>().flatMap { r -> r.cells.mapNotNull { it.id } }.toSet()
            KeyNames.LETTERS.filter { it !in ids }.takeIf { it.isNotEmpty() }
                ?.let { p.add(root.line, "a letters board lacks the keys ${it.joinToString(" ")}") }
        }
        val named = composedNamed(family, parts, p)
        return p.result { Layout(id.orEmpty(), name.orEmpty(), family.orEmpty(), columns, onSplit, defaults, named, preview, popups) }
    }

    /**
     * A nine key's and 注音九键's group's long press is made by its name alone (`<popup from="nine"/"group">`, KeyDefaults):
     * one written with no `shortcut=` (a theme pack's of before 060-keys-unify) named as the app's are, a digit's `t9/<id>`,
     * the groups `zy9/1`… in their order, so it has the long press, the corner and the swipe the user's list gives it
     * (060-s6-review M3); a key named already kept as it is. A name another key has written already is not given
     * again (two keys of one name would share one list, slot and mark): the key is left unnamed, with a warning.
     */
    private fun composedNamed(family: String?, parts: List<Part>, p: Problems): List<Part> {
        if (family != "nine" && family != "zhuyin-nine") return parts
        val written = parts.filterIsInstance<Row>().flatMap { r -> r.cells.mapNotNull { (it as? Key)?.attrs?.shortcut } }.toSet()
        var group = 0
        fun name(k: Key): String? = when (k.popup?.from) {
            "nine" -> k.id.takeIf { family == "nine" && it.length == 1 && it[0] in '1'..'9' }?.let { "t9/$it" }
            "group" -> if (family == "zhuyin-nine" && k.letters == null) "zy9/${++group}" else null
            else -> null
        }
        return parts.map { part ->
            if (part !is Row) return@map part
            val cells = part.cells.map { c ->
                if (c !is Key) return@map c
                val name = name(c)
                when {
                    name == null || c.attrs.shortcut != null -> c
                    name in written -> c.also { p.warn(c.line, "key ${c.id}: $name is another key's shortcut=, left unnamed") }
                    else -> c.named(name)
                }
            }
            part.withCells(cells)
        }
    }

    private fun readFragment(root: XmlElement): Parsed<Fragment> {
        val p = Problems()
        if (root.name != "fragment") return Parsed.Failed(root.line, "the root is <${root.name}>, not <fragment>")
        val a = Attributes(root, p)
        namespace(a)
        val id = a.matching("id", IDENT, "a name") ?: a.required("id")
        a.done()
        val defaults = defaults(root, p)
        val rows = root.children.filter { it.name != "defaults" }.mapNotNull { child ->
            if (child.name == "row") row(child, p) else null.also { p.add(child.line, "<${child.name}> is not allowed in <fragment>") }
        }
        if (rows.isEmpty()) p.add(root.line, "<fragment> has no rows")
        checkKeys(rows, p)
        return p.result { Fragment(id.orEmpty(), defaults, rows) }
    }

    private fun namespace(a: Attributes) {
        if (a.string("xmlns") != NAMESPACE) a.problem("xmlns= must be $NAMESPACE")
    }

    // ids unique within the file, but where each has a when= (the number pad's `-`, at a place a pad):
    // the engine checks those shown are (Expand)
    private fun checkKeys(rows: List<Row>, p: Problems) {
        rows.flatMap { it.cells }.filter { it.id != null }.groupBy { it.id }.forEach { (id, cells) ->
            if (cells.size > 1 && cells.any { it.condition == null }) p.add(cells[1].line, "the id \"$id\" is used twice")
        }
    }

    // `split-side` is the side a board split goes to; one split in halves is on both, which no row can be yet
    private fun splitSides(rows: List<Row>, p: Problems) {
        fun Condition?.asks() = this?.anyOf.orEmpty().any { group -> group.any { it.name == "split-side" } }
        val lines = rows.filter { it.condition.asks() }.map { it.line } + rows.flatMap { r ->
            r.cells.filter { c -> c.condition.asks() || (c is Key && c.variants.any { it.condition.asks() }) }.map { it.line }
        }
        lines.forEach { p.add(it, "split-side on a board split in halves (on-split=\"split\") is not supported yet") }
    }

    private fun defaults(parent: XmlElement, p: Problems): KeyAttrs {
        val all = parent.all("defaults")
        if (all.size > 1) p.add(all[1].line, "<defaults> given twice")
        if (all.isNotEmpty() && parent.children.first() !== all[0]) p.add(all[0].line, "<defaults> must come first")
        val d = all.firstOrNull() ?: return KeyAttrs()
        val a = Attributes(d, p)
        val attrs = look(a).copy(width = width(a))
        a.done()
        return attrs
    }

    private fun row(el: XmlElement, p: Problems): Row {
        val a = Attributes(el, p)
        val id = a.matching("id", IDENT, "a name")
        val indent = a.decimal("indent", 0.0, MAX_UNITS, minIncluded = true) ?: 0.0
        val endIndent = a.decimal("end-indent", 0.0, MAX_UNITS, minIncluded = true) ?: 0.0
        val height = a.decimal("height", 0.0, MAX_RATIO, minIncluded = false) ?: 1.0
        val condition = condition(a)
        val split = if (a.oneOf("split", listOf("halves", "scale")) == "halves") RowSplit.Halves else RowSplit.Scale
        a.done()
        val defaults = defaults(el, p)
        val cells = el.children.filter { it.name != "defaults" }.mapNotNull { child ->
            when (child.name) {
                "key" -> key(child, p)
                "gap" -> gap(child, p)
                "slot" -> slot(child, p)
                else -> null.also { p.add(child.line, "<${child.name}> is not allowed in <row>") }
            }
        }
        if (cells.isEmpty()) p.add(el.line, "<row> has no keys")
        return Row(id, indent, endIndent, height, condition, split, defaults, cells, el.line)
    }

    private fun include(el: XmlElement, p: Problems): Include {
        val a = Attributes(el, p)
        val src = a.matching("src", IDENT, "a fragment's id") ?: a.required("src")
        val condition = condition(a)
        a.done()
        return Include(src.orEmpty(), condition, el.line)
    }

    private fun gap(el: XmlElement, p: Problems): Gap {
        val a = Attributes(el, p)
        val gap = Gap(
            a.matching("id", IDENT, "a name"), width(a) ?: Width.Units(1.0), a.oneOf("look", KINDS), classes(a).orEmpty(),
            condition(a), el.line,
        )
        a.done()
        noChildren(el, p)
        return gap
    }

    private fun slot(el: XmlElement, p: Problems): Slot {
        val a = Attributes(el, p)
        val kind = a.oneOf("kind", SLOT_KINDS) ?: a.required("kind")
        val slot = Slot(
            a.matching("id", IDENT, "a name"), kind.orEmpty(), width(a) ?: Width.Units(1.0), a.positiveInt("rows") ?: 1,
            face(a) ?: Face.Default, a.oneOf("look", KINDS), classes(a).orEmpty(), condition(a), el.line,
        )
        a.done()
        noChildren(el, p)
        return slot
    }

    private fun noChildren(el: XmlElement, p: Problems) = el.children.forEach { p.add(it.line, "<${it.name}> is not allowed in <${el.name}>") }

    private fun classes(a: Attributes): List<String>? = a.matching("class", CLASSES, "class names")?.split(' ')

    private fun key(el: XmlElement, p: Problems): Key {
        val a = Attributes(el, p)
        val id = a.matching("id", KEY_ID, "a key id (1–32 characters, no spaces, not starting view:)") ?: a.required("id")
        val condition = condition(a)
        val attrs = look(a).copy(width = width(a), rows = a.positiveInt("rows"))
        val letters = a.matching("letters", LETTERS, "a group's letters (a–z and ;, rows apart by a space)")?.let(Letters::of)
        val lean = a.oneOf("lean", ON_OFF)?.let { it == "on" }
        a.done()
        letters?.group?.let { g ->
            if (g.length > LETTERS_MAX) a.problem("letters=\"$letters\" has more than $LETTERS_MAX letters")
            if (g.toSet().size != g.length) a.problem("letters=\"$letters\" has a letter twice")
        }
        if (lean != null && letters == null) a.problem("lean= is a group key's (letters=)")
        if (attrs.icon != null && attrs.label == null && attrs.a11y == null) a.problem("an icon key needs a11y= (what a screen reader says)")
        var popup: Popup? = null
        var menu = emptyList<MenuItem>()
        val variants = ArrayList<Variant>()
        for (child in el.children) {
            when (child.name) {
                "popup" -> popup = popup(child, p)
                "menu" -> menu = menu(child, p)
                "variant" -> variant(child, p)?.let(variants::add)
                else -> p.add(child.line, "<${child.name}> is not allowed in <key>")
            }
        }
        return Key(id.orEmpty(), condition, attrs, popup, menu, el.line, variants, letters, lean)
    }

    // a <variant>: a when= and what it changes (no id, no width: the key stays where it is)
    private fun variant(el: XmlElement, p: Problems): Variant? {
        val a = Attributes(el, p)
        val condition = condition(a) ?: null.also { a.required("when") }
        val attrs = look(a)
        a.done()
        noChildren(el, p)
        return condition?.let { Variant(it, attrs, el.line) }
    }

    private fun popup(el: XmlElement, p: Problems): Popup {
        val a = Attributes(el, p)
        val from = a.oneOf("from", POPUP_FROM) ?: "preset"
        val preset = a.matching("preset", KEY_ID, "a key's preset (a key id)")
        val options = POPUP_OPTIONS.mapNotNull { (name, pattern) -> a.matching(name, pattern, "a value of $name")?.let { name to it } }.toMap()
        a.done()
        if (preset != null && from != "preset") a.problem("preset= is only for from=\"preset\"")
        val items = el.children.mapNotNull { item ->
            if (item.name != "item") return@mapNotNull null.also { p.add(item.line, "<${item.name}> is not allowed in <popup>") }
            val ia = Attributes(item, p)
            val text = ia.required("text")
            val send = ia.value("send", "an action", ::action)
            ia.done()
            text?.let { it to send }
        }
        if (from == "list" && items.isEmpty()) a.problem("from=\"list\" with no items")
        if (from != "list" && items.isNotEmpty()) a.problem("items are only for from=\"list\"")
        return Popup(from, preset, items, options)
    }

    private fun menu(el: XmlElement, p: Problems): List<MenuItem> {
        Attributes(el, p).done()
        val items = el.children.mapNotNull { item ->
            if (item.name != "item") return@mapNotNull null.also { p.add(item.line, "<${item.name}> is not allowed in <menu>") }
            val a = Attributes(item, p)
            val label = a.required("label")
            val icon = a.matching("icon", IDENT, "an icon name")
            val action = a.value("do", "an action", ::action) ?: a.required("do")
            a.done()
            if (label != null && action != null) MenuItem(label, icon, action) else null
        }
        if (items.isEmpty()) p.add(el.line, "<menu> has no items")
        return items
    }

    private fun condition(a: Attributes): Condition? {
        val raw = a.string("when") ?: return null
        return when (val c = Condition.parse(raw)) {
            is Parsed.Ok -> c.value
            is Parsed.Failed -> null.also { c.problems.forEach { pr -> a.problem(pr.message) } }
        }
    }

    private fun width(a: Attributes): Width? = a.value("width", "a width (0–${SceneText.n(MAX_UNITS)} or fill)") { raw ->
        if (raw == "fill") Width.Fill else Attributes.decimal(raw)?.takeIf { it in 0.0..MAX_UNITS }?.let { Width.Units(it) }
    }

    private fun face(a: Attributes): Face? = a.value("face", "default|keep|none|special") { raw -> Face.entries.firstOrNull { it.xml == raw } }

    // the look and actions a key and <defaults> share (keyLook, keyActions)
    private fun look(a: Attributes): KeyAttrs = KeyAttrs(
        label = a.value("label", "text or one binding", ::text),
        alt = a.value("alt", "text or one binding ({punct:} of one character)", ::oneMark),
        icon = a.value("icon", "an icon name or binding", ::icon),
        kind = a.oneOf("kind", KINDS),
        role = a.oneOf("role", KeyNames.ROLES),
        classes = classes(a),
        textSize = a.decimal("text-size", 0.0, MAX_TEXT_DP, minIncluded = false),
        compactTextScale = a.decimal("compact-text-scale", 0.0, MAX_RATIO, minIncluded = false),
        bold = a.oneOf("text-style", listOf("normal", "bold"))?.let { it == "bold" },
        face = face(a),
        margin = a.boolean("margin"),
        a11y = a.value("a11y", "text or one binding ({punct:} of one character)", ::oneMark),
        sound = a.oneOf("sound", listOf("standard", "space", "delete", "return")),
        badge = a.value("badge", "an icon name or binding", ::icon),
        actions = ACTIONS.mapNotNull { name -> a.value(name, "an action", ::action)?.let { name to it } }.toMap() +
            listOfNotNull(
                a.oneOf("swipe-axis", listOf("vertical", "both"))?.let { "swipe-axis" to it },
                a.boolean("repeat")?.let { "repeat" to it.toString() },
                // the bubble as it is pressed, where its kind says otherwise: on a function key (注音九键's tones, ? ! ~), off a letter key (its slot)
                a.oneOf("preview", ON_OFF)?.let { "preview" to it },
            ),
        // one name a key (060-keys-unify): a function key's `comma` is `fn/comma`, as the settings store it
        shortcut = a.matching("shortcut", SHORTCUT, "a shortcut name")?.let { KeyId.of(it).name },
        mark = a.boolean("mark"),
        splitAfter = a.oneOf("split", listOf("after"))?.let { true },
    )

    /** [raw] as text: a binding where it is one of the list, as written where it has no braces. */
    fun text(raw: String): Text? = when {
        '{' !in raw && '}' !in raw -> Text.Literal(raw)
        else -> binding(raw)?.takeIf { it.name !in Text.ICONS }
    }

    // a corner or what a screen reader says: one mark of {punct:} (a run of them, 九键 1's `,.?!`, its face's alone; the
    // betas' readers took one, 060-keys-unify review L3)
    private fun oneMark(raw: String): Text? = text(raw)?.takeUnless { it is Text.Binding && it.name == Text.PUNCT && it.arg.orEmpty().length > 1 }

    private fun binding(raw: String): Text.Binding? {
        val m = BINDING.matchEntire(raw) ?: return null
        val name = m.groupValues[1]
        val arg = m.groupValues[2].takeIf { it.isNotEmpty() }
        val ok = when {
            arg == null -> name in Text.PLAIN || name in Text.ICONS
            // marks of the basic plane alone: 1 to 4 counted alike on the JVM (code points) and JS (UTF-16 units)
            name == Text.PUNCT -> PUNCT_ARG.matches(arg) && arg.none { it.isSurrogate() }
            name == Text.GROUP -> GROUP_ARG.matches(arg)
            name in Text.WITH_LETTER -> LETTER_ARG.matches(arg)
            name in Text.WITH_NUMBER -> NUMBER_ARG.matches(arg)
            else -> false
        }
        return if (ok) Text.Binding(name, arg) else null
    }

    private fun icon(raw: String): Text? = when {
        IDENT.matches(raw) -> Text.Literal(raw)
        else -> binding(raw)?.takeIf { it.name in Text.ICONS }
    }

    /** [raw] where it is a word of the action vocabulary (layout-1.xsd's `action`). */
    fun action(raw: String): String? = raw.takeIf { a -> ACTION_PATTERNS.any { it.matches(a) } && (!a.startsWith("code:") || codePoint(a) != null) }

    /**
     * The code point of a `code:U+…` action: a Unicode scalar value, U+10FFFF at most and no surrogate
     * (a half of a pair is no character to send); null for any other.
     */
    fun codePoint(raw: String): Int? = raw.removePrefix("code:").takeIf { it.startsWith("U+") && it.length in CODE_DIGITS }
        ?.substring(2)?.takeIf { d -> d.all { it in '0'..'9' || it in 'A'..'F' } }?.toInt(HEX)
        ?.takeIf { it <= MAX_CODE_POINT && it !in SURROGATES }

    private const val HEX = 16
    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATES = 0xD800..0xDFFF

    // "U+" and 4 to 6 hex digits
    private val CODE_DIGITS = 6..8

    // a board may be 100 columns: the boards but the letters give widths in hundredths of the row (rules C)
    private const val MAX_UNITS = 100.0
    private const val MAX_RATIO = 4.0
    private const val MAX_TEXT_DP = 64.0
    private const val DEFAULT_COLUMNS = 10.0
    private val IDENT = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    // KeyNames': 大千's `/` and the number pad's are keys too (a theme's `zhuyin//` is its own: Selector)
    private val KEY_ID = Regex("(?!view:)\\S{1,32}")
    private val CLASSES = Regex("[a-z][a-z0-9-]*( [a-z][a-z0-9-]*)*")
    // a group of the 14/18 keys' kind: NineKeyLetters.MAX letters at most, ; (双拼's ing) one of them;
    // a row a space apart (060-render-v2 §15: where they sit on the key)
    private const val LETTERS_MAX = 5
    private val LETTERS = Regex("[a-z;]+( [a-z;]+)*")
    private val SHORTCUT = Regex("([a-z0-9-]+/)?\\S{1,32}")
    private val BINDING = Regex("\\{([a-z-]+)(?::([^{}]*))?\\}")
    private val LETTER_ARG = Regex("[a-z;,./'?!~-]")
    private val PUNCT_ARG = Regex("[^\\s{}]{1,4}")
    private val ON_OFF = listOf("on", "off")
    private val POPUP_FROM = listOf("preset", "list", "none", "nine", "group")
    private val NUMBER_ARG = Regex("[0-9]{1,2}")
    private val GROUP_ARG = Regex("[a-z;]{1,2}")
    private val FAMILIES = listOf("letters", "zhuyin", "nine", "zhuyin-nine", "grouped", "number", "symbols", "handwriting", "emoji-search", "expanded-side", "number-row")
    private val KINDS = listOf("letter", "function", "space", "enter")
    private val SLOT_KINDS = listOf("handwriting-pad", "nine-side", "handwriting-candidates", "symbol-categories", "symbol-grid")
    private val ACTIONS = listOf("send", "long-press", "swipe-up", "swipe-down", "double-tap")
    private val BOOLEAN = Regex("true|false|1|0")
    private val COUNT = Regex("[0-9]{1,3}")
    private val POPUP_OPTIONS = listOf(
        "transform-punctuation" to BOOLEAN, "symbols-from" to COUNT, "as-is" to BOOLEAN, "in-row" to COUNT,
        "preview" to Regex(".{1,16}"), "preview-alt" to Regex(".{1,16}"),
    )
    private val ACTION_PATTERNS = listOf(
        // one character, sent to the engine as typed: `:` too (the 18 keys' M swipes it, HostKeyAction(":"))
        Regex("\\S"),
        Regex("(sym|picker|shortcut|reading):[A-Za-z0-9_.-]*"),
        // a layout named; emoji search on or off, as the app has it (KeyAction.EmojiSearchAction)
        Regex("layout:[A-Za-z0-9_.-]+"),
        Regex("emoji-search:(on|off)"),
        // what the app does that is no key: a table of the app's says (060-render-p2 U4): a zhuyin
        // symbol, tone 1–5 (ˉ ˊ ˇ ˋ ˙), 注音九键's slot. A group's code is no action: the key's
        // letters= say it (060-render-v2 §15)
        // the longer name first: Kotlin/JS matches the first alternative that starts a match, then wants it whole
        Regex("action:(zhuyin-slot|zhuyin|tone-[1-5]|page-up|page-down)"),
        Regex("commit:.+"),
        Regex("code:U\\+[0-9A-F]{4,6}"),
        Regex("keypad:[0-9]"),
        Regex("caps(:lock)?"),
        Regex(
            "lang|ime-picker|quickphrase|unicode|panel-back|panel-lock|space-long-press|reading|begin-selection-swipe|" +
                "delete-selection|move-selection|lookup",
        ),
    )
}
