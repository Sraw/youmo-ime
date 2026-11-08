/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.store.replaceFile
import java.io.IOException

/**
 * The English words the user blocked: never offered, completed, predicted, glided or corrected to.
 * A file of its own in [dir] ([FILE], a word a line as written), beside pinyin's `words.blocked`,
 * whose entries need a reading English has none of; with no [dir], kept in memory. A word is
 * blocked in any case (Hello as hello): the data and the sentence's start change a word's case.
 */
class EnglishBlocks(private val dir: UserFiles?, private val onError: (IOException) -> Unit = {}) {
    // a file that did not read is not written over: what is in memory is not all of it
    private var unread = false
    private val words: LinkedHashMap<String, String> = read()

    /** The words blocked, as written when blocked. */
    val blocked: List<String> get() = words.values.toList()

    /** Whether [text] is blocked, in any case. */
    fun has(text: String): Boolean = words.isNotEmpty() && text.lowercase() in words

    /** Blocks each of [texts] that English types ([EnglishText.isWord]): those newly blocked. */
    fun block(texts: Collection<String>): List<String> =
        texts.filter { EnglishText.isWord(it) && words.putIfAbsent(it.lowercase(), it) == null }.also { if (it.isNotEmpty()) write() }

    /** Offers each of [texts] again: those that were blocked. */
    fun unblock(texts: Collection<String>): List<String> =
        texts.filter { words.remove(it.lowercase()) != null }.also { if (it.isNotEmpty()) write() }

    private fun read(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val file = dir?.file(FILE) ?: return out
        try {
            if (file.taken()) {
                file.input().bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter(EnglishText::isWord).forEach { out.putIfAbsent(it.lowercase(), it) }
                }
            }
        } catch (e: IOException) {
            onError(e)
            unread = true
        }
        return out
    }

    private fun write() {
        val dir = dir ?: return
        val file = dir.file(FILE)
        if (unread) {
            onError(IOException("$file did not read: not written over"))
            return
        }
        try {
            dir.mkdirs()
            // the whole list or the one before it, never half of one
            replaceFile(file, dir.file("$FILE.new")) { temp ->
                temp.write(words.values.joinToString("") { "$it\n" }.toByteArray())
            }
        } catch (e: IOException) {
            onError(e)
        }
    }

    companion object {
        const val FILE = "words.en.blocked"
    }
}
