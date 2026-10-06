/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

/**
 * What the user commits for what they type, where the engine did not offer it first: `ee` for
 * 嗯嗯. The decoder reads `ee` as e e (呃呃), and 嗯嗯 (en en) only as two syllables each the start
 * of a longer one, a penalty each that learning the word never makes up: put together from 嗯
 * and 嗯 once, it stayed far down. A habit is offered first for its keys instead, however the
 * decoder reads them.
 *
 * Each text of a key counts its commits: [learn] adds one to what was committed, unless the
 * engine offers it first on its own, and halves the others of the key, passed over. The text
 * counted most is the habit, from [MIN_COUNT] on. Keys are as typed and per input method
 * ([scope]): `ee` in 双拼 is other syllables. At most [limit] are kept, those counted least going
 * first. What the session teaches it is its to say (PinyinSession: text put together from pieces).
 */
class KeyHabits(private val limit: Int = DEFAULT_LIMIT) {
    init {
        require(limit > 0) { "limit $limit" }
    }

    /** Hears of each count as it changes, to keep it: see [UserStore]. Zero is gone. */
    fun interface Journal {
        fun changed(keys: String, text: String, count: Float)
    }

    var journal: Journal? = null

    // scoped keys, to the texts committed for them and their counts
    private val habits = HashMap<String, HashMap<String, Float>>()

    /** The habits of one input method: its keys apart from another's. */
    inner class Scope internal constructor(private val name: String) {
        private fun key(keys: String) = name + SCOPE_END + keys

        /** The text to offer first for [keys], or null if there is none. */
        fun habit(keys: String): String? = this@KeyHabits.habit(key(keys))

        /**
         * [text] committed for [keys], where the engine would offer [own] first without habits:
         * counted unless it is that, which needs no habit; the key's others, passed over, halved.
         */
        fun learn(keys: String, text: String, own: String?) {
            if (keys.length <= MAX_KEY) this@KeyHabits.learn(key(keys), text, own)
        }

        /** Forgets every habit of [text], of any input method. */
        fun forget(text: String) = this@KeyHabits.forget(text)
    }

    fun scope(name: String): Scope {
        require(SCOPE_END !in name) { "scope $name" }
        return Scope(name)
    }

    private fun habit(key: String): String? =
        habits[key]?.maxByOrNull { it.value }?.takeIf { it.value >= MIN_COUNT }?.key

    private fun learn(key: String, text: String, own: String?) {
        if (text.isEmpty() || text.length > MAX_TEXT) return
        // the others committed for these keys before, passed over now
        habits[key]?.filterKeys { it != text }?.forEach { (other, count) -> set(key, other, count / 2) }
        if (text == own) return
        set(key, text, (habits[key]?.get(text) ?: 0f) + 1f)
        trim(key, text)
    }

    /** A count as the log has it, not learned again: zero is none. */
    fun restore(scopedKeys: String, text: String, count: Float) {
        if (count < DROP) habits[scopedKeys]?.let { texts ->
            texts.remove(text)
            if (texts.isEmpty()) habits.remove(scopedKeys)
        } else {
            habits.getOrPut(scopedKeys) { HashMap() }[text] = count
        }
    }

    /** Forgets every habit of [text], a word the user forgot or blocked. */
    fun forget(text: String) = forgetAll(setOf(text))

    /** [forget] for each of [texts], in one pass. */
    fun forgetAll(texts: Set<String>) {
        if (texts.isEmpty()) return
        val gone = habits.flatMap { (key, kept) -> kept.keys.filter { it in texts }.map { key to it } }
        gone.forEach { (key, text) -> set(key, text, 0f) }
    }

    /** Each count kept, its keys scoped as [restore] takes them. */
    fun forEach(count: (scopedKeys: String, text: String, count: Float) -> Unit) {
        for ((key, texts) in habits) for ((text, c) in texts) count(key, text, c)
    }

    val size: Int get() = habits.values.sumOf { it.size }

    private fun set(key: String, text: String, count: Float) {
        val kept = if (count < DROP) 0f else count
        restore(key, text, kept)
        journal?.changed(key, text, kept)
    }

    // the least counted go when there are too many, but not the one just learned
    private fun trim(key: String, text: String) {
        val over = size - limit
        if (over <= 0) return
        val order = compareBy<Triple<String, String, Float>>({ it.third }, { it.first }, { it.second })
        val all = habits.flatMap { (k, texts) -> texts.map { Triple(k, it.key, it.value) } }
            .filter { it.first != key || it.second != text }
        // one over, as each learn at the limit is: the least of them, not all of them sorted
        val least = if (over == 1) listOfNotNull(all.minWithOrNull(order)) else all.sortedWith(order).take(over)
        least.forEach { (key, text, _) -> set(key, text, 0f) }
    }

    companion object {
        const val DEFAULT_LIMIT = 5000

        /** Committed once is a habit: what the user had to look for, they want first next time. */
        const val MIN_COUNT = 1f

        // passed over twice, a habit of one is gone
        private const val DROP = 0.3f
        private const val MAX_KEY = 64
        private const val MAX_TEXT = 32
        private const val SCOPE_END = '\u0000'
    }
}
