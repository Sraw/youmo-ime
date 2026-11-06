/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
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

    fun layout(root: XmlElement): Parsed<Layout> {
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
        if (family == "letters") {
            val ids = parts.filterIsInstance<Row>().flatMap { r -> r.cells.mapNotNull { it.id } }.toSet()
            KeyNames.LETTERS.filter { it !in ids }.takeIf { it.isNotEmpty() }
                ?.let { p.add(root.line, "a letters board lacks the keys ${it.joinToString(" ")}") }
        }
        return p.result { Layout(id.orEmpty(), name.orEmpty(), family.orEmpty(), columns, onSplit, defaults, parts) }
    }

    fun fragment(root: XmlElement): Parsed<Fragment> {
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

    // ids unique within the file (keys of exclusive conditions have their own: lookup, lookup-on)
    private fun checkKeys(rows: List<Row>, p: Problems) {
        val seen = HashSet<String>()
        for (cell in rows.flatMap { it.cells }) {
            val id = cell.id ?: continue
            if (!seen.add(id)) p.add(cell.line, "the id \"$id\" is used twice")
        }
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
        val gap = Gap(a.matching("id", IDENT, "a name"), width(a) ?: Width.Units(1.0), condition(a), el.line)
        a.done()
        return gap
    }

    private fun slot(el: XmlElement, p: Problems): Slot {
        val a = Attributes(el, p)
        val kind = a.oneOf("kind", SLOT_KINDS) ?: a.required("kind")
        val slot = Slot(
            a.matching("id", IDENT, "a name"), kind.orEmpty(), width(a) ?: Width.Units(1.0), a.positiveInt("rows") ?: 1,
            face(a) ?: Face.Default, condition(a), el.line,
        )
        a.done()
        return slot
    }

    private fun key(el: XmlElement, p: Problems): Key {
        val a = Attributes(el, p)
        val id = a.matching("id", KEY_ID, "a key id (no spaces, no /)") ?: a.required("id")
        val condition = condition(a)
        val attrs = look(a).copy(width = width(a), rows = a.positiveInt("rows"))
        a.done()
        if (attrs.rows != null && attrs.rows > 1) a.problem("rows=\"${attrs.rows}\": keys across rows are not laid out yet (P2)")
        if (attrs.icon != null && attrs.label == null && attrs.a11y == null) a.problem("an icon key needs a11y= (what a screen reader says)")
        var popup: Popup? = null
        var menu = emptyList<MenuItem>()
        for (child in el.children) {
            when (child.name) {
                "popup" -> popup = popup(child, p)
                "menu" -> menu = menu(child, p)
                else -> p.add(child.line, "<${child.name}> is not allowed in <key>")
            }
        }
        return Key(id.orEmpty(), condition, attrs, popup, menu, el.line)
    }

    private fun popup(el: XmlElement, p: Problems): Popup {
        val a = Attributes(el, p)
        val from = a.oneOf("from", listOf("preset", "list", "none")) ?: "preset"
        val preset = a.string("preset")
        val options = POPUP_OPTIONS.mapNotNull { name -> a.string(name)?.let { name to it } }.toMap()
        a.done()
        val items = el.children.mapNotNull { item ->
            if (item.name != "item") return@mapNotNull null.also { p.add(item.line, "<${item.name}> is not allowed in <popup>") }
            val ia = Attributes(item, p)
            val text = ia.required("text")
            val send = ia.value("send", "an action", ::action)
            ia.done()
            text?.let { it to send }
        }
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

    private fun width(a: Attributes): Width? = a.value("width", "a width (0–$MAX_UNITS or fill)") { raw ->
        if (raw == "fill") Width.Fill else raw.toDoubleOrNull()?.takeIf { it in 0.0..MAX_UNITS }?.let { Width.Units(it) }
    }

    private fun face(a: Attributes): Face? = a.value("face", "default|keep|none|special") { raw -> Face.entries.firstOrNull { it.xml == raw } }

    // the look and actions a key and <defaults> share (keyLook, keyActions)
    private fun look(a: Attributes): KeyAttrs = KeyAttrs(
        label = a.value("label", "text or one binding", ::text),
        alt = a.value("alt", "text or one binding", ::text),
        icon = a.value("icon", "an icon name or binding", ::icon),
        kind = a.oneOf("kind", KINDS),
        role = a.oneOf("role", KeyNames.ROLES),
        classes = a.matching("class", CLASSES, "class names")?.split(' '),
        textSize = a.decimal("text-size", 0.0, MAX_TEXT_DP, minIncluded = false),
        compactTextScale = a.decimal("compact-text-scale", 0.0, MAX_RATIO, minIncluded = false),
        bold = a.oneOf("text-style", listOf("normal", "bold"))?.let { it == "bold" },
        face = face(a),
        margin = a.boolean("margin"),
        a11y = a.string("a11y")?.let { raw -> binding(raw) ?: Text.Literal(raw) },
        sound = a.oneOf("sound", listOf("standard", "space", "delete", "return")),
        badge = a.value("badge", "an icon name or binding", ::icon),
        actions = ACTIONS.mapNotNull { name -> a.value(name, "an action", ::action)?.let { name to it } }.toMap() +
            listOfNotNull(
                a.oneOf("swipe-axis", listOf("vertical", "both"))?.let { "swipe-axis" to it },
                a.boolean("repeat")?.let { "repeat" to it.toString() },
            ),
        shortcut = a.matching("shortcut", SHORTCUT, "a shortcut name"),
        mark = a.boolean("mark"),
        splitAfter = a.oneOf("split", listOf("after"))?.let { true },
    )

    /** [raw] as text: a binding where it is one of the list, as written where it has no braces. */
    fun text(raw: String): Text? = when {
        '{' !in raw && '}' !in raw -> Text.Literal(raw)
        else -> binding(raw)?.takeIf { it.name !in Text.ICONS }
    }

    private fun binding(raw: String): Text.Binding? {
        val m = BINDING.matchEntire(raw) ?: return null
        val name = m.groupValues[1]
        val arg = m.groupValues[2].takeIf { it.isNotEmpty() }
        val ok = when {
            arg == null -> name in Text.PLAIN || name in Text.ICONS
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
    fun action(raw: String): String? = raw.takeIf { a -> ACTION_PATTERNS.any { it.matches(a) } }

    private const val MAX_UNITS = 20.0
    private const val MAX_RATIO = 4.0
    private const val MAX_TEXT_DP = 64.0
    private const val DEFAULT_COLUMNS = 10.0
    private val IDENT = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val KEY_ID = Regex("[^\\s/]+")
    private val CLASSES = Regex("[a-z][a-z0-9-]*( [a-z][a-z0-9-]*)*")
    private val SHORTCUT = Regex("([a-z0-9-]+/)?[^\\s/]+")
    private val BINDING = Regex("\\{([a-z-]+)(?::([^{}]*))?\\}")
    private val LETTER_ARG = Regex("[a-z;,./'?!~-]")
    private val NUMBER_ARG = Regex("[0-9]{1,2}")
    private val FAMILIES = listOf("letters", "zhuyin", "nine", "zhuyin-nine", "grouped", "number", "symbols", "handwriting")
    private val KINDS = listOf("letter", "function", "space", "enter")
    private val SLOT_KINDS = listOf("handwriting-pad", "nine-side", "handwriting-candidates")
    private val ACTIONS = listOf("send", "long-press", "swipe-up", "swipe-down", "double-tap")
    private val POPUP_OPTIONS = listOf("transform-punctuation", "symbols-from", "as-is", "in-row", "preview", "preview-alt")
    private val ACTION_PATTERNS = listOf(
        Regex("[^\\s:]|[A-Za-z0-9]"),
        Regex("(sym|layout|picker|shortcut|reading|emoji-search|action):[A-Za-z0-9_.-]*"),
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
