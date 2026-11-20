/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

object WordListFiles {

    /** The export's first line, never translated, so an export is recognised in any language; the translated header follows. */
    const val EXPORT_MARK = "# youmo-words 1"

    /** Before a learned word in the export, apart (`~ 拟好 ni'hao 3.0`): no other list starts a line so, and an older version reads two words, so nothing. */
    const val LEARNED_MARK = "~"

    /** Before an English word blocked in the export (`!@en the`): a line of letters alone would read as pinyin. */
    const val ENGLISH_BLOCK_MARK = "!@en "

    // exports before EXPORT_MARK, one header per language shipped
    private val OLD_HEADERS = setOf(
        "# Youmo IME user dictionary: a word a line, its pinyin after it; a ! before a word blocks it",
        "# 幽默输入法用户词库：一行一个词，词后面是拼音；! 开头的是屏蔽的词",
        "# 幽默輸入法使用者詞庫：一行一個詞，詞後面是拼音；! 開頭的是封鎖的詞",
    )

    /** The charset of [open]'s word list by [WordLists.decode]'s rules, read through rather than whole (base dictionaries are megabytes). */
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

    /** [line] of a file imported into the base, in libime's format: kept if already so, else read as a word list line; null for a line without pinyin or a blocked one. A learned word is read only in the user's [export]. */
    fun baseLine(line: String, export: Boolean = false): String? {
        val bare = line.removePrefix("\uFEFF")
        val learned = bare.trimStart().startsWith(LEARNED_MARK)
        if (learned && !export) return null
        val plain = if (learned) bare.trimStart().removePrefix(LEARNED_MARK) else bare
        if (!learned && WordText.dictionaryEntry(plain) != null) return plain
        val read = WordLists.line(plain) ?: return null
        if (read.blocked) return null
        val entry = WordLists.entry(read.text, read.pinyin ?: return null) ?: return null
        return "${escapeValue(entry.text)} ${entry.apostrophed()} 0"
    }

    fun baseLines(lines: Sequence<String>): Sequence<String> {
        var export: Boolean? = null
        return lines.mapNotNull { line ->
            if (export == null) export = exportMark(line)
            baseLine(line, export == true)
        }
    }

    /** The lines of [head] up to its last whole line: a cut character would read UTF-8 as GB18030; UTF-16 to a whole code unit. */
    private fun headLines(head: ByteArray): Sequence<String> {
        val utf16 = WordLists.bomCharset(head, head.size) != null
        val end = if (utf16) head.size and 1.inv() else head.lastIndexOf('\n'.code.toByte()) + 1
        return WordLists.decode(if (end > 0) head.copyOf(end) else head).lineSequence()
    }

    private fun header(line: String) = line.removePrefix("\uFEFF").trim().takeIf { it.isNotEmpty() }

    /** Whether a file is an export, by its first non-blank line; null for a blank [line]. The one rule [baseLines], the import and [isOwnExport] share. */
    internal fun exportMark(line: String, old: Boolean = false): Boolean? =
        header(line)?.let { it == EXPORT_MARK || (old && it in OLD_HEADERS) }

    /** Whether [head]'s first non-blank line is [EXPORT_MARK] or an older export's header. */
    fun isOwnExport(head: ByteArray): Boolean = isOwnExport(headLines(head))

    private fun isOwnExport(lines: Sequence<String>) = lines.firstNotNullOfOrNull { exportMark(it, old = true) } == true

    /** Whether [head] is the user dictionary's export: [isOwnExport], or a `!` line, which no other list has and the base would drop. */
    fun isUserExport(head: ByteArray): Boolean {
        val lines = headLines(head).toList().asSequence()
        return isOwnExport(lines) || lines.any { it.removePrefix("\uFEFF").trimStart().startsWith("!") }
    }

    private const val BUFFER = 8192
}
