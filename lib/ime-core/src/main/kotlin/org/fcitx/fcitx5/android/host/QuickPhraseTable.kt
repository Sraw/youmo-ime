/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.TreeMap

/**
 * One of fcitx's PkgData directories (`StandardPathsType::PkgData`): the user's
 * (`files/data`) first, then the app's built-in data. Paths are relative, `/`-separated.
 */
interface PkgDataDir {
    /** The file at [path], null when there is none. */
    fun read(path: String): ByteArray?

    /** The names of the files directly in [dir], null or empty when there are none. */
    fun list(dir: String): Collection<String>?
}

/** A [PkgDataDir] on disk. */
class FilePkgDataDir(private val root: File) : PkgDataDir {
    override fun read(path: String): ByteArray? = File(root, path).takeIf { it.isFile }?.readBytes()

    override fun list(dir: String): Collection<String>? =
        File(root, dir).listFiles()?.filter { it.isFile }?.map { it.name }
}

/**
 * The quick phrase table, fcitx's `BuiltInQuickPhraseProvider` (`modules/quickphrase/quickphraseprovider.cpp:29-104`).
 *
 * Loading (`reloadConfig`, `:43-70`): `data/QuickPhrase.mb` from the first directory that has it, then
 * every `data/quickphrase.d/<name>.mb` by file name — a name in an earlier directory hides the same name in
 * a later one (`StandardPaths::locate`, `fcitx-utils/standardpaths.cpp:201-229`) — skipping a file
 * when `<name>.disable` is in any of the directories. Each line (`load`, `:72-104`): trimmed, valid
 * UTF-8, the key up to the first whitespace, the value after the whitespace, through
 * `unescapeForValue`; anything else is skipped.
 *
 * The table is a multimap: keys in byte order of their UTF-8 (code point order), one key's values
 * in the order they were read. [populate] (`:29-42`) gives every entry whose key starts with the input.
 */
class QuickPhraseTable private constructor(private val map: TreeMap<String, MutableList<String>>) {

    /** `populate`: (key, value) of every entry whose key starts with [input], in table order. */
    fun populate(input: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((key, values) in map.tailMap(input, true)) {
            if (!key.startsWith(input)) break
            values.forEach { out.add(key to it) }
        }
        return out
    }

    companion object {
        private const val MAIN = "data/QuickPhrase.mb"
        private const val DIR = "data/quickphrase.d"
        private const val EXTENSION = ".mb"
        private const val DISABLE = ".disable"

        /** Code point order, which is UTF-8 byte order, what `std::string` compares. */
        val CODE_POINT_ORDER = Comparator<String> { a, b ->
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return@Comparator ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            (a.length - i).compareTo(b.length - j)
        }

        /** No phrases: quick input with nothing to offer, what the host starts with when no file reads. */
        val EMPTY = QuickPhraseTable(TreeMap(CODE_POINT_ORDER))

        /** Loads from [dirs], the user's first. */
        fun load(dirs: List<PkgDataDir>): QuickPhraseTable {
            val map = TreeMap<String, MutableList<String>>(CODE_POINT_ORDER)
            dirs.firstNotNullOfOrNull { it.read(MAIN) }?.let { loadInto(map, it) }
            val files = TreeMap<String, PkgDataDir>(CODE_POINT_ORDER)
            val disabled = HashSet<String>()
            for (dir in dirs) {
                for (name in dir.list(DIR).orEmpty()) {
                    when (extension(name)) {
                        EXTENSION -> files.putIfAbsent(name, dir)
                        DISABLE -> disabled.add(name)
                    }
                }
            }
            for ((name, dir) in files) {
                if ("$name$DISABLE" in disabled) continue
                dir.read("$DIR/$name")?.let { loadInto(map, it) }
            }
            return QuickPhraseTable(map)
        }

        /** `std::filesystem::path::extension`: from the last dot, unless the name starts with it. */
        private fun extension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(dot) else ""
        }

        private fun isWhitespace(b: Byte) = b == ' '.code.toByte() || b in 0x09..0x0d

        /** `load` (`quickphraseprovider.cpp:72-104`), line by line on `\n`. */
        private fun loadInto(map: TreeMap<String, MutableList<String>>, bytes: ByteArray) {
            // one decoder for the file: made per line, it was most of a cold start's 0.4 s on a debug build
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            var start = 0
            while (start < bytes.size) {
                var end = start
                while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
                parseLine(decoder, bytes, start, end)?.let { (key, value) -> map.getOrPut(key) { ArrayList(1) }.add(value) }
                start = end + 1
            }
        }

        @Suppress("ReturnCount")
        private fun parseLine(decoder: CharsetDecoder, bytes: ByteArray, from: Int, to: Int): Pair<String, String>? {
            var s = from
            var e = to
            while (s < e && isWhitespace(bytes[s])) s++
            while (e > s && isWhitespace(bytes[e - 1])) e--
            if (s == e) return null
            val text = decodeStrict(decoder, bytes, s, e) ?: return null
            val pos = text.indexOfFirst { it.code < 0x80 && isWhitespace(it.code.toByte()) }
            if (pos < 0) return null
            val word = (pos until text.length).firstOrNull { !(text[it].code < 0x80 && isWhitespace(text[it].code.toByte())) }
                ?: return null
            val value = FcitxUtils.unescapeForValue(text.substring(word)) ?: return null
            return text.substring(0, pos) to value
        }

        /** `utf8::validate`: null when the bytes are not valid UTF-8. */
        private fun decodeStrict(decoder: CharsetDecoder, bytes: ByteArray, from: Int, to: Int): String? = try {
            decoder.reset().decode(ByteBuffer.wrap(bytes, from, to - from)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
