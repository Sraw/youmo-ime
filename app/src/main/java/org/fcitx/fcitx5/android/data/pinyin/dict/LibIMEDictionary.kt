/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

/** A dictionary in libime's binary format (`.dict`), to import: the engine keeps text. */
class LibIMEDictionary(file: File) : PinyinDictionary() {

    override val file: File = file

    override val type: Type = Type.LibIME

    init {
        ensureFileExists()
        if (file.extension != type.ext) errorArg(R.string.exception_libime_dict_filename, file.name)
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        dest.bufferedWriter().use { out ->
            LibimeFiles.pinyinDictionary(file.readBytes()).forEach { out.write(it); out.write('\n'.code) }
        }
        return TextDictionary(dest)
    }
}
