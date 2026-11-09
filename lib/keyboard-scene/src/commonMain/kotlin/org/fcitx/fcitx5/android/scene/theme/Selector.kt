/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * One selector of a `<style select>` (theme-4.xsd's grammar): `[@scene…] widget[qualifiers] [part]`.
 * A key is selected as `key`, by name (`qwerty/q`), by its role on every board (`*`/enter), or every
 * key of a board (`qwerty/` then a star); then `.class` (its kind is one), `[role=…]`, `:state`.
 * As in CSS, a selector's qualifiers all hold (`.function[role=enter]:pressed`: each of them) and a
 * `select=` list's selectors any (`key.enter, key.space`).
 *
 * A key's name may be any characters ([KeyNames]: 大千's `zhuyin//`, the number pad's `number/+`): its
 * first is always the name's, and as in CSS a `\` makes the one after it the name's whatever it is,
 * so `zhuyin/\,` is the comma key (not two selectors), `number/\*` the star key (a lone `*` after the slash is every
 * key of the board), `\\` a backslash.
 *
 * How specific it is, as CSS weighs it ([specificity]): a key's own name like an id; a class, a role,
 * a state, a scene each like a class, a board's every key one too, `*`/role two (so it outranks a kind,
 * `.enter`, as theme 3.0's `*`/role did its kind: KeyStyleResolver); the widget and the part like
 * elements. Ties go to the later style.
 */
data class Selector(
    val scenes: Set<String>,
    val widget: String,
    val board: String? = null,
    val key: String? = null,
    val starRole: String? = null,
    val classes: Set<String> = emptySet(),
    val roles: Set<String> = emptySet(),
    val states: Set<String> = emptySet(),
    val part: String? = null,
) {
    /** (ids, classes, elements), compared in that order. */
    // once: a cascade weighs it for every target it matches
    val specificity: Int = run {
        val a = if (key != null) 1 else 0
        val b = classes.size + roles.size + states.size + scenes.size + (if (starRole != null) 2 else 0) +
            (if (board != null && key == null) 1 else 0)
        val c = 1 + (if (part != null) 1 else 0)
        (a * WEIGHT + b) * WEIGHT + c
    }

    /**
     * What of it a later stage makes work ([RESERVED_WIDGETS], [RESERVED_STATES]), null where none:
     * read, but it selects nothing yet.
     */
    val reserved: String? = when {
        widget in RESERVED_WIDGETS -> widget
        states.any { it in RESERVED_STATES } -> ":" + states.first { it in RESERVED_STATES }
        else -> null
    }

    /** Whether it selects [target] in [scene] (the scenes the environment is in). */
    fun matches(target: Target, scene: Set<String>): Boolean = scene.containsAll(scenes) && selects(target)

    /** Whether it selects [target], its scenes known to hold. */
    fun selects(target: Target): Boolean =
        widget == target.widget && part == target.part &&
            (board == null || board == target.board) && (key == null || key == target.key) &&
            !(target.slot && board != null && key == null) &&
            (starRole == null || starRole == target.role) && target.classes.containsAll(classes) &&
            roles.all { it == target.role } && target.states.containsAll(states)

    /** What a style may select: a widget, or a key's [part], with its names, classes and states. */
    data class Target(
        val widget: String,
        val board: String? = null,
        val key: String? = null,
        val role: String? = null,
        val classes: Set<String> = emptySet(),
        val states: Set<String> = emptySet(),
        val part: String? = null,
        /** A slot's face (the handwriting pad's): not one of its board's every key, only by its name or look. */
        val slot: Boolean = false,
    )

    companion object {
        private const val WEIGHT = 100

        val SCENES = setOf("compact", "landscape", "floating", "split", "one-handed", "dark", "flat")
        /** Widgets of a later stage of 0.6.0 (060-render-p3: T6b), read but selecting nothing yet. */
        val RESERVED_WIDGETS = setOf("preedit", "toolbar")

        /**
         * The bar and the candidates, which the app's views draw (060-render-p3 T6a:
         * [org.fcitx.fcitx5.android.scene.engine.BarLook]): the bar's base, a candidate and its parts,
         * the lines between candidates, the expanded candidates' panel.
         */
        val BAR_WIDGETS = setOf("bar", "candidate", "candidates-divider", "candidates-panel")

        /** The keyboard's window round its keys (060-render-p3 T7a: [org.fcitx.fcitx5.android.scene.engine.WindowLooks], `split-gap` the scene's). */
        val WINDOW_WIDGETS = setOf("frame", "handle", "side-button", "split-gap", "navbar")

        /** The app's own panels over the keyboard (060-render-p3 T7b: [org.fcitx.fcitx5.android.scene.engine.PanelLooks]). */
        val PANEL_WIDGETS = setOf("clipboard", "picker", "editing", "voice")
        val WIDGETS = setOf("keyboard", "popup", "preview", "menu", "panel", "key") + BAR_WIDGETS + WINDOW_WIDGETS + PANEL_WIDGETS + RESERVED_WIDGETS
        val PARTS = setOf("label", "mark", "shortcut", "root", "icon", "hint", "badge")

        /**
         * A panel's parts (T7b): the picker's bar of categories (`picker categories`; the one shown is
         * `picker:selected`), the voice mask's microphone (`voice mic`: the bubble its sound shows in).
         */
        val PANEL_PARTS = mapOf("picker" to setOf("categories"), "voice" to setOf("mic"))

        /** A candidate's parts (T6a): its number and its comment (a comment, a reading over its words). */
        val CANDIDATE_PARTS = setOf("label", "comment")

        /** States of a later stage: none left since T6a made the first candidate's (`:first`) work. */
        val RESERVED_STATES = emptySet<String>()
        val STATES = setOf("pressed", "locked", "shifted", "disabled", "selected", "on", "first") + RESERVED_STATES

        /**
         * The scenes of what is typed ([org.fcitx.fcitx5.android.scene.engine.Env.scenes]): `@im=<input
         * method>` (its kind, `pinyin` `shuangpin` `english` `table` `handwriting`, or a table's name, `wubi`),
         * `@field=<kind>` of these, `@keyboard=<board>` (a layout's id, `nine`).
         */
        val FIELDS = setOf("password", "url", "number", "email")

        /**
         * The words of `@im=` that select (the app's SceneTyping, held to it by its test): the kinds, T9 and
         * zhuyin `pinyin` as their engine is, and the tables' names. Another is read, warned of, selects nothing.
         */
        val IMS = setOf("pinyin", "shuangpin", "english", "table", "handwriting", "wubi", "cangjie", "ziranma", "erbi", "wubipinyin")
        private val IM = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val BOARD = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val CLASS = Regex("[a-z][a-z0-9-]*")
        private val ROLE = Regex("[a-z-]+")

        /** A `select=` read: its selectors, split at each comma not escaped; null where one is not of the grammar. */
        fun parseList(text: String): List<Selector>? {
            val chars = chars(text) ?: return null
            return chars.splitAt(',').map { parse(it) ?: return null }.takeIf { it.isNotEmpty() }
        }

        fun parse(text: String): Selector? = chars(text)?.let(::parse)

        private fun parse(chars: List<Ch>): Selector? {
            val words = chars.splitAt(' ').filter { it.isNotEmpty() }
            val scenes = words.takeWhile { it[0].plain('@') }.map { it.plain()?.drop(1) ?: return null }
            if (scenes.any { it !in SCENES && !typed(it) }) return null
            val rest = words.drop(scenes.size)
            if (rest.isEmpty() || rest.size > 2) return null
            val head = head(rest[0]) ?: return null
            val parts = when (head.widget) {
                "candidate" -> CANDIDATE_PARTS
                "key" -> PARTS
                else -> PANEL_PARTS[head.widget] ?: emptySet()
            }
            val part = rest.getOrNull(1)?.let { it.plain()?.takeIf(parts::contains) ?: return null }
            return head.copy(scenes = scenes.toSet(), part = part)
        }

        // `im=wubi`, `field=password`, `keyboard=nine`
        private fun typed(scene: String): Boolean {
            val value = scene.substringAfter('=', "")
            return when (scene.substringBefore('=')) {
                "im" -> IM.matches(value)
                "field" -> value in FIELDS
                "keyboard" -> BOARD.matches(value)
                else -> false
            }
        }

        // the widget or key name, then its qualifiers; but a key's name, nothing escaped
        private fun head(word: List<Ch>): Selector? {
            val slash = word.indexOfFirst { it.plain('/') }
            if (slash < 0) {
                val plain = word.plain() ?: return null
                val i = plain.indexOfFirst { it == '.' || it == '[' || it == ':' }.let { if (it < 0) plain.length else it }
                val sel = Selector(emptySet(), plain.substring(0, i)).takeIf { it.widget in WIDGETS } ?: return null
                return qualifiers(sel, plain.substring(i))
            }
            val end = nameEnd(word, slash + 1)
            val board = word.subList(0, slash).plain() ?: return null
            val name = word.subList(slash + 1, end)
            if (name.isEmpty()) return null
            val key = name.joinToString("") { it.c.toString() }
            val sel = when {
                board == "*" -> Selector(emptySet(), "key", starRole = name.plain()?.takeIf(ROLE::matches) ?: return null)
                !BOARD.matches(board) -> return null
                name.size == 1 && name[0].plain('*') -> Selector(emptySet(), "key", board = board)
                else -> Selector(emptySet(), "key", board = board, key = key)
            }
            return qualifiers(sel, word.subList(end, word.size).plain() ?: return null)
        }

        // a key's name runs to its first qualifier not escaped; its first character is always its own (zhuyin/.)
        private fun nameEnd(word: List<Ch>, start: Int): Int {
            var i = start + 1
            while (i < word.size) {
                val c = word[i]
                val qualifier = c.plain('.') && word.getOrNull(i + 1)?.c?.isLetter() == true
                if (c.plain('[') || c.plain(':') || qualifier) return i
                i++
            }
            return minOf(i, word.size)
        }

        // a selector's characters, `\x` read as x escaped; null where it ends in a lone `\`
        private fun chars(text: String): List<Ch>? {
            val out = ArrayList<Ch>(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '\\') {
                    if (i + 1 == text.length) return null
                    out += Ch(text[i + 1], true)
                    i += 2
                } else {
                    out += Ch(text[i], false)
                    i++
                }
            }
            return out
        }

        // split at each [at] not escaped, each piece's spaces at its ends (not escaped) dropped
        private fun List<Ch>.splitAt(at: Char): List<List<Ch>> {
            val out = ArrayList<List<Ch>>()
            var from = 0
            for (i in 0..size) {
                if (i == size || this[i].plain(at)) {
                    out += subList(from, i).dropWhile { it.plain(' ') }.dropLastWhile { it.plain(' ') }
                    from = i + 1
                }
            }
            return out
        }

        // as text, where nothing is escaped
        private fun List<Ch>.plain(): String? = if (any { it.escaped }) null else joinToString("") { it.c.toString() }

        private class Ch(val c: Char, val escaped: Boolean) {
            fun plain(char: Char): Boolean = !escaped && c == char
        }

        private fun qualifiers(base: Selector, q: String): Selector? {
            var sel = base
            var rest = q
            while (rest.isNotEmpty()) {
                val end = rest.drop(1).indexOfFirst { it == '.' || it == '[' || it == ':' }.let { if (it < 0) rest.length else it + 1 }
                val token = rest.substring(0, end)
                rest = rest.substring(end)
                sel = when {
                    token.startsWith(".") && CLASS.matches(token.drop(1)) -> sel.copy(classes = sel.classes + token.drop(1))
                    token.startsWith(":") && token.drop(1) in STATES -> sel.copy(states = sel.states + token.drop(1))
                    token.startsWith("[role=") && token.endsWith("]") && ROLE.matches(token.drop(6).dropLast(1)) ->
                        sel.copy(roles = sel.roles + token.drop(6).dropLast(1))
                    else -> return null
                }
            }
            if (sel.widget != "key" && (sel.classes.isNotEmpty() || sel.roles.isNotEmpty())) return null
            if ("first" in sel.states && sel.widget != "candidate") return null
            return sel
        }
    }
}
