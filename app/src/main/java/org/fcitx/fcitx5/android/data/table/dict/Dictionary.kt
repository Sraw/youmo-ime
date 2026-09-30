/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table.dict

import java.io.File

abstract class Dictionary {

    enum class Type(val ext: String) {
        LibIME("dict"), Text("txt");

        companion object {
            fun fromFileName(name: String): Type? =
                when {
                    name.endsWith(".dict") -> LibIME
                    name.endsWith(".txt") -> Text
                    else -> null
                }
        }
    }

    abstract val file: File

    abstract val type: Type

    abstract fun toTextDictionary(dest: File): TextDictionary

    open val name: String
        get() = file.nameWithoutExtension

    protected fun ensureFileExists() {
        if (!file.exists())
            throw IllegalStateException("File ${file.absolutePath} does not exist")
    }

    protected fun ensureTxt(dest: File) {
        if (dest.extension != Type.Text.ext)
            throw IllegalArgumentException("Dest file name must end with .${Type.Text.ext}")
        dest.delete()
    }

    override fun toString(): String = "${javaClass.simpleName}[$name -> ${file.path}]"

    companion object {
        fun new(it: File): Dictionary? = when (Type.fromFileName(it.name)) {
            Type.LibIME -> LibIMEDictionary(it)
            Type.Text -> TextDictionary(it)
            null -> null
        }
    }
}