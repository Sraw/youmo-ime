/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.fcitx.fcitx5.android.data.table.dict.Dictionary
import org.fcitx.fcitx5.android.data.table.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.table.TableText
import java.io.File
import java.io.IOException

/** The tables the user imported, kept as text the engine reads, whatever they came as. */
object ImportedTables {

    /**
     * [dict] as text at [dest], checked as the engine will read it; what was at [dest] is kept
     * if it cannot be.
     */
    fun install(dict: Dictionary, dest: File): TextDictionary {
        // named .txt as the conversion wants, hidden till it is checked
        val temp = File(dest.parentFile, ".${dest.name}")
        try {
            dict.toTextDictionary(temp)
            temp.bufferedReader().use { TableText.check(it, dict.file.name) }
            if (!temp.renameTo(dest)) throw IOException("cannot write ${dest.name}")
        } finally {
            temp.delete()
        }
        return TextDictionary(dest)
    }
}
