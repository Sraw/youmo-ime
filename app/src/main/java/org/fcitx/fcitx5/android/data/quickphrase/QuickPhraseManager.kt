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

/** Each change rereads and rewrites the files whole, so the page and the keyboard never lose each other's changes. */
object QuickPhraseManager {

    val BUILT_IN = listOf("emoji.mb", "emoji-eac.mb", "latex.mb")

    private const val DISABLE = ".disable"

    enum class Put { Saved, Duplicate }

    private fun dir(): File = File(HostConfigDir.inUse(ConfigHome.DATA).dir, QuickPhraseBook.DIR)

    // none while fcitx's data is not settled: saved then, it would be lost at the next start
    private fun dirToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return File(home.dir, QuickPhraseBook.DIR)
    }

    private fun readAll(dir: File): QuickPhraseMine =
        File(dir, QuickPhraseBook.MINE).takeIf { it.isFile }?.readBytes()?.let(QuickPhraseFiles::readMine) ?: QuickPhraseMine.EMPTY

    private fun readMine(dir: File): List<QuickPhraseEntry> = readAll(dir).entries

    // folder aside: the keyboard's entry, from a read before the page moved it, still finds it
    private fun List<QuickPhraseEntry>.indexOfEntry(entry: QuickPhraseEntry) = indexOfFirst { it.unfiled == entry.unfiled }

    private fun readUsed(dir: File): Map<QuickPhraseEntry, Long> =
        File(dir, QuickPhraseBook.USED).takeIf { it.isFile }?.readText()?.let(QuickPhraseFiles::parseUsed).orEmpty()

    // synchronized as the writes are: one between the two reads would pair the new list with old times
    @Synchronized
    fun load(dir: File = dir()): List<QuickPhraseEntry> = loadAll(dir).entries

    @Synchronized
    fun loadAll(dir: File = dir()): QuickPhraseMine {
        val all = readAll(dir)
        return QuickPhraseMine(QuickPhraseBook(QuickPhraseTable.EMPTY, all.entries, readUsed(dir)).mine, all.folders)
    }

    /** A duplicate is refused ([Put.Duplicate]); if [old] is gone meanwhile (deleted from the keyboard), [new] is added. */
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

    class Removed(val entry: QuickPhraseEntry, val index: Int, val usedAt: Long?)

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

    /** One the same added meanwhile is kept ([Put.Duplicate]). */
    @Synchronized
    fun restore(removed: Removed, dir: File? = null): Put {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        val mine = all.entries
        if (mine.any { it.sameAs(removed.entry) }) return Put.Duplicate
        val out = mine.toMutableList().also { it.add(removed.index.coerceIn(0, it.size), removed.entry) }
        val used = readUsed(target)
        // its folder deleted meanwhile: made again
        write(target, out, removed.usedAt?.let { used + (removed.entry.unfiled to it) } ?: used, all.folders)
        return Put.Saved
    }

    @Synchronized
    fun markUsed(entry: QuickPhraseEntry, at: Long, dir: File? = null) {
        val target = dir ?: dirToSave()
        val mine = readMine(target)
        if (mine.indexOfEntry(entry) < 0) return
        writeUsed(target, readUsed(target) + (entry.unfiled to at), mine)
    }

    @Synchronized
    fun addFolder(name: String, dir: File? = null): Boolean {
        require(QuickPhraseFiles.validFolder(name)) { "no folder name: $name" }
        val target = dir ?: dirToSave()
        val all = readAll(target)
        if (name in all.folders) return false
        write(target, all.entries, readUsed(target), all.folders + name)
        return true
    }

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

    /** Its entries are kept out of any folder; returns how many, or -1 if absent. */
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

    /** [keyless]: added with an all-Chinese key, now their note. */
    data class Imported(val added: Int, val duplicates: Int, val tooLong: Int, val badKey: Int = 0, val keyless: Int = 0, val overLimit: Int = 0)

    /** Each use and change rewrites the whole file, which a million short lines (8 MB) would make seconds a tap. */
    const val MAX_ENTRIES = 5000

    @Synchronized
    fun import(bytes: ByteArray, dir: File? = null): Imported {
        val target = dir ?: dirToSave()
        val all = readAll(target)
        val mine = all.entries
        val file = QuickPhraseFiles.parse(bytes)
        val merged = QuickPhraseFiles.merge(mine, file.entries, all.folders, file.folders)
        // a list already past the limit (older) is kept whole, nothing added
        val entries = merged.entries.take(maxOf(MAX_ENTRIES, mine.size))
        val added = entries.subList(mine.size, entries.size)
        if (entries.size != mine.size || merged.folders != all.folders) write(target, entries, readUsed(target), merged.folders)
        return Imported(
            added.size, merged.duplicates, merged.tooLong, merged.badKey, added.count { it.keyless }, merged.entries.size - entries.size,
        )
    }

    /** Desktop fcitx5 reads it too, a note after its key (`#wz｜百度`). */
    fun export(dir: File = dir()): String = loadAll(dir).let { QuickPhraseFiles.formatMine(it.entries, it.folders) }

    fun builtInOn(name: String, dir: File = dir()): Boolean = !File(dir, name + DISABLE).exists()

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

    // a changed or deleted one leaves no line
    private fun writeUsed(dir: File, used: Map<QuickPhraseEntry, Long>, mine: List<QuickPhraseEntry>) {
        dir.mkdirs()
        val file = File(dir, QuickPhraseBook.USED)
        replaceFile(file, File(file.path + ".new")) { it.writeText(QuickPhraseFiles.formatUsed(used, mine)) }
    }
}
