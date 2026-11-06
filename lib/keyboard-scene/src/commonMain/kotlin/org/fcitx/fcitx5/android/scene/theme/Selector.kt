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
    val specificity: Int
        get() {
            val a = if (key != null) 1 else 0
            val b = classes.size + roles.size + states.size + scenes.size + (if (starRole != null) 2 else 0) +
                (if (board != null && key == null) 1 else 0)
            val c = 1 + (if (part != null) 1 else 0)
            return (a * WEIGHT + b) * WEIGHT + c
        }

    /** Whether it selects [target] in [scene] (the scenes the environment is in). */
    fun matches(target: Target, scene: Set<String>): Boolean =
        widget == target.widget && part == target.part && scene.containsAll(scenes) &&
            (board == null || board == target.board) && (key == null || key == target.key) &&
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
    )

    companion object {
        private const val WEIGHT = 100

        val SCENES = setOf("compact", "landscape", "floating", "split", "one-handed", "dark", "flat")
        val WIDGETS = setOf("keyboard", "bar", "candidate", "popup", "preview", "menu", "handle", "panel", "key")
        val PARTS = setOf("label", "mark", "shortcut", "root", "icon", "hint", "badge")
        val STATES = setOf("pressed", "locked", "shifted", "disabled", "selected", "on")
        private val BOARD = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val CLASS = Regex("[a-z][a-z0-9-]*")
        private val ROLE = Regex("[a-z-]+")

        /** A `select=` read: its comma-separated selectors, or null where one is not of the grammar. */
        fun parseList(text: String): List<Selector>? =
            text.split(',').map { parse(it.trim()) ?: return null }.takeIf { it.isNotEmpty() }

        fun parse(text: String): Selector? {
            val words = text.split(' ').filter { it.isNotEmpty() }
            val scenes = words.takeWhile { it.startsWith("@") }
            if (scenes.any { it.drop(1) !in SCENES }) return null
            val rest = words.drop(scenes.size)
            if (rest.isEmpty() || rest.size > 2) return null
            val part = rest.getOrNull(1)?.also { if (it !in PARTS) return null }
            val head = head(rest[0]) ?: return null
            if (part != null && head.widget != "key") return null
            return head.copy(scenes = scenes.map { it.drop(1) }.toSet(), part = part)
        }

        // the widget or key name, then its qualifiers
        private fun head(word: String): Selector? {
            var i = word.indexOfFirst { it == '.' || it == '[' || it == ':' }.let { if (it < 0) word.length else it }
            var sel = Selector(emptySet(), word.substring(0, i))
            if ('/' in word) {
                val slash = word.indexOf('/')
                i = nameEnd(word, slash + 1)
                val board = word.substring(0, slash)
                val key = word.substring(slash + 1, i)
                if (key.isEmpty()) return null
                sel = when {
                    board == "*" -> Selector(emptySet(), "key", starRole = key.takeIf(ROLE::matches) ?: return null)
                    !BOARD.matches(board) -> return null
                    key == "*" -> Selector(emptySet(), "key", board = board)
                    else -> Selector(emptySet(), "key", board = board, key = key)
                }
            } else if (sel.widget !in WIDGETS) {
                return null
            }
            return qualifiers(sel, word.substring(i))
        }

        // a key's name runs to its first qualifier; its first character is always its own (zhuyin/.)
        private fun nameEnd(word: String, start: Int): Int {
            var i = start + 1
            while (i < word.length) {
                val c = word[i]
                val qualifier = c == '.' && word.getOrNull(i + 1)?.isLetter() == true
                if (c == '[' || c == ':' || qualifier) return i
                i++
            }
            return minOf(i, word.length)
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
            return sel
        }
    }
}
