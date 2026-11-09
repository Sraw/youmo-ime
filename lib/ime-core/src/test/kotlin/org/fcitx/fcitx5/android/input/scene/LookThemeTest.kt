/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.input.theme.BarBase
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Gap
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyLabel
import org.fcitx.fcitx5.android.input.theme.KeyOverride
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.KindStyle
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.Palette
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.Text
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookThemeTest {

    // a built-in theme is its colours and LookDefaults: converted, the scene is the one of the 4.0 file
    // written by hand for it (material-light.xml, material-dark.xml), in every scene the styles tell apart
    @Test
    fun aBuiltInThemeDrawsAsItsHandWrittenFile() {
        for (file in listOf("material-light.xml", "material-dark.xml")) {
            val written = SceneData.theme(file)
            val look = LookDefaults.look(palette(written))
            val converted = theme(LookTheme.xml(look, written.name, written.dark))
            for (env in listOf(SceneData.portrait, SceneData.portrait.copy(flat = true), SceneData.portrait.copy(landscape = true, width = 2000.0))) {
                assertEquals("$file ${env.scenes}", SceneText.of(scene(written, env)), SceneText.of(scene(converted, env)))
            }
        }
    }

    @Test
    fun theKeysShapeAndPressAreTheLooks() {
        val look = LookDefaults.look(palette(SceneData.theme("material-light.xml"))).let {
            it.copy(keys = it.keys.copy(radiusDp = 8, borderDp = 2, shadowDp = 0, gap = Gap(5, 6, 1, 2), press = PressEffect.Ripple))
        }
        val q = key(scene(theme(LookTheme.xml(look, "T", false)), SceneData.portrait), "q")
        val d = SceneData.portrait.density
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
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
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
        val scene = scene(theme, SceneData.portrait)
        assertTrue(scene.base.any { it is Op.Picture })
        val q = key(scene, "q")
        // the letter's colour under its image where the image is not there, as theme 3.0 showed it
        val keyColor = Argb(look.palette.keyBackgroundColor)
        assertEquals(Op.NineSlice(q.shape, "key.png", listOf(1, 2, 3, 4), 2, keyColor), q.ops(false).filterIsInstance<Op.NineSlice>().single())
        // its own pressed face, not tinted over
        val pressed = q.ops(true).filterIsInstance<Op.NineSlice>()
        assertTrue(pressed.toString(), pressed.any { it.file == "key-down.png" && it.fallback == keyColor })
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
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
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
        val scene = scene(theme, SceneData.portrait)
        assertEquals(Argb(p.keyBackgroundColor), key(scene, "q").ops(false).filterIsInstance<Op.Picture>().single().over)
        val shift = key(scene, "shift")
        assertEquals(Argb(p.altKeyBackgroundColor), shift.ops(false).filterIsInstance<Op.NineSlice>().single().fallback)
        assertTrue(shift.ops(true).any { it is Op.NineSlice && it.file == "key-down.png" && it.fallback == Argb(p.altKeyBackgroundColor) })
        val backspace = key(scene, "backspace").ops(false)
        assertTrue(backspace.none { it is Op.Picture })
        assertTrue(backspace.toString(), backspace.any { it is Op.Fill && it.color == Argb(p.altKeyBackgroundColor) })
        assertTrue(key(scene, "enter").ops(true).any { it is Op.NineSlice && it.fallback == Argb(p.accentKeyBackgroundColor) })
        // a flat space's pressed image over the space bar's colour, as its face is
        val flat = scene(theme, SceneData.portrait.copy(flat = true))
        assertTrue(key(flat, "space").ops(true).any { it is Op.NineSlice && it.fallback == Argb(p.spaceBarColor) })
        // flat, a letter or function key has no face, its own or pressed (3.0's face=null), and no tint over a
        // pressed face of its own (3.0 drew none there); Return keeps its own
        fun shown(op: Op) = op is Op.Picture || op is Op.NineSlice || (op is Op.Fill && op.color.value ushr 24 != 0)
        for (id in listOf("q", "backspace")) assertTrue(id, key(flat, id).ops(false).none(::shown))
        for (id in listOf("w", "shift")) {
            assertTrue(id, key(flat, id).ops(false).none(::shown))
            assertTrue(id, key(flat, id).ops(true).none(::shown))
        }
        assertTrue(key(flat, "enter").ops(true).any { it is Op.NineSlice && it.fallback == Argb(p.accentKeyBackgroundColor) })
        // a ripple theme's press still ripples there
        val rippled = theme(LookTheme.xml(look.copy(keys = look.keys.copy(press = PressEffect.Ripple)), "T", false))
        val flatRipple = scene(rippled, SceneData.portrait.copy(flat = true))
        for (id in listOf("w", "shift")) {
            val ops = key(flatRipple, id).ops(true)
            assertTrue("$id $ops", ops.none(::shown) && ops.any { it is Op.Ripple })
        }
    }

    // a single key's Darken over its kind's pressed image: its own face tinted, as 3.0's own won
    @Test
    fun aSingleKeysDarkenIsItsOwn() {
        val down = NineSlice("/data/themes/t/key-down.png", Insets(1, 1, 1, 1), 1)
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
        val look = base.copy(
            keys = base.keys.copy(
                letter = KindStyle(KeyFill.Color, KeyPressed.Image(down)),
                overrides = mapOf("qwerty/e" to KeyOverride(null, KeyPressed.Darken, KeyLabel.Show)),
            ),
        )
        val scene = scene(theme(LookTheme.xml(look, "T", false)), SceneData.portrait)
        assertTrue(key(scene, "q").ops(true).any { it is Op.NineSlice })
        val e = key(scene, "e").ops(true)
        assertTrue("$e", e.none { it is Op.NineSlice })
        assertTrue("$e", e.any { it is Op.Fill && it.color == Argb(look.palette.keyBackgroundColor) })
        assertTrue("$e", e.any { it is Op.Fill && it.color == Argb(look.palette.keyPressHighlightColor) })
    }

    // the most single keys a 3.0 theme brings (ThemeAssetLimits), each a face and a pressed one of its own:
    // the theme written still reads, its styles more than a theme file's
    @Test
    fun theMostSingleKeysOfTheirOwnRead() {
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
        val overrides = (0 until ThemeAssetLimits.MAX_OVERRIDES).associate { i ->
            "b$i/k" to KeyOverride(
                KeyFill.Image(NineSlice("/data/themes/t/k$i.png", Insets(1, 1, 1, 1), 1)),
                KeyPressed.Image(NineSlice("/data/themes/t/d$i.png", Insets(1, 1, 1, 1), 1)),
                KeyLabel.Hide,
            )
        }
        val xml = LookTheme.xml(base.copy(keys = base.keys.copy(overrides = overrides)), "T", false)
        val styles = Regex("<style ").findAll(xml).count()
        assertTrue("$styles", styles in (ThemeReader.MAX_STYLES + 1)..LookTheme.MAX_STYLES)
        assertTrue(ThemeReader.theme(xml, maxStyles = LookTheme.MAX_STYLES) is Parsed.Ok)
    }

    // 3.0's single keys by their names (KeyNames): 大千's , . ; / - selected as the keys of those ids, a
    // selector's comma not read as the list's (060-render-p2 S4 step 6)
    @Test
    fun aSingleKeyOfAnyNameIsTheKeyOfThatId() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 2, 3, 4), 2)
        val names = listOf(",", ".", ";", "/", "-", "q")
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
        val look = base.copy(keys = base.keys.copy(overrides = names.associate { "zhuyin/$it" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide) }))
        val layout = (LayoutReader.layout(SceneData.read("zhuyin.xml")) as Parsed.Ok).value
        val bottom = (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value
        val built = SceneBuilder(theme(LookTheme.xml(look, "T", false)), SceneData.portrait, SceneData.measurer).build(layout, mapOf("grouped-bottom" to bottom))
        val scene = (built as Parsed.Ok).value
        for (id in names) {
            val k = key(scene, id)
            assertTrue(id, k.ops(false).any { it is Op.NineSlice })
            assertTrue(id, k.ops(false).none { it is Op.Text })
        }
        // the others their kind's: the comma of the bottom row (its id "comma") too
        for (id in listOf("w", "comma", "period")) assertTrue(id, key(scene, id).ops(false).none { it is Op.NineSlice })
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
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
        val look = base.copy(keys = base.keys.copy(overrides = names.associate { "zhuyin-nine/$it" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide) }))
        val layout = (LayoutReader.layout(SceneData.read("zhuyin-nine.xml")) as Parsed.Ok).value
        val bottom = (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value
        val built = SceneBuilder(theme(LookTheme.xml(look, "T", false)), SceneData.portrait, SceneData.measurer).build(layout, mapOf("grouped-bottom" to bottom))
        val scene = (built as Parsed.Ok).value
        for (id in names) assertTrue(id, key(scene, id).ops(false).any { it is Op.NineSlice })
        for (id in listOf("r2c1", "r1c3", "r3c5", "comma")) assertTrue(id, key(scene, id).ops(false).none { it is Op.NineSlice })
    }

    // 3.0's single keys of the number pad (060-render-p2 S4b): number/* , . : each selects the one key of
    // that id and no other (number/* unescaped was every key of the pad, a comma the selector's list)
    @Test
    fun theNumberPadsSingleKeysAreThoseKeysAlone() {
        val slice = NineSlice("/data/themes/t/key.png", Insets(1, 2, 3, 4), 2)
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
        val layout = (LayoutReader.layout(SceneData.read("number.xml")) as Parsed.Ok).value
        // : is the date and time pad's, the others the full pad's
        for ((id, pad) in listOf("*" to "full", "," to "full", "." to "full", ":" to "datetime")) {
            val look = base.copy(keys = base.keys.copy(overrides = mapOf("number/$id" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide))))
            val env = SceneData.portrait.copy(input = SceneData.portrait.input.copy(pad = pad))
            val built = SceneBuilder(theme(LookTheme.xml(look, "T", false)), env, SceneData.measurer).build(layout, emptyMap())
            val scene = (built as Parsed.Ok).value
            val imaged = scene.keys.filter { k -> k.ops(false).any { it is Op.NineSlice } }.map { it.id }
            assertEquals("number/$id", listOf(id), imaged)
            assertTrue(id, key(scene, id).ops(false).none { it is Op.Text })
        }
    }

    // two images of one name in two folders stay two: the second numbered, in the theme written and the app's lookup alike
    @Test
    fun filesOfOneNameInTwoFoldersKeptApart() {
        val base = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
        val look = base.copy(
            keys = base.keys.copy(
                letter = KindStyle(KeyFill.Image(NineSlice("/t/a/key.png", Insets(1, 1, 1, 1), 1)), KeyPressed.Image(NineSlice("/t/b/key.png", Insets(0, 0, 0, 0), 1))),
                function = KindStyle(KeyFill.Image(NineSlice("/t/c/key", Insets(0, 0, 0, 0), 1)), KeyPressed.Image(NineSlice("/t/d/key", Insets(0, 0, 0, 0), 1))),
            ),
        )
        val names = LookTheme.fileNames(look)
        assertEquals(mapOf("/t/a/key.png" to "key.png", "/t/b/key.png" to "key~2.png", "/t/c/key" to "key", "/t/d/key" to "key~2"), names)
        val q = key(scene(theme(LookTheme.xml(look, "T", false)), SceneData.portrait), "q")
        assertEquals("key.png", q.ops(false).filterIsInstance<Op.NineSlice>().single().file)
        assertTrue("key~2.png" in q.ops(true).filterIsInstance<Op.NineSlice>().map { it.file })
    }

    @Test
    fun fileNamesLoseTheirFolders() {
        assertEquals("a.png", LookTheme.fileName("/x/y/a.png"))
        assertEquals("a.png", LookTheme.fileName("a.png"))
        assertEquals(24, LookTheme.colors(palette(SceneData.theme("material-light.xml"))).size)
    }

    @Test
    fun aLookWithoutPictureHasNoPictureElement() {
        val look = LookDefaults.look(palette(SceneData.theme("material-light.xml")))
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
        when (val s = SceneBuilder(theme, env, SceneData.measurer).build(SceneData.qwerty, emptyMap())) {
            is Parsed.Ok -> s.value
            is Parsed.Failed -> error(s.problems.toString())
        }

    companion object {
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
