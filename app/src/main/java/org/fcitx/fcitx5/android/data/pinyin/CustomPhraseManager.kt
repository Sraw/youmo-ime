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

/** The pinyin custom phrases, where fcitx5-chinese-addons kept them and the engine reads them. */
object CustomPhraseManager {

    // in fcitx's data, the app's own files once an older build's is moved in (A4): where the engine reads it
    private fun file() = file(HostConfigDir.inUse(ConfigHome.DATA))

    private fun file(home: InUse) = File(home.dir, "pinyin/customphrase")

    // the file to save to, a save without one given asking at its start: none while fcitx's data is not
    // settled, what was saved then being lost at the next start
    private fun fileToSave(): File {
        val home = HostConfigDir.inUse(ConfigHome.DATA)
        if (!home.writable) throw IOException(appContext.getString(R.string.user_data_not_settled))
        return file(home)
    }

    /**
     * Every phrase, turned off too, as [CustomPhrases.all] lists them; none if there is no file.
     * @throws IOException if there is one that cannot be read
     */
    fun load(from: File = file()): List<PinyinCustomPhrase> {
        if (!from.isFile) return emptyList()
        return items(CustomPhrases.parse(from.readText()))
    }

    /** [phrases] as the editor lists them, turned off too, in the order of [CustomPhrases.all]. */
    fun items(phrases: CustomPhrases) = phrases.all.map { PinyinCustomPhrase(it.key, it.order, it.value) }

    /** Saves [items] in their order, whole or not at all. */
    fun save(items: List<PinyinCustomPhrase>, to: File? = null) {
        val target = to ?: fileToSave()
        write(items.map { CustomPhrases.Phrase(it.key, it.order, it.value) }, target)
    }

    /**
     * Saves [mine], the editor's list, loaded as [base] by [load] or returned by the last call.
     * The keyboard writes the file too (a phrase pinned or deleted): if it changed since [base],
     * only what the editor added and removed is applied to the file as it is now.
     * Returns what the file then has, the next call's [base].
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
            // one edited or turned on or off takes the place of the one it replaced, not the end of its key
            val kept = theirs.mapNotNull { phrase ->
                if (phrase !in removed) phrase
                else added.firstOrNull { it.key == phrase.key }?.also { added.remove(it) }
            }
            save(kept + added, target)
        }
        return load(target)
    }

    /**
     * [saveOver] for the engine, which keeps its own list: nothing read back. A key whose phrases
     * [mine] changed since [base] has them in the order of [mine] (a phrase pinned first), less
     * those the file lost since, then those it gained; any other key's are as the file has them.
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

    /**
     * Saves [phrases] in their order, whole or not at all: what [save], and so the merges above, write.
     * One writer at a time, the editor's and the engine's sharing the file next to it.
     */
    @Synchronized
    fun write(phrases: List<CustomPhrases.Phrase>, to: File? = null) {
        val target = to ?: fileToSave()
        target.parentFile?.mkdirs()
        replaceFile(target, File(target.path + ".new")) {
            it.writeText(CustomPhrases.format(phrases))
        }
    }
}
