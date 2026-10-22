/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/** A clock a test moves: UTC+8 unless told otherwise. */
internal class FakeClock(
    @Volatile var at: Long = START,
    @Volatile var zone: Int = 8 * HOUR,
) : LearnClock {
    override fun now() = at
    override fun offset(at: Long) = zone

    fun minutes(n: Int) {
        at += n * LearnClock.MINUTE
    }

    fun days(n: Int) {
        at += n * LearnClock.DAY
    }

    companion object {
        const val HOUR = 3_600_000

        /** 2026-10-20 10:00 in UTC+8. */
        const val START = 1_792_461_600_000L
    }
}

internal fun word(text: String, reading: String = "") = Word(text, reading)

internal fun row(
    keys: String,
    text: String,
    reading: String = "",
    prev: String = "",
    flags: Int = Row.PICK or Row.WHOLE,
    kind: String = Kinds.FULL,
    how: Char = 'A',
    origin: Int = Row.DICTIONARY,
    shown: String = "",
) = Row(flags, Code(kind, keys), prev, Words.of(text, reading), how, origin, shown)

internal fun corrected(keys: String, text: String, reading: String = "", prev: String = "", origin: Int = Row.DICTIONARY, shown: String = "") =
    row(keys, text, reading, prev, flags = Row.PICK or Row.WHOLE or Row.CORRECTED, how = 'B', origin = origin, shown = shown)

/** What a session records, as written: learns, reads nothing. */
internal class RecordingHabits : Habits by Habits.OFF {
    val commits = ArrayList<List<Row>>()
    val takes = ArrayList<List<Take>>()
    val pins = ArrayList<String>()
    val forgotten = ArrayList<String>()
    val flags = ArrayList<Int>()

    override val learns get() = true

    override fun record(rows: List<Row>, takes: List<Take>, flags: Int): LearnRecord.Commit? {
        commits += rows
        this.flags += flags
        this.takes += takes
        return null
    }

    // what is pinned, as the record has it: a session offers to unpin it
    private val pinned = HashMap<Code, Words>()

    override fun pin(code: Code, text: String, reading: String, on: Boolean) {
        pins += "$code $text ${if (on) "on" else "off"}"
        if (on) pinned[code] = Words.of(text, reading) else pinned.remove(code)
    }

    override fun pinned(code: Code): Words? = pinned[code]

    // what a long press may forget: a text committed or pinned
    override fun knows(text: String) = commits.any { rows -> rows.any { it.words.text == text } } || pinned.values.any { it.text == text }

    override fun forget(text: String) {
        forgotten += text
    }

    val last get() = commits.last()
}

/** OpenCC's character tables as the app ships them (app/src/main/assets/learn). */
internal val openccSimplifier: Simplifier by lazy {
    val dir = java.io.File("../../app/src/main/assets/learn")
    Simplifier.read(java.io.File(dir, "TSCharacters.txt").readBytes(), java.io.File(dir, "STCharacters.txt").readBytes())
}

/** A simplifier of [pairs], each a traditional character and its simplified one, in OpenCC's tables' form. */
internal fun simplifierOf(vararg pairs: Pair<Char, Char>): Simplifier =
    Simplifier.read(pairs.joinToString("") { (t, s) -> "$t\t$s\n" }.toByteArray(), ByteArray(0))
