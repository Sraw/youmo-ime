/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant

/**
 * 主题只管外观 (061-custom-layout §19; the user, 2026-11-11: a theme's scope is the look, not the data): what a key does
 * held or swiped is its name's, the app's key of that name's, never what a theme pack's layout writes. That is its
 * long press (`<popup>`, `long-press=`), its corner and first (`alt=`, `swipe-up=`), `swipe-down=`, `double-tap=`, its
 * `<menu>`, and a keyboard's `popups=` ([Held]). [of] puts the app's in place of what a layout writes before anything
 * draws or reads it, so the scene, the default marks, the long press and TalkBack all see the app's. What a key is
 * and types where it is stays the layout's: its place, look, tap (`send=`), group letters, role, name, condition,
 * `repeat=` and `swipe-axis=` (Q10, Q14). A user's layout ([Mode.Own]) keeps what it writes for a name of its own:
 * that name has no other data to follow (Q11). The app's keys' are [BuiltInKeyData], made of its layouts.
 */
object KeyData {
    /** The actions that are what a key does held or swiped; `send=`, `repeat=`, `swipe-axis=` and `preview=` are the layout's. */
    val HELD = setOf("long-press", "swipe-up", "swipe-down", "double-tap")

    /**
     * The app's keyboards whose theme layouts 0.6.1 moves to the layout list (061-custom-layout §11): read as a user's
     * layout is ([Mode.Own]'s rule) until then, so a key named by the theme keeps what it writes all along.
     */
    val LISTED = setOf("qwerty", "k14", "k18", "nine", "number", "symbols")

    /** The order the app's keyboards are looked through for a name neither of a layout's own two has (§19.2); any other after, by id. */
    val ORDER = listOf(
        "qwerty", "k18", "k14", "nine", "number", "symbols", "zhuyin", "zhuyin-nine",
        "emoji", "emoticon", "emoji-search", "expanded-side", "number-row",
    )

    /** What a key does held or swiped, as its layout has it: each null or empty where not written. */
    data class Held(
        val alt: Text? = null,
        val actions: Map<String, String> = emptyMap(),
        val popup: Popup? = null,
        val menu: List<MenuItem> = emptyList(),
    )

    /**
     * One of the app's keyboards: its keys' [Held] by name ([named]: every name it has, of no data too) and, those of
     * none, by id ([unnamed]); its group keys' places by name ([places], the corners go by them); its `popups=`.
     */
    class Board(
        val popups: Boolean,
        val named: Map<String, Held>,
        val unnamed: Map<String, Held> = emptyMap(),
        val places: Map<String, Int> = emptyMap(),
    )

    enum class Mode {
        /** A theme pack's layout: the app's keys' in place of all it writes; a name of its own of no data ([LISTED]'s boards aside). */
        Pack,

        /** A user's layout (061-custom-layout): the app's for the app's names, what it writes for a name of its own. */
        Own,
    }

    /**
     * Something written that is not taken: in the file [file] (the board's id or a fragment's), the key's [line], the
     * attribute or element [what]; [key] the name it was written under (a variant's own, else its key's), else the key's
     * id (a name has a `/`, an id none), so two keys of a line are told apart (L6 review N1).
     */
    data class Ignored(val file: String, val line: Int, val what: String, val key: String = "")

    class Result(val layout: Layout, val fragments: Map<String, Fragment>, val ignored: List<Ignored>)

    private val NONE = Held()

    // a key's held data: the app's (where the key is an app's key, [place] its group's), or what it writes (a name of its own)
    private sealed interface Taken {
        class App(val held: Held, val place: Int?) : Taken

        class Written(val place: Int?) : Taken
    }

    /**
     * [layout], the file of the app's keyboard [id] (`emoticon`, whatever its `<keyboard id>`), and its [fragments], each
     * key's [Held] the app's key's of its name: looked for on the app's [id], then [basedOn] (a user's layout's), then
     * every other in [ORDER]; a key of no name, the one of its id and no name on the app's [id] or [basedOn]. A key the
     * app has none of: no data ([Mode.Pack]) or its own ([Mode.Own], and [LISTED]'s boards). A group key takes the
     * place of the app's key of its name, else none (-1): no corner of the app's ([Key.place]). `<defaults>` and a
     * row's are laid on each key first, so nothing written there reaches a key past it; a `<variant>` of a name of its
     * own looks up that name, any other follows its key. What was written and differs from what is taken is
     * [Result.ignored]: the import's and the theme maker's notice.
     */
    fun of(
        id: String,
        layout: Layout,
        fragments: Map<String, Fragment>,
        mode: Mode,
        basedOn: String? = layout.basedOn,
        app: Map<String, Board> = BuiltInKeyData.BOARDS,
    ): Result {
        val own = listOfNotNull(app[id], basedOn?.let { app[it] })
        val all = (listOfNotNull(id, basedOn) + ORDER + app.keys.sorted()).distinct().mapNotNull { app[it] }
        val r = Reading(own, all, mode == Mode.Own || id in LISTED, DefaultMarks.variantOf(layout, basedOn ?: id).form)
        val parts = layout.parts.map { p -> if (p is Row) r.row(id, p, layout.defaults) else p }
        val frags = fragments.mapValues { (src, f) ->
            Fragment(f.id, f.defaults.holding(NONE), f.rows.map { r.row(src, it, f.defaults.over(layout.defaults)) })
        }
        val popups = own.firstOrNull()?.popups ?: if (r.keepOwn) layout.popups else true
        if (!layout.popups && popups) r.ignored += Ignored(id, 0, "popups")
        return Result(layout.with(layout.defaults.holding(NONE), parts, popups), frags, r.ignored.toList())
    }

    // one layout's reading ([of]): the app's boards a name is looked up on ([all]) and a key of no name ([own]), what is
    // not taken as it is met
    private class Reading(val own: List<Board>, val all: List<Board>, val keepOwn: Boolean, val form: KeyboardVariant.Form) {
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

        fun key(file: String, k: Key, under: KeyAttrs): Key {
            val a = k.attrs.over(under)
            val t = take(a.shortcut, k.id, k.letters != null)
            if (t is Taken.Written) {
                // its own: what it writes and what its <defaults> and row's give it, those being cleared; a variant
                // of a name the app has, the app's
                val variants = k.variants.map { v -> if (v.attrs.shortcut != null) variant(file, v, a.shortcut ?: k.id) else v }
                return k.with(k.attrs.holding(Held(a.alt, held(a.actions))), k.popup, k.menu, variants, t.place)
            }
            t as Taken.App
            // a group key's corner written as the app draws it at its name's place (0.6.0's k18 wrote each,
            // `alt="{punct:(}" swipe-up="("`, as k17-motion does): the same as taken, no notice of it
            val corner = t.place?.takeIf { it >= 0 && t.held.alt == null }?.let { KeyDefaults.corner(form, it) }
            val written = Held(a.alt, held(a.actions), k.popup, k.menu)
            note(file, k.line, a.shortcut ?: k.id, corner?.let { written.besides(it) } ?: written, t.held)
            return k.with(k.attrs.holding(t.held), t.held.popup, t.held.menu, k.variants.map { variant(file, it, a.shortcut ?: k.id) }, t.place)
        }

        // a variant of a name of its own: that name's (its own where the app has none and the layout keeps them); any
        // other its key's ([key]: its name, else its id), so none of its own
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

    /** The app's keyboard [layout] (its [fragments] with it) as [of] looks its keys up: what [BuiltInKeyData] is made of. */
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

    // what is written less an alt= and a swipe-up= that are [corner] (`(`, `{punct:(}`; `(`, `keypad:1`, `commit:(`, `key:(`)
    private fun Held.besides(corner: String): Held = copy(
        alt = alt?.takeUnless { it == Text.Literal(corner) || it == Text.Binding("punct", corner) },
        actions = actions.filterNot { (name, a) -> name == "swipe-up" && a in CORNER_PREFIXES.map { it + corner } },
    )

    private val CORNER_PREFIXES = listOf("", "keypad:", "commit:", "key:")

    // these attributes with [h]'s held data in place of any written: none left to come from what they are laid on
    private fun KeyAttrs.holding(h: Held): KeyAttrs = copy(alt = h.alt, actions = actions.filterKeys { it !in HELD } + h.actions)

    private fun Key.with(attrs: KeyAttrs, popup: Popup?, menu: List<MenuItem>, variants: List<Variant>, place: Int?): Key =
        Key(id, condition, attrs, popup, menu, line, variants, letters, lean, place)
}
