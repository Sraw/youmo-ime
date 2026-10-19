/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.escapeValue
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/** How a word list file is read: the user dictionary's export told, a text file put into the base. */
object WordListFiles {

    /**
     * The export's first line, never translated: an export made in one language is told in any
     * other. The translated header follows it.
     */
    const val EXPORT_MARK = "# youmo-words 1"

    /**
     * Before a learned word in the export, apart from it (`~ 拟好 ni'hao 3.0`), as `!` before a
     * blocked one: no other input method's list starts a line so, and apart, an older version
     * reads the line as two words, so not at all (not a word `~拟好`).
     */
    const val LEARNED_MARK = "~"

    /** The first lines of an export: [EXPORT_MARK], then [header], the translated one. */
    fun exportHead(header: String) = "$EXPORT_MARK\n$header\n"

    // the first line of exports made before EXPORT_MARK, one per language shipped (en, zh-CN, zh-TW)
    private val OLD_HEADERS = setOf(
        "# Youmo IME user dictionary: a word a line, its pinyin after it; a ! before a word blocks it",
        "# 幽默输入法用户词库：一行一个词，词后面是拼音；! 开头的是屏蔽的词",
        "# 幽默輸入法使用者詞庫：一行一個詞，詞後面是拼音；! 開頭的是封鎖的詞",
    )

    /**
     * The charset of the word list [open] reads, by [WordLists.decode]'s rules: UTF-16 by its byte
     * order mark, UTF-8 if the whole of it reads so, else GB18030. Told by reading it through
     * rather than whole: a base dictionary runs to many megabytes.
     */
    fun charset(open: () -> InputStream): Charset {
        val head = ByteArray(2)
        val n = open().use { input -> input.read(head) }
        WordLists.bomCharset(head, n)?.let { return it }
        return try {
            InputStreamReader(open(), WordLists.strictUtf8()).use { reader ->
                val buffer = CharArray(BUFFER)
                while (reader.read(buffer) >= 0) Unit
            }
            Charsets.UTF_8
        } catch (_: CharacterCodingException) {
            WordLists.GB18030
        }
    }

    /**
     * [line] of a text file imported into the base dictionary, in libime's format: as it is if it
     * is one already (its cost kept), else read as the user dictionary reads a word list
     * ([WordLists.line]: `you mo 幽默`, a Sogou export's `'you'mo 幽默 1`) and written so. Null for
     * a line neither reads, a word with no pinyin (the base has nothing to guess it from) or a
     * blocked one: the base cannot block. A learned word ([LEARNED_MARK]) is read only in the
     * user's [export], as the user dictionary's import reads it; in another list, none.
     */
    fun baseLine(line: String, export: Boolean = false): String? {
        // the byte order mark, read as a character in UTF-8 and UTF-16 alike
        val bare = line.removePrefix("\uFEFF")
        // a learned word of the user's export: the word, its count no cost
        val learned = bare.trimStart().startsWith(LEARNED_MARK)
        if (learned && !export) return null
        val plain = if (learned) bare.trimStart().removePrefix(LEARNED_MARK) else bare
        if (!learned && LibimeImport.dictionaryEntry(plain) != null) return plain
        val read = WordLists.line(plain) ?: return null
        if (read.blocked) return null
        val entry = WordLists.entry(read.text, read.pinyin ?: return null) ?: return null
        return "${escapeValue(entry.text)} ${WordLists.code(entry.syllables)} 0"
    }

    /** [lines], a text file, as [baseLine] reads them: an export ([EXPORT_MARK] its first line not blank) with its learned words. */
    fun baseLines(lines: Sequence<String>): Sequence<String> {
        var export: Boolean? = null
        return lines.mapNotNull { line ->
            if (export == null) export = exportMark(line)
            baseLine(line, export == true)
        }
    }

    /**
     * The lines of [head], the start of a file, decoded up to its last whole line: a character cut
     * in two would read UTF-8 as GB18030 and garble the rest; UTF-16 (by its byte order mark) to a
     * whole code unit, its newline being two bytes.
     */
    private fun headLines(head: ByteArray): Sequence<String> {
        val utf16 = WordLists.bomCharset(head, head.size) != null
        val end = if (utf16) head.size and 1.inv() else head.lastIndexOf('\n'.code.toByte()) + 1
        return WordLists.decode(if (end > 0) head.copyOf(end) else head).lineSequence()
    }

    /** [line] without a byte order mark and the spaces around it; null for one with nothing else. */
    private fun header(line: String) = line.removePrefix("\uFEFF").trim().takeIf { it.isNotEmpty() }

    /**
     * Whether a file is an export, told line by line from its start: null for [line] with nothing
     * but a byte order mark or spaces (the answer comes from a later one), else whether it is
     * [EXPORT_MARK] (or, [old], also an older export's header). The one rule [baseLines], the user
     * dictionary's import and [isOwnExport] tell an export by: its first non-blank line.
     */
    internal fun exportMark(line: String, old: Boolean = false): Boolean? =
        header(line)?.let { it == EXPORT_MARK || (old && it in OLD_HEADERS) }

    /**
     * Whether [head], a file's start, begins with an export's header: its first non-blank line (as
     * [baseLines] and the user dictionary's import tell an export) is [EXPORT_MARK] or an older
     * export's header. A `!` line tells nothing here.
     */
    fun isOwnExport(head: ByteArray): Boolean = isOwnExport(headLines(head))

    private fun isOwnExport(lines: Sequence<String>) = lines.firstNotNullOfOrNull { exportMark(it, old = true) } == true

    /**
     * Whether [head], the start of a file, is the user dictionary's export: [isOwnExport], or it has
     * a blocked word (`!`), which no other list has and which the base, should it be imported there,
     * would drop.
     */
    fun isUserExport(head: ByteArray): Boolean {
        // decoded once: both questions read the same lines
        val lines = headLines(head).toList().asSequence()
        return isOwnExport(lines) || lines.any { it.removePrefix("\uFEFF").trimStart().startsWith("!") }
    }

    private const val BUFFER = 8192
}
