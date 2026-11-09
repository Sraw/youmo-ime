/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.json

import org.fcitx.fcitx5.android.scene.Parsed

/** A JSON value as read, with the [line] it starts on (a reader of it names the line of what is wrong). */
sealed interface JsonValue {
    val line: Int

    /** Its members in the order written; a name given twice is refused as the file is read. */
    class Object(val members: Map<String, JsonValue>, override val line: Int) : JsonValue

    class Array(val items: List<JsonValue>, override val line: Int) : JsonValue

    class Str(val value: String, override val line: Int) : JsonValue

    /** As written (`-12`, `0.5`, `1e3`): the reader of it says which numbers it takes. */
    class Number(val text: String, override val line: Int) : JsonValue

    class Bool(val value: Boolean, override val line: Int) : JsonValue

    class Null(override val line: Int) : JsonValue
}

/**
 * JSON (RFC 8259) as a theme file 3.0 is written, read as [org.fcitx.fcitx5.android.scene.xml.Xml]
 * reads XML: a file comes from anywhere, so it is [MAX_CHARS] at most, [MAX_DEPTH] deep, a string
 * [MAX_STRING] characters, and anything else is a problem with its line, never a crash. A name given
 * twice in one object is refused (which of the two is meant?). For the web's theme maker, which has no
 * kotlinx.serialization; the app reads its themes with that.
 */
object Json {
    const val MAX_CHARS = 1_000_000
    const val MAX_DEPTH = 32
    const val MAX_STRING = 4096

    /**
     * The value [source] is, or why not. A file comes from anywhere: whatever else goes wrong reading
     * it (a bug of ours it finds) is a problem of the file too, never a crash, as ThemeReader has it.
     * An exception only, not any Throwable: an Error (out of memory) is not the file's. No stack
     * overflows here, the reader recursing [MAX_DEPTH] deep at most (and common Kotlin has no
     * StackOverflowError to name).
     */
    @Suppress("TooGenericExceptionCaught")
    fun parse(source: String): Parsed<JsonValue> =
        if (source.length > MAX_CHARS) {
            Parsed.Failed(0, "the file is over $MAX_CHARS characters")
        } else {
            try {
                Parsed.Ok(JsonReader(source).document())
            } catch (e: JsonException) {
                Parsed.Failed(e.line, e.message.orEmpty())
            } catch (e: Exception) {
                Parsed.Failed(0, "the file could not be read: ${e::class.simpleName}")
            }
        }
}

/** Thrown inside the reader only, caught by [Json.parse]: a parse stops at its first problem. */
private class JsonException(val line: Int, message: String) : Exception(message)

private class JsonReader(private val s: String) {
    private var i = 0
    private var line = 1

    fun document(): JsonValue {
        // a byte order mark, as an editor may save one
        if (s.startsWith(BOM)) i++
        val v = value(0)
        space()
        if (i < s.length) fail("text after the value")
        return v
    }

    private fun fail(message: String): Nothing = throw JsonException(line, message)

    private fun space() {
        while (i < s.length) {
            when (s[i]) {
                '\n' -> line++
                ' ', '\t', '\r' -> Unit
                else -> return
            }
            i++
        }
    }

    private fun value(depth: Int): JsonValue {
        space()
        if (i >= s.length) fail("the file ends where a value was to be")
        val at = line
        return when (val c = s[i]) {
            '{' -> obj(depth + 1, at)
            '[' -> array(depth + 1, at)
            '"' -> JsonValue.Str(string(), at)
            't' -> word("true", JsonValue.Bool(true, at))
            'f' -> word("false", JsonValue.Bool(false, at))
            'n' -> word("null", JsonValue.Null(at))
            else -> if (c == '-' || c in '0'..'9') JsonValue.Number(number(), at) else fail("'$c' where a value was to be")
        }
    }

    private fun deeper(depth: Int) {
        if (depth > Json.MAX_DEPTH) fail("nested over ${Json.MAX_DEPTH} deep")
    }

    private fun obj(depth: Int, at: Int): JsonValue {
        deeper(depth)
        i++
        val members = LinkedHashMap<String, JsonValue>()
        space()
        if (i < s.length && s[i] == '}') {
            i++
            return JsonValue.Object(members, at)
        }
        while (true) {
            space()
            if (i >= s.length || s[i] != '"') fail("a name in quotes was to be here")
            val name = string()
            space()
            expect(':')
            if (name in members) fail("\"$name\" given twice")
            members[name] = value(depth)
            space()
            when (if (i < s.length) s[i] else ' ') {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonValue.Object(members, at)
                }
                else -> fail("',' or '}' was to be here")
            }
        }
    }

    private fun array(depth: Int, at: Int): JsonValue {
        deeper(depth)
        i++
        val items = ArrayList<JsonValue>()
        space()
        if (i < s.length && s[i] == ']') {
            i++
            return JsonValue.Array(items, at)
        }
        while (true) {
            items += value(depth)
            space()
            when (if (i < s.length) s[i] else ' ') {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonValue.Array(items, at)
                }
                else -> fail("',' or ']' was to be here")
            }
        }
    }

    private fun expect(c: Char) {
        if (i >= s.length || s[i] != c) fail("'$c' was to be here")
        i++
    }

    private fun word(w: String, v: JsonValue): JsonValue {
        if (!s.startsWith(w, i)) fail("not a value")
        i += w.length
        return v
    }

    private fun number(): String {
        val m = NUMBER.matchAt(s, i) ?: fail("not a number")
        i += m.value.length
        return m.value
    }

    private fun string(): String {
        i++
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) fail("the file ends in a string")
            if (b.length > Json.MAX_STRING) fail("a string over ${Json.MAX_STRING} characters")
            when (val c = s[i]) {
                '"' -> {
                    i++
                    return b.toString()
                }
                '\\' -> b.append(escape())
                else -> {
                    if (c < ' ') fail("a control character in a string")
                    b.append(c)
                    i++
                }
            }
        }
    }

    private fun escape(): Char {
        if (i + 1 >= s.length) fail("the file ends in a string")
        val c = s[i + 1]
        i += 2
        return when (c) {
            '"', '\\', '/' -> c
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = s.substring(i, minOf(i + HEX_DIGITS, s.length))
                if (hex.length != HEX_DIGITS || hex.any { it !in HEX }) fail("\\u needs four hex digits")
                i += HEX_DIGITS
                hex.toInt(HEX_RADIX).toChar()
            }
            else -> fail("\\$c is no escape")
        }
    }

    private companion object {
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        const val HEX = "0123456789abcdefABCDEF"
        const val HEX_DIGITS = 4
        const val HEX_RADIX = 16
        const val BOM = '\uFEFF'
    }
}
