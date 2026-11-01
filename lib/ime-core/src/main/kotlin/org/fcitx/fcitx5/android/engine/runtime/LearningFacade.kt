/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.learn.DayCounts
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.learn.Mine
import org.fcitx.fcitx5.android.engine.learn.backup.AddressData
import org.fcitx.fcitx5.android.engine.learn.backup.BackupControl
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.ListedWord
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.LearningControl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.table.OlderTableLog
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * The learning record and all that is done with it, for [Engines]: the one holder of `learn.log`
 * (impl/design-1a.md), opened at its first use ([learnLog]: a key's session made, a list asked for),
 * never as the engines are made, for opening it writes (a backup's claim, review B 1.3); its
 * backups, the stashes a settings restore leaves, the user's acts on what was learned, the words
 * imported, U-93's switch, and the counts of the user model of the moment. [invalidate] makes the
 * sessions anew where what they read changed; [shown] and [first] are the session an input method
 * shows now and its first candidate, for a long press's acts. On the engines' thread.
 */
internal class LearningFacade(
    private val userDir: UserFiles?,
    private val onError: (IOException) -> Unit,
    private val learnClock: LearnClock,
    private val compactions: Executor,
    installFile: LogFile?,
    appVersion: String,
    private val legacyFiles: List<LogFile>,
    private val lexicon: UserLexicon,
    private val tables: TableRegistry,
    private val english: EnglishLexicon,
    private val invalidate: () -> Unit,
    private val shown: (String) -> Session?,
    private val first: (String) -> Int,
    addresses: () -> AddressData? = { null },
    putAddresses: (AddressData, merge: Boolean) -> Unit = { _, _ -> },
) {
    // the imported words (U-101): in the learning record, told to the user model
    val imports = ImportLayer(::learnLog, { lexicon.user() }, { lexicon.isBlockedWord(it) }, invalidate, learnClock)

    // the learning record (impl/design-1a.md), opened before the user model, which is made from it
    // (impl/design-1b.md §4)
    private var learnLog: LearnLog? = null

    // the counts of the user model of the moment, kept as the record changes
    private var habitCounts: HabitCounts? = null

    // a long press's forget in handwriting or a table: the tables', and the learning record's, as the model's
    fun forgottenEverywhere(text: String) {
        tables.forgetInTables(listOf(text))
        habits.forget(text)
    }

    /** The record, opened at its first use. */
    fun learnLog(): LearnLog = learnLog ?: run {
        val log = LearnLog(userDir?.file(LearnLog.FILE), learnClock, onError = onError, background = compactions)
        learnLog = log
        // 0.4 stopped before it put an older build's learning in place (its mark left): told once, as none reads it
        // now; the mark left for a 0.4 put back
        userDir?.file(OLD_CONVERTING)?.takeIf { it.taken() && !it.beside(TOLD).taken() }?.let {
            onError(IOException("$it: 0.4 had not put an older build's learning in place; not read since 0.4"))
            runCatching { it.beside(TOLD).createNew() }
        }
        try {
            // a clear killed before it deleted the older builds' files it set aside: deleted now
            if (control.resumeDrop()) control.finishDrop()
            log.open()
        } catch (e: IOException) {
            notOpened(log, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            notOpened(log, e)
        }
        val opened = checkNotNull(learnLog)
        opened.listener = ::changed
        control.attach(opened)
        backups.claim(opened)
        keepOlderTablePhrases(opened)
        opened
    }

    /** The record if it is open: none opened to answer. */
    val openLog: LearnLog? get() = learnLog

    /**
     * Once, at the first open of a build past 0.4 (its mark beside the record then): the phrases 0.4's
     * tables offered from their logs that the record has not, put into it (F2 review). Not marked if a
     * table's data did not load, to be tried again at the next open, nor with no log to read (none
     * comes after: a restore leaves a zip's in its stash); a clear deletes the logs it reads.
     */
    private fun keepOlderTablePhrases(log: LearnLog) {
        val dir = userDir ?: return
        val mark = dir.file(Engines.TABLE_LOGS_READ)
        if (!log.onDisk || mark.taken() || TABLES.keys.none { OlderTableLog.file(dir, it).exists() }) return
        try {
            val rows = olderTablePhrases(dir, log.state) ?: return
            // marked only once on disk: a write that failed reads them again at the next open. The mark
            // not made after the rows went in reads them again too, what was forgotten since back:
            // accepted, as rare as a file that cannot be made, and a clear retires the logs
            if (log.absorb(rows)) mark.createNew()
        } catch (e: IOException) {
            onError(IOException("the tables' older logs not read", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // a bug, not the disk's: told, as learnLog() tells its own, not thrown at the key that opened the record
            onError(IOException("the tables' older logs not read", e))
        }
    }

    // a stash's [state] with the phrases its tables' older logs in [dir] offered, unless its record is of a build that
    // read them already (its TABLE_LOGS_READ beside it: what was forgotten since stays so). A table that did not load
    // keeps the stash aside, as the phone's own are read again at the next open
    @Throws(IOException::class)
    private fun withOlderTablePhrases(dir: UserFiles, state: LearnState) {
        if (dir.file(Engines.TABLE_LOGS_READ).taken()) return
        val rows = olderTablePhrases(dir, state) ?: throw IOException("$dir: a table did not load, its older logs not read")
        rows.forEach { state.apply(it) }
    }

    /**
     * What 0.4's tables offered as saved phrases from their logs in [dir] (OlderTableLog) and [state]
     * has not: 0.3.0's picks of what the table has not (vbvn 好女), which 0.4 converted to counts
     * alone, or a phrase saved its conversion missed. Null if a table's data did not load.
     */
    @Throws(IOException::class)
    private fun olderTablePhrases(dir: UserFiles, state: LearnState): List<TableWordRow>? {
        val rows = ArrayList<TableWordRow>()
        val logs = TABLES.keys.mapNotNull { im -> OlderTableLog.file(dir, im).takeIf { it.exists() }?.let { im to olderPhrases(it) } }
        for ((im, phrases) in logs.filter { it.second.isNotEmpty() }) {
            val dictionary = try {
                tables.table(im).dictionary
            } catch (e: IOException) {
                onError(IOException("${OlderTableLog.file(dir, im)}: not read, its table did not load", e))
                return null
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // its data not the table's: as one not loaded
                onError(IOException("${OlderTableLog.file(dir, im)}: not read, its table did not load", e))
                return null
            }
            for ((code, text) in phrases) {
                if (dictionary.indexOf(code, text) < 0 && Triple(im, code, text) !in state.tableWords) rows += TableWordRow(im, code, text, state.day)
            }
        }
        return rows
    }

    // a log whose bytes are not a log's: told and taken as read, none offered from it, so it is not told again at
    // every start. A read the disk failed throws on (not marked, tried again at the next open, as a table not loaded)
    @Throws(IOException::class)
    private fun olderPhrases(file: LogFile): List<Pair<String, String>> {
        val bytes = file.read()
        return try {
            OlderTableLog.phrases(bytes)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            onError(IOException("$file: not read, not a table's log", e))
            emptyList()
        }
    }

    // a fault of the record's: learned in memory this run (review-1a-app S1)
    private fun notOpened(log: LearnLog, e: Exception) {
        onError(IOException("the learning record not opened", e))
        runCatching { log.close() }
        learnLog = LearnLog(null, learnClock, onError = onError)
    }

    // the learning backups (U-100, U-106; impl/streams/D-design.md): made every few typing days and when asked, listed, merged in when restored
    private val backups: BackupControl = BackupControl(
        Backups(userDir?.dir(Backups.DIR), Backups.installId(installFile, onError), onError, compactions, learnClock),
        learnClock,
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
        importLines = { lexicon.wordImport.importWords(it) },
        // read off the keyboard's thread: a log read while a record is appended reads up to the last whole one
        logBytes = userDir?.let { dir -> { dir.file(LearnLog.FILE).takeIf { it.exists() }?.read() } },
        addresses = addresses,
        putAddresses = putAddresses,
    )

    private fun listed(kind: UserWord.Kind) = lexicon.userWords(kind).map { ListedWord(it.text, Syllables.apostrophed(it.pinyin)) }

    private fun ListedWord.word(kind: UserWord.Kind) = UserWord(text, Syllables.spaced(code), kind)

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
        invalidate()
        val file = userDir?.file(LearnLog.FILE) ?: return true
        if (!file.exists() || !file.read().contentEquals(wrote)) throw IOException("the learning record is not open: learned in memory alone")
        return true
    }

    // the settings backups' learning, left by the app while the engine was stopped (RestoreStash)
    private val stashes = StashIntake(
        userDir, onError,
        // a 0.4 zip's: the phrases its tables offered from their logs, beside its learning, as the phone's at its first open
        read = { dir -> RestoreStash.read(dir, onError).also { withOlderTablePhrases(dir, it.state) } },
        merge = { backup, time -> backups.restore(backup, since = time) },
    )

    /** Each stash a settings restore left merged in, then gone: last, as the engines are made, every part made by then (review B 1.2). */
    fun takeStashes() = stashes.takeAll()

    /** A user model made: the user's own words in it, and the counts, as the record has them. */
    fun attach(model: UserModel) {
        val log = learnLog()
        habitCounts = HabitCounts(model).also { it.attach(log.state) }
        model.predictionsOff = ::predictionOff
    }

    /** U-72: what the user turned off after a word, as the record has it now (a rewrite makes its state anew). */
    fun predictionOff(prev: String, next: String) = learnLog?.state?.noPredict?.contains(prev to next) == true

    // the user's acts on what was learned (impl/streams/E-design.md): the older builds' logs go with what is cleared
    private val control = LearningControl(::learnLog, learnClock, legacy = ::retired, onError = onError)

    // what a clear deletes of the older builds' files: the tables' logs with a clear of everything, or once read
    // into the record (TABLE_LOGS_READ), not before: their phrases are the user's still (F2 review B2)
    private fun retired(all: Boolean): List<LogFile> {
        val dir = userDir ?: return legacyFiles
        val tableLogs = if (all || dir.file(Engines.TABLE_LOGS_READ).taken()) TABLES.keys.map { OlderTableLog.file(dir, it) } else emptyList()
        return LearningControl.retired(dir, tableLogs) + legacyFiles
    }

    private val ops by lazy(LazyThreadSafetyMode.NONE) {
        LearningOps(
            shown, lexicon, ::learnLog, control,
            habits = { useHabits }, useHabits = { useHabits = it }, dropSessions = invalidate,
            first = first, tableForgets = { tables.forgets },
            // a clear forgets the backups that hold what it clears first, and saves one of what is left after (C25)
            backups = backups, backup = backups::run, restoreCorrection = { english.restoreCorrection(it) },
            resetTableOrder = ::resetTableOrder,
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
            invalidate()
        }

    /** Does [op] in [im]: see [Engines.learning]. */
    fun apply(im: String?, op: LearningOp, act: (event: Int, index: Int) -> Boolean): LearningResult = ops.apply(im, op, act)

    /** [words] each dated as the learning record has it (U-80). */
    fun dated(words: List<UserWord>): List<UserWord> = ops.dated(words)

    /** The habits the sessions are made with: none while the user turns them off. */
    fun habitsNow(): Habits = if (useHabits) openHabits() else Habits.OFF

    /** The record's habits, the record opened before they are asked (its listener set, the counts attached). */
    fun openHabits(): Habits = habits.also { learnLog() }

    // what a record changed: the model's words and counts, and a text forgotten off the words added by hand (U-82)
    private fun changed(changes: LearnState.Changes) {
        val log = learnLog ?: return
        habitCounts?.apply(log.state, changes)
        changes.forgotten.forEach(lexicon::unAdd)
        if (changes.commits.isNotEmpty()) backups.afterCommit()
    }

    /** The counts a new typing day changed, made anew (HabitCounts.settle): as a field starts, rather than on a key. */
    fun settle() {
        learnLog?.let { log -> habitCounts?.settle(log.state) }
    }

    /** Texts blocked: unpinned under every code, the block winning (impl/design-1b.md §3). */
    fun unpinEverywhere(texts: Collection<String>) {
        val pins = learnLog().state.pins.filter { it.value.text in texts }
        for ((code, words) in pins) habits.pin(code, words.text, words.reading, on = false)
    }

    /**
     * Words made the user's own where they are not yet, an export's count each as uses today where
     * none are: how many were not.
     */
    fun own(words: Map<UserModel.Entry, Float>): Int {
        val log = learnLog()
        val state = log.state
        val rows = ArrayList<LearnRecord>()
        var owned = 0
        for ((e, count) in words.entries.distinctBy { it.key.text }) {
            if (e.text in state.mine) continue
            val reading = e.spelled()
            rows += MineRow(e.text, Mine(reading, Mine.TAKEN_IN, state.day, state.nextSeq + owned++, state.day, state.localDay, 0))
            if (count > 0f && state.wordUses[e.text]?.get(reading) == null) rows += WordUseRow(e.text, reading, DayCounts.of(state.day to maxOf(1, count.roundToInt())))
        }
        log.absorb(rows)
        return owned
    }

    val habits: Habits = LogHabits(::learnLog, onError)

    /**
     * 恢复码表顺序 (U-28): what table [im] learned of the order by use is forgotten, what was pinned
     * and blocked under its codes kept; nothing for an input method no table is.
     */
    fun resetTableOrder(im: String) {
        if (im in TABLES) openHabits().resetTableOrder(im)
    }

    /** The record closed, opened anew at its next use. */
    fun close() {
        learnLog?.let { closeQuietly(it, onError) }
        learnLog = null
        habitCounts = null
    }

    private companion object {
        // beside the record while 0.4 converted an older build's learning, gone once it was in place
        const val OLD_CONVERTING = "learn.converting"
        const val TOLD = ".told"
    }
}
