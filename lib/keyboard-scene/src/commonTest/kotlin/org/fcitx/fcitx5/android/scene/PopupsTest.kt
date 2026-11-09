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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
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
