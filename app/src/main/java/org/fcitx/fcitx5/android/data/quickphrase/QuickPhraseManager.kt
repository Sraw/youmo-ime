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
import org.fcitx.fcitx5.android.host.QuickPhraseTable
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException

/**
 * The user's quick phrases, in fcitx's data where the host reads them and backups take them
 * (`data/quickphrase.d`): `mine.mb` (the entries), `mine.used` (when each was used, added or
 * changed last) and a `<table>.disable` for each built-in table turned off.
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

    private fun readMine(dir: File): List<QuickPhraseEntry> =
        File(dir, QuickPhraseBook.MINE).takeIf { it.isFile }?.readBytes()?.let(QuickPhraseFiles::parseMine).orEmpty()

    private fun readUsed(dir: File): Map<QuickPhraseEntry, Long> =
        File(dir, QuickPhraseBook.USED).takeIf { it.isFile }?.readText()?.let(QuickPhraseFiles::parseUsed).orEmpty()

    /**
     * The user's entries, the last used, added or changed first, as the keyboard lists them.
     * @throws IOException if a file there cannot be read
     */
    fun load(dir: File = dir()): List<QuickPhraseEntry> = QuickPhraseBook(QuickPhraseTable.EMPTY, readMine(dir), readUsed(dir)).mine

    /**
     * [new] in place of [old] (null: added at the end), first in the order of use from [now]. Another
     * already there with the same key, case aside, and content is refused ([Put.Duplicate],
     * [QuickPhraseEntry.sameAs]); [old] gone meanwhile (deleted from the keyboard), [new] is added.
     * @throws IOException if the files cannot be read or written
     */
    @Synchronized
    fun put(old: QuickPhraseEntry?, new: QuickPhraseEntry, now: Long = System.currentTimeMillis(), dir: File? = null): Put {
        val target = dir ?: dirToSave()
        val mine = readMine(target)
        if (mine.any { it != old && it.sameAs(new) }) return Put.Duplicate
        val at = old?.let(mine::indexOf) ?: -1
        val out = if (at >= 0) mine.toMutableList().also { it[at] = new } else mine + new
        val used = readUsed(target) + (new to now)
        write(target, out, used)
        return Put.Saved
    }

    /** [entry] deleted, if it is there. @throws IOException if the files cannot be read or written */
    @Synchronized
    fun remove(entry: QuickPhraseEntry, dir: File? = null) {
        val target = dir ?: dirToSave()
        val mine = readMine(target)
        if (entry !in mine) return
        write(target, mine - entry, readUsed(target))
    }

    /** [entry] used at [at] (the keyboard's): only `mine.used` is written. @throws IOException as [put] */
    @Synchronized
    fun markUsed(entry: QuickPhraseEntry, at: Long, dir: File? = null) {
        val target = dir ?: dirToSave()
        val mine = readMine(target)
        if (entry !in mine) return
        writeUsed(target, readUsed(target) + (entry to at), mine)
    }

    /** What an [import] did: [added] entries, and the lines skipped as already there or too long. */
    data class Imported(val added: Int, val duplicates: Int, val tooLong: Int)

    /**
     * The entries of a `.mb` file's [bytes] added after the user's ([QuickPhraseFiles.merge]): those
     * already there, too long or with no good key skipped.
     * @throws IOException as [put]
     */
    @Synchronized
    fun import(bytes: ByteArray, dir: File? = null): Imported {
        val target = dir ?: dirToSave()
        val mine = readMine(target)
        val merged = QuickPhraseFiles.merge(mine, QuickPhraseFiles.parseLines(bytes))
        if (merged.entries.size != mine.size) write(target, merged.entries, readUsed(target))
        return Imported(merged.entries.size - mine.size, merged.duplicates, merged.tooLong)
    }

    /** The user's entries as a `.mb` file, in the order of use, the one fcitx5 on a computer reads too. */
    fun export(dir: File = dir()): String = QuickPhraseFiles.formatMine(load(dir))

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

    private fun write(dir: File, mine: List<QuickPhraseEntry>, used: Map<QuickPhraseEntry, Long>) {
        dir.mkdirs()
        val file = File(dir, QuickPhraseBook.MINE)
        replaceFile(file, File(file.path + ".new")) { it.writeText(QuickPhraseFiles.formatMine(mine)) }
        writeUsed(dir, used, mine)
    }

    // the times of the entries there alone: one changed or deleted leaves no line behind
    private fun writeUsed(dir: File, used: Map<QuickPhraseEntry, Long>, mine: List<QuickPhraseEntry>) {
        dir.mkdirs()
        val file = File(dir, QuickPhraseBook.USED)
        replaceFile(file, File(file.path + ".new")) { it.writeText(QuickPhraseFiles.formatUsed(used, mine)) }
    }
}
