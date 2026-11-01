/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import java.io.File
import java.nio.ByteBuffer

/**
 * [Engines.Additions.dictionary] of [lines], compiled as the app compiles the dictionaries the user
 * merged, against the pinyin data [load] gives (its words kept out of the file's trie): each time it
 * is asked, so [lines] can count the reads or throw as an unreadable dictionary would.
 */
internal fun compiled(load: (String) -> ByteBuffer, lines: () -> Sequence<String>): () -> DictionaryFile? = {
    val file = File.createTempFile("merged", ".ymd").apply { deleteOnExit() }
    val data = PinyinData.load(load(InputMethodCatalog.PINYIN_DATA), verify = false)
    DictionaryCompiler.compile(listOf(lines()), file, inBase = DictionaryCompiler.inBase(data))
    DictionaryFile.open(JvmFile(file))
}

/** A directory for the tables' indexes of a compiled dictionary ([Engines.Additions.indexes]), in memory. */
internal fun indexDir(): UserFiles = MemoryFiles()
