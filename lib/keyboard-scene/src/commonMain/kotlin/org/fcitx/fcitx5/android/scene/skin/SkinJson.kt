/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * JSON read as plain data (FlorisBoard's `extension.json` and stylesheets): an object a [Map] in its order, an array a
 * [List], a string a [String], a number its text, `true`/`false` [Boolean], `null` null. Lenient where it costs
 * nothing (a trailing comma); null for text that is no JSON at all; held to [SkinYaml.MAX_DEPTH].
 */
object SkinJson {
    fun parse(text: String): Any? = runCatchingParse { Reader(text).document() }

    // a malformed text: no value, not an error (the reader says the skin is broken)
    @Suppress("SwallowedException")
    private inline fun runCatchingParse(block: () -> Any?): Any? = try {
        block()
    } catch (e: Malformed) {
        null
    }

    private class Malformed : RuntimeException()

    private class Reader(private val s: String) {
        private var p = 0

        fun document(): Any? {
            val v = value(0)
            ws()
            if (p < s.length) throw Malformed()
            return v
        }

        private fun value(depth: Int): Any? {
            if (depth > SkinYaml.MAX_DEPTH) throw Malformed()
            ws()
            if (p >= s.length) throw Malformed()
            return when (s[p]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            p++
            while (true) {
                ws()
                if (at('}')) return out
                need(p < s.length && s[p] == '"')
                val k = str()
                ws()
                need(at(':'))
                out[k] = value(depth + 1)
                ws()
                if (at('}')) return out
                need(at(','))
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            p++
            while (true) {
                ws()
                if (at(']')) return out
                out += value(depth + 1)
                ws()
                if (at(']')) return out
                if (!at(',')) throw Malformed()
            }
        }

        private fun str(): String {
            p++
            val sb = StringBuilder()
            while (p < s.length) {
                val c = s[p++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(escape())
                    else -> sb.append(c)
                }
            }
            throw Malformed()
        }

        private fun escape(): Char {
            need(p < s.length)
            return when (val c = s[p++]) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000C'
                'u' -> {
                    // four hex digits, no sign (`toIntOrNull` alone takes `+abc`, `-001`)
                    val code = s.substring(p, minOf(p + HEX_DIGITS, s.length))
                        .takeIf { d -> d.length == HEX_DIGITS && d.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }?.toIntOrNull(HEX)
                    need(code != null)
                    p += HEX_DIGITS
                    code!!.toChar()
                }
                else -> c
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, p)) throw Malformed()
            p += w.length
            return v
        }

        private fun number(): String {
            val from = p
            while (p < s.length && (s[p].isDigit() || s[p] in "+-.eE")) p++
            val n = s.substring(from, p)
            if (!NUMBER.matches(n)) throw Malformed()
            return n
        }

        private fun need(ok: Boolean) {
            if (!ok) throw Malformed()
        }

        private fun at(c: Char): Boolean = (p < s.length && s[p] == c).also { if (it) p++ }

        private fun ws() {
            while (p < s.length && s[p].isWhitespace()) p++
        }
    }

    private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")
    private const val HEX = 16
    private const val HEX_DIGITS = 4
}
