/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.engine.user.WordPack
import java.io.InputStream

/**
 * Whether a dictionary the user imports replaces one imported before: a word pack replaces the
 * pack of its name (each month's official pack is youmo-new.words). A pack is told by its first
 * line and named as it is kept, not as the browser that downloaded it saved the file.
 */
internal object PackImport {

    enum class Action { IMPORT, REPLACE, REFUSE }

    /** What importing a file does, a [pack] or not, where one of its name is there of type [existing], if any. */
    fun action(pack: Boolean, existing: PinyinDictionary.Type?): Action = when {
        existing == null -> Action.IMPORT
        pack && existing == PinyinDictionary.Type.Words -> Action.REPLACE
        else -> Action.REFUSE
    }

    /**
     * The name a pack in a file called [fileName] is kept as: without the ` (1)` a browser puts on
     * a second download of it, or the `.words` left of one it saved as `.txt`.
     */
    fun name(fileName: String): String {
        var name = PinyinDictionary.nameOf(fileName)
        while (true) {
            val next = name.replace(COPY, "").let { if (it.endsWith(WORDS, ignoreCase = true)) it.dropLast(WORDS.length) else it }
            if (next == name || next.isEmpty()) return name
            name = next
        }
    }

    /** Whether [stream] starts as a word pack, as [WordPack.isPack] tells one; read no further than its first line could be. */
    fun isPack(stream: InputStream) = WordPack.isPack(String(stream.readAtMost(HEAD), Charsets.UTF_8).substringBefore('\n'))

    private val COPY = Regex("\\s*\\(\\d+\\)$")

    private val WORDS = ".${PinyinDictionary.Type.Words.ext}"

    // the header, after a byte order mark
    private const val HEAD = 64
}
