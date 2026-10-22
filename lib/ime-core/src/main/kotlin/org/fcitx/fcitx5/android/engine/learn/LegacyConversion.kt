/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.english.EnglishUserStore
import org.fcitx.fcitx5.android.engine.english.LegacyEnglishUser
import org.fcitx.fcitx5.android.engine.learn.ConvertedMarker.Seen
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Absorbed
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnPairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Forget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PinRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.StatRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * What older builds learned, as the learning record's first snapshot (impl/design-1a.md §5): the
 * old files read, never written, so a build before this one still reads them as they were.
 *
 * - [pinyin], the older build's user model (`UserLexicon.legacyModel()`), read when converting:
 *   the words the user made are theirs ([Mine.CONVERTED]), its counts the words' uses on the day
 *   converted ([LearnRecord.WordUseRow]), its pairs pairs; the dictionaries' and those added by
 *   hand are not words of the user's to form.
 * - [phrases], the user's own custom phrases: one first of its key, of letters, filled in by nothing, a pin.
 * - The tables' logs in [dir] (`<im>.user`): what was picked, used on the day; the phrases saved.
 * - [english], the English log: added words, pairs; one counted to [LegacyEnglishUser.LEARNED] the data
 *   misspells or lacks ([englishKnown], read only if there is a log; [misspelt]) is never corrected, one it lacks the user's own.
 *
 * A source that cannot be read (an [IOException]) fails it whole, to be tried again; one of
 * another format is skipped, reported to [onError].
 */
class LegacyConversion(
    private val dir: File,
    private val pinyin: () -> UserModel?,
    private val phrases: CustomPhrases?,
    private val englishKnown: () -> ((String) -> Boolean)? = { null },
    private val misspelt: (String) -> Boolean = { false },
    private val onError: (IOException) -> Unit = {},
) : LearnLog.Legacy {
    /** The marker of the logs as they are, [state] just filled from them ([into]). */
    @Throws(IOException::class)
    override fun marker(state: LearnState) = ConvertedMarker(logs().associate { it.name to Seen.of(it, it.readBytes()) }, state.mine.keys.toSet())

    /**
     * What an older build did in its logs since [was] (null: no marker that reads), as records
     * for [state] stamped [stamp], and the marker after them; null if no log changed.
     *
     * What it did, never what this build did (review-1a-r2 M1), which it still appends to the
     * tables' logs and English's: a forget only where a log says so past what was seen, and not
     * where the log has the word again after it (a log rewritten since, or no marker, says no
     * forgets); the user's own words of the pinyin model that [was] had not; a table's saved
     * words. This build's own appends, read again, change nothing: its forgets are the record's
     * already, its saved words taken in once. Counts are not taken in again, nor English's words,
     * nor pins: an older build's pin is an order-1 custom phrase, which still shows first.
     */
    @Throws(IOException::class)
    override fun since(was: ConvertedMarker?, state: LearnState, stamp: LearnClock.Stamp): Pair<List<LearnRecord>, ConvertedMarker>? {
        val seen = was?.seen.orEmpty()
        val logs = logs()
        val changed = logs.filter { seen[it.name]?.same(it) != true }
        if (was != null && changed.isEmpty() && seen.size == logs.size) return null
        val now = HashMap<String, Seen>()
        for (file in logs) seen[file.name]?.let { now[file.name] = it }
        val caught = Caught(state, stamp)
        var mine = was?.mine.orEmpty()
        for (file in changed) {
            val bytes = file.readBytes()
            now[file.name] = Seen.of(file, bytes)
            val prev = seen[file.name]
            // the records appended to what was seen; all of a log not seen then; null: rewritten
            // since (compacted), or no marker to go by
            val appended = when {
                was == null -> null
                prev == null -> bytes.copyOfRange(minOf(TableLog.FORMAT.header().size, bytes.size), bytes.size)
                else -> prev.appended(bytes)
            }
            when (file.name) {
                PINYIN -> mine = caught.pinyin(appended, was?.mine.orEmpty()) ?: mine
                ENGLISH -> if (appended != null) EnglishUserStore.forgotten(EnglishUserStore.FORMAT.header() + appended).forEach(caught::enForget)
                else -> caught.table(file, bytes, appended)
            }
        }
        return caught.records() to ConvertedMarker(now, mine)
    }

    /** What a catch-up takes in, as it reads the logs: removals first, as written. */
    private inner class Caught(private val state: LearnState, private val stamp: LearnClock.Stamp) {
        private val forgets = ArrayList<LearnRecord>()
        private val added = ArrayList<LearnRecord>()
        private val removed = ArrayList<LearnRecord>()

        fun enForget(word: String) {
            forgets += EnForget(stamp.minute, word)
        }

        // the texts pinyin.user forgot past what was seen; the user's own words its model has that
        // [had] has not, as become theirs now (review-1a-r2 NIT 3): the model's words
        fun pinyin(appended: ByteArray?, had: Set<String>): Set<String>? {
            if (appended != null) for (text in UserLog.forgotten(UserLog.header() + appended)) forgets += Forget(stamp.minute, stamp.day, text)
            val model = pinyin() ?: return null
            val made = LinkedHashMap<String, String>()
            for ((entry, _) in model.madeWords().sortedByDescending { it.second }) if (entry.text !in made) made[entry.text] = reading(entry)
            var seq = state.nextSeq
            for ((text, reading) in made) {
                if (text in had || text in state.mine) continue
                added += MineRow(text, Mine(reading, Mine.CONVERTED, stamp.day, seq++, stamp.day, stamp.localDay, 0))
            }
            return made.keys
        }

        // a table's words saved and forgotten past what was seen; rewritten, those it has saved
        fun table(file: File, bytes: ByteArray, appended: ByteArray?) {
            if (!TableLog.FORMAT.hasHeader(bytes)) return
            val im = IM_PREFIX + file.name.removeSuffix(LOG_SUFFIX)
            val saved = LinkedHashMap<Pair<String, String>, Boolean>()
            TableLog.FORMAT.read(if (appended != null) TableLog.FORMAT.header() + appended else bytes) { type, input ->
                if (type == TableLog.SAVED || type == TableLog.FORGOT) {
                    val key = input.readUTF() to input.readUTF()
                    saved.remove(key)
                    saved[key] = type == TableLog.SAVED
                }
            }
            for ((key, on) in saved) {
                if (on) added += TableWordRow(im, key.first, key.second, stamp.day)
                // a log rewritten keeps no forgets: only one read past what was seen says one
                else if (appended != null) removed += TableWordRow(im, key.first, key.second, 0)
            }
        }

        fun records(): List<LearnRecord> = forgets +
            removed.chunked(LearnRecords.ABSORBED_ROWS).map { Absorbed(stamp.minute, emptyList(), it) } +
            added.chunked(LearnRecords.ABSORBED_ROWS).map { Absorbed(stamp.minute, it, emptyList()) }
    }

    override val heavy: Boolean get() = logs().isNotEmpty()

    /** How many older logs there are to convert. */
    val logCount: Int get() = logs().size

    // the logs an older build appends to as it learns: pinyin's, English's, the tables'
    private fun logs(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(LOG_SUFFIX) }.orEmpty().sortedBy { it.name }

    /** Fills [state], empty, as of now by [clock]. */
    @Throws(IOException::class)
    override fun into(state: LearnState, clock: LearnClock) {
        val minute = LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt()
        val localDay = LearnClock.localDay(clock, minute)
        val rows = ArrayList<LearnRecord>()
        var seq = 0L
        pinyin()?.let { model ->
            // most counted first: the order they became the user's is not kept, the count is the next best
            // a text the user has at two readings (多音) is theirs at the one typed most
            val mine = HashSet<String>()
            for ((entry, _) in model.madeWords().sortedByDescending { it.second }) {
                if (mine.add(entry.text)) rows += MineRow(entry.text, Mine(reading(entry), Mine.CONVERTED, DAY, ++seq, DAY, localDay, 0))
            }
            // the words' counts, as uses on the day converted: what the decoder's counts and 联想 go by,
            // till they fade as anything used then does; halved below one, still one
            model.forEachWordCount { entry, count -> rows += WordUseRow(entry.text, reading(entry), DayCounts.of(DAY to maxOf(1, count.roundToInt()))) }
            // a pair by its texts and the next one's reading, the readings before it summed; halved
            // below one, still one: it was typed
            val pairs = LinkedHashMap<Triple<String, String, String>, Float>()
            model.forEachCount({ _, _ -> }) { prev, next, count ->
                val key = Triple(prev.text, next.text, reading(next))
                pairs[key] = (pairs[key] ?: 0f) + count
            }
            for ((key, count) in pairs) rows += PairRow(key.first, key.second, key.third, maxOf(1, count.roundToInt()), DAY)
        }
        phrases?.all?.forEach { p ->
            if (p.order == 1 && pinnable(p.key, p.value)) {
                rows += PinRow(Code(Kinds.FULL, p.key), p.value, "")
            }
        }
        val tables = tables(rows)
        english(rows)
        state.apply(Head(-1, minute, DAY, localDay, seq + 1, DAY, -1, tables.joinToString(",")))
        rows.forEach(state::apply)
    }

    // a key of letters; a value fcitx fills in when offered (# first, or a $ in it) is no word to pin
    private fun pinnable(key: String, value: String) =
        key.isNotEmpty() && key.all { it in 'a'..'z' } && value.isNotEmpty() && value[0] != '#' && '$' !in value

    private fun reading(entry: UserModel.Entry) = entry.syllables.joinToString(" ") { Syllables.spelling(it) }

    /** The tables' logs as rows: the input methods converted. */
    private fun tables(rows: MutableList<LearnRecord>): List<String> {
        val files = logs().filter { it.name !in OTHERS }
        val converted = ArrayList<String>()
        for (file in files) {
            val bytes = file.readBytes()
            if (!TableLog.FORMAT.hasHeader(bytes)) {
                // a log of another kind: not a table's
                onError(IOException("$file: no table log, not converted"))
                continue
            }
            val im = IM_PREFIX + file.name.removeSuffix(LOG_SUFFIX)
            val picked = LinkedHashMap<Pair<String, String>, Int>()
            val saved = LinkedHashSet<Pair<String, String>>()
            TableLog.FORMAT.read(bytes) { type, input ->
                if (type !in TableLog.PICKED..TableLog.FORGOT) return@read
                val key = input.readUTF() to input.readUTF()
                if (key.first.isEmpty() || key.second.isEmpty()) return@read
                when (type) {
                    TableLog.PICKED -> picked[key] = (picked[key] ?: 0) + input.readInt()
                    TableLog.SAVED -> saved += key
                    TableLog.FORGOT -> {
                        picked.remove(key)
                        saved.remove(key)
                    }
                    // what the table saw typed but not picked: not the user's choice
                    else -> Unit
                }
            }
            val kind = Kinds.table(im)
            for ((key, n) in picked) if (n > 0) rows += StatRow(Code(kind, key.first), Words.of(key.second), DayCounts.of(DAY to n), DayCounts(), 0)
            for (key in saved) rows += TableWordRow(im, key.first, key.second, DAY)
            converted += im
        }
        return converted
    }

    private fun english(rows: MutableList<LearnRecord>) {
        val file = File(dir, ENGLISH)
        if (!file.isFile) return
        val bytes = file.readBytes()
        if (!EnglishUserStore.FORMAT.hasHeader(bytes)) {
            onError(IOException("$file: no English log, not converted"))
            return
        }
        val user = LegacyEnglishUser()
        EnglishUserStore.FORMAT.read(bytes) { type, input -> EnglishUserStore.replay(type, input, user) }
        val words = LinkedHashMap<String, EnWordRow>()
        val known = englishKnown()
        user.forEachCount { prev, word, count ->
            val n = count.roundToInt()
            if (prev != null) {
                if (n > 0) rows += EnPairRow(prev, word, n, DAY)
                return@forEachCount
            }
            val flags = flags(word, count, known?.invoke(word))
            // faded below one use and nothing to keep of it: nothing
            if (n > 0 || flags != 0) words[word] = EnWordRow(word, flags, if (n > 0) DayCounts.of(DAY to n) else DayCounts())
        }
        for (word in user.addedWords) {
            val had = words[word]
            words[word] = EnWordRow(word, (had?.flags ?: 0) or EnWordRow.ADDED, had?.uses ?: DayCounts())
        }
        rows += words.values
    }

    // a word learned the data misspells or lacks is never corrected; one it lacks, the user's own
    private fun flags(word: String, count: Float, known: Boolean?): Int {
        if (known == null) return 0
        val learned = count >= LegacyEnglishUser.LEARNED
        val never = if (learned && (!known || misspelt(word))) EnWordRow.NEVER_CORRECT else 0
        return never or if (known) 0 else EnWordRow.OWN
    }

    companion object {
        /** The typing day of what was converted: the first. */
        const val DAY = 1

        private const val LOG_SUFFIX = ".user"
        private const val IM_PREFIX = "engine-"
        private const val ENGLISH = "english.user"
        private const val PINYIN = "pinyin.user"
        private val OTHERS = setOf(PINYIN, ENGLISH)

        // of the logs [logs] lists, those no build writes any more (English's, in learn.log now): the
        // [OTHERS] today; the tables' logs are live yet (their stream retires them, adds them here then)
        private val RETIRED = OTHERS.toList()

        /**
         * The older builds' learning files in [dir] that a clear deletes with what it cleared
         * (U-86, U-88 today; review E-S4), so an older build put back shows none of it.
         */
        fun retired(dir: File): List<File> = RETIRED.map { File(dir, it) }
    }
}
