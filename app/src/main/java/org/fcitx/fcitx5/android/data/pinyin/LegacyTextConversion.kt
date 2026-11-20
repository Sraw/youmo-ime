/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary.leftNotes
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary.unreadNotes
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary.unwrittenNotes
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/** Older builds' `.txt` gzip'd into [MergedDictionary]'s sources at the top of its compile, on its thread; texts whose delete was refused are noted apart. */
internal object LegacyTextConversion {

    /** Keyed by on-state name: a turn renames text and gzip together, and the note stays the text's. */
    internal fun leftKey(text: File) = Signature(SourceNames.textName(SourceNames.nameOf(text.name), true), text.length(), text.lastModified()).line

    private fun leftText(dir: File, key: String): File? {
        val name = SourceNames.nameOf(Signature.fileNameOf(key))
        return listOf(true, false).map { File(dir, SourceNames.textName(name, it)) }.firstOrNull { it.isFile && leftKey(it) == key }
    }

    // noted texts whose same-state gzip is there: their words are in it
    fun leftHeld(dir: File): Set<String> = leftNotes.notedIn(dir).filterTo(HashSet()) {
        val text = leftText(dir, it) ?: return@filterTo false
        File(dir, SourceNames.fileName(SourceNames.nameOf(text.name), SourceNames.isOn(text.name))).isFile
    }

    // each noted text deleted again under the sources lock; the note dropped once it or its gzip is gone
    private fun retryLeft(dir: File) {
        val noted = leftNotes.notedIn(dir)
        if (noted.isEmpty()) return
        val dropped = synchronized(ImportedDictionaries) {
            val held = leftHeld(dir)
            noted.filter { s ->
                val text = leftText(dir, s)
                text == null || s !in held || text.delete()
            }
        }
        leftNotes.forget(dir, dropped)
    }

    // not those that could not be before
    fun toConvert(dir: File): List<File> {
        val texts = SourceNames.unconverted(dir)
        if (texts.isEmpty()) return texts
        val skipped = unreadNotes.notedIn(dir) + unwrittenNotes.notedIn(dir)
        val held = leftHeld(dir)
        return texts.filter { Signature.lineOf(it) !in skipped && leftKey(it) !in held }
    }

    /**
     * Each text gzip'd to a tmp outside the sources lock, committed under it ([ImportedDictionaries.commitConversion]),
     * till none is left (one changed meanwhile is relisted). One that cannot be is skipped and noted, not retried till it
     * changes. [owe] first: a kill after a commit leaves the upgrade owed. Returns whether any was converted.
     */
    fun convert(dir: File, owe: () -> Unit, start: () -> Unit, tell: (Float) -> Unit): Converted {
        // a text both on and off (a backup restored over the other state): the newer one, before either is gzip'd
        ImportedDictionaries.settle(dir)
        val failed = ArrayList<String>()
        val unread = ArrayList<String>()
        // a text whose delete failed after its commit is not converted again in this pass
        val done = HashSet<String>()
        // nor at a later start; its signature taken under the sources lock, as a turn may rename it
        val left = ArrayList<String>()
        retryLeft(dir)
        fun left() = toConvert(dir).filter { Signature.lineOf(it) !in failed && Signature.lineOf(it) !in unread && SourceNames.nameOf(it.name) !in done }
        var texts = left()
        if (texts.isEmpty()) return Converted(any = false, unconverted = false)
        owe()
        start()
        val total = texts.sumOf { it.length() }.coerceAtLeast(1)
        var read = 0L
        var converted = false
        // notes written however it ends: a cancel (thrown by [tell]) keeps a left text's
        try {
            tell(0f)
            while (texts.isNotEmpty()) {
                val seen = texts.map { Signature.lineOf(it) }
                var committed = false
                for (text in texts) {
                    val one = convertOne(dir, text) { n ->
                        read += n
                        tell(read.toFloat() / total)
                    }
                    when (one) {
                        is One.Committed -> {
                            committed = true
                            converted = true
                            done += SourceNames.nameOf(text.name)
                            left += listOfNotNull(one.left)
                        }
                        is One.Unread -> unread += one.line
                        is One.Failed -> failed += one.line
                        One.Kept -> {}
                    }
                }
                texts = left()
                // none could be and none changed: retried only once they change
                if (!committed && texts.map { Signature.lineOf(it) } == seen) {
                    failed += seen.filter { it !in unread }
                    break
                }
            }
        } finally {
            unreadNotes.note(dir, unread)
            unwrittenNotes.note(dir, failed)
            leftNotes.note(dir, left)
        }
        return Converted(converted, unconverted = failed.isNotEmpty() || unread.isNotEmpty())
    }

    class Converted(val any: Boolean, val unconverted: Boolean)

    // the text's own read failed, not the gzip's write: the text is what is wrong
    private class Unread(cause: IOException) : IOException(cause)

    private sealed class One {
        class Committed(val left: String?) : One()
        class Unread(val line: String) : One()
        class Failed(val line: String) : One()
        object Kept : One()
    }

    // [read] told the bytes as they are read
    private fun convertOne(dir: File, text: File, read: (Int) -> Unit): One {
        val name = SourceNames.nameOf(text.name)
        val length = text.length()
        val modified = text.lastModified()
        val temp = File(dir, name + SourceNames.CONVERT_TMP)
        return try {
            val commit = if (gzip(text, temp, read)) ImportedDictionaries.commitConversion(dir, name, temp, length, modified) else null
            if (commit != null) One.Committed(commit.left) else One.Kept
        } catch (e: Unread) {
            Timber.w(e.cause, "%s: cannot be read", text.name)
            One.Unread(Signature(text.name, length, modified).line)
        } catch (e: IOException) {
            // gzip not written (likely disk full): kept, retried when the page opens
            Timber.w(e, "%s: not converted", text.name)
            One.Failed(Signature(text.name, length, modified).line)
        } finally {
            temp.delete()
        }
    }

    // false if it is gone (turned or deleted since listed: relisted)
    // @throws Unread if the text cannot be read, another IOException if the gzip cannot be written
    private fun gzip(text: File, temp: File, read: (Int) -> Unit): Boolean {
        val input = try {
            FileInputStream(text)
        } catch (e: FileNotFoundException) {
            // there but unreadable: a failure, not a text gone
            if (text.exists()) throw Unread(e)
            Timber.d(e, "%s: listed again", text.name)
            return false
        }
        input.use {
            SourceNames.writeGzip(temp) { out ->
                val buffer = ByteArray(CONVERT_BUFFER)
                while (true) {
                    val n = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        throw Unread(e)
                    }
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read(n)
                }
            }
        }
        return true
    }

    private const val CONVERT_BUFFER = 1 shl 16
}
