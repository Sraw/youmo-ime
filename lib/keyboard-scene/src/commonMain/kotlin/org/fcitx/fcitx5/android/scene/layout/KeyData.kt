/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant

/**
 * A theme's scope is the look, not the data: what a key does held or swiped is the app's key of its name's, never what
 * a theme pack's layout writes. [of] puts the app's in place before anything draws or reads it, so the scene, the
 * marks, the long press and TalkBack all see the app's. A user's layout keeps what it writes for a name of its own.
 */
object KeyData {
    val HELD = setOf("long-press", "swipe-up", "swipe-down", "double-tap")

    /** Where one is still read as a pack's board (the web page), it is read as a user's layout is. */
    val LISTED = setOf("qwerty", "k14", "k18", "nine", "number", "symbols")

    val ORDER = listOf(
        "qwerty", "k18", "k14", "nine", "number", "symbols", "zhuyin", "zhuyin-nine",
        "emoji", "emoticon", "emoji-search", "expanded-side", "number-row",
    )

    data class Held(
        val alt: Text? = null,
        val actions: Map<String, String> = emptyMap(),
        val popup: Popup? = null,
        val menu: List<MenuItem> = emptyList(),
    )

    class Board(
        val popups: Boolean,
        val named: Map<String, Held>,
        val unnamed: Map<String, Held> = emptyMap(),
        val places: Map<String, Int> = emptyMap(),
    )

    enum class Mode {
        Pack,

        Own,
    }

    /** [key] the name it was written under, else the key's id, so two keys of a line are told apart. */
    data class Ignored(val file: String, val line: Int, val what: String, val key: String = "")

    class Result(val layout: Layout, val fragments: Map<String, Fragment>, val ignored: List<Ignored>)

    private val NONE = Held()

    private const val NUMBER = "number"

    // a 0.6.2 board's sign keys still do nothing held or swiped, as then, though the app's board has them no more
    private fun Board.withLegacy() = Board(popups, named, LegacyNumberKeys.SENDS.keys.associateWith { NONE } + unnamed, places)

    private sealed interface Taken {
        class App(val held: Held, val place: Int?) : Taken

        class Written(val place: Int?) : Taken
    }

    /**
     * `<defaults>` are laid on each key first, so nothing written there reaches a key past it. A group key's corner is
     * judged by [shape], its own family's board, not [basedOn]'s; a user's layout's caller passes its baseId.
     */
    fun of(
        id: String,
        layout: Layout,
        fragments: Map<String, Fragment>,
        mode: Mode,
        basedOn: String? = layout.basedOn,
        app: Map<String, Board> = BuiltInKeyData.BOARDS,
        shape: String = basedOn ?: id,
    ): Result {
        val own = listOfNotNull(id, basedOn).mapNotNull { n -> app[n]?.let { if (n == NUMBER) it.withLegacy() else it } }
        val all = (listOfNotNull(id, basedOn) + ORDER + app.keys.sorted()).distinct().mapNotNull { app[it] }
        val rows = layout.parts.filterIsInstance<Row>().map { it to layout.defaults } +
            fragments.values.flatMap { f -> f.rows.map { it to f.defaults.over(layout.defaults) } }
        val looksUp = rows.any { (row, under) -> row.cells.any { it is Key && it.attrs.over(row.defaults.over(under)).looksUp() } }
        val r = Reading(own, all, mode == Mode.Own || id in LISTED, DefaultMarks.variantOf(layout, shape).form, looksUp)
        val parts = layout.parts.map { p -> if (p is Row) r.row(id, p, layout.defaults) else p }
        val frags = fragments.mapValues { (src, f) ->
            Fragment(f.id, f.defaults.holding(NONE), f.rows.map { r.row(src, it, f.defaults.over(layout.defaults)) })
        }
        val popups = own.firstOrNull()?.popups ?: if (r.keepOwn) layout.popups else true
        if (!layout.popups && popups) r.ignored += Ignored(id, 0, "popups")
        return Result(layout.with(layout.defaults.holding(NONE), parts, popups), frags, r.ignored.toList())
    }

    private class Reading(
        val own: List<Board>,
        val all: List<Board>,
        val keepOwn: Boolean,
        val form: KeyboardVariant.Form,
        val looksUp: Boolean,
    ) {
        val ignored = LinkedHashSet<Ignored>()

        fun take(name: String?, keyId: String?, group: Boolean): Taken {
            if (name != null) {
                for (b in all) b.named[name]?.let { return Taken.App(it, if (group) b.places[name] ?: -1 else null) }
            } else if (keyId != null) {
                for (b in own) b.unnamed[keyId]?.let { return Taken.App(it, if (group) -1 else null) }
            }
            val place = if (group) -1 else null
            return if (keepOwn) Taken.Written(place) else Taken.App(NONE, place)
        }

        fun note(file: String, line: Int, key: String, written: Held, taken: Held) {
            if (written.alt != null && written.alt != taken.alt) ignored += Ignored(file, line, "alt", key)
            for ((name, action) in written.actions) if (action != taken.actions[name]) ignored += Ignored(file, line, name, key)
            if (written.popup != null && written.popup != taken.popup) ignored += Ignored(file, line, "popup", key)
            if (written.menu.isNotEmpty() && written.menu != taken.menu) ignored += Ignored(file, line, "menu", key)
        }

        fun key(file: String, written: Key, under: KeyAttrs): Key {
            val a = written.attrs.over(under)
            val k = lookUpMoved(written, a, looksUp)
            val t = take(a.shortcut, k.id, k.letters != null)
            if (t is Taken.Written) {
                val variants = k.variants.map { v -> if (v.attrs.shortcut != null) variant(file, v, a.shortcut ?: k.id) else v }
                return k.with(k.attrs.holding(Held(a.alt, held(a.actions))), k.popup, k.menu, variants, t.place)
            }
            t as Taken.App
            // a corner written as the app draws it at its name's place (0.6.0's k18 wrote each): no notice of it
            val corner = t.place?.takeIf { it >= 0 && t.held.alt == null }?.let { KeyDefaults.corner(form, it) }
            val written = Held(a.alt, held(a.actions), k.popup, k.menu)
            note(file, k.line, a.shortcut ?: k.id, corner?.let { written.besides(it) } ?: written, t.held)
            return k.with(k.attrs.holding(t.held), t.held.popup, t.held.menu, k.variants.map { variant(file, it, a.shortcut ?: k.id) }, t.place)
        }

        fun variant(file: String, v: Variant, key: String): Variant {
            val t = v.attrs.shortcut?.let { take(it, null, false) } ?: Taken.App(NONE, null)
            if (t is Taken.Written) return v
            val taken = (t as Taken.App).held
            note(file, v.line, v.attrs.shortcut ?: key, Held(v.attrs.alt, held(v.attrs.actions)), taken)
            return Variant(v.condition, v.attrs.holding(taken), v.line)
        }

        fun row(file: String, r: Row, under: KeyAttrs): Row {
            val rowUnder = r.defaults.over(under)
            val cells = r.cells.map { c -> if (c is Key) key(file, c, rowUnder) else c }
            return Row(r.id, r.indent, r.endIndent, r.height, r.condition, r.split, r.defaults.holding(NONE), cells, r.line)
        }
    }

    fun board(layout: Layout, fragments: Map<String, Fragment>): Board {
        val named = LinkedHashMap<String, Held>()
        val unnamed = LinkedHashMap<String, Held>()
        val rows = layout.parts.flatMap { part ->
            when (part) {
                is Row -> listOf(part to layout.defaults)
                is Include -> fragments[part.src]?.let { f -> f.rows.map { it to f.defaults.over(layout.defaults) } }.orEmpty()
            }
        }
        for ((row, under) in rows) for (cell in row.cells) {
            val k = cell as? Key ?: continue
            val a = k.attrs.over(row.defaults.over(under))
            val h = Held(a.alt, held(a.actions), k.popup, k.menu)
            if (a.shortcut != null) named.getOrPut(a.shortcut) { h } else unnamed.getOrPut(k.id) { h }
            for (v in k.variants) v.attrs.shortcut?.let { named.getOrPut(it) { v.attrs.over(a).let { va -> h.copy(alt = va.alt, actions = held(va.actions)) } } }
        }
        val places = DefaultMarks.nodes(layout, fragments).filter { it.second.place >= 0 }.associate { it.first to it.second.place }
        return Board(layout.popups, named, unnamed, places)
    }

    private fun held(actions: Map<String, String>) = actions.filterKeys { it in HELD }

    private const val LOOK_UP = "code:U+F0104"
    private const val TABLE_LOOKUP = "table-lookup"
    private val ON_TABLE = Condition.Term("table", null, false)
    private val OFF_TABLE = Condition.Term("table", null, true)

    private fun KeyAttrs.looksUp() = role == "lookup" || role == "lookup-on" || actions["send"] == LOOK_UP

    private fun KeyAttrs.shifts() = role == "shift" || role == null && actions["send"].let { it == "caps" || it == "caps:lock" }

    /**
     * Which tables have 拼 is the engine's (二笔, 仓颉, 自然码; 五笔 looks up behind z), not a layout's: one written before
     * `table-lookup` shows 拼 where it would do nothing, and its Shift, off every table, comes back where 拼 goes.
     */
    internal fun lookUpMoved(k: Key, a: KeyAttrs, looksUp: Boolean): Key {
        val groups = k.condition?.anyOf ?: listOf(emptyList())
        if (groups.any { g -> g.any { it.name == TABLE_LOOKUP } }) return k
        val moved = when {
            // after `table`, as the app's own qwerty writes it
            a.looksUp() -> groups.map { g -> (g.indexOf(ON_TABLE) + 1).let { at -> g.take(at) + Condition.Term(TABLE_LOOKUP, null, false) + g.drop(at) } }
            looksUp && a.shifts() && k.condition != null && groups.all { OFF_TABLE in it } ->
                groups + groups.map { g -> g.filter { it.name != "table" && it.name != "composing" } + ON_TABLE + Condition.Term(TABLE_LOOKUP, null, true) }.distinct()
            else -> return k
        }
        return Key(k.id, Condition(moved), k.attrs, k.popup, k.menu, k.line, k.variants, k.letters, k.lean, k.place)
    }

    private fun Held.besides(corner: String): Held = copy(
        alt = alt?.takeUnless { it == Text.Literal(corner) || it == Text.Binding("punct", corner) },
        actions = actions.filterNot { (name, a) -> name == "swipe-up" && a in CORNER_PREFIXES.map { it + corner } },
    )

    private val CORNER_PREFIXES = listOf("", "keypad:", "commit:", "key:")

    // none left to come from what they are laid on
    private fun KeyAttrs.holding(h: Held): KeyAttrs = copy(alt = h.alt, actions = actions.filterKeys { it !in HELD } + h.actions)

    private fun Key.with(attrs: KeyAttrs, popup: Popup?, menu: List<MenuItem>, variants: List<Variant>, place: Int?): Key =
        Key(id, condition, attrs, popup, menu, line, variants, letters, lean, place)
}
