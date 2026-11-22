/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.face
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.gradient
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.img
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text

/**
 * Baidu skins made here: the classic one of [SkinFixtures.baidu] changed file by file, and a `.bda` whose
 * `port/appearanceConfig` is written by [Proto], a protobuf writer of the tests' own.
 */
object BaiduFixtures {
    /** Protobuf's wire format written field by field. */
    class Proto {
        private val out = ArrayList<Byte>()

        private fun raw(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out += ((x and 0x7F) or 0x80).toByte()
                x = x ushr 7
            }
            out += x.toByte()
        }

        private fun tag(n: Int, type: Int) = raw((n.toLong() shl 3) or type.toLong())

        fun varint(n: Int, v: Long) { tag(n, ProtoWire.VARINT); raw(v) }

        // an int32 as protobuf writes one: a negative one in ten bytes
        fun varint(n: Int, v: Int) = varint(n, v.toLong())

        /** An ARGB colour as a u32. */
        fun color(n: Int, argb: Int) = varint(n, argb.toLong() and 0xFFFFFFFFL)

        fun fixed64(n: Int, v: Long) { tag(n, ProtoWire.FIXED64); for (i in 0 until 8) out += (v ushr (8 * i)).toByte() }

        fun fixed32(n: Int, v: Int) { tag(n, ProtoWire.FIXED32); for (i in 0 until 4) out += (v ushr (8 * i)).toByte() }

        fun bytes(n: Int, b: ByteArray) { tag(n, ProtoWire.LENGTH); raw(b.size.toLong()); for (x in b) out += x }

        fun string(n: Int, s: String) = bytes(n, s.encodeToByteArray())

        fun message(n: Int, block: Proto.() -> Unit) = bytes(n, proto(block))

        fun toByteArray() = out.toByteArray()
    }

    fun proto(block: Proto.() -> Unit): ByteArray = Proto().apply(block).toByteArray()

    /** [name]'s text with [old] made [new] (each once at least). */
    fun MutableMap<String, ByteArray>.edit(name: String, old: String, new: String) {
        val s = getValue(name).decodeToString()
        check(old in s) { "$old not in $name" }
        this[name] = text(s.replace(old, new))
    }

    fun MutableMap<String, ByteArray>.append(name: String, more: String) {
        this[name] = text((this[name]?.decodeToString() ?: "") + more)
    }

    /** [SkinFixtures.baidu]'s letters labelled by colour (style 10, its mark by style 11), as [styles] adds to them. */
    fun labelled(styles: String = "", root: String = "", files: MutableMap<String, ByteArray> = SkinFixtures.baidu()): MutableMap<String, ByteArray> {
        files.append(root + "port/res/default.css", "[STYLE10]\nNM_COLOR=112233\nHL_COLOR=ff0000\n$styles\n[STYLE11]\nNM_COLOR=80445566\nHL_COLOR=80aa0000\n")
        files.edit(root + "port/py_26.ini", "FORE_STYLE=8\nCENTER=", "FORE_STYLE=10,11\nCENTER=")
        return files
    }

    // SkinStyle's types; the style numbers of [bda]'s
    const val IMAGE = 0
    const val COLOR = 1
    const val TEXT = 2
    const val FACE = 1
    const val FUNCTION_FACE = 5
    const val BACKSPACE_ICON = 2
    const val ENTER_ICON = 3
    const val PANEL = 4
    const val PLACEHOLDER = 6
    const val EXPAND_ICON = 7
    const val GIF = 8
    const val BLANK = 9
    const val LETTER_TEXT = 1
    const val MARK_TEXT = 2
    const val FUNCTION_TEXT = 3
    const val SPACE_COLOR = 1

    const val LETTER = 0xFF223344.toInt()
    const val LETTER_PRESSED = 0xFFFF0000.toInt()
    const val MARK = 0x80112233.toInt()
    const val MARK_PRESSED = 0x80445566.toInt()
    const val FUNCTION = 0xFF555555.toInt()
    const val SPACE = 0xFFDDDDDD.toInt()
    const val SPACE_PRESSED = 0xFFB4B9C2.toInt()
    const val LABEL = 0xFF010203.toInt()
    const val BRAND = 0xFF0000FF.toInt()
    const val BACKGROUND = 0xFF00FF00.toInt()
    const val FACE_COLOR = 0xFFFFFFFF.toInt()
    const val FACE_PRESSED = 0xFF4477FF.toInt()
    const val ICON = 0xFF102030.toInt()
    const val ICON_PRESSED = 0xFFFFFFFF.toInt()

    private fun Proto.style(n: Int, type: Int, key: Int) = message(n) {
        if (type != IMAGE) varint(1, type)
        varint(2, key)
    }

    private fun Proto.atom(n: Int, id: String, type: Int = 0, inner: IntArray? = null) = message(n) {
        message(1) {
            if (type != 0) varint(1, type)
            string(2, id)
        }
        if (inner != null) {
            message(2) {
                message(1) { varint(1, inner[0]); varint(2, inner[1]) }
                message(2) { varint(1, inner[2]); varint(2, inner[3]) }
            }
        }
    }

    private fun Proto.image(key: Int, normal: String, pressed: String? = null, type: Int = 0, inner: IntArray? = null) = message(1) {
        varint(1, key)
        message(2) {
            atom(1, normal, type, inner)
            if (pressed != null) atom(2, pressed, type, inner)
        }
    }

    private fun Proto.text(key: Int, size: Int, normal: Int?, pressed: Int?, font: String = "") = message(2) {
        varint(1, key)
        message(2) {
            if (font.isNotEmpty()) string(2, font)
            varint(3, size)
            normal?.let { color(4, it) }
            pressed?.let { color(5, it) }
        }
    }

    private fun Proto.key(name: String, back: Pair<Int, Int>, vararg fores: Pair<Int, Int>) = message(3) {
        string(1, name)
        message(2) {
            style(1, back.first, back.second)
            for (f in fores) style(2, f.first, f.second)
        }
    }

    private fun icon(color: Int) = Pixels(48, 48).also { it.paste(Pixels.filled(30, 24, color), 9, 12) }

    /**
     * A `.bda`: `py_26` of three letters (a mark each) and five function keys, pictures of faces stretched by an inner
     * rect, icons (shift's a 1×1 placeholder), a palette ([palette]), a GIF resource ([gif]), a font ([font]); [blank]
     * more of a transparent picture [blankSide] across: a Q key each after the letters, a fore each before backspace's icon.
     */
    fun bda(
        gif: Boolean = false, palette: Boolean = true, font: Boolean = false, letters: String = "QWE", blank: Int = 0, blankSide: Int = 1000,
    ): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["Info.txt"] = text("Name=Plum\nSupportPlatform=A\n")
        files["res/10.png"] = img(face(120, 150, 6, FACE_COLOR))
        files["res/11.png"] = img(face(120, 150, 6, FACE_PRESSED))
        files["res/12.png"] = img(face(120, 150, 6, 0xFF8899AA.toInt()))
        files["res/20.png"] = img(icon(ICON))
        files["res/21.png"] = img(icon(ICON_PRESSED))
        files["res/30.png"] = img(icon(ICON))
        files["res/40.png"] = img(icon(ICON))
        files["res/50.png"] = img(gradient(1080, 600, 0xFFE0E8F0.toInt(), 0xFF8090A0.toInt()))
        files["res/1.png"] = img(Pixels(1, 1))
        if (gif) files["res/60.gif"] = text("GIF89a")
        if (blank > 0) files["res/70.png"] = img(Pixels(blankSide, blankSide))
        files["port/appearanceConfig"] = proto {
            image(FACE, "10", "11", inner = intArrayOf(30, 30, 60, 90))
            image(BACKSPACE_ICON, "20", "21")
            image(ENTER_ICON, "30")
            image(PANEL, "50")
            image(FUNCTION_FACE, "12", "11", inner = intArrayOf(30, 30, 60, 90))
            image(PLACEHOLDER, "1")
            image(EXPAND_ICON, "40")
            if (gif) image(GIF, "60", type = 1)
            if (blank > 0) image(BLANK, "70")
            text(LETTER_TEXT, 64, LETTER, LETTER_PRESSED, font = if (font) "Made Sans" else "")
            text(MARK_TEXT, 27, MARK, MARK_PRESSED)
            text(FUNCTION_TEXT, 40, FUNCTION, null)
            message(3) {
                varint(1, SPACE_COLOR)
                message(2) { color(1, SPACE); color(2, SPACE_PRESSED) }
            }
            message(4) {
                string(1, "py_26")
                message(2) {
                    // a state's and a variant's, first: not the key's own look
                    key("KEY_Q_S4", IMAGE to FUNCTION_FACE, TEXT to FUNCTION_TEXT)
                    key("KEY_F38_S34_14", IMAGE to FUNCTION_FACE)
                    for (c in letters) key("KEY_$c", IMAGE to FACE, TEXT to LETTER_TEXT, TEXT to MARK_TEXT)
                    repeat(blank) { key("KEY_Q", IMAGE to FACE, IMAGE to BLANK) }
                    key("KEY_F36", IMAGE to FUNCTION_FACE, *Array(blank) { IMAGE to BLANK }, IMAGE to BACKSPACE_ICON)
                    key("KEY_F39", IMAGE to FUNCTION_FACE, IMAGE to ENTER_ICON, TEXT to FUNCTION_TEXT)
                    key("KEY_F38", COLOR to SPACE_COLOR, TEXT to FUNCTION_TEXT)
                    key("KEY_F11", IMAGE to FUNCTION_FACE, IMAGE to PLACEHOLDER)
                    key("KEY_F6", IMAGE to FUNCTION_FACE, TEXT to FUNCTION_TEXT)
                    if (gif) key("KEY_F1", IMAGE to FUNCTION_FACE, IMAGE to GIF)
                    message(4) {
                        style(4, TEXT, LETTER_TEXT)
                        style(5, TEXT, FUNCTION_TEXT)
                        message(10) {
                            string(1, "CAND_F9")
                            message(2) { style(1, COLOR, SPACE_COLOR); style(2, IMAGE, EXPAND_ICON) }
                        }
                    }
                    style(7, IMAGE, PANEL)
                }
            }
            if (palette) message(5) { color(1, LABEL); color(5, BRAND); color(6, BACKGROUND) }
            varint(6, 1080)
        }
        return files
    }
}
