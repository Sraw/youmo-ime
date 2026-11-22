/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.face
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.gradient
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.img

/**
 * An iFlytek shop skin made here, laid out as the shop's are: one folder, `theme_info.ini`, a folder per size
 * (`layout/style.ini`, `layout/image.ini`, `res/`), the keyboards' `layout/<keyboard>/…`, `\r\n` lines with `#`
 * comments, UTF-8 with a byte-order mark. A deep blue board, white letters, a pressed face lighter than the key.
 */
object XunfeiFixtures {
    const val TOP = "chunri01/"
    const val BLUE = 0xFF0D1B3E.toInt()
    const val KEY = 0xFF2A3F73.toInt()
    const val KEY_PRESSED = 0xFF6A8FE0.toInt()
    const val FUNCTION = 0xFF1C2C55.toInt()
    const val FUNCTION_PRESSED = 0xFF8090A0.toInt()
    const val CAND = 0xFF14244A.toInt()
    const val BALLOON = 0xFF3355AA.toInt()

    /** The letters' colour at [size]: each size its own, to tell which was read. */
    fun letterColor(size: Int): Int = if (size == 720) 0xFFFFFFFF.toInt() else 0xFFEEEEEE.toInt()

    /** The styles every size has, by section; a test edits them through [store]'s `edit`. */
    private fun styles(size: Int): LinkedHashMap<String, MutableList<String>> = linkedMapOf(
        "Style_Cand_Word_1_Focused" to mutableListOf("TEXT_COLOR=0xFF3FC1E8"),
        "Style_Cand_Word_1" to mutableListOf("TEXT_COLOR=0xFFF0E68C"),
        "Style_Keyboard_Key_Fore_2" to mutableListOf("TEXT_COLOR=0x" + hex(letterColor(size))),
        "Style_Keyboard_Key_Fore_2_Press" to mutableListOf("TEXT_COLOR=0xFF0D1B3E"),
        "Style_Keyboard_Key_Fore_1" to mutableListOf("TEXT_COLOR=0xFFB0C4FF"),
        "Style_Composing_Key_Fixed" to mutableListOf("TEXT_COLOR=0xFFFFCC00"),
        "Style_Composing_Key" to mutableListOf("BG_COLOR=0xFE4A2A6B"),
        "Style_Balloon_Amplify" to mutableListOf("IMAGE=Image_Bubble", "TEXT_COLOR=0xFF112233"),
        "Style_Cand_Default" to mutableListOf("IMAGE=Image_Bar"),
        "Style_Keyboard_Key_2" to mutableListOf("IMAGE=Image_Key"),
        "Style_Keyboard_Key_1" to mutableListOf("IMAGE=Image_Fn"),
        "Style_Keyboard_Key_2_Press" to mutableListOf("IMAGE=Image_Key_Down"),
        "Style_Py_9" to mutableListOf("IMAGE=Image_Layout_Bg", "#BG_COLOR=0xFF22446A"),
    )

    private fun hex(argb: Int) = (argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

    // the image sections at [size] (its pixels [k] times 240's), in image.ini's text
    private fun images(k: Int, preview: Boolean): String = buildString {
        append("[Image_Bar]\nSRC=bar.png\nX_DIVS=0,1\nY_DIVS=${2 * k},${47 * k}\n\n")
        append("[Image_Layout_Bg]\nSRC=board.png\n\n")
        append("#键\n[Image_Key]\nSRC=key.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n")
        append("[Image_Fn]\nSRC=fn.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n")
        append("[Image_Key_Down]\nSRC=key_down.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n")
        append("[Image_Fn_Down]\nSRC=fn_down.png\n\n")
        append("#气泡\n[Image_Bubble]\nSRC=bubble.png\n#NOT_READ=1\nX_DIVS=${15 * k},${35 * k}\nY_DIVS=${15 * k},${40 * k}\n\n")
        if (preview) append("[Image_Shot]\nSRC=shot.png\n")
    }

    /** Text as the shop writes it: `\r\n`, UTF-8 with a byte-order mark. */
    fun utf8(s: String): ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.replace("\n", "\r\n").encodeToByteArray()

    /**
     * The skin's files under [top] ("" for none): [sizes] each a full folder; [partial] sizes with only an image.ini
     * and pictures (a size the shop overrides only some images at); [edit] changes every size's styles.
     */
    fun store(
        top: String = TOP,
        sizes: List<Int> = listOf(480, 720),
        partial: List<Int> = emptyList(),
        preview: Boolean = true,
        animation: Boolean = true,
        edit: (MutableMap<String, MutableList<String>>) -> Unit = {},
    ): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files[top + "theme_info.ini"] = utf8(
            "#测试用\n[THEME_INFO]\nPLATFORM=android\nNAME=春日\nAUTHOR=测试\nVERSION=1.0\n" +
                (if (preview) "PREVIEW=Image_Shot\n" else "") +
                "RESOLUTION=960x480,1440x720\nID=e06fb7e0-f0e7-4689-aea0-4ebc32f20d57\n",
        )
        for (size in sizes + partial) {
            val k = size / 240
            val r = "$top$size/"
            files[r + "res/board.png"] = img(gradient(size, size * 5 / 6, BLUE, 0xFF1B2B5E.toInt()))
            files[r + "res/bar.png"] = img(Pixels.filled(8, 50 * k, CAND))
            files[r + "res/key.png"] = img(face(33 * k, 48 * k, 1, KEY))
            files[r + "res/fn.png"] = img(face(33 * k, 48 * k, 1, FUNCTION))
            files[r + "res/key_down.png"] = img(face(33 * k, 48 * k, 1, KEY_PRESSED))
            files[r + "res/fn_down.png"] = img(face(33 * k, 48 * k, 1, FUNCTION_PRESSED))
            files[r + "res/bubble.png"] = img(face(50 * k, 60 * k, 2, BALLOON))
            files[r + "res/shot.png"] = img(gradient(240, 200, BLUE, KEY))
            files[r + "layout/image.ini"] = utf8("#测试用\n" + images(k, preview))
            if (size in partial) continue
            val st = styles(size)
            edit(st)
            files[r + "layout/style.ini"] = utf8("#测试用\n" + st.entries.joinToString("\n\n") { (s, lines) -> "#一段\n[$s]\n" + lines.joinToString("\n") })
            files[r + "layout/subject.ini"] = utf8("[SUBJECT]\nNOT_READ=1\n")
        }
        // the keyboards: not read, their animations only noted; a style.ini here is no size folder's
        val kb = top + "layout/layout_test/480/layout/"
        files[kb + "keyboard.ini"] = utf8("[Keyboard_Test]\nSIZE=90%p,40%p\n")
        files[kb + "style.ini"] = utf8("[Style_Keyboard_Key_2]\nBG_COLOR=0xFFFF0000\n")
        files[kb + "image.ini"] = utf8("[Image_Key]\nSRC=nothing.png\n")
        if (animation) {
            files[kb + "animation.ini"] = utf8(
                "[Anim_Keyboard_Up]\nANIM_TYPE=3\nDURATION=300\n\n" +
                    "[Animation_Wave]\nANIM_TYPE=7\nANIM_IMAGE=Image_Wave_1,Image_Wave_2\n",
            )
        }
        return files
    }
}
