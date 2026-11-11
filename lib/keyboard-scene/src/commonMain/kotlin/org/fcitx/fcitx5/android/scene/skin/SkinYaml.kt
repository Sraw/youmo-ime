/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * The part of YAML that Trime's themes are written in (dev/design/060-skin-formats.md §4.1), read as plain data:
 * block mappings and sequences by indentation, flow `{…}` and `[…]` (over lines too), quoted and plain scalars,
 * `|` and `>` blocks, comments, anchors and aliases, `<<` merges. Every scalar is a [String] (`0xFF6699`, `4`, `true`
 * alike: the reader takes them as it needs them); a mapping a [Map] in its order, a sequence a [List]; `~` and `null`
 * null. No tags are acted on, no other file is read. Lenient: a line it cannot place is skipped, never an error; held
 * to [MAX_DEPTH] (deeper is dropped). An alias is the anchored value itself, never a copy, so a chain of aliases costs
 * nothing.
 */
object SkinYaml {
    const val MAX_DEPTH = 64

    fun parse(text: String): Any? = Parser(text).document()

    private class Line(val indent: Int, val text: String, val raw: String)

    private class Parser(text: String) {
        private val lines = ArrayList<Line>()
        private var i = 0
        private val anchors = HashMap<String, Any?>()

        init {
            for (raw in text.lineSequence()) {
                val noTabs = raw.replace('\t', ' ')
                val indent = noTabs.length - noTabs.trimStart(' ').length
                val content = stripComment(noTabs.substring(indent)).trimEnd()
                val document = indent == 0 && (content.startsWith("---") || content.startsWith("...") || content.startsWith("%"))
                if (content.isNotEmpty() && !document) lines += Line(indent, content, noTabs)
            }
        }

        fun document(): Any? = if (lines.isEmpty()) null else node(0, 0)

        private fun node(min: Int, depth: Int): Any? {
            if (i >= lines.size || lines[i].indent < min) return null
            val l = lines[i]
            if (depth > MAX_DEPTH) {
                skip(l.indent)
                return null
            }
            return when {
                seq(l.text) -> sequence(l.indent, depth)
                key(l.text) != null -> mapping(l.indent, depth)
                else -> {
                    i++
                    value(l.text, l.indent, depth)
                }
            }
        }

        // the lines at [indent] and under: a block too deep to read
        private fun skip(indent: Int) {
            i++
            while (i < lines.size && lines[i].indent > indent) i++
        }

        private fun seq(t: String) = t == "-" || t.startsWith("- ")

        private fun sequence(ind: Int, depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (true) {
                val l = at(ind)?.takeIf { seq(it.text) } ?: break
                val rest = l.text.substring(1).trimStart()
                if (rest.isEmpty()) {
                    i++
                    out += node(ind + 1, depth + 1)
                } else {
                    // the item's own text read as if it began its line: `- a: 1` then `  b: 2` is one mapping
                    val at = ind + l.text.length - rest.length
                    lines[i] = Line(at, rest, l.raw)
                    out += node(at, depth + 1)
                }
            }
            return out
        }

        private fun mapping(ind: Int, depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                val l = at(ind)?.takeIf { !seq(it.text) } ?: break
                i++
                key(l.text)?.let { (k, rest) ->
                    val v = if (rest.isEmpty()) child(ind, depth) else value(rest, ind, depth)
                    if (k == "<<") merge(out, v) else out[k] = v
                }
            }
            return out
        }

        // the next line at [ind], those deeper before it (a line it cannot place, a plain scalar over lines) stepped over
        private fun at(ind: Int): Line? {
            while (i < lines.size && lines[i].indent > ind) i++
            return lines.getOrNull(i)?.takeIf { it.indent == ind }
        }

        private fun merge(into: MutableMap<String, Any?>, v: Any?) {
            val maps = if (v is List<*>) v else listOf(v)
            for (m in maps) (m as? Map<*, *>)?.forEach { (k, x) -> if (k is String && k !in into) into[k] = x }
        }

        // a key's value on the lines under it: deeper, or a sequence at the key's own indent
        private fun child(ind: Int, depth: Int): Any? {
            val next = lines.getOrNull(i) ?: return null
            return when {
                next.indent > ind -> node(next.indent, depth + 1)
                next.indent == ind && seq(next.text) -> sequence(ind, depth + 1)
                else -> null
            }
        }

        private fun value(text: String, ind: Int, depth: Int): Any? {
            // the anchors (`&a`) and tags (`!t`) before it, stepped over in a loop: a line of thousands is no
            // deeper a call, nor the line's rest copied for each
            var p = 0
            val names = ArrayList<String>()
            while (true) {
                while (p < text.length && text[p] == ' ') p++
                if (p >= text.length || (text[p] != '&' && text[p] != '!')) break
                val end = text.indexOf(' ', p).let { if (it < 0) text.length else it }
                if (text[p] == '&') names += text.substring(p + 1, end)
                p = end
            }
            val t = text.substring(p).trim()
            val v = when {
                t.isEmpty() && p > 0 -> child(ind, depth)
                t.startsWith("*") -> anchors[t.substring(1).trim()]
                BLOCK.matches(t) -> block(ind, folded = t[0] == '>')
                t.startsWith("{") || t.startsWith("[") -> Flow(flowText(t), anchors).value(depth)
                else -> scalar(t)
            }
            for (name in names) anchors[name] = v
            return v
        }

        // a `|` or `>` block: the deeper lines' text, as they stand under the first one
        private fun block(ind: Int, folded: Boolean): String {
            val parts = ArrayList<String>()
            var cut = -1
            while (i < lines.size && lines[i].indent > ind) {
                val raw = lines[i++].raw
                if (cut < 0) cut = raw.length - raw.trimStart(' ').length
                parts += raw.drop(cut).trimEnd()
            }
            return parts.joinToString(if (folded) " " else "\n")
        }

        // a flow collection over as many lines as its brackets take (within MAX_FLOW_LINES)
        private fun flowText(first: String): String {
            val sb = StringBuilder(first)
            var n = 0
            while (!balanced(sb) && i < lines.size && n++ < MAX_FLOW_LINES) sb.append(' ').append(lines[i++].text)
            return sb.toString()
        }

        private fun balanced(s: CharSequence): Boolean {
            var open = 0
            var quote = ' '
            var escaped = false
            for (c in s) {
                when {
                    escaped -> escaped = false
                    quote == '"' && c == '\\' -> escaped = true
                    quote != ' ' -> if (c == quote) quote = ' '
                    c == '"' || c == '\'' -> quote = c
                    c == '{' || c == '[' -> open++
                    c == '}' || c == ']' -> open--
                }
            }
            return open <= 0
        }
    }

    // a flow collection: {a: b, c: [d, e]}
    private class Flow(private val s: String, private val anchors: MutableMap<String, Any?>) {
        private var p = 0

        fun value(depth: Int): Any? {
            ws()
            if (p >= s.length) return null
            if (depth > MAX_DEPTH) {
                p = s.length
                return null
            }
            return when (s[p]) {
                '{' -> map(depth)
                '[' -> list(depth)
                '"', '\'' -> quoted()
                '*' -> {
                    p++
                    anchors[plain(key = false)]
                }
                '&' -> {
                    // a run of anchors taken in a loop, as a block value's are
                    val names = ArrayList<String>()
                    while (p < s.length && s[p] == '&') {
                        val from = ++p
                        while (p < s.length && s[p] != ' ' && s[p] !in FLOW_ENDS) p++
                        names += s.substring(from, p)
                        ws()
                    }
                    value(depth).also { v -> names.forEach { anchors[it] = v } }
                }
                else -> plain(key = false).let(::typed)
            }
        }

        private fun map(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            p++
            while (true) {
                ws()
                if (p >= s.length) return out
                if (s[p] == '}') {
                    p++
                    return out
                }
                if (s[p] == ',') {
                    p++
                    continue
                }
                val at = p
                val k = if (s[p] == '"' || s[p] == '\'') quoted() else plain(key = true)
                ws()
                val v = if (p < s.length && s[p] == ':') {
                    p++
                    ws()
                    if (p < s.length && (s[p] == ',' || s[p] == '}')) null else value(depth + 1)
                } else {
                    null
                }
                if (k == "<<") (v as? Map<*, *>)?.forEach { (a, b) -> if (a is String && a !in out) out[a] = b } else out[k] = v
                // a stray ']' where a key was to be: stepped over, never read again
                if (p == at) p++
            }
        }

        private fun list(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            p++
            while (true) {
                ws()
                if (p >= s.length) return out
                when (s[p]) {
                    ']' -> {
                        p++
                        return out
                    }
                    // a stray '}' in a list: stepped over
                    ',', '}' -> p++
                    else -> {
                        val at = p
                        out += value(depth + 1)
                        if (p == at) p++
                    }
                }
            }
        }

        private fun quoted(): String {
            val q = s[p++]
            val sb = StringBuilder()
            while (p < s.length) {
                val c = s[p++]
                when {
                    q == '\'' && c == '\'' && p < s.length && s[p] == '\'' -> {
                        sb.append('\'')
                        p++
                    }
                    c == q -> return sb.toString()
                    q == '"' && c == '\\' && p < s.length -> sb.append(escape(s[p++]))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun plain(key: Boolean): String {
            val from = p
            while (p < s.length && !ends(key)) p++
            return s.substring(from, p).trim()
        }

        // a plain scalar's end: a flow's comma or bracket, or a key's colon (one a space or the flow's end follows)
        private fun ends(key: Boolean): Boolean {
            val c = s[p]
            if (c in FLOW_ENDS) return true
            return key && c == ':' && (p + 1 >= s.length || s[p + 1] in " ,}")
        }

        private fun ws() {
            while (p < s.length && s[p] == ' ') p++
        }
    }

    // `"…"`, `'…'` or a plain scalar
    private fun scalar(t: String): Any? = when {
        t.length >= 2 && t[0] == '"' -> Flow(t, HashMap()).value(0)
        t.length >= 2 && t[0] == '\'' -> Flow(t, HashMap()).value(0)
        else -> typed(t)
    }

    private fun typed(t: String): String? = if (t == "~" || t == "null" || t.isEmpty()) null else t

    private fun escape(c: Char): Char = when (c) {
        'n' -> '\n'
        't' -> '\t'
        '0' -> '\u0000'
        else -> c
    }

    // `key: value` or `key:` (quoted keys too); null where the line is no key's
    private fun key(t: String): Pair<String, String>? {
        if (t[0] in "{[&*!") return null
        if (t[0] == '"' || t[0] == '\'') {
            val end = t.indexOf(t[0], 1)
            if (end < 0) return null
            val after = t.substring(end + 1).trimStart()
            if (!after.startsWith(":") || (after.length > 1 && after[1] != ' ')) return null
            return t.substring(1, end) to after.substring(1).trim()
        }
        var at = t.indexOf(':')
        while (at >= 0 && at + 1 < t.length && t[at + 1] != ' ') at = t.indexOf(':', at + 1)
        if (at <= 0) return null
        return t.substring(0, at).trim() to t.substring(at + 1).trim()
    }

    // [s] up to a comment: `#` at its start or after a space, outside quotes
    private fun stripComment(s: String): String {
        var quote = ' '
        for ((n, c) in s.withIndex()) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c in QUOTES && (n == 0 || s[n - 1] in " :{[,") -> quote = c
                c == '#' && (n == 0 || s[n - 1] == ' ') -> return s.substring(0, n)
            }
        }
        return s
    }

    private const val FLOW_ENDS = ",}]"
    private const val QUOTES = "\"'"
    private val BLOCK = Regex("[|>][-+0-9]*")
    private const val MAX_FLOW_LINES = 200
}
