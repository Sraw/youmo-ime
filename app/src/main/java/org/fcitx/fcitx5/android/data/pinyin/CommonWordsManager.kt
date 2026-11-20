/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.InUse
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException

/** Both the settings page and the keyboard write the file, each merging its changes over the current file ([saveOver]). */
object CommonWordsManager {

    const val FILE = "pinyin/commonwords.txt"

    class Newer : IOException("common words: written by a later version, not saved over")

    private fun file() = file(HostConfigDir.inUse(ConfigHome.DATA))

    private fun file(home: InUse) = File(home.dir, FILE)

    // none while fcitx's data is not settled: what was saved then would be lost at the next start
    private fun fileToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return file(home)
    }

    /** Waits for a [saveOver] under way, so a recreated page reads the last save. */
    @Synchronized
    fun load(from: File = file()): CommonWords = if (from.isFile) CommonWords.parse(from.readText()).words else CommonWords.EMPTY

    /** Returns what the file then has: the next save's base. */
    @Synchronized
    fun saveOver(base: CommonWords, mine: CommonWords, to: File? = null): CommonWords {
        val target = to ?: fileToSave()
        val theirs = load(target)
        // this build reads none of it, and would write it all away
        if (theirs.newer) throw Newer()
        val merged = CommonWords.merged(base, mine, theirs)
        write(merged, target)
        return merged
    }

    /** Whole or not at all. */
    @Synchronized
    fun write(words: CommonWords, to: File? = null) {
        val target = to ?: fileToSave()
        target.parentFile?.mkdirs()
        replaceFile(target, File(target.path + ".new")) { it.writeText(words.format()) }
    }
}
