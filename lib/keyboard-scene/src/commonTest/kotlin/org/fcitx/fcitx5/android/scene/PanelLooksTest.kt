/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Pads
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-p3 T7b: the panels, `clipboard`, `picker`, `editing`, `voice`, and the handwriting pad's ink and grid
class PanelLooksTest {
    private val source = testData("material-light.xml")

    private fun withStyle(style: String) = source.replace("</theme>", "$style\n</theme>")

    private fun theme(s: String): Theme = ThemeReader.theme(s).ok()

    private fun looks(t: Theme = Fixtures.materialLight) = PanelLooks(Cascades(t))

    private fun warnings(s: String): List<String> {
        val t = ThemeReader.theme(s)
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    private fun problems(s: String) = ThemeReader.theme(s).problems().map { it.message }

    private fun argb(v: Long) = Argb(v.toInt())

    // material-light's colours
    private val keyText = argb(0xff37474f)
    private val clipboardEntry = argb(0xfffbfbfc)
    private val bar = argb(0xffe4e7e9)
    private val keyboard = argb(0xffeceff1)
    private val accent = argb(0xff5cb5ab)

    @Test
    fun theClipboardNotSetIsTheAppsOfBefore() {
        // ThemeColors.entryRadius's: half the keys' radius (4dp), flat or not
        val l = looks()
        assertEquals(PanelLooks.Clipboard(clipboardEntry, 2.0, false, keyText), l.clipboard(emptySet()))
        assertEquals(PanelLooks.Clipboard(clipboardEntry, 2.0, false, keyText), l.clipboard(setOf("flat", "dark", "landscape")))
        assertEquals(0.0, looks(theme(withStyle("<style select=\"key\" radius=\"0\"/>"))).clipboard(emptySet()).radiusDp)
        assertEquals(6.0, looks(theme(withStyle("<style select=\"key\" radius=\"12dp\"/>"))).clipboard(emptySet()).radiusDp)
        // a pill's keys, a pill
        assertTrue(looks(theme(withStyle("<style select=\"key\" radius=\"50%\"/>"))).clipboard(emptySet()).round)
    }

    @Test
    fun theClipboardATheme() {
        val t = theme(
            withStyle(
                "<style select=\"clipboard\" fill=\"@altKey\" radius=\"10dp\" color=\"#ff0000\"/>" +
                    "<style select=\"@dark clipboard\" fill=\"#000000\" radius=\"50%\"/>",
            ),
        )
        assertEquals(PanelLooks.Clipboard(argb(0xffdfe2e4), 10.0, false, argb(0xffff0000)), looks(t).clipboard(emptySet()))
        assertEquals(PanelLooks.Clipboard(argb(0xff000000), 0.0, true, argb(0xffff0000)), looks(t).clipboard(setOf("dark")))
        // a fill but a colour's (a picture, a gradient): the default
        val gradient = theme(withStyle("<style select=\"clipboard\" fill=\"linear(90deg, @key 0%, @altKey 100%)\"/>"))
        assertEquals(clipboardEntry, looks(gradient).clipboard(emptySet()).fill)
    }

    @Test
    fun thePickerNotSetIsTheAppsOfBefore() {
        val side = looks().picker(emptySet(), side = true)
        assertNull(side.fill)
        // PickerSideUi's: the bar's colour, its text the keys' at 60%; the one shown the keyboard's, the text whole
        assertEquals(PanelLooks.Categories(bar, argb(0x9937474f)), side.categories)
        assertEquals(PanelLooks.Categories(keyboard, keyText), side.selected)
        // PickerTabsUi's: on the bar's own, at 50%
        val tabs = looks().picker(setOf("dark"), side = false)
        assertEquals(PanelLooks.Categories(null, argb(0x8037474f)), tabs.categories)
        assertEquals(PanelLooks.Categories(null, keyText), tabs.selected)
    }

    @Test
    fun thePickerATheme() {
        val t = theme(
            withStyle(
                "<style select=\"picker\" fill=\"#101010\" color=\"#202020\"/>" +
                    "<style select=\"picker categories\" fill=\"#303030\"/>" +
                    "<style select=\"picker:selected categories\" fill=\"#404040\" color=\"#505050\"/>",
            ),
        )
        for (side in listOf(true, false)) {
            val p = looks(t).picker(emptySet(), side)
            assertEquals(argb(0xff101010), p.fill)
            // the picker's colour its categories', inherited
            assertEquals(PanelLooks.Categories(argb(0xff303030), argb(0xff202020)), p.categories)
            assertEquals(PanelLooks.Categories(argb(0xff404040), argb(0xff505050)), p.selected)
        }
        // the categories' alone set: the one shown as they are (a cascade), but what it sets of its own
        val plain = looks(theme(withStyle("<style select=\"picker categories\" fill=\"#303030\" color=\"#202020\"/>"))).picker(emptySet(), true)
        assertEquals(PanelLooks.Categories(argb(0xff303030), argb(0xff202020)), plain.selected)
    }

    @Test
    fun theEditingPanelNotSetIsTheAppsOfBefore() {
        val e = looks().editing(emptySet())
        assertEquals(PanelLooks.Editing(null, null, false, 4.0), e)
        assertEquals(4.0, looks().editing(setOf("flat")).keyRadiusDp, "the keys' radius as set, flat or not")
        val t = theme(withStyle("<style select=\"editing\" fill=\"@accentKey\" radius=\"8.5dp\"/><style select=\"@landscape editing\" radius=\"50%\"/>"))
        assertEquals(PanelLooks.Editing(accent, 8.5, false, 4.0), looks(t).editing(emptySet()))
        assertEquals(PanelLooks.Editing(accent, 0.0, true, 4.0), looks(t).editing(setOf("landscape")))
        // pill keys, the buttons not set: pills too
        val pills = theme(withStyle("<style select=\"key\" radius=\"50%\"/>"))
        assertTrue(looks(pills).editing(emptySet()).round)
    }

    @Test
    fun theVoiceMaskNotSetIsTheAppsOfBefore() {
        // VoiceStyle.mask's: the keyboard's colour at 0xE0 of its alpha; the pill the accent at 0x29, its bars the accent
        assertEquals(PanelLooks.Voice(argb(0xe0eceff1), null, argb(0x295cb5ab), accent), looks().voice(emptySet()))
        assertEquals(argb(0x70123456), PanelLooks.mask(argb(0x80123456)), "0x80 × 0xE0 / 0xFF, truncated")
        val t = theme(
            withStyle(
                "<style select=\"voice\" fill=\"#80000000\" blur=\"25dp\" icon-color=\"#ff0000\"/>" +
                    "<style select=\"@dark voice\" blur=\"0\"/><style select=\"voice mic\" fill=\"@altKey\"/>",
            ),
        )
        // a theme's mask as it says, not made less translucent; the icon colour the mic's, inherited
        assertEquals(PanelLooks.Voice(argb(0x80000000), 25.0, argb(0xffdfe2e4), argb(0xffff0000)), looks(t).voice(emptySet()))
        assertEquals(0.0, looks(t).voice(setOf("dark")).blurDp)
    }

    @Test
    fun theBlurAndTheInkInRange() {
        assertEquals(listOf<String>(), problems(withStyle("<style select=\"voice\" blur=\"0\"/><style select=\"key\" ink-width=\"1dp\"/>")))
        for (attr in listOf("blur=\"25.5dp\"", "blur=\"-1dp\"", "blur=\"12\"")) {
            assertTrue(problems(withStyle("<style select=\"voice\" fill=\"@key\" $attr/>")).single().contains("blur="), attr)
        }
        for (attr in listOf("ink-width=\"0\"", "ink-width=\"0.5dp\"", "ink-width=\"8.5dp\"", "grid=\"yes\"", "ink-color=\"rgb(0,0,0)\"")) {
            assertTrue(problems(withStyle("<style select=\"handwriting/pad\" fill=\"@key\" $attr/>")).single().contains(attr.substringBefore('=')), attr)
        }
        assertEquals(listOf("<style>: @keyShadw is no colour"), problems(withStyle("<style select=\"handwriting/pad\" grid-color=\"@keyShadw\"/>")))
        assertTrue(listOf("blur", "ink-color", "ink-width", "grid-color", "grid").all { it in Values.PROPERTIES && it !in Values.RESERVED })
    }

    @Test
    fun whatAPanelDoesNotReadIsWarnedOf() {
        assertEquals(listOf<String>(), warnings(withStyle("<style select=\"clipboard\" fill=\"@key\" radius=\"2dp\" color=\"@keyText\"/>")))
        assertEquals(listOf<String>(), warnings(withStyle("<style select=\"picker:selected categories, picker\" fill=\"@key\" color=\"@keyText\"/>")))
        assertEquals(
            listOf("shadow= on clipboard does nothing: it reads fill radius color"),
            warnings(withStyle("<style select=\"clipboard\" shadow=\"0 1dp 0 #000000\"/>")),
        )
        assertEquals(
            listOf("radius= on voice mic does nothing: it reads fill icon-color"),
            warnings(withStyle("<style select=\"voice mic\" radius=\"2dp\"/>")),
        )
        assertEquals(
            listOf("fill= on editing:pressed does nothing: it reads nothing in that state"),
            warnings(withStyle("<style select=\"editing:pressed\" fill=\"@key\"/>")),
        )
        // the blur the voice mask's alone, the ink and grid a key's (the pad's)
        assertEquals(listOf("blur= on key does nothing: it is the voice mask's"), warnings(withStyle("<style select=\"key\" blur=\"4dp\"/>")))
        assertEquals(
            listOf("grid= on keyboard does nothing: it is the handwriting pad's (handwriting/pad)"),
            warnings(withStyle("<style select=\"keyboard\" grid=\"true\"/>")),
        )
        assertEquals(
            listOf("grid= on clipboard does nothing: it reads fill radius color"),
            warnings(withStyle("<style select=\"clipboard\" grid=\"true\"/>")),
        )
        // the one shown with no part: the colour its categories inherit, no fill of its own
        assertEquals(
            listOf("fill= on picker:selected does nothing: it reads color"),
            warnings(withStyle("<style select=\"picker:selected\" fill=\"@key\" color=\"@keyText\"/>")),
        )
        // a window widget's warned of once, by what it reads
        val handle = warnings(withStyle("<style select=\"handle\" ink-color=\"@keyText\"/>"))
        assertEquals(1, handle.size, "$handle")
        assertTrue(handle.single().startsWith("ink-color= on handle does nothing"), "$handle")
        // a key named that is not the pad: none of it read (a class may be the pad's)
        assertEquals(
            listOf(
                "ink-width= on qwerty/q does nothing: it is the handwriting pad's (handwriting/pad)",
                "ink-width= on handwriting/* does nothing: it is the handwriting pad's (handwriting/pad)",
            ),
            warnings(withStyle("<style select=\"qwerty/q, handwriting/*, handwriting/pad, key.pen\" ink-width=\"2dp\"/>")),
        )
        // a panel's fill a colour alone: the native view's
        assertTrue(
            "fill= on picker is no colour: a panel's fill is a colour alone, ignored" in
                warnings(withStyle("<style select=\"picker\" fill=\"picture\"/>")),
        )
    }

    @Test
    fun thePadsInkNotSetIsTheAppsOfBefore() {
        val d = Forms.block.density
        val pad = Forms.scene("handwriting", Forms.block).slots.single { it.kind == Pads.KIND }
        assertEquals(Ink(keyText, 3.5 * d), pad.ink)
        assertEquals(listOf(Op.Shadow::class, Op.Fill::class), pad.ops.map { it::class }, "its face alone (its shadow, its fill), no grid")
        // no other slot has ink
        assertTrue(Forms.scene("handwriting", Forms.block).slots.filter { it.kind != Pads.KIND }.all { it.ink == null })
    }

    @Test
    fun thePadsInkAndGridATheme() {
        val d = Forms.block.density
        val t = theme(withStyle("<style select=\"handwriting/pad\" ink-color=\"#ff0000\" ink-width=\"6dp\" grid=\"true\"/>"))
        val scene = Forms.scene("handwriting", Forms.block, t)
        val pad = scene.slots.single { it.kind == Pads.KIND }
        assertEquals(Ink(argb(0xffff0000), 6.0 * d), pad.ink)
        val face = (pad.ops[1] as Op.Fill).shape.bounds
        val lines = pad.ops.drop(2).map { it as Op.Fill }
        // the divider's colour, 1dp wide (3px at 2.625), on whole pixels, within the face
        assertTrue(lines.all { it.color == argb(0x1f000000) })
        val w = 3.0
        val across = lines.last().shape.bounds
        assertEquals(w, across.height)
        assertTrue(lines.dropLast(1).all { it.shape.bounds.width == w && it.shape.bounds.top == face.top && it.shape.bounds.bottom == face.bottom })
        assertTrue(lines.all { b -> b.shape.bounds.let { it.left == kotlin.math.floor(it.left) && it.left >= face.left && it.right <= face.right } })
        // squares as tall as the face, as many as fit: each halved down, the inner edges between them
        val n = kotlin.math.floor(face.width / face.height).toInt()
        assertEquals(2 * n - 1 + (if (face.width - n * face.height >= 2 * w) 2 else 0), lines.size - 1)
        // the grid's colour of its own; under @flat as the scene's
        val coloured = theme(withStyle("<style select=\"handwriting/pad\" grid=\"true\" grid-color=\"@accentKey\"/><style select=\"@flat handwriting/pad\" grid=\"false\"/>"))
        val g = Forms.scene("handwriting", Forms.block, coloured).slots.single { it.kind == Pads.KIND }
        assertTrue(g.ops.drop(2).all { (it as Op.Fill).color == accent } && g.ops.size > 2)
        assertEquals(2, Forms.scene("handwriting", Forms.flat, coloured).slots.single { it.kind == Pads.KIND }.ops.size)
        // printed as the scene's text
        assertTrue("  ink #ffff0000 ${SceneText.n(6.0 * d)}" in SceneText.of(scene))
    }
}
