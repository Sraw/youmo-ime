/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import java.nio.charset.Charset

/**
 * Traditional characters to simplified, a character at a time, for the app's text learning reads
 * (the spec's §3 點了 is 点了): OpenCC's character tables, as the app ships them (`learn/` among its
 * assets, OpenCC 1.2.0's, the version fcitx's chttrans has). The engine commits simplified text,
 * chttrans converting it after; the app's text may have come through it, or been written
 * traditional. A phrase OpenCC converts as a whole (軟體 for 软件) stays as it is.
 *
 * Text is traditional if a character of it is one simplified text never has; simplified text is
 * left as it is. A character simplified text has too is changed only in traditional text
 * (review-1a-r2 S3): one OpenCC's traditional-to-simplified table keeps as one of its own readings
 * (乾坤, 覆盖, 慰藉, 瞭望 in simplified, 乾淨, 由於 in traditional: 乾 is 干, 於 is 于), and one of
 * GB2312, simplified text's set (哪吒, 菸, 後). One OpenCC's simplified-to-traditional table has as
 * a simplified one (緼) is never changed. gbks2t.tab read backwards got these wrong (機 to 几, 覆 to
 * 复), a table of every traditional form a simplified one may have.
 */
class Simplifier private constructor(private val map: Map<Int, Int>, private val inTraditional: Map<Int, Int>) {

    fun simplify(text: String): String {
        if (!traditional(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            out.appendCodePoint(map[c] ?: inTraditional[c] ?: c)
            i += Character.charCount(c)
        }
        return out.toString()
    }

    private fun traditional(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (c in map) return true
            i += Character.charCount(c)
        }
        return false
    }

    companion object {
        val NONE = Simplifier(emptyMap(), emptyMap())

        /**
         * Reads OpenCC's `TSCharacters.txt` ([traditional]: a line per character, a tab, its
         * simplified ones apart by spaces, the usual first) and `STCharacters.txt` ([simplified],
         * the other way): each traditional character to its first simplified one; one whose first is
         * itself (覆, 藉, 阪) stays as it is, not taken to a later one. Lines of `#` and of a phrase are skipped.
         */
        fun read(traditional: ByteArray, simplified: ByteArray): Simplifier {
            val simplifiedAlready = HashSet<Int>()
            for ((key, _) in entries(simplified)) simplifiedAlready += key
            // a JVM or phone without the charset: none of them is taken for GB2312's
            val gb2312 = if (Charset.isSupported(GB2312)) Charset.forName(GB2312).newEncoder() else null
            val map = HashMap<Int, Int>()
            val inTraditional = HashMap<Int, Int>()
            for ((key, values) in entries(traditional)) {
                // OpenCC's first value, unless that is the character itself (覆 覆 复: 覆蓋 is 覆盖, not 复盖;
                // 藉, 阪 stay too): verify-1a-r2 S3
                val to = values.firstOrNull()?.takeIf { it != key }
                if (to == null || key in simplifiedAlready) continue
                val shared = key in values || gb2312?.canEncode(String(Character.toChars(key))) == true
                (if (shared) inTraditional else map)[key] = to
            }
            return Simplifier(map, inTraditional)
        }

        private const val GB2312 = "GB2312"
        // the table's lines as a character and the single characters it goes to
        private fun entries(table: ByteArray): Sequence<Pair<Int, List<Int>>> =
            String(table, Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (line.startsWith("#") || tab < 0) return@mapNotNull null
                val key = line.substring(0, tab)
                if (key.codePointCount(0, key.length) != 1) return@mapNotNull null
                val values = line.substring(tab + 1).trim().split(' ').filter { it.codePointCount(0, it.length) == 1 }
                key.codePointAt(0) to values.map { it.codePointAt(0) }
            }
    }
}
