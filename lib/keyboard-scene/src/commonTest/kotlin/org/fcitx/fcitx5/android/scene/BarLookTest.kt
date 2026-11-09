/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.engine.CandidateLook
import org.fcitx.fcitx5.android.scene.engine.PreeditLook
import org.fcitx.fcitx5.android.scene.engine.ToolbarLook
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import org.fcitx.fcitx5.android.scene.theme.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-p3 T6a: the candidate bar and the expanded candidates (BarLook), and the sample bar (BarSample)
class BarLookTest {
    private val source = testData("material-light.xml")

    private fun withStyle(vararg style: String) = source.replace("</theme>", style.joinToString("\n", postfix = "\n</theme>"))

    private fun theme(vararg style: String): Theme = ThemeReader.theme(withStyle(*style)).ok()

    private fun look(t: Theme = Fixtures.materialLight, scenes: Set<String> = emptySet()) = BarLook.of(Cascades(t), scenes)

    private fun warnings(vararg style: String): List<String> {
        val t = ThemeReader.theme(withStyle(*style))
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    private fun argb(v: Long) = Argb(v.toInt())

    // material-light's colours (its <colors>)
    private val text = argb(0xFF37474F)
    private val comment = argb(0xFF7A858A)
    private val press = argb(0x1F000000)
    private val activeBackground = argb(0xFF80CBC4)

    @Test
    fun noneSetIsTheBarAsTheAppDrewIt() {
        val l = look()
        val plain = CandidateLook(fill = null, pressed = press, color = text, labelColor = text, commentColor = comment, font = null, weight = null)
        assertEquals(plain, l.plain)
        assertEquals(plain, l.first, "the first as the others")
        val chosen = CandidateLook(activeBackground, press, text, text, text, null, null)
        assertEquals(chosen, l.selected, "selected: the active colours, its comment too")
        assertEquals(chosen, l.firstSelected)
        assertNull(l.base, "material-light's bar transparent: the keyboard's base")
        assertEquals(Radius(List(4) { 0.0 }), l.radius)
        assertEquals(Inset(0.0, 0.0, 0.0, 0.0), l.inset)
        assertNull(l.padding, "the app's, of 候选字号")
        assertEquals(0.0, l.gapDp)
        assertNull(l.labelStyle, "the place's own")
        assertEquals(press, l.divider, "material-light's divider: #1f000000")
        assertNull(l.panel, "panel transparent: the keyboard's base")
        // LookTheme's flat keyboard: the panel the bar's colour (ThemeColors.panel's)
        assertEquals(argb(0xFFE4E7E9), look(theme("<style select=\"@flat panel\" fill=\"@bar\"/>"), setOf("flat")).panel)
        assertNull(look(theme("<style select=\"@flat panel\" fill=\"@bar\"/>")).panel)
    }

    @Test
    fun aThemesCandidatesRead() {
        val t = theme(
            "<style select=\"bar\" fill=\"#ff102030\"/>",
            "<style select=\"candidate\" fill=\"#ffffffff\" radius=\"50%\" inset=\"6dp 2dp\" padding=\"0 12dp 0 10dp\" gap=\"4dp\" label-style=\"dot\" color=\"#ff111111\" font=\"system\" weight=\"700\"/>",
            "<style select=\"candidate:first\" fill=\"@accentKey\" color=\"#ffffffff\"/>",
            "<style select=\"candidate label\" color=\"#ff222222\"/>",
            "<style select=\"candidate:first comment\" color=\"#ff333333\"/>",
            "<style select=\"candidate:selected\" fill=\"#ff444444\"/>",
            "<style select=\"candidate:pressed\" overlay=\"#40000000\"/>",
            "<style select=\"candidates-divider\" fill=\"transparent\"/>",
            "<style select=\"candidates-panel\" fill=\"#ff555555\"/>",
        )
        val l = look(t)
        assertEquals(BarLook.Base.Solid(argb(0xFF102030)), l.base)
        assertEquals(Radius(List(4) { 0.0 }, half = true), l.radius, "a pill")
        assertEquals(Inset(6.0, 2.0, 6.0, 2.0), l.inset)
        assertEquals(Inset(0.0, 12.0, 0.0, 10.0), l.padding)
        assertEquals(4.0, l.gapDp)
        assertEquals(BarLook.LabelStyle.Dot, l.labelStyle)
        assertNull(l.divider, "transparent: none drawn")
        assertEquals(argb(0xFF555555), l.panel)
        val pressed = argb(0x40000000)
        assertEquals(CandidateLook(argb(0xFFFFFFFF), pressed, argb(0xFF111111), argb(0xFF222222), argb(0xFF111111), "system", 700), l.plain, "the comment the words' colour, inherited")
        // the first: its own fill and words' colour; the comment's of :first comment; the label's of its part
        val accent = t.colors.getValue("accentKey")
        assertEquals(CandidateLook(accent, pressed, argb(0xFFFFFFFF), argb(0xFF222222), argb(0xFF333333), "system", 700), l.first)
        // selected: only what a style naming :selected sets; else the active colours
        val active = t.colors.getValue("genericActiveForeground")
        assertEquals(CandidateLook(argb(0xFF444444), pressed, active, active, active, "system", 700), l.selected)
        assertEquals(argb(0xFF444444), l.firstSelected.fill)
        assertEquals(l.candidate(first = true, selected = false), l.first)
        assertEquals(l.candidate(first = false, selected = true), l.selected)
        // the first pressed: its own overlay where a style names it
        val firstPressed = look(theme("<style select=\"candidate:pressed\" overlay=\"#40000000\"/>", "<style select=\"candidate:first:pressed\" overlay=\"#60ffffff\"/>"))
        assertEquals(argb(0x60FFFFFF), firstPressed.first.pressed)
        assertEquals(argb(0x40000000), firstPressed.plain.pressed)
    }

    @Test
    fun aPartTakesTheWordsColourUnlessItsOwnIsSet() {
        // as CSS inherits a colour: a theme colouring a candidate colours its number and comment too
        val l = look(theme("<style select=\"candidate\" color=\"#ff111111\"/>"))
        assertEquals(List(3) { argb(0xFF111111) }, listOf(l.plain.color, l.plain.labelColor, l.plain.commentColor))
        // its own, kept; none set anywhere: theme 3.0's three colours (noneSetIsTheBarAsTheAppDrewIt)
        val own = look(theme("<style select=\"candidate\" color=\"#ff111111\"/>", "<style select=\"candidate comment\" color=\"#ff222222\"/>"))
        assertEquals(argb(0xFF222222), own.plain.commentColor)
        assertEquals(argb(0xFF222222), own.first.commentColor, "the first's comment too")
        // selected: a :selected candidate's colour its parts' too, else the active colour
        val chosen = look(theme("<style select=\"candidate:selected\" color=\"#ff333333\"/>"))
        assertEquals(List(3) { argb(0xFF333333) }, listOf(chosen.selected.color, chosen.selected.labelColor, chosen.selected.commentColor))
    }

    @Test
    fun theBarsBaseIsAColourOrThePicture() {
        assertNull(look(theme("<style select=\"bar\" fill=\"transparent\"/>")).base)
        val pictured = source.replace("</colors>", "</colors>\n<picture src=\"p.jpg\" brightness=\"70%\" blur=\"3dp\"><cut orientation=\"portrait\" file=\"p.png\"/></picture>")
        fun withPicture(style: String) = ThemeReader.theme(pictured.replace("</theme>", "$style\n</theme>")).ok()
        val bar = argb(0xFFE4E7E9)
        assertEquals(BarLook.Base.Picture(3.0, 70.0, null, bar), look(withPicture("<style select=\"bar\" fill=\"picture\"/>")).base, "the theme's")
        assertEquals(
            BarLook.Base.Picture(8.0, 50.0, argb(0x40000000), bar),
            look(withPicture("<style select=\"bar\" fill=\"picture(8dp, 50%, #40000000)\"/>")).base,
        )
        // a gradient: not drawn, warned of
        assertNull(look(theme("<style select=\"bar\" fill=\"linear(90deg, @key 0%, @bar 100%)\"/>")).base)
        assertTrue(warnings("<style select=\"bar\" fill=\"linear(90deg, @key 0%, @bar 100%)\"/>").single().contains("a gradient or an image is not drawn there"))
        assertTrue(warnings("<style select=\"candidate\" fill=\"linear(90deg, @key 0%, @bar 100%)\"/>").single().contains("on candidate is a colour"))
    }

    @Test
    fun scenesSelectAsElsewhere() {
        val t = theme("<style select=\"@dark candidate\" fill=\"#ff000000\"/>", "<style select=\"@landscape candidate\" gap=\"2dp\"/>")
        assertNull(look(t).plain.fill)
        assertEquals(argb(0xFF000000), look(t, setOf("dark")).plain.fill)
        assertEquals(2.0, look(t, setOf("landscape")).gapDp)
    }

    @Test
    fun whatTheBarDoesNotReadIsWarnedOf() {
        assertTrue(
            warnings(
                "<style select=\"bar\" fill=\"#ff000000\"/>",
                "<style select=\"candidate\" fill=\"#ffffffff\" radius=\"4dp\" inset=\"2dp\" padding=\"0 8dp\" gap=\"2dp\" label-style=\"none\" color=\"#ff000000\" font=\"system\" weight=\"700\"/>",
                "<style select=\"candidate:first, candidate:selected\" fill=\"#ffffffff\" color=\"#ff000000\" font=\"system\" weight=\"700\"/>",
                "<style select=\"candidate label, candidate:first comment, candidate:selected label\" color=\"#ff000000\"/>",
                "<style select=\"candidate:pressed, candidate:first:pressed\" overlay=\"#40000000\"/>",
                "<style select=\"candidates-divider, candidates-panel\" fill=\"#ff000000\"/>",
            ).isEmpty(),
        )
        assertEquals(
            listOf("overlay= on candidate:pressed:selected does nothing: it reads nothing in that state"),
            warnings("<style select=\"candidate:selected:pressed\" overlay=\"#40000000\"/>"),
        )
        assertEquals(
            listOf("size= on candidate does nothing: it reads fill radius inset padding gap color font weight label-style"),
            warnings("<style select=\"candidate\" size=\"18sp\"/>"),
        )
        assertEquals(listOf("radius= on candidates-divider does nothing: it reads fill"), warnings("<style select=\"candidates-divider\" radius=\"2dp\"/>"))
        assertEquals(listOf("gap= on candidate:first does nothing: it reads fill color font weight"), warnings("<style select=\"candidate:first\" gap=\"2dp\"/>"))
        assertEquals(listOf("fill= on candidate comment does nothing: it reads color"), warnings("<style select=\"candidate comment\" fill=\"#ff000000\"/>"))
        assertEquals(listOf("color= on candidate:pressed label does nothing: it reads nothing of that part in that state"), warnings("<style select=\"candidate:pressed label\" color=\"#ff000000\"/>"))
        assertEquals(listOf("fill= on candidate:pressed does nothing: it reads overlay"), warnings("<style select=\"candidate:pressed\" fill=\"#ff000000\"/>"))
        assertEquals(listOf("fill= on bar:pressed does nothing: it reads nothing in that state"), warnings("<style select=\"bar:pressed\" fill=\"#ff000000\"/>"))
        assertEquals(listOf("gap= on key does nothing: only a candidate has it", "label-style= on key does nothing: only a candidate has it"), warnings("<style select=\"key\" gap=\"2dp\" label-style=\"dot\"/>"))
        // of the wrong form: refused as ever
        assertTrue(ThemeReader.theme(withStyle("<style select=\"candidate\" label-style=\"roman\"/>")).problems().isNotEmpty())
        assertTrue(ThemeReader.theme(withStyle("<style select=\"candidate\" gap=\"25dp\"/>")).problems().isNotEmpty())
        // :first is a candidate's alone
        assertTrue(ThemeReader.theme(withStyle("<style select=\"key:first\" fill=\"#ff000000\"/>")).problems().isNotEmpty())
    }

    @Test
    fun gapAndLabelStyleAreWrittenAndReadBack() {
        val t = theme("<style select=\"candidate\" gap=\"4.5dp\" label-style=\"number\"/>")
        val text = ThemeWriter.write(t)
        assertTrue("gap=\"4.5dp\" label-style=\"number\"" in text, text)
        val back = ThemeReader.theme(text).ok().styles.last().values
        assertEquals(Value.Number(4.5), back["gap"])
        assertEquals(Value.Word("number"), back["label-style"])
    }

    // the sample bar: laid out as the app's, each candidate its text and padding, the room after it a line
    @Test
    fun theSampleBarIsLaidOutAsTheAppsViews() {
        val m = BarSample.Metrics(width = 1000.0, height = 100.0, density = 2.0, textSize = 40.0, paddingDp = 9.0, minWidthDp = 32.0)
        val s = BarSample.scene(look(), m, FakeMeasurer, listOf(BarSample.Sample("有"), BarSample.Sample("油墨", "yo")))
        // 有: 40 wide, 18 each side (9dp); 油墨 yo: 80 + 3 × 24 (narrow) = 152, then 36
        assertEquals(listOf(76.0, 188.0), s.keys.map { it.bounds.width })
        // after each, the line: 2px (1dp) in the middle of the room it takes, 16px (8dp) short of each end
        assertEquals(listOf(0.0, 78.0), s.keys.map { it.bounds.left })
        val lines = s.base.filterIsInstance<Op.Fill>()
        assertEquals(listOf(76.0, 266.0), lines.map { it.shape.bounds.left })
        assertEquals(16.0, lines[0].shape.bounds.top)
        assertEquals(84.0, lines[0].shape.bounds.bottom)
        assertTrue(lines.all { it.color == press })
        // the words its colour, the comment its own, as runs of the text
        val texts = s.keys[1].faces.getValue(emptySet()).filterIsInstance<Op.Text>()
        assertEquals(listOf("油墨" to null, " yo" to "comment"), texts.map { it.text to it.part })
        assertEquals(listOf(text, comment), texts.map { it.color })
        // the touch floor: a narrow word 32dp wide
        val narrow = BarSample.scene(look(theme("<style select=\"candidate\" padding=\"0\"/>")), m, FakeMeasurer, listOf(BarSample.Sample("i")))
        assertEquals(64.0, narrow.keys.single().bounds.width)
        // pressed: its colour over its face
        assertEquals(Op.Fill(s.keys[0].shape, press), s.keys[0].faces.getValue(setOf(KeyState.Pressed)).last())
    }

    @Test
    fun theSampleBarTakesTheThemesSpacingAndLabels() {
        val m = BarSample.Metrics(width = 1000.0, height = 100.0, density = 2.0, textSize = 40.0, paddingDp = 9.0, minWidthDp = 32.0)
        val l = look(theme("<style select=\"candidate\" padding=\"0 5dp\" gap=\"3dp\" label-style=\"number\" fill=\"#ffffffff\" radius=\"50%\" inset=\"10dp 0\"/>"))
        val s = BarSample.scene(l, m, FakeMeasurer, listOf(BarSample.Sample("有"), BarSample.Sample("又")))
        // "1 有": 24 + 24 + 40 = 88, 10 each side; the room after it 6 + 2
        assertEquals(listOf(108.0, 108.0), s.keys.map { it.bounds.width })
        assertEquals(116.0, s.keys[1].bounds.left)
        val texts = s.keys[1].faces.getValue(emptySet()).filterIsInstance<Op.Text>()
        assertEquals(listOf("2 ", "又"), texts.map { it.text })
        assertEquals(listOf("label", null), texts.map { it.part })
        // a pill inset 20px top and bottom: 60 high, its corners 30
        val face = s.keys[0].shape as Shape.Rect
        assertEquals(Box(0.0, 20.0, 108.0, 80.0), face.box)
        assertEquals(List(4) { 30.0 }, face.radii)
        assertEquals(Op.Fill(face, argb(0xFFFFFFFF)), s.keys[0].faces.getValue(emptySet()).first())
        assertEquals("•", BarSample.label(BarLook.LabelStyle.Dot, 3))
        assertEquals("", BarSample.label(null, 3))
    }

    // T6b: the bar's height, the code being typed, the toolbar's buttons
    @Test
    fun noneSetIsTheCodeAndToolbarAsBefore() {
        val l = look()
        assertEquals(100.0, l.heightPercent)
        assertEquals(PreeditLook(text, text, null), l.preedit, "the keys' text colour, the underline fcitx's own")
        assertEquals(ToolbarLook(null, null, argb(0xFF7A858A), press, null, argb(0xFF5CB5AB)), l.toolbar, "toolbarIcon, lit in accentKey")
    }

    @Test
    fun theCodeAndToolbarAreTheThemes() {
        val l = look(
            theme(
                "<style select=\"bar\" height=\"120%\"/>",
                "<style select=\"@landscape bar\" height=\"90%\"/>",
                "<style select=\"preedit\" color=\"#ff112233\" underline=\"2dp @accentKey\"/>",
                "<style select=\"toolbar\" fill=\"#ffeeeeee\" radius=\"8dp\" icon-color=\"#ff445566\"/>",
                "<style select=\"toolbar:pressed\" overlay=\"#33000000\"/>",
                "<style select=\"toolbar:selected\" fill=\"@accentKey\" icon-color=\"#ffffffff\"/>",
            ),
        )
        assertEquals(120.0, l.heightPercent)
        assertEquals(PreeditLook(argb(0xFF112233), argb(0xFF112233), PreeditLook.Underline(2.0, argb(0xFF5CB5AB))), l.preedit, "the cursor the text's")
        assertEquals(ToolbarLook(argb(0xFFEEEEEE), Radius(List(4) { 8.0 }), argb(0xFF445566), argb(0x33000000), argb(0xFF5CB5AB), argb(0xFFFFFFFF)), l.toolbar)
        val turned = look(theme("<style select=\"bar\" height=\"120%\"/>", "<style select=\"@landscape bar\" height=\"90%\"/>"), setOf("landscape"))
        assertEquals(90.0, turned.heightPercent)
        // a cursor of its own; no underline at all
        val p = look(theme("<style select=\"preedit\" cursor-color=\"#ffff0000\" underline=\"none\"/>")).preedit
        assertEquals(PreeditLook(text, argb(0xFFFF0000), PreeditLook.Underline(0.0, null)), p)
        // on, a fill unset: the button's own
        assertEquals(argb(0xFFEEEEEE), look(theme("<style select=\"toolbar\" fill=\"#ffeeeeee\"/>")).toolbar.onFill)
    }

    @Test
    fun whatTheCodeAndToolbarDoNotReadIsWarnedOf() {
        assertTrue(warnings("<style select=\"preedit\" fill=\"#ff000000\"/>").single().contains("fill= on preedit does nothing"))
        assertTrue(warnings("<style select=\"toolbar:selected\" radius=\"4dp\"/>").single().contains("radius= on toolbar:selected does nothing"))
        assertTrue(warnings("<style select=\"key\" underline=\"none\"/>").single().contains("only the code being typed"))
        assertTrue(warnings("<style select=\"toolbar\" cursor-color=\"@keyText\"/>").any { it.contains("cursor-color= on toolbar does nothing") })
        assertTrue(ThemeReader.theme(withStyle("<style select=\"preedit\" underline=\"9dp @keyText\"/>")).problems().isNotEmpty(), "at most 3dp, a border's")
    }

    @Test
    fun theBarsHeightAndTheCodesUnderlineAreWrittenAndReadBack() {
        val t = theme("<style select=\"bar\" height=\"115%\"/>", "<style select=\"preedit\" underline=\"1.5dp @keyText\" cursor-color=\"@accentKey\"/>")
        val text = ThemeWriter.write(t)
        assertEquals(look(t), look(ThemeReader.theme(text).ok()), text)
    }
}
