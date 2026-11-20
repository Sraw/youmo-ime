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

/** fcitx's PkgData: the user's (`files/data`) first, then the app's. Paths relative, `/`-separated. */
interface PkgDataDir {
    fun read(path: String): ByteArray?

    /** Null or empty when there are none. */
    fun list(dir: String): Collection<String>?
}

class FilePkgDataDir(private val root: File) : PkgDataDir {
    override fun read(path: String): ByteArray? = File(root, path).takeIf { it.isFile }?.readBytes()

    override fun list(dir: String): Collection<String>? =
        File(root, dir).listFiles()?.filter { it.isFile }?.map { it.name }
}

/**
 * fcitx's `BuiltInQuickPhraseProvider`: `data/QuickPhrase.mb` from the first directory that has it, then each
 * `data/quickphrase.d/<name>.mb` (an earlier directory's name hides a later's; `<name>.disable` anywhere skips it).
 * Lines trimmed, valid UTF-8, key up to whitespace, value through `unescapeForValue`. A multimap in code point order.
 */
class QuickPhraseTable private constructor(private val map: TreeMap<String, MutableList<String>>) {

    /** What [populate] gives for the empty input. */
    fun entries(): Sequence<Pair<String, String>> = map.asSequence().flatMap { (key, values) -> values.asSequence().map { key to it } }

    /** In table order. */
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

        /** Code point order is UTF-8 byte order, what `std::string` compares. */
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

        /** What the host starts with when no file reads. */
        val EMPTY = QuickPhraseTable(TreeMap(CODE_POINT_ORDER))

        /** The user's first. */
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

        /** Lines [load] skips left out. */
        fun parse(bytes: ByteArray): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            eachLine(bytes) { out += it }
            return out
        }

        /** Lines [load] skips only for having no whitespace go to [bare] in place: the user's file marks its version and folders so ([QuickPhraseFiles]). */
        internal fun parse(bytes: ByteArray, bare: (String) -> Unit, each: (Pair<String, String>) -> Unit) =
            eachLine(bytes, bare, each)

        /** `std::filesystem::path::extension`: from the last dot, unless the name starts with it. */
        private fun extension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(dot) else ""
        }

        private fun isWhitespace(b: Byte) = b == ' '.code.toByte() || b in 0x09..0x0d

        /** `load`, line by line on `\n`. */
        private fun loadInto(map: TreeMap<String, MutableList<String>>, bytes: ByteArray) =
            eachLine(bytes) { (key, value) -> map.getOrPut(key) { ArrayList(1) }.add(value) }

        private inline fun eachLine(bytes: ByteArray, noinline bare: ((String) -> Unit)? = null, each: (Pair<String, String>) -> Unit) {
            // one decoder for the file: one per line was most of a cold start
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            var start = 0
            while (start < bytes.size) {
                var end = start
                while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
                parseLine(decoder, bytes, start, end, bare)?.let(each)
                start = end + 1
            }
        }

        @Suppress("ReturnCount")
        private fun parseLine(decoder: CharsetDecoder, bytes: ByteArray, from: Int, to: Int, bare: ((String) -> Unit)?): Pair<String, String>? {
            var s = from
            var e = to
            while (s < e && isWhitespace(bytes[s])) s++
            while (e > s && isWhitespace(bytes[e - 1])) e--
            if (s == e) return null
            val text = decodeStrict(decoder, bytes, s, e) ?: return null
            val pos = text.indexOfFirst { it.code < 0x80 && isWhitespace(it.code.toByte()) }
            if (pos < 0) {
                bare?.invoke(text)
                return null
            }
            val word = (pos until text.length).firstOrNull { !(text[it].code < 0x80 && isWhitespace(text[it].code.toByte())) }
                ?: return null
            val value = FcitxUtils.unescapeForValue(text.substring(word)) ?: return null
            return text.substring(0, pos) to value
        }

        /** `utf8::validate`: null when not valid UTF-8. */
        private fun decodeStrict(decoder: CharsetDecoder, bytes: ByteArray, from: Int, to: Int): String? = try {
            decoder.reset().decode(ByteBuffer.wrap(bytes, from, to - from)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
