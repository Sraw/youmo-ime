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
import org.fcitx.fcitx5.android.data.clipboard.favoritesToMerge
import org.fcitx.fcitx5.android.data.clipboard.importedClipboardMarker
import org.fcitx.fcitx5.android.data.clipboard.keepFavoritesToMerge
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.pinyin.CommonWordsManager
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.data.theme.ThemeSeparators
import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.store.RecordStore
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.ZipEnd
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import org.w3c.dom.Element
import timber.log.Timber
import java.io.File
import java.io.FileFilter
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

    // fcitx and the clipboard keep writing: a file gone since listing is left out
    private fun File.openIfThere() = try {
        inputStream()
    } catch (e: FileNotFoundException) {
        if (exists()) throw e
        null
    }

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

    internal class Dirs(
        val sharedPrefs: File,
        val dataBases: File,
        val external: File,
        val recentlyUsed: File,
        val engine: File,
        // where the host reads them; a backup has them under external/ by their old names
        val config: File,
        val data: File = File(external, ConfigHome.DATA.old),
        val theme: File = File(external, ConfigHome.THEME.old),
        // unsettled, an export would take a stand-in and a restore write where the next start does not read: both refuse
        val settled: Boolean = true,
        val layouts: File = File(engine.parentFile, CustomLayouts.DIR),
    ) {
        val moved get() = listOf(ConfigHome.CONFIG.old to config, ConfigHome.DATA.old to data, ConfigHome.THEME.old to theme)
    }

    // internal for ClipboardManagerReopenTest; made at each use, as this object outlives a test's app
    internal val appDirs
        get() = listOf(ConfigHome.CONFIG, ConfigHome.DATA, ConfigHome.THEME).map(HostConfigDir::inUse).let { (config, data, theme) ->
            Dirs(
                File(appContext.applicationInfo.dataDir, "shared_prefs"),
                File(appContext.applicationInfo.dataDir, "databases"),
                // external storage away: under the cache
                appContext.getExternalFilesDir(null) ?: File(appContext.cacheDir, "external.away"),
                appContext.filesDir.resolve(RecentlyUsed.DIR_NAME),
                appContext.filesDir.resolve("engine"),
                config.dir,
                data.dir,
                theme.dir,
                settled = config.writable && data.writable && theme.writable,
                layouts = CustomLayouts.dir(appContext),
            )
        }

    private val journals = listOf("-journal", "-wal", "-shm")

    /** Without what an app marked sensitive (a password manager's): the backup is a plain zip. */
    private fun clipboardWithoutSensitive(databases: File, dir: File): File? {
        // with its journals: what is not yet written into it is there
        for (suffix in listOf("") + journals) {
            val file = File(databases, CLIPBOARD_DATABASE + suffix)
            file.openIfThere()?.use { input -> File(dir, file.name).outputStream().use { input.copyTo(it) } }
        }
        val copy = File(dir, CLIPBOARD_DATABASE).takeIf { it.isFile } ?: return null
        try {
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // a write-ahead log would keep the entries in a file beside the copy
                db.disableWriteAheadLogging()
                // a throwaway copy: no journal or fsync (each made VACUUM take 90 s at 100 000 entries)
                db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous=OFF")
                db.execSQL("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE sensitive=1")
                // else their text stays in the freed pages
                db.execSQL("VACUUM")
            }
        } catch (e: SQLiteException) {
            // torn while the clipboard wrote: the backup goes without it, not without everything
            Timber.w(e, "Clipboard history left out of the backup: its copy cannot be cleaned")
            return null
        }
        return copy
    }

    /** [learning] (BackupOp.Copy) replaces the engine's own files; null: the files go as they are. */
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis(), learning: ByteArray? = null) =
        export(dest, timestamp, appDirs, learning)

    @OptIn(ExperimentalSerializationApi::class)
    internal fun export(dest: OutputStream, timestamp: Long, dirs: Dirs, learning: ByteArray? = null) = runCatching {
        if (!dirs.settled) errorRuntime(R.string.user_data_not_settled)
        ZipOutputStream(dest.buffered()).use { zipStream ->
            writeFileTree(dirs.sharedPrefs, "shared_prefs", zipStream)
            writeFileTree(dirs.dataBases, "databases", zipStream) {
                it.name.startsWith(CLIPBOARD_DATABASE) || it.name.startsWith(favoritesToMerge(dirs.dataBases).name)
            }
            withTempDir { dir ->
                clipboardWithoutSensitive(dirs.dataBases, dir)?.let { db ->
                    zipStream.putNextEntry(ZipEntry("databases/${db.name}"))
                    db.inputStream().use { it.copyTo(zipStream) }
                }
            }
            // rebuilt after a restore, and as large as the dictionaries
            val compiled = MergedDictionary::isCompiled
            val old = dirs.moved.map { (name, _) -> File(dirs.external, name) }.toSet()
            writeFileTree(dirs.external, "external", zipStream, skipDir = { it in old }, skip = compiled)
            // where every backup has had them, so builds restore each other's; the move's mark left out
            for ((name, dir) in dirs.moved) {
                if (dir.isDirectory) writeFileTree(dir, "external/$name", zipStream) { compiled(it) || it.name == ConfigHome.MOVED }
            }
            // left out: a compacting log, the phone's own backups, and with [learning] its source files and old logs
            if (dirs.engine.isDirectory) {
                // else a restore of the bare record would reread what was forgotten since
                val oldLogsRead = File(dirs.engine, Engines.TABLE_LOGS_READ).exists()
                writeFileTree(dirs.engine, "engine", zipStream) { f ->
                    val path = f.relativeTo(dirs.engine).path
                    f.name.endsWith(".compacting") || path.substringBefore(File.separatorChar) in OWN_DIRS ||
                        (learning != null && (path in LEARNED_FROM || isKeptAside(path))) || ((learning != null || oldLogsRead) && isOldLog(path))
                }
            }
            // not a half-written one; 0.6.0 restores what it knows and leaves this
            if (dirs.layouts.isDirectory) writeFileTree(dirs.layouts, CustomLayouts.DIR, zipStream, skipDir = { true }) { !CustomLayouts.backedUp(it) }
            learning?.let {
                zipStream.putNextEntry(ZipEntry(RestoreStash.BACKUP))
                zipStream.write(it)
            }
            // recently_used moved to SharedPreference and shoud not be exported
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

    // a pack is whole: the backup's replaces the phone's of its name, never merged
    private fun dropRestoredPacks(from: File, themes: File) {
        val packs = ThemePackFiles.folder(themes)
        ThemePackFiles.folder(from).listFiles(FileFilter { it.isDirectory && !it.name.startsWith(".") })
            ?.forEach { File(packs, it.name).deleteRecursively() }
        // a backup's stamps are no check of this app's: every pack rechecked at the next listing
        ThemePackFiles.dropStamps(from)
        ThemePackFiles.dropStamps(themes)
    }

    // this app's pre-0.6.1 backups get 分词 for their packs at the next start
    private fun restoredPacks(name: String, from: File, own: Boolean, versionCode: Long): List<String> {
        if (name != ConfigHome.THEME.old) return emptyList()
        val packs = ThemePackFiles.folder(from).listFiles(FileFilter { it.isDirectory && !it.name.startsWith(".") })
            ?.map { it.name }.orEmpty()
        if (own && versionCode < FIRST_WITH_SEPARATOR) {
            runCatching { ThemeSeparators.restoredBeforeSeparator(packs) }
                .onFailure { Timber.w(it, "Theme packs restored not recorded to be given 分词") }
        }
        return packs
    }

    // listed in 外观 → 布局 at the next start; choices switched where the record has no CURRENT line
    private fun restoredPackLayouts(layouts: File, names: List<String>, own: Boolean) {
        if (!own || names.isEmpty()) return
        val record = CustomLayouts.record(layouts)
        ThemeLayoutsToList.restored(names.filter { it !in record.packNames }, switch = !record.current)
    }

    private fun layOver(name: String, from: File, dir: File) {
        dir.mkdirs()
        if (name == ConfigHome.THEME.old) dropRestoredPacks(from, dir)
        // under the sources lock as every writer, else a conversion could commit a half-copied text
        if (name == ConfigHome.DATA.old) synchronized(ImportedDictionaries) { copyMoved(from, dir) } else copyMoved(from, dir)
    }

    @VisibleForTesting
    internal var copyMoved: (source: File, target: File) -> Unit = { source, target -> copyDir(source, target) }

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
     * Settings and user-edited text files ([FOLLOWED]) follow the backup, so what it lacks is deleted; collections merge.
     * Run after the copy, so a failing import leaves the settings whole.
     */
    private fun pruneAbsent(backupConfig: File, backupData: File, dirs: Dirs) {
        if (!backupConfig.isDirectory) return
        prune(backupConfig, dirs.config)
        for (path in FOLLOWED) prune(File(backupData, path), File(dirs.data, path))
    }

    private fun prune(backup: File, target: File) {
        target.walkBottomUp().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(target).path
            val there = if (relative.isEmpty()) backup else File(backup, relative)
            if (relative.split(File.separatorChar).none { it.startsWith(".") } && !there.exists()) file.delete()
        }
    }

    // the clipboard's history is the backup's, its favourites both; one not kept fails the import before anything is replaced
    private fun keepPhonesFavorites(backup: File, dirs: Dirs) {
        // only this import's file is merged, never one the backup brings
        favoritesToMerge(backup).deleteRecursively()
        File(backup, "${favoritesToMerge(backup).name}.tmp").deleteRecursively()
        if (File(backup, CLIPBOARD_DATABASE).exists()) keepFavoritesToMerge(dirs.dataBases)
    }

    fun import(src: InputStream) = import(src, appDirs)

    // zip bomb and entry-count guards: the end record refuses too many entries first, the unpack counts again as it may lie.
    // Only a zip (head checked), at most [maxBytes] and half the free room, as unpacking takes as much again
    @VisibleForTesting
    internal fun extractWithinRoom(
        src: InputStream,
        tempDir: File,
        room: Long = tempDir.usableSpace,
        maxEntries: Int = MAX_BACKUP_ENTRIES,
        maxBytes: Long = MAX_BACKUP_BYTES,
    ): List<File> {
        val copy = File(tempDir.parentFile, "${tempDir.name}$COPY_SUFFIX")
        try {
            dropStaleCopies(tempDir.parentFile)
            val copied = src.use { input ->
                val head = ByteArray(ZIP_HEAD.size).let { it.copyOf(readUpTo(input, it)) }
                if (!head.contentEquals(ZIP_HEAD) && !head.contentEquals(EMPTY_ZIP_HEAD)) errorRuntime(R.string.exception_user_data_not_a_backup)
                copy.outputStream().use { out ->
                    out.write(head)
                    copyWithin(input, out, head.size.toLong(), maxBytes, room / 2)
                }
            }
            val entries = ZipEnd.entries(copy) ?: errorRuntime(R.string.exception_user_data_not_a_backup)
            if (entries > maxEntries) errorRuntime(R.string.exception_user_data_too_many_entries)
            return ZipInputStream(copy.inputStream()).use {
                it.extract(tempDir) { count, size, total ->
                    if (count > maxEntries) errorRuntime(R.string.exception_user_data_too_many_entries)
                    if (copied + total + size > room) errorRuntime(R.string.exception_user_data_no_room)
                }
            }
        } finally {
            copy.delete()
        }
    }

    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val read = input.read(into, n, into.size - n)
            if (read < 0) break
            n += read
        }
        return n
    }

    // stops at the chunk past [maxBytes] or [room], unwritten
    private fun copyWithin(input: InputStream, out: OutputStream, from: Long, maxBytes: Long, room: Long): Long {
        var size = from
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return size
            size += n
            if (size > maxBytes) errorRuntime(R.string.exception_user_data_too_big)
            if (size > room) errorRuntime(R.string.exception_user_data_no_room)
            out.write(buffer, 0, n)
        }
    }

    // copies of restores killed midway, an hour old
    private fun dropStaleCopies(dir: File?) {
        val before = System.currentTimeMillis() - STALE_COPY_MS
        dir?.listFiles { f -> f.isFile && f.name.endsWith(COPY_SUFFIX) && f.lastModified() < before }?.forEach { it.delete() }
    }

    // a zip's first local header; an empty zip's whole end record
    private val ZIP_HEAD = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val EMPTY_ZIP_HEAD = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    private const val COPY_SUFFIX = ".restore.zip"
    private const val STALE_COPY_MS = 60 * 60 * 1000L

    private const val MAX_BACKUP_BYTES = 2L shl 30

    // a phone's backup is a few thousand files (mostly pack images)
    private const val MAX_BACKUP_ENTRIES = 100_000

    internal fun import(src: InputStream, dirs: Dirs) = runCatching {
        if (!dirs.settled) errorRuntime(R.string.user_data_not_settled)
        // the start's pack listing ended first; its switch is dropped below once the backup passed its checks
        ThemeLayoutsToList.awaitStart(restoring = true)
        withTempDir { tempDir ->
            // closed before anything is copied: nothing may fail the import once the engine's is in
            val extracted = extractWithinRoom(src, tempDir)
            val metadataFile = extracted.find { it.name == "metadata.json" }
                ?: errorRuntime(R.string.exception_user_data_metadata)
            val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
            val origin = UserDataOrigin.of(
                metadata.packageName, BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID_ROOT
            ) ?: errorRuntime(R.string.exception_user_data_package_name_mismatch)
            // checked before anything is copied, so a refusal leaves the phone as it was
            val tooOld = origin == UserDataOrigin.Own && metadata.versionCode < FIRST_WITH_THE_RECORD
            if (tooOld || (origin is UserDataOrigin.Legacy && isBeforeTheRecord(tempDir))) errorRuntime(R.string.exception_user_data_too_old)
            if (origin is UserDataOrigin.Legacy) {
                val prefs = File(tempDir, "shared_prefs")
                val legacy = File(prefs, origin.preferences)
                // else copied under a name this app does not read: every setting at its default
                if (legacy.exists() && !legacy.renameTo(File(prefs, origin.renamed))) {
                    throw IOException("cannot rename ${legacy.name}")
                }
            }
            val databases = File(tempDir, "databases")
            keepPhonesFavorites(databases, dirs)
            // reset at the next start, not here, where AppPrefs would write its copy over the imported one
            importedUserDataMarker(appContext).createNewFile()
            // merged at the next start; the phone's kept for 退回恢复前
            keepAddressBook(tempDir)
            // made after the copy it would write the phone's over the backup's; dropped only after the checks
            ThemeLayoutsToList.dropStart()
            copyDir(File(tempDir, "shared_prefs"), dirs.sharedPrefs)
            // only this import's copy marks the clipboard file the backup's, not a marker the backup brings
            importedClipboardMarker(databases).deleteRecursively()
            copyDir(databases, dirs.dataBases)
            // a replaced database's journal would be played over the new one
            databases.list()?.forEach { name ->
                journals.map { name + it }.filterNot { File(databases, it).exists() }.forEach { File(dirs.dataBases, it).delete() }
            }
            // the next open may start over what will not pass; without the marker the file is kept as the user's
            if (File(databases, CLIPBOARD_DATABASE).exists()) {
                runCatching { importedClipboardMarker(dirs.dataBases).createNewFile() }
                    .onFailure { Timber.w(it, "Clipboard database import not marked: its next open keeps it as the user's") }
            }
            // settled by appDirs first so a restore never bypasses the move
            val external = File(tempDir, "external")
            var packs = emptyList<String>()
            for ((name, dir) in dirs.moved) {
                val from = File(tempDir, "external-$name")
                if (File(external, name).renameTo(from)) {
                    layOver(name, from, dir)
                    packs += restoredPacks(name, from, origin == UserDataOrigin.Own, metadata.versionCode)
                }
            }
            if (external.isDirectory) copyDir(external, dirs.external)
            // fcitx writes only what is set, so a config the backup lacks was at defaults: the phone's is dropped
            pruneAbsent(File(tempDir, "external-${ConfigHome.CONFIG.old}"), File(tempDir, "external-${ConfigHome.DATA.old}"), dirs)
            // a restored punctuation table is unused; AppPrefs clears its mark next start, as writing here would be overwritten
            if (!PunctuationMaps.dropEdited(dirs.data)) Timber.w("a restored punctuation table not deleted: tried again at the next start")
            // keep importing recently_used for backwards compatibility: an upstream Fcitx5 backup's recent symbols are still taken
            copyDir(File(tempDir, "recently_used"), dirs.recentlyUsed)
            // best effort: failing here would leave settings and themes the backup's and the engine's not
            runCatching { CustomLayouts.restore(File(tempDir, CustomLayouts.DIR), dirs.layouts, ThemePackFiles.minAppVersion(BuildConfig.VERSION_NAME)) }
                .onFailure { Timber.w(it, "layouts not restored") }
            runCatching { restoredPackLayouts(File(tempDir, CustomLayouts.DIR), packs, origin == UserDataOrigin.Own) }
                .onFailure { Timber.w(it, "theme packs restored not recorded to have their layouts listed") }
            // last: an import failing after it would restart fcitx on the logs it replaced
            val learning = File(tempDir, RestoreStash.BACKUP).takeIf { it.isFile }
            if (File(tempDir, "engine").exists() || learning != null) {
                val source = File(tempDir, "engine")
                source.mkdirs()
                importEngine(source, dirs.engine, learning)
            }
            metadata
        }
    }

    // the phone's address entries are merged into the zip's learning; an unparseable file is left as is
    private fun keepAddressBook(tempDir: File) {
        val staged = File(tempDir, "shared_prefs/${UserDataOrigin.preferencesFile(appContext.packageName)}")
        if (!File(tempDir, RestoreStash.BACKUP).isFile || !staged.isFile) return
        val internal = AppPrefs.getInstance().internal
        val phone = listOf(internal.addressCounts, internal.rememberedAddresses).associate { it.key to it.getValue() }
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                // a prefs file has no DOCTYPE: refused where the parser knows the feature
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
     * One rename, so a failure leaves the engine's directory whole. The learning is merged, not laid over: the backup's
     * goes to [RestoreStash.DIR] for the engine to merge at its next start.
     */
    private fun importEngine(source: File, engine: File, learning: File?) {
        if (!source.isDirectory) {
            Timber.w("Cannot import user data: path='${source.path}' is not a directory")
            return
        }
        replaceDirectory(engine, File(engine.path + ".import")) { staged ->
            // moved, only missing files copied: a full phone may have no room for two copies
            if (!source.renameTo(staged)) source.copyRecursively(staged)
            stash(staged, learning, engine)
            engine.walkTopDown()
                .onEnter { !File(staged, it.toRelativeString(engine)).isFile }
                // half-written by a compaction stopped with fcitx
                .filterNot { it.name.endsWith(".compacting") }
                .forEach {
                    val path = it.toRelativeString(engine)
                    val target = File(staged, path)
                    val phonesOwn = path in PHONES || isKeptAside(path) || path.substringBefore(File.separatorChar) in OWN_DIRS || isOldLog(path)
                    if (it.isDirectory) target.mkdirs() else if (phonesOwn || !target.exists()) it.copyTo(target, overwrite = true)
                }
        }
    }

    private fun stash(staged: File, learning: File?, engine: File) {
        OWN_DIRS.forEach { File(staged, it).deleteRecursively() }
        val waiting = File(engine, RestoreStash.DIR).list().orEmpty().mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L
        val stash = File(staged, RestoreStash.DIR + File.separator + maxOf(System.currentTimeMillis(), waiting + 1))
        stash.mkdirs()
        learning?.copyTo(File(stash, RestoreStash.BACKUP))
        val stashed = STASHED + staged.list().orEmpty().filter(::isOldLog)
        for (name in stashed) File(staged, name).takeIf { it.isFile }?.let { it.copyTo(File(stash, name)); it.delete() }
        PHONES.forEach { File(staged, it).deleteRecursively() }
        staged.list().orEmpty().filter(::isKeptAside).forEach { File(staged, it).deleteRecursively() }
        if (stash.list().isNullOrEmpty()) stash.parentFile?.let { if (stash.delete()) it.delete() }
    }

    // 0.3.0 or before: learning in old logs alone, which only 0.4 converted
    private fun isBeforeTheRecord(zip: File): Boolean {
        val engine = File(zip, "engine")
        if (File(zip, RestoreStash.BACKUP).isFile || File(engine, LearnLog.FILE).isFile) return false
        return engine.list().orEmpty().any(::isOldLog)
    }

    // 0.3.1's lowest-ABI code (Versions.calculateVersionCode: release × 10 + ABI)
    private const val FIRST_WITH_THE_RECORD = 2130L

    // 0.6.1's code, an ABI's (5130 + 1-4): the first whose packs were given 分词
    @VisibleForTesting
    internal const val FIRST_WITH_SEPARATOR = 5130L

    // includes 0.4's mark beside a table's
    private fun isOldLog(path: String) = File.separatorChar !in path && (path.endsWith(".user") || path.endsWith(".user.told"))

    private const val OLD_MARKER = "learn.converted"
    private const val OLD_PINS_MOVED = "learn.pins-moved"
    private const val OLD_PINYIN = "pinyin.user"

    // .unreadable, .unreadable.1... (the engine's rule)
    private fun isKeptAside(path: String) = File.separatorChar !in path && RecordStore.isAside(LearnLog.FILE, path)

    // kept over a backup's, which is merged into them
    private val PHONES = setOf(
        LearnLog.FILE, OLD_MARKER, "$OLD_MARKER.new",
        OLD_PINS_MOVED, WordLists.ADDED, WordLists.BLOCKED, WordLists.ALWAYS, EnglishBlocks.FILE, OLD_PINYIN, Engines.TABLE_LOGS_READ,
    )

    // merged by the engine at its next start, with the mark that its old logs are read
    private val STASHED = listOf(LearnLog.FILE, WordLists.ADDED, WordLists.BLOCKED, WordLists.ALWAYS, EnglishBlocks.FILE, Engines.TABLE_LOGS_READ)

    // custom phrases, 常用词, 生僻字屏蔽, quick phrases; punctuation maps are dropped whole
    private val FOLLOWED = listOf("pinyin/customphrase", CommonWordsManager.FILE, RareCharsManager.FILE, "data/quickphrase.d")

    // the phone's alone, neither exported nor imported
    private val OWN_DIRS = setOf(Backups.DIR, RestoreStash.DIR, RestoreStash.UNREADABLE)

    private val LEARNED_FROM = setOf(LearnLog.FILE, OLD_MARKER, OLD_PINS_MOVED, Engines.TABLE_LOGS_READ)
}

/** Outside the object, which the app's start must not make. */
fun importedUserDataMarker(context: Context) = File(context.noBackupFilesDir, "user_data_imported")