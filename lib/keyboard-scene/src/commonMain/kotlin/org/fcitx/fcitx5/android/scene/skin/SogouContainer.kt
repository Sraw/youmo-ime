/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * Sogou's encrypted skin container: `Skin`, a version, then AES-CBC over a length and zlib data, which the platform
 * opens ([SkinPlatform.sogouPlain]). The payload: a word not read, the index's bytes, the index (each a word, an
 * offset from the payload's start), and at each offset a name's bytes, the name (UTF-16LE), the data's bytes and the
 * data. All little-endian. Held to [SkinLimits] as a ZIP's files are; names are only keys.
 */
object SogouContainer {
    private const val NAME_MAX = 260
    private const val HEAD = 8
    private const val WORD = 4
    private const val BYTE = 0xFF
    private const val BITS = 8

    fun isContainer(b: ByteArray): Boolean = b.size >= WORD && b[0] == 'S'.code.toByte() && b[1] == 'k'.code.toByte() &&
        b[2] == 'i'.code.toByte() && b[3] == 'n'.code.toByte()

    /**
     * [b]'s files by name, `/`-separated, the first of a name twice; [budget] what is left of the whole that may be unpacked
     * (less a ZIP's other files where the container is in one).
     *
     * @throws SkinError [SkinError.Reason.SOGOU_ENCRYPTED] where it does not open or its index does not hold, or a
     *   limit's ([SkinError.Reason.UNPACKED_TOO_BIG] too where [budget] is short of the payload) or [SkinError.Reason.UNSAFE_NAME]
     */
    fun open(b: ByteArray, platform: SkinPlatform, budget: Long = SkinLimits.MAX_TOTAL_BYTES): Map<String, ByteArray> {
        if (!isContainer(b)) broken()
        val payload = platform.sogouPlain(b, minOf(budget, SkinLimits.MAX_TOTAL_BYTES).coerceAtLeast(0).toInt()) ?: broken()
        return entries(payload, budget)
    }

    /** The payload's files ([open] without the cipher), all of them [budget] at most. */
    fun entries(p: ByteArray, budget: Long = SkinLimits.MAX_TOTAL_BYTES): Map<String, ByteArray> {
        val most = minOf(budget, SkinLimits.MAX_TOTAL_BYTES)
        val index = word(p, WORD)
        if (index % WORD != 0L || HEAD + index > p.size) broken()
        val count = (index / WORD).toInt()
        if (count > SkinLimits.MAX_ENTRIES) fail(SkinError.Reason.TOO_MANY_FILES)
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for (i in 0 until count) {
            val at = word(p, HEAD + WORD * i)
            val nameBytes = word(p, at)
            if (nameBytes % 2 != 0L || nameBytes > 2L * NAME_MAX) unsafe("#${i + 1}")
            val dataAt = at + WORD + nameBytes
            val size = word(p, dataAt)
            val from = dataAt + WORD
            if (from + size > p.size) broken()
            if (size > SkinLimits.MAX_ENTRY_BYTES) fail(SkinError.Reason.UNPACKED_TOO_BIG)
            // an index may point at the same data again and again: counted each time, as each is a copy
            total += size
            if (total > most) fail(SkinError.Reason.UNPACKED_TOO_BIG)
            val name = name(p, (at + WORD).toInt(), nameBytes.toInt()) ?: continue
            if (name !in out) out[name] = p.copyOfRange(from.toInt(), (from + size).toInt())
        }
        if (out.isEmpty()) broken()
        return out
    }

    // `\` as `/`, none leading; null for a folder's; a `..` part, a control character or past NAME_MAX refused
    private fun name(p: ByteArray, at: Int, len: Int): String? {
        val raw = CharArray(len / 2) { ((p[at + 2 * it].toInt() and BYTE) or ((p[at + 2 * it + 1].toInt() and BYTE) shl BITS)).toChar() }.concatToString()
        val parts = raw.replace('\\', '/').split('/')
        if (parts.any { it == ".." } || raw.any { it < ' ' }) unsafe(raw)
        val name = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return name.takeUnless { it.isEmpty() || raw.endsWith('/') || raw.endsWith('\\') }
    }

    // a u32 at [at], or past the end (so any bound after it fails)
    private fun word(p: ByteArray, at: Long): Long {
        if (at < 0 || at + WORD > p.size) broken()
        val i = at.toInt()
        return (0 until WORD).fold(0L) { v, k -> v or ((p[i + k].toLong() and BYTE.toLong()) shl (BITS * k)) }
    }

    private fun word(p: ByteArray, at: Int): Long = word(p, at.toLong())

    private fun fail(reason: SkinError.Reason): Nothing = throw SkinError(reason)

    private fun broken(): Nothing = fail(SkinError.Reason.SOGOU_ENCRYPTED)

    private fun unsafe(name: String): Nothing = throw SkinError(SkinError.Reason.UNSAFE_NAME, name)
}
