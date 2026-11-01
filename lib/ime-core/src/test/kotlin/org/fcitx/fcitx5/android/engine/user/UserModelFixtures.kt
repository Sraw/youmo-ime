/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

// What an older build learned through, kept for the tests that seed a model with it: the model
// typing goes on with never learns through these (see UserModel). Out of the model's own code (S11).

/**
 * Counts [sentence], each word and each pair, the first after [prev] if given (the word committed
 * before it) and still known: one forgotten since is not made a word again. Words of the sentence
 * the dictionary lacks become the user's made words ([madeWords]).
 *
 * @return the words' ids
 */
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

/**
 * Forgets [words], a candidate the user asked to: their counts and every pair they are in. One of
 * the user's own words, forgotten alone, is gone from the trie too, as libime drops it from its
 * user dictionary. One listed stays typeable, its counts gone; so does one in a longer candidate,
 * till the next start.
 */
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

/**
 * The words the user made, by putting a reading together from pieces, with how often each was
 * typed: not those of their dictionaries or packs, nor those forgotten.
 */
internal fun UserModel.madeWords(): List<Pair<Entry, Float>> = entries.filter { (id, entry) -> isMade(id) && entry !in blockedEntries }
    .map { (id, entry) -> entry to count(id) }
