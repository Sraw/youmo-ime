/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import java.io.File

abstract class PinyinDictionary {

    /** [Words] is a word pack ([org.fcitx.fcitx5.android.engine.user.WordPack]): text too, kept as it came. */
    enum class Type(val ext: String) {
        LibIME("dict"), Sougou("scel"), Text("txt"), Words("words");

        companion object {
            /**
             * A file manager may name one `WORDS.SCEL`: an extension in any case, unless [ignoreCase]
             * is false, for the files kept, which are named in lowercase as the engine reads them.
             */
            fun fromFileName(name: String, ignoreCase: Boolean = true): Type? =
                when {
                    name.endsWith(".dict", ignoreCase) -> LibIME
                    name.endsWith(".scel", ignoreCase) -> Sougou
                    name.endsWith(".txt", ignoreCase) || name.endsWith(".txt.${TextDictionary.DISABLE}", ignoreCase) -> Text
                    name.endsWith(".words", ignoreCase) || name.endsWith(".words.${TextDictionary.DISABLE}", ignoreCase) -> Words
                    else -> null
                }
        }
    }

    abstract val file: File

    abstract val type: Type

    abstract fun toTextDictionary(dest: File): TextDictionary

    open val name: String
        get() = nameOf(file.name)

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
        /** A dictionary's name, from its file's: what the extension (and `.disable`) leave. */
        fun nameOf(fileName: String): String =
            fileName.removeSuffix(".${TextDictionary.DISABLE}").substringBeforeLast('.')

        fun new(it: File): PinyinDictionary? = when (Type.fromFileName(it.name)) {
            Type.LibIME -> LibIMEDictionary(it)
            Type.Sougou -> SougouDictionary(it)
            Type.Text, Type.Words -> TextDictionary(it)
            null -> null
        }
    }
}