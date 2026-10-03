/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.fields
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * A pack of words to type that the dictionary built in does not have yet, or has as unknown:
 * the new words of a season, got to the phone sooner than a release. Text, UTF-8:
 *
 * ```
 * # youmo words 1
 * # layer: 2026q1
 * 搭子	da'zi	-5.6
 * ```
 *
 * The first line is [HEADER]. `layer` names the layer the words are of, for [LayerPrior]
 * ([org.fcitx.fcitx5.android.engine.lattice.LayerPrior]) to weigh against the user's picks;
 * [DEFAULT_LAYER] without it. Then a word a line: its text, how it reads (libime's `pin'yin`),
 * and its log10 probability on the model's unigram scale (万象's 搭子, seen 342 times, comes to
 * -5.9 against the mixed model; the unknown word is -6.5). Lines starting `#` are comments.
 *
 * A word of the pack that the model knows is listed but scored as the model says; one the model
 * lacks is scored as the pack says, in place of the unknown word ([UserScorer]).
 */
class WordPack(val layer: String, val words: List<Word>) {

    class Word(val entry: Entry, val score: Float)

    companion object {
        const val HEADER = "# youmo words 1"
        const val DEFAULT_LAYER = "pack"
        private const val LAYER_KEY = "# layer:"

        private const val KIND = "# youmo words"

        /**
         * Whether [firstLine] starts a pack of some version: how one is told from a dictionary in
         * libime's format. [parse] says whether this version reads it.
         */
        fun isPack(firstLine: String) = clean(firstLine).startsWith(KIND)

        private fun clean(line: String) = line.removePrefix("﻿").trimEnd()

        /**
         * @throws SourceException at the first line that is not as above, naming [source]
         */
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

        /** A pack's name as a file is named for it (`<name>.words`): ASCII letters, digits, `.`, `_`, `-`, not starting with a dot. */
        fun validName(name: String) =
            name.length in 1..MAX_NAME && name[0] != '.' && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }

        private const val MAX_NAME = 64

        /** A name that fits a `name=value,...` list and a header line: no comma, no space, not empty. */
        fun validLayer(name: String) = name.isNotEmpty() && name.none { it == ',' || it == '=' || it.isWhitespace() }

        private fun word(line: String): Word? {
            val f = line.fields()
            if (f.size != 3) return null
            val score = f[2].toFloatOrNull()?.takeIf { it.isFinite() && it <= 0f } ?: return null
            val entry = LibimeImport.entry(f[0], f[1]) ?: return null
            return Word(entry, score)
        }
    }
}
