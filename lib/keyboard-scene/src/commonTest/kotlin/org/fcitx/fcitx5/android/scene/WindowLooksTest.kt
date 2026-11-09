/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-p3 T7a: the window round the keys, `frame`, `handle`, `side-button`, `split-gap`, `navbar`
class WindowLooksTest {
    private val source = testData("material-light.xml")

    private fun withStyle(style: String) = source.replace("</theme>", "$style\n</theme>")

    private fun theme(s: String): Theme = ThemeReader.theme(s).ok()

    private fun looks(t: Theme = Fixtures.materialLight) = WindowLooks(Cascades(t))

    private fun warnings(s: String): List<String> {
        val t = ThemeReader.theme(s)
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    private fun problems(s: String) = ThemeReader.theme(s).problems().map { it.message }

    private fun argb(v: Long) = Argb(v.toInt())

    @Test
    fun theFrameNotSetIsAsRoundAsTheKeysOnTheirMargins() {
        // material-light: keys 4dp round, 7dp by 3dp of margin, 4dp by 3dp turned; drawn flat, square
        val l = looks()
        assertEquals(WindowLooks.Frame(7.0, false, null, 6.0, null), l.frame(emptySet()))
        assertEquals(7.0, l.frame(setOf("landscape")).radiusDp)
        assertEquals(3.0, l.frame(setOf("flat")).radiusDp, "flat: the margin alone")
        assertEquals(3.0, l.frame(setOf("flat", "landscape", "dark")).radiusDp)
        // keys square (直角): the frame too, flat or not
        val square = looks(theme(withStyle("<style select=\"key\" radius=\"0\"/>")))
        assertEquals(0.0, square.frame(emptySet()).radiusDp)
        assertEquals(0.0, square.frame(setOf("flat")).radiusDp)
        // the corner the most round of the four, the least of the four margins
        val uneven = looks(theme(withStyle("<style select=\"key\" radius=\"2dp 9dp 0 0\" inset=\"5dp 6dp 1dp 8dp\"/>")))
        assertEquals(10.0, uneven.frame(emptySet()).radiusDp)
        // a pill's radius no window knows: the margin
        assertEquals(3.0, looks(theme(withStyle("<style select=\"key\" radius=\"50%\"/>"))).frame(emptySet()).radiusDp)
        // a theme's letters, not its other keys
        assertEquals(7.0, looks(theme(withStyle("<style select=\"key.function\" radius=\"12dp\"/>"))).frame(emptySet()).radiusDp)
        assertEquals(15.0, looks(theme(withStyle("<style select=\"@floating key.letter\" radius=\"12dp\"/>"))).frame(emptySet()).radiusDp)
    }

    @Test
    fun theFrameATheme() {
        val t = theme(
            withStyle(
                "<style select=\"frame\" radius=\"12dp\" border=\"2dp @keyText\" shadow=\"0 0 10dp #80ff0000\"/>" +
                    "<style select=\"@landscape frame\" radius=\"50%\" shadow=\"none\" border=\"none\"/>",
            ),
        )
        val l = looks(t)
        assertEquals(WindowLooks.Frame(12.0, false, WindowLooks.Line(2.0, argb(0xff37474f)), 10.0, argb(0x80ff0000)), l.frame(emptySet()))
        assertEquals(WindowLooks.Frame(0.0, true, null, 0.0, null), l.frame(setOf("landscape")))
        // under @floating, whatever the scenes say
        val floating = looks(theme(withStyle("<style select=\"@floating frame\" radius=\"9dp\"/>")))
        assertEquals(9.0, floating.frame(emptySet()).radiusDp)
    }

    @Test
    fun theHandleNotSetIsTheAppsOfBefore() {
        val l = looks()
        // the bar's #e4e7e9 with 10% of the text's #37474f, each channel rounded: 228-17.3, 231-16, 233-15.4
        val strip = Argb.of(0xff, 211, 215, 218)
        val grip = argb(0xff7a858a)
        assertEquals(WindowLooks.Handle(strip, grip, grip, 36.0, 4.0), l.handle(emptySet(), top = true))
        assertEquals(WindowLooks.Handle(null, grip, grip, 36.0, 4.0), l.handle(emptySet(), top = false))
        // a light bar with dark text: a little darker; a dark bar with light text: a little lighter (FloatingKeyboardTest's of 0.5.2)
        assertEquals(argb(0xFFE6E6E6), WindowLooks.handleColor(argb(0xFFFFFFFF), argb(0xFF000000)))
        assertEquals(argb(0xFF1A1A1A), WindowLooks.handleColor(argb(0xFF000000), argb(0xFFFFFFFF)))
        assertEquals(argb(0x80F2ECE9), WindowLooks.handleColor(argb(0x80FFFFFF), argb(0x00804020)))
        // the bar's alpha kept (a mix() would mix it to the text's)
        val see = Argb.of(0x40, 0, 0, 0)
        assertEquals(Argb.of(0x40, 26, 26, 26), WindowLooks.handleColor(see, Argb.of(0xff, 255, 255, 255)))
    }

    @Test
    fun theHandleATheme() {
        val t = theme(
            withStyle(
                "<style select=\"handle\" fill=\"@keyboard\" color=\"#ff0000\" icon-color=\"#00ff00\" width=\"48dp\" height=\"6dp\"/>" +
                    "<style select=\"@dark handle\" color=\"#0000ff\"/>",
            ),
        )
        val l = looks(t)
        val keyboard = t.colors.getValue("keyboard")
        assertEquals(WindowLooks.Handle(keyboard, argb(0xffff0000), argb(0xff00ff00), 48.0, 6.0), l.handle(emptySet(), top = true))
        assertEquals(keyboard, l.handle(emptySet(), top = false).fill, "the bottom one's set too")
        assertEquals(argb(0xff0000ff), l.handle(setOf("dark"), top = true).color)
        // the dock button's icon the grip's where not set
        assertEquals(argb(0xffff0000), looks(theme(withStyle("<style select=\"handle\" color=\"#ff0000\"/>"))).handle(emptySet(), true).iconColor)
    }

    @Test
    fun aSideButton() {
        val plain = looks().sideButton(setOf("one-handed"))
        assertEquals(WindowLooks.SideButton(null, argb(0xff7a858a), true, 0.0, false, argb(0x1f000000)), plain)
        val t = theme(withStyle("<style select=\"side-button\" fill=\"@key\" icon-color=\"@keyText\" shape=\"rect\" radius=\"8dp\" overlay=\"#33000000\"/>"))
        assertEquals(
            WindowLooks.SideButton(t.colors.getValue("key"), argb(0xff37474f), false, 8.0, false, argb(0x33000000)),
            looks(t).sideButton(setOf("one-handed")),
        )
        // pressed as a key is: `side-button:pressed`'s overlay over `side-button`'s, either alone too
        val pressed = theme(withStyle("<style select=\"side-button:pressed\" overlay=\"#44000000\"/>"))
        assertEquals(argb(0x44000000), looks(pressed).sideButton(emptySet()).pressed)
        val both = theme(
            withStyle("<style select=\"side-button:pressed\" overlay=\"#44000000\"/><style select=\"side-button\" overlay=\"#33000000\"/>"),
        )
        assertEquals(argb(0x44000000), looks(both).sideButton(emptySet()).pressed, "the state's, more specific, wins")
    }

    @Test
    fun theNavigationBarFollowsUnlessSet() {
        assertNull(looks().navbar(emptySet()))
        val t = theme(withStyle("<style select=\"navbar\" color=\"@bar\"/><style select=\"@landscape navbar\" color=\"follow\"/>"))
        assertEquals(t.colors.getValue("bar"), looks(t).navbar(emptySet()))
        assertNull(looks(t).navbar(setOf("landscape")), "follow, later, wins")
    }

    @Test
    fun theSplitGapDrawnWhereSet() {
        val split = Fixtures.landscape
        val plain = Fixtures.scene(split)
        assertEquals(Fixtures.scene(split).base, plain.base)
        val red = argb(0xffff0000)
        assertTrue(plain.base.none { it is Op.Fill && it.color == red })
        val t = theme(withStyle("<style select=\"split-gap\" fill=\"#ff0000\" radius=\"2dp\"/>"))
        val gapped = SceneBuilder(t, split, FakeMeasurer).build(Fixtures.qwerty).ok()
        val gaps = gapped.base.filterIsInstance<Op.Fill>().filter { it.color == red }
        // the three rows of letters split in two, each its room between
        assertEquals(3, gaps.size, "$gaps")
        for (g in gaps) {
            val box = g.shape.bounds
            assertTrue(box.left >= split.width * 0.25 && box.right <= split.width * 0.75, "$box")
            assertEquals(List(4) { 2 * Fixtures.DENSITY }, (g.shape as Shape.Rect).radii)
        }
        assertEquals(plain.base, gapped.base - gaps.toSet(), "the rest as it was")
        val gapLines = gaps.map { "  ${SceneText.op(it)}" }.toSet()
        assertEquals(SceneText.of(plain).lines(), SceneText.of(gapped).lines().filter { it !in gapLines }, "the keys as they were")
        // not split: none
        assertTrue(SceneBuilder(t, Fixtures.portrait, FakeMeasurer).build(Fixtures.qwerty).ok().base.none { it is Op.Fill && it.color == red })
    }

    @Test
    fun theWindowsWidgetsRead() {
        // the handle's grip: dp, in range; on a list with another widget, a later stage's
        assertTrue(warnings(withStyle("<style select=\"handle\" width=\"0\" height=\"24dp\"/>")).isEmpty())
        assertTrue(problems(withStyle("<style select=\"handle\" width=\"161dp\"/>")).any { it.contains("width=\"161dp\" is not 0–160dp") })
        assertTrue(problems(withStyle("<style select=\"handle\" height=\"25dp\"/>")).any { it.contains("is not 0–24dp") })
        assertTrue(problems(withStyle("<style select=\"handle\" height=\"30%\"/>")).isNotEmpty())
        assertTrue(warnings(withStyle("<style select=\"handle, bar\" height=\"6dp\"/>")).single().contains("height= is not supported"))
        assertTrue(warnings(withStyle("<style select=\"bar\" height=\"6dp\"/>")).single().contains("height= is not supported"))
        // a grip's width is not the keyboard's size
        assertEquals(null, theme(withStyle("<style select=\"handle\" height=\"6dp\"/>")).keyboardSizes.height)
        // follow: the navigation bar's alone
        assertTrue(warnings(withStyle("<style select=\"navbar\" color=\"follow\"/>")).isEmpty())
        val other = warnings(withStyle("<style select=\"preview\" color=\"follow\"/>"))
        assertEquals(listOf("color=\"follow\" is the navigation bar's alone: ignored"), other)
        assertTrue(warnings(withStyle("<style select=\"navbar, key\" color=\"follow\"/>")).single().contains("navigation bar's alone"))
        assertTrue(problems(withStyle("<style select=\"navbar\" color=\"folow\"/>")).isNotEmpty())
        // the frame's shadow has no offset
        assertTrue(warnings(withStyle("<style select=\"frame\" shadow=\"0 0 8dp #000000\"/>")).isEmpty())
        assertTrue(warnings(withStyle("<style select=\"frame\" shadow=\"0 2dp 8dp #000000\"/>")).single().contains("its x and y are ignored"))
        // what a window widget does not read, warned of (in a list, of that widget alone); what it reads, not
        val unread = mapOf(
            "<style select=\"navbar\" fill=\"#ff0000\"/>" to "fill= on navbar does nothing: it reads color",
            "<style select=\"frame\" fill=\"#ff0000\"/>" to "fill= on frame does nothing: it reads radius border shadow",
            "<style select=\"handle\" radius=\"4dp\"/>" to "radius= on handle does nothing: it reads fill color icon-color width height",
            "<style select=\"side-button\" color=\"#ff0000\"/>" to "color= on side-button does nothing: it reads fill icon-color shape radius overlay",
            "<style select=\"split-gap, key\" color=\"#ff0000\"/>" to "color= on split-gap does nothing: it reads fill radius",
        )
        for ((style, warning) in unread) assertEquals(listOf(warning), warnings(withStyle(style)), style)
        val read = listOf(
            "<style select=\"frame\" radius=\"8dp\" border=\"1dp #ff0000\" shadow=\"none\"/>",
            "<style select=\"handle\" fill=\"#ff0000\" color=\"#ff0000\" icon-color=\"#ff0000\" width=\"40dp\" height=\"4dp\"/>",
            "<style select=\"side-button:pressed\" fill=\"#ff0000\" icon-color=\"#ff0000\" shape=\"rect\" radius=\"4dp\" overlay=\"#ff0000\"/>",
            "<style select=\"split-gap\" fill=\"#ff0000\" radius=\"4dp\"/>",
        )
        for (style in read) assertEquals(emptyList(), warnings(withStyle(style)), style)
        // each a widget now, under a scene too
        for (w in listOf("frame", "handle", "side-button", "split-gap", "navbar")) {
            assertNull(Selector.parse("@dark $w")?.reserved, w)
        }
    }
}
