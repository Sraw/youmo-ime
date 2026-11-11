/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/** An INI file of a skin, read: its sections by name, each its keys and values. */
typealias Ini = Map<String, Map<String, String>>

/**
 * The text files of a skin: INI files (Baidu's `.ini`, `.css`, `.til`, `.cnd`, `.pop`; Sogou's `phoneSkin.ini`)
 * and their colours. They are hand-edited and come from anywhere, so the reading forgives what it can and never
 * throws: a line that is not `key=value` or `[section]` is skipped, keys before any section are dropped, a section
 * written twice is one, and the first value of a key wins (Baidu's own reader's way, as far as skins show).
 */
object SkinText {
    /** Bytes as text: UTF-16 or UTF-8 by its byte-order mark, UTF-8 where it reads as such, else [legacy]'s. */
    fun decode(bytes: ByteArray, legacy: (ByteArray) -> String): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return utf16(bytes, 2, little = true)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return utf16(bytes, 2, little = false)
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return utf8(bytes, if (bom) 3 else 0) ?: legacy(bytes)
    }

    // null where it is not UTF-8: the failure is the answer, not an error
    @Suppress("SwallowedException")
    private fun utf8(bytes: ByteArray, from: Int): String? = try {
        bytes.decodeToString(from, bytes.size, throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        null
    }

    private fun utf16(b: ByteArray, from: Int, little: Boolean): String {
        val n = (b.size - from) / 2
        val chars = CharArray(n) { i ->
            val lo = b[from + 2 * i].toInt() and BYTE
            val hi = b[from + 2 * i + 1].toInt() and BYTE
            (if (little) hi shl BITS or lo else lo shl BITS or hi).toChar()
        }
        return chars.concatToString()
    }

    /** [text] as INI sections ([SkinText]'s rules); comments start with `;`, `#` or `//`. */
    fun ini(text: String): Ini {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        var section: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (comment(line)) continue
            if (line.length >= 2 && line[0] == '[' && line.last() == ']') {
                // an empty name, or one past the cap: its keys dropped till the next
                val name = line.substring(1, line.length - 1).trim().takeIf { it.isNotEmpty() && out.size < MAX_SECTIONS }
                section = name?.let { out.getOrPut(it) { LinkedHashMap() } }
            } else {
                section?.let { pair(line, it) }
            }
        }
        return out
    }

    private fun comment(line: String) = line.isEmpty() || line[0] == ';' || line[0] == '#' || line.startsWith("//")

    private fun pair(line: String, into: MutableMap<String, String>) {
        val eq = line.indexOf('=')
        if (eq <= 0 || into.size >= MAX_KEYS) return
        val key = line.substring(0, eq).trim()
        if (key.isNotEmpty() && key !in into) into[key] = line.substring(eq + 1).trim()
    }

    /**
     * A colour as skins write it: Baidu's `rrggbb` or `aarrggbb`, Sogou's `0xAARRGGBB`, either with `#`; as ARGB,
     * or null where it is none of those.
     */
    fun color(value: String?): Int? {
        val v = value?.trim()?.removePrefix("0x")?.removePrefix("0X")?.removePrefix("#") ?: return null
        val hex = v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (!hex || v.length != RGB_DIGITS && v.length != ARGB_DIGITS) return null
        val n = v.toLong(HEX).toInt()
        return if (v.length == RGB_DIGITS) n or OPAQUE else n
    }

    /** Whole numbers separated by commas (`0,0,1080,126`), [count] of them, or null. */
    fun ints(value: String?, count: Int): IntArray? {
        val parts = value?.split(',')?.map { it.trim() } ?: return null
        if (parts.size < count) return null
        return IntArray(count) { parts[it].toIntOrNull()?.takeIf { n -> n in -MAX_COORD..MAX_COORD } ?: return null }
    }

    // a section or a key past these is dropped: a skin has a few hundred of each, a crafted file millions
    const val MAX_SECTIONS = 10_000
    const val MAX_KEYS = 1_000

    // a coordinate past this is no skin's (they are a few thousand pixels)
    private const val MAX_COORD = 100_000
    private const val BYTE = 0xFF
    private const val BITS = 8
    private const val HEX = 16
    private const val RGB_DIGITS = 6
    private const val ARGB_DIGITS = 8
    private const val OPAQUE = -0x1000000
}
