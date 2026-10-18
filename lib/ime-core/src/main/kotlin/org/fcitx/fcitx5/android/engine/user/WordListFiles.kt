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
     * blocked one: the base cannot block.
     */
    fun baseLine(line: String): String? {
        // the byte order mark, read as a character in UTF-8 and UTF-16 alike
        val plain = line.removePrefix("\uFEFF")
        if (LibimeImport.dictionaryEntry(plain) != null) return plain
        val read = WordLists.line(plain) ?: return null
        if (read.blocked) return null
        val entry = WordLists.entry(read.text, read.pinyin ?: return null) ?: return null
        return "${escapeValue(entry.text)} ${WordLists.code(entry.syllables)} 0"
    }

    /**
     * Whether [head], the start of a file, is the user dictionary's export: its first line is
     * [EXPORT_MARK] (or an older export's header, in any language shipped), or it has a blocked
     * word (`!`), which no other list has and which the base, should it be imported there, would drop.
     */
    fun isUserExport(head: ByteArray): Boolean {
        // up to the last whole line: a character cut in two would read the UTF-8 as GB18030; UTF-16
        // (by its byte order mark) to a whole code unit, its newline being two bytes
        val utf16 = WordLists.bomCharset(head, head.size) != null
        val end = if (utf16) head.size and 1.inv() else head.lastIndexOf('\n'.code.toByte()) + 1
        val lines = WordLists.decode(if (end > 0) head.copyOf(end) else head).lineSequence()
        return lines.withIndex().any { (i, line) ->
            (i == 0 && line.removePrefix("\uFEFF").trim().let { it == EXPORT_MARK || it in OLD_HEADERS }) ||
                line.trimStart().startsWith("!")
        }
    }

    private const val BUFFER = 8192
}
