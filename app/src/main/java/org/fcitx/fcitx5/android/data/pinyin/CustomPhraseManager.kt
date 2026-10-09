/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.replaceFile
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException

/** The pinyin custom phrases, where fcitx5-chinese-addons kept them and the engine reads them. */
object CustomPhraseManager {

    private val file by lazy { File(appContext.getExternalFilesDir(null)!!, "data/pinyin/customphrase") }

    /**
     * Every phrase, turned off too, as [CustomPhrases.all] lists them; none if there is no file.
     * @throws IOException if there is one that cannot be read
     */
    fun load(from: File = file): List<PinyinCustomPhrase> {
        if (!from.isFile) return emptyList()
        return CustomPhrases.parse(from.readText()).all.map { PinyinCustomPhrase(it.key, it.order, it.value) }
    }

    /** Saves [items] in their order, whole or not at all. */
    fun save(items: List<PinyinCustomPhrase>, to: File = file) =
        write(items.map { CustomPhrases.Phrase(it.key, it.order, it.value) }, to)

    /**
     * Saves [mine], the editor's list of what it loaded as [base]. The keyboard may have pinned or
     * deleted a phrase since (the engine writes the file too), which the editor's list does not
     * show: then what the editor added and removed is applied to the file as it is now, rather
     * than the editor's list written over it. [base] is as [load] gave it, or as this returned:
     * what the file has now, in its order.
     * @throws IOException if the file cannot be read or written
     */
    @Synchronized
    fun saveOver(base: List<PinyinCustomPhrase>, mine: List<PinyinCustomPhrase>, to: File = file): List<PinyinCustomPhrase> {
        val theirs = load(to)
        if (theirs == base) {
            save(mine, to)
        } else {
            val removed = base.toSet() - mine.toSet()
            val added = mine.toSet() - base.toSet() - theirs.toSet()
            save(theirs.filter { it !in removed } + added, to)
        }
        return load(to)
    }

    /**
     * [saveOver] for the engine, which keeps its own list: nothing read back. A key whose phrases
     * [mine] changed since [base] has them in the order of [mine] (a phrase pinned first), less
     * those the file lost since, then those it gained; any other key's are as the file has them.
     * @throws IOException if the file cannot be read or written
     */
    @Synchronized
    fun saveKeysOver(base: List<PinyinCustomPhrase>, mine: List<PinyinCustomPhrase>, to: File = file) {
        val theirs = load(to)
        if (theirs == base) return save(mine, to)
        val before = base.groupBy { it.key }
        val now = theirs.groupBy { it.key }
        val wanted = mine.groupBy { it.key }
        save((now.keys + wanted.keys).flatMap { key ->
            val b = before[key].orEmpty()
            val t = now[key].orEmpty()
            val m = wanted[key].orEmpty()
            if (m == b) t else m.filter { it !in b || it in t } + t.filter { it !in b && it !in m }
        }, to)
    }

    /**
     * Saves [phrases] in their order, whole or not at all: the engine's, changed from the keyboard.
     * One writer at a time, the editor's and the engine's sharing the file next to it.
     */
    @Synchronized
    fun write(phrases: List<CustomPhrases.Phrase>, to: File = file) {
        to.parentFile?.mkdirs()
        replaceFile(to, File(to.path + ".new")) {
            it.writeText(CustomPhrases.format(phrases))
            true
        }
    }
}
