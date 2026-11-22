/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text

/**
 * Sogou containers made here: the payload as Sogou lays it out, sealed by a stand-in cipher (each byte XOR [MASK], no
 * zlib) that [Sealed] opens as the app's AES and inflater would, with the same checks on the stated length.
 */
object SogouFixtures {
    const val MASK = 0x5A

    /** The fake platform with [SogouSkin]'s cipher; [asked] is the last `maxBytes` it was given. */
    class Sealed(private val inner: SkinFixtures.Platform = SkinFixtures.Platform()) : SkinPlatform by inner {
        var asked = -1

        override fun sogouPlain(container: ByteArray, maxBytes: Int): ByteArray? {
            asked = maxBytes
            if (container.size < HEAD + 4 || !SogouContainer.isContainer(container)) return null
            val plain = ByteArray(container.size - HEAD) { (container[HEAD + it].toInt() xor MASK).toByte() }
            val raw = u32(plain, 0)
            if (raw != plain.size - 4L) return null
            if (raw > maxBytes) throw SkinError(SkinError.Reason.UNPACKED_TOO_BIG)
            return plain.copyOfRange(4, plain.size)
        }
    }

    private const val HEAD = 8

    fun u32(v: Long): ByteArray = ByteArray(4) { (v shr (8 * it)).toByte() }

    fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, k -> v or ((b[at + k].toLong() and 0xFF) shl (8 * k)) }

    private fun utf16(s: String) = ByteArray(2 * s.length) { (s[it / 2].code shr (8 * (it % 2))).toByte() }

    /**
     * Sogou's payload of [entries] in order, the index of [slots] offsets as [index] makes them from the entries' own.
     */
    fun payload(entries: List<Pair<String, ByteArray>>, slots: Int = entries.size, index: (List<Long>) -> List<Long> = { it }): ByteArray {
        val bodies = entries.map { (name, data) -> u32(2L * name.length) + utf16(name) + u32(data.size.toLong()) + data }
        var at = 8L + 4L * slots
        val offsets = bodies.map { b -> at.also { at += b.size } }
        val list = index(offsets)
        check(list.size == slots)
        val out = ByteArray(at.toInt())
        u32(4L * slots).copyInto(out, 4)
        list.forEachIndexed { i, o -> u32(o).copyInto(out, 8 + 4 * i) }
        var to = 8 + 4 * slots
        for (b in bodies) {
            b.copyInto(out, to)
            to += b.size
        }
        return out
    }

    /** [payload] sealed as a container of [version]; [stated] its length as said, if not the true one. */
    fun seal(payload: ByteArray, version: Long = 3, stated: Long = payload.size.toLong()): ByteArray {
        val plain = u32(stated) + payload
        return "Skin".encodeToByteArray() + u32(version) + ByteArray(plain.size) { (plain[it].toInt() xor MASK).toByte() }
    }

    fun container(files: Map<String, ByteArray>): ByteArray = seal(payload(files.toList()))

    /** [files] with [ini] added to each period's phoneSkin.ini. */
    fun phoneSkin(files: MutableMap<String, ByteArray>, ini: String): MutableMap<String, ByteArray> {
        for (k in files.keys.filter { it.endsWith("phoneSkin.ini") }) files[k] = text(files.getValue(k).decodeToString() + ini)
        return files
    }

    /** A 26.ini as Sogou's own skins write one (fractions of the keyboard's width and height). */
    val QWERTY = """
        [Keyboard]
        W=1.0
        H=0.6
        PADDINGS=0,0,0,0
        ROWS=ROW1,ROW2,ROW3,ROW4
        H_GAP_QWERTY=0.01
        [ROW1]
        V_OFFSET=0.01
        H_OFFSET=0.005
        KEYS=Key_Q,Key_W,Key_E,Key_R,Key_T,Key_Y,Key_U,Key_I,Key_O,Key_P
        [ROW2]
        V_OFFSET=0.26
        H_OFFSET=0.055
        KEYS=Key_A,Key_S_PY,Key_D,Key_F,Key_G,Key_H_PY,Key_J_PY,Key_K,Key_L_PY
        [ROW3]
        V_OFFSET=0.51
        H_OFFSET=0.005
        KEYS=Key_Apostrophe_Shift,Key_Z,Key_X,Key_C,Key_V,Key_B,Key_N,Key_M,Key_Backspace_Qwerty
        [ROW4]
        V_OFFSET=0.76
        H_OFFSET=0.005
        KEYS=Key_SymbolTable_Qwerty,Key_Symbols_Qwerty,Key_UDSymbol1_Qwerty,Key_Space_Qwerty,Key_UDSymbol2_Qwerty,Key_SwitchToEN_Qwerty,Key_Enter_Qwerty
        [Key]
        W=0.09
        H=0.23
        [Key_Apostrophe_Shift]
        W=0.14
        [Key_Backspace_Qwerty]
        W=0.14
        [Key_SymbolTable_Qwerty]
        W=0.14
        [Key_Symbols_Qwerty]
        W=0.11
        [Key_Space_Qwerty]
        W=0.25
        [Key_SwitchToEN_Qwerty]
        W=0.11
        [Key_Enter_Qwerty]
        W=0.14
    """.trimIndent()

    /** A 9.ini as Sogou's own skins write one: the left column (CandidateCodeView) is not a key. */
    val NINE = """
        [Keyboard]
        W=1
        H=0.6
        ROWS=ROW1,ROW2,ROW3,ROW4
        [ROW1]
        V_OFFSET=0.01
        H_OFFSET=0.17
        KEYS=Key_PunctPY,Key_ABC,Key_DEF,Key_Backspace
        [ROW2]
        V_OFFSET=0.26
        H_OFFSET=0.17
        KEYS=Key_GHI,Key_JKL,Key_MNO,Key_ClearInput_NewLine
        [ROW3]
        V_OFFSET=0.51
        H_OFFSET=0.17
        KEYS=Key_PQRS,Key_TUV,Key_WXYZ,Key_Edits
        [ROW4]
        V_OFFSET=0.76
        H_OFFSET=0.005
        KEYS=Key_SymbolTable,Key_Digits,Key_Space_py9,Key_SwitchToEN,Key_Enter
        [CandidateCodeView]
        W=0.17
        H=0.75
        [Key]
        W=0.22
        H=0.24
        [Key_Backspace]
        W=0.17
        [Key_ClearInput_NewLine]
        W=0.17
        [Key_Edits]
        W=0.17
        [Key_SymbolTable]
        W=0.17
        [Key_Digits]
        W=0.16
        [Key_Space_py9]
        W=0.33
        [Key_SwitchToEN]
        W=0.16
        [Key_Enter]
        W=0.17
    """.trimIndent()
}
