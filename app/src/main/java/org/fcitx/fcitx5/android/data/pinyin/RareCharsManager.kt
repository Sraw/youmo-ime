/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.InUse
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile
import org.fcitx.fcitx5.android.engine.user.CharGate
import org.fcitx.fcitx5.android.engine.user.RareChoice
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 生僻字屏蔽's file (dev/design/061-rare-char-block.md §4.2), beside 常用词' in fcitx's pinyin data, where
 * the engine's additions read it ([RareChoice]'s text). Two kinds of writer, the grid page and the
 * user words' 「放行」, each hand in their changes, laid over the file as it is then ([update]): one
 * after the other, neither writes the other's away.
 */
object RareCharsManager {

    const val FILE = "pinyin/rarechars.txt"

    /** The file is a later version's ([RareChoice.newer]), left as it is. */
    class Newer : IOException("rare characters: written by a later version, not saved over")

    private fun file() = file(HostConfigDir.inUse(ConfigHome.DATA))

    private fun file(home: InUse) = File(home.dir, FILE)

    // none while fcitx's data is not settled: what was saved then would be lost at the next start
    private fun fileToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return file(home)
    }

    /**
     * The levels' table the APK has (not a pack's): what the choice is over; null, and logged, where
     * it cannot be read, 生僻字屏蔽 then off as the engine has it.
     */
    val levels: CharLevels? get() = read.value

    private var read = lazy {
        try {
            CharLevels.load(AppData.map(InputMethodCatalog.CHAR_LEVELS_DATA), verify = false)
        } catch (e: IOException) {
            Timber.w(e, "rare characters: no levels")
            null
        } catch (e: DataFormatException) {
            Timber.w(e, "rare characters: no levels")
            null
        } catch (e: IllegalArgumentException) {
            // what makes no sense past the header (a bad bit width or length): as EngineAssets.readOr
            Timber.w(e, "rare characters: no levels")
            null
        }
    }

    // the JVM tests have no APK assets: a test's table instead
    @VisibleForTesting
    internal fun useLevels(levels: CharLevels?) {
        read = lazyOf(levels)
    }

    /**
     * The choice the file has; off if there is none. One [update] under way is waited for.
     * @throws IOException if there is one that cannot be read
     */
    @Synchronized
    fun load(from: File = file()): RareChoice = if (from.isFile) RareChoice.parse(from.readText()) else RareChoice.NONE

    /** What the choice holds back, for the user words' pages to mark (§5.4): none if it cannot be read. */
    fun gate(levels: CharLevels? = this.levels): CharGate = try {
        load().gate(levels)
    } catch (e: IOException) {
        Timber.w(e, "rare characters: not read, none marked")
        CharGate.NONE
    }

    /**
     * Lets [chars] through, one by one (a user word's 「放行」, §5.4), laid over the file as it is.
     * @throws IOException as [update]
     */
    fun allow(chars: Collection<String>, levels: CharLevels, to: File? = null): RareChoice =
        update(chars.map { RareChoice.Change.One(it.codePointAt(0), false) }, levels, to)

    /**
     * Lays [changes] over the file as it is now and writes it, whole or not at all; returns what it then has.
     * @throws IOException if the file cannot be read (nothing written: not the default over what is there)
     * or written, or a later version wrote it ([Newer])
     */
    @Synchronized
    fun update(changes: List<RareChoice.Change>, levels: CharLevels, to: File? = null): RareChoice {
        val target = to ?: fileToSave()
        val now = load(target)
        // a later version's: this one reads none of it, and would write it all away
        if (now.newer) throw Newer()
        val made = now.with(changes, levels)
        target.parentFile?.mkdirs()
        replaceFile(target, File(target.path + ".new")) { it.writeText(made.format()) }
        return made
    }
}
