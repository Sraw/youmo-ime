/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/** A word as learned: simplified text and its reading (spelt syllables space-separated; "" for none: a table code, letters). */
data class Word(val text: String, val reading: String)

/** Words as chosen, counted by text and reading together: 点｜了 picked as two words and 点了 once a word are one row; the words find the row again. */
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
 * The kinds of [Code], stored in the log: never rename one, or every row under it is orphaned. A 双拼
 * scheme by its libime config name (`EngineSettings.SCHEMES`), the nine keys by their letters
 * ([org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.tag]).
 */
object Kinds {
    const val FULL = "qp"
    const val HANDWRITING = "hw"
    /** 联想 picked, in any input method: keyed by the word it follows, by text, never pinyin's order. */
    const val PREDICTION = "pred"
    private const val TABLE = "tb:"
    private const val SHUANGPIN = "sp:"
    private const val KEYS = "qk:"
    private const val SHUANGPIN_KEYS = "spk:"

    /** Zhuyin keyboards (注音九键, 大千): their key codes ([org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys]), a kind each. */
    const val ZHUYIN_NINE = "zy:nine"
    const val ZHUYIN_DACHEN = "zy:dachen"

    /** Zhuyin keys are symbols, filed by the code itself as 双拼's are. */
    fun isZhuyin(kind: String) = kind.startsWith("zy:")

    fun shuangpin(scheme: String) = "$SHUANGPIN$scheme"
    fun nineKeys(layout: String) = "t9:$layout"
    fun nineKeysMultiTap(layout: String) = "t9m:$layout"

    /** A grouped keyboard's keys, [tag] its groups (NineKeyLetters.tag, `qw,er,…,m` for 14 keys): apart from qp and t9. */
    fun keys(tag: String) = "$KEYS$tag"

    /**
     * 双拼 on a grouped keyboard: [scheme] as in [shuangpin], [tag] the keys' (NineKeyLetters.G18 or
     * NINE_SP): apart from sp: and from each other, as a scheme's keys mean other syllables on each. Not under
     * `sp:`: ask [isShuangpin] whether a kind is 双拼's.
     */
    fun shuangpinKeys(scheme: String, tag: String) = "$SHUANGPIN_KEYS$scheme:$tag"

    /** 双拼 on any keyboard ([shuangpin], [shuangpinKeys]). */
    fun isShuangpin(kind: String) = kind.startsWith(SHUANGPIN) || kind.startsWith(SHUANGPIN_KEYS)

    /** Null for a non-双拼 kind. */
    fun shuangpinScheme(kind: String): String? = when {
        kind.startsWith(SHUANGPIN_KEYS) -> kind.removePrefix(SHUANGPIN_KEYS).substringBefore(':')
        kind.startsWith(SHUANGPIN) -> kind.removePrefix(SHUANGPIN)
        else -> null
    }

    /** The layout a bar-taken syllable ([Take]) is kept under: `<scheme>:<tag>` for grouped 双拼 (its keys read other syllables than another scheme's); the keys' [tag] otherwise. */
    fun takeLayout(kind: String, tag: String) = if (kind.startsWith(SHUANGPIN_KEYS)) kind.removePrefix(SHUANGPIN_KEYS) else tag

    /** For a [keys] or [shuangpinKeys] kind; null for others. */
    fun keysTag(kind: String): String? = when {
        kind.startsWith(KEYS) -> kind.removePrefix(KEYS)
        // neither a scheme's name (EngineSettings.SCHEMES) nor the tag has a colon
        kind.startsWith(SHUANGPIN_KEYS) -> kind.substringAfter(':').substringAfter(':', "").ifEmpty { null }
        else -> null
    }

    /**
     * The grouped keyboard a [keys] or [shuangpinKeys] kind was typed on, to show its codes as letters
     * (`bn ui`): a `qk:` kind's [NineKeyLetters.filedAs], a `spk:` kind's [NineKeyLetters.grouped]; null for others.
     */
    fun keysOf(kind: String): NineKeyLetters? = keysTag(kind)?.let { tag ->
        if (kind.startsWith(KEYS)) NineKeyLetters.filedAs(tag) else NineKeyLetters.grouped(tag)
    }

    fun table(im: String) = "$TABLE$im"

    /** The keyboard [kind] is typed on (nine-key 连按 maps to the nine keys): spellings of one keyboard are linked. */
    fun keyboard(kind: String) = if (kind.startsWith(NINE_MULTI_TAP)) "t9:" + kind.removePrefix(NINE_MULTI_TAP) else kind

    private const val NINE_MULTI_TAP = "t9m:"

    fun tableOf(kind: String) = kind.removePrefix(TABLE)

    /** A table's, handwriting's or 联想's: words without a reading, counted by text. */
    fun byText(kind: String) = kind == HANDWRITING || kind == PREDICTION || kind.startsWith(TABLE)
}

/** One learning row: [words] under [code] after [prev] ("" for none), [how] committed ('A' to 'H'), [shown] first then, [origin] of the text when written. */
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
        /** Level 3: a pick's word the first shown also has as a word. */
        const val WORD = 4
        const val LEVELS = PICK or WHOLE or WORD
        const val CORRECTED = 8
        /** Retyped after an undo, counted under the undone commit's code. */
        const val RETYPED = 16
        /** A pick of the decoder's or table's own first while another was shown first: a use of the original first. */
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

/** A nine-key bar syllable the commit kept, for its digits; [layout] as Kinds.takeLayout gives it. */
data class Take(val layout: String, val digits: String, val syllable: String)

/** A first, the words corrected to since it was last used, the last typing day, and the commit that last changed it. */
data class First(val first: Words?, val set: List<Words>, val lastDay: Int, val lastBy: Long)

/** Text committed once, not yet the user's word. */
data class Pending(val reading: String, val firstDay: Int, val lastBy: Long)

/** The user's word: how it became one, since when, last used. */
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
        /** Brought in whole: a word from the user's export, a phrase a table saved. */
        const val TAKEN_IN = 3
    }
}

/** A row before a commit changed it (null for none): what [LearnRecord.Undo] restores. */
sealed class Before {
    data class OfFirst(val code: Code, val prev: String, val value: First?) : Before()
    data class OfPending(val text: String, val value: Pending?) : Before()
    data class OfMine(val text: String, val value: Mine?) : Before()
}

/** What `learn.log` holds ([LearnRecords]): events, then after compaction a snapshot's rows. */
sealed class LearnRecord {
    /** The epoch minute written, for the cuts; null if none. */
    open val minute: Int? get() = null

    data class Commit(
        val seq: Long,
        override val minute: Int,
        val day: Int,
        val localDay: Int,
        val flags: Int,
        val rows: List<Row>,
        val takes: List<Take> = emptyList(),
        /** A 联想 pick continued with a whole learned phrase: that phrase, used whole once more; "" for none. */
        val continued: String = "",
    ) : LearnRecord() {
        companion object {
            /** A field of URLs, e-mail, numbers, phone numbers or dates: no pairs learned. */
            const val NO_PAIRS = 1
            /** A search field: learned, not predicted. */
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
    /** [older]: written by a build whose Forget also dropped the tables' remembered words of [text], which replay keeps doing. */
    data class Forget(override val minute: Int, val day: Int, val text: String, val older: Boolean = false) : LearnRecord()

    /** A whole phrase deleted from its long press: its count alone, not words of the same text (added, imported, a table's saved). Type 16. */
    data class PhraseForget(override val minute: Int, val text: String) : LearnRecord()
    data class NoPredict(override val minute: Int, val prev: String, val text: String, val on: Boolean) : LearnRecord()
    data class TableOrderReset(override val minute: Int, val im: String) : LearnRecord()

    /** A table's 忘掉学到的: [text]'s order learned under [code] of table [im] alone. Type 17. */
    data class TableForget(override val minute: Int, val im: String, val code: String, val text: String) : LearnRecord()

    /** [text] blocked under table [im]'s [code] alone ([on]), or unblocked: see [TableBlockRow]. */
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

    /** 恢复: the English [word] may be corrected again (never-corrected and added flags off); uses and pairs kept. */
    data class EnCorrect(override val minute: Int, val word: String) : LearnRecord()
    data class Restored(override val minute: Int) : LearnRecord()
    data class ImportBatch(val id: Int, val file: String, val localDay: Int, val count: Int) : LearnRecord()
    data class Imported(val batch: Int, val order: Int, val text: String, val reading: String) : LearnRecord()
    data class ImportUndo(val batch: Int) : LearnRecord()

    /**
     * What an older build changed in its files since last converted (a downgrade, then this build again):
     * the user's words, tables' saved words and English word flags it [added] (where not kept already), and
     * those it [removed]. Only [MineRow], [TableWordRow] and [EnWordRow] rows; counts are not carried.
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

    /** [firstSeq]: the commit that first used [words] under [code]; 0 in an older row. */
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
    /** [prev] "" for a whole phrase turned off everywhere: a word's 联想 always follows one. */
    data class NoPredictRow(val prev: String, val text: String) : LearnRecord()

    /** A whole commit's text of 3 to [Phrases.MAX] letters, committed whole [count] times, last on typing day [lastDay]. Type 49. */
    data class PhraseRow(val text: String, val count: Int, val lastDay: Int) : LearnRecord()

    /** 屏蔽: [text] not offered under table [im]'s [code] alone; pinyin's blocks never hide a table's own entries. */
    data class TableBlockRow(val im: String, val code: String, val text: String) : LearnRecord()
    object Cut : LearnRecord() {
        override fun toString() = "Cut"
    }
    data class BatchRow(val id: Int, val file: String, val localDay: Int, val count: Int) : LearnRecord()
    data class ImportedRow(val batch: Int, val order: Int, val text: String, val reading: String) : LearnRecord()

    /**
     * A word of the whole commits, [text] read [reading] ("" for tables, handwriting), used on each typing day
     * of [uses]: what the decoder's counts are made of. Cannot be rebuilt from other rows (which keep no
     * level), so a snapshot keeps it as it is.
     */
    class WordUseRow(val text: String, val reading: String, val uses: DayCounts) : LearnRecord() {
        override fun equals(other: Any?) = other is WordUseRow && text == other.text && reading == other.reading && uses == other.uses
        override fun hashCode() = text.hashCode() * 31 + reading.hashCode()
        override fun toString() = "WordUseRow($text, $reading, $uses)"
    }

    /** The calendar day ([localDay]) typing day [day] was: shown as a word's last use. Type 80. */
    data class DateRow(val day: Int, val localDay: Int) : LearnRecord()

    /** A record of a type this build does not know: kept as it is, so a fold here loses nothing a later build wrote. */
    class Unknown(val type: Byte, val fields: ByteArray) : LearnRecord() {
        override fun equals(other: Any?) = other is Unknown && type == other.type && fields.contentEquals(other.fields)
        override fun hashCode() = type * 31 + fields.contentHashCode()
        override fun toString() = "Unknown($type, ${fields.size} bytes)"
    }
}
