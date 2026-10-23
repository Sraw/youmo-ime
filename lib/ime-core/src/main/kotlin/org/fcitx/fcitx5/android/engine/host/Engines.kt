/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.english.Misspellings
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideReading
import org.fcitx.fcitx5.android.engine.glide.GlideSession
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.DayCounts
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.LegacyConversion
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.learn.Mine
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.learn.backup.BackupControl
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.ListedWord
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.LearningControl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.fcitx.fcitx5.android.engine.table.SharedWords
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.TableUser
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * The input methods the androidengine addon lists, by name, each with its session made on first
 * use. [load] gives a data file by its path in the app's assets; what the user picks, in pinyin
 * and in each table, and the English words they commit, are kept under [userDir] (in memory only
 * if null). When pinyin's log is made afresh, what libime's pinyin learned ([legacy], if the user
 * had it) is read into it first. What the user added to
 * pinyin ([additions]) is read at first need, and again on [reload]. The sentence models'
 * int8 weights are multiplied by [kernel], if given (in C++, say); if not, by Kotlin. What the
 * first keys of pinyin and of English want is read ahead of them on [warmUps], a thread each
 * unless given.
 *
 * A facade over its collaborators, which it makes and wires: [EngineAssets] (the data read from
 * the assets), [UserLexicon] (the user's words), [EnglishLexicon] (the English words the user
 * committed) and [TableRegistry] (the table input methods); here are the sessions, made from
 * them, and the settings they are made with.
 *
 * Everything here runs on one thread, the one fcitx runs on, but what is handed to [warmUps].
 */
class Engines(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit = {},
    private val legacy: () -> LibimeImport.Legacy? = { null },
    private val additions: () -> Additions? = { null },
    private val kernel: MatrixKernel? = null,
    private val warmUps: Executor = Executor { Thread(it, "engine-warm-up").apply { isDaemon = true }.start() },
    /** Whether the phone can draw an emoji: those it cannot are no candidates. */
    private val emojiShows: (String) -> Boolean = { true },
    /** Whether the phone can draw a text: a rare character it cannot is no candidate. */
    private val textShows: (String) -> Boolean = { true },
    /** The app's traditional text to simplified, as learning reads it (OpenCC's tables, [Simplifier.read]): asked once, as the first pinyin session is made. */
    private val simplifier: () -> Simplifier = { Simplifier.NONE },
    /** Where this install's id is kept, a directory backups do not go to ([Backups.installId]); none: another install each time. */
    installFile: File? = null,
    /** The app's version, named in the backups it makes. */
    appVersion: String = "",
    /** libime's own learning files, where [legacy] reads them: gone with what U-86 and U-88 「今天」 clear (review-E-engine S3). */
    private val legacyFiles: List<File> = emptyList(),
    /** What the older builds' learning's conversion did, for the app's log; [ConversionNote.Made] on a [warmUps] thread. */
    onConversion: (ConversionNote) -> Unit = {},
    /** Monotonic milliseconds: how long typing has paused ([paused]). */
    uptimeMillis: () -> Long = OwedConversion.UPTIME,
) : Closeable {

    /**
     * What the user added to pinyin, as libime kept it: its custom phrases file, and the lines
     * of its dictionaries turned on, in libime's text format (`你好 ni'hao 0`), read only when
     * the user's words are made: they can be many, and are kept in the model, not here.
     * [dictionaries] tells the dictionaries apart (their names, sizes and times, say): read again
     * on [reload] only if it changed. The custom phrases the user changes from the keyboard
     * ([PhraseBook]) are handed to [savePhrases], all of them.
     */
    class Additions(
        val phrases: String,
        val dictionaries: String,
        val savePhrases: (CustomPhrases) -> Unit = {},
        /**
         * The dictionaries turned on, merged into the base dictionary, compiled (DictionaryCompiler)
         * and opened, or null for none: asked once per these additions, on the fcitx thread, so the
         * file is mapped, never read through (a 1.9-million-word one ran 0.3.1's heap out, read).
         * @throws java.io.IOException if it cannot be opened: typing goes on without it, told once
         */
        val dictionary: () -> DictionaryFile?,
        /** Where the tables keep their index of [dictionary]'s words (CodedIndex), beside it; null for none kept. */
        val indexes: File? = null,
        /**
         * The merged file on disk opened now, stale or not, for when [dictionary] has none yet (its
         * open queued at a fresh start): an older build's learning converted then must know the
         * dictionaries' words, or they become the user's made words for good (review S4). Rare
         * (a conversion owed), so the CRC pass on the fcitx thread is accepted.
         * @throws java.io.IOException if it cannot be opened
         */
        val dictionaryNow: () -> DictionaryFile? = { null },
        /**
         * Whether [dictionary] was compiled against another base dictionary (an update's, its
         * recompile not done): its words the base has now are left to the base till then (review S7).
         */
        val dictionaryStale: Boolean = false,
        /**
         * Whether the dictionaries turned on are not all in [dictionary] yet (compiled, or one
         * failed): a word they may have is not known to be none of theirs, so removing it
         * forgets no learning, as when [dictionary] does not open (review M4).
         */
        val dictionaryPending: () -> Boolean = { false },
    )

    private val assets = EngineAssets(load, onError, kernel, emojiShows, textShows)

    // a thread for each compaction an append makes due, so a key does not wait on the disk: none
    // kept, so the logs opened again after close() still have one
    private val compactions = Executor { Thread(it, "record-compaction").apply { isDaemon = true }.start() }

    private val lexicon: UserLexicon = UserLexicon(
        userDir, onError, legacy, additions, assets,
        forgetInTables = { tables.forgetInTables(it) },
        dropSessions = ::dropSessions,
        forgotten = { texts -> texts.forEach(habits::forget) },
        attach = ::attach,
        blocked = ::unpinEverywhere,
        owned = ::own,
        imports = { file, words, known -> imports.import(file, words, known) },
    )

    // the imported words (U-101): in the learning record, told to the user model
    private val imports = ImportLayer(::learnLog, { lexicon.user() }, { lexicon.isBlockedWord(it) }, ::dropSessions)

    private val tables = TableRegistry(load, userDir, onError, compactions, warmUps, assets, lexicon,
        knows = { habits.knows(it) }, recorded = { learnLog().onDisk }, beforeOpen = { learnLog() },
        indexes = lexicon::indexes)

    private val english = EnglishLexicon(::learnLog)

    // the learning record (impl/design-1a.md), opened before the user model, which is made from it
    // (impl/design-1b.md §4): converting the old logs reads an older build's model of its own
    private var learnLog: LearnLog? = null

    // the counts of the user model of the moment, kept as the record changes
    private var habitCounts: HabitCounts? = null

    // a long press's forget in handwriting or a table: the tables', and the learning record's, as the model's
    private fun forgottenEverywhere(text: String) {
        tables.forgetInTables(listOf(text))
        habits.forget(text)
    }

    // internal: a test reaches the record opened as its first use opens it (EnginesRecordFaultTest)
    internal fun learnLog(): LearnLog = learnLog ?: run {
        val legacy = userDir?.let { dir -> legacyConversion(dir, lexicon::legacyModel, lexicon.phrases().offered(builtIns = false)) }
        val log = LearnLog(userDir?.let { File(it, LearnLog.FILE) }, onError = onError, background = compactions)
        learnLog = log
        try {
            // a clear killed before it said in the marker that it deleted the older builds' files
            val remark = control.resumeDrop()
            log.open(legacy, remark, deferred = true)
            convert(log)
            if (remark && log.markedAtOpen) control.finishDrop()
        } catch (e: IOException) {
            notOpened(log, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            notOpened(log, e)
        }
        val opened = checkNotNull(learnLog)
        opened.listener = ::changed
        control.attach(opened)
        backups.claim(opened)
        // the pins moved once a user model has the user's words and counts: see attach
        opened
    }

    // a fault of the record's, or old files it trips on: learned in memory this run, no marker written (review-1a-app S1)
    private fun notOpened(log: LearnLog, e: Exception) {
        onError(IOException("the learning record not opened", e))
        runCatching { log.close() }
        learnLog = LearnLog(null, onError = onError)
    }

    private val owed = OwedConversion(warmUps, onError, onConversion, uptimeMillis)

    // the phrases of the pins converted looked at this run: see movePins
    private var pinsMoved = false

    // the conversion put in place by [take]: the pins it brought taken out of the phrases
    private fun afterConversionLands(take: () -> Unit) {
        take()
        learnLog?.let(::movePins)
    }

    /** Typing paused in the field, [idle] or not: see [OwedConversion.paused]. Whether the older build's learning, made, still waits. */
    fun paused(idle: Boolean = false): Boolean = owed.paused(learnLog, keyboards.values.any { it.composing }, idle).also { learnLog?.let(::movePins) }

    /**
     * The older builds' files [log] left owed, converted off this thread ([OwedConversion]) with a
     * lexicon of its own, given what the app gives as read here (review-uat-fixes S6: [additions]
     * and [legacy] are this thread's to call).
     */
    private fun convert(log: LearnLog) {
        val dir = userDir ?: return
        if (!log.conversionPending) return
        val phrases = lexicon.phrases().offered(builtIns = false)
        // read here, as its lazy is not to be raced: English's words, if there is an English log to convert
        if (File(dir, USER_ENGLISH).isFile) assets.englishData
        // the app's files not read: the conversion owed to the next start, the record open and learning to disk
        val (added, libime) = try {
            additions() to legacy()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            owed.notStarted = true
            return onError(IOException("the older builds' learning not converted: owed to the next start", e))
        }
        val own = UserLexicon(dir, onError, { libime }, { added }, assets, forgetInTables = {}, dropSessions = {})
        val files = legacyConversion(dir, own::legacyModel, phrases)
        owed.begin(log, files, files.logCount)
    }

    // [block] with the older build's learning made in first, if [wholeRecord]: an act on the whole record, or
    // its pages' reading it (review-uat-fixes S3, verify-uat-fixes V3)
    private inline fun <T> withConversionMade(wholeRecord: Boolean = true, block: () -> T): T {
        if (wholeRecord && userDir != null) afterConversionLands { owed.finish(learnLog()) }
        return block()
    }

    // the old logs in [dir] as the learning record converts them
    private fun legacyConversion(dir: File, pinyin: () -> UserModel?, phrases: CustomPhrases?): LegacyConversion {
        val known = { assets.englishData?.let { d -> { word: String -> d.id(word) != NO_WORD } } }
        return LegacyConversion(dir, pinyin, phrases, known, { it in Misspellings.MAP }, onError)
    }

    // the learning backups (U-100, U-106; impl/streams/D-design.md): made every few typing days and when asked, listed, merged in when restored
    private val backups: BackupControl = BackupControl(
        Backups(userDir?.let { File(it, Backups.DIR) }, Backups.installId(installFile, onError), onError, compactions),
        LearnClock.System,
        appVersion,
        state = { learnLog().state },
        lists = { listed(UserWord.Kind.ADDED) to listed(UserWord.Kind.BLOCKED) },
        rewrite = ::rewriteLearning,
        addLists = { added, blocked ->
            lexicon.addListed(added.map { it.text to it.code }, blocked.map { it.text to it.code })
        },
        removeLists = { added, blocked ->
            lexicon.removeWords(added.map { it.word(UserWord.Kind.ADDED) } + blocked.map { it.word(UserWord.Kind.BLOCKED) })
        },
        importLines = { lexicon.importWords(it) },
        // read off the keyboard's thread: a log read while a record is appended reads up to the last whole one
        logBytes = userDir?.let { dir -> { File(dir, LearnLog.FILE).takeIf { it.isFile }?.readBytes() } },
        // not one that failed this run: owed to the next start, it would hold every backup back till then
        conversionPending = { learnLog().conversionPending && owed.mayLand },
    )

    private fun listed(kind: UserWord.Kind) = lexicon.userWords(kind).map { ListedWord(it.text, it.pinyin.replace(' ', '\'')) }

    private fun ListedWord.word(kind: UserWord.Kind) = UserWord(text, code.replace('\'', ' '), kind)

    // the learning record rewritten whole by [transform] (LearnLog.rewrite), the sessions made anew on it. A record
    // that did not open learns in memory, where a rewrite does not last: an error then, so a restore says so and a
    // settings zip's stash is kept for the next start (what is written is on disk, or it was not written)
    private fun rewriteLearning(transform: (ByteArray, OutputStream) -> Boolean): Boolean {
        var wrote: ByteArray? = null
        val done = learnLog().rewrite { log, out ->
            val bytes = ByteArrayOutputStream()
            transform(log, bytes).also { if (it) out.write(bytes.toByteArray().also { b -> wrote = b }) }
        }
        if (!done) return false
        dropSessions()
        val file = userDir?.let { File(it, LearnLog.FILE) } ?: return true
        if (!file.isFile || !file.readBytes().contentEquals(wrote)) throw IOException("the learning record is not open: learned in memory alone")
        return true
    }

    // the settings backups' learning, left by the app while the engine was stopped (RestoreStash)
    private val stashes = StashIntake(
        userDir, onError,
        read = { dir ->
            val conversion = legacyConversion(dir, { lexicon.legacyModel(File(dir, USER_PINYIN)) }, null)
            RestoreStash.read(dir, { conversion.into(it, LearnClock.System) }, onError)
        },
        // the older build's learning made in first: the copy for 退回恢复前 has it (a revert keeps it).
        // Made here, at the start, on this thread (seconds for a heavy user, the background one begun
        // on the open made for nothing): only where an upgrade still owes it and a settings copy was
        // restored, rare enough to leave as is (review R11 F3136)
        merge = { backup, time -> withConversionMade { backups.restore(backup, since = time) } },
    )

    // a user model made: the user's own words in it, and the counts, as the record has them
    private fun attach(model: UserModel) {
        val log = learnLog()
        habitCounts = HabitCounts(model).also { it.attach(log.state) }
        model.predictionsOff = ::predictionOff
        // here, not as the record opens (often inside user(), before this): shows() ranks by the model's words
        movePins(log)
    }

    // U-72: what the user turned off after a word, as the record has it now (a rewrite makes its state anew)
    private fun predictionOff(prev: String, next: String) = learnLog?.state?.noPredict?.contains(prev to next) == true

    // the user's acts on what was learned (impl/streams/E-design.md): the older builds' pinyin log goes with what is cleared
    private val control = LearningControl(::learnLog, legacy = { userDir?.let(LegacyConversion::retired).orEmpty() + legacyFiles }, onError = onError)

    private val learning by lazy(LazyThreadSafetyMode.NONE) {
        LearningOps(
            sessions::get, lexicon, ::learnLog, control,
            habits = { useHabits }, useHabits = { useHabits = it }, dropSessions = ::dropSessions,
            first = { keyboards[it]?.first ?: 0 }, tableForgets = { tables.forgets },
            // a clear forgets the backups that hold what it clears first, and saves one of what is left after (C25)
            backups = backups, backup = backups::run, restoreCorrection = ::restoreEnglishCorrection,
            clearTables = tables::clearSaved, resetTableOrder = ::resetTableOrder,
        )
    }

    /**
     * U-93 「用我的输入习惯」: off, the sessions are made as installed but for the blocks (C26): no
     * learning record read or written, no word of the user's, added by hand or pinned, no custom
     * phrase, nothing English learned (impl/streams/E-design.md §4). Nothing recorded, no typing day
     * passes. The sessions are made anew for a change, what was typed dropped.
     */
    var useHabits = true
        set(value) {
            if (value == field) return
            field = value
            dropSessions()
        }

    /**
     * Does [op] (PLAN §2.8) in [im], the input method in use; [act] has the host send a long
     * press's event for the candidate at an index, as it always did, so it shows the list left
     * (the addon's; by default the keyboard's here).
     */
    fun learning(im: String?, op: LearningOp, act: (event: Int, index: Int) -> Boolean = { event, index -> im?.let { keyboards[it] }?.onEvent(event, index) != null }): LearningResult =
        withConversionMade(OwedConversion.readsTheWhole(op)) { learning.apply(im, op, act) }

    // [method]'s options as [s] sets them: what 与 X 同码 checks is what the session offers
    private fun tableOptions(method: TableMethod, s: EngineSettings): TableOptions = s.tables[method.group]?.applyTo(method.options) ?: method.options

    // the habits the sessions are made with: none while the user turns them off
    private fun habitsNow(): Habits = if (useHabits) openHabits() else Habits.OFF

    // the record's habits, the record opened before they are asked (its listener set, the counts attached)
    private fun openHabits(): Habits = habits.also { learnLog() }

    // what a record changed: the model's words and counts, and a text forgotten off the words added by hand (U-82)
    private fun changed(changes: LearnState.Changes) {
        val log = learnLog ?: return
        habitCounts?.apply(log.state, changes)
        changes.forgotten.forEach(lexicon::unAdd)
        if (changes.commits.isNotEmpty()) backups.afterCommit()
    }

    // texts blocked: unpinned under every code, the block winning (impl/design-1b.md §3)
    private fun unpinEverywhere(texts: Collection<String>) {
        val pins = learnLog().state.pins.filter { it.value.text in texts }
        for ((code, words) in pins) habits.pin(code, words.text, words.reading, on = false)
    }

    // words made the user's own where they are not yet, an export's count each as uses today where
    // none are (as LegacyConversion takes an older build's): how many were not
    private fun own(words: Map<UserModel.Entry, Float>): Int {
        val log = learnLog()
        val state = log.state
        val rows = ArrayList<LearnRecord>()
        var owned = 0
        for ((e, count) in words.entries.distinctBy { it.key.text }) {
            if (e.text in state.mine) continue
            val reading = e.syllables.joinToString(" ") { Syllables.spelling(it) }
            rows += MineRow(e.text, Mine(reading, Mine.TAKEN_IN, state.day, state.nextSeq + owned++, state.day, state.localDay, 0))
            if (count > 0f && state.wordUses[e.text]?.get(reading) == null) rows += WordUseRow(e.text, reading, DayCounts.of(state.day to maxOf(1, count.roundToInt())))
        }
        log.absorb(rows)
        return owned
    }

    /**
     * Once (a marker, [PINS_MOVED]): the user's order-1 phrases a pin of full pinyin was converted
     * from (design-1a Open 3) taken out of the phrases, the pin being the learning record's now.
     * Not while the conversion is owed (made off this thread, UAT PR-30): its pins are not in the
     * record yet, and the marker written then would leave the phrases for good. Asked again where
     * it is put in place.
     */
    private fun movePins(log: LearnLog) {
        // a record learning in memory: the marker would leave the phrases of the pins its next open converts
        if (pinsMoved || log.conversionPending || !log.onDisk) return
        val marker = userDir?.let { File(it, PINS_MOVED) } ?: return
        pinsMoved = true
        if (marker.exists()) return
        val book = lexicon.phrases()
        val own = book.offered(builtIns = false).all
        for ((code, words) in log.state.pins) {
            val from = own.any { it.order == 1 && it.key == code.keys && it.value == words.text }
            if (code.kind == Kinds.FULL && from && shows(code.keys, words.text)) book.remove(code.keys, words.text)
        }
        try {
            marker.createNewFile()
        } catch (e: IOException) {
            onError(e)
        }
    }

    // whether the pin of [text] under [keys] shows it first there, the phrases aside: full pinyin
    // typed, ranked by the record (你好 under nh, 行 under hang). One the input does not read (呢
    // under ni, a sentence the decoder misses) stays the phrase it was (review-1b M2)
    private fun shows(keys: String, text: String): Boolean {
        val s = PinyinSession(assets.pinyinData, PinyinSegmenter(), user = lexicon.user(), prediction = false, learnTo = LearnTo(habits))
        return keys.fold(null as Snapshot?) { _, c -> s.apply(Action.Key(c)) }?.candidates?.firstOrNull() == text
    }

    private val habits: Habits = LogHabits(::learnLog, onError)

    private val simplified by lazy(LazyThreadSafetyMode.NONE) { simplifier() }

    /**
     * A word of the user's, as the settings list them; [pinyin] its syllables apart by spaces. An
     * English one [neverCorrected]: added, or a correction of it taken back (U-31).
     */
    data class UserWord(
        val text: String,
        val pinyin: String,
        val kind: Kind,
        val count: Float = 0f,
        val neverCorrected: Boolean = false,
        /** The calendar day (days since the epoch, local) it was last used, [NEVER] if not known (U-80). */
        val lastUsed: Int = NEVER,
        /** Typing days since it was last used, [NEVER] if not known: what 「很久没用」 counts (§1: 「天」 are typing days). */
        val idle: Int = NEVER,
        /** How recently it became the user's, larger later: the page's 「最近学到」 order. */
        val order: Long = 0,
    ) {
        enum class Kind {
            /** Added by hand, in the settings. */
            ADDED,

            /** Put together by the user from pieces as they typed. */
            LEARNED,

            /** Blocked, a long press on a candidate or in the settings: never offered. */
            BLOCKED,
        }

        companion object {
            const val NEVER = Int.MIN_VALUE
        }
    }

    /** The words the user added, those they made as they typed (most typed first), and those they blocked. */
    fun userWords(): List<UserWord> = lexicon.userWords()

    /** [kind]'s part of [userWords] alone, each dated as the learning record has it (U-80): a list of the settings shows one kind. */
    fun userWords(kind: UserWord.Kind): List<UserWord> =
        withConversionMade { lexicon.userWords(kind).let { if (kind == UserWord.Kind.BLOCKED) it else learning.dated(it) } }

    /** How many words of each kind [userWords] lists, with none of them made a [UserWord]. */
    fun userWordCounts(): Map<UserWord.Kind, Int> = withConversionMade { lexicon.userWordCounts() }

    /**
     * The words of [texts] that tables among [ims] cannot take, each with the tables' words they
     * give way to, as (table input method, its word): 「五笔：与 X 同码」 in the user dictionary (D8).
     */
    fun sameCodes(texts: Collection<String>, ims: Collection<String>): Map<String, List<Pair<String, String>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (im in ims) {
            val method = TABLES[im] ?: continue
            val options = tableOptions(method, settings)
            for ((text, word) in tables.sameCode(im, options, texts)) out.getOrPut(text) { ArrayList(1) } += im to word
        }
        return out
    }

    /**
     * How the dictionary reads [text]: as few of its words as make it up, each read as it most
     * likely is; syllables apart by spaces, null if some character is none of its words. For
     * the user to check, as a character of several readings may be read the other way; a first
     * character read as a word has it, though it never is so alone (了 liao in 了解).
     */
    fun pinyinOf(text: String): String? = assets.pinyinOf(text, strayFirst = true)

    /**
     * [pinyinOf] of each of [texts], the dictionary walked once for them all, and once more for
     * what those not read whole missed: a walk is the whole of it, a thousand words imported with
     * no pinyin would be a thousand walks.
     */
    fun pinyinsOf(texts: Collection<String>): Map<String, String?> = assets.pinyinsOf(texts, strayFirst = true)

    /** Where each of [texts] splits into words, as the model finds likeliest: TextWords.boundaries. */
    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = assets.wordBoundaries(texts)

    /** log10 P of each of [texts] under the pinyin model (TextWords.logProb): for ranking what voice input heard. */
    fun logProbs(texts: List<String>): FloatArray = assets.logProbs(texts)

    /** A character's readings as the dictionary has them, the likeliest first: `zhang/chang`; empty for none. */
    fun charReading(c: String): String = assets.charReading(c)

    /** Adds [text] read as [pinyin] (see [WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean = lexicon.addWord(text, pinyin)

    /** Blocks [text] read as [pinyin] from the settings; false if it does not read so. */
    fun blockWord(text: String, pinyin: String): Boolean = lexicon.blockWord(text, pinyin)

    /** Takes [word] off its list: an added word is no longer typeable, a learned one forgotten, a blocked one offered again. */
    fun removeWord(word: UserWord) = removeWords(listOf(word))

    /** [removeWord] for each of [words], a list written once. */
    fun removeWords(words: Collection<UserWord>) = lexicon.removeWords(words)

    /**
     * The English words the user committed lately, as written, and those never corrected, the
     * most typed first: [UserWord.Kind.ADDED] those added, else [UserWord.Kind.LEARNED]; no pinyin.
     */
    fun englishWords(): List<UserWord> = withConversionMade { english.words() }

    /**
     * Makes [text] an English word of the user's, added: offered by its keys, never corrected, and
     * kept however long untyped. False for what is no word English types (letters, an
     * apostrophe or a hyphen within).
     */
    fun addEnglishWord(text: String): Boolean = english.add(text)

    /** Forgets each of the English words [texts]: their counts, the pairs they are in, and that they are never corrected. */
    fun forgetEnglishWords(texts: Collection<String>) = english.forget(texts)

    /** Has each of the English words [texts] corrected again (U-31 恢复), their counts and pairs kept. */
    fun restoreEnglishCorrection(texts: Collection<String>) = english.restoreCorrection(texts)

    /**
     * What [importWords] made of the lines: words added, blocked and learned that were not already,
     * lines it could not read, words imported (U-101).
     */
    data class Imported(val added: Int, val blocked: Int, val unread: Int, val learned: Int = 0, val imported: Int = 0)

    /**
     * The words of a list the user imports, named [file], a word a line (see [WordLists.line]); one
     * with no pinyin read as the dictionary reads it ([pinyinOf]). The user's own export
     * ([exportWords]) comes back as it was: added, blocked, learned with its count. Another input
     * method's list is blocked where marked so, and its other words imported as a batch (U-101):
     * typed only as words the input starts with, after the dictionary's of the same keys, never in
     * a sentence, till typed twice; a count after a word orders the batch (D24).
     */
    fun importWords(lines: Sequence<String>, file: String = ""): Imported = withConversionMade { lexicon.importWords(lines, file) }

    /** The batches of words imported, oldest first (U-102). */
    fun importBatches(): List<ImportLayer.Batch> = imports.batches()

    /** The words only imported, of [batch] if given (来自导入: U-62, U-80). */
    fun importedWords(batch: Int? = null): List<ImportLayer.Word> = imports.words(batch)

    /** Undoes the import [batch] (U-102), the user's own words staying: how many went; null for no such batch. */
    fun dropImport(batch: Int): Int? = imports.drop(batch)

    /** Puts back what [dropImport] took of [batch] (撤销): false if it cannot be, the record read afresh since. */
    fun restoreImport(batch: Int): Boolean = imports.restore(batch)

    /**
     * The user's words as [importWords] reads them back: the added as `幽默 you'mo`, the learned
     * (most typed first) with a `~` apart before them and their count after (`~ 幽默 you'mo 3.0`, learned
     * again only from a file headed by the export's mark), the blocked with a `!` before them.
     */
    // the user's export is a backup (FXLB) since 4fa9c54f: this text form is kept for the word lists' round-trip tests
    fun exportWords(): List<String> = lexicon.exportWords()

    /**
     * 恢复码表顺序 (U-28): what table [im] learned of the order by use is forgotten, what was pinned
     * and blocked under its codes kept; nothing for an input method no table is.
     */
    internal fun resetTableOrder(im: String) {
        if (im in TABLES) openHabits().resetTableOrder(im)
    }

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    // English's data is read once and kept: warmed up once, however often its session is made
    private var englishWarmedUp = false

    // pinyin's data and word index, which the sessions of READS_PINYIN_DATA and the tables read as they
    // are made: read once, in the background
    private var pinyinWarmedUp = false

    // of what is placed beside the candidates (EMOJI_DATA, CHAIZI_DATA, CHAR_READINGS), what was warmed up
    private val asideWarmedUp = HashSet<String>()

    // the coarse words of pinyin's vocabulary but those the user added, one for handwriting and every
    // table: made again once the sessions are dropped, as every change to the added words drops them
    private var coarseWords: CoarseWords? = null

    // the latest piece of handwriting ended by a session dropped: a reading late for it is not let in
    // by the session made in its place ([HandwritingSession.latestPiece])
    private var inkEndedBefore = HandwritingSession.NO_PIECE

    private fun dropSessions() {
        (sessions[HANDWRITING] as? HandwritingSession)?.let { inkEndedBefore = maxOf(inkEndedBefore, it.latestPiece) }
        keyboards.clear()
        sessions.clear()
        coarseWords = null
        assets.dropDecoderExtensions()
    }

    /** What the user set; the sessions are made again for a change, what was typed dropped. */
    var settings = EngineSettings()
        set(value) {
            if (value == field) return
            field = value
            dropSessions()
            // what a lower level no longer runs leaves memory, read again if the level goes up
            if (value.modelLevel < ModelLevel.MEDIUM) assets.sentenceModel.drop()
            if (value.modelLevel < ModelLevel.HIGH) assets.refiningModel.drop()
        }

    private val latin = LatinSync { w -> assets.englishData?.let { it.id(w) != NO_WORD } == true }

    // off with the habits (U-93): nothing English learned is spelled, and no user model is made for it
    private fun syncLatin() = latin.sync(settings.latinWords && useHabits, if (useHabits) english.user() else EnglishUser.OFF, { if (useHabits) learnLog().state else null }, lexicon)

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true): Snapshot {
        owed.keyed()
        if (im == PINYIN) syncLatin()
        val keyboard = keyboards[im] ?: Keyboard(session(im), nineKeys = im == T9).also {
            keyboards[im] = it
            // one made after the field's context (its first key, sessions made anew): the field's flags all
            // the same, its text not known as a context would make it (review-int S5)
            if (fieldFlags != 0) it.fieldFlags(fieldFlags)
        }
        return keyboard.onEvent(event, arg, learning)
    }

    /**
     * What learning leaves out in the field now ([Action.Context.learnFlags]), as its last [context]
     * said; the host's to set again for engines made anew in the field (review-int N1).
     */
    var fieldFlags = 0

    /**
     * [before] is the text before the cursor, put where the engines did not: see
     * [Keyboard.context]; [learning] off in a sensitive field, told "" (not known), so each
     * learns as its [onEvent] would. [im] is the input method the field starts with. [cleared],
     * [started], [after] and [selected] as [Action.Context] has them. True where English, [im], now
     * offers a word in place of the one before the cursor (a correction taken back, U-31), for the
     * host to show at once with a [Keyboard.RECALL].
     */
    fun context(
        im: String,
        before: String,
        learning: Boolean = true,
        cleared: Boolean = false,
        learnFlags: Int = 0,
        started: Boolean = false,
        after: String? = null,
        selected: Boolean = false,
        said: Boolean = false,
    ): Boolean {
        // the older build's learning, made, put in place as a field starts: no sentence is being typed
        if (started) afterConversionLands { owed.take(learnLog) }
        // the counts a new typing day changed, made anew here rather than on a key (HabitCounts.settle)
        learnLog?.let { log -> habitCounts?.settle(log.state) }
        if (im in READS_PINYIN_DATA || im in TABLES) warmUpPinyin(im)
        // 中英混输 asks English's words of each English word learned at the first key (LatinSync): indexed before it
        if (im == PINYIN && !englishWarmedUp && latinAsksEnglish()) warmUpEnglish()
        // the user's words coded for the table before its first key, which would otherwise start it
        if (im in TABLES) tables.prepare(im)
        fieldFlags = learnFlags
        var offers = false
        for ((name, keyboard) in keyboards) {
            // only [im]'s: another's candidates would stay on show where nothing resets them, its contexts ignored
            val shows = keyboard.context(before, learning, cleared, learnFlags, started, after, selected, offers = name == im, said = said)
            if (name == im && shows && sessions[name] is EnglishSession) offers = true
        }
        return offers
    }

    // English's data, its keys and word index (some hundreds of milliseconds on a phone) built off
    // this thread before the first key wants them; a key that comes sooner waits for them, as it would anyway
    private fun latinAsksEnglish() = settings.latinWords && useHabits && english.user().words.isNotEmpty()

    private fun warmUpEnglish(): EnglishData? = assets.englishData.also { data ->
        if (data != null && !englishWarmedUp) {
            englishWarmedUp = true
            warmUps.execute(data::warmUp)
        }
    }

    // told as a field starts in an input method that reads pinyin's data (READS_PINYIN_DATA, a table,
    // which ranks and learns by them: TableLanguage), before its first key: the data read and its 270
    // thousand words indexed meanwhile, off the fcitx thread; one who types only English neither waits
    // for them nor holds them. A key that comes sooner waits for them, and a switch from English to one
    // of those reads them on the fcitx thread as it makes the session (activate's Reset). Whatever
    // reading them throws is dropped, the lazies left unset: the session reads them again as it is
    // made, on the fcitx thread, which hands it to the addon (EngineBridge.Unloadable); thrown out of
    // this thread, it would end the app. Memory run out is no Exception, and is not caught.
    // Beside it only what [im] shows as the settings have it, held from then on: no emoji in handwriting,
    // characters by their parts and their readings (笔画查字's) only in full pinyin
    private fun warmUpPinyin(im: String) {
        val aside = HashSet<String>()
        if (settings.emoji && im != HANDWRITING) aside += EMOJI_DATA
        if (im == PINYIN && settings.chaizi) aside += CHAIZI_DATA
        if (im == PINYIN) aside += CHAR_READINGS
        aside -= asideWarmedUp
        if (pinyinWarmedUp && aside.isEmpty()) return
        pinyinWarmedUp = true
        asideWarmedUp += aside
        warmUps.execute {
            try {
                assets.pinyinData.wordIndex
                assets.charSyllables
                assets.warmUpAside(EMOJI_DATA in aside, CHAIZI_DATA in aside, CHAR_READINGS in aside)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * The keys no letter [im], a table input method, reads now, as its session has them, for
     * [readsAsKey]: 二笔's `;',./` of its codes, 仓颉's `*` for any key, 二笔's `[` that starts a
     * pinyin lookup, the lookup's `'`. None for another input method, nor a table with no session
     * yet, whose table is not read to answer: the keyboard waits.
     */
    fun tableKeys(im: String): Set<Char> {
        val options = TABLES[im]?.options ?: return emptySet()
        val session = sessions[im] ?: return emptySet()
        // made with its session: read already
        val keys = tables.table(im).dictionary.keys + listOfNotNull(options.matchingKey, options.pinyinKey, SyllableGraph.SEPARATOR) + options.selectionKeys.asIterable()
        return keys.filterTo(HashSet()) { !it.isLetter() && session.reads(it) }
    }

    /**
     * What the handwriting pad read, for its session to show at its next [Keyboard.INK]:
     * [HandwritingSession.offer]. Called on the fcitx thread, just before that key is sent.
     */
    fun offerInk(reading: InkReading, piece: Int) {
        (session(HANDWRITING) as HandwritingSession).offer(reading, piece)
    }

    // read when first glided: most never glide pinyin
    private val pinyinGlide by lazy { PinyinGlide(assets.pinyinData, user = ::glideUser) }

    // the user's words in a glide's readings, as the sessions score them: added, learned, blocked
    private fun glideUser(): PinyinGlide.User {
        // as installed while habits are off (U-93), as the sessions
        val user = sessionUser()
        val scorer = lexicon.prior().scorer(UserScorer(assets.pinyinData.model, user))
        val context = scorer.context(NO_WORD, NO_WORD)
        return PinyinGlide.User(user to user.changes, { word -> scorer.scoreAfter(context, word) }, user, { word -> user.count(word) > 0f }, user::blocked)
    }

    // what a pinyin's glide after something typed goes on after
    private val syllableBoundary = Action.Key(PinyinGlide.SEPARATOR)

    /**
     * The keyboard's glide across the letters, for [im] to read at its next [Keyboard.GLIDE]:
     * English's session its words ([EnglishSession.offerGlide]), a pinyin's or a table's its
     * readings ([GlideSession]). False for one that does not glide ([glides]). On the fcitx thread,
     * just before that key.
     */
    fun offerGlide(im: String, glide: Glide): Boolean {
        if (!glides(im)) return false
        when (val s = session(im)) {
            is EnglishSession -> s.offerGlide(glide)
            is GlideSession -> s.offer(glide)
            else -> return false
        }
        return true
    }

    /** The handwriting pad's [piece] finished, after its readings: [HandwritingSession.finished]. On the fcitx thread, before an Ink key. */
    fun inkFinished(piece: Int) {
        (session(HANDWRITING) as HandwritingSession).finished(piece)
    }

    /** The latest piece of handwriting ended ([HandwritingSession.ended]), for the pad to wipe; on the fcitx thread. */
    val inkEnded: Int get() = (sessions[HANDWRITING] as? HandwritingSession)?.ended ?: inkEndedBefore

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = sessions[im]?.candidates(from, count).orEmpty()

    /** What a long press on the [index]th of [im]'s candidates offers; nothing if it has no session yet. */
    fun offers(im: String, index: Int): Set<Offer> = sessions[im]?.offers(index).orEmpty()

    // each of [glides] made a GlideSession or an EnglishSession, which offerGlide hands the glide to
    private fun session(im: String): Session = sessions.getOrPut(im) {
        val s = settings
        when (im) {
            PINYIN -> {
                val segmenter = PinyinSegmenter(
                    s.fuzzy, s.typos, neighbours = s.typos,
                    latin = if (s.latinWords) assets.latinWords else null, userLatin = if (s.latinWords) latin.words else null,
                )
                val pinyin = pinyinSession(segmenter, spell = false, chaizi = chaizi())
                val looked = ForgetsInTables(StrokeLookup(pinyin, assets::strokes, assets::charReading, s.pageSize, blocked = lexicon::isBlocked, follow = pinyin::follow, retype = pinyin::typeAll))
                GlideSession(looked, syllableBoundary, blocked = glideHeldBack(), heldBack = heldBack(pinyin)) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            ENGLISH -> {
                val data = warmUpEnglish()
                EnglishSession(data, pageSize = s.pageSize, user = if (useHabits) english.user() else EnglishUser.OFF, nineKeys = s.nineKeys)
            }
            HANDWRITING -> {
                // read as pinyin reads a sentence: its words, the user's among them, and their counts
                val language = ChineseLanguage()
                val data = language.data
                val decoder = HandwritingDecoder(
                    data.dictionary, data.vocabulary, data.wordIndex, language.scorer,
                    assets::charSyllables, language.user, language.coarse,
                )
                HandwritingSession(
                    decoder, TextWords(data.model, data.wordIndex), s.pageSize, language.user, language.predictor, reranker(), refiner(),
                    block = lexicon::block, forgotten = { forgottenEverywhere(it) }, ended = inkEndedBefore, habits = habitsNow(), simplify = simplified::simplify,
                )
            }
            // 简拼 only where the digits read nothing else: as common on the evaluation set, and faster
            // glided as full pinyin, over the digits' keys: each letter typed as itself, as one chosen from a key's long press
            T9 -> {
                val pinyin = pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = s.nineKeys), spell = true)
                GlideSession(
                    ForgetsInTables(pinyin),
                    syllableBoundary,
                    blocked = glideHeldBack(),
                    typed = { c -> if (c == PinyinGlide.SEPARATOR) Action.Key(c, glided = true) else Action.Letter(c, glided = true) },
                    readings = GlideSession.NINE_KEYS_READINGS,
                    heldBack = heldBack(pinyin),
                ) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            SHUANGPIN -> {
                val spelling = PinyinGlide.Spelling.shuangpin(s.scheme)
                val pinyin = pinyinSession(ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true, kind = Kinds.shuangpin(s.shuangpin))
                GlideSession(
                    ForgetsInTables(pinyin),
                    syllableBoundary,
                    blocked = glideHeldBack(),
                    heldBack = heldBack(pinyin),
                ) { g, n -> pinyinGlide.decode(g.path, g.layout, n, spelling) }
            }
            else -> {
                val method = TABLES[im] ?: throw IllegalArgumentException("no input method $im")
                val table = tables.table(im)
                val options = tableOptions(method, s).copy(pageSize = s.pageSize)
                // looking a character up by pinyin learns as full pinyin does (U-28), the table's codes untouched;
                // no phrases, emoji nor 联想 there: what it shows is to be typed by its code. Habits off, as
                // pinyin's own session: nothing learned, nor read of what was (U-93)
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(
                    assets.pinyinData, PinyinSegmenter(), user = sessionUser(), prediction = false, prior = lexicon.prior(),
                    builtInPhrases = false, block = lexicon::block, charReadings = assets::charSyllables,
                    extensions = assets::decoderExtensions, addedWords = ::addedNow,
                    learnTo = LearnTo(habitsNow(), Kinds.FULL, simplified::simplify),
                )
                val language = tableLanguage(im)
                val glide = TableGlide(
                    table.dictionary, { text -> language.words.logProb(text) }, PinyinGlide.WEIGHTS,
                    // the table's blocks too: a word blocked under a code scores -inf from then on (U-28);
                    // keyed on which state as well: a replay or clear makes a new one counting from 0 (by its id, not
                    // holding it: F3216)
                    version = { language.user?.let { it to it.changes } to learnLog().state.let { it.id to it.tableBlocksVersion } },
                    // the words counted, scored anew a glide: their scores move with every word learned
                    learned = { text -> language.user?.typed(text) == true },
                    // the table's own blocks, under a code, alone: a block in pinyin hides none of them (U-28 C12)
                    blocked = { code, text -> text in openHabits().blockedUnder(Code(Kinds.table(im), code)) },
                )
                // a glide after a code typed picks its first candidate: the next code is the glide's
                GlideSession(
                    TableSession(table.dictionary, options, lookUp?.let(::ForgetsInTables), if (useHabits) table.user else TableUser(table.dictionary), sharedWords(table, im), language),
                    between = Action.Pick(0),
                    blocked = glideHeldBack { false },
                ) { g, n -> glide.decode(g.path, g.layout, n) }
            }
        }
    }

    // a glide's word the session held back, committed all the same: recorded as learning records a pick
    private fun heldBack(pinyin: PinyinSession): (GlideReading, () -> String) -> Unit = { r, first -> pinyin.heldBack(r.word, r.pinyin, first) }

    // what a glide's other readings leave out: the words blocked, and the coarse ones but those the
    // user added, as the decoders hold them back (CoarseWords); [blocked] as the session blocks
    private fun glideHeldBack(blocked: (String) -> Boolean = lexicon::isBlocked): (String) -> Boolean {
        val coarse = (CoarseWords.LIST - addedNow()).toHashSet()
        return { blocked(it) || it in coarse }
    }

    /**
     * What a table shares with the other Chinese input methods: the word model with the user's words,
     * the one user model, 联想 as the setting has it, the sentence models of the 性能档位.
     */
    private fun tableLanguage(im: String): TableLanguage {
        val language = ChineseLanguage()
        val data = language.data
        return TableLanguage(
            TextWords(data.model, data.wordIndex, language.scorer),
            language.user,
            reading = { text -> data.wordIndex.find(text).takeIf { it != NO_WORD }?.let(language.readings::reading) },
            predictor = language.predictor,
            reranker = reranker(),
            refiner = refiner(),
            block = lexicon::block,
            forgotten = { forgottenEverywhere(it) },
            emoji = emoji(),
            phrases = wordPhrases(),
            habits = habitsNow(),
            kind = Kinds.table(im),
            simplify = { simplified.simplify(it) },
            // the record's own, not the habits a session is made with (U-93 keeps the blocks)
            blockedUnder = openHabits()::blockedUnder,
        )
    }

    /**
     * What handwriting and a table read by, as pinyin does (specs/engine.md), made with each of their
     * sessions: pinyin's [data], the one [user] model, the word model with the user's words
     * ([scorer]), the coarse words held back ([coarse], one for them all), and 联想 ([predictor]).
     */
    private inner class ChineseLanguage {
        val data = assets.pinyinData
        val user = sessionUser()
        val scorer = lexicon.prior().scorer(UserScorer(data.model, user))
        val coarse = coarseWords ?: CoarseWords.of({ data.wordIndex.find(it) }, CoarseWords.LIST - addedNow()).also { coarseWords = it }

        // a word's reading as it is learned: as the user typed it, else the dictionary's likeliest. The
        // coarse words hold back only what is predicted, not a reading: one predictor for both
        val readings = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = assets::charSyllables)

        // what may follow, as pinyin offers it: the same setting
        val predictor = readings.takeIf { settings.prediction }
    }

    /** A session of one of the pinyins, alike but for how [segmenter] reads the keys; the built-in phrases as set, kept from [spell] by the session. */
    private fun pinyinSession(
        segmenter: Segmenter,
        spell: Boolean,
        chaizi: (String) -> List<Pair<String, String>> = { emptyList() },
        kind: String = Kinds.FULL,
    ): PinyinSession {
        val s = settings
        return PinyinSession(
            assets.pinyinData, segmenter, spell = spell,
            pageSize = s.pageSize, user = sessionUser(), prediction = s.prediction, prior = lexicon.prior(), phraseBook = if (useHabits) lexicon.phrases() else lexicon.freshPhrases(),
            builtInPhrases = s.builtInPhrases,
            reranker = reranker(), refiner = refiner(), block = lexicon::block, emoji = emoji(), chaizi = chaizi,
            charReadings = assets::charSyllables, extensions = assets::decoderExtensions, addedWords = ::addedNow,
            learnTo = LearnTo(habitsNow(), kind, simplified::simplify),
        )
    }

    /**
     * [pinyin], what a long press forgets there forgotten in every table too, as [TableRegistry.shared] has it:
     * a phrase an older build saved there (U-84). Offered only where something was learned of it (PY-33);
     * what the record has of it, a table's picks too, the pinyin forgets itself. A table's lookup is
     * pinyin wrapped so (review C N3).
     */
    private inner class ForgetsInTables(private val pinyin: Session) : Session by pinyin {
        override fun apply(action: Action): Snapshot {
            val forgotten = if (action is Action.Forget && Offer.FORGET in pinyin.offers(action.index)) pinyin.candidates(action.index, 1).firstOrNull() else null
            return pinyin.apply(action).also { if (forgotten != null) tables.forgetInTables(listOf(forgotten.text)) }
        }
    }

    // the user's words a table offers, and the blocks it keeps; while habits are off (U-93) the blocks
    // and the dictionaries turned on alone, as pinyin's fresh model keeps them (review F3412)
    private fun sharedWords(table: TableRegistry.Table, im: String): SharedWords {
        val shared = if (useHabits) tables.shared(table, im) else tables.dictionariesOnly(table, im)
        shared.prepare()
        return shared
    }

    // the words added by hand, as the sessions offer them: none while habits are off (U-93)
    private fun addedNow(): Set<String> = if (useHabits) lexicon.addedTexts() else emptySet()

    // the user's words the sessions read: as installed, but for the blocks and the predictions turned off, while habits are off (U-93)
    private fun sessionUser(): UserModel = if (useHabits) {
        lexicon.user()
    } else {
        lexicon.freshUser().also { it.predictionsOff = ::predictionOff }
    }

    /** The emoji a word stands for, as the Chinese input methods offer them: none, the setting off. */
    private fun emoji(): (String) -> List<String> = if (settings.emoji) assets::emojiOf else { _ -> emptyList() }

    /** The built-in phrases' texts a word stands for, as the tables offer them: none, the setting off. */
    private fun wordPhrases(): (String) -> List<String> =
        if (settings.builtInPhrases) { word -> lexicon.phrases().builtInsOf(word, Calendar::getInstance) } else { _ -> emptyList() }

    /** The characters by their parts' pinyin, as full pinyin offers them, but those blocked: none, the setting off. */
    private fun chaizi(): (String) -> List<Pair<String, String>> =
        if (settings.chaizi) { letters -> assets.chaiziOf(letters).filterNot { lexicon.isBlocked(it.first) } } else { _ -> emptyList() }

    /**
     * The one reranker over the model, for every session: they all run on the fcitx thread, one
     * input typed at a time, and what it ran for one input is started over for another's.
     */
    private fun reranker(): Reranker? = if (settings.modelLevel >= ModelLevel.MEDIUM) assets.sentenceModel.reranker(Reranker.LIMIT) else null

    /** As [reranker], over the larger model, which weighs the readings again while the user pauses. */
    private fun refiner(): SentenceRefiner? = if (settings.modelLevel == ModelLevel.HIGH) assets.lateRefiner else null

    /**
     * Reads [additions] again, for the user changed them: the sessions are made anew, what was
     * typed dropped. If the dictionaries changed, the user's words are read again from the log,
     * with them; with no log, what was learned is only in memory, so the new words are added to it
     * and the removed ones kept till the next start.
     */
    fun reload() {
        val before = lexicon.dropAdditions()
        dropSessions()
        lexicon.reloadDictionaries(before)
    }

    override fun close() {
        // the older build's learning, if made, put in place: not made again at the next start
        afterConversionLands { owed.take(learnLog) }
        // each on its own: one that fails to close must not leave the others open
        (tables.stores() + listOfNotNull(learnLog)).forEach { closeQuietly(it, onError) }
        learnLog = null
        habitCounts = null
        // used after all the same: each reads its log again, and appends to it
        lexicon.closed()
        english.closed()
        tables.closed()
        dropSessions()
    }

    init {
        stashes.takeAll()
    }

    companion object {
        /** How long since the last key typing counts as paused ([paused]). */
        const val IDLE_MILLIS = OwedConversion.IDLE_MILLIS

        /** That the order-1 phrases pinned were moved to the learning record's pins: see movePins. */
        const val PINS_MOVED = "learn.pins-moved"

        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        /** Pinyin on a phone's nine keys (九键). */
        const val T9 = "engine-t9"

        const val WUBI = "engine-wubi"

        /** Handwriting: characters written one at a time on a pad, read as a sentence. */
        const val HANDWRITING = "engine-handwriting"

        /**
         * The input methods but the tables ([TABLES], which read it too) whose sessions read pinyin's
         * data and word index as they are made: the pinyins, and handwriting, which reads its sentences by them.
         */
        private val READS_PINYIN_DATA = setOf(PINYIN, SHUANGPIN, T9, HANDWRITING)

        // what charReading reads off pinyin's dictionary, warmed up as the data files are
        private const val CHAR_READINGS = "char readings"

        /** Offered first to be added: the most asked for after pinyin, which is there already. */
        private val ADDED_FIRST = listOf(T9, WUBI)

        /** [methods] in the order they are offered to be added: [ADDED_FIRST], then the rest as they came. */
        fun <T> addOrder(methods: Collection<T>, name: (T) -> String): List<T> =
            methods.sortedBy { ADDED_FIRST.indexOf(name(it)).takeIf { i -> i >= 0 } ?: ADDED_FIRST.size }

        /**
         * Whether [im] types with [c], no letter, as with a letter: the nine keys' digits, a 双拼
         * [scheme]'s own keys (`;` is ing in 微软 and 紫光), and the pinyins' syllable separator `'`
         * while something is typed ([composing]), as PinyinSession.reads has them; and a table's
         * keys while something is typed, as its session reads them ([tableKeys], [Engines.tableKeys] as
         * read on fcitx's thread: asked only then). Any other an address
         * field takes as it is (PendingInputPolicy.typesAsIs); 微软's `;` taken so cut the syllable
         * being typed, `'` put `xi'an` in as `xi'` and `an`, and 二笔's `;` cut its code.
         */
        fun readsAsKey(
            im: String,
            scheme: ShuangpinScheme,
            c: Int,
            composing: Boolean = false,
            tableKeys: () -> Set<Char> = { emptySet() },
        ): Boolean = when (im) {
            T9 -> Character.isDigit(c) || separates(c, composing)
            SHUANGPIN -> c <= Char.MAX_VALUE.code && scheme.types(c.toChar()) || separates(c, composing)
            PINYIN -> separates(c, composing)
            // with nothing typed a table's is text, as ' is: through the table 二笔's / alone would be 、
            else -> composing && c <= Char.MAX_VALUE.code && c.toChar() in tableKeys()
        }

        // with nothing typed ' is the app's: through the engine it would be a mark, ‘
        private fun separates(c: Int, composing: Boolean) = composing && c == SyllableGraph.SEPARATOR.code

        /**
         * Whether input method [name] is typed with a keyboard's keys: all but those made for a
         * touch screen, the nine keys and handwriting. A hardware keyboard types with one that is.
         */
        fun takesKeyboard(name: String): Boolean = name != T9 && name != HANDWRITING

        /**
         * Whether input method [name] reads a glide across its keys (滑行输入): English, the pinyins and
         * the tables, not handwriting. Each is made a session that reads one (Engines.session): one
         * added here is added there.
         */
        fun glides(name: String): Boolean = name == ENGLISH || name == PINYIN || name == SHUANGPIN || name == T9 || name in TABLES

        /** English: fcitx's own name for it, which fcitx falls back to, so a user's lists keep it. */
        const val ENGLISH = "keyboard-us"

        /** Its words as written and a trigram model over them, counted from FineWeb's English pages, in the app's assets. */
        const val ENGLISH_DATA = "engine/english.data"

        const val PINYIN_DATA = "engine/pinyin.data"

        /** Characters by their strokes (rime-stroke), for pinyin's `u` lookup; read if the app has it. */
        const val STROKE_DATA = "engine/stroke.data"

        /** The emoji panel's groups and the words emoji stand for (EmojiData); read if the app has it. */
        const val EMOJI_DATA = "engine/emoji.data"

        /** Characters by the pinyin of their parts (Chaizi), for full pinyin; read if the app has it. */
        const val CHAIZI_DATA = "engine/chaizi.data"
        /** Read if the app has one; without it the engine types by the decoder alone. */
        const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
        /** Six times the work of [SENTENCE_MODEL], and right more often: see [PinyinSession]'s refiner. */
        const val REFINING_MODEL = "engine/sentence-model-large.safetensors"
        const val TABLE_DIR = "engine/table"
        const val USER_PINYIN = "pinyin.user"
        const val USER_ENGLISH = "english.user"

        /** Beside a table's log: its phrases saved before were told to pinyin (see shared). */
        const val TOLD = ".told"

        /** Where what the user taught the table input method [im] is kept: `wubi.user` and so on. */
        fun userTable(im: String) = im.removePrefix("engine-") + ".user"

        /** Whether [im] is a table input method, the only kind [tableKeys] has keys for. */
        fun hasTableKeys(im: String) = im in TABLES

        /** Each table input method. */
        val TABLES: Map<String, TableMethod> = mapOf(
            WUBI to TableMethod("wbx.data", "Wubi", TableOptions.WUBI, "wbx"),
            "engine-cangjie" to TableMethod("cj.data", "Cangjie", TableOptions.CANGJIE, "cangjie"),
            "engine-ziranma" to TableMethod("zrm.data", "Ziranma", TableOptions.ZIRANMA, "zrm"),
            "engine-erbi" to TableMethod("erbi.data", "Erbi", TableOptions.ERBI, "erbi"),
            "engine-wubipinyin" to TableMethod("wbpy.data", "WubiPinyin", TableOptions.WUBI_PINYIN, "wbpy"),
        )
    }
}

/** Closes [closeable], what fails reported to [onError] rather than thrown. */
internal fun closeQuietly(closeable: Closeable, onError: (IOException) -> Unit) {
    try {
        closeable.close()
    } catch (e: IOException) {
        onError(e)
    }
}

/**
 * A table input method: its [file] under [Engines.TABLE_DIR], the [group] of its settings in the
 * addon's config (see [EngineSettings.tables]), and how it behaves unless those say otherwise.
 * [libime] the name libime's table input method had.
 */
class TableMethod(val file: String, val group: String, val options: TableOptions, val libime: String)
