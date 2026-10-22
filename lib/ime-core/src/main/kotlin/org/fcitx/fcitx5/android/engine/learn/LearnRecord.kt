/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/** A word as learned: its text, simplified, and its reading, spelt syllables joined by spaces; "" where none (a table's code, letters). */
data class Word(val text: String, val reading: String)

/**
 * Words as the user chose them, counted by their text and reading together: 点｜了 picked as two
 * words and 点了 once it is a word are one row (PLAN §2.1), the words kept to find the row again.
 */
class Words(val list: List<Word>) {
    val text: String = list.joinToString("") { it.text }
    val reading: String = list.map { it.reading }.filter { it.isNotEmpty() }.joinToString(" ")

    override fun equals(other: Any?) = other is Words && text == other.text && reading == other.reading
    override fun hashCode() = text.hashCode() * 31 + reading.hashCode()
    override fun toString() = if (reading.isEmpty()) text else "$text($reading)"

    companion object {
        fun of(text: String, reading: String = "") = Words(listOf(Word(text, reading)))
    }
}

/** What was typed, as learning tells it apart: the keyboard's [kind] (see [Kinds]) and the [keys] pressed. */
data class Code(val kind: String, val keys: String) {
    override fun toString() = "$kind:$keys"
}

/**
 * The kinds of [Code], named in the log: never renamed, or every row learned under one is
 * orphaned. A 双拼 scheme by the name libime's config gives it (`EngineSettings.SCHEMES`), the
 * nine keys by their letters ([org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.tag]).
 */
object Kinds {
    const val FULL = "qp"
    const val HANDWRITING = "hw"
    /** 联想 picked, in any input method: keyed by the word it follows, by its text, never pinyin's order (review C S1). */
    const val PREDICTION = "pred"
    private const val TABLE = "tb:"

    fun shuangpin(scheme: String) = "sp:$scheme"
    fun nineKeys(layout: String) = "t9:$layout"
    fun nineKeysMultiTap(layout: String) = "t9m:$layout"
    fun table(im: String) = "$TABLE$im"

    /** The table input method a table's [kind] is of. */
    fun tableOf(kind: String) = kind.removePrefix(TABLE)

    /** Whether [kind] is a table's, handwriting's or 联想's: their words have no reading, and are counted by their text. */
    fun byText(kind: String) = kind == HANDWRITING || kind == PREDICTION || kind.startsWith(TABLE)
}

/**
 * One learning row of a commit (PLAN §2.1): [words] under [code] after [prev] ("" for none), how
 * they were committed ([how], 'A' to 'H' of the spec's §1), what the user picked from shown
 * first there ([shown]), and where the text comes from ([origin], at the time of writing).
 */
class Row(
    val flags: Int,
    val code: Code,
    val prev: String,
    val words: Words,
    val how: Char,
    val origin: Int,
    val shown: String,
) {
    val corrected get() = flags and CORRECTED != 0
    val whole get() = flags and WHOLE != 0
    val ownFirst get() = flags and OWN_FIRST != 0

    override fun equals(other: Any?) = other is Row && flags == other.flags && code == other.code && prev == other.prev &&
        words.list == other.words.list && how == other.how && origin == other.origin && shown == other.shown
    override fun hashCode() = (code.hashCode() * 31 + prev.hashCode()) * 31 + words.hashCode()
    override fun toString() = "$code|$prev|$words|$how${if (corrected) "*" else ""}|${flags and LEVELS}"

    companion object {
        /** Level 1: a pick. */
        const val PICK = 1
        /** Level 2: the whole commit. */
        const val WHOLE = 2
        /** Level 3: a word of a pick the first shown has as a word too. */
        const val WORD = 4
        const val LEVELS = PICK or WHOLE or WORD
        const val CORRECTED = 8
        /** U-03: what was retyped after an undo, counted under the undone commit's code (1c). */
        const val RETYPED = 16
        /** A pick of the decoder's or table's own first while another was shown first (F2739): a use of the original first. */
        const val OWN_FIRST = 32

        /** [how]: the first shown put in. */
        const val HOW_FIRST = 'A'
        /** [how]: another than the first picked. */
        const val HOW_PICKED = 'B'

        const val DICTIONARY = 0
        const val ADDED = 1
        const val IMPORTED = 2
        const val NONE = 3
    }
}

/** A syllable taken from the nine keys' bar, for its digits, that the commit kept. */
data class Take(val layout: String, val digits: String, val syllable: String)

/** A first, the words corrected to since it was last used, the typing day last typed, and the commit that changed it last. */
data class First(val first: Words?, val set: List<Words>, val lastDay: Int, val lastBy: Long)

/** Text committed once, not yet the user's word (U-06). */
data class Pending(val reading: String, val firstDay: Int, val lastBy: Long)

/** The user's word: how it became one, since when, and when last used. */
data class Mine(
    val reading: String,
    val how: Int,
    val since: Int,
    val sinceSeq: Long,
    val lastUsedDay: Int,
    val lastUsedLocalDay: Int,
    val lastBy: Long,
) {
    companion object {
        const val FORMED = 0
        const val FORMED_FROM_IMPORT = 1
        const val CONVERTED = 2
        /** Brought in whole: a learned word of the user's own export, a phrase a table saved. */
        const val TAKEN_IN = 3
    }
}

/** A row as it was before a commit changed it, null for none: what an [LearnRecord.Undo] puts back. */
sealed class Before {
    data class OfFirst(val code: Code, val prev: String, val value: First?) : Before()
    data class OfPending(val text: String, val value: Pending?) : Before()
    data class OfMine(val text: String, val value: Mine?) : Before()
}

/** What `learn.log` holds ([LearnRecords]): events, then, compacted, a snapshot's rows. */
sealed class LearnRecord {
    /** The epoch minute it was written at, for the cuts; null for one that has none. */
    open val minute: Int? get() = null

    data class Commit(
        val seq: Long,
        override val minute: Int,
        val day: Int,
        val localDay: Int,
        val flags: Int,
        val rows: List<Row>,
        val takes: List<Take> = emptyList(),
    ) : LearnRecord() {
        companion object {
            /** A field of URLs, e-mail, numbers, phone numbers or dates: no pairs learned (U-91). */
            const val NO_PAIRS = 1
            /** A search field: learned, not predicted (U-91). */
            const val NO_PREDICT = 2

            /** Either: no pairs learned, as 联想 goes by them. */
            const val UNPAIRED = NO_PAIRS or NO_PREDICT
        }
    }

    /** [undone], the commit taken back whole, and the rows it changed as they were. */
    data class Undo(
        val undoneSeq: Long,
        override val minute: Int,
        val day: Int,
        val localDay: Int,
        val undone: LearnRecord,
        val befores: List<Before>,
    ) : LearnRecord()

    data class Pin(override val minute: Int, val day: Int, val code: Code, val text: String, val reading: String, val on: Boolean) : LearnRecord()
    data class Forget(override val minute: Int, val day: Int, val text: String) : LearnRecord()
    data class NoPredict(override val minute: Int, val prev: String, val text: String, val on: Boolean) : LearnRecord()
    data class TableOrderReset(override val minute: Int, val im: String) : LearnRecord()

    /** [text] blocked under the table [im]'s [code] alone ([on]), or no more: see [TableBlockRow]. */
    data class TableBlock(override val minute: Int, val im: String, val code: String, val text: String, val on: Boolean) : LearnRecord()

    data class EnCommit(
        val seq: Long,
        override val minute: Int,
        val day: Int,
        val localDay: Int,
        val flags: Int,
        val prev: String,
        val word: String,
        val typed: String,
        val how: Int,
    ) : LearnRecord() {
        companion object {
            const val TYPED = 0
            const val AUTOCORRECTED = 1
            const val PICKED = 2
            const val REVERTED = 3
        }
    }

    data class EnForget(override val minute: Int, val word: String) : LearnRecord()
    data class EnAdd(override val minute: Int, val word: String, val on: Boolean) : LearnRecord()

    /**
     * The English [word] corrected again (U-31 恢复): that it is never corrected, or was added by
     * hand, taken off; its uses and pairs kept.
     */
    data class EnCorrect(override val minute: Int, val word: String) : LearnRecord()
    data class Restored(override val minute: Int) : LearnRecord()
    data class ImportBatch(val id: Int, val file: String, val localDay: Int, val count: Int) : LearnRecord()
    data class Imported(val batch: Int, val order: Int, val text: String, val reading: String) : LearnRecord()
    data class ImportUndo(val batch: Int) : LearnRecord()

    /**
     * What an older build changed of its files since they were last converted (a downgrade, then
     * this build again; review-1a-app S4): the user's words, the tables' saved words and the
     * English words' flags it [added] (where none is kept already), and those it [removed]: a
     * word of the user's forgotten everywhere, a table's word, an English word. Rows of
     * [MineRow], [TableWordRow] and [EnWordRow] only; counts are not carried.
     */
    data class Absorbed(override val minute: Int, val added: List<LearnRecord>, val removed: List<LearnRecord>) : LearnRecord()

    // a snapshot's rows

    data class Head(
        val cut: Int,
        val lastMinute: Int,
        val day: Int,
        val localDay: Int,
        val nextSeq: Long,
        val convertedDay: Int = -1,
        val restoredMinute: Int = -1,
        val convertedTables: String = "",
    ) : LearnRecord()

    /** [firstSeq]: the commit that first used [words] under [code] (impl/design-1b.md §4); 0 in a row of 1a's. */
    class StatRow(val code: Code, val words: Words, val uses: DayCounts, val corrections: DayCounts, val older: Int, val firstSeq: Long = 0) : LearnRecord() {
        override fun equals(other: Any?) = other is StatRow && code == other.code && words.list == other.words.list &&
            uses == other.uses && corrections == other.corrections && older == other.older && firstSeq == other.firstSeq
        override fun hashCode() = code.hashCode() * 31 + words.hashCode()
    }

    data class FirstRow(val code: Code, val prev: String, val value: First) : LearnRecord()
    data class PendingRow(val text: String, val value: Pending) : LearnRecord()
    data class MineRow(val text: String, val value: Mine) : LearnRecord()
    data class PairRow(val prev: String, val next: String, val nextReading: String, val count: Int, val lastDay: Int) : LearnRecord()
    data class PinRow(val code: Code, val text: String, val reading: String) : LearnRecord()
    data class BarRow(val take: Take, val count: Int, val lastDay: Int) : LearnRecord()
    data class EnWordRow(val word: String, val flags: Int, val uses: DayCounts) : LearnRecord() {
        companion object {
            const val NEVER_CORRECT = 1
            const val ADDED = 2
            const val OWN = 4
        }
    }
    data class EnPairRow(val prev: String, val word: String, val count: Int, val lastDay: Int) : LearnRecord()
    data class TableWordRow(val im: String, val code: String, val text: String, val since: Int) : LearnRecord()
    data class NoPredictRow(val prev: String, val text: String) : LearnRecord()

    /** [text] not offered under the table [im]'s [code] alone (U-28 屏蔽): pinyin's blocks never hide a table's own entries (C12). */
    data class TableBlockRow(val im: String, val code: String, val text: String) : LearnRecord()
    object Cut : LearnRecord() {
        override fun toString() = "Cut"
    }
    data class BatchRow(val id: Int, val file: String, val localDay: Int, val count: Int) : LearnRecord()
    data class ImportedRow(val batch: Int, val order: Int, val text: String, val reading: String) : LearnRecord()

    /**
     * A word of the whole commits, [text] read [reading] ("" for a table's, handwriting's), used on
     * each typing day of [uses] (impl/design-1b.md §4): what the decoder's counts are made of. Not
     * made again from the other rows, which keep no level: kept as it is in a snapshot.
     */
    class WordUseRow(val text: String, val reading: String, val uses: DayCounts) : LearnRecord() {
        override fun equals(other: Any?) = other is WordUseRow && text == other.text && reading == other.reading && uses == other.uses
        override fun hashCode() = text.hashCode() * 31 + reading.hashCode()
        override fun toString() = "WordUseRow($text, $reading, $uses)"
    }

    /** The calendar day ([localDay]) typing day [day] was: what a word's last use (U-80) shows. Stream E's, type 80. */
    data class DateRow(val day: Int, val localDay: Int) : LearnRecord()

    /** A record of a type this build does not know: kept as it is, so a fold here loses nothing a later build wrote. */
    class Unknown(val type: Byte, val fields: ByteArray) : LearnRecord() {
        override fun equals(other: Any?) = other is Unknown && type == other.type && fields.contentEquals(other.fields)
        override fun hashCode() = type * 31 + fields.contentHashCode()
        override fun toString() = "Unknown($type, ${fields.size} bytes)"
    }
}
