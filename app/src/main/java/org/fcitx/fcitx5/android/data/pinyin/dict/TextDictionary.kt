/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

/**
 * A dictionary in libime's text format, a word a line (`text pin'yin cost`): one to import, or
 * one imported, which the engine reads and which is turned off by naming it `.txt.disable`.
 */
class TextDictionary(file: File) : PinyinDictionary() {
    override var file: File = file
        private set

    override val type: Type = if (Type.fromFileName(file.name) == Type.Words) Type.Words else Type.Text

    var isEnabled: Boolean = true
        private set

    override val name: String
        get() = nameOf(file.name)

    init {
        ensureFileExists()
        isEnabled = when {
            file.name.endsWith(".${type.ext}") -> true
            file.name.endsWith(".${type.ext}.$DISABLE") -> false
            else -> errorArg(R.string.exception_text_dict_filename, file.name)
        }
    }

    /** Whether it is now on: false if the file could not be renamed. */
    fun enable() = rename(true)

    /** Whether it is now off: false if the file could not be renamed. */
    fun disable() = rename(false)

    private fun rename(enabled: Boolean): Boolean {
        if (isEnabled == enabled) return true
        val newFile = file.resolveSibling(fileName(name, enabled, type))
        // renameTo replaces a file there on most filesystems: never another dictionary
        if (newFile.exists() || !file.renameTo(newFile)) return false
        file = newFile
        isEnabled = enabled
        return true
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        file.copyTo(dest)
        return TextDictionary(dest)
    }

    companion object {
        const val DISABLE = "disable"

        fun fileName(name: String, enabled: Boolean = true, type: Type = Type.Text) = "$name.${type.ext}" + if (enabled) "" else ".$DISABLE"
    }
}
