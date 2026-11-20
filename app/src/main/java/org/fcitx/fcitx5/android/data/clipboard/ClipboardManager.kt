/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.Kept
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.snapshot
import timber.log.Timber
import java.io.File

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    // replaced under the mutex on any thread, read without it
    @Volatile
    private lateinit var clbDb: ClipboardDatabase
    @Volatile
    private lateinit var clbDao: ClipboardDao
    // what clbDb leaves beside it for the next start, for nukeTable
    @Volatile
    private var kept = Kept.NOTHING

    internal enum class Kept {
        // clbDb the file, or in-memory beside a file holding none of the user's clips
        NOTHING,
        // in-memory beside a file failing with what will not pass, or holding undeletable clips
        UNOPENABLE,
        // in-memory beside the user's file that failed with what may pass: its clips back once it opens
        MAY_OPEN
    }

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    private val clearUrlsPref = AppPrefs.getInstance().clipboard.clipboardClearUrls

    // compiling ~200 providers' regexes takes a while
    private val clearUrls by lazy {
        runCatching {
            val rules = appContext.resources.openRawResource(R.raw.clearurls_rules)
                .bufferedReader().use { it.readText() }
            ClearURLs(rules)
        }.onFailure { Timber.w(it, "Failed to load ClearURLs rules") }.getOrNull()
    }

    private fun transform(text: String): String =
        if (clearUrlsPref.getValue()) cleanClipboardText(text, clearUrls?.let { it::transform }) else text

    // told from a copy outside the lock: a ConcurrentModificationException there would kill the process (no handler)
    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        synchronized(onUpdateListeners) { onUpdateListeners.add(listener) }
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        synchronized(onUpdateListeners) { onUpdateListeners.remove(listener) }
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch {
            orNeutral(Unit) {
                removeOutdated()
                updateItemCount()
            }
        }
    }

    @Volatile
    var lastEntry: ClipboardEntry? = null

    // listeners first: whoever sees [lastEntry] knows what they posted to main is queued already
    @VisibleForTesting
    internal fun updateLastEntry(entry: ClipboardEntry) {
        synchronized(onUpdateListeners) { onUpdateListeners.snapshot() }.forEach { it.onUpdate(entry) }
        lastEntry = entry
    }

    fun init(context: Context) {
        open(context)
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitPref.registerOnChangeListener(limitListener)
        launch {
            mutex.withLock {
                startUp { openDatabase(context) }
                tidyUp()
            }
        }
    }

    private suspend fun tidyUp() {
        startUp {
            // marked by a process that died with its undo offer up: nothing can undo them now
            clbDao.purgeAllMarked()
            // after the open, not by a limitListener launch that could run before it
            removeOutdated()
        }
        // after a failed purge too: else the panel would open as if empty
        startUp { updateItemCount() }
    }

    // nothing handles init's launch's exceptions: a persistent one would crash every start
    private suspend fun startUp(step: suspend () -> Unit) {
        runCatching { step() }.onFailure {
            if (it is CancellationException || it !is Exception) throw it
            Timber.w(it, "Failed to start the clipboard database")
        }
    }

    private fun open(context: Context) {
        switchTo(clipboardDatabase(context, CLIPBOARD_DATABASE), Kept.NOTHING)
    }

    /** Until the next start retries the kept files: this run's clips cannot move into the file mid-run. */
    private fun openInMemory(context: Context, kept: Kept) {
        clbDb.close()
        switchTo(Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java).build(), kept)
    }

    private fun switchTo(db: ClipboardDatabase, kept: Kept) {
        clbDb = db
        clbDao = db.clipboardDao()
        this.kept = kept
    }

    // returns what it could not delete: a clip-holding file the user's clearing tries to delete again
    private fun startOver(context: Context, path: File): List<File> {
        clbDb.close()
        val left = deleteWithJournals(context, path)
        if (left.isNotEmpty()) {
            Timber.w("Clipboard database could not be deleted to start it over: %s left", left.joinToString { it.name })
        }
        open(context)
        return left
    }

    // directories too, which deleteDatabase leaves: a journal would else be played over the new database
    private fun deleteWithJournals(context: Context, path: File): List<File> {
        withJournals(path).forEach { it.deleteRecursively() }
        context.deleteDatabase(CLIPBOARD_DATABASE)
        return withJournals(path).filter { it.exists() }
    }

    // a failing open sleeps 500 ms before it retries
    private suspend fun openDatabase(context: Context) {
        withContext(Dispatchers.IO) {
            val path = context.getDatabasePath(CLIPBOARD_DATABASE)
            // a successful import exits the app before anything opens the file
            val marker = importedClipboardMarker(path.parentFile)
            // read before the open, which creates a missing file
            val heldClips = path.isFile && path.length() > 0
            var startedOver = false
            var leftClips = false
            var inMemoryForWhatMayPass = false
            openOrFallBack(
                path,
                marker.exists(),
                open = { clbDb.openHelper.writableDatabase },
                startOver = {
                    startedOver = true
                    leftClips = path in startOver(context, path) && path.isFile && path.length() > 0
                },
                openInMemory = {
                    inMemoryForWhatMayPass = it
                    openInMemory(context, keptInMemory(it, startedOver, heldClips, leftClips))
                }
            )
            // kept for what may pass, the file is still the backup's; else the next start takes it for the user's and keeps it forever
            if (startedOver || !inMemoryForWhatMayPass) marker.deleteRecursively()
        }
    }

    // around an import: an open connection would keep writing to the old files, losing clips
    suspend fun closeDatabase() {
        mutex.withLock {
            clbDb.close()
            // the import replaces the file now: nukeTable must not delete it, nor warn of its clips
            kept = Kept.NOTHING
        }
    }

    /** The backup's file if the marker is beside it, else the user's, kept as at a start. */
    suspend fun reopenDatabase() = mutex.withLock {
        if (!clbDb.isOpen) open(appContext)
        openDatabase(appContext)
        updateItemCount()
    }

    suspend fun get(id: Int): ClipboardEntry? = orNeutral(null) { clbDao.get(id) }

    suspend fun haveUnpinned(): Boolean = orNeutral(false) { clbDao.haveUnpinned() }

    fun allEntries() = clbDao.allEntries()

    suspend fun pin(id: Int) = orNeutral(Unit) { clbDao.updatePinStatus(id, true) }

    suspend fun unpin(id: Int) = orNeutral(Unit) { clbDao.updatePinStatus(id, false) }

    /** As a copy would be; the last pasted first. */
    suspend fun used(ids: List<Int>, now: Long = System.currentTimeMillis()) = orNeutral(Unit) {
        ids.forEachIndexed { i, id -> clbDao.updateTimeIfUnpinned(id, now + i) }
    }

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        orNeutral(Unit) { clbDao.updateText(id, text) }
    }

    suspend fun delete(id: Int) = orNeutral(Unit) {
        clbDao.markAsDeleted(id)
        updateItemCount()
    }

    // none, for the undo hint to offer back, if they cannot be marked
    suspend fun deleteAll(skipPinned: Boolean = true): IntArray = orNeutral(IntArray(0)) {
        val ids = if (skipPinned) {
            clbDao.findUnpinnedIds()
        } else {
            clbDao.findAllIds()
        }
        inBatches(ids) { clbDao.markAsDeleted(*it) }
        updateItemCount()
        ids
    }

    suspend fun undoDelete(vararg ids: Int) = orNeutral(Unit) {
        inBatches(ids) { clbDao.undoDelete(*it) }
        updateItemCount()
    }

    suspend fun purgeMarked(vararg ids: Int) = orNeutral(Unit) {
        inBatches(ids) { clbDao.purgeMarked(*it) }
    }

    // Android 10 and 11's SQLite takes 999 variables at most, and 「无上限」 can clear thousands
    private suspend fun inBatches(ids: IntArray, statement: suspend (IntArray) -> Unit) {
        val batches = idBatches(ids)
        if (batches.size <= 1) return statement(ids)
        clbDb.withTransaction { for (batch in batches) statement(batch) }
    }

    suspend fun outdatedBy(limit: Int): Int = orNeutral(0) {
        val last = lastKept(limit, System.currentTimeMillis()) { clbDao.unpinnedTimestampAt(it) } ?: return@orNeutral 0
        clbDao.countUnpinnedEarlierThan(last)
    }

    /** False when some may be left: in an unopenable file, one kept for what may pass, or tables not cleared. */
    suspend fun nukeTable(): Boolean = withContext(coroutineContext) {
        val deleted = deleteAFileKeptUnopenable(appContext)
        val cleared = clearTables()
        orNeutral(Unit) { updateItemCount() }
        val mayOpen = kept == Kept.MAY_OPEN
        if (mayOpen) Timber.w("Clipboard database kept for what may pass left at the user's clearing: its clips back once it opens")
        deleted && cleared && !mayOpen
    }

    // cleared even if it threw after its DELETE: Room's clearAllTables then VACUUMs, which a full disk fails
    private suspend fun clearTables(): Boolean = clearedTables(
        clear = { clbDb.clearAllTables() },
        isEmpty = { clbDao.countAll() == 0 },
        onClearedThoughFailed = {
            Timber.w("Clipboard database cleared though its clearing failed: no row left")
            // Room tells observers only after the VACUUM: the panel would keep listing the deleted rows
            clbDb.invalidationTracker.refreshAsync()
        }
    )

    // clearing gives up the unopenable file too, else only clearing app data ends an in-memory clipboard; deleted for the
    // next start, as the panel may be using this run's database
    private suspend fun deleteAFileKeptUnopenable(context: Context): Boolean {
        if (kept != Kept.UNOPENABLE) return true
        return mutex.withLock {
            // another caller may have deleted it meanwhile
            if (kept != Kept.UNOPENABLE) return@withLock true
            val left = withContext(Dispatchers.IO) {
                deleteWithJournals(context, context.getDatabasePath(CLIPBOARD_DATABASE))
            }
            if (left.isEmpty()) {
                kept = Kept.NOTHING
                Timber.w("Clipboard database that would not open deleted at the user's clearing: a new one at the next start")
            } else {
                // the next clearing tries again
                Timber.w(
                    "Clipboard database that would not open could not be deleted at the user's clearing: %s left",
                    left.joinToString { it.name }
                )
            }
            left.isEmpty()
        }
    }

    @VisibleForTesting
    internal var lastClipTimestamp = -1L

    // a test's restarted app has its clock started over, while this object keeps the last start's
    @VisibleForTesting
    internal fun resetDuplicateCheck() {
        lastClipTimestamp = -1L
    }

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        val timestamp = clip.description.timestamp
        if (timestamp == lastClipTimestamp) return
        lastClipTimestamp = timestamp
        launch {
            mutex.withLock {
                val entry = ClipboardEntry.fromClipData(clip, ::transform) ?: return@withLock
                if (entry.text.isBlank()) return@withLock
                try {
                    clbDao.find(entry.text, entry.sensitive)?.let {
                        updateLastEntry(it.copy(timestamp = entry.timestamp))
                        clbDao.updateTime(it.id, entry.timestamp)
                        return@withLock
                    }
                    val insertedEntry = clbDb.withTransaction {
                        val rowId = clbDao.insert(entry)
                        removeOutdated()
                        // new entry can be deleted immediately if clipboard limit == 0
                        clbDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastEntry(entry)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val last = lastKept(limitPref.getValue(), System.currentTimeMillis()) { clbDao.unpinnedTimestampAt(it) } ?: return
        // outright: no undo offers them back, and a purge takes only what its hint showed
        clbDao.deleteUnpinnedEarlierThan(last)
    }

}

// a failing statement (a full disk) costs its call, not the IME: the callers' launches have no handler
private inline fun <T> orNeutral(neutral: T, statement: () -> T): T =
    try {
        statement()
    } catch (e: SQLiteException) {
        Timber.w(e, "Clipboard database statement failed")
        neutral
    }

/** On failure, whether [isEmpty] finds no row (a clear that threw after its DELETE). */
internal suspend fun clearedTables(
    clear: suspend () -> Unit,
    isEmpty: suspend () -> Boolean,
    onClearedThoughFailed: () -> Unit
): Boolean {
    val clearedAll = orNeutral(false) {
        clear()
        true
    }
    if (clearedAll) return true
    if (!orNeutral(false) { isEmpty() }) return false
    onClearedThoughFailed()
    return true
}

/** Null below [limit] or for [CLIPBOARD_UNLIMITED] (OFFSET 2147483646 would sort the whole table); [now] with no room. */
internal suspend fun lastKept(limit: Int, now: Long, unpinnedAt: suspend (Int) -> Long?): Long? = when {
    limit == CLIPBOARD_UNLIMITED -> null
    // not OFFSET -1, which SQLite takes as 0, keeping the newest
    limit > 0 -> unpinnedAt(limit - 1)
    else -> now
}

/**
 * No start may crash on the database or delete the user's clips: a failure keeps every file and runs [openInMemory].
 * Only what cannot be the user's history is started over: a directory at the database or a journal, and when
 * [imported] a backup failing with what will not pass.
 */
internal fun openOrFallBack(
    path: File,
    imported: Boolean,
    open: () -> Unit,
    startOver: () -> Unit,
    openInMemory: (mayPass: Boolean) -> Unit
) {
    val failure = openFailure(open) ?: return
    if (!startsOver(failure, path, imported)) {
        Timber.e(failure, "Clipboard database cannot be opened: kept for the next start, this run's clips in memory")
        openInMemory(mayPass(failure, path))
        return
    }
    Timber.w(failure, "Clipboard database cannot be opened, starting it over")
    val again = openFailure {
        startOver()
        open()
    } ?: return
    Timber.w(again, "Clipboard database cannot be opened even started over: this run's clips in memory")
    openInMemory(mayPass(again, path))
}

// of any kind: what Room throws of a rejected schema is no SQLiteException
private fun openFailure(open: () -> Unit): Exception? =
    try {
        open()
        null
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        e
    }

private fun startsOver(e: Exception, path: File, imported: Boolean) =
    withJournals(path).any { it.isDirectory } || (imported && !mayPass(e, path))

private fun mayPass(e: Exception, path: File) = when (e) {
    // with the file there: EMFILE, a permission or SELinux denial, a journal on a full disk
    is SQLiteCantOpenDatabaseException -> path.isFile
    is SQLiteFullException, is SQLiteDiskIOException, is SQLiteDatabaseLockedException, is SQLiteTableLockedException,
    is SQLiteOutOfMemoryException, is SQLiteAccessPermException, is SQLiteReadOnlyDatabaseException -> true
    else -> false
}

/** Clips return only from a file that held them and was not started over; an undeletable clip holder is unopenable. */
internal fun keptInMemory(mayPass: Boolean, startedOver: Boolean, heldClips: Boolean, leftClips: Boolean) = when {
    !mayPass -> Kept.UNOPENABLE
    startedOver -> if (leftClips) Kept.UNOPENABLE else Kept.NOTHING
    heldClips -> Kept.MAY_OPEN
    else -> Kept.NOTHING
}

// internal for ClipboardDatabaseMigrationTest
internal fun clipboardDatabase(context: Context, name: String): ClipboardDatabase = Room
    .databaseBuilder(context, ClipboardDatabase::class.java, name)
    // allow wipe the database instead of crashing when downgrade
    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
    .build()

// an import copies whatever the backup holds, so any of these may be a directory
private fun withJournals(path: File) = listOf("", "-journal", "-wal", "-shm").map { File(path.path + it) }

// UserDataManager's export and import know it by this name too
internal const val CLIPBOARD_DATABASE = "clbdb"

// below SQLite's 999 variables before 3.32 (SQLITE_MAX_VARIABLE_NUMBER)
internal const val ID_BATCH = 900

internal fun idBatches(ids: IntArray): List<IntArray> =
    (ids.indices step ID_BATCH).map { ids.copyOfRange(it, minOf(ids.size, it + ID_BATCH)) }

const val CLIPBOARD_UNLIMITED = Int.MAX_VALUE

/** The app exits after a successful import, so the next open takes the file as imported. */
internal fun importedClipboardMarker(databases: File) = File(databases, "$CLIPBOARD_DATABASE.imported")

/** A throwing cleaner must not cost the entry: the original text is kept. */
internal fun cleanClipboardText(text: String, clearUrls: ((String) -> String)?): String =
    cleanClipboardText(text, BuildConfig.DEBUG, clearUrls)

// [debug] is a parameter so one test run covers both builds' logging
internal fun cleanClipboardText(text: String, debug: Boolean, clearUrls: ((String) -> String)?): String =
    clearUrls?.let {
        runCatching { it(text) }.onFailure { e ->
            // its message may quote the clip: release builds log only its class
            if (debug) {
                Timber.w(e, "Failed to clear URLs")
            } else {
                Timber.w("Failed to clear URLs: ${e.javaClass.name}")
            }
        }.getOrNull()
    } ?: text
