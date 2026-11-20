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
import org.fcitx.fcitx5.android.scene.engine.clusterEnds
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertEquals(px(Popups.PREVIEW_MIN_WIDTH_DP), b.preview("g", 20.0).ok().width)
        assertEquals(300.0, b.preview("g", 300.0).ok().width)
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
        val ops = bubble.ops(false)
        assertEquals(Op.Fill(shape, color("popupBackground")), ops[1])
        assertEquals(shape.moved(0.0, 2 * d), assertIs<Op.Shadow>(ops[0]).shape)
        assertEquals(plain.texts().single(), s.texts().single())
        val head = styled("<style select=\"preview\" shape=\"bubble\" tail=\"false\"/>").preview("g", 100.0).ok().keys.single()
        assertEquals(Shape.Rect(Box(0.0, 0.0, 100.0, row), List(4) { 4 * d }), head.shape)
        val narrow = styled("<style select=\"preview\" shape=\"bubble\" radius=\"50%\"/>").preview("g", 20.0).ok().keys.single()
        assertIs<Shape.Rect>(narrow.shape, "no room for a tail between the corners")
        val path = styled("<style select=\"preview\" shape=\"path(M0 0 H1 V.5 L.5 1 L0 .5 Z)\"/>").preview("g", 100.0).ok().keys.single()
        assertEquals(Box(0.0, 0.0, 100.0, plain.height), assertIs<Shape.Path>(path.shape).bounds)
    }

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
        assertEquals(ceil(px(Popups.PREVIEW_MIN_WIDTH_DP) * 1.6), styled("<style select=\"preview\" size=\"160%\"/>").preview("g", 1.0).ok().width, 1e-9)
        val words = styled("<style select=\"preview\" size=\"120%\"/>").preview("abcdefghij", 20.0, 16 * d).ok()
        assertEquals(ceil(ceil(10 * 0.6 * 16 * d * 1.2) + 2 * px(Popups.TEXT_PADDING_DP) * 1.2), words.width, 1e-9)
        val larger = styled("<style select=\"preview\" size=\"130%\"/>")
        assertEquals(129.0, larger.preview("g", 97.0).ok().width, "the least, 99 px, by 1.3: 128.7")
        assertEquals(257.0, larger.preview("g", 197.0).ok().width, "the key's 197 px by 1.3: 256.1")
        val up = styled("<style select=\"preview\" offset=\"-4dp\"/>").preview("g", 100.0).ok()
        val by = px(4.0)
        assertEquals(plain.height + by, up.height)
        assertEquals(plain.keys.single().shape, up.keys.single().shape)
        // its bottom past the scene's: the app draws it in the room round it
        val down = styled("<style select=\"preview\" offset=\"4dp\"/>").preview("g", 100.0).ok()
        assertEquals(plain.height - by, down.height)
        assertEquals(plain.keys.single().shape, down.keys.single().shape)
        assertEquals(plain.height, down.keys.single().ops(false).filterIsInstance<Op.Fill>().single().shape.bounds.bottom)
        val bubble = styled("<style select=\"preview\" shape=\"bubble\" offset=\"4dp\"/>").preview("g", 100.0).ok()
        assertEquals(plain.height - by, bubble.height)
        val path = styled("<style select=\"preview\" shape=\"path(M0 0 H1 V1 H0 Z)\" offset=\"4dp\"/>").preview("g", 100.0).ok()
        assertEquals(Box(0.0, 0.0, 100.0, plain.height), path.keys.single().shape.bounds, "a path as tall too")
    }

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
        assertEquals(listOf("Text"), s.keys.first { it.label == "b" }.ops(false).map { it::class.simpleName })
    }

    // the cell in focus takes the panel's border, inner shadow and shape only from a style naming `:selected`
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
        assertEquals(px(Popups.KEY_MIN_WIDTH_DP), b.popupKeys(listOf("a", "b"), 2, 2000.0).ok().width)
        assertEquals(keys.width, b.popupKeys(labels, 3, null).ok().width)
        val narrow = b.popupKeys(labels, 3, 3 * 100.0).ok()
        assertEquals(100.0, narrow.width)
        assertEquals(size * 100 / keys.width, narrow.size, 1e-9)
        assertEquals(px(Popups.MIN_WORDS_DP), b.popupKeys(labels, 3, 3 * 50.0).ok().size)
    }

    @Test
    fun aLabelTooWideIsShrunkThenCutAndSaidWhole() {
        val b = builder()
        val long = "2026年11月14日 星期六 下午 03:05 " + "x".repeat(30)
        val keys = b.popupKeys(listOf("a", long), 2, 2 * 60.0).ok()
        assertEquals(60.0, keys.width)
        assertEquals(px(Popups.MIN_WORDS_DP), keys.size)
        val s = b.popup(listOf("a", long), listOf(listOf(0, 1)), keys, selected = -1).ok()
        val cut = s.keys.single { it.label == long }
        assertEquals(long, cut.a11y)
        val text = cut.ops(false).filterIsInstance<Op.Text>().single()
        assertTrue(text.text.endsWith("…"), text.text)
        assertTrue(long.startsWith(text.text.removeSuffix("…")))
        assertTrue(text.text.length < long.length)
        assertEquals("a", s.keys.single { it.label == "a" }.ops(false).filterIsInstance<Op.Text>().single().text)
    }

    // a long label is cut by halving: a label of 64 x's measured a handful of times, not some fifty
    @Test
    fun aLongLabelIsCutInAFewMeasures() {
        val long = "x".repeat(64)
        val measured = ArrayList<String>()
        val counting = TextMeasurer { text, font, weight, size -> FakeMeasurer.measure(text, font, weight, size).also { if ('x' in text) measured += text } }
        val b = builder(measurer = counting)
        val keys = b.popupKeys(listOf(long), 1, 300.0).ok()
        measured.clear()
        val text = b.popup(listOf(long), listOf(listOf(0)), keys, selected = -1).ok().keys.single().ops(false).filterIsInstance<Op.Text>().single().text
        val kept = text.removeSuffix("…")
        assertTrue(text.endsWith("…") && kept.isNotEmpty() && long.startsWith(kept), text)
        assertTrue(measured.size <= 10, "${measured.size} measures: $measured")
        assertTrue("x".repeat(kept.length + 1) + "…" in measured, "$measured")
    }

    @Test
    fun aLabelIsCutBetweenCharactersAsSeen() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val flag = "\uD83C\uDDE8\uD83C\uDDF3"
        assertEquals(listOf(family.length, 2 * family.length), clusterEnds(family + family))
        assertEquals(listOf(4, 8), clusterEnds(flag + flag))
        assertEquals(listOf(2, 3), clusterEnds("e\u0301x"))
        assertEquals(listOf(3, 5), clusterEnds("1\uFE0F\u20E3\uD83D\uDC4D"))
        assertEquals(listOf(4, 5), clusterEnds("\uD83D\uDC4D\uD83C\uDFFDa"))
        assertEquals(listOf(2, 3), clusterEnds("\r\na"))
        assertEquals(emptyList(), clusterEnds(""))
        val b = builder()
        val long = family.repeat(40)
        val keys = b.popupKeys(listOf("a", long), 2, 2 * 1000.0).ok()
        val text = b.popup(listOf("a", long), listOf(listOf(0, 1)), keys, selected = -1).ok()
            .keys.single { it.label == long }.ops(false).filterIsInstance<Op.Text>().single().text
        val kept = text.removeSuffix("…")
        assertTrue(kept.isNotEmpty() && kept.length < long.length, text)
        assertEquals(0, kept.length % family.length, text)
    }

    @Test
    fun aLongPresssRowsAreDrawnBottomFirstTheOneInFocusSelected() {
        val labels = listOf("a", "b", "c", "d", "e", "f", "g")
        val b = builder()
        val keys = b.popupKeys(labels, 4, null).ok()
        val s = b.popup(labels, listOf(listOf(2, 0, 1, 3), listOf(6, 4, 5, -1)), keys, selected = 0).ok()
        assertEquals(4 * keys.width, s.width)
        assertEquals(2 * keys.height, s.height)
        val byLabel = s.keys.associateBy { it.label }
        assertEquals(7, byLabel.size)
        assertEquals(Box(keys.width, keys.height, 2 * keys.width, 2 * keys.height), byLabel.getValue("a").bounds)
        assertEquals(Box(0.0, 0.0, keys.width, keys.height), byLabel.getValue("g").bounds)
        assertEquals(listOf("Shadow", "Fill"), s.base.map { it::class.simpleName })
        assertEquals(color("popupBackground"), (s.base[1] as Op.Fill).color)
        val a = byLabel.getValue("a")
        assertEquals(setOf(KeyState.Selected), a.states)
        assertEquals(color("genericActiveBackground"), (a.ops(false).first() as Op.Fill).color)
        assertEquals(color("genericActiveForeground"), a.ops(false).filterIsInstance<Op.Text>().single().color)
        val b2 = byLabel.getValue("b")
        assertEquals(listOf("Text"), b2.ops(false).map { it::class.simpleName })
        assertEquals(color("popupText"), (b2.ops(false).single() as Op.Text).color)
    }

    @Test
    @Uat("KB-105")
    fun aMenusCellInFocusIsLargerAndReturnsColourOneDisabledFaded() {
        val cells = listOf(MenuCell("全选", "select-all"), MenuCell("剪切", "cut"), MenuCell("复制", "copy"))
        val s = builder().menu(cells, listOf(0, 1, 2), selected = 1, disabled = setOf(2)).ok()
        val cell = px(Popups.MENU_CELL_DP)
        assertEquals(3 * cell, s.width)
        assertEquals(cell, s.height)
        val (all, cut, copy) = s.keys
        assertEquals(px(Popups.MENU_SELECTED_DP), cut.shape.bounds.width, 1.0)
        assertEquals(px(Popups.MENU_FACE_DP), all.shape.bounds.width, 1.0)
        assertTrue(cut.shape.bounds.width > all.shape.bounds.width)
        val face = assertIs<Shape.Rect>(all.shape)
        assertEquals(face.box.width / 2, face.radii[0], 1e-9)
        assertEquals(listOf(color("accentKey")), cut.ops(false).filterIsInstance<Op.Fill>().map { it.color })
        assertEquals(color("accentKeyText"), cut.ops(false).filterIsInstance<Op.Icon>().single().tint)
        assertEquals(listOf(color("popupBackground")), all.ops(false).filterIsInstance<Op.Fill>().map { it.color })
        val icon = all.ops(false).filterIsInstance<Op.Icon>().single()
        assertEquals("select-all", icon.name)
        assertEquals(Box(cell / 2 - 12 * d, cell / 2 - 12 * d, cell / 2 + 12 * d, cell / 2 + 12 * d), icon.box)
        assertEquals(color("popupText"), icon.tint)
        assertEquals(setOf(KeyState.Disabled), copy.states)
        val faded = copy.ops(false).filterIsInstance<Op.Fill>().single().color
        assertEquals(Argb(color("popupBackground").value and 0xFFFFFF or (77 shl 24)), faded)
        assertEquals("全选", all.a11y)
    }

    // the cell in focus disabled still Return's colour, faded, so the pick shows though it does nothing
    @Test
    @Uat("KB-105")
    fun aMenusCellInFocusThatIsDisabledKeepsReturnsColourFaded() {
        val cells = listOf(MenuCell("全选", "select-all"), MenuCell("剪切", "cut"), MenuCell("复制", "copy"))
        val s = builder().menu(cells, listOf(0, 1, 2), selected = 2, disabled = setOf(1, 2)).ok()
        val (_, cut, copy) = s.keys
        fun faded(c: Argb) = Argb((c.value and 0xFFFFFF) or ((c.alpha * Popups.DISABLED_ALPHA).roundToInt() shl 24))
        assertEquals(setOf(KeyState.Selected, KeyState.Disabled), copy.states)
        assertEquals(px(Popups.MENU_SELECTED_DP), copy.shape.bounds.width, 1.0)
        assertEquals(listOf(faded(color("accentKey"))), copy.ops(false).filterIsInstance<Op.Fill>().map { it.color })
        assertEquals(faded(color("accentKeyText")), copy.ops(false).filterIsInstance<Op.Icon>().single().tint)
        assertEquals(px(Popups.MENU_FACE_DP), cut.shape.bounds.width, 1.0)
        assertEquals(listOf(faded(color("popupBackground"))), cut.ops(false).filterIsInstance<Op.Fill>().map { it.color })
        assertEquals(faded(color("popupText")), cut.ops(false).filterIsInstance<Op.Icon>().single().tint)
    }

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
        assertEquals("拼音", s.keys.first().label)
        val pill = s.keys.first().shape.bounds
        assertEquals(wide - 2 * px(Popups.MENU_PILL_SIDE_DP), pill.width, 1e-9)
        val text = s.keys.first().ops(false).filterIsInstance<Op.Text>().single()
        assertEquals(Popups.MENU_TEXT_DP * d, text.size, 1e-9)
    }

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
