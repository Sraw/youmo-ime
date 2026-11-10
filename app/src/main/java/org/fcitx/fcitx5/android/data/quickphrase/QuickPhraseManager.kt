/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.fcitx.fcitx5.android.host.QuickPhraseMine
import org.fcitx.fcitx5.android.host.QuickPhraseTable
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException

/**
 * The user's quick phrases, in fcitx's data where the host reads them and backups take them
 * (`data/quickphrase.d`): `mine.mb` (the entries and their folders), `mine.used` (when each was used,
 * added or changed last) and a `<table>.disable` for each built-in table turned off. Their format is
 * [QuickPhraseFiles]'; a file of before (an upgrade, an old backup restored) is read as it was meant
 * and written anew at the next change.
 *
 * Every change reads the files, makes its one change and writes them whole: the settings page and
 * the keyboard (an entry used, or deleted from its card) both write, one at a time, and neither
 * loses what the other did meanwhile.
 */
object QuickPhraseManager {

    /** The built-in tables the user may turn off, as the settings page lists them. */
    val BUILT_IN = listOf("emoji.mb", "emoji-eac.mb", "latex.mb")

    private const val DISABLE = ".disable"

    /** What a [put] did. */
    enum class Put { Saved, Duplicate }

    private fun dir(): File = File(HostConfigDir.inUse(ConfigHome.DATA).dir, QuickPhraseBook.DIR)

    // the directory to save to: none while fcitx's data is not settled, what was saved then being lost at the next start
    private fun dirToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return File(home.dir, QuickPhraseBook.DIR)
    }

    private fun readAll(dir: File): QuickPhraseMine =
        File(dir, QuickPhraseBook.MINE).takeIf { it.isFile }?.readBytes()?.let(QuickPhraseFiles::readMine) ?: QuickPhraseMine.EMPTY

    private fun readMine(dir: File): List<QuickPhraseEntry> = readAll(dir).entries

    // where [entry] is, its folder aside: the keyboard's, of a read before the page moved it, still finds it
    private fun List<QuickPhraseEntry>.indexOfEntry(entry: QuickPhraseEntry) = indexOfFirst { it.unfiled == entry.unfiled }

    private fun readUsed(dir: File): Map<QuickPhraseEntry, Long> =
        File(dir, QuickPhraseBook.USED).takeIf { it.isFile }?.readText()?.let(QuickPhraseFiles::parseUsed).orEmpty()

    /**
     * The user's entries, the last used, added or changed first, as the keyboard lists them.
     * @throws IOException if a file there cannot be read
     */
    // as the writes are: a write between the two files read would pair the new list with the old times
    @Synchronized
    fun load(dir: File = dir()): List<QuickPhraseEntry> = loadAll(dir).entries

    /** As [load], with the folders, in their order. @throws IOException as [load] */
    @Synchronized
    fun loadAll(dir: File = dir()): QuickPhraseMine {
        val all = readAll(dir)
        return QuickPhraseMine(QuickPhraseBook(QuickPhraseTable.EMPTY, all.entries, readUsed(dir)).mine, all.folders)
    }

    /**
     * [new] in place of [old] (null: added at the end of its folder), first in the order of use from
     * [now]. Another already there with the same key, case aside, note and content is refused
     * ([Put.Duplicate], [QuickPhraseEntry.sameAs]); [old] gone meanwhile (deleted from the keyboard),
     * [new] is added. Its folder is made if it is not there.
     * @throws IOException if the files cannot be read or written
     */
    @Synchronized
    fun put(old: QuickPhraseEntry?, new: QuickPhraseEntry, now: Long = System.currentTimeMillis(), dir: File? = null): Put {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        val mine = all.entries
        val at = old?.let { mine.indexOfEntry(it) } ?: -1
        if (mine.withIndex().any { (i, e) -> i != at && e.sameAs(new) }) return Put.Duplicate
        val out = if (at >= 0) mine.toMutableList().also { it[at] = new } else mine + new
        val used = readUsed(target) + (new.unfiled to now)
        write(target, out, used, all.folders)
        return Put.Saved
    }

    /** What [remove] took away: [entry], where it was in the file, when it was last used (null: never). */
    class Removed(val entry: QuickPhraseEntry, val index: Int, val usedAt: Long?)

    /**
     * [entry] deleted, if it is there; what was taken away, for [restore], or null if it was not there.
     * @throws IOException if the files cannot be read or written
     */
    @Synchronized
    fun remove(entry: QuickPhraseEntry, dir: File? = null): Removed? {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        val mine = all.entries
        val index = mine.indexOfEntry(entry)
        if (index < 0) return null
        val used = readUsed(target)
        val there = mine[index]
        write(target, mine.filterIndexed { i, _ -> i != index }, used, all.folders)
        return Removed(there, index, used[there.unfiled])
    }

    /**
     * 撤销 of a deletion: [removed] put back where it was in the file, with its time of use, so it is
     * listed where it was. One the same added meanwhile is kept as it is ([Put.Duplicate]).
     * @throws IOException as [put]
     */
    @Synchronized
    fun restore(removed: Removed, dir: File? = null): Put {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        val mine = all.entries
        if (mine.any { it.sameAs(removed.entry) }) return Put.Duplicate
        val out = mine.toMutableList().also { it.add(removed.index.coerceIn(0, it.size), removed.entry) }
        val used = readUsed(target)
        // its folder deleted meanwhile: made again, as it was where it was
        write(target, out, removed.usedAt?.let { used + (removed.entry.unfiled to it) } ?: used, all.folders)
        return Put.Saved
    }

    /** [entry] used at [at] (the keyboard's): only `mine.used` is written. @throws IOException as [put] */
    @Synchronized
    fun markUsed(entry: QuickPhraseEntry, at: Long, dir: File? = null) {
        val target = dir ?: dirToSave()
        val mine = readMine(target)
        if (mine.indexOfEntry(entry) < 0) return
        writeUsed(target, readUsed(target) + (entry.unfiled to at), mine)
    }

    /**
     * A folder [name] made, after the others ([QuickPhraseFiles.validFolder]); false if there is one
     * of that name. @throws IOException as [put]
     */
    @Synchronized
    fun addFolder(name: String, dir: File? = null): Boolean {
        require(QuickPhraseFiles.validFolder(name)) { "no folder name: $name" }
        val target = dir ?: dirToSave()
        val all = readAll(target)
        if (name in all.folders) return false
        write(target, all.entries, readUsed(target), all.folders + name)
        return true
    }

    /**
     * The folder [old] named [new], its entries with it; false if [old] is gone or [new] is another's.
     * @throws IOException as [put]
     */
    @Synchronized
    fun renameFolder(old: String, new: String, dir: File? = null): Boolean {
        require(QuickPhraseFiles.validFolder(new)) { "no folder name: $new" }
        val target = dir ?: dirToSave()
        val all = readAll(target)
        if (old !in all.folders || (new != old && new in all.folders)) return false
        val entries = all.entries.map { if (it.folder == old) it.copy(folder = new) else it }
        write(target, entries, readUsed(target), all.folders.map { if (it == old) new else it })
        return true
    }

    /**
     * The folder [name] deleted, its entries kept out of any folder (none lost to a slip); how many
     * they were, or -1 if it was not there. @throws IOException as [put]
     */
    @Synchronized
    fun deleteFolder(name: String, dir: File? = null): Int {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        if (name !in all.folders) return -1
        val moved = all.entries.count { it.folder == name }
        val entries = all.entries.map { if (it.folder == name) it.copy(folder = "") else it }
        write(target, entries, readUsed(target), all.folders - name)
        return moved
    }

    /**
     * What an [import] did: [added] entries ([keyless] of them with a key Chinese from its start, now
     * their note), and the lines skipped as already there, too long, or with a key or note that cannot
     * be one ([badKey], [QuickPhraseFiles.validKey]).
     */
    data class Imported(val added: Int, val duplicates: Int, val tooLong: Int, val badKey: Int = 0, val keyless: Int = 0)

    /**
     * The entries of a `.mb` file's [bytes] added after the user's ([QuickPhraseFiles.merge]), into
     * the file's folders (one of the same name the same): those already there, too long or with no
     * good key skipped. A file of before, or a computer's, read as the files of before are.
     * @throws IOException as [put]
     */
    @Synchronized
    fun import(bytes: ByteArray, dir: File? = null): Imported {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        val mine = all.entries
        val file = QuickPhraseFiles.parse(bytes)
        val merged = QuickPhraseFiles.merge(mine, file.entries, all.folders, file.folders)
        if (merged.entries.size != mine.size || merged.folders != all.folders) write(target, merged.entries, readUsed(target), merged.folders)
        return Imported(merged.entries.size - mine.size, merged.duplicates, merged.tooLong, merged.badKey, merged.keyless)
    }

    /**
     * The user's entries as a `.mb` file, its folders kept, in the order of use: fcitx5 on a computer
     * reads it too, a note after its key (`#wz｜百度`).
     */
    fun export(dir: File = dir()): String = loadAll(dir).let { QuickPhraseFiles.formatMine(it.entries, it.folders) }

    /** Whether the built-in table [name] ([BUILT_IN]) is on. */
    fun builtInOn(name: String, dir: File = dir()): Boolean = !File(dir, name + DISABLE).exists()

    /** Turns the built-in table [name] on or off. @throws IOException if it cannot be */
    @Synchronized
    fun setBuiltIn(name: String, on: Boolean, dir: File? = null) {
        require(name in BUILT_IN) { "no built-in table $name" }
        val target = dir ?: dirToSave()
        val file = File(target, name + DISABLE)
        if (on) {
            if (file.exists() && !file.delete()) throw IOException("cannot delete $file")
        } else {
            target.mkdirs()
            if (!file.exists() && !file.createNewFile()) throw IOException("cannot create $file")
        }
    }

    private fun write(dir: File, mine: List<QuickPhraseEntry>, used: Map<QuickPhraseEntry, Long>, folders: List<String>) {
        dir.mkdirs()
        val file = File(dir, QuickPhraseBook.MINE)
        replaceFile(file, File(file.path + ".new")) { it.writeText(QuickPhraseFiles.formatMine(mine, folders)) }
        writeUsed(dir, used, mine)
    }

    // the times of the entries there alone: one changed or deleted leaves no line behind
    private fun writeUsed(dir: File, used: Map<QuickPhraseEntry, Long>, mine: List<QuickPhraseEntry>) {
        dir.mkdirs()
        val file = File(dir, QuickPhraseBook.USED)
        replaceFile(file, File(file.path + ".new")) { it.writeText(QuickPhraseFiles.formatUsed(used, mine)) }
    }
}
