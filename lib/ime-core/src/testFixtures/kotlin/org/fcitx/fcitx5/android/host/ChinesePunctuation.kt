/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.io.File

/**
 * Chinese punctuation's map as the app ships it (`punc.mb.zh_CN`), each key to its first (the one typed): what a test
 * hands the keyboard for Chinese punctuation on, so every mark is covered (`^` ……, `\` 、, `"` “), not a few written out.
 */
object ChinesePunctuation {
    private const val PATH = "pkgdata/punctuation/punc.mb.zh_CN"
    private val DIRS = listOf("src/main/host-assets", "../../app/src/main/host-assets", "app/src/main/host-assets")

    val map: Map<String, String> by lazy {
        // on ime-core's test classpath; :app's tests read the file from where the app ships it
        val bytes = ChinesePunctuation::class.java.getResourceAsStream("/$PATH")?.use { it.readBytes() }
            ?: DIRS.map { File(it, PATH) }.firstOrNull(File::isFile)?.readBytes()
            ?: error("no $PATH on the classpath nor under ${DIRS.joinToString()} (from ${File("").absolutePath})")
        PunctuationTable.parse(bytes).groupBy { it.key }.mapValues { (_, entries) -> entries.first().mapping }
    }
}
