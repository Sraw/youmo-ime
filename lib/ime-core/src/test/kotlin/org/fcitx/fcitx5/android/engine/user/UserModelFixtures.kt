/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

// What an older build learned through, for tests that seed a model; typing never learns through these.

/** Counts [sentence], its words and pairs, after [prev] if still known; words the dictionary lacks become [madeWords]. */
internal fun UserModel.learn(prev: Entry?, sentence: List<Entry>): IntArray {
    require(sentence.isNotEmpty()) { "nothing to learn" }
    val words = IntArray(sentence.size) { idAsTyped(sentence[it]) }
    // a word before whose reading is not kept yet takes this one: its pair is written out with it
    var before = if (prev != null && knows(prev)) id(prev).also { if (it !in entries) entered(it, prev) } else NO_WORD
    for (w in words) {
        add(NO_WORD, w, 1f)
        if (before != NO_WORD) add(before, w, 1f)
        before = w
    }
    return words
}

/** Forgets [words]: their counts and every pair; the user's own, forgotten alone, leaves the trie as libime drops it. */
internal fun UserModel.forget(words: List<Entry>) {
    val forgotten = words.mapNotNull(::idOf).toSet()
    if (forgotten.isEmpty()) return
    forgetCounts(forgotten)
    val word = words.singleOrNull()
    val id = word?.let(::idOf) ?: NO_WORD
    if (word != null && isMade(id)) dropOwn(word, id)
    changes++
}

/** Adds [count] to [entry] as a word. */
internal fun UserModel.addCount(entry: Entry, count: Float) = add(NO_WORD, idAsTyped(entry), count)

/** Adds [count] to [second] after [first]. */
internal fun UserModel.addCount(first: Entry, second: Entry, count: Float) = add(idAsTyped(first), idAsTyped(second), count)

/** The words the user made by putting a reading together, with their counts. */
internal fun UserModel.madeWords(): List<Pair<Entry, Float>> = entries.filter { (id, entry) -> isMade(id) && entry !in blockedEntries }
    .map { (id, entry) -> entry to count(id) }
