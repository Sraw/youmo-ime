/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.ExpandedRow
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/**
 * A theme pack's qwerty.xml given the app's 分词 in its Shift's place while pinyin or 双拼 is typed (0.6.1, FB12,
 * dev/design/061-theme-separator-migration.md): Shift's `when=` made to yield then, and after it a 分词 key in the same
 * place, as the app's own qwerty has them. Done once to the packs installed before 0.6.1 (ThemeFilesManager), the file
 * itself rewritten: two places of its text changed, every other byte as it was (its comments, a 0.6.0 `<gap look=>`,
 * which LayoutWriter would not keep), so 0.6.0 reads it as before (there `composing` is only a table's).
 */
object PackSeparator {

    sealed interface Rewrite {
        /** The file's new text. */
        class Done(val text: String) : Rewrite

        /** Left as it is, [why]. */
        class Kept(val why: String) : Rewrite
    }

    /** The key put in: the app's 分词's id, which the keyboard tells it by (TextKeyboard.SEPARATOR_KEY). */
    const val ID = "apostrophe"

    // the 分词 key's name as read (`separator` written), whatever key or variant has it
    private val NAME = KeyId.of("separator").name

    // what of Shift's the 分词 key takes, as written on it: where it is and how it looks, so the two in turn fill one place alike
    private val COPIED = listOf("kind", "width", "rows", "split", "margin", "face", "class")

    /**
     * [text] (a pack's qwerty.xml, its byte-order mark gone) with 分词 in each Shift's place while pinyin or 双拼 is typed,
     * or why it is kept. [own]: the pack's file of a fragment's id, [appText] the app's; [app] the app's qwerty and its
     * [appFragments], which the rewritten one must stand for as the original did (PackLayouts.judge). Checked before it
     * is given: it reads, is judged as the original, and drawn in each of composing × table shows in each Shift's place
     * exactly one of Shift and 分词, as wide as Shift was, and no key twice.
     */
    fun addSeparator(
        text: String,
        own: (String) -> String?,
        appText: (String) -> String?,
        app: Layout,
        appFragments: Map<String, Fragment>,
    ): Rewrite {
        val root = when (val x = Xml.parse(text, offsets = true)) {
            is Parsed.Ok -> x.value
            is Parsed.Failed -> return Rewrite.Kept("does not read: ${x.problems.firstOrNull()}")
        }
        val layout = when (val l = LayoutReader.layout(root)) {
            is Parsed.Ok -> l.value
            is Parsed.Failed -> return Rewrite.Kept("does not read: ${l.problems.firstOrNull()}")
        }
        val fragments = fragments(layout, own, appText) ?: return Rewrite.Kept("a fragment it includes does not read")
        PackLayouts.judge("qwerty", layout, fragments, app, appFragments)?.let { return Rewrite.Kept("not drawn: $it") }
        val ownFragments = fragments.filterKeys { own(it) != null }.values
        val rows = layout.parts.filterIsInstance<Row>() + ownFragments.flatMap { it.rows }
        hasSeparator(rows)?.let { return Rewrite.Kept(it) }

        val found = shifts(root, layout)
        found.why?.let { return Rewrite.Kept(it) }
        val separators = found.separators
        for (i in separators.indices) for (j in i + 1 until separators.size) {
            if (!exclusive(separators[i].second, separators[j].second)) return Rewrite.Kept("two Shifts show together while pinyin is typed")
        }

        val rewritten = rewrite(text, separators.map { it.first })
        if (rewritten.encodeToByteArray().size > PackLayouts.MAX_BYTES) return Rewrite.Kept("over ${PackLayouts.MAX_BYTES} bytes rewritten")
        return checked(rewritten, layout, fragments, own, appText, app, appFragments, found.shifts)
    }

    // a Shift and what its and its 分词's `when=` become
    private class Placed(val element: XmlElement, val shift: List<List<Condition.Term>>, val separator: List<List<Condition.Term>>)

    // the Shifts of a file, each 分词 put in with its conditions and its row's (whether two could show together); or why none
    private class Found(
        val shifts: List<Key> = emptyList(),
        val separators: List<Pair<Placed, List<List<Condition.Term>>>> = emptyList(),
        val why: String? = null,
    )

    // each row's keys as read, by the elements they were read of (one a <key>, in order)
    private fun shifts(root: XmlElement, layout: Layout): Found {
        val rowElements = root.all("row")
        val layoutRows = layout.parts.filterIsInstance<Row>()
        if (rowElements.size != layoutRows.size) return Found(why = "its rows not as read")
        val shifts = ArrayList<Key>()
        val separators = ArrayList<Pair<Placed, List<List<Condition.Term>>>>()
        for ((rowElement, row) in rowElements.zip(layoutRows)) {
            val keyElements = rowElement.all("key")
            val keys = row.cells.filterIsInstance<Key>()
            if (keyElements.size != keys.size) return Found(why = "its keys not as read")
            for ((element, key) in keyElements.zip(keys).filter { isShift(it.second) }) {
                shifts += key
                val groups = groups(key.condition)
                val separator = separatorGroups(groups)
                val shift = shiftGroups(groups)
                // Shift there only while pinyin is typed: a when= of nothing is none, and 分词 alone in its place is no Shift's
                if (separator.isNotEmpty() && shift.isEmpty()) return Found(why = "Shift shows only while pinyin is typed")
                if (separator.isNotEmpty()) separators += Placed(element, shift, separator) to product(groups(row.condition), separator)
            }
        }
        return when {
            shifts.isEmpty() -> Found(why = "no Shift")
            separators.isEmpty() -> Found(why = "Shift never shows while pinyin is typed")
            else -> Found(shifts, separators)
        }
    }

    private fun fragments(layout: Layout, own: (String) -> String?, appText: (String) -> String?): Map<String, Fragment>? {
        val out = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val t = own(src) ?: appText(src) ?: return null
            out[src] = (LayoutReader.fragment(t) as? Parsed.Ok)?.value ?: return null
        }
        return out
    }

    private fun isShift(key: Key): Boolean =
        key.attrs.role == "shift" || key.attrs.role == null && key.attrs.actions["send"]?.let { it == "caps" || it == "caps:lock" } == true

    // a 分词 of its own already: a key or a variant of the name, `'` sent while typing, or a key of the id
    private fun hasSeparator(rows: List<Row>): String? {
        for (row in rows) for (cell in row.cells) {
            if (cell.id == ID) return "a key $ID already"
            val key = cell as? Key ?: continue
            if (key.attrs.shortcut == NAME || key.variants.any { it.attrs.shortcut == NAME }) return "a 分词 already"
            val typing = (groups(key.condition) + groups(row.condition)).any { g -> g.any { it.name == COMPOSING && !it.negated } }
            if (key.attrs.actions["send"] == "'" && typing) return "a ' key while typing already"
        }
        return null
    }

    private const val COMPOSING = "composing"
    private const val TABLE = "table"

    private fun groups(c: Condition?): List<List<Condition.Term>> = c?.anyOf ?: listOf(emptyList())

    private fun term(name: String, negated: Boolean) = Condition.Term(name, null, negated)

    // a table's, whichever: `table` or `table=wbx`
    private fun List<Condition.Term>.table() = any { it.name == TABLE && !it.negated }

    // not a table's at all: `!table` alone, not `!table=wbx`
    private fun List<Condition.Term>.noTable() = any { it.name == TABLE && it.negated && it.values == null }

    private fun List<Condition.Term>.has(name: String, negated: Boolean) = any { it.name == name && it.values == null && it.negated == negated }

    private fun List<Condition.Term>.and(t: Condition.Term) = if (t in this) this else this + t

    /** Shift's [groups] and not (composing and not a table's). */
    internal fun shiftGroups(groups: List<List<Condition.Term>>): List<List<Condition.Term>> = groups.flatMap { g ->
        when {
            g.table() || g.has(COMPOSING, negated = true) -> listOf(g)
            // a group that asks composing yields whole while no table's: `G !composing` would never hold
            g.noTable() -> listOfNotNull(g.and(term(COMPOSING, true)).takeUnless { g.has(COMPOSING, negated = false) })
            else -> listOfNotNull(g.and(term(COMPOSING, true)).takeUnless { g.has(COMPOSING, negated = false) }, g.and(term(TABLE, false)))
        }
    }

    /** Shift's [groups] and composing and not a table's: none where Shift never shows then. */
    internal fun separatorGroups(groups: List<List<Condition.Term>>): List<List<Condition.Term>> = groups.mapNotNull { g ->
        // `composing !table` last, as the app's own qwerty writes it
        if (g.table() || g.has(COMPOSING, negated = true)) null else g.filter { it != term(TABLE, true) }.and(term(COMPOSING, false)) + term(TABLE, true)
    }

    // every group of [a] with every one of [b]: the key's when= under its row's
    private fun product(a: List<List<Condition.Term>>, b: List<List<Condition.Term>>) = a.flatMap { x -> b.map { y -> x + y.filter { it !in x } } }

    // never both: each group of one with each of the other has a fact one asks and the other asks not
    internal fun exclusive(a: List<List<Condition.Term>>, b: List<List<Condition.Term>>): Boolean = a.all { x ->
        b.all { y -> x.any { t -> y.any { u -> t.name == u.name && t.values == u.values && t.negated != u.negated } } }
    }

    private fun write(groups: List<List<Condition.Term>>) = Condition(groups).toString()

    // [text] with each Shift's when= changed and its 分词 after it, from the last back so the offsets before hold
    private fun rewrite(text: String, placed: List<Placed>): String {
        val out = StringBuilder(text)
        for (p in placed.sortedByDescending { it.element.offsets!!.from }) {
            val at = p.element.offsets!!
            out.insert(at.to, before(text, at.from) + separator(text, p))
            val value = at.values["when"]
            if (value != null) {
                out.setRange(value.first, value.last + 1, write(p.shift))
            } else {
                out.insert(at.close, " when=\"${write(p.shift)}\"")
            }
        }
        return out.toString()
    }

    // what goes before the 分词: on a line of its own where Shift starts one (that line's break and indent), else a space
    private fun before(text: String, from: Int): String {
        var start = from
        while (start > 0 && text[start - 1] != '\n' && text[start - 1] != '\r') start--
        val indent = text.substring(start, from)
        if (start == 0 || indent.any { it != ' ' && it != '\t' }) return " "
        val newline = when {
            text[start - 1] == '\r' -> "\r"
            start >= 2 && text[start - 2] == '\r' -> "\r\n"
            else -> "\n"
        }
        return newline + indent
    }

    private fun separator(text: String, p: Placed): String {
        val values = p.element.offsets!!.values
        val copied = COPIED.mapNotNull { name -> values[name]?.let { "$name=\"${text.substring(it.first, it.last + 1)}\"" } }
        val attrs = listOf("id=\"$ID\"", "role=\"$ID\"") + copied +
            listOf("label=\"分词\"", "text-size=\"16\"", "a11y=\"a11y_key_separator\"", "send=\"'\"", "shortcut=\"separator\"", "when=\"${write(p.separator)}\"")
        return "<key ${attrs.joinToString(" ")}/>"
    }

    // the rewritten text as it would be drawn: read, judged, and in each of composing × table each Shift's place shows
    // what it did but Shift's 分词 while pinyin is typed, as wide
    internal fun checked(
        rewritten: String,
        original: Layout,
        originalFragments: Map<String, Fragment>,
        own: (String) -> String?,
        appText: (String) -> String?,
        app: Layout,
        appFragments: Map<String, Fragment>,
        shifts: List<Key>,
    ): Rewrite {
        val layout = (LayoutReader.layout(rewritten) as? Parsed.Ok)?.value ?: return Rewrite.Kept("rewritten, does not read")
        val fragments = fragments(layout, own, appText) ?: return Rewrite.Kept("rewritten, a fragment does not read")
        PackLayouts.judge("qwerty", layout, fragments, app, appFragments)?.let { return Rewrite.Kept("rewritten, not drawn: $it") }
        val shiftIds = shifts.map { it.id }.toSet()
        for (composing in listOf(false, true)) for (table in listOf(false, true)) {
            val env = Env(WIDTH, HEIGHT, DENSITY, input = Input(ime = if (table) TABLE else "pinyin", table = if (table) "wbx" else null, composing = composing))
            val before = (Expand.rows(original, originalFragments, env) as? Parsed.Ok)?.value ?: return Rewrite.Kept("not drawn as it is")
            val after = (Expand.rows(layout, fragments, env) as? Parsed.Ok)?.value ?: return Rewrite.Kept("rewritten, not drawn")
            if (!swappedAlone(before, after, if (composing && !table) shiftIds else emptySet())) return Rewrite.Kept("rewritten, a key changes where it is")
        }
        // never given it twice: a start after a kill, or a pack of a later one's (its 分词 is what the next asks first)
        if (hasSeparator(layout.parts.filterIsInstance<Row>()) == null) return Rewrite.Kept("rewritten, would be rewritten again")
        return Rewrite.Done(rewritten)
    }

    // [after] as [before] key by key, as wide, but 分词 where a key of [swapped] was
    private fun swappedAlone(before: List<ExpandedRow>, after: List<ExpandedRow>, swapped: Set<String>): Boolean =
        before.size == after.size && before.zip(after).all { (b, a) ->
            b.items.size == a.items.size && b.items.zip(a.items).all { (x, y) ->
                val xId = (x as? ExpandedKey)?.key?.id
                val yId = (y as? ExpandedKey)?.key?.id
                (if (xId in swapped) yId == ID else yId == xId) && x.width == y.width
            }
        }

    // a phone upright, as the drawing's tests have it: the check asks only what shows, not where
    private const val WIDTH = 1080.0
    private const val HEIGHT = 720.0
    private const val DENSITY = 2.625
}
