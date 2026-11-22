/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * Protocol Buffers' wire format read by hand, without a schema: a message is its fields by number, a length-delimited
 * one read as a message, a string or bytes as the caller asks. A skin's message is hostile input: it is held to
 * [MAX_DEPTH] nested messages, [MAX_FIELDS] fields in all, varints of at most 10 bytes and lengths within the
 * message around them; past any of them, or on a group (wire types 3 and 4), it is a broken skin.
 */
class ProtoWire(private val bytes: ByteArray) {
    private var fields = 0L

    /** The whole of [bytes] as a message. */
    val root: Message by lazy(LazyThreadSafetyMode.NONE) { Message(0, bytes.size, 1) }

    class Field(val number: Int, val type: Int, val value: Long, val from: Int, val to: Int)

    inner class Message internal constructor(private val from: Int, private val to: Int, private val depth: Int) {
        /** Its fields in the order written; read the first time asked. */
        val fields: List<Field> by lazy(LazyThreadSafetyMode.NONE) { read() }

        // a skin's style is looked up once for each key that names it: by number, not a scan of all its fields each time
        private val byNumber: Map<Int, List<Field>> by lazy(LazyThreadSafetyMode.NONE) { fields.groupBy { it.number } }

        // a nested message read once, however often asked for
        private val children = HashMap<Int, Message>()

        /** The last [number] (protobuf's rule for a field written twice). */
        fun field(number: Int): Field? = byNumber[number]?.last()

        fun all(number: Int): List<Field> = byNumber[number].orEmpty()

        fun long(number: Int): Long? = field(number)?.takeIf { it.type == VARINT || it.type == FIXED32 || it.type == FIXED64 }?.value

        /** As protobuf's int32, uint32 and enums are: the low 32 bits. */
        fun int(number: Int): Int? = long(number)?.toInt()

        fun float(number: Int): Float? = field(number)?.takeIf { it.type == FIXED32 }?.let { Float.fromBits(it.value.toInt()) }

        fun string(number: Int): String? = field(number)?.let(::string)

        fun string(f: Field): String? = if (f.type == LENGTH) bytes.decodeToString(f.from, f.to) else null

        fun message(number: Int): Message? = field(number)?.let(::message)

        fun message(f: Field): Message? = if (f.type == LENGTH) child(f) else null

        fun messages(number: Int): List<Message> = all(number).mapNotNull(::message)

        private fun child(f: Field): Message {
            if (depth >= MAX_DEPTH) throw broken("nested past $MAX_DEPTH")
            return children.getOrPut(f.from) { Message(f.from, f.to, depth + 1) }
        }

        private fun read(): List<Field> {
            val out = ArrayList<Field>()
            var at = from
            while (at < to) {
                ensure(++this@ProtoWire.fields <= MAX_FIELDS) { "more than $MAX_FIELDS fields" }
                val (tag, afterTag) = varint(at, to)
                at = afterTag
                val number = (tag ushr TYPE_BITS).toInt()
                val type = (tag and TYPE_MASK).toInt()
                ensure(number > 0 && tag ushr TYPE_BITS <= Int.MAX_VALUE) { "field number $tag" }
                out += when (type) {
                    VARINT -> varint(at, to).let { (v, next) -> at = next; Field(number, type, v, 0, 0) }
                    FIXED64 -> Field(number, type, fixed(at, FIXED64_BYTES), 0, 0).also { at += FIXED64_BYTES }
                    FIXED32 -> Field(number, type, fixed(at, FIXED32_BYTES), 0, 0).also { at += FIXED32_BYTES }
                    LENGTH -> {
                        val (n, start) = varint(at, to)
                        ensure(n >= 0 && n <= to - start) { "length $n past its message" }
                        at = start + n.toInt()
                        Field(number, type, n, start, at)
                    }
                    else -> throw broken("wire type $type")
                }
            }
            return out
        }

        private fun fixed(at: Int, n: Int): Long {
            if (to - at < n) throw broken("cut short")
            var v = 0L
            for (i in n - 1 downTo 0) v = (v shl BYTE_BITS) or (bytes[at + i].toLong() and BYTE)
            return v
        }
    }

    // a varint at [at] and where it ends, within [end]
    private fun varint(at: Int, end: Int): Pair<Long, Int> {
        var v = 0L
        var i = at
        var shift = 0
        while (true) {
            if (i >= end) throw broken("cut short")
            if (i - at == MAX_VARINT_BYTES) throw broken("varint past $MAX_VARINT_BYTES bytes")
            val b = bytes[i++].toLong() and BYTE
            v = v or ((b and SEVEN_BITS) shl shift)
            if (b and CONTINUES == 0L) return v to i
            shift += SEVEN
        }
    }

    private fun broken(why: String) = SkinError(SkinError.Reason.BROKEN, "protobuf: $why")

    private inline fun ensure(ok: Boolean, why: () -> String) {
        if (!ok) throw broken(why())
    }

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_FIELDS = 200_000L
        const val MAX_VARINT_BYTES = 10

        const val VARINT = 0
        const val FIXED64 = 1
        const val LENGTH = 2
        const val FIXED32 = 5

        private const val TYPE_BITS = 3
        private const val TYPE_MASK = 7L
        private const val FIXED64_BYTES = 8
        private const val FIXED32_BYTES = 4
        private const val BYTE_BITS = 8
        private const val BYTE = 0xFFL
        private const val SEVEN_BITS = 0x7FL
        private const val CONTINUES = 0x80L
        private const val SEVEN = 7
    }
}
