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

/**
 * An older build's texts (0.3.1's or 0.3.2's `.txt`, or a backup of theirs restored) gzip'd into
 * the sources [MergedDictionary] compiles, at the top of its compile, on its thread; and the texts
 * a conversion left (their delete refused), kept apart in [MergedDictionary]'s notes.
 */
internal object LegacyTextConversion {

    /**
     * A left text as [leftNotes] has it: its signature by its name on, whichever state it is in. A turn
     * renames a dictionary's text and gzip together (F3686), and the note stays the text's.
     */
    internal fun leftKey(text: File) = Signature(SourceNames.textName(SourceNames.nameOf(text.name), true), text.length(), text.lastModified()).line

    // the text, on or off, [key] notes in [leftNotes] as it is now; null if there is none
    private fun leftText(dir: File, key: String): File? {
        val name = SourceNames.nameOf(Signature.fileNameOf(key))
        return listOf(true, false).map { File(dir, SourceNames.textName(name, it)) }.firstOrNull { it.isFile && leftKey(it) == key }
    }

    // the texts noted in [leftNotes] whose gzip of their own state is there: their words are in it
    fun leftHeld(dir: File): Set<String> = leftNotes.notedIn(dir).filterTo(HashSet()) {
        val text = leftText(dir, it) ?: return@filterTo false
        File(dir, SourceNames.fileName(SourceNames.nameOf(text.name), SourceNames.isOn(text.name))).isFile
    }

    // each text noted in [leftNotes] deleted again, under the sources lock (no reconversion, no
    // upgrade told); the note dropped once it is gone or its gzip is (F3674, F3675)
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

    // an older build's texts in [dir] still to convert: not those that could not be before
    fun toConvert(dir: File): List<File> {
        val texts = SourceNames.unconverted(dir)
        if (texts.isEmpty()) return texts
        val skipped = unreadNotes.notedIn(dir) + unwrittenNotes.notedIn(dir)
        val held = leftHeld(dir)
        return texts.filter { Signature.lineOf(it) !in skipped && leftKey(it) !in held }
    }

    /**
     * Each text an older build left in [dir] gzip'd into a tmp, outside the sources lock, then put
     * in its place under it ([ImportedDictionaries.commitConversion]), till none is left: one
     * turned, deleted or restored over meanwhile is listed again. Whether any was converted. One
     * that cannot be is skipped, the others converted, and noted
     * ([MergedDictionary.unreadable] if it could not be read, else [MergedDictionary.unconverted]; [Converted.unconverted]);
     * not tried again till it changes or is retried (F3592). [owe] called first: a kill after a commit
     * leaves the upgrade owed; [start] before the first is read, [tell] the share read as it goes.
     */
    fun convert(dir: File, owe: () -> Unit, start: () -> Unit, tell: (Float) -> Unit): Converted {
        // a text both on and off (a backup restored over the other state): the newer one, before either is gzip'd (F3596)
        ImportedDictionaries.settle(dir)
        val failed = ArrayList<String>()
        val unread = ArrayList<String>()
        // committed this pass: a text whose delete failed after its commit is not converted again in it (F3649)
        val done = HashSet<String>()
        // such a text, as it is: not converted again at a later start either (F3660); its signature
        // taken under the sources lock, a turn meanwhile renaming it (F3675)
        // (no test of the write: a delete refused while the rename beside it is allowed needs a hook in this code)
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
        // the notes written however it ends: a cancel (thrown by [tell]) keeps a left text's, not converted again (F4043)
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
                // none could be, and they are as they were: not here for ever, tried again once they change
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

    /** What a [convert] did: [any] converted, and whether one could not be. */
    class Converted(val any: Boolean, val unconverted: Boolean)

    // a text's own read failed, not the gzip's write: the text is what is wrong (F3645)
    private class Unread(cause: IOException) : IOException(cause)

    /** What became of one text in [convert]. */
    private sealed class One {
        class Committed(val left: String?) : One()
        class Unread(val line: String) : One()
        class Failed(val line: String) : One()
        object Kept : One()
    }

    // [text] gzip'd into a tmp and committed in its place, or why not; [read] told the bytes as they are read
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
            // its gzip not written (most likely the disk full): kept as it is, tried again when the page opens (F3645)
            Timber.w(e, "%s: not converted", text.name)
            One.Failed(Signature(text.name, length, modified).line)
        } finally {
            temp.delete()
        }
    }

    // [text] gzip'd into [temp], synced, [read] told of the bytes as they go; false if it is not
    // there (turned or deleted since it was listed: listed again)
    // @throws Unread if the text cannot be read, another IOException if the gzip cannot be written
    private fun gzip(text: File, temp: File, read: (Int) -> Unit): Boolean {
        val input = try {
            FileInputStream(text)
        } catch (e: FileNotFoundException) {
            // there but not to be read: a failure, not a text gone
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
