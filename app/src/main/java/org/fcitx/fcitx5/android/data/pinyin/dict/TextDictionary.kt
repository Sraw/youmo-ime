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
 * one imported, kept gzip-compressed ([SourceNames]) and turned off by naming it `.txt.gz.disable`;
 * one an older build kept as `.txt[.disable]` till the compile thread converts it.
 */
class TextDictionary(file: File) : PinyinDictionary() {
    override var file: File = file
        private set

    override val type: Type = Type.Text

    var isEnabled: Boolean = true
        private set

    override val name: String
        get() = SourceNames.nameOf(file.name)

    init {
        ensureFileExists()
        isEnabled = when {
            SourceNames.isSource(file.name) || SourceNames.isText(file.name) -> SourceNames.isOn(file.name)
            else -> errorArg(R.string.exception_text_dict_filename, file.name)
        }
    }

    /** Whether it is now on: false if the file could not be renamed. */
    fun enable() = rename(true)

    /** Whether it is now off: false if the file could not be renamed. */
    fun disable() = rename(false)

    private fun rename(enabled: Boolean): Boolean {
        if (isEnabled == enabled) return true
        val newFile = file.resolveSibling(if (SourceNames.isSource(file.name)) fileName(name, enabled) else SourceNames.textName(name, enabled))
        // renameTo replaces a file there on most filesystems: never another dictionary
        if (newFile.exists() || !file.renameTo(newFile)) return false
        file = newFile
        isEnabled = enabled
        return true
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        // a kept one's text, not its gzip
        SourceNames.open(file).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return TextDictionary(dest)
    }

    companion object {
        const val DISABLE = "disable"

        /** The file a dictionary called [name] is kept in, on or off ([SourceNames.fileName]). */
        fun fileName(name: String, enabled: Boolean = true) = SourceNames.fileName(name, enabled)
    }
}
