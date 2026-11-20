/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.fields
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.imports.WordText

/**
 * New words shipped sooner than a release, UTF-8 text:
 *
 * ```
 * # youmo words 1
 * # layer: 2026q1
 * 搭子	da'zi	-5.6
 * ```
 *
 * [HEADER] first; `layer` names the words' layer for [org.fcitx.fcitx5.android.engine.lattice.LayerPrior]
 * ([DEFAULT_LAYER] without it). A line: text, `pin'yin`, log10 probability on the unigram scale (unknown is -6.5).
 * Words the model knows keep its score.
 */
class WordPack(val layer: String, val words: List<Word>) {

    class Word(val entry: Entry, val score: Float)

    companion object {
        const val HEADER = "# youmo words 1"
        const val DEFAULT_LAYER = "pack"
        private const val LAYER_KEY = "# layer:"

        private const val KIND = "# youmo words"

        /** Whether [firstLine] starts a pack of any version (as against a libime dictionary); [parse] says whether this one reads it. */
        fun isPack(firstLine: String) = clean(firstLine).startsWith(KIND)

        private fun clean(line: String) = line.removePrefix("\uFEFF").trimEnd()

        fun parse(lines: Sequence<String>, source: String = "pack"): WordPack {
            val rest = lines.iterator()
            val first = if (rest.hasNext()) rest.next() else fail(source, 1, "not a word pack: empty")
            if (!isPack(first)) fail(source, 1, "not a word pack: expected \"$HEADER\"")
            if (clean(first) != HEADER) fail(source, 1, "a word pack of another version: expected \"$HEADER\"")
            var layer = DEFAULT_LAYER
            val words = ArrayList<Word>()
            var n = 1
            while (rest.hasNext()) {
                val line = rest.next().trimEnd()
                n++
                when {
                    line.isBlank() -> Unit
                    line.startsWith(LAYER_KEY) -> layer = line.substring(LAYER_KEY.length).trim().takeIf(::validLayer)
                        ?: fail(source, n, "bad layer name \"${line.substring(LAYER_KEY.length).trim()}\"")
                    line.startsWith('#') -> Unit
                    else -> words += word(line) ?: fail(source, n, "expected \"word pin'yin log10P\"")
                }
            }
            return WordPack(layer, words)
        }

        private fun fail(source: String, line: Int, message: String): Nothing = throw SourceException(source, line, message)

        private const val MAX_NAME = 64

        /** A layer name fitting `name=value,...` lists and the log's `writeUTF`: no comma, `=` or space, 1 to 64 chars. */
        fun validLayer(name: String) = name.length in 1..MAX_NAME && name.none { it == ',' || it == '=' || it.isWhitespace() }

        private fun word(line: String): Word? {
            val f = line.fields()
            if (f.size != 3) return null
            val score = f[2].toFloatOrNull()?.takeIf { it.isFinite() && it <= 0f } ?: return null
            val entry = WordText.entry(f[0], f[1]) ?: return null
            return Word(entry, score)
        }
    }
}
