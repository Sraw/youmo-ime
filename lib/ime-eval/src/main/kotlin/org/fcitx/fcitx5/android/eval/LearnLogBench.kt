/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.SpelledForm
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.File
import kotlin.math.pow
import kotlin.random.Random

/**
 * How long a heavy user's learning record takes to open (impl/design-1a.md §8): [commits]
 * commits over [days] typing days of words drawn as people type them (a few common, most rare),
 * corrected now and then, after the word before; the log as written, unfolded, then folded as a
 * start the next day folds it. Times are the best of a few opens, on this machine's JVM. A commit
 * here is one row; the phone's carry a word's pieces and pairs too, so sizes and times are a lower bound.
 */
internal class LearnLogBench(private val commits: Int, private val days: Int, private val seed: Int = 7) {

    class Result(
        val unfoldedBytes: Long,
        val unfoldedOpenMs: Long,
        val foldMs: Long,
        val foldedBytes: Long,
        val foldedOpenMs: Long,
        val stats: Int,
        val mine: Int,
        val pairs: Int,
        val spelledMs: Double,
        val heapBytes: Long,
        val spelledRows: Int,
    ) {
        fun lines() = listOf(
            "unfolded log      %,d bytes, opened in %d ms".format(unfoldedBytes, unfoldedOpenMs),
            "fold at start     %d ms".format(foldMs),
            "folded log        %,d bytes, opened in %d ms".format(foldedBytes, foldedOpenMs),
            "state             %,d used rows (%,d of them indexed for U-20), %,d words of the user's, %,d pairs; %,d bytes of heap".format(stats, spelledRows, mine, pairs, heapBytes),
            "spellings         %.3f ms at a key (U-20: the first key after a commit, of the most used word's first letter)".format(spelledMs),
        )
    }

    private class Clock(var at: Long) : LearnClock {
        override fun now() = at
        override fun offset(at: Long) = OFFSET
    }

    init {
        require(days in 1..commits) { "--days must be 1 to --commits" }
    }

    /** The bench in [dir], which must have nothing in it: what it writes there it deletes first. */
    fun run(dir: File): Result {
        require(dir.listFiles().orEmpty().all { it.name.startsWith(LearnLog.FILE) }) { "$dir: not empty" }
        val file = JvmFile(File(dir, LearnLog.FILE))
        file.delete()
        val clock = Clock(START)
        val random = Random(seed)
        val vocabulary = Array(VOCABULARY) { word(it) }
        // written as the phone would, but folded only at the end: the worst a start may find
        LearnLog(file, clock, compactAt = Long.MAX_VALUE, foldDaily = false).use { log ->
            log.open()
            val perDay = commits / days
            var prev = ""
            repeat(commits) { n ->
                if (n > 0 && n % perDay == 0) clock.at += LearnClock.DAY
                clock.at += LearnClock.MINUTE / 2
                val w = vocabulary[zipf(random)]
                val corrected = random.nextInt(CORRECTED_ONE_IN) == 0
                val flags = Row.PICK or Row.WHOLE or (if (corrected) Row.CORRECTED else 0)
                val keys = w.reading.replace(" ", "")
                val origin = if (w.text.length > 2) Row.NONE else Row.DICTIONARY
                log.record(listOf(Row(flags, Code(Kinds.FULL, keys), prev, Words(listOf(w)), if (corrected) 'B' else 'A', origin, "")))
                prev = w.text
            }
        }
        val unfolded = file.length()
        val unfoldedOpen = best { LearnLog(file, clock, foldDaily = false).also { it.open() }.close() }
        clock.at += LearnClock.DAY
        val foldStart = System.nanoTime()
        LearnLog(file, clock).also { it.open() }.close()
        val fold = (System.nanoTime() - foldStart) / NANOS_PER_MS
        var state = LearnLog(file, clock).also { it.open() }.also { it.close() }.state
        val foldedOpen = best { state = LearnLog(file, clock).also { it.open() }.also { it.close() }.state }
        // what a key pays to count the words across the spellings (R14 F3733): the first letter of most rows;
        // and what the state holds, its indexes in it (R14 F3761)
        val before = usedHeap()
        val opened = LearnLog(file, clock).also { it.open() }
        val heap = usedHeap() - before
        val spelledRows = opened.state.stats.keys.count { SpelledForm.of(it.code.kind, it.code.keys, it.reading).let { f -> f == SpelledForm.FULL || f == SpelledForm.INITIALS } }
        val first = vocabulary[0].reading.first()
        // anew each time: as the first key after a commit finds it, each word's counts read once with
        // its reading split (S15 B5.1), what HabitOrder's levels then read
        val spelled = bestNanos { LogHabits({ opened }).spelledFrom(Kinds.FULL, first).size } / NANOS_PER_MS.toDouble()
        opened.close()
        return Result(unfolded, unfoldedOpen, fold, file.length(), foldedOpen, state.stats.size, state.mine.size, state.pairs.size, spelled, heap, spelledRows)
    }

    /** What the counts cost on [run]'s record: made anew (a new typing day, a word formed), and a key typed (review-1b S1). */
    class Counts(
        val attachMs: Long,
        val rebuildMs: Double,
        val formedMs: Double,
        val keyMs: Double,
        val keyBareMs: Double,
        val keyKeptMs: Double,
        val keyImportedMs: Double,
        val words: Int,
        val pairs: Int,
    ) {
        fun lines() = listOf(
            "counts            attached in %d ms (%,d words of the user's, %,d pairs); made anew %.1f ms".format(attachMs, words, pairs, rebuildMs),
            "a word formed     %.2f ms".format(formedMs),
            "a key typed       %.2f ms with the record, %.2f ms without".format(keyMs, keyBareMs),
            "                  %.2f ms with %d words added by hand and %d pins over the keys kept in sentences".format(keyKeptMs, ADDED, PINS.size),
            "                  %.2f ms with those and %,d words imported, of the keys' syllables".format(keyImportedMs, IMPORTED),
        )
    }

    /** [run], then [Counts] on [data]'s model in [dir]. */
    fun counts(data: PinyinData, dir: File): Counts {
        run(dir)
        val clock = Clock(START + (days + 1) * LearnClock.DAY)
        val log = LearnLog(JvmFile(File(dir, LearnLog.FILE)), clock, foldDaily = false).also { it.open() }
        val state = log.state
        val model = UserModel(data.dictionary, data.vocabulary)
        val attach = best { HabitCounts(model).attach(state) }
        val counts = HabitCounts(model).also { it.attach(state) }
        val rebuild = bestNanos { counts.rebuild(state) } / NANOS_PER_MS.toDouble()
        // one word formed: what the counts do of it
        // none where a small --commits formed none
        val formed = vocabulary(state).firstOrNull()?.let { word ->
            val changes = LearnState.Changes().apply { formed[word.first] = word.second }
            bestNanos { counts.apply(state, changes) } / NANOS_PER_MS.toDouble()
        } ?: Double.NaN
        // the session made before the clock starts: a key's cost, not a session's
        fun typing(learnTo: LearnTo, user: UserModel?, added: Set<String> = emptySet()): Double {
            val session = PinyinSession(data, PinyinSegmenter(), user = user, prediction = false, learnTo = learnTo, addedWords = { added })
            return bestNanos {
                session.apply(Action.Reset)
                for (c in KEYS) session.apply(Action.Key(c))
            } / NANOS_PER_MS.toDouble() / KEYS.length
        }
        val key = typing(LearnTo(LogHabits({ log })), model)
        val bare = typing(LearnTo(), UserModel(data.dictionary, data.vocabulary))
        // what keeping words in sentences costs: words added by hand, some over the keys, and pins (review-1c N8)
        val kept = UserModel(data.dictionary, data.vocabulary).also { HabitCounts(it).attach(state) }
        val random = Random(seed)
        val added = (0 until ADDED).mapTo(LinkedHashSet()) { i ->
            val text = String(Character.toChars(ADDED_TEXT + i * 3)) + String(Character.toChars(ADDED_TEXT + i * 3 + 1)) + String(Character.toChars(ADDED_TEXT + i * 3 + 2))
            kept.listByHand(UserModel.Entry(text, IntArray(3) { Syllables.id(ADDED_SYLLABLES[random.nextInt(ADDED_SYLLABLES.size)]) }))
            text
        }
        for ((keys, word) in PINS) log.pin(Code(Kinds.FULL, keys), word.first, word.second, on = true)
        val keyKept = typing(LearnTo(LogHabits({ log })), kept, added)
        // a large list imported, words the keys' starts read among them: each level sorts those it offers (review-int-runtime N4)
        val list = (0 until IMPORTED).map { i ->
            val n = 2 + i % 2
            val text = (0 until n).joinToString("") { k -> String(Character.toChars(IMPORTED_TEXT + (i * n + k) % IMPORTED_SPAN)) }
            ImportedWords.Incoming(text, (0 until n).joinToString(" ") { ADDED_SYLLABLES[random.nextInt(ADDED_SYLLABLES.size)] }, random.nextInt(100).toDouble())
        }
        log.imports(ImportedWords.plan(1, "bench.txt", 0, list) { _, _ -> false })
        kept.attachImports(log.state.imports)
        val keyImported = typing(LearnTo(LogHabits({ log })), kept, added)
        log.close()
        return Counts(attach, rebuild, formed, key, bare, keyKept, keyImported, state.mine.size, state.pairs.size)
    }

    private fun vocabulary(state: LearnState) = state.mine.entries.map { it.key to it.value.reading }

    private inline fun bestNanos(run: () -> Unit): Long = (0 until WARM_RUNS).minOf {
        val start = System.nanoTime()
        run()
        System.nanoTime() - start
    }

    // the heap in use after a collection: a lower bound of what is live, enough for a difference
    private fun usedHeap(): Long {
        repeat(2) { System.gc() }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    // the best of a few runs, in milliseconds: the first warms the JVM
    private inline fun best(run: () -> Unit): Long = (0 until RUNS).minOf {
        val start = System.nanoTime()
        run()
        (System.nanoTime() - start) / NANOS_PER_MS
    }

    // a rank drawn as words are used: the n-th about 1/n as often
    private fun zipf(random: Random): Int {
        val u = random.nextDouble()
        return ((VOCABULARY + 1.0).pow(u) - 1).toInt().coerceIn(0, VOCABULARY - 1)
    }

    // a word of two to four characters, as pinyin of as many syllables
    private fun word(i: Int): Word {
        val length = 2 + i % 3
        val text = StringBuilder()
        val reading = ArrayList<String>()
        var n = i
        repeat(length) {
            text.appendCodePoint(HAN_FIRST + n % HAN_COUNT)
            reading += SYLLABLES[n % SYLLABLES.size]
            n = n / SYLLABLES.size + it * PRIME
        }
        return Word(text.toString(), reading.joinToString(" "))
    }

    private companion object {
        const val VOCABULARY = 40_000
        const val KEYS = "womenjintianqunali"

        // words added by hand, read of the keys' syllables and others, so some are over the keys; pins over them
        const val ADDED = 50
        const val ADDED_TEXT = 0x6C00
        const val IMPORTED = 100_000
        const val IMPORTED_TEXT = 0x4E00
        const val IMPORTED_SPAN = 0x5000
        val ADDED_SYLLABLES = arrayOf("wo", "men", "jin", "tian", "qu", "na", "li", "ma", "shi", "hao")
        val PINS = listOf("jintian" to ("今天" to "jin tian"), "qunali" to ("去哪里" to "qu na li"))

        // the small times: the best of more runs, the JIT warmed as typing all day warms it
        const val WARM_RUNS = 30
        const val CORRECTED_ONE_IN = 8
        const val RUNS = 5
        const val NANOS_PER_MS = 1_000_000L
        const val OFFSET = 8 * 3_600_000
        const val START = 1_760_000_000_000L
        const val HAN_FIRST = 0x4E00
        const val HAN_COUNT = 20_000
        const val PRIME = 7919
        val SYLLABLES = listOf("ni", "hao", "zhong", "guo", "shi", "jie", "wo", "men", "de", "le", "zai", "you", "xue", "sheng", "lao", "dian")
    }
}
