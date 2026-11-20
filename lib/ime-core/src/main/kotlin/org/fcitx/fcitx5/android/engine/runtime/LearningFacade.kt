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
import org.fcitx.fcitx5.android.engine.store.RecordStore
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * The learning record and everything done with it for [Engines]: sole holder of `learn.log`, opened at first use
 * ([learnLog]), never at engine creation since opening writes; plus backups, restore stashes, user acts, imports,
 * the habits switch and the user model's counts. [invalidate] remakes sessions on changes. On the engines' thread.
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
    private val unkept: () -> Unit = {},
    private val openAppend: (LogFile) -> OutputStream = LogFile::append,
) {
    // imported words: in the learning record, told to the user model
    val imports = ImportLayer(::learnLog, { lexicon.user() }, { lexicon.isBlockedWord(it) }, invalidate, learnClock)

    // the learning record, opened before the user model made from it
    private var learnLog: LearnLog? = null

    // the current user model's counts, kept as the record changes
    private var habitCounts: HabitCounts? = null

    // a long press's forget in handwriting: the record's, as the model's; a table's remembered words stay
    fun forgottenEverywhere(text: String) {
        habits.forget(text)
    }

    /** Opened at first use. */
    fun learnLog(): LearnLog = learnLog ?: run {
        val log = LearnLog(userDir?.file(LearnLog.FILE), learnClock, onError = onError, background = compactions, unkept = unkept, openAppend = openAppend)
        learnLog = log
        try {
            // a clear killed before deleting its set-aside copies: delete them now
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
        opened
    }

    // the record faulted: learn in memory this run
    private fun notOpened(log: LearnLog, e: Exception) {
        onError(IOException("the learning record not opened", e))
        runCatching { log.close() }
        learnLog = LearnLog(null, learnClock, onError = onError, unkept = unkept)
    }

    // learning backups: made every few typing days and on request, listed, merged on restore
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
        // read off the keyboard thread: a log read during an append reads up to the last whole record
        logBytes = userDir?.let { dir -> { dir.file(LearnLog.FILE).takeIf { it.exists() }?.read() } },
        addresses = addresses,
        putAddresses = putAddresses,
        voiceAlways = { lexicon.voiceAlways },
        putVoiceAlways = lexicon::putVoiceAlways,
    )

    private fun listed(kind: UserWord.Kind) = lexicon.userWords(kind).map { ListedWord(it.text, Syllables.apostrophed(it.pinyin)) }

    private fun ListedWord.word(kind: UserWord.Kind) = UserWord(text, Syllables.spaced(code), kind)

    // rewrites the record via [transform] and remakes sessions. A record that never opened learns in memory, where
    // a rewrite would not last: an error then, so a restore says so and a settings stash is kept. The file is
    // compared with what [transform] wrote (restores and reverts always write HEAD)
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

    // learning left by a settings restore while the engine was stopped (RestoreStash)
    private val stashes = StashIntake(
        userDir, onError,
        read = { dir -> RestoreStash.read(dir, onError) },
        merge = { backup, time -> backups.restore(backup, since = time) },
    )

    /** Merges and removes each restore stash: last, once every part is made. */
    fun takeStashes() = stashes.takeAll()

    /** Gives a new user model the user's words and counts from the record. */
    fun attach(model: UserModel) {
        val log = learnLog()
        habitCounts = HabitCounts(model).also { it.attach(log.state) }
        model.predictionsOff = ::predictionOff
    }

    /** Whether the user turned predictions off after [prev], as the record says now. */
    fun predictionOff(prev: String, next: String) = learnLog?.state?.noPredict?.contains(prev to next) == true

    // the user's acts on what was learned
    private val control = LearningControl(::learnLog, learnClock, legacy = ::retired, copies = { userDir?.let(::keptAside).orEmpty() }, onError = onError)

    // a table's old log only with a clear of everything or once read into the record: before, its phrases are the user's still
    private fun retired(all: Boolean): List<LogFile> {
        val dir = userDir ?: return legacyFiles
        val tableLogs = if (all || dir.file(Engines.TABLE_LOGS_READ).taken()) TABLES.keys.map { dir.file(it.removePrefix("engine-") + ".user") } else emptyList()
        return LearningControl.retired(dir, tableLogs) + legacyFiles
    }

    // the record's whole set-aside copies (RecordStore.KeptAside): a clear means gone, and a copy cannot lose one
    // period alone. Listed by the name a clear renames from, so one a killed clear left is found (LearningControl.resumeDrop)
    private fun keptAside(dir: UserFiles): List<LogFile> =
        dir.files().map { it.removeSuffix(LearningControl.CLEARED) }.distinct()
            .filter { RecordStore.isAside(LearnLog.FILE, it) }.map(dir::file)

    private val ops by lazy(LazyThreadSafetyMode.NONE) {
        LearningOps(
            shown, lexicon, ::learnLog, control,
            habits = { useHabits }, useHabits = { useHabits = it }, dropSessions = invalidate,
            first = first,
            // a clear forgets backups holding what it clears first, and saves one of what is left after
            backups = backups, backup = backups::run, restoreCorrection = { english.restoreCorrection(it) },
            resetTableOrder = ::resetTableOrder,
        )
    }

    /** 「用我的输入习惯」: off, sessions are made as installed except blocks: no record read or written, no user words, phrases or English learning, no typing days. Changing it remakes sessions. */
    var useHabits = true
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** See [Engines.learning]. */
    fun apply(im: String?, op: LearningOp, shown: (String) -> String = { it }, act: (event: Int, index: Int, text: String) -> Boolean): LearningResult = ops.apply(im, op, shown, act)

    /** Dated as the record has them. */
    fun dated(words: List<UserWord>): List<UserWord> = ops.dated(words)

    /** None while the user turns them off. */
    fun habitsNow(): Habits = if (useHabits) openHabits() else Habits.OFF

    /** The record opened first (its listener set, counts attached). */
    fun openHabits(): Habits = habits.also { learnLog() }

    // what a record changed: the model's words and counts, and a forgotten text off the added words
    private fun changed(changes: LearnState.Changes) {
        val log = learnLog ?: return
        habitCounts?.apply(log.state, changes)
        changes.forgotten.forEach(lexicon::unAdd)
        if (changes.commits.isNotEmpty()) backups.afterCommit()
    }

    /** Remakes the counts a new typing day changed (HabitCounts.settle): as a field starts, not on a key. */
    fun settle() {
        learnLog?.let { log -> habitCounts?.settle(log.state) }
    }

    /** Blocked texts unpinned under every code: the block wins. */
    fun unpinEverywhere(texts: Collection<String>) {
        val pins = learnLog().state.pins.filter { it.value.text in texts }
        for ((code, words) in pins) habits.pin(code, words.text, words.reading, on = false)
    }

    /** Makes words the user's own where not yet, an export's count as today's uses where none: how many were new. */
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

    /** 恢复码表顺序: forgets table [im]'s learned order, keeping pins and blocks; nothing for non-tables. */
    fun resetTableOrder(im: String) {
        if (im in TABLES) openHabits().resetTableOrder(im)
    }

    /** Reopened at its next use. */
    fun close() {
        learnLog?.let { closeQuietly(it, onError) }
        learnLog = null
        habitCounts = null
    }
}
