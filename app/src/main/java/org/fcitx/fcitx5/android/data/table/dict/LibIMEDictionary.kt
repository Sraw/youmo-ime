/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

class LibIMEDictionary(file: File) : Dictionary() {

    override var file: File = file
        private set

    override val type: Type = Type.LibIME

    init {
        ensureFileExists()
        if (file.extension != type.ext)
            errorArg(R.string.exception_dict_filename, file.name)
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        dest.writeText(LibimeFiles.table(file.readBytes()))
        return TextDictionary(dest)
    }
}