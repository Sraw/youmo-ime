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
 * 生僻字屏蔽's file beside 常用词' in fcitx's pinyin data. Two writers (the grid page and the user words' 「放行」) hand in
 * changes laid over the file as it is then ([update]), so neither writes the other's away.
 */
object RareCharsManager {

    const val FILE = "pinyin/rarechars.txt"

    /** A later version's file ([RareChoice.newer]), left as is. */
    class Newer : IOException("rare characters: written by a later version, not saved over")

    private fun file() = file(HostConfigDir.inUse(ConfigHome.DATA))

    private fun file(home: InUse) = File(home.dir, FILE)

    // none while fcitx's data is unsettled: a save then would be lost at the next start
    private fun fileToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return file(home)
    }

    /** The APK's table (not a pack's); null, logged, where unreadable: 生僻字屏蔽 then off as the engine has it. */
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
            // nonsense past the header (bad bit width or length): as EngineAssets.readOr
            Timber.w(e, "rare characters: no levels")
            null
        }
    }

    // the JVM tests have no APK assets
    @VisibleForTesting
    internal fun useLevels(levels: CharLevels?) {
        read = lazyOf(levels)
    }

    /**
     * Off if there is none. Waits for an [update] under way.
     * @throws IOException if there is one that cannot be read
     */
    @Synchronized
    fun load(from: File = file()): RareChoice = if (from.isFile) RareChoice.parse(from.readText()) else RareChoice.NONE

    /** For the user words' pages to mark: none if it cannot be read. */
    fun gate(levels: CharLevels? = this.levels): CharGate = try {
        load().gate(levels)
    } catch (e: IOException) {
        Timber.w(e, "rare characters: not read, none marked")
        CharGate.NONE
    }

    /** A user word's 「放行」, laid over the file as it is. @throws IOException as [update] */
    fun allow(chars: Collection<String>, levels: CharLevels, to: File? = null): RareChoice =
        update(chars.map { RareChoice.Change.One(it.codePointAt(0), false) }, levels, to)

    /**
     * Whole or not at all; returns what the file then has.
     * @throws IOException if unreadable (nothing written), unwritable, or a later version's ([Newer])
     */
    @Synchronized
    fun update(changes: List<RareChoice.Change>, levels: CharLevels, to: File? = null): RareChoice {
        val target = to ?: fileToSave()
        val now = load(target)
        // this version reads none of it and would write it all away
        if (now.newer) throw Newer()
        val made = now.with(changes, levels)
        target.parentFile?.mkdirs()
        replaceFile(target, File(target.path + ".new")) { it.writeText(made.format()) }
        return made
    }
}
