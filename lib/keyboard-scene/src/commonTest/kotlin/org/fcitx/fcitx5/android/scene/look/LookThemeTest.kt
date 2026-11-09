/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LookThemeTest {

    // a built-in theme is its colours and LookDefaults: converted, the scene is the one of the 4.0 file
    // written by hand for it (material-light.xml, material-dark.xml), in every scene the styles tell apart
    @Test
    fun aBuiltInThemeDrawsAsItsHandWrittenFile() {
        for (file in listOf("material-light.xml", "material-dark.xml")) {
            val written = themeFile(file)
            val look = LookDefaults.look(palette(written))
            val converted = theme(LookTheme.xml(look, written.name, written.dark))
            for (env in listOf(portrait, portrait.copy(flat = true), portrait.copy(landscape = true, width = 2000.0))) {
                assertEquals(SceneText.of(scene(written, env)), SceneText.of(scene(converted, env)), "$file ${env.scenes}")
            }
        }
    }

    @Test
    fun theKeysShapeAndPressAreTheLooks() {
        val look = LookDefaults.look(palette(themeFile("material-light.xml"))).let {
            it.copy(keys = it.keys.copy(radiusDp = 8, borderDp = 2, shadowDp = 0, gap = Gap(5, 6, 1, 2), press = PressEffect.Ripple))
        }
        val q = key(scene(theme(LookTheme.xml(look, "T", false)), portrait), "q")
        val d = portrait.density
        val stroke = q.ops(false).filterIsInstance<Op.Stroke>().single()
        assertEquals(2 * d, stroke.width, 1e-9)
        assertTrue(q.ops(false).none { it is Op.Shadow })
        // inset 6dp top, 5dp left
        val shape = q.shape as org.fcitx.fcitx5.android.scene.Shape.Rect
        assertEquals(q.bounds.top + 6 * d, shape.box.top, 1e-6)
        assertEquals(q.bounds.left + 5 * d, shape.box.left, 1e-6)
        assertEquals(8 * d, shape.radii[0], 1e-6)
        assertTrue(q.ops(true).any { it is Op.Ripple })
    }

    @Test
    fun imagesPicturesAndPressedFacesOfTheirOwn() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 2, 3, 4), 2)
        val pressedSlice = NineSlice("/data/themes/t/key-down.png", Insets(0, 0, 0, 0), 1)
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val look = base.copy(
            picture = Picture("/data/themes/t/src.jpg", Crop("/data/themes/t/p.jpg", null, 0), Crop("/data/themes/t/l.jpg", null, 0), 80, 5),
            base = BoardBase.Picture,
            bar = BarBase.Picture(3, 70),
            keys = base.keys.copy(
                letter = KindStyle(KeyFill.Image(slice), KeyPressed.Image(pressedSlice)),
                function = KindStyle(KeyFill.Picture(4, 90), KeyPressed.Color(0xff112233.toInt())),
                overrides = mapOf(
                    "qwerty/w" to KeyOverride(KeyFill.Color, null, KeyLabel.Hide),
                    "*/space" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Show),
                ),
            ),
            text = Text(keyFont = "/data/themes/t/f.ttf", candidateFont = null, keyWeight = 700, candidateWeight = 400, keyScale = 120),
        )
        val theme = theme(LookTheme.xml(look, "A \"name\" & <more>", true))
        assertEquals("A \"name\" & <more>", theme.name)
        assertEquals(mapOf("portrait" to "p.jpg", "landscape" to "l.jpg"), theme.picture?.cuts)
        assertEquals("src.jpg", theme.picture?.src)
        assertEquals(mapOf(LookTheme.KEY_FONT to "f.ttf"), theme.fonts)
        val scene = scene(theme, portrait)
        assertTrue(scene.base.any { it is Op.Picture })
        val q = key(scene, "q")
        // the letter's colour under its image where the image is not there, as theme 3.0 showed it
        val keyColor = Argb(look.palette.keyBackgroundColor)
        assertEquals(Op.NineSlice(q.shape, "key.png", listOf(1, 2, 3, 4), 2, keyColor), q.ops(false).filterIsInstance<Op.NineSlice>().single())
        // its own pressed face, not tinted over
        val pressed = q.ops(true).filterIsInstance<Op.NineSlice>()
        assertTrue(pressed.any { it.file == "key-down.png" && it.fallback == keyColor }, pressed.toString())
        val text = q.ops(false).filterIsInstance<Op.Text>().first()
        assertEquals(LookTheme.KEY_FONT, text.font)
        assertEquals(700, text.weight)
        val shift = key(scene, "shift")
        // the function keys' colour laid over their picture, as theme 3.0's face did
        assertEquals(Argb(look.palette.altKeyBackgroundColor), shift.ops(false).filterIsInstance<Op.Picture>().single().over)
        assertTrue(shift.ops(true).any { it is Op.Fill && it.color.value == 0xff112233.toInt() })
        // w's label hidden, its corner's symbol too (a hidden label hides the symbol: rules B §5); its face
        // fill=Color, its kind's colour over the kind's image (theme 3.0: own fill or the kind's colour)
        val w = key(scene, "w")
        assertTrue(w.ops(false).filterIsInstance<Op.Text>().none { it.text == "w" })
        assertTrue(w.ops(false).none { it is Op.NineSlice })
        assertTrue(w.ops(false).any { it is Op.Fill && it.color == keyColor })
        assertTrue(key(scene, "space").ops(false).any { it is Op.NineSlice })
    }

    // a single key's own face over its kind's colour, as theme 3.0's (ThemeColors.face for every key):
    // under its picture, while its image is not there, and as fill=Color over a kind's picture or image
    @Test
    fun aSingleKeysFaceHasItsKindsColour() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 1, 1, 1), 1)
        val down = NineSlice("/data/themes/t/key-down.png", Insets(1, 1, 1, 1), 1)
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val look = base.copy(
            picture = Picture("/data/themes/t/src.jpg", Crop("/data/themes/t/p.jpg", null, 0), null, 80, 0),
            keys = base.keys.copy(
                function = KindStyle(KeyFill.Picture(4, 90), KeyPressed.Darken),
                space = KindStyle(KeyFill.Color, KeyPressed.Image(down)),
                overrides = mapOf(
                    "qwerty/q" to KeyOverride(KeyFill.Picture(2, 80), null, KeyLabel.Show),
                    "*/shift" to KeyOverride(KeyFill.Image(slice), KeyPressed.Image(down), KeyLabel.Show),
                    "*/backspace" to KeyOverride(KeyFill.Color, null, KeyLabel.Show),
                    "*/enter" to KeyOverride(null, KeyPressed.Image(down), KeyLabel.Show),
                    "qwerty/w" to KeyOverride(KeyFill.Image(slice), KeyPressed.Color(0xff445566.toInt()), KeyLabel.Show),
                ),
            ),
        )
        val p = look.palette
        val theme = theme(LookTheme.xml(look, "T", false))
        val scene = scene(theme, portrait)
        assertEquals(Argb(p.keyBackgroundColor), key(scene, "q").ops(false).filterIsInstance<Op.Picture>().single().over)
        val shift = key(scene, "shift")
        assertEquals(Argb(p.altKeyBackgroundColor), shift.ops(false).filterIsInstance<Op.NineSlice>().single().fallback)
        assertTrue(shift.ops(true).any { it is Op.NineSlice && it.file == "key-down.png" && it.fallback == Argb(p.altKeyBackgroundColor) })
        val backspace = key(scene, "backspace").ops(false)
        assertTrue(backspace.none { it is Op.Picture })
        assertTrue(backspace.any { it is Op.Fill && it.color == Argb(p.altKeyBackgroundColor) }, backspace.toString())
        assertTrue(key(scene, "enter").ops(true).any { it is Op.NineSlice && it.fallback == Argb(p.accentKeyBackgroundColor) })
        // a flat space's pressed image over the space bar's colour, as its face is
        val flat = scene(theme, portrait.copy(flat = true))
        assertTrue(key(flat, "space").ops(true).any { it is Op.NineSlice && it.fallback == Argb(p.spaceBarColor) })
        // flat, a letter or function key has no face, its own or pressed (3.0's face=null), and no tint over a
        // pressed face of its own (3.0 drew none there); Return keeps its own
        fun shown(op: Op) = op is Op.Picture || op is Op.NineSlice || (op is Op.Fill && op.color.value ushr 24 != 0)
        for (id in listOf("q", "backspace")) assertTrue(key(flat, id).ops(false).none(::shown), id)
        for (id in listOf("w", "shift")) {
            assertTrue(key(flat, id).ops(false).none(::shown), id)
            assertTrue(key(flat, id).ops(true).none(::shown), id)
        }
        assertTrue(key(flat, "enter").ops(true).any { it is Op.NineSlice && it.fallback == Argb(p.accentKeyBackgroundColor) })
        // a ripple theme's press still ripples there
        val rippled = theme(LookTheme.xml(look.copy(keys = look.keys.copy(press = PressEffect.Ripple)), "T", false))
        val flatRipple = scene(rippled, portrait.copy(flat = true))
        for (id in listOf("w", "shift")) {
            val ops = key(flatRipple, id).ops(true)
            assertTrue(ops.none(::shown) && ops.any { it is Op.Ripple }, "$id $ops")
        }
    }

    // a single key's Darken over its kind's pressed image: its own face tinted, as 3.0's own won
    @Test
    fun aSingleKeysDarkenIsItsOwn() {
        val down = NineSlice("/data/themes/t/key-down.png", Insets(1, 1, 1, 1), 1)
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val look = base.copy(
            keys = base.keys.copy(
                letter = KindStyle(KeyFill.Color, KeyPressed.Image(down)),
                overrides = mapOf("qwerty/e" to KeyOverride(null, KeyPressed.Darken, KeyLabel.Show)),
            ),
        )
        val scene = scene(theme(LookTheme.xml(look, "T", false)), portrait)
        assertTrue(key(scene, "q").ops(true).any { it is Op.NineSlice })
        val e = key(scene, "e").ops(true)
        assertTrue(e.none { it is Op.NineSlice }, "$e")
        assertTrue(e.any { it is Op.Fill && it.color == Argb(look.palette.keyBackgroundColor) }, "$e")
        assertTrue(e.any { it is Op.Fill && it.color == Argb(look.palette.keyPressHighlightColor) }, "$e")
    }

    // the most single keys a 3.0 theme brings (Keys), each a face and a pressed one of its own:
    // the theme written still reads, its styles more than a theme file's
    @Test
    fun theMostSingleKeysOfTheirOwnRead() {
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val overrides = (0 until Keys.MAX_OVERRIDES).associate { i ->
            "b$i/k" to KeyOverride(
                KeyFill.Image(NineSlice("/data/themes/t/k$i.png", Insets(1, 1, 1, 1), 1)),
                KeyPressed.Image(NineSlice("/data/themes/t/d$i.png", Insets(1, 1, 1, 1), 1)),
                KeyLabel.Hide,
            )
        }
        val xml = LookTheme.xml(base.copy(keys = base.keys.copy(overrides = overrides)), "T", false)
        val styles = Regex("<style ").findAll(xml).count()
        assertTrue(styles in (ThemeReader.MAX_STYLES + 1)..LookTheme.MAX_STYLES, "$styles")
        assertTrue(ThemeReader.theme(xml, maxStyles = LookTheme.MAX_STYLES) is Parsed.Ok)
    }

    // 3.0's single keys by their names (KeyNames): 大千's , . ; / - selected as the keys of those ids, a
    // selector's comma not read as the list's (060-render-p2 S4 step 6)
    @Test
    fun aSingleKeyOfAnyNameIsTheKeyOfThatId() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 2, 3, 4), 2)
        val names = listOf(",", ".", ";", "/", "-", "q")
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val look = base.copy(keys = base.keys.copy(overrides = names.associate { "zhuyin/$it" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide) }))
        val layout = (LayoutReader.layout(testData("zhuyin.xml")) as Parsed.Ok).value
        val bottom = (LayoutReader.fragment(testData("grouped-bottom.xml")) as Parsed.Ok).value
        val built = SceneBuilder(theme(LookTheme.xml(look, "T", false)), portrait, measurer).build(layout, mapOf("grouped-bottom" to bottom))
        val scene = (built as Parsed.Ok).value
        for (id in names) {
            val k = key(scene, id)
            assertTrue(k.ops(false).any { it is Op.NineSlice }, id)
            assertTrue(k.ops(false).none { it is Op.Text }, id)
        }
        // the others their kind's: the comma of the bottom row (its id "comma") too
        for (id in listOf("w", "comma", "period")) assertTrue(key(scene, id).ops(false).none { it is Op.NineSlice }, id)
        // number/* the pad's star, not every key of the pad; a role and a plain key as written
        assertEquals("number/\\*", LookTheme.selector("number/*"))
        assertEquals("*/space", LookTheme.selector("*/space"))
        assertEquals("zhuyin/q", LookTheme.selector("zhuyin/q"))
        assertEquals("zhuyin//", LookTheme.selector("zhuyin//"))
    }

    // 注音九键's 3.0 single keys by their place in its grid (zhuyin-nine/r1c1: a tone, r1c2 a group, r4c4 the
    // slot, r2c5 ?; ⌫ by its role), selected as the keys of those ids (060-render-p2 S4d, step 6)
    @Test
    fun zhuyinNinesSingleKeysAreTheKeysOfTheirPlaces() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 2, 3, 4), 2)
        val names = listOf("r1c1", "r1c2", "r4c4", "r2c5", "backspace")
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val look = base.copy(keys = base.keys.copy(overrides = names.associate { "zhuyin-nine/$it" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide) }))
        val layout = (LayoutReader.layout(testData("zhuyin-nine.xml")) as Parsed.Ok).value
        val bottom = (LayoutReader.fragment(testData("grouped-bottom.xml")) as Parsed.Ok).value
        val built = SceneBuilder(theme(LookTheme.xml(look, "T", false)), portrait, measurer).build(layout, mapOf("grouped-bottom" to bottom))
        val scene = (built as Parsed.Ok).value
        for (id in names) assertTrue(key(scene, id).ops(false).any { it is Op.NineSlice }, id)
        for (id in listOf("r2c1", "r1c3", "r3c5", "comma")) assertTrue(key(scene, id).ops(false).none { it is Op.NineSlice }, id)
    }

    // 3.0's single keys of the number pad (060-render-p2 S4b): number/* , . : each selects the one key of
    // that id and no other (number/* unescaped was every key of the pad, a comma the selector's list)
    @Test
    fun theNumberPadsSingleKeysAreThoseKeysAlone() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 2, 3, 4), 2)
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val layout = (LayoutReader.layout(testData("number.xml")) as Parsed.Ok).value
        // : is the date and time pad's, the others the full pad's
        for ((id, pad) in listOf("*" to "full", "," to "full", "." to "full", ":" to "datetime")) {
            val look = base.copy(keys = base.keys.copy(overrides = mapOf("number/$id" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide))))
            val env = portrait.copy(input = portrait.input.copy(pad = pad))
            val built = SceneBuilder(theme(LookTheme.xml(look, "T", false)), env, measurer).build(layout, emptyMap())
            val scene = (built as Parsed.Ok).value
            val imaged = scene.keys.filter { k -> k.ops(false).any { it is Op.NineSlice } }.map { it.id }
            assertEquals(listOf(id), imaged, "number/$id")
            assertTrue(key(scene, id).ops(false).none { it is Op.Text }, id)
        }
    }

    // two images of one name in two folders stay two: the second numbered, in the theme written and the app's lookup alike
    @Test
    fun filesOfOneNameInTwoFoldersKeptApart() {
        val base = LookDefaults.look(palette(themeFile("material-light.xml")))
        val look = base.copy(
            keys = base.keys.copy(
                letter = KindStyle(KeyFill.Image(NineSlice("/t/a/key.png", Insets(1, 1, 1, 1), 1)), KeyPressed.Image(NineSlice("/t/b/key.png", Insets(0, 0, 0, 0), 1))),
                function = KindStyle(KeyFill.Image(NineSlice("/t/c/key", Insets(0, 0, 0, 0), 1)), KeyPressed.Image(NineSlice("/t/d/key", Insets(0, 0, 0, 0), 1))),
            ),
        )
        val names = LookTheme.fileNames(look)
        assertEquals(mapOf("/t/a/key.png" to "key.png", "/t/b/key.png" to "key~2.png", "/t/c/key" to "key", "/t/d/key" to "key~2"), names)
        val q = key(scene(theme(LookTheme.xml(look, "T", false)), portrait), "q")
        assertEquals("key.png", q.ops(false).filterIsInstance<Op.NineSlice>().single().file)
        assertTrue("key~2.png" in q.ops(true).filterIsInstance<Op.NineSlice>().map { it.file })
    }

    @Test
    fun fileNamesLoseTheirFolders() {
        assertEquals("a.png", LookTheme.fileName("/x/y/a.png"))
        assertEquals("a.png", LookTheme.fileName("a.png"))
        assertEquals(24, LookTheme.colors(palette(themeFile("material-light.xml"))).size)
    }

    @Test
    fun aLookWithoutPictureHasNoPictureElement() {
        val look = LookDefaults.look(palette(themeFile("material-light.xml")))
        val xml = LookTheme.xml(look, "T", false)
        assertFalse("<picture" in xml)
        assertFalse("<font" in xml)
    }

    private fun key(scene: Scene, id: String) = scene.keys.first { it.id == id }

    private fun theme(xml: String): Theme = when (val t = ThemeReader.theme(xml)) {
        is Parsed.Ok -> t.value
        is Parsed.Failed -> error("${t.problems}\n$xml")
    }

    private fun scene(theme: Theme, env: Env): Scene =
        when (val s = SceneBuilder(theme, env, measurer).build(Fixtures.qwerty, emptyMap())) {
            is Parsed.Ok -> s.value
            is Parsed.Failed -> error(s.problems.toString())
        }

    companion object {
        /** Every character half the size wide, its ink the em box: what is measured does not matter here, only that both sides measure alike. */
        val measurer = TextMeasurer { text, _, _, size ->
            val w = text.length * size / 2
            TextMetrics(w, Box(0.0, -size * 0.7, w, 0.0), -size, size * 0.3)
        }

        val portrait = Env(1080.0, 720.0, 2.625, input = Input(langKey = true), texts = Texts(imeName = "拼音", returnLabel = "换行"))

        fun themeFile(name: String): Theme = ThemeReader.theme(testData(name)).ok()

        /** A 4.0 theme's colours as a 3.0 palette: the built-in themes' palettes. */
        fun palette(t: Theme): Palette {
            fun c(n: String) = t.colors.getValue(n).value
            return Palette(
                c("background"), c("bar"), c("keyboard"), c("key"), c("keyText"), c("candidateText"), c("candidateLabel"),
                c("candidateComment"), c("altKey"), c("altKeyText"), c("accentKey"), c("accentKeyText"), c("keyPressHighlight"),
                c("keyShadow"), c("popupBackground"), c("popupText"), c("spaceBar"), c("divider"), c("clipboardEntry"),
                c("genericActiveBackground"), c("genericActiveForeground"), c("keyBorder"), c("keyHint"), c("toolbarIcon"),
            )
        }
    }
}
