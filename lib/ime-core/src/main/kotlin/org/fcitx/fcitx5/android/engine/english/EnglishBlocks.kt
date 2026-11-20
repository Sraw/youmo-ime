/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.store.Unsaved
import org.fcitx.fcitx5.android.engine.store.replaceFile
import java.io.IOException

/** English words the user blocked, a file of their own ([FILE]; pinyin's needs readings). Lower case blocks any case (the: The); a capital only as written (US, not us). */
class EnglishBlocks(private val dir: UserFiles?, private val onError: (IOException) -> Unit = {}) {
    // a file that did not read is not written over: memory is not all of it
    private var unread = false
    // lines this build cannot read (a later build's) are written back as they were
    private val unknown = ArrayList<String>()
    private val words: LinkedHashSet<String> = read()

    val blocked: List<String> get() = words.toList()

    fun has(text: String): Boolean = words.isNotEmpty() && (text in words || text.any(Char::isUpperCase) && text.lowercase() in words)

    fun block(texts: Collection<String>): List<String> =
        texts.filter { EnglishText.isWord(it) && words.add(it) }.also { if (it.isNotEmpty()) write() }

    /** Blocks the first form of [shown] that [known] has: as shown, first letter lowered, all lowered. */
    fun blockShown(shown: String, known: (String) -> Boolean): List<String> =
        block(listOf(sequenceOf(shown, shown.replaceFirstChar(Char::lowercaseChar), shown.lowercase()).firstOrNull(known) ?: shown))

    fun unblock(texts: Collection<String>): List<String> =
        texts.filter { words.remove(it) }.also { if (it.isNotEmpty()) write() }

    /** Clears all, unknown lines too; a file that did not read is deleted unread. */
    fun clear() {
        if (unread) {
            words.clear()
            unknown.clear()
            val file = dir?.file(FILE)
            if (file != null && file.taken() && !file.delete()) onError(Unsaved("$file did not read: not cleared")) else unread = false
            return
        }
        if (words.isEmpty() && unknown.isEmpty()) return
        words.clear()
        unknown.clear()
        write()
    }

    private fun read(): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        val file = dir?.file(FILE) ?: return out
        try {
            if (file.taken()) {
                file.input().bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter(String::isNotEmpty).forEach { if (EnglishText.isWord(it)) out.add(it) else unknown += it }
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
            onError(Unsaved("$file did not read: not written over"))
            return
        }
        try {
            dir.mkdirs()
            // the whole list or the one before it, never half
            replaceFile(file, dir.file("$FILE.new")) { temp ->
                temp.write((words + unknown).joinToString("") { "$it\n" }.toByteArray())
            }
        } catch (e: IOException) {
            onError(Unsaved("$file not written", e))
        }
    }

    companion object {
        const val FILE = "words.en.blocked"
    }
}
