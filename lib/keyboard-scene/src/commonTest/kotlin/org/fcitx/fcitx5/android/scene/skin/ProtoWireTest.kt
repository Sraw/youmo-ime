/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.BaiduFixtures.proto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ProtoWireTest {
    private fun broken(bytes: ByteArray, read: (ProtoWire.Message) -> Unit = { it.fields }) {
        assertEquals(SkinError.Reason.BROKEN, assertFailsWith<SkinError> { read(ProtoWire(bytes).root) }.reason)
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun theFourWireTypesAreRead() {
        val m = ProtoWire(
            proto {
                varint(1, 150)
                fixed64(2, 0x0102030405060708L)
                string(3, "键")
                fixed32(4, 1.5f.toRawBits())
                varint(5, -1)
                message(6) { varint(1, 7) }
            },
        ).root
        assertEquals(150, m.int(1))
        assertEquals(0x0102030405060708L, m.long(2))
        assertEquals("键", m.string(3))
        assertEquals(1.5f, m.float(4))
        assertEquals(-1, m.int(5))
        assertEquals(7, m.message(6)?.int(1))
        // asked as what it is not: none
        assertNull(m.string(1))
        assertNull(m.message(1))
        assertNull(m.float(1))
        assertNull(m.int(3))
    }

    @Test
    fun aFieldWrittenTwiceIsItsLastAndUnknownOnesAreSkipped() {
        val m = ProtoWire(
            proto {
                varint(1, 1)
                string(99, "skip")
                fixed64(100, 5)
                message(101) { varint(1, 1) }
                varint(1, 2)
                message(2) { varint(1, 3) }
                message(2) { varint(1, 4) }
            },
        ).root
        assertEquals(2, m.int(1))
        assertEquals(listOf(3, 4), m.messages(2).map { it.int(1) })
        assertEquals(listOf(1, 99, 100, 101, 1, 2, 2), m.fields.map { it.number })
    }

    private fun nested(levels: Int): ByteArray {
        var inner = proto { varint(2, 1) }
        repeat(levels - 1) { val m = inner; inner = proto { bytes(1, m) } }
        return inner
    }

    @Test
    fun messagesNestedPast32AreBroken() {
        fun down(m: ProtoWire.Message, levels: Int): ProtoWire.Message = (1 until levels).fold(m) { at, _ -> at.message(1)!! }
        assertEquals(1, down(ProtoWire(nested(ProtoWire.MAX_DEPTH)).root, ProtoWire.MAX_DEPTH).int(2))
        broken(nested(ProtoWire.MAX_DEPTH + 1)) { down(it, ProtoWire.MAX_DEPTH + 1) }
    }

    @Test
    fun aLengthPastItsMessageIsBroken() {
        // 10 bytes said, 3 there
        broken(bytes(0x0A, 10, 1, 2, 3))
        // within the whole, past the message around it
        val outer = bytes(0x0A, 3, 0x0A, 5, 0)
        broken(outer) { it.message(1)!!.fields }
    }

    @Test
    fun aVarintOfElevenBytesIsBroken() {
        broken(bytes(0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01))
        // ten: -1, as an int32 is written
        assertEquals(-1L, ProtoWire(bytes(0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)).root.long(1))
    }

    @Test
    fun aMessageCutShortIsBroken() {
        broken(bytes(0x08, 0x96))
        broken(bytes(0x0D, 1, 2))
        broken(bytes(0x09, 1, 2, 3, 4, 5, 6, 7))
        broken(bytes(0x0A))
        broken(bytes(0x80))
    }

    @Test
    fun groupsAndUnknownWireTypesAreBroken() {
        for (type in listOf(3, 4, 6, 7)) broken(bytes(0x08 or type, 0))
        // field number 0
        broken(bytes(0x00, 0))
    }

    @Test
    fun nothingIsAMessageOfNoFields() {
        assertEquals(0, ProtoWire(ByteArray(0)).root.fields.size)
    }

    @Test
    fun aBigMessageIsLookedUpByNumber() {
        val m = ProtoWire(
            proto {
                varint(3, 7)
                message(4) { varint(1, 1) }
                repeat(100_000) { varint(99, 0) }
            },
        ).root
        val start = TimeSource.Monotonic.markNow()
        // a scan of its fields each time: minutes
        repeat(100_000) { assertEquals(7, m.int(3)) }
        assertTrue(start.elapsedNow() < 2.seconds, "${start.elapsedNow()}")
        assertSame(m.message(4), m.message(4))
    }

    @Test
    fun fieldsPastTheLimitAreBroken() {
        val most = proto { repeat(ProtoWire.MAX_FIELDS.toInt()) { varint(1, 0) } }
        assertEquals(ProtoWire.MAX_FIELDS.toInt(), ProtoWire(most).root.fields.size)
        broken(most + bytes(8, 0))
        // nested ones counted too
        broken(proto { message(1) { repeat(ProtoWire.MAX_FIELDS.toInt()) { varint(1, 0) } } }) { it.message(1)!!.fields }
    }
}
