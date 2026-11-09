/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.IconSizes
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.Paint
import org.fcitx.fcitx5.android.scene.engine.PopupBuilder
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The popups' scenes (rules D §4): their sizes, as theme 3.0's views had them, and their looks the theme's. */
class PopupsTest {
    private val d = Forms.DENSITY
    private val theme = Fixtures.materialLight

    private fun builder(env: Env = Forms.block, measurer: TextMeasurer = FakeMeasurer) = SceneBuilder(theme, env, measurer)

    private fun color(name: String) = theme.colors.getValue(name)

    private fun px(dp: Double) = floor(dp * d)

    private fun Scene.texts() = keys.flatMap { it.ops(false) }.filterIsInstance<Op.Text>()

    @Test
    fun aBubbleIsAsWideAsTheKeyItsTextOrTheLeastWhicheverIsWidest() {
        val b = builder()
        // a narrow key, one letter: the least
        assertEquals(px(Popups.PREVIEW_MIN_WIDTH_DP), b.preview("g", 20.0).ok().width)
        // a wide key
        assertEquals(300.0, b.preview("g", 300.0).ok().width)
        // words wider than the key: their width and 6 dp a side
        val size = 16 * d
        val words = b.preview("abcdefghij", 20.0, size).ok()
        assertEquals(ceil(10 * 0.6 * size) + 2 * px(Popups.TEXT_PADDING_DP), words.width)
        assertEquals(px(Popups.PREVIEW_HEIGHT_DP), words.height)
    }

    @Test
    fun theBubblesTextIsInItsTopRowCentred() {
        val s = builder().preview("ab", 200.0).ok()
        val t = s.texts().single()
        val size = Popups.SYMBOL_DP * d
        assertEquals(size, t.size, 1e-9)
        // longer than a character: centred by its advance (AutoScaleTextView), upright by the font's extent
        val advance = 2 * 0.6 * size
        assertEquals(100 - ceil(advance) / 2, t.x, 1e-9)
        val row = px(Popups.PREVIEW_ROW_DP)
        assertEquals(row / 2 - (-1.05 * size + 0.3 * size) / 2, t.baseline, 1e-9)
        assertEquals(color("popupText"), t.color)
    }

    @Test
    fun aCharacterIsAsLargeAsTheKeysTextIsScaled() {
        fun size(env: Env) = builder(env).preview("g", 100.0).ok().texts().single().size
        assertEquals(Popups.SYMBOL_DP * d, size(Forms.block), 1e-9)
        assertEquals(Popups.SYMBOL_DP * d * 0.9, size(Forms.compact), 1e-9)
        assertEquals(Popups.SYMBOL_DP * d * 0.85, size(Forms.floating), 1e-9)
        val (words, least) = builder(Forms.compact).previewWordsSize().ok()
        assertEquals(Popups.WORDS_DP * d * 0.9, words, 1e-9)
        assertEquals(Popups.MIN_WORDS_DP * d * 0.9, least, 1e-9)
    }

    // 060-render-p2 §5.3: the bubble's corners the keys' (the theme's radius), its shadow drawn
    @Test
    fun theBubbleIsThemeShapedAndShadowed() {
        val bubble = builder().preview("g", 100.0).ok().keys.single()
        val shape = assertIs<Shape.Rect>(bubble.shape)
        assertEquals(List(4) { 4 * d }, shape.radii)
        val ops = bubble.ops(false)
        val shadow = assertIs<Op.Shadow>(ops.first())
        assertEquals(2 * d, shadow.blur, 1e-9)
        assertEquals(shape.box.bottom + 2 * d, shadow.shape.bounds.bottom, 1e-9)
        assertEquals(Op.Fill(shape, color("popupBackground")), ops[1])
        assertEquals("g", bubble.label)
    }

    private fun styled(styles: String) = SceneBuilder(
        ThemeReader.theme(testData("material-light.xml").replace("</theme>", "$styles\n</theme>")).ok(),
        Forms.block,
        FakeMeasurer,
    )

    // 060-render-p3 T4b: `shape="bubble"`, a rounded head over the key, a tail down from its middle
    @Test
    fun aBubblesHeadIsItsTopRowItsTailDownToTheKey() {
        val plain = builder().preview("g", 100.0).ok()
        val s = styled("<style select=\"preview\" shape=\"bubble\"/>").preview("g", 100.0).ok()
        assertEquals(plain.width, s.width)
        assertEquals(plain.height, s.height, "the scene as tall: the app puts it as ever")
        val bubble = s.keys.single()
        val shape = assertIs<Shape.Path>(bubble.shape)
        val row = px(Popups.PREVIEW_ROW_DP)
        val tail = Popups.TAIL_DP * d
        assertEquals(Box(0.0, 0.0, 100.0, row + tail), shape.bounds)
        assertTrue(shape.contains(50.0, row + tail - 2) && !shape.contains(30.0, row + 2), "the tail in the middle, narrow")
        assertFalse(shape.contains(0.5, 0.5), "its corners the theme's radius")
        // drawn: its shadow, its fill, all the bubble's shape; the text in the head
        val ops = bubble.ops(false)
        assertEquals(Op.Fill(shape, color("popupBackground")), ops[1])
        assertEquals(shape.moved(0.0, 2 * d), assertIs<Op.Shadow>(ops[0]).shape)
        assertEquals(plain.texts().single(), s.texts().single())
        // tail="false": the head alone, rounded
        val head = styled("<style select=\"preview\" shape=\"bubble\" tail=\"false\"/>").preview("g", 100.0).ok().keys.single()
        assertEquals(Shape.Rect(Box(0.0, 0.0, 100.0, row), List(4) { 4 * d }), head.shape)
        // the tail a third of a narrow head at most, clear of its corners
        val narrow = styled("<style select=\"preview\" shape=\"bubble\" radius=\"50%\"/>").preview("g", 20.0).ok().keys.single()
        assertIs<Shape.Rect>(narrow.shape, "no room for a tail between the corners")
        // a path: the theme's outline in the bubble's box
        val path = styled("<style select=\"preview\" shape=\"path(M0 0 H1 V.5 L.5 1 L0 .5 Z)\"/>").preview("g", 100.0).ok().keys.single()
        assertEquals(Box(0.0, 0.0, 100.0, plain.height), assertIs<Shape.Path>(path.shape).bounds)
    }

    // a bubble's border inside it, its middle half its width in, as a rectangle's is: its tail's too
    @Test
    fun aBubblesBorderIsInsideIt() {
        val bubble = styled("<style select=\"preview\" shape=\"bubble\" border=\"2dp #ff000000\"/>").preview("g", 100.0).ok().keys.single()
        val face = assertIs<Shape.Path>(bubble.shape)
        val line = assertIs<Shape.Path>(bubble.ops(false).filterIsInstance<Op.Stroke>().single().shape)
        val w = d
        val row = px(Popups.PREVIEW_ROW_DP)
        val tail = Popups.TAIL_DP * d
        val b = line.bounds
        assertEquals(w, b.left, 1e-9)
        assertEquals(w, b.top, 1e-9)
        assertEquals(100.0 - w, b.right, 1e-9)
        // the tip up by w over the sine of the tail's half angle, more than w: its edges w in
        val half = minOf(Popups.TAIL_WIDTH_DP * d / 2, 100.0 / 6)
        assertEquals(row + tail - w * kotlin.math.hypot(half, tail) / half, b.bottom, 1e-9)
        for (op in line.ops) {
            val (x, y) = when (op) {
                is Shape.Path.Op.MoveTo -> op.x to op.y
                is Shape.Path.Op.LineTo -> op.x to op.y
                Shape.Path.Op.Close -> continue
            }
            assertTrue(face.contains(x, y), "($x, $y) inside the bubble")
        }
    }

    // `size`: the whole bubble larger (its text, padding, width and top row, the row up), `offset` it moved
    @Test
    fun aBubblesSizeAndOffset() {
        val plain = builder().preview("g", 100.0).ok()
        val big = styled("<style select=\"preview\" size=\"150%\"/>").preview("g", 100.0).ok()
        assertEquals(150.0, big.width)
        val row = px(Popups.PREVIEW_ROW_DP)
        assertEquals(plain.height + ceil(row * 1.5 - row), big.height, "taller by the row's growth: up")
        val t = big.texts().single()
        assertEquals(plain.texts().single().size * 1.5, t.size, 1e-9)
        assertEquals(row * 1.5 / 2 - (-1.05 * t.size + 0.3 * t.size) / 2, t.baseline, 1e-9)
        // its width whole pixels, as the view's is: up to the next where scaled to a fraction
        assertEquals(ceil(px(Popups.PREVIEW_MIN_WIDTH_DP) * 1.6), styled("<style select=\"preview\" size=\"160%\"/>").preview("g", 1.0).ok().width, 1e-9)
        val words = styled("<style select=\"preview\" size=\"120%\"/>").preview("abcdefghij", 20.0, 16 * d).ok()
        assertEquals(ceil(ceil(10 * 0.6 * 16 * d * 1.2) + 2 * px(Popups.TEXT_PADDING_DP) * 1.2), words.width, 1e-9)
        val larger = styled("<style select=\"preview\" size=\"130%\"/>")
        assertEquals(129.0, larger.preview("g", 97.0).ok().width, "the least, 99 px, by 1.3: 128.7")
        assertEquals(257.0, larger.preview("g", 197.0).ok().width, "the key's 197 px by 1.3: 256.1")
        // up: the scene taller by it, the bubble as tall, clear of the key's bottom
        val up = styled("<style select=\"preview\" offset=\"-4dp\"/>").preview("g", 100.0).ok()
        val by = px(4.0)
        assertEquals(plain.height + by, up.height)
        assertEquals(plain.keys.single().shape, up.keys.single().shape)
        // down: the scene shorter, the bubble as tall, its bottom past the scene's (the app draws it in the room round it)
        val down = styled("<style select=\"preview\" offset=\"4dp\"/>").preview("g", 100.0).ok()
        assertEquals(plain.height - by, down.height)
        assertEquals(plain.keys.single().shape, down.keys.single().shape)
        assertEquals(plain.height, down.keys.single().ops(false).filterIsInstance<Op.Fill>().single().shape.bounds.bottom)
        // a bubble's head moved down as much, its tail too, the scene's top moved
        val bubble = styled("<style select=\"preview\" shape=\"bubble\" offset=\"4dp\"/>").preview("g", 100.0).ok()
        assertEquals(plain.height - by, bubble.height)
        val path = styled("<style select=\"preview\" shape=\"path(M0 0 H1 V1 H0 Z)\" offset=\"4dp\"/>").preview("g", 100.0).ok()
        assertEquals(Box(0.0, 0.0, 100.0, plain.height), path.keys.single().shape.bounds, "a path as tall too")
    }

    // `popup:selected`: the cell in focus a face, its radius and border the theme's (theme 3.0's had neither)
    @Test
    fun aLongPresssSelectedCellHasItsRadiusAndBorder() {
        val b = styled("<style select=\"popup:selected\" radius=\"6dp\" border=\"1dp #ff112233\" inner-shadow=\"0 1dp 0 #40000000\"/>")
        val keys = b.popupKeys(listOf("a", "b"), 2, null).ok()
        val s = b.popup(listOf("a", "b"), listOf(listOf(0, 1)), keys, selected = 0).ok()
        val a = s.keys.first { it.label == "a" }
        assertEquals(listOf("Fill", "InnerShadow", "Stroke", "Text"), a.ops(false).map { it::class.simpleName })
        val fill = assertIs<Op.Fill>(a.ops(false)[0])
        assertEquals(List(4) { 6 * d }, assertIs<Shape.Rect>(fill.shape).radii)
        val stroke = assertIs<Op.Stroke>(a.ops(false)[2])
        assertEquals(d, stroke.width)
        assertEquals(Argb(0xFF112233.toInt()), stroke.color)
        assertEquals(fill.shape.bounds.inset(d / 2, d / 2, d / 2, d / 2), stroke.shape.bounds)
        // the others: their text only, as ever
        assertEquals(listOf("Text"), s.keys.first { it.label == "b" }.ops(false).map { it::class.simpleName })
    }

    // the panel's border, inner shadow and shape its own: the cell in focus takes them only from a style naming
    // `:selected`, its fill and radius as they cascade (decided 2026-11-09)
    @Test
    fun aLongPresssSelectedCellTakesItsBorderOnlyFromSelected() {
        val panel = "<style select=\"popup\" radius=\"5dp\" border=\"2dp #ff445566\" inner-shadow=\"0 1dp 0 #40000000\" shape=\"path(M0 0 H1 V1 Z)\"/>"
        fun cell(styles: String): KeyNode {
            val b = SceneBuilder(ThemeReader.theme(testData("material-light.xml").replace("</theme>", "$styles\n</theme>")).ok(), Forms.block.copy(dark = true), FakeMeasurer)
            val keys = b.popupKeys(listOf("a", "b"), 2, null).ok()
            val s = b.popup(listOf("a", "b"), listOf(listOf(0, 1)), keys, selected = 0).ok()
            assertTrue(s.base.any { it is Op.Stroke } && s.base.any { it is Op.InnerShadow }, "the panel's drawn on it")
            return s.keys.first { it.label == "a" }
        }
        val plain = cell(panel)
        assertEquals(listOf("Fill", "Text"), plain.ops(false).map { it::class.simpleName })
        assertEquals(List(4) { 5 * d }, assertIs<Shape.Rect>(plain.shape).radii, "a rectangle, the panel's radius")
        // a later style of the panel as specific as `popup:selected` (a scene's) does not take its border away
        val selected = cell("<style select=\"popup:selected\" border=\"1dp #ff112233\"/>\n<style select=\"@dark popup\" border=\"3dp #ff000000\"/>\n$panel")
        val stroke = selected.ops(false).filterIsInstance<Op.Stroke>().single()
        assertEquals(d, stroke.width)
        assertEquals(Argb(0xFF112233.toInt()), stroke.color)
    }

    @Test
    fun aLongPresssKeysAreOneWidthForTheWidestLabel() {
        val b = builder()
        val size = Popups.KEY_TEXT_DP * d
        val pad = px(Popups.TEXT_PADDING_DP)
        val labels = listOf(".com", ".cn", ".net")
        val keys = b.popupKeys(labels, 3, 2000.0).ok()
        assertEquals(ceil(4 * 0.6 * size) + 2 * pad, keys.width)
        assertEquals(size, keys.size)
        assertEquals(px(Popups.KEY_HEIGHT_DP), keys.height)
        // one letter: the least width
        assertEquals(px(Popups.KEY_MIN_WIDTH_DP), b.popupKeys(listOf("a", "b"), 2, 2000.0).ok().width)
        // no room known: as wide as they want
        assertEquals(keys.width, b.popupKeys(labels, 3, null).ok().width)
        // too wide for the room: narrower, the text smaller alike
        val narrow = b.popupKeys(labels, 3, 3 * 50.0).ok()
        assertEquals(50.0, narrow.width)
        assertEquals(size * 50 / keys.width, narrow.size, 1e-9)
    }

    @Test
    fun aLongPresssRowsAreDrawnBottomFirstTheOneInFocusSelected() {
        val labels = listOf("a", "b", "c", "d", "e", "f", "g")
        val b = builder()
        val keys = b.popupKeys(labels, 4, null).ok()
        // the 4 columns round the 2nd, the top row's last slot empty
        val s = b.popup(labels, listOf(listOf(2, 0, 1, 3), listOf(6, 4, 5, -1)), keys, selected = 0).ok()
        assertEquals(4 * keys.width, s.width)
        assertEquals(2 * keys.height, s.height)
        val byLabel = s.keys.associateBy { it.label }
        assertEquals(7, byLabel.size)
        assertEquals(Box(keys.width, keys.height, 2 * keys.width, 2 * keys.height), byLabel.getValue("a").bounds)
        assertEquals(Box(0.0, 0.0, keys.width, keys.height), byLabel.getValue("g").bounds)
        // the panel the base: its shadow and fill once, under all the keys
        assertEquals(listOf("Shadow", "Fill"), s.base.map { it::class.simpleName })
        assertEquals(color("popupBackground"), (s.base[1] as Op.Fill).color)
        // the key in focus filled and coloured as selected; the others their text only
        val a = byLabel.getValue("a")
        assertEquals(setOf(KeyState.Selected), a.states)
        assertEquals(color("genericActiveBackground"), (a.ops(false).first() as Op.Fill).color)
        assertEquals(color("genericActiveForeground"), a.ops(false).filterIsInstance<Op.Text>().single().color)
        val b2 = byLabel.getValue("b")
        assertEquals(listOf("Text"), b2.ops(false).map { it::class.simpleName })
        assertEquals(color("popupText"), (b2.ops(false).single() as Op.Text).color)
    }

    @Test
    fun aMenusCellInFocusIsLargerAndTintedOneDisabledFaded() {
        val cells = listOf(MenuCell("全选", "select-all"), MenuCell("剪切", "cut"), MenuCell("复制", "copy"))
        val s = builder().menu(cells, listOf(0, 1, 2), selected = 1, disabled = setOf(2)).ok()
        val cell = px(Popups.MENU_CELL_DP)
        assertEquals(3 * cell, s.width)
        assertEquals(cell, s.height)
        val (all, cut, copy) = s.keys
        assertEquals(px(Popups.MENU_SELECTED_DP), cut.shape.bounds.width, 1.0)
        assertEquals(px(Popups.MENU_FACE_DP), all.shape.bounds.width, 1.0)
        assertTrue(cut.shape.bounds.width > all.shape.bounds.width)
        // round: the theme's 50%
        val face = assertIs<Shape.Rect>(all.shape)
        assertEquals(face.box.width / 2, face.radii[0], 1e-9)
        assertEquals(listOf(color("accentKey"), color("keyPressHighlight")), cut.ops(false).filterIsInstance<Op.Fill>().map { it.color })
        assertEquals(1, all.ops(false).filterIsInstance<Op.Fill>().size)
        // an icon at its own size in the middle, the theme's colour
        val icon = all.ops(false).filterIsInstance<Op.Icon>().single()
        assertEquals("select-all", icon.name)
        assertEquals(Box(cell / 2 - 12 * d, cell / 2 - 12 * d, cell / 2 + 12 * d, cell / 2 + 12 * d), icon.box)
        assertEquals(color("accentKeyText"), icon.tint)
        // disabled: 30% of itself
        assertEquals(setOf(KeyState.Disabled), copy.states)
        val faded = copy.ops(false).filterIsInstance<Op.Fill>().single().color
        assertEquals(Argb(color("accentKey").value and 0xFFFFFF or (77 shl 24)), faded)
        assertEquals("全选", all.a11y)
    }

    // a faded cell's picture and image as they are, the colour over the one and under the other faded
    @Test
    fun aFadedPictureOrImageFadesItsColour() {
        val env = Forms.block
        val faded = PopupBuilder(theme, env, FakeMeasurer, IconSizes.MATERIAL, Cascade(theme, env.scenes), Paint(theme, env.density))
        val shape = Shape.Rect(Box(0.0, 0.0, 10.0, 10.0))
        val red = Argb(0xFFFF0000.toInt())
        val half = Argb(0x80FF0000.toInt())
        assertEquals(Op.Picture(shape, 4.0, 90.0, half), faded.faded(Op.Picture(shape, 4.0, 90.0, red), 0.5))
        assertEquals(Op.NineSlice(shape, "k.png", listOf(1, 1, 1, 1), 1, half), faded.faded(Op.NineSlice(shape, "k.png", listOf(1, 1, 1, 1), 1, red), 0.5))
        assertEquals(Op.Picture(shape, 4.0, 90.0), faded.faded(Op.Picture(shape, 4.0, 90.0), 0.5))
    }

    @Test
    fun aMenuOfWordsIsPills() {
        val cells = listOf(MenuCell("关", null), MenuCell("拼音", null), MenuCell("注音符号", null))
        val b = builder()
        val s = b.menu(cells, listOf(1, 0, 2), selected = 0).ok()
        val cell = px(Popups.MENU_CELL_DP)
        val wide = floor(cell * Popups.MENU_WORDS_WIDTH)
        assertEquals(wide, b.menuKeys(cells).ok().width)
        assertEquals(3 * wide, s.width)
        // in [order]: 拼音 first
        assertEquals("拼音", s.keys.first().label)
        val pill = s.keys.first().shape.bounds
        assertEquals(wide - 2 * px(Popups.MENU_PILL_SIDE_DP), pill.width, 1e-9)
        val text = s.keys.first().ops(false).filterIsInstance<Op.Text>().single()
        assertEquals(Popups.MENU_TEXT_DP * d, text.size, 1e-9)
    }

    // 060-render-p3 T4a review: a theme's letter-spacing and each script's font measured in the popups as on the keys
    @Test
    fun aPopupsTextIsMeasuredAsAKeys() {
        val styled = ThemeReader.theme(
            testData("material-light.xml").replace("</colors>", "</colors>\n<font name=\"kai\" src=\"kai.ttf\"/>")
                .replace("</theme>", "<style select=\"preview, popup, menu\" letter-spacing=\"0.2em\" font-han=\"kai\"/>\n</theme>"),
        ).ok()
        val b = SceneBuilder(styled, Forms.block, FakeMeasurer)
        val size = Popups.KEY_TEXT_DP * d
        val spaced = FakeMeasurer.measure(".com", "system", 400, size, 0.2).advance
        assertEquals(ceil(spaced) + 2 * px(Popups.TEXT_PADDING_DP), b.popupKeys(listOf(".com"), 1, 2000.0).ok().width)
        val runs = b.preview("拼a", 100.0).ok().texts()
        assertEquals(listOf("拼" to "kai", "a" to "system"), runs.map { it.text to it.font })
        assertTrue(runs.all { it.letterSpacing == 0.2 })
        assertEquals(
            FakeMeasurer.measure("拼a", "system", 400, runs[0].size, 0.2).advance,
            b.previewWidth("拼a", runs[0].size).ok(),
        )
        val pill = b.menu(listOf(MenuCell("拼音", null)), listOf(0), 0).ok().texts().single()
        assertEquals("kai" to 0.2, pill.font to pill.letterSpacing)
    }

    // the keyboard drawing it has nowhere to send a throw: a fault is a problem
    @Test
    fun aFaultIsAProblemNotAThrow() {
        val b = builder(measurer = { _, _, _, _ -> error("no font") })
        assertIs<Parsed.Failed>(b.preview("g", 10.0))
        assertIs<Parsed.Failed>(b.popup(listOf("a"), listOf(listOf(0)), org.fcitx.fcitx5.android.scene.engine.PopupKeys(10.0, 10.0, 10.0), 0))
        assertIs<Parsed.Failed>(b.menu(listOf(MenuCell("a", null)), listOf(0), 0))
        assertIs<Parsed.Failed>(b.popupKeys(listOf("a"), 1, null))
        assertIs<Parsed.Failed>(b.previewWidth("a", 10.0))
    }
}
