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
import timber.log.Timber
import java.io.File

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    // open and its fallbacks replace them, under the mutex (or in init, before anything reads them) and on whichever
    // thread calls them, while the other calls read them without it
    @Volatile
    private lateinit var clbDb: ClipboardDatabase
    @Volatile
    private lateinit var clbDao: ClipboardDao
    // what clbDb leaves beside it for the next start, for nukeTable
    @Volatile
    private var kept = Kept.NOTHING

    // internal for keptInMemory, which chooses it
    internal enum class Kept {
        // clbDb the file, or this run's in-memory one beside a file that holds none of the user's clips
        NOTHING,
        // this run's in-memory one beside a file that failed with what will not pass, or one holding clips that a
        // start-over could not delete
        UNOPENABLE,
        // this run's in-memory one beside a file of the user's that failed with what may pass: its clips back once it opens
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

    // parsing and compiling ~200 providers' regexes takes a while; only pay for it once needed
    private val clearUrls by lazy {
        runCatching {
            val rules = appContext.resources.openRawResource(R.raw.clearurls_rules)
                .bufferedReader().use { it.readText() }
            ClearURLs(rules)
        }.onFailure { Timber.w(it, "Failed to load ClearURLs rules") }.getOrNull()
    }

    private fun transform(text: String): String =
        if (clearUrlsPref.getValue()) cleanClipboardText(text, clearUrls?.let { it::transform }) else text

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
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
                // the panel opens on the count
                updateItemCount()
            }
        }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
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

    // what a start does once a database is open: each step's failure only logged
    private suspend fun tidyUp() {
        startUp {
            // marked by a process that died with its undo snackbar up: nothing can undo them now
            clbDao.purgeAllMarked()
            // the limit applied here, after the open, rather than by a launch of limitListener's that could run before it
            removeOutdated()
        }
        // after a failed purge too: else the panel would open as if there were no clips
        startUp { updateItemCount() }
    }

    // nothing handles init's launch's exceptions: one that persists would crash the app at every start
    private suspend fun startUp(step: suspend () -> Unit) {
        runCatching { step() }.onFailure {
            if (it is CancellationException || it !is Exception) throw it
            Timber.w(it, "Failed to start the clipboard database")
        }
    }

    private fun open(context: Context) {
        switchTo(clipboardDatabase(context, CLIPBOARD_DATABASE), Kept.NOTHING)
    }

    /** Until the process next starts, which tries the kept files again: this run's clips and the ids held of them ([lastEntry], the panel's, the editor's) cannot move into the file mid-run. */
    private fun openInMemory(context: Context, kept: Kept) {
        clbDb.close()
        switchTo(Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java).build(), kept)
    }

    private fun switchTo(db: ClipboardDatabase, kept: Kept) {
        clbDb = db
        clbDao = db.clipboardDao()
        this.kept = kept
    }

    // what it could not delete, its directory not writable say: a file left there that holds clips, which no start
    // opens, and the user's clearing tries the deletion again (keptInMemory)
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

    // off the main thread, where reopenDatabase's caller is: a failing open sleeps 500 ms before it retries
    private suspend fun openDatabase(context: Context) {
        withContext(Dispatchers.IO) {
            val path = context.getDatabasePath(CLIPBOARD_DATABASE)
            // the import's, for the start after it: a successful import exits the app before anything opens the file
            val marker = importedClipboardMarker(path.parentFile)
            // whether it may hold the user's clips: read before the open, which makes the file it does not find
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
            // for this open alone, once the file opened or was started over: kept for what may pass, the file is still
            // the backup's, which the next start would else take for the user's and, Room rejecting it, keep at every start
            if (startedOver || !inMemoryForWhatMayPass) marker.deleteRecursively()
        }
    }

    // around an import, which replaces the database's files: an open connection would go on
    // writing to the old ones, and the clips copied meanwhile be lost at the next start
    suspend fun closeDatabase() {
        mutex.withLock {
            clbDb.close()
            // the import replaces the file now: nukeTable must not delete it, nor warn of its clips
            kept = Kept.NOTHING
        }
    }

    /** After a failed import: the file the backup's if the import's marker is beside it, else the user's, kept as at a start. */
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

    // none, for its snackbar to offer back, if they cannot be marked
    suspend fun deleteAll(skipPinned: Boolean = true): IntArray = orNeutral(IntArray(0)) {
        val ids = if (skipPinned) {
            clbDao.findUnpinnedIds()
        } else {
            clbDao.findAllIds()
        }
        clbDao.markAsDeleted(*ids)
        updateItemCount()
        ids
    }

    suspend fun undoDelete(vararg ids: Int) = orNeutral(Unit) {
        clbDao.undoDelete(*ids)
        updateItemCount()
    }

    suspend fun purgeMarked(vararg ids: Int) = orNeutral(Unit) {
        clbDao.purgeMarked(*ids)
    }

    /**
     * Clears every clip: false when some may be left, in a file no start opens that could not be deleted, in one kept
     * for what may pass, which they come back from once it opens, or in tables the database would not clear.
     */
    suspend fun nukeTable(): Boolean = withContext(coroutineContext) {
        val deleted = deleteAFileKeptUnopenable(appContext)
        val cleared = clearTables()
        orNeutral(Unit) { updateItemCount() }
        val mayOpen = kept == Kept.MAY_OPEN
        if (mayOpen) Timber.w("Clipboard database kept for what may pass left at the user's clearing: its clips back once it opens")
        deleted && cleared && !mayOpen
    }

    // cleared too when it threw once its DELETE was in: Room's clearAllTables VACUUMs after it, which a full disk fails
    private suspend fun clearTables(): Boolean = clearedTables(
        clear = { clbDb.clearAllTables() },
        isEmpty = { clbDao.countAll() == 0 },
        onClearedThoughFailed = {
            Timber.w("Clipboard database cleared though its clearing failed: no row left")
            // Room tells observers only after the VACUUM: the panel's Pager would else go on listing the deleted rows
            clbDb.invalidationTracker.refreshAsync()
        }
    )

    // asked to clear the clipboard, the user gives up the file no start opens too, a backup Room rejects say: else their
    // only way out of a clipboard in memory at every start is clearing all the app's data. Deleted for the next start to
    // make anew, not started over: the run stays on its in-memory database, which the panel and the editor may be using
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
                // still the file every start keeps: the state stays, for the next clearing to try again
                Timber.w(
                    "Clipboard database that would not open could not be deleted at the user's clearing: %s left",
                    left.joinToString { it.name }
                )
            }
            left.isEmpty()
        }
    }

    private var lastClipTimestamp = -1L

    // duplicates are of a start's clips: a test's app, started again in this process, has its clock started over, while
    // this object keeps the last start's
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
        // the last one we will keep; with fewer than limit unpinned, none is outdated
        val last = lastKept(limitPref.getValue(), System.currentTimeMillis()) { clbDao.unpinnedTimestampAt(it) } ?: return
        // delete all unpinned before that, or delete all when limit <= 0; outright, as no undo
        // offers them back and a purge takes only what its snackbar showed
        clbDao.deleteUnpinnedEarlierThan(last)
    }

}

// a statement that fails though the database opened (a full disk, say) costs its call, not the IME: the
// callers' launches, the panel's and limitListener's, have no handler
private inline fun <T> orNeutral(neutral: T, statement: () -> T): T =
    try {
        statement()
    } catch (e: SQLiteException) {
        Timber.w(e, "Clipboard database statement failed")
        neutral
    }

/**
 * Whether [clear] emptied the clipboard's tables: when it fails, whether [isEmpty] then finds no row left, as after a
 * clearing that threw once its DELETE was in, [onClearedThoughFailed] run then; false when that fails too.
 */
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

/**
 * When the last unpinned entry kept for [limit] was copied, those copied before it outdated: the
 * [limit]th newest time ([unpinnedAt] gives the newest from 0), null with fewer than [limit],
 * [now] with no room for one.
 */
internal suspend fun lastKept(limit: Int, now: Long, unpinnedAt: suspend (Int) -> Long?): Long? =
    // not OFFSET -1, which SQLite takes as 0, keeping the newest
    if (limit > 0) unpinnedAt(limit - 1) else now

/**
 * Opens the clipboard's database at [path] with [open], or puts another in its place, so that no start crashes on
 * it and none deletes the user's clips. What fails keeps every file for the next start, this run's clips going
 * [openInMemory]: a migration left out, a schema Room rejects, a lock, a full disk (a file SQLite finds corrupt as it
 * opens, the framework deletes itself). Only what cannot be the user's history is started over, [startOver], and
 * opened again: a directory at the database or a journal, which a failed import may leave, and, [imported], a file
 * from the backup that fails with what will not pass, the import having replaced the user's own. That failing too,
 * the clips go in memory as well. [openInMemory] is told whether the failure may pass: a file left for what will not,
 * or one holding clips that a start-over could not delete ([keptInMemory]), the user's clearing of the clipboard
 * ([ClipboardManager.nukeTable]) tries to delete, for the next start to make anew.
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

// what [open] throws, null once it opens: of any kind, as what Room throws of a schema it rejects is no SQLiteException
private fun openFailure(open: () -> Unit): Exception? =
    try {
        open()
        null
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        e
    }

// whether [e], thrown opening the database at [path], starts it over: a directory there or at a journal, which holds
// no clips, at any open; after an import, what will not pass, the file being the backup's
private fun startsOver(e: Exception, path: File, imported: Boolean) =
    withJournals(path).any { it.isDirectory } || (imported && !mayPass(e, path))

// whether [e], thrown opening the database at [path], may pass by a later try
private fun mayPass(e: Exception, path: File) = when (e) {
    // with the file there: EMFILE, a permission or SELinux denial, a journal on a full disk
    is SQLiteCantOpenDatabaseException -> path.isFile
    is SQLiteFullException, is SQLiteDiskIOException, is SQLiteDatabaseLockedException, is SQLiteTableLockedException,
    is SQLiteOutOfMemoryException, is SQLiteAccessPermException, is SQLiteReadOnlyDatabaseException -> true
    else -> false
}

/**
 * What this run's in-memory database leaves beside it, the open having failed with what [mayPass] or not: the user's
 * clips come back once it opens only from a file that holds them as the open found it, [heldClips], with no
 * [startedOver]. One that a start-over could not delete and that holds clips, [leftClips], no start opens, each
 * starting it over again: kept unopenable, for the user's clearing to try the deletion again.
 */
internal fun keptInMemory(mayPass: Boolean, startedOver: Boolean, heldClips: Boolean, leftClips: Boolean) = when {
    !mayPass -> Kept.UNOPENABLE
    startedOver -> if (leftClips) Kept.UNOPENABLE else Kept.NOTHING
    heldClips -> Kept.MAY_OPEN
    // new or empty: none of the user's clips to come back once it opens
    else -> Kept.NOTHING
}

// internal for ClipboardDatabaseMigrationTest, which upgrades every exported schema with these migrations
internal fun clipboardDatabase(context: Context, name: String): ClipboardDatabase = Room
    .databaseBuilder(context, ClipboardDatabase::class.java, name)
    // allow wipe the database instead of crashing when downgrade
    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
    .build()

// the database at [path] and its journals, any of which an import may have put in as a directory: it copies whatever the backup holds
private fun withJournals(path: File) = listOf("", "-journal", "-wal", "-shm").map { File(path.path + it) }

// the clipboard's database, by the name UserDataManager's export and import know it by too
internal const val CLIPBOARD_DATABASE = "clbdb"

/**
 * Left in [databases], beside the clipboard's database, by an import once the backup's is in, for the next open to take
 * it as imported: the app exits after a successful import, and the next start is the first to open it. Made here alone,
 * for the import's directories and the clipboard's to find the one file.
 */
internal fun importedClipboardMarker(databases: File) = File(databases, "$CLIPBOARD_DATABASE.imported")

/**
 * Applies [clearUrls] if its rules could be loaded. A cleaner that throws must not cost the user
 * the clipboard entry, so that falls back to the original text.
 */
internal fun cleanClipboardText(text: String, clearUrls: ((String) -> String)?): String =
    cleanClipboardText(text, BuildConfig.DEBUG, clearUrls)

// debug a parameter, BuildConfig.DEBUG's, so that one test run holds both builds' logging
internal fun cleanClipboardText(text: String, debug: Boolean, clearUrls: ((String) -> String)?): String =
    clearUrls?.let {
        runCatching { it(text) }.onFailure { e ->
            // its message may quote the clip: release builds log only its class, as ClearURLs logs no URL
            if (debug) {
                Timber.w(e, "Failed to clear URLs")
            } else {
                Timber.w("Failed to clear URLs: ${e.javaClass.name}")
            }
        }.getOrNull()
    } ?: text
