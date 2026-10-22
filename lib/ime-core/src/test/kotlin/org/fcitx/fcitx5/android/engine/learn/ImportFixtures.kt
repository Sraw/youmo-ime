/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/** [words] (text to reading, syllables apart by spaces) imported as batch [batch] of a list, in this order. */
internal fun ImportedWords.import(vararg words: Pair<String, String>, batch: Int = lastId + 1): ImportedWords = also { w ->
    ImportedWords.plan(batch, "t.txt", 0, words.map { ImportedWords.Incoming(it.first, it.second) }) { _, _ -> false }.forEach { w.apply(it) }
}
