/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Bitmap
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.SkinAction
import org.fcitx.fcitx5.android.scene.skin.SkinBoard
import org.fcitx.fcitx5.android.scene.skin.SkinKeyBox
import org.fcitx.fcitx5.android.scene.skin.SkinPlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The skins of uat/14-settings.md ST-200–214, made here as `uat/scripts/14-settings.py` makes them (none third-party in
 * the repo): real PNGs, so the app's own codec decodes them. Each a map of file name to bytes, [zip]ped as its file is.
 */
internal object SkinUatSamples {
    fun png(p: Pixels): ByteArray {
        val b = Bitmap.createBitmap(p.argb, p.width, p.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun filled(w: Int, h: Int, argb: Int) = Pixels.filled(w, h, argb)

    // an opaque body of [color] inside a transparent [margin]
    fun face(w: Int, h: Int, margin: Int, color: Int): Pixels = Pixels(w, h).also { it.paste(filled(w - 2 * margin, h - 2 * margin, color), margin, margin) }

    fun gradient(w: Int, h: Int, from: Int, to: Int): Pixels = Pixels(w, h, IntArray(w * h) { i ->
        val t = (i / w).toDouble() / h
        fun ch(s: Int) = ((from shr s and 0xFF) * (1 - t) + (to shr s and 0xFF) * t).toInt()
        Pixels.argbOf(0xFF, ch(16), ch(8), ch(0))
    })

    fun text(s: String) = s.encodeToByteArray()

    fun wav(ms: Int): ByteArray {
        val rate = 22050
        val n = rate * ms / 1000
        return ByteBuffer.allocate(44 + 2 * n).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 2 * n); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(2 * n)
            repeat(n) { putShort(((it % 40 - 20) * 300).toShort()) }
        }.array()
    }

    fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            for ((name, b) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(b)
            }
        }
    }.toByteArray()

    fun MutableMap<String, ByteArray>.edit(name: String, old: String, new: String) {
        val s = getValue(name).decodeToString()
        check(old in s) { "$old not in $name" }
        this[name] = text(s.replace(old, new))
    }

    fun MutableMap<String, ByteArray>.append(name: String, more: String) {
        this[name] = text((this[name]?.decodeToString() ?: "") + more)
    }

    const val GREEN_MARK = "ff00aa00"
    const val YELLOW = "ffff00"

    /**
     * A Baidu skin ([roots]: "" at the top, or "light/" and "dark/"): a 1080-wide panel, a letter face, a function face
     * and a pressed one, 26 letters in the app's places, space, enter, backspace, shift and 123; letters in style 10
     * (their marks in green, style 11), function labels in style 3, white, yellow when pressed.
     */
    fun baidu(roots: List<String> = listOf(""), name: String = "uat 皮肤"): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["Info.txt"] = text("[Info]\nName=$name\nAuthor=uat\nVersion=1\n")
        files["demo.png"] = png(gradient(400, 300, 0xFF336699.toInt(), 0xFF99CCFF.toInt()))
        for (r in roots) {
            val dark = "dark" in r
            val atlas = Pixels(600, 200)
            atlas.paste(face(162, 180, 6, if (dark) 0xFF333333.toInt() else 0xFFFF9933.toInt()), 0, 0)
            atlas.paste(face(162, 180, 6, 0xFF8899AA.toInt()), 200, 0)
            atlas.paste(face(162, 180, 6, 0xFF4477FF.toInt()), 400, 0)
            files[r + "res/keys.png"] = png(atlas)
            files[r + "res/keys.til"] = text(
                "[IMG1]\nSOURCE_RECT=0,0,162,180\nINNER_RECT=30,30,102,120\n" +
                    "[IMG2]\nSOURCE_RECT=200,0,162,180\nINNER_RECT=230,30,102,120\n" +
                    "[IMG3]\nSOURCE_RECT=400,0,162,180\n",
            )
            files[r + "res/panel.png"] = png(gradient(1080, 600, if (dark) 0xFF101010.toInt() else 0xFF2050A0.toInt(), if (dark) 0xFF000000.toInt() else 0xFF8090A0.toInt()))
            files[r + "res/panel.til"] = text("[IMG1]\nSOURCE_RECT=0,0,1080,600\nINNER_RECT=10,10,1060,580\n")
            files[r + "res/click.wav"] = wav(80)
            files[r + "port/res/default.css"] = text(
                "[STYLE1]\nNM_IMG=keys,1\nHL_IMG=keys,3\n" +
                    "[STYLE2]\nNM_IMG=keys,2\nHL_IMG=keys,3\n" +
                    "[STYLE3]\nNM_COLOR=ffffff\nHL_COLOR=$YELLOW\n" +
                    "[STYLE4]\nNM_IMG=panel,1\n" +
                    "[STYLE5]\nNM_COLOR=CC336699\n" +
                    "[STYLE6]\nNM_COLOR=ff6600\n" +
                    "[STYLE7]\nPRESS_SOUND_PATH=click.wav\n" +
                    "[STYLE10]\nNM_COLOR=${if (dark) "eeeeee" else "112233"}\nHL_COLOR=ff0000\n" +
                    "[STYLE11]\nNM_COLOR=$GREEN_MARK\nHL_COLOR=80aa0000\n",
            )
            files[r + "port/gen.ini"] = text(
                "[PANEL]\nSIZE=1080,600\nBACK_STYLE=4,5\nSOUND_STYLE=7\n[CAND]\nLAYOUT_NAME=cand1\nVIEW_RECT=0,0,1080,126\n[INPUT]\nFORE_STYLE=6\n",
            )
            files[r + "port/cand1.cnd"] = text("[CAND]\nBACK_STYLE=5\nFORE_STYLE=3\nFIRST_FORE=6\n")
            val keys = StringBuilder()
            var n = 0
            fun key(x: Int, y: Int, w: Int, back: Int, fore: String, center: String) {
                n++
                keys.append("[KEY$n]\nVIEW_RECT=$x,$y,$w,150\nBACK_STYLE=$back\nFORE_STYLE=$fore\nCENTER=$center\n")
            }
            "qwertyuiop".forEachIndexed { i, c -> key(i * 108, 0, 108, 1, "10,11", "$c") }
            "asdfghjkl".forEachIndexed { i, c -> key(54 + i * 108, 150, 108, 1, "10,11", "$c") }
            key(0, 300, 162, 2, "3", "F10")
            "zxcvbnm".forEachIndexed { i, c -> key(162 + i * 108, 300, 108, 1, "10,11", "$c") }
            key(918, 300, 162, 2, "3", "F36")
            var x = 0
            for ((c, w) in listOf("F1" to 160, "F6" to 120, "F38" to 520, "F12" to 120, "F39" to 160)) {
                key(x, 450, w, 2, "3", c)
                x += w
            }
            files[r + "port/py_26.ini"] = text(keys.toString())
        }
        return files
    }

    /** ST-200: letters 1.2 times as large ([size], in the skin's pixels) and bold. */
    fun textSized(size: Int): MutableMap<String, ByteArray> = baidu().also {
        it.edit("port/res/default.css", "[STYLE10]\n", "[STYLE10]\nFONT_SIZE=$size\nFONT_WEIGHT=700\n")
    }

    /** A Baidu skin's picture atlas of [n] icons, one colour each, 48 px a side; `IMG<i+1>` the i-th. */
    private fun icons(files: MutableMap<String, ByteArray>, root: String, colors: List<Int>) {
        val atlas = Pixels(48 * colors.size, 48)
        // two colours each, the left half its own: one colour alone would be taken as a tint
        colors.forEachIndexed { i, c ->
            atlas.paste(face(48, 48, 8, 0xFFFFFFFF.toInt()), 48 * i, 0)
            atlas.paste(filled(16, 32, c), 48 * i + 8, 8)
        }
        files[root + "res/icons.png"] = png(atlas)
        files[root + "res/icons.til"] = text(colors.indices.joinToString("") { "[IMG${it + 1}]\nSOURCE_RECT=${48 * it},0,48,48\n" })
    }

    val ICON_COLORS = listOf(0xFFE53935, 0xFF8E24AA, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835, 0xFF6D4C41).map { it.toInt() }

    /**
     * ST-201: backspace (pressed another picture), shift (once and locked its tips') and enter as colour pictures:
     * backspace 1/2, shift 3, once 4, locked 5, enter 6 of [ICON_COLORS].
     */
    fun withIcons(): MutableMap<String, ByteArray> = baidu(name = "uat 图标皮肤").also {
        icons(it, "", ICON_COLORS)
        it.append(
            "port/res/default.css",
            "[STYLE20]\nNM_IMG=icons,1\nHL_IMG=icons,2\n[STYLE21]\nNM_IMG=icons,3\n[STYLE22]\nNM_IMG=icons,4\n[STYLE23]\nNM_IMG=icons,5\n[STYLE24]\nNM_IMG=icons,6\n",
        )
        it.edit("port/py_26.ini", "FORE_STYLE=3\nCENTER=F36\n", "FORE_STYLE=20\nCENTER=F36\n")
        it.edit("port/py_26.ini", "FORE_STYLE=3\nCENTER=F10\n", "FORE_STYLE=21\nCENTER=F10\nSTAT_STYLE=S1_1|S2_2\n")
        it.edit("port/py_26.ini", "FORE_STYLE=3\nCENTER=F39\n", "FORE_STYLE=24\nCENTER=F39\n")
        it.append("port/py_26.ini", "[TIP1]\nFORE_STYLE=22\n[TIP2]\nFORE_STYLE=23\n")
    }

    /** ST-202: the toolbar's emoji, clipboard and settings pictures, and a search one the app has no button for. */
    fun withToolbar(): MutableMap<String, ByteArray> = baidu(name = "uat 工具栏皮肤").also {
        val icon = png(face(60, 60, 4, 0xFF336699.toInt()))
        for (n in listOf("Emoji", "Clipboard", "Setting", "Search")) {
            for (s in listOf("Normal", "Select")) it["res/logo/1.0/toolbarMenuItem_${n}_${s}_iphone@3x.png"] = icon
        }
    }

    /** ST-205/206: [baidu] with a nine keys' board too, named by letters, an emoji key where 0 is. */
    fun withLayouts(): MutableMap<String, ByteArray> = baidu(name = "uat 布局皮肤").also {
        val rows = listOf(
            173 to listOf("'" to 238, "b" to 238, "e" to 238, "F36" to 193),
            173 to listOf("h" to 238, "k" to 238, "n" to 238, "F40" to 193),
            173 to listOf("q" to 238, "u" to 238, "x" to 238, "F7" to 193),
            0 to listOf("F1" to 173, "F6" to 151, "F7" to 100, "F38" to 462, "F39" to 194),
        )
        var n = 0
        it["port/py_9.ini"] = text(
            rows.flatMapIndexed { y, (x0, keys) ->
                var x = x0
                keys.map { (c, w) ->
                    n++
                    "[KEY$n]\nVIEW_RECT=$x,${y * 150},$w,150\nBACK_STYLE=${if (c.length == 1 && c[0] in 'a'..'z') 1 else 2}\nFORE_STYLE=3\nCENTER=$c\n".also { x += w }
                }
            }.joinToString(""),
        )
    }

    /** MN-27: backspace pressed shrinks to 90% in 255 ms (a letter's bubble would hide its face on the device). */
    fun pressing(): MutableMap<String, ByteArray> = baidu(name = "uat 按下皮肤").also {
        it["port/res/anim.ini"] = text("[ANIM1]\nTYPE=4\nFROM=100,100\nTO=90,90\nDURATION=255\n")
        it.append("port/res/default.css", "[STYLE12]\nPRESS_ANIM=1\n")
        it.edit("port/py_26.ini", "FORE_STYLE=3\nCENTER=F36\n", "FORE_STYLE=3\nANIM_STYLE=12\nCENTER=F36\n")
    }

    /** ST-210: each letter a picture of its own (a bar [i] px wider for the i-th), pressed in red. */
    fun withLetters(): MutableMap<String, ByteArray> = baidu(name = "uat 字母皮肤").also { files ->
        val atlas = Pixels(26 * 40, 80)
        val til = StringBuilder()
        val css = StringBuilder()
        for (i in 0 until 26) {
            atlas.paste(filled(10 + i % 10, 30, 0xFF000000.toInt() or (i * 9)), i * 40 + 10, 5)
            atlas.paste(filled(20, 30, 0xFFFF0000.toInt()), i * 40 + 10, 45)
            til.append("[IMG${i + 1}]\nSOURCE_RECT=${i * 40},0,40,40\n[IMG${i + 27}]\nSOURCE_RECT=${i * 40},40,40,40\n")
            css.append("[STYLE${30 + i}]\nNM_IMG=letter,${i + 1}\nHL_IMG=letter,${i + 27}\n")
            val c = 'a' + i
            files.edit("port/py_26.ini", "FORE_STYLE=10,11\nCENTER=$c\n", "FORE_STYLE=${30 + i},11\nCENTER=$c\n")
        }
        files["res/letter.png"] = png(atlas)
        files["res/letter.til"] = text(til.toString())
        files.append("port/res/default.css", css.toString())
    }

    /** A Sogou phone skin: one period, key classes, [font] its `default/font.ttf` where given. */
    fun sogou(font: ByteArray? = null): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["phoneTheme.ini"] = text("[General]\nskin_name=uat 搜狗皮肤\nskin_author=uat\nskin_version=1\n")
        files["time.ini"] = text("[General]\nSUPPORT_SCREEN=1080\n[1080]\nSTATUS_LIST=status1\n[status1]\nSTART=0\nEND=24\n")
        files["preview_main.png"] = png(gradient(300, 200, 0xFFFF9900.toInt(), 0xFFFFCC66.toInt()))
        val r = "1080/status1/"
        files[r + "bg.png"] = png(gradient(1080, 700, 0xFFFFE0B0.toInt(), 0xFF805020.toInt()))
        files[r + "cand.png"] = png(filled(40, 126, 0x80FFFFFF.toInt()))
        files[r + "key.png"] = png(face(106, 156, 4, 0xFFFFF5E8.toInt()))
        files[r + "key_p.png"] = png(face(106, 156, 4, 0xFFFFAA44.toInt()))
        files[r + "fn.png"] = png(face(106, 156, 4, 0xFFDDAA77.toInt()))
        files[r + "phoneSkin.ini"] = text(
            "[RootView]\nBG_IMAGE=bg.png,10,1070,10,690\n" +
                "[CandidateView]\nBG_IMAGE=cand.png\n" +
                "[KeyBg_Default]\nNORMAL=key.png,20,86,20,136\nPRESSED=key_p.png,20,86,20,136\n" +
                "[KeyBg_Function_Qwerty]\nNORMAL=fn.png,20,86,20,136\n" +
                "[TextStyle_Qwerty]\nTEXT_COLOR=0xFF331100\nTEXT_COLOR_PRESSED=0xFFFFFFFF\n" +
                (if (font != null) "TYPEFACE=font.ttf\n" else "") +
                "[TextStyle_FuncKey_26char]\nTEXT_COLOR=0xFF553311\n" +
                "[TextStyle_Candidate]\nTEXT_COLOR=0xFF222222\n",
        )
        if (font != null) files["default/font.ttf"] = font
        return files
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /** [plain] in Sogou's container: AES-CBC with its public key, as Sogou's own skins are. */
    fun sogouSeal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(SkinPlatform.SOGOU_KEY), "AES"), IvParameterSpec(hex(SkinPlatform.SOGOU_IV)))
        return "Skin".toByteArray() + byteArrayOf(2, 0, 0, 0) + cipher.doFinal(plain)
    }

    /** ST-203: [files] in Sogou's container, sealed with its public key as Sogou's own skins are. */
    fun sogouSealed(files: Map<String, ByteArray>): ByteArray {
        val bodies = files.map { (name, data) -> le(2 * name.length) + name.toByteArray(Charsets.UTF_16LE) + le(data.size) + data }
        var at = 8 + 4 * bodies.size
        val index = bodies.map { b -> at.also { at += b.size } }
        val payload = ByteArray(4) + le(4 * bodies.size) + index.fold(ByteArray(0)) { a, o -> a + le(o) } + bodies.fold(ByteArray(0)) { a, b -> a + b }
        val deflated = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(payload) } }.toByteArray()
        return sogouSeal(le(payload.size) + deflated)
    }

    /** The app's 26 keys in its rows, at a skin's sizes, as a reader that finds them makes them. */
    fun qwertyBoard(): SkinBoard {
        fun row(y: Double, x0: Double, keys: List<Pair<SkinAction, Double>>): List<SkinKeyBox> {
            var x = x0
            return keys.map { (a, w) -> SkinKeyBox(x, y, w, 150.0, a).also { x += w } }
        }
        fun letters(s: String) = s.map { SkinAction.Letter(it) to 108.0 }
        fun roles(vararg r: Pair<String, Double>) = r.map { SkinAction.Role(it.first) to it.second }
        return SkinBoard(
            "qwerty",
            listOf(
                row(0.0, 0.0, letters("qwertyuiop")),
                row(150.0, 54.0, letters("asdfghjkl")),
                row(300.0, 0.0, roles("shift" to 162.0) + letters("zxcvbnm") + roles("backspace" to 162.0)),
                row(450.0, 0.0, roles("symbols" to 120.0, "numbers" to 120.0, "comma" to 100.0, "space" to 480.0, "period" to 100.0, "enter" to 160.0)),
            ),
            1080.0,
        )
    }

    // protobuf as Baidu's `.bda` writes it
    private class Proto {
        val out = ArrayList<Byte>()

        fun raw(v: Long) {
            var x = v
            // unsigned: a negative one in ten bytes, as protobuf writes it
            while (x and 0x7FL.inv() != 0L) {
                out += ((x and 0x7F) or 0x80).toByte()
                x = x ushr 7
            }
            out += x.toByte()
        }

        fun tag(n: Int, type: Int) = raw((n.toLong() shl 3) or type.toLong())
        fun varint(n: Int, v: Long) { tag(n, 0); raw(v) }
        fun varint(n: Int, v: Int) = varint(n, v.toLong())
        fun color(n: Int, argb: Int) = varint(n, argb.toLong() and 0xFFFFFFFFL)
        fun bytes(n: Int, b: ByteArray) { tag(n, 2); raw(b.size.toLong()); out.addAll(b.toList()) }
        fun string(n: Int, s: String) = bytes(n, s.encodeToByteArray())
        fun message(n: Int, block: Proto.() -> Unit) = bytes(n, Proto().apply(block).out.toByteArray())
    }

    private fun Proto.style(n: Int, type: Int, key: Int) = message(n) {
        if (type != 0) varint(1, type)
        varint(2, key)
    }

    private fun Proto.image(key: Int, normal: String, pressed: String? = null, inner: IntArray? = null) = message(1) {
        varint(1, key)
        message(2) {
            for ((n, id) in listOfNotNull(1 to normal, pressed?.let { 2 to it })) {
                message(n) {
                    message(1) { string(2, id) }
                    if (inner != null) {
                        message(2) {
                            message(1) { varint(1, inner[0]); varint(2, inner[1]) }
                            message(2) { varint(1, inner[2]); varint(2, inner[3]) }
                        }
                    }
                }
            }
        }
    }

    private fun Proto.key(name: String, vararg styles: Pair<Int, Int>) = message(3) {
        string(1, name)
        message(2) {
            style(1, 0, styles[0].second)
            for (s in styles.drop(1)) style(2, s.first, s.second)
        }
    }

    const val BDA_BACKGROUND = 0xFF6A1B9A.toInt()
    const val BDA_FACE = 0xFF00BCD4.toInt()

    /** ST-207: a `.bda`, purple, its keys cyan faces of rounded corners stretched by an inner rect, a GIF among its pictures. */
    fun bda(): MutableMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["Info.txt"] = text("Name=uat bda\nSupportPlatform=A\n")
        val rounded = face(120, 150, 6, BDA_FACE).also { p -> for ((x, y) in listOf(6 to 6, 113 to 6, 6 to 143, 113 to 143)) p.paste(Pixels(1, 1), x, y) }
        files["res/10.png"] = png(rounded)
        files["res/11.png"] = png(face(120, 150, 6, 0xFF80DEEA.toInt()))
        files["res/50.png"] = png(filled(108, 60, BDA_BACKGROUND))
        files["res/60.gif"] = text("GIF89a")
        files["port/appearanceConfig"] = Proto().apply {
            image(1, "10", "11", inner = intArrayOf(30, 30, 60, 90))
            image(4, "50")
            message(1) { varint(1, 8); message(2) { message(1) { message(1) { varint(1, 1); string(2, "60") } } } }
            message(2) { varint(1, 1); message(2) { varint(3, 64); color(4, 0xFFFFFFFF.toInt()); color(5, 0xFFFF0000.toInt()) } }
            message(4) {
                string(1, "py_26")
                message(2) {
                    for (c in "QWE") key("KEY_$c", 0 to 1, 2 to 1)
                    key("KEY_F1", 0 to 1, 0 to 8)
                    style(7, 0, 4)
                }
            }
            message(5) { color(1, 0xFFFFFFFF.toInt()); color(5, BDA_FACE); color(6, BDA_BACKGROUND) }
            varint(6, 1080)
        }.out.toByteArray()
        return files
    }

    /** ST-208: a Trime theme of an include of `base.yaml`, a patch making keys red, and a `trime:/` reference. */
    fun trime(): Map<String, ByteArray> {
        val keys = buildList {
            "qwertyuiop".forEach { add("{click: $it}") }
            add("{width: 5}")
            "asdfghjkl".forEach { add("{click: $it}") }
            add("{width: 5}")
            add("{click: Shift_L, width: 15}")
            "zxcvbnm".forEach { add("{click: $it}") }
            add("{click: BackSpace, width: 15}")
            add("{click: Keyboard_symbols, width: 12}")
            add("{click: Keyboard_number, width: 12}")
            add("{click: comma}")
            add("{click: space, width: 41}")
            add("{click: period}")
            add("{click: Return, width: 15}")
        }.joinToString("\n") { "      - $it" }
        val base = """
            name: Base
            author: uat
            style:
              color_scheme: sea
              round_corner: 4
              keyboards: [.default]
            preset_color_schemes:
              sea:
                name: Sea
                back_color: 0x003366
                key_back_color: 0x336699
                key_text_color: 0xFFFFFF
            preset_keyboards:
              default:
                ascii_mode: 0
                width: 10
                height: 50
                keys:
        """.trimIndent() + "\n" + keys + "\n"
        val main = """
            name: uat 同文
            __include: base:/
            style:
              __include: trime:/style
            preset_color_schemes:
              __patch:
                - {sea/key_back_color: 0xFF0000}
        """.trimIndent()
        return mapOf("base.yaml" to text(base), "uat.trime.yaml" to text(main))
    }

    const val XUNFEI_KEY = 0xFF2A3F73.toInt()
    const val XUNFEI_KEY_PRESSED = 0xFF6A8FE0.toInt()

    private fun utf8(s: String): ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.replace("\n", "\r\n").encodeToByteArray()

    /** ST-211: an iFlytek shop skin: a folder, `theme_info.ini`, `480/` and `720/`; a deep blue board, white letters, a lighter pressed face. */
    fun xunfei(): Map<String, ByteArray> {
        val top = "uat01/"
        val files = LinkedHashMap<String, ByteArray>()
        files[top + "theme_info.ini"] = utf8("[THEME_INFO]\nPLATFORM=android\nNAME=uat 讯飞\nAUTHOR=uat\nVERSION=1.0\nRESOLUTION=960x480,1440x720\n")
        for (size in listOf(480, 720)) {
            val k = size / 240
            val r = "$top$size/"
            files[r + "res/board.png"] = png(gradient(size, size * 5 / 6, 0xFF0D1B3E.toInt(), 0xFF1B2B5E.toInt()))
            files[r + "res/key.png"] = png(face(33 * k, 48 * k, 1, XUNFEI_KEY))
            files[r + "res/fn.png"] = png(face(33 * k, 48 * k, 1, 0xFF1C2C55.toInt()))
            files[r + "res/key_down.png"] = png(face(33 * k, 48 * k, 1, XUNFEI_KEY_PRESSED))
            files[r + "layout/image.ini"] = utf8(
                "[Image_Layout_Bg]\nSRC=board.png\n\n" +
                    "[Image_Key]\nSRC=key.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n" +
                    "[Image_Fn]\nSRC=fn.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n" +
                    "[Image_Key_Down]\nSRC=key_down.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n",
            )
            files[r + "layout/style.ini"] = utf8(
                "[Style_Keyboard_Key_Fore_2]\nTEXT_COLOR=0xFFFFFFFF\n\n[Style_Keyboard_Key_Fore_1]\nTEXT_COLOR=0xFFB0C4FF\n\n" +
                    "[Style_Keyboard_Key_2]\nIMAGE=Image_Key\n\n[Style_Keyboard_Key_1]\nIMAGE=Image_Fn\n\n" +
                    "[Style_Keyboard_Key_2_Press]\nIMAGE=Image_Key_Down\n\n[Style_Py_9]\nIMAGE=Image_Layout_Bg\n",
            )
        }
        return files
    }

    /** ST-214: a file of each format told apart and not converted, by its name. */
    fun unsupported(): Map<String, ByteArray> = mapOf(
        "uat-shouxin.zip" to zip(mapOf("skin.json" to text("{}"), "light/res/skin.proto" to byteArrayOf(8, 1))),
        "uat-gboard.zip" to zip(mapOf("metadata.json" to text("{}"), "style_sheet_md2.css" to text("a{}"))),
        "x.cskin" to zip(mapOf("x.yaml" to text("a: 1"))),
        "x.apk" to zip(mapOf("AndroidManifest.xml" to byteArrayOf(3, 0), "resources.arsc" to byteArrayOf(2, 0))),
    )

    /** A font of square a–z (web/theme-maker's test font, made by make-tiny-font.py). */
    val font: ByteArray by lazy { File(System.getProperty("themeMaker.testdata") ?: "../web/theme-maker/test/testdata", "tiny.ttf").readBytes() }
}
