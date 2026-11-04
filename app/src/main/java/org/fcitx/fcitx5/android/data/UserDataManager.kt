/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.data.clipboard.CLIPBOARD_DATABASE
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.importedClipboardMarker
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.store.RecordStore
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import org.w3c.dom.Element
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    // fcitx and the clipboard go on writing while this reads: a file renamed or deleted since it was listed is left out
    private fun File.openIfThere() = try {
        inputStream()
    } catch (e: FileNotFoundException) {
        if (exists()) throw e
        null
    }

    // [skip]: files left out; [skipDir]: directories left out with all in them
    private fun writeFileTree(
        srcDir: File,
        destPrefix: String,
        dest: ZipOutputStream,
        skipDir: (File) -> Boolean = { false },
        skip: (File) -> Boolean = { false },
    ) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().onEnter { it == srcDir || !skipDir(it) }.forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile && !skip(f)) {
                    f.openIfThere()?.use {
                        dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                        it.copyTo(dest)
                    }
                }
            }
        }
    }

    /** Where the user's data is: the app's ([appDirs]), or a test's. */
    internal class Dirs(
        val sharedPrefs: File,
        val dataBases: File,
        val external: File,
        val recentlyUsed: File,
        // the engine's user store (EngineBridge): what it learnt of the user's words, user tables
        val engine: File,
        // the host's config, fcitx's data and the themes where they are read (HostConfigDir.inUse): the app's own
        // files once moved in (ConfigHome), what an export takes and a restore writes into; a backup has them under
        // external/ by their old names, as fcitx's home on the external storage had them
        val config: File,
        val data: File = File(external, ConfigHome.DATA.old),
        val theme: File = File(external, ConfigHome.THEME.old),
        // the three settled where they are written: if not, an export would take a stand-in and a restore
        // write where the next start does not read, so both refuse (user_data_not_settled)
        val settled: Boolean = true,
    ) {
        // each by its name in a backup's external/
        val moved get() = listOf(ConfigHome.CONFIG.old to config, ConfigHome.DATA.old to data, ConfigHome.THEME.old to theme)
    }

    // internal for ClipboardManagerReopenTest, which leaves the import's clipboard marker where an import into these does;
    // made at each use, as this object outlives a test's app and the next test's app has directories of its own
    internal val appDirs
        get() = listOf(ConfigHome.CONFIG, ConfigHome.DATA, ConfigHome.THEME).map(HostConfigDir::inUse).let { (config, data, theme) ->
            Dirs(
                File(appContext.applicationInfo.dataDir, "shared_prefs"),
                File(appContext.applicationInfo.dataDir, "databases"),
                // none (the external storage away): what is left of it, nothing of the user's now, under the cache
                appContext.getExternalFilesDir(null) ?: File(appContext.cacheDir, "external.away"),
                appContext.filesDir.resolve(RecentlyUsed.DIR_NAME),
                appContext.filesDir.resolve("engine"),
                config.dir,
                data.dir,
                theme.dir,
                settled = config.writable && data.writable && theme.writable,
            )
        }

    // SQLite's files beside a database
    private val journals = listOf("-journal", "-wal", "-shm")

    /**
     * A copy in [dir] of the clipboard's database in [databases], without the entries an app marked
     * sensitive, a password from a password manager say: the backup is a plain zip. Null if there is
     * none, or if the copy cannot be cleaned.
     */
    private fun clipboardWithoutSensitive(databases: File, dir: File): File? {
        // with its journals: what is not yet written into it is there
        for (suffix in listOf("") + journals) {
            val file = File(databases, CLIPBOARD_DATABASE + suffix)
            file.openIfThere()?.use { input -> File(dir, file.name).outputStream().use { input.copyTo(it) } }
        }
        val copy = File(dir, CLIPBOARD_DATABASE).takeIf { it.isFile } ?: return null
        try {
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // out of the write-ahead log, which would keep the entries in a file beside the copy
                db.disableWriteAheadLogging()
                // a copy thrown away after: no journal, no fsync (each made VACUUM take 90 s at 100 000 entries on a device)
                db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous=OFF")
                db.execSQL("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE sensitive=1")
                // else their text stays in the pages they leave free
                db.execSQL("VACUUM")
            }
        } catch (e: SQLiteException) {
            // copied while the clipboard wrote to it, torn: the backup goes without it, not without the settings and words too
            Timber.w(e, "Clipboard history left out of the backup: its copy cannot be cleaned")
            return null
        }
        return copy
    }

    /**
     * The user's data as a zip, with [learning], the engine's learning as a backup (its
     * BackupOp.Copy), in place of its own files; null if the engine could not give it, the files then
     * going as they are.
     */
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis(), learning: ByteArray? = null) =
        export(dest, timestamp, appDirs, learning)

    @OptIn(ExperimentalSerializationApi::class)
    internal fun export(dest: OutputStream, timestamp: Long, dirs: Dirs, learning: ByteArray? = null) = runCatching {
        if (!dirs.settled) errorRuntime(R.string.user_data_not_settled)
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(dirs.sharedPrefs, "shared_prefs", zipStream)
            // databases, the clipboard's as clipboardWithoutSensitive copies it
            writeFileTree(dirs.dataBases, "databases", zipStream) { it.name.startsWith(CLIPBOARD_DATABASE) }
            withTempDir { dir ->
                clipboardWithoutSensitive(dirs.dataBases, dir)?.let { db ->
                    zipStream.putNextEntry(ZipEntry("databases/${db.name}"))
                    db.inputStream().use { it.copyTo(zipStream) }
                }
            }
            // what is compiled from the dictionaries (MergedDictionary) is left out: made again from them after a
            // restore, and as large as they are
            val compiled = MergedDictionary::isCompiled
            // external, but for an older build's config, data and themes, the copies moved in from (ConfigHome),
            // which are not read
            val old = dirs.moved.map { (name, _) -> File(dirs.external, name) }.toSet()
            writeFileTree(dirs.external, "external", zipStream, skipDir = { it in old }, skip = compiled)
            // the config, data and themes where every backup has had them, so an older build restores this one's
            // and this one an older's (A4: the app's own files now, fcitx's home on the external storage before)
            // (with the mark of a move whose old directory is not deleted yet: restored, it would have that delete
            // an old directory it never moved)
            for ((name, dir) in dirs.moved) {
                if (dir.isDirectory) writeFileTree(dir, "external/$name", zipStream) { compiled(it) || it.name == ConfigHome.MOVED }
            }
            // engine, but for a log being compacted (RecordStore): it holds nothing the log has not; the
            // backups, which are the phone's own; and with the learning given, the files it is made of
            // and the old logs it was converted from (PLAN §3)
            if (dirs.engine.isDirectory) {
                // the old logs read into the record already (Engines.TABLE_LOGS_READ): left out with the learning or
                // without, or a restore of the bare record would read again what was forgotten since (F2 review B1)
                val oldLogsRead = File(dirs.engine, Engines.TABLE_LOGS_READ).exists()
                writeFileTree(dirs.engine, "engine", zipStream) { f ->
                    val path = f.relativeTo(dirs.engine).path
                    f.name.endsWith(".compacting") || path.substringBefore(File.separatorChar) in OWN_DIRS ||
                        (learning != null && path in LEARNED_FROM) || ((learning != null || oldLogsRead) && isOldLog(path))
                }
            }
            learning?.let {
                zipStream.putNextEntry(ZipEntry(RestoreStash.BACKUP))
                zipStream.write(it)
            }
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }

    /**
     * What the backup lacks of the settings ([backupConfig], its `external/config`, against the config's
     * own directory) and of the text files the user edits ([backupData], its `external/data`: [FOLLOWED]) deleted
     * from the phone: those follow the backup, while dictionaries, themes and the other collections are
     * merged; an entry whose name starts with a dot (the `.libime` copies an older build kept) is left
     * alone, and a backup without `external/config` at all keeps all of the phone's.
     * Run after the copy, so an import failing in it leaves the phone's settings whole.
     */
    private fun pruneAbsent(backupConfig: File, backupData: File, dirs: Dirs) {
        if (!backupConfig.isDirectory) return
        prune(backupConfig, dirs.config)
        for (path in FOLLOWED) prune(File(backupData, path), File(dirs.data, path))
    }

    // the files under [target] (or [target] itself) that [backup] has not at the same place
    private fun prune(backup: File, target: File) {
        target.walkBottomUp().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(target).path
            val there = if (relative.isEmpty()) backup else File(backup, relative)
            if (relative.split(File.separatorChar).none { it.startsWith(".") } && !there.exists()) file.delete()
        }
    }

    fun import(src: InputStream) = import(src, appDirs)

    // stopped as it is unpacked once it would fill the disk (a zip bomb), not after
    @VisibleForTesting
    internal fun extractWithinRoom(src: InputStream, tempDir: File, room: Long = tempDir.usableSpace): List<File> {
        return ZipInputStream(src).use {
            it.extract(tempDir) { _, size, total -> if (total + size > room) errorRuntime(R.string.exception_user_data_no_room) }
        }
    }

    internal fun import(src: InputStream, dirs: Dirs) = runCatching {
        if (!dirs.settled) errorRuntime(R.string.user_data_not_settled)
        withTempDir { tempDir ->
            // closed before anything is copied: nothing may fail the import once the engine's is in
            val extracted = extractWithinRoom(src, tempDir)
            val metadataFile = extracted.find { it.name == "metadata.json" }
                ?: errorRuntime(R.string.exception_user_data_metadata)
            val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
            val origin = UserDataOrigin.of(
                metadata.packageName, BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID_ROOT
            ) ?: errorRuntime(R.string.exception_user_data_package_name_mismatch)
            // checked before anything is copied: refused whole, the phone's as it was. This app's of 0.3.0 or
            // before by the version that made it, with old logs or none (F2 review 8); one from before the
            // renaming had no learning of the engine's to lose, but for old logs it may have. Only that one is
            // judged by its files: this app's of a later version may hold old logs alone (its learning not copied)
            val tooOld = origin == UserDataOrigin.Own && metadata.versionCode < FIRST_WITH_THE_RECORD
            if (tooOld || (origin is UserDataOrigin.Legacy && isBeforeTheRecord(tempDir))) errorRuntime(R.string.exception_user_data_too_old)
            if (origin is UserDataOrigin.Legacy) {
                val prefs = File(tempDir, "shared_prefs")
                val legacy = File(prefs, origin.preferences)
                // else copied in under a name this app does not read: every setting at its default
                if (legacy.exists() && !legacy.renameTo(File(prefs, origin.renamed))) {
                    throw IOException("cannot rename ${legacy.name}")
                }
            }
            // the settings it brings that have no UI go back to their defaults at the next start:
            // not here, where AppPrefs would write its own copy over the imported one
            importedUserDataMarker(appContext).createNewFile()
            // the address bar's book the phone's, where the learning the zip brings carries the zip's: merged into it at
            // the next start, as a restore of a learning backup is, and the phone's kept for 退回恢复前
            keepAddressBook(tempDir)
            copyDir(File(tempDir, "shared_prefs"), dirs.sharedPrefs)
            val databases = File(tempDir, "databases")
            // only an import's own copy marks the clipboard's file the backup's: not a marker the backup brings, which
            // would mark the user's own file if it has no database. One an earlier import left stays, a start having
            // kept it for what may pass: the file is a backup's whether this import brings none, fails before its copy
            // or copies its own
            importedClipboardMarker(databases).deleteRecursively()
            copyDir(databases, dirs.dataBases)
            // a journal left of a database it replaced would be played over the one it brings: deleted only once that
            // is in, as a failed import must leave it its journal; the backup's own kept
            databases.list()?.forEach { name ->
                journals.map { name + it }.filterNot { File(databases, it).exists() }.forEach { File(dirs.dataBases, it).delete() }
            }
            // the clipboard's file the backup's now: its next open, at the start after this or at the reopen after a
            // failure, may start over what will not pass, where the user's own is kept. A marker that cannot be written
            // leaves the file to be kept as the user's
            if (File(databases, CLIPBOARD_DATABASE).exists()) {
                runCatching { importedClipboardMarker(dirs.dataBases).createNewFile() }
                    .onFailure { Timber.w(it, "Clipboard database import not marked: its next open keeps it as the user's") }
            }
            // the config, data and themes, where a backup has them, into where they are read (the app's own
            // files, ConfigHome: settled before, by appDirs, so an older build's is moved in first, never
            // passed over by a restore made before the move), the rest of external where it was
            val external = File(tempDir, "external")
            for ((name, dir) in dirs.moved) {
                val from = File(tempDir, "external-$name")
                if (File(external, name).renameTo(from)) {
                    dir.mkdirs()
                    copyDir(from, dir)
                }
            }
            if (external.isDirectory) copyDir(external, dirs.external)
            // the settings are the backup's (PR-10): a config file it has not was at its defaults when it was
            // made, fcitx writing only what is set, so the phone's is dropped, not left over the backup's
            pruneAbsent(File(tempDir, "external-${ConfigHome.CONFIG.old}"), File(tempDir, "external-${ConfigHome.DATA.old}"), dirs)
            // keep importing recently_used for backwords compatibility
            copyDir(File(tempDir, "recently_used"), dirs.recentlyUsed)
            // last: an import failing after it would start fcitx again on the logs it replaced
            val learning = File(tempDir, RestoreStash.BACKUP).takeIf { it.isFile }
            if (File(tempDir, "engine").exists() || learning != null) {
                val source = File(tempDir, "engine")
                source.mkdirs()
                importEngine(source, dirs.engine, learning)
            }
            metadata
        }
    }

    // where the zip extracted to [tempDir] brings a learning backup: its prefs' entries of the address book (AddressStore)
    // the phone's, as AppPrefs has them (what it saved last may not be on disk yet). A file that will not parse is left
    // as it is, the zip's book in it: the import is not failed for what was once only copied
    private fun keepAddressBook(tempDir: File) {
        val staged = File(tempDir, "shared_prefs/${UserDataOrigin.preferencesFile(appContext.packageName)}")
        if (!File(tempDir, RestoreStash.BACKUP).isFile || !staged.isFile) return
        val internal = AppPrefs.getInstance().internal
        val phone = listOf(internal.addressCounts, internal.rememberedAddresses).associate { it.key to it.getValue() }
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                // a prefs file has no DOCTYPE: one is refused where the parser can (Android's may not know the feature)
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(staged)
        }.getOrElse {
            Timber.w(it, "Imported settings unreadable: the address book left the backup's")
            return
        }
        val root = doc.documentElement
        root.childNodes.let { nodes -> (0 until nodes.length).map(nodes::item) }
            .filterIsInstance<Element>().filter { it.getAttribute("name") in phone }
            .forEach { root.removeChild(it) }
        for ((key, value) in phone) {
            if (value.isEmpty()) continue
            root.appendChild(doc.createElement("string").apply {
                setAttribute("name", key)
                textContent = value
            })
        }
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(staged))
    }

    /**
     * [source] laid over [engine], as [copyDir] would, but put in place in one rename: if the
     * import fails here, the engine's directory is whole as it was, for fcitx to start again on.
     *
     * The learning is merged, not laid over (U-100, PLAN §2.9): the phone's learning record, its
     * lists of words, its old logs (every `*.user`: a changed one would read to the record as the
     * phone's words removed) and its backups stay; what the backup has of learning ([learning], the
     * zip's backup of it; or its record; or, older, its old logs to convert; with its lists) is left
     * in a directory of its own in [RestoreStash.DIR] for the engine to merge as it next starts,
     * beside any the engine has not merged yet.
     */
    private fun importEngine(source: File, engine: File, learning: File?) {
        if (!source.isDirectory) {
            Timber.w("Cannot import user data: path='${source.path}' is not a directory")
            return
        }
        replaceDirectory(engine, File(engine.path + ".import")) { staged ->
            // the backup moved, and only the engine's files it lacks copied: a full phone may have no room for two copies
            if (!source.renameTo(staged)) source.copyRecursively(staged)
            stash(staged, learning, engine)
            engine.walkTopDown()
                // a directory the backup has as a file: the backup's
                .onEnter { !File(staged, it.toRelativeString(engine)).isFile }
                // half written by a compaction stopped with fcitx: nothing the log has not
                .filterNot { it.name.endsWith(".compacting") }
                .forEach {
                    val path = it.toRelativeString(engine)
                    val target = File(staged, path)
                    val phonesOwn = path in PHONES || path.substringBefore(File.separatorChar) in OWN_DIRS || isOldLog(path)
                    if (it.isDirectory) target.mkdirs() else if (phonesOwn || !target.exists()) it.copyTo(target, overwrite = true)
                }
        }
    }

    // the backup's learning in [staged] (its engine directory) moved aside into a new directory of
    // RestoreStash.DIR, named by the time (after any the phone has waiting), and the learning files that
    // are the phone's alone dropped
    private fun stash(staged: File, learning: File?, engine: File) {
        OWN_DIRS.forEach { File(staged, it).deleteRecursively() }
        val waiting = File(engine, RestoreStash.DIR).list().orEmpty().mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L
        val stash = File(staged, RestoreStash.DIR + File.separator + maxOf(System.currentTimeMillis(), waiting + 1))
        stash.mkdirs()
        learning?.copyTo(File(stash, RestoreStash.BACKUP))
        val stashed = STASHED + staged.list().orEmpty().filter(::isOldLog)
        for (name in stashed) File(staged, name).takeIf { it.isFile }?.let { it.copyTo(File(stash, name)); it.delete() }
        PHONES.forEach { File(staged, it).deleteRecursively() }
        if (stash.list().isNullOrEmpty()) stash.parentFile?.let { if (stash.delete()) it.delete() }
    }

    // a zip of 0.3.0 or before: its learning in the old logs alone (no backup of it, no learning record),
    // which only 0.4 converted; 0.4 opened once first converts it, and its zip then has it
    private fun isBeforeTheRecord(zip: File): Boolean {
        val engine = File(zip, "engine")
        if (File(zip, RestoreStash.BACKUP).isFile || File(engine, LearnLog.FILE).isFile) return false
        return engine.list().orEmpty().any(::isOldLog)
    }

    // 0.3.1's version code, its lowest ABI's (Versions.calculateVersionCode: the release's code × 10 + the
    // ABI's): this app's zip of a version before has its learning in the old logs alone
    private const val FIRST_WITH_THE_RECORD = 2130L

    // an older build's log of learning (pinyin's, a table's, English's) or 0.4's mark beside a table's,
    // at the engine directory's top
    private fun isOldLog(path: String) = File.separatorChar !in path && (path.endsWith(".user") || path.endsWith(".user.told"))

    // what 0.4 left beside the record, read by nothing since: still the phone's, not a backup's to lay over it
    private const val OLD_MARKER = "learn.converted"
    private const val OLD_PINS_MOVED = "learn.pins-moved"
    private const val OLD_PINYIN = "pinyin.user"

    // the files of the phone's learning: kept over a backup's, as the backup's learning is merged into them
    private val PHONES = setOf(
        LearnLog.FILE, LearnLog.FILE + RecordStore.UNREADABLE, OLD_MARKER, "$OLD_MARKER.new",
        OLD_PINS_MOVED, WordLists.ADDED, WordLists.BLOCKED, OLD_PINYIN, Engines.TABLE_LOGS_READ,
    )

    // of a backup's engine directory, what is its learning, with its old logs: merged by the engine at its next start; the
    // mark that its record has its tables' old logs read in, so the stash's are not read again
    private val STASHED = listOf(LearnLog.FILE, WordLists.ADDED, WordLists.BLOCKED, Engines.TABLE_LOGS_READ)

    // in fcitx's data, beside the settings (punctuation.conf, androidengine.conf, ..., in the config's own
    // directory): the custom phrases, the quick phrases (fcitx's PkgData data/quickphrase.d) and the
    // punctuation maps (PkgData punctuation/punc.mb.*)
    private val FOLLOWED = listOf("pinyin/customphrase", "data/quickphrase.d", "punctuation")

    // the directories of the engine's that are the phone's alone, neither exported nor imported
    private val OWN_DIRS = setOf(Backups.DIR, RestoreStash.DIR, RestoreStash.UNREADABLE)

    // what the learning a zip carries as a backup is made of, with the old logs (isOldLog): not exported beside it
    private val LEARNED_FROM = setOf(LearnLog.FILE, LearnLog.FILE + RecordStore.UNREADABLE, OLD_MARKER, OLD_PINS_MOVED, Engines.TABLE_LOGS_READ)
}

/**
 * Left by [UserDataManager.import] for the next start, which puts the settings without a UI back
 * to their defaults ([org.fcitx.fcitx5.android.data.prefs.AppPrefs.resetHiddenSettings]); outside
 * the object, which the app's start must not make (it needs the external storage).
 */
fun importedUserDataMarker(context: Context) = File(context.noBackupFilesDir, "user_data_imported")