/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader

/**
 * Skins made here for the tests (no third party's skin is in the repository): a Baidu one and a Sogou one with
 * what the converter reads, as files by name, and a platform that decodes images written by [img] (the test's own
 * format: `IMG`, the width and height, the ARGB ints).
 */
object SkinFixtures {
    /** [p] in the fake platform's image format. */
    fun img(p: Pixels): ByteArray {
        val out = ByteArray(HEAD + 4 * p.argb.size)
        "IMG".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        int(out, 3, p.width)
        int(out, 7, p.height)
        p.argb.forEachIndexed { i, v -> int(out, HEAD + 4 * i, v) }
        return out
    }

    private fun int(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v shr (24 - 8 * i)).toByte()
    }

    private fun int(b: ByteArray, at: Int) = (0 until 4).fold(0) { acc, i -> acc shl 8 or (b[at + i].toInt() and 0xFF) }

    /** The fake platform; [decoded] counts the images it decoded, [maxPixels] its cap. */
    class Platform(private val maxPixels: Long = SkinLimits.MAX_IMAGE_PIXELS) : SkinPlatform {
        var decoded = 0

        override fun image(bytes: ByteArray): SkinImage? {
            if (bytes.size < HEAD || bytes.decodeToString(0, 3) != "IMG") return null
            val w = int(bytes, 3)
            val h = int(bytes, 7)
            val size = w.toLong() * h
            if (w <= 0 || h <= 0 || size > maxPixels) return null
            if (bytes.size < HEAD + 4L * size) return null
            decoded++
            return PixelsImage(Pixels(w, h, IntArray(w * h) { int(bytes, HEAD + 4 * it) }))
        }

        override fun legacyText(bytes: ByteArray): String = "LEGACY:" + bytes.size
    }

    private const val HEAD = 11

    // a key face: an opaque body of [color] inside a transparent [margin], [w]×[h]
    fun face(w: Int, h: Int, margin: Int, color: Int): Pixels = Pixels(w, h).also {
        it.paste(Pixels.filled(w - 2 * margin, h - 2 * margin, color), margin, margin)
    }

    fun gradient(w: Int, h: Int, from: Int, to: Int): Pixels = Pixels(w, h, IntArray(w * h) { i ->
        val t = (i / w).toDouble() / h
        fun ch(s: Int) = ((from shr s and 0xFF) * (1 - t) + (to shr s and 0xFF) * t).toInt()
        Pixels.argbOf(0xFF, ch(16), ch(8), ch(0))
    })

    fun text(s: String) = s.encodeToByteArray()

    /** A WAV of [ms] of 16-bit PCM at [rate], [channels]. */
    fun wav(ms: Int, rate: Int = 44100, channels: Int = 1): ByteArray {
        val n = rate * ms / 1000 * channels
        val out = ByteArray(44 + 2 * n)
        fun put(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun le(at: Int, v: Int, k: Int) { for (i in 0 until k) out[at + i] = (v shr (8 * i)).toByte() }
        put(0, "RIFF"); le(4, 36 + 2 * n, 4); put(8, "WAVE"); put(12, "fmt "); le(16, 16, 4)
        le(20, 1, 2); le(22, channels, 2); le(24, rate, 4); le(28, rate * channels * 2, 4); le(32, channels * 2, 2); le(34, 16, 2)
        put(36, "data"); le(40, 2 * n, 4)
        for (i in 0 until n) le(44 + 2 * i, (i % 64 - 32) * 500, 2)
        return out
    }

    /**
     * A Baidu skin: [roots] its versions' folders ("" one at the top; "light/", "dark/"), a 1080-wide panel, an atlas
     * of a letter key (body 150 of a 162-wide image, 6 px transparent margins), a function key and a pressed face,
     * letters on rows 108 apart, every key's action. [ios]: iOS's way, view rects wider than the pitch.
     */
    fun baidu(roots: List<String> = listOf(""), ios: Boolean = false, name: String = "Sea Breeze", author: String = "Ann"): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["Info.txt"] = text("[Info]\nName=$name\nAuthor=$author\nVersion=1\n")
        files["demo.png"] = img(gradient(400, 300, 0xFF336699.toInt(), 0xFF99CCFF.toInt()))
        for (r in roots) {
            val dark = "dark" in r
            val atlas = Pixels(600, 260)
            atlas.paste(face(162, 180, 6, if (dark) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()), 0, 0)
            atlas.paste(face(162, 180, 6, 0xFF8899AA.toInt()), 200, 0)
            atlas.paste(face(162, 180, 6, 0xFF4477FF.toInt()), 400, 0)
            // the letter's glyph style: a dark letter on transparency
            val glyph = Pixels(40, 40).also { it.paste(Pixels.filled(20, 30, 0xFF102030.toInt()), 10, 5) }
            atlas.paste(glyph, 0, 210)
            files[r + "res/keys.png"] = img(atlas)
            files[r + "res/keys.til"] = text(
                "[IMG1]\nSOURCE_RECT=0,0,162,180\nINNER_RECT=30,30,102,120\n" +
                    "[IMG2]\nSOURCE_RECT=200,0,162,180\nINNER_RECT=230,30,102,120\n" +
                    "[IMG3]\nSOURCE_RECT=400,0,162,180\n" +
                    "[IMG4]\nSOURCE_RECT=0,210,40,40\n",
            )
            files[r + "res/panel.png"] = img(gradient(1080, 600, if (dark) 0xFF101010.toInt() else 0xFFE0E8F0.toInt(), 0xFF8090A0.toInt()))
            files[r + "res/panel.til"] = text("[IMG1]\nSOURCE_RECT=0,0,1080,600\nINNER_RECT=10,10,1060,580\n")
            files[r + "res/click.wav"] = wav(80)
            files[r + "port/res/default.css"] = text(
                "[STYLE1]\nNM_IMG=keys,1\nHL_IMG=keys,3\n" +
                    "[STYLE2]\nNM_IMG=keys,2\nHL_IMG=keys,3\n" +
                    "[STYLE3]\nNM_COLOR=${if (dark) "eeeeee" else "222222"}\nHL_COLOR=ff0000\n" +
                    "[STYLE4]\nNM_IMG=panel,1\n" +
                    "[STYLE5]\nNM_COLOR=CC336699\n" +
                    "[STYLE6]\nNM_COLOR=ff6600\n" +
                    "[STYLE7]\nPRESS_SOUND_PATH=click.wav\n" +
                    "[STYLE8]\nNM_IMG=keys,4\n" +
                    "[STYLE9]\nNM_IMG=keys,1\nPRESS_ANIM=1\n",
            )
            files[r + "port/gen.ini"] = text(
                "[PANEL]\nSIZE=1080,600\nBACK_STYLE=4,5\nSOUND_STYLE=7\n[CAND]\nLAYOUT_NAME=cand1\nVIEW_RECT=0,0,1080,126\n[INPUT]\nFORE_STYLE=6\n",
            )
            files[r + "port/cand1.cnd"] = text("[CAND]\nBACK_STYLE=5\nFORE_STYLE=3\nFIRST_FORE=6\n")
            files[r + "port/hint1.pop"] = text("[HINT]\nBACK_ICON=1\n[ICON1]\nBACK_STYLE=1\nFORE_STYLE=3\n")
            val keys = StringBuilder()
            var n = 0
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val pitch = if (ios) 112 else 108
            val width = if (ios) 162 else 108
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, c ->
                    n++
                    keys.append("[KEY$n]\nVIEW_RECT=${x * pitch + y * 54},${y * 150},$width,150\nBACK_STYLE=1\nFORE_STYLE=8\nCENTER=$c\n")
                }
            }
            for ((code, style) in listOf("F38" to 2, "F39" to 9, "F36" to 2, "F10" to 2, "F12" to 2)) {
                n++
                keys.append("[KEY$n]\nVIEW_RECT=0,450,160,150\nBACK_STYLE=$style\nFORE_STYLE=3\nCENTER=$code\n")
            }
            // a sticker: drawn, no action
            keys.append("[KEY99]\nVIEW_RECT=0,0,50,50\nBACK_STYLE=8\n")
            files[r + "port/py_26.ini"] = text(keys.toString())
        }
        return files
    }

    /** A Sogou phone skin: 1080 wide, a day period and a night one ([periods] in all), key classes, a letter of its own. */
    fun sogou(periods: Int = 2, name: String = "Warm Orange", author: String = "Bo"): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["phoneTheme.ini"] = text("[General]\nskin_name=$name\nskin_author=$author\nskin_version=1\n")
        val list = (1..periods).joinToString(",") { "status$it" }
        files["time.ini"] = text(
            "[General]\nSUPPORT_SCREEN=720,1080\n[1080]\nSTATUS_LIST=$list\n" +
                "[status1]\nSTART=6\nEND=18\n[status2]\nSTART=18,0\nEND=24,6\n",
        )
        files["preview_main.png"] = img(gradient(300, 200, 0xFFFF9900.toInt(), 0xFFFFCC66.toInt()))
        files["default/sound_android.ini"] = text("[Key]\nsound=tick\n[Key_Space]\nsound=space\n[Key_Enter]\nsound=nothing\n")
        files["default/tick.wav"] = wav(60)
        files["default/space.wav"] = wav(3000, rate = 48000, channels = 2)
        files["default/font.ttf"] = text("font")
        for (i in 1..periods) {
            val r = "1080/status$i/"
            val night = i > 1
            files[r + "bg.png"] = img(gradient(1080, 700, if (night) 0xFF202020.toInt() else 0xFFFFE0B0.toInt(), 0xFF805020.toInt()))
            files[r + "cand.png"] = img(Pixels.filled(40, 126, 0x80FFFFFF.toInt()))
            files[r + "key.png"] = img(face(106, 156, 4, if (night) 0xFF444444.toInt() else 0xFFFFF5E8.toInt()))
            files[r + "key_p.png"] = img(face(106, 156, 4, 0xFFFFAA44.toInt()))
            files[r + "fn.png"] = img(face(106, 156, 4, 0xFFDDAA77.toInt()))
            files[r + "enter.png"] = img(face(106, 156, 4, 0xFFFF7700.toInt()))
            files[r + "q.png"] = img(face(106, 156, 4, 0xFF00AA00.toInt()))
            files[r + "phoneSkin.ini"] = text(
                "[RootView]\nBG_IMAGE=bg.png,10,1070,10,690\n" +
                    "[CandidateView]\nBG_IMAGE=cand.png\n" +
                    "[KeyBg_Default]\nNORMAL=key.png,20,86,20,136\nPRESSED=key_p.png,20,86,20,136\n" +
                    "[KeyBg_Function_Qwerty]\nNORMAL=fn.png,20,86,20,136\n" +
                    "[KeyBg_Enter]\nNORMAL=enter.png,20,86,20,136\n" +
                    "[Key_Enter_Qwerty]\nBG_IMAGES=KeyBg_Enter\n" +
                    "[KeyBg_Q]\nNORMAL=q.png\n[Key_Q]\nBG_IMAGES=KeyBg_Q\n" +
                    "[TextStyle_Qwerty]\nTEXT_COLOR=0xFF331100\nTEXT_COLOR_PRESSED=0xFFFFFFFF\nTYPEFACE=font.ttf\nTEXT_SIZE=20\n" +
                    "[TextStyle_FuncKey_26char]\nTEXT_COLOR=0xFF553311\n" +
                    "[TextStyle_Candidate]\nTEXT_COLOR=0xFF222222\n" +
                    "[TextStyle_Candidate_Highlight]\nTEXT_COLOR=0xFFFF6600\n" +
                    "[TextStyle_Composing]\nTEXT_COLOR=0xFF666666\n",
            )
        }
        return files
    }

    fun convert(files: Map<String, ByteArray>, platform: SkinPlatform = Platform(), fallback: String = "file"): SkinConversion =
        SkinConverter.convert(files, platform, fallback)

    /** [c]'s theme.xml read, failing the test on any problem or warning. */
    fun read(c: SkinConversion): Theme = when (val p = ThemeReader.theme(c.themeXml, appVersion = "0.6.0")) {
        is Parsed.Ok -> p.value.also { check(p.warnings.isEmpty()) { "warnings: ${p.warnings}\n${c.themeXml}" } }
        is Parsed.Failed -> error("problems: ${p.problems}\n${c.themeXml}")
    }
}
