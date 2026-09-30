/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
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
    fun save(items: List<PinyinCustomPhrase>, to: File = file) {
        to.parentFile?.mkdirs()
        val next = File(to.path + ".new")
        try {
            next.writeText(CustomPhrases.format(items.map { CustomPhrases.Phrase(it.key, it.order, it.value) }))
            if (!next.renameTo(to)) throw IOException("cannot replace $to")
        } finally {
            next.delete()
        }
    }
}
