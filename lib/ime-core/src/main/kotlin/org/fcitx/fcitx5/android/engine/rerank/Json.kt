/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

/**
 * Just enough JSON for a safetensors header: objects as maps, arrays as lists, strings, numbers
 * as doubles, true, false and null. Throws [IllegalArgumentException] on anything else, or on
 * input nested deeper than [MAX_DEPTH] or of more than [MAX_VALUES] values (the file is untrusted).
 */
internal class Json private constructor(private val text: String) {

    private var at = 0
    private var values = 0

    private fun fail(what: String): Nothing = throw IllegalArgumentException("JSON at $at: $what")

    private fun skipSpace() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun expect(c: Char) {
        skipSpace()
        if (at >= text.length || text[at] != c) fail("expected $c")
        at++
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) fail("nested too deep")
        if (++values > MAX_VALUES) fail("too many values")
        skipSpace()
        if (at >= text.length) fail("unexpected end")
        return when (text[at]) {
            '{' -> obj(depth)
            '[' -> array(depth)
            '"' -> string()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> number()
        }
    }

    private fun word(w: String, v: Any?): Any? {
        if (!text.startsWith(w, at)) fail("expected $w")
        at += w.length
        return v
    }

    private fun obj(depth: Int): Map<String, Any?> {
        at++
        val map = LinkedHashMap<String, Any?>()
        skipSpace()
        if (at < text.length && text[at] == '}') return map.also { at++ }
        while (true) {
            skipSpace()
            if (at >= text.length || text[at] != '"') fail("expected a key")
            val key = string()
            expect(':')
            map[key] = value(depth + 1)
            skipSpace()
            if (at >= text.length) fail("unexpected end")
            if (text[at++] == '}') return map
            if (text[at - 1] != ',') fail("expected , or }")
        }
    }

    private fun array(depth: Int): List<Any?> {
        at++
        val list = ArrayList<Any?>()
        skipSpace()
        if (at < text.length && text[at] == ']') return list.also { at++ }
        while (true) {
            list += value(depth + 1)
            skipSpace()
            if (at >= text.length) fail("unexpected end")
            if (text[at++] == ']') return list
            if (text[at - 1] != ',') fail("expected , or ]")
        }
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (true) {
            if (at >= text.length) fail("unterminated string")
            val c = text[at++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> out.append(escape())
                else -> out.append(c)
            }
        }
    }

    private fun escape(): Char {
        if (at >= text.length) fail("unterminated escape")
        return when (val c = text[at++]) {
            '"', '\\', '/' -> c
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            // a surrogate pair arrives as two escapes, each one char here
            'u' -> {
                if (at + 4 > text.length) fail("short \\u escape")
                val hex = text.substring(at, at + 4)
                // toInt(16) would take a sign
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad \\u escape")
                at += 4
                hex.toInt(16).toChar()
            }
            else -> fail("bad escape \\$c")
        }
    }

    private fun number(): Double {
        val start = at
        while (at < text.length && text[at] in "+-0123456789.eE") at++
        return text.substring(start, at).toDoubleOrNull() ?: fail("bad value")
    }

    companion object {
        const val MAX_DEPTH = 16
        /** A header of a 8192-token vocabulary has some 9000; each is a boxed object. */
        const val MAX_VALUES = 1 shl 18

        fun parse(text: String): Any? = Json(text).run {
            val v = value(0)
            skipSpace()
            if (at != text.length) fail("trailing text")
            v
        }
    }
}
