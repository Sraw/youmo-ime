/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * One selector of a `<style select>`: `[@scene…] widget[qualifiers] [part]`, weighed as CSS weighs it. `*`/role weighs
 * two classes, so it outranks a kind as theme 3.0's did (KeyStyleResolver). A `\` makes the next character the name's:
 * `zhuyin/\,` is the comma key, `number/\*` the star key (a lone `*` after the slash is every key of the board).
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
    // once: a cascade weighs it for every target it matches
    val specificity: Int = run {
        val a = if (key != null) 1 else 0
        val b = classes.size + roles.size + states.size + scenes.size + (if (starRole != null) 2 else 0) +
            (if (board != null && key == null) 1 else 0)
        val c = 1 + (if (part != null) 1 else 0)
        (a * WEIGHT + b) * WEIGHT + c
    }

    val reserved: String? = when {
        widget in RESERVED_WIDGETS -> widget
        states.any { it in RESERVED_STATES } -> ":" + states.first { it in RESERVED_STATES }
        else -> null
    }

    fun matches(target: Target, scene: Set<String>): Boolean = scene.containsAll(scenes) && selects(target)

    fun selects(target: Target): Boolean =
        widget == target.widget && part == target.part &&
            (board == null || board == target.board || board == target.base) && (key == null || key == target.key) &&
            !(target.slot && board != null && key == null) &&
            (starRole == null || starRole == target.role) && target.classes.containsAll(classes) &&
            roles.all { it == target.role } && target.states.containsAll(states)

    data class Target(
        val widget: String,
        val board: String? = null,
        val key: String? = null,
        val role: String? = null,
        val classes: Set<String> = emptySet(),
        val states: Set<String> = emptySet(),
        val part: String? = null,
        val slot: Boolean = false,
        val base: String? = null,
    )

    companion object {
        private const val WEIGHT = 100

        val SCENES = setOf("compact", "landscape", "floating", "split", "one-handed", "dark", "flat")
        val RESERVED_WIDGETS = emptySet<String>()

        val BAR_WIDGETS = setOf("bar", "candidate", "candidates-divider", "candidates-panel", "preedit", "toolbar")

        val WINDOW_WIDGETS = setOf("frame", "handle", "side-button", "split-gap", "navbar")

        val PANEL_WIDGETS = setOf("clipboard", "picker", "editing", "voice")
        val WIDGETS = setOf("keyboard", "popup", "preview", "menu", "panel", "key") + BAR_WIDGETS + WINDOW_WIDGETS + PANEL_WIDGETS + RESERVED_WIDGETS
        val PARTS = setOf("label", "mark", "shortcut", "root", "icon", "hint", "badge")

        val PANEL_PARTS = mapOf("picker" to setOf("categories"), "voice" to setOf("mic"))

        val CANDIDATE_PARTS = setOf("label", "comment")

        val RESERVED_STATES = emptySet<String>()
        val STATES = setOf("pressed", "locked", "shifted", "disabled", "selected", "on", "first") + RESERVED_STATES

        val FIELDS = setOf("password", "url", "number", "email")

        /** Held to the app's SceneTyping by its test; another is read, warned of, selects nothing. */
        val IMS = setOf("pinyin", "shuangpin", "english", "table", "wubi", "cangjie", "ziranma", "erbi", "wubipinyin")
        private val IM = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val BOARD = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val CLASS = Regex("[a-z][a-z0-9-]*")
        private val ROLE = Regex("[a-z-]+")

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

        private fun typed(scene: String): Boolean {
            val value = scene.substringAfter('=', "")
            return when (scene.substringBefore('=')) {
                "im" -> IM.matches(value)
                "field" -> value in FIELDS
                "keyboard" -> BOARD.matches(value)
                else -> false
            }
        }

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

        // its first character is always its own (zhuyin/.)
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
