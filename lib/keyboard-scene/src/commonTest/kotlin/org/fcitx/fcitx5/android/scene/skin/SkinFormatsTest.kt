/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convert
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.face
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.img
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.read
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.sogou
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sogou as real skins write it, Trime's and FlorisBoard's themes, and the readers under them (synthetic files only). */
class SkinFormatsTest {
    private fun SkinConversion.image(name: String) =
        assertNotNull(files.firstOrNull { it.name == name } as? SkinOutput.Image, "$name in ${files.map { it.name }}").pixels

    private fun hexOf(c: SkinConversion, name: String) = read(c).colors[name]?.let { SkinTheme.hex(it.value) }

    // --- Sogou as the downloaded skins write it

    private fun sogouKeyboardPicture(): MutableMap<String, ByteArray> {
        val files = sogou(periods = 1)
        val r = "1080/status1/"
        files[r + "kb.png"] = img(Pixels.filled(1080, 600, 0xFF3366CC.toInt()))
        files[r + "bar.png"] = img(Pixels.filled(40, 126, 0xFFCC3333.toInt()))
        files[r + "press.png"] = img(face(106, 156, 4, 0xFFFFAA44.toInt()))
        files[r + "clear.png"] = img(Pixels(20, 20))
        files[r + "g.png"] = img(Pixels(30, 30).also { it.paste(Pixels.filled(10, 20, 0xFF00FF00.toInt()), 10, 5) })
        files[r + "phoneSkin.ini"] = text(
            "[Keyboard]\nBG_STYLE=1\nBG_IMAGE=Kb_Bg\n[Kb_Bg]\nNORMAL=kb.png\n" +
                "[CandidateView]\nBG_IMAGE=bar.png,0,1,1,0,1,1\n" +
                "[KeyBg_Default]\nNORMAL=transparent.png,0,10,0,10\nPRESSED=press.png,1,100,1,150\n" +
                "[KeyBg_Function_Qwerty]\nNORMAL=clear.png\nPRESSED=press.png\n" +
                "[Key_Space]\nICON=transparent.png\nBG_IMAGES=KeyBg_Space\n[KeyBg_Space]\nNORMAL=transparent.png\nPRESSED=press.png\n",
        )
        files[r + "keys.ini"] = text("[Key_G_L]\nFG_IMAGES=Fg_G\n[Fg_G]\nNORMAL=g.png\n")
        files["square.png"] = img(Pixels.filled(64, 64, 0xFF123456.toInt()))
        files["phoneTheme.ini"] = text("[General]\nskin_name=Lake\nskin_author=Bo\npreview_square=square.png\n")
        return files
    }

    @Test
    fun sogouKeyboardPictureUnderTheBar() {
        val c = convert(sogouKeyboardPicture())
        val board = c.image(SkinTheme.BOARD)
        // the bar's picture (126 px at 1080) over the keyboard's (600), its six-number band read as the four
        assertEquals(1080 to 726, board.width to board.height)
        assertEquals(0xFFCC3333.toInt(), board[540, 60])
        assertEquals(0xFF3366CC.toInt(), board[540, 700])
    }

    @Test
    fun sogouTransparentFacesBakedIconsAndSuffixedLetters() {
        val c = convert(sogouKeyboardPicture())
        // the letters' face transparent.png, an all-transparent picture the function keys': no face, the pressed one drawn
        assertContains(c.themeXml, """<style select="key" fill="#00000000"""")
        assertContains(c.themeXml, """select="key.function" fill="#00000000"""")
        assertContains(c.themeXml, """select="key:pressed" fill="image(l-key-p.png""")
        // space's symbol in its face (ICON transparent.png): no label or icon of the app's on it
        assertContains(c.themeXml, """select="key.space" fill="#00000000" color="transparent" icon-color="transparent"""")
        // the letters' colour from Key_G_L's picture
        assertEquals("#00ff00", hexOf(c, "keyText"))
        assertTrue(SkinNote.LETTER_IMAGES in c.notes)
    }

    @Test
    fun sogouPreviewSquare() {
        val c = convert(sogouKeyboardPicture())
        assertEquals(0xFF123456.toInt(), c.image(SkinTheme.PREVIEW)[0, 0])
    }

    // --- Trime

    private val trimeYaml = """
        # a theme of Trime's
        name: "Ink"
        author: Cy
        style:
          color_scheme: ink
          horizontal_gap: 4
          vertical_gap: 6
          round_corner: 5
          key_font: k.ttf
          background_folder: ink
          __include: base.yaml:/style
        preset_keyboards:
          qwerty: {author: Cy,
            keys: [{click: q}, {click: w}]}
        preset_color_schemes:
          ink:
            name: Ink day
            back_color: 0xEEEEEE
            text_color: 0x333333
            candidate_text_color : &c 0xAA0000
            hilited_candidate_text_color: *c
            key_back_color: key.9.png
            hilited_key_back_color: 0xFF6699
            key_text_color: 0x112233
            off_key_back_color: 0xCCCCCC
            dark_scheme: ink_night
          ink_night:
            name: Ink night
            back_color: 0x222222
            key_back_color: 0x444444
            key_text_color: 0xEEEEEE
    """.trimIndent()

    // a 14×14 .9.png: the 12×12 face inside a border marked 4 px in from each end
    private fun ninePatch(): ByteArray {
        val px = Pixels(14, 14)
        for (y in 1..12) for (x in 1..12) px.argb[y * 14 + x] = 0xFF8899AA.toInt()
        for (n in 5..8) {
            px.argb[n] = 0xFF000000.toInt()
            px.argb[n * 14] = 0xFF000000.toInt()
        }
        return img(px)
    }

    private fun trime(): MutableMap<String, ByteArray> = linkedMapOf(
        "ink.trime.yaml" to text(trimeYaml),
        "backgrounds/ink/key.9.png" to ninePatch(),
        "fonts/k.ttf" to text("font"),
    )

    @Test
    fun trimeThemeAndItsDarkScheme() {
        val c = convert(trime())
        val t = read(c)
        assertEquals("Ink", t.name)
        assertEquals("Cy", t.meta?.author)
        assertEquals(setOf(false, true), t.variants)
        assertEquals("#112233", hexOf(c, "keyText"))
        // an anchor (`candidate_text_color : &c`, a space before the colon) and its alias
        assertEquals("#aa0000", hexOf(c, "first"))
        assertContains(c.themeXml, """inset="3dp 2dp"""")
        assertContains(c.themeXml, """radius="5dp"""")
        assertContains(c.themeXml, """select="key" fill="image(l-key-n.png""")
        assertContains(c.themeXml, "#444444")
        assertContains(c.themeXml, """<font name="skin" src="skin-k.ttf"/>""")
        assertEquals(setOf(SkinNote.LAYOUT, SkinNote.TEXT_SIZE, SkinNote.INCLUDES), c.notes)
    }

    @Test
    fun trimeOneSchemeOfMany() {
        val yaml = trimeYaml.replace("    dark_scheme: ink_night\n", "") + "\n  sea:\n    back_color: 0x003366\n"
        val c = convert(mapOf("ink.trime.yaml" to text(yaml)))
        assertEquals("Ink · Ink day", c.name)
        assertEquals(setOf(false), read(c).variants)
        assertTrue(SkinNote.MORE_THEMES in c.notes)
        // its picture not in the file: said, the face the fallback's colour
        assertTrue(SkinNote.IMAGES in c.notes)
    }

    @Test
    fun trimeDarkSchemeWithItsLightOne() {
        val yaml = """
            name: Star
            style: {color_scheme: night}
            preset_color_schemes:
              night: {name: Night, back_color: 0x101020, key_back_color: 0x303050, key_text_color: 0xFFFFFF, light_scheme: day}
              day: {name: Day, back_color: 0xF0F0FF, key_back_color: 0xFFFFFF, key_text_color: 0x000000}
        """.trimIndent()
        val c = convert(mapOf("star.yaml" to text(yaml)))
        assertEquals("Star", c.name)
        assertEquals(setOf(false, true), read(c).variants)
        assertEquals("#000000", hexOf(c, "keyText"))
    }

    @Test
    fun trimeInARepositoryFolder() {
        val zipped = trime().mapKeys { "ink-main/" + it.key } + ("ink-main/README.md" to text("x"))
        assertEquals("Ink", convert(zipped).name)
        // the biggest theme file is the theme; the others (a patch) beside it
        val two = trime() + ("ink.custom.trime.yaml" to text("patch: {}"))
        assertEquals("Ink", convert(two).name)
    }

    @Test
    fun aFolderZippedByMacOsFinderIsUnwrapped() {
        // Finder's __MACOSX/ and ._ files beside the theme's folder: not a second folder
        val zipped = trime().mapKeys { "ink/" + it.key } + ("__MACOSX/ink/._ink.trime.yaml" to text("x")) + ("ink/._k.ttf" to text("x"))
        assertEquals("Ink", convert(zipped).name)
    }

    @Test
    fun aKeyboardFontPastTheLimitIsLeftOut() {
        // each reader keeps a font of its own within SkinLimits.MAX_FONT_BYTES; one past it is left out and the text
        // note said (Sogou's and Trime's skins set a text size of their own, so theirs is said whatever the font)
        val rows = listOf(
            Triple("FlorisBoard's @font", { floris() }, "fonts/moss.ttf"),
            Triple("Sogou's TYPEFACE", { sogou() }, "default/font.ttf"),
            Triple("Trime's key_font", { trime() }, "fonts/k.ttf"),
        )
        assertRows(rows, { it.first }) { (why, skin, font) ->
            assertContains(convert(skin()).themeXml, "<font ", message = "$why: within the limit")
            val files = skin()
            files[font] = ByteArray(SkinLimits.MAX_FONT_BYTES + 1)
            val c = convert(files)
            assertFalse(c.themeXml.contains("<font "), "$why: past the limit")
            assertTrue(SkinNote.TEXT_SIZE in c.notes, "$why: said")
        }
    }

    @Test
    fun yamlWithoutColourSchemesIsNoKeyboard() {
        val e = assertFailsWith<SkinError> { convert(mapOf("x.yaml" to text("name: x\nstyle: {}\n"))) }
        assertEquals(SkinError.Reason.NO_KEYBOARD, e.reason)
        assertFailsWith<SkinError> { convert(mapOf("x.yaml" to text("- just\n- a list\n"))) }
    }

    // --- FlorisBoard

    private fun floris(): MutableMap<String, ByteArray> = linkedMapOf(
        "extension.json" to text(
            """{"${'$'}":"ime.extension.theme","meta":{"id":"moss","version":"1","title":"Moss","maintainers":["Di"]},
               "themes":[{"id":"day","label":"Moss Day","authors":["Di"]},{"id":"night","label":"Moss Night","authors":["Di"]},
                 {"id":"extra","label":"Extra","isNight":true},{"id":"gone","label":"No sheet"},]}""",
        ),
        "stylesheets/day.json" to text(
            """{"@defines":{"--bg":"#e0f0e0","--key":"var(--white)","--white":"#ffffff"},
               "window":{"background":"var(--bg)","foreground":"#102010"},
               "smartbar":{"background":"#c0d0c0"},
               "key":{"background":"var(--key)","foreground":"#203020","shape":"rounded-corner(7dp, 7dp, 7dp, 7dp)","margin":"3dp 1dp","font-family":"`Moss`"},
               "key:pressed":{"background":"rgba(0, 128, 0, 0.5)"},
               "key[code=10]":{"background":"#080","foreground":"#fff"},
               "key[code=-201,-202,-203]":{"background":"#aaccaa80"},
               "key[code=97]":{"background":"#ff0000"},
               "@font `Moss`":[{"src":"uri(`flex:/fonts/moss.ttf`)"}],
               "key-popup-box":{"background":"#334433","foreground":"#ffffff"}}""",
        ),
        "stylesheets/night.json" to text(
            """{"window":{"background":"#101810"},"key":{"background":"#203020","foreground":"#e0f0e0"},
               "keyboard":{"background-image":"uri(`flex:/images/missing.png`)"}}""",
        ),
        "stylesheets/extra.json" to text("""{"window":{"background":"#000000"}}"""),
        "fonts/moss.ttf" to text("font"),
    )

    @Test
    fun florisLightAndDark() {
        val c = convert(floris())
        val t = read(c)
        assertEquals("Moss Day", t.name)
        assertEquals("Di", t.meta?.author)
        // the night one has no isNight: dark by its background
        assertEquals(setOf(false, true), t.variants)
        assertEquals("#203020", hexOf(c, "keyText"))
        assertEquals("#102010", hexOf(c, "candidateText"))
        assertContains(c.themeXml, """radius="7dp"""")
        assertContains(c.themeXml, """inset="3dp 1dp"""")
        assertContains(c.themeXml, """select="key" fill="#ffffff"""")
        assertContains(c.themeXml, """fill="#80008000"""")
        assertContains(c.themeXml, """select="key.enter" fill="#008800"""")
        assertContains(c.themeXml, """select="key.function" fill="#80aaccaa"""")
        assertFalse(c.themeXml.contains("#ff0000"))
        assertContains(c.themeXml, """<font name="skin" src="skin-moss.ttf"/>""")
        assertEquals(setOf(SkinNote.LAYOUT, SkinNote.PANELS, SkinNote.MORE_THEMES, SkinNote.IMAGES), c.notes)
    }

    @Test
    fun florisPictureBackground() {
        val files = floris()
        files["images/bg.png"] = img(Pixels.filled(1080, 600, 0xFF224422.toInt()))
        files["stylesheets/day.json"] = text("""{"window":{"background-image":"uri(`flex:/images/bg.png`)"},"smartbar":{"background":"#c0d0c0"}}""")
        files["stylesheets/night.json"] = text("""{"window":{"background":"#101810"}}""")
        val c = convert(files)
        // the picture under the keys, the smartbar's colour above it
        val board = c.image(SkinTheme.BOARD)
        assertEquals(0xFFC0D0C0.toInt(), board[540, 10])
        assertEquals(0xFF224422.toInt(), board[540, board.height - 10])
        assertFalse(SkinNote.IMAGES in c.notes)
    }

    @Test
    fun florisBroken() {
        val files = floris()
        files["extension.json"] = text("{not json")
        assertEquals(SkinError.Reason.BROKEN, assertFailsWith<SkinError> { convert(files) }.reason)
        files["extension.json"] = text("""{"themes":[{"id":"nope"}]}""")
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { convert(files) }.reason)
    }

    // --- the readers under them

    @Test
    fun yamlStructures() {
        val y = SkinYaml.parse(
            """
            %YAML 1.1
            ---
            a: 1  # a comment
            b: "x # not a comment"
            'c d': 'it''s'
            list:
            - one
            - k: v
              k2: v2
            -
              - nested
            empty:
            nul: ~
            flow: {x: [1, 2, {y: z}], w: "a,b"}
            base: &base {p: 1, q: 2}
            derived:
              <<: *base
              q: 3
            lit: |
              line one
                line two
            fold: >-
              folded
              text
            stray: first
               continued
            after: yes
            url: http://x.y/z
            tagged: !foo bar
            """.trimIndent(),
        ) as Map<*, *>
        assertEquals("1", y["a"])
        assertEquals("x # not a comment", y["b"])
        assertEquals("it's", y["c d"])
        assertEquals(listOf("one", mapOf("k" to "v", "k2" to "v2"), listOf("nested")), y["list"])
        assertTrue("empty" in y)
        assertNull(y["empty"])
        assertNull(y["nul"])
        assertEquals(mapOf("x" to listOf("1", "2", mapOf("y" to "z")), "w" to "a,b"), y["flow"])
        assertEquals(mapOf("q" to "3", "p" to "1"), y["derived"])
        assertEquals("line one\n  line two", y["lit"])
        assertEquals("folded text", y["fold"])
        assertEquals("first", y["stray"])
        assertEquals("yes", y["after"])
        assertEquals("http://x.y/z", y["url"])
        assertEquals("bar", y["tagged"])
    }

    @Test
    fun yamlFlowOverLinesAndEscapes() {
        val y = SkinYaml.parse("k: {a: \"q\\\"t\\n\",\n   b: [1,\n  2], c: , d: &n e, f: *n}\nnext: 1\n") as Map<*, *>
        assertEquals(mapOf("a" to "q\"t\n", "b" to listOf("1", "2"), "c" to null, "d" to "e", "f" to "e"), y["k"])
        assertEquals("1", y["next"])
        assertNull(SkinYaml.parse(""))
        assertNull(SkinYaml.parse("# only\n---\n"))
        assertEquals("plain", SkinYaml.parse("plain"))
        assertEquals(listOf("a", null, "b"), SkinYaml.parse("[a, ~, b, }]"))
    }

    @Test
    fun yamlDepthIsHeld() {
        val deep = (0 until 300).joinToString("\n") { " ".repeat(it) + "k$it:" } + " end"
        var m = SkinYaml.parse(deep)
        var depth = 0
        while (m is Map<*, *>) {
            m = m.values.first()
            depth++
        }
        assertTrue(depth in 2..SkinYaml.MAX_DEPTH + 2, "depth $depth")
        val brackets = "[".repeat(10_000) + "]".repeat(10_000)
        SkinYaml.parse(brackets)
        SkinYaml.parse("k: " + "{a: ".repeat(5_000))
        SkinYaml.parse((0 until 2000).joinToString("\n") { "- ".repeat(it % 200) + "x" })
    }

    @Test
    fun yamlNeverThrows() {
        val parts = listOf("a", ":", " ", "\n", "  ", "- ", "{", "}", "[", "]", ",", "\"", "'", "#", "&x ", "*x", "<<: ", "|", ">", "!t ", "\t", "~", "\\")
        val rnd = Random(60)
        repeat(3000) {
            val s = buildString { repeat(rnd.nextInt(80)) { append(parts[rnd.nextInt(parts.size)]) } }
            SkinYaml.parse(s)
            SkinJson.parse(s)
        }
        // and a real theme cut anywhere, or with lines swapped, still a theme or a refusal
        repeat(400) {
            val t = trimeYaml
            val cut = t.substring(0, rnd.nextInt(t.length)) + t.substring(rnd.nextInt(t.length))
            try {
                convert(mapOf("t.trime.yaml" to text(cut)))
            } catch (_: SkinError) {
            }
        }
    }

    @Test
    fun yamlLongChainsOfAnchorsAndTags() {
        // a line of 300000 anchors or tags: no deeper a call for each, nor the line's rest copied for each
        val n = 300_000
        val block = SkinYaml.parse("a: " + "&x ".repeat(n) + "v\nb: *x\n") as Map<*, *>
        assertEquals("v", block["a"])
        assertEquals("v", block["b"])
        assertEquals("v", (SkinYaml.parse("a: " + "!t ".repeat(n) + "v\n") as Map<*, *>)["a"])
        assertEquals(listOf("v", "v"), (SkinYaml.parse("a: [" + "&x ".repeat(n) + "v, *x]\n") as Map<*, *>)["a"])
        // a chain over the block under it
        assertEquals(mapOf("k" to "v"), (SkinYaml.parse("a: " + "&x !t ".repeat(n) + "\n  k: v\n") as Map<*, *>)["a"])
    }

    @Test
    fun boardsOfAnOddShapeAreShrunkBeforeTheyArePutTogether() {
        // 100000×40: at its size, with the bar on top, a board of 100000×11540 (1.2 G pixels), past any memory
        val look = SkinLook(1080)
        SkinPatch.board(look, SkinFill.Image(Pixels.filled(100_000, 40, 0xFF3366CC.toInt()), null), SkinFill.Color(0xFFCC3333.toInt()))
        val board = assertNotNull(look.board)
        assertTrue(board.width <= SkinTheme.MAX_PICTURE_SIDE && board.height <= SkinTheme.MAX_PICTURE_SIDE, "${board.width}x${board.height}")
        assertEquals(0xFFCC3333.toInt(), board[board.width / 2, 0])
        assertEquals(0xFF3366CC.toInt(), board[board.width / 2, board.height - 1])
        // Sogou's keyboard picture under its bar's
        val files = sogouKeyboardPicture()
        files["1080/status1/kb.png"] = img(Pixels.filled(100_000, 40, 0xFF3366CC.toInt()))
        val sogou = convert(files).image(SkinTheme.BOARD)
        assertEquals(0xFFCC3333.toInt(), sogou[sogou.width / 2, 0])
        assertEquals(0xFF3366CC.toInt(), sogou[sogou.width / 2, sogou.height - 1])
    }

    @Test
    fun decodedImagesAreKeptWithinABudget() {
        var decoded = 0
        // an image as tall as its file is long, 4096 across: no pixels made
        val platform = object : SkinPlatform {
            override fun image(bytes: ByteArray): SkinImage {
                decoded++
                return object : SkinImage {
                    override val width = 4096
                    override val height = bytes.size

                    override fun pixels(x: Int, y: Int, w: Int, h: Int) = Pixels(1, 1)
                }
            }

            override fun legacyText(bytes: ByteArray) = ""
        }
        // two of 8 M pixels are past the budget of 12 M; one of 8 M and one of 4 K are not
        val tall = (SkinLimits.MAX_KEPT_PIXELS * 2 / 3 / 4096).toInt()
        val f = SkinFiles(mapOf("a" to ByteArray(tall), "b" to ByteArray(tall), "c" to ByteArray(1)), platform)
        f.image("a")
        f.image("a")
        assertEquals(1, decoded)
        f.image("c")
        f.image("a")
        assertEquals(2, decoded)
        f.image("b")
        f.image("b")
        assertEquals(3, decoded)
        f.image("a")
        assertEquals(4, decoded)
    }

    @Test
    fun wholePixelsAreOneCopyWithinTheSameBudget() {
        var decoded = 0
        // as above, each whole read a new 1x1 Pixels: the same one back means it was kept
        val platform = object : SkinPlatform {
            override fun image(bytes: ByteArray): SkinImage {
                decoded++
                return object : SkinImage {
                    override val width = 4096
                    override val height = bytes.size

                    override fun pixels(x: Int, y: Int, w: Int, h: Int) = Pixels(1, 1)
                }
            }

            override fun legacyText(bytes: ByteArray) = ""
        }
        val tall = (SkinLimits.MAX_KEPT_PIXELS * 2 / 3 / 4096).toInt()
        val f = SkinFiles(mapOf("a" to ByteArray(tall), "b" to ByteArray(tall)), platform)
        // a name not in the skin: null, nothing decoded
        assertNull(f.pixels("none"))
        assertNull(f.pixels(null))
        assertEquals(0, decoded)
        // asked twice: one decode, the same instance
        val a = f.pixels("a")
        assertTrue(a === f.pixels("a"))
        assertEquals(1, decoded)
        // b's 8 M pixels with a's 8 M are past the one budget the decoded images and whole pixels share: all let go
        f.pixels("b")
        assertEquals(2, decoded)
        val again = f.pixels("a")
        assertEquals(3, decoded)
        assertFalse(a === again)
        // the decoded image is not kept beside its whole pixels: two of 2/5 the budget each both stay (counted
        // twice, the second pushed the first out)
        val fifths = (SkinLimits.MAX_KEPT_PIXELS * 2 / 5 / 4096).toInt()
        val g = SkinFiles(mapOf("a" to ByteArray(fifths), "b" to ByteArray(fifths)), platform)
        val before = decoded
        val ga = g.pixels("a")
        g.pixels("b")
        assertTrue(ga === g.pixels("a"))
        assertEquals(2, decoded - before, "a and b decoded once each, a not again")
    }

    @Test
    fun yamlMergesAreHeldToABudget() {
        // an anchor of 1000 keys merged into 300 mappings: 300 000 copies asked, MAX_MERGED made
        val anchor = (0 until 1000).joinToString(", ") { "k$it: $it" }
        val text = "a: &a {$anchor}\nl:\n" + "- {<<: *a}\n".repeat(300)
        val l = (SkinYaml.parse(text) as Map<*, *>)["l"] as List<*>
        assertEquals(1000, (l[0] as Map<*, *>).size)
        val made = l.sumOf { (it as Map<*, *>).size }
        assertTrue(made <= SkinYaml.MAX_MERGED, "$made")
        // a block mapping's merge alike, a small one whole
        assertEquals(mapOf("x" to "1", "y" to "2"), (SkinYaml.parse("b: &b {x: 1}\nc:\n  <<: *b\n  y: 2\n") as Map<*, *>)["c"])
    }

    @Test
    fun yamlCommentAfterAnEscapedQuote() {
        // the \" inside the quotes ends nothing: the # is the text's, not a comment
        assertEquals("a \" b # c", (SkinYaml.parse("k: \"a \\\" b # c\"\n") as Map<*, *>)["k"])
    }

    @Test
    fun json() {
        val j = SkinJson.parse("""{"a": [1, -2.5e3, true, false, null], "b": "\u4e2d\"\\\/\n", "c": {}, "d": [],}""") as Map<*, *>
        assertEquals(listOf("1", "-2.5e3", true, false, null), j["a"])
        assertEquals("中\"\\/\n", j["b"])
        assertEquals(emptyMap<String, Any?>(), j["c"])
        assertEquals(emptyList<Any?>(), j["d"])
        // \u with a sign is no four hex digits
        for (bad in listOf("", "{", "[1 2]", "{a: 1}", "\"open", "tru", "{\"a\" 1}", "[1]x", "\"\\u12\"", "-", "\"\\", "\"\\u-001\"", "\"\\u+abc\"")) {
            assertNull(SkinJson.parse(bad), bad)
        }
        assertNull(SkinJson.parse("[".repeat(1000) + "]".repeat(1000)))
    }

    @Test
    fun cssColours() {
        assertEquals(0xFF112233.toInt(), SkinPatch.cssColor("#123"))
        assertEquals(0xFF112233.toInt(), SkinPatch.cssColor(" #112233 "))
        assertEquals(0x80112233.toInt(), SkinPatch.cssColor("#11223380"))
        assertEquals(0xFF0A141E.toInt(), SkinPatch.cssColor("rgb(10, 20, 30)"))
        assertEquals(0x800A141E.toInt(), SkinPatch.cssColor("rgba(10,20,30,0.5)"))
        assertEquals(0, SkinPatch.cssColor("transparent"))
        for (bad in listOf(null, "", "red", "#12", "#12345", "rgb(1,2)", "var(--x)")) assertNull(SkinPatch.cssColor(bad), bad)
    }

    @Test
    fun ninePatches() {
        val px = Pixels(14, 10)
        for (y in 1..8) for (x in 1..12) px.argb[y * 14 + x] = 0xFF8899AA.toInt()
        for (x in 4..9) px.argb[x] = 0xFF000000.toInt()
        val n = SkinPatch.nine(px)
        assertEquals(12 to 8, n.pixels.width to n.pixels.height)
        // across: the marks at 4..9 of the border are 3..8 inside, 3 in from the left, 12 − 1 − 8 = 3 from the right
        assertEquals(Pixels.Box(3, 0, 3, 0), n.insets)
        assertNull(SkinPatch.nine(Pixels(14, 10)).insets)
        assertEquals(2, SkinPatch.nine(Pixels(2, 2)).pixels.width)
    }

    @Test
    fun unwrapping() {
        val one = mapOf("top/a" to text("1"), "top/b/c" to text("2"), "top/" to text(""))
        assertEquals(setOf("a", "b/c"), SkinConverter.unwrapped(one).keys)
        val two = mapOf("x/a" to text("1"), "y/a" to text("2"))
        assertEquals(two, SkinConverter.unwrapped(two))
        val flat = mapOf("a" to text("1"))
        assertEquals(flat, SkinConverter.unwrapped(flat))
    }
}
