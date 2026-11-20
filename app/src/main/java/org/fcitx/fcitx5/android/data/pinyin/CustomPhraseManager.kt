/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.InUse
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException

/** Where fcitx5-chinese-addons kept them and the engine reads them. */
object CustomPhraseManager {

    // in fcitx's data, the app's own once an older build's is moved in
    private fun file() = file(HostConfigDir.inUse(ConfigHome.DATA))

    private fun file(home: InUse) = File(home.dir, "pinyin/customphrase")

    // none while fcitx's data is unsettled: a save then would be lost at the next start
    private fun fileToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return file(home)
    }

    /**
     * Turned off too; none if there is no file. Waits for a [saveOver] under way, so a recreated page reads the last save.
     * @throws IOException if there is one that cannot be read
     */
    @Synchronized
    fun load(from: File = file()): List<PinyinCustomPhrase> {
        if (!from.isFile) return emptyList()
        return items(CustomPhrases.parse(from.readText()))
    }

    fun items(phrases: CustomPhrases) = phrases.all.map { PinyinCustomPhrase(it.key, it.order, it.value) }

    /** Whole or not at all. */
    fun save(items: List<PinyinCustomPhrase>, to: File? = null) {
        val target = to ?: fileToSave()
        write(items.map { CustomPhrases.Phrase(it.key, it.order, it.value) }, target)
    }

    /**
     * The keyboard writes the file too: if it changed since [base], only the editor's adds and removals apply to it as it
     * is now. Returns the file's content, the next call's [base].
     * @throws IOException if the file cannot be read or written
     */
    @Synchronized
    fun saveOver(base: List<PinyinCustomPhrase>, mine: List<PinyinCustomPhrase>, to: File? = null): List<PinyinCustomPhrase> {
        val target = to ?: fileToSave()
        val theirs = load(target)
        if (theirs == base) {
            save(mine, target)
        } else {
            val removed = base.toSet() - mine.toSet()
            val added = (mine.toSet() - base.toSet() - theirs.toSet()).toMutableList()
            // one edited or toggled takes the replaced one's place, not the end of its key
            val kept = theirs.mapNotNull { phrase ->
                if (phrase !in removed) phrase
                else added.firstOrNull { it.key == phrase.key }?.also { added.remove(it) }
            }
            save(kept + added, target)
        }
        return load(target)
    }

    /**
     * For the engine, which keeps its own list: nothing read back. A key [mine] changed takes [mine]'s order, less what the
     * file lost since, then what it gained; other keys as the file has them.
     * @throws IOException if the file cannot be read or written
     */
    @Synchronized
    fun saveKeysOver(base: List<PinyinCustomPhrase>, mine: List<PinyinCustomPhrase>, to: File? = null) {
        val target = to ?: fileToSave()
        val theirs = load(target)
        if (theirs == base) return save(mine, target)
        val before = base.groupBy { it.key }
        val now = theirs.groupBy { it.key }
        val wanted = mine.groupBy { it.key }
        save((now.keys + wanted.keys).flatMap { key ->
            val b = before[key].orEmpty()
            val t = now[key].orEmpty()
            val m = wanted[key].orEmpty()
            if (m == b) t else m.filter { it !in b || it in t } + t.filter { it !in b && it !in m }
        }, target)
    }

    /** Whole or not at all; one writer at a time, sharing the file next to it. */
    @Synchronized
    fun write(phrases: List<CustomPhrases.Phrase>, to: File? = null) {
        val target = to ?: fileToSave()
        target.parentFile?.mkdirs()
        replaceFile(target, File(target.path + ".new")) {
            it.writeText(CustomPhrases.format(phrases))
        }
    }
}
