/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.File

/**
 * A user learning as `Engines` has one (impl/design-1b.md §3, §4): an in-memory learning record
 * ([log], on a [clock] the test moves), its [habits], and the [model] the decoder reads, its own
 * words and counts kept from the record. A session made with [learnTo] and [model] learns into it.
 */
internal class Learner(data: PinyinData, val clock: FakeClock = FakeClock(), settles: Boolean = true, file: File? = null) {
    val model = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
    val log = LearnLog(file, clock).apply { open() }
    val habits = LogHabits({ log })
    val counts = HabitCounts(model).also { it.attach(log.state) }

    init {
        // [settles]: the counts a new typing day changed made anew at once, as if a field started after each commit
        log.listener = {
            counts.apply(log.state, it)
            if (settles) counts.settle(log.state)
        }
    }

    fun learnTo(kind: String = Kinds.FULL) = LearnTo(habits, kind)

    /** What the record counts: each word used by its text, and each pair, `before next`. */
    fun used(): Set<String> {
        val state = log.state
        return state.wordUses.keys + state.pairs.keys.map { "${it.prev} ${it.next}" }
    }

    /** The words counted, `text(reading)`, and the pairs, `a(..) b(..)`, as the old model's tests read them. */
    fun learned(): Set<String> {
        val out = LinkedHashSet<String>()
        model.forEachCount({ e, _ -> out += e.toString() }) { a, b, _ -> out += "$a $b" }
        return out
    }
}
