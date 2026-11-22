/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `<pet>` as theme.xml writes it: each attribute's bounds, and anything wrong a warning that leaves the pet out, never the theme. */
class PetReaderTest {

    private val idle = """<state name="idle" sheet="pet-idle.png" frames="4" columns="4" fps="4"/>"""

    private fun source(pet: String) = Fixtures.materialLightSource.replace("</theme>", "$pet\n</theme>")

    private fun pet(attrs: String = "", vararg states: String = arrayOf(idle)) = "<pet$attrs>${states.joinToString("")}</pet>"

    private fun read(pet: String): Theme = ThemeReader.theme(source(pet)).ok()

    private fun warnings(pet: String) = ThemeReader.theme(source(pet)).warnings().map { it.message }
        .filter { !it.startsWith("the theme uses what 0.6.3 added") }

    private fun leftOut(pet: String, why: String) {
        val t = read(pet)
        assertNull(t.pet, "left out: $why")
        assertTrue(warnings(pet).any { it.startsWith("<pet> left out:") && why in it }, "${warnings(pet)} says $why")
    }

    @Test
    fun theDesignsExampleReads() {
        val p = read(
            """<pet place="keyboard" align="top-end" size="48dp" offset-x="-4dp" offset-y="2dp">
              <state name="idle"   sheet="pet-idle.png"  frames="4" columns="4" fps="4"/>
              <state name="typing" sheet="pet-peck.png"  frames="6" columns="6" fps="12"/>
              <state name="delete" sheet="pet-shake.png" frames="5" fps="12"/>
              <state name="sleep"  sheet="pet-sleep.png" frames="4" fps="2" loop="true"/>
            </pet>""",
        ).pet!!
        assertEquals(Pet.Place.KEYBOARD, p.place)
        assertEquals(Pet.Align.TOP_END, p.align)
        assertEquals(Triple(48.0, -4.0, 2.0), Triple(p.sizeDp, p.offsetXDp, p.offsetYDp))
        assertEquals(PetState("pet-shake.png", 5, 5, 12, false), p.states[PetState.Name.DELETE])
        assertEquals(PetState("pet-sleep.png", 4, 4, 2, true), p.states[PetState.Name.SLEEP])
        assertEquals(2, PetState("a.png", 5, 3, 8, false).rows)
    }

    @Test
    fun defaultsAreTheTablesOnes() {
        val p = read(pet("", """<state name="idle" sheet="i.webp"/>""", """<state name="enter" sheet="e.png"/>""")).pet!!
        assertEquals(Pet.Place.KEYBOARD, p.place)
        assertEquals(Pet.Align.TOP_END, p.align)
        assertEquals(Triple(PetLimits.DEFAULT_SIZE_DP, 0.0, 0.0), Triple(p.sizeDp, p.offsetXDp, p.offsetYDp))
        assertEquals(PetState("i.webp", 1, 1, 8, true), p.idle)
        assertEquals(PetState("e.png", 1, 1, 8, false), p.states[PetState.Name.ENTER])
    }

    @Test
    fun sizesOffsetsAndFpsOutOfRangeAreClampedWithAWarning() {
        val p = read(pet(""" size="80dp" offset-x="-40dp" offset-y="33dp"""", """<state name="idle" sheet="i.png" fps="40"/>""")).pet!!
        assertEquals(Triple(64.0, -32.0, 32.0), Triple(p.sizeDp, p.offsetXDp, p.offsetYDp))
        assertEquals(30, p.idle.fps)
        assertEquals(4, warnings(pet(""" size="8dp" offset-x="-40dp" offset-y="33dp"""", """<state name="idle" sheet="i.png" fps="0"/>""")).size)
        assertEquals(16.0, read(pet(""" size="8dp"""")).pet!!.sizeDp)
        assertEquals(1, read(pet("", """<state name="idle" sheet="i.png" fps="0"/>""")).pet!!.idle.fps)
        assertEquals(Triple(16.0, 32.0, -32.0), read(pet(""" size="16dp" offset-x="32dp" offset-y="-32dp"""")).pet!!.let { Triple(it.sizeDp, it.offsetXDp, it.offsetYDp) })
        assertEquals(emptyList(), warnings(pet(""" size="64dp"""")))
    }

    @Test
    fun aBadPlaceAlignOrLengthLeavesThePetOut() {
        leftOut(pet(""" place="corner""""), "place=")
        leftOut(pet(""" align="middle""""), "align=")
        leftOut(pet(""" size="big""""), "size=")
        leftOut(pet(""" colour="red""""), "unknown attribute colour=")
        assertTrue("<pet>: align= does nothing at the bar's end" in warnings(pet(""" place="bar-end" align="top-start"""")))
        val bar = pet(""" place="bar-end" offset-x="4dp" offset-y="-2dp"""")
        assertEquals(Triple(Pet.Place.BAR_END, 0.0, 0.0), read(bar).pet!!.let { Triple(it.place, it.offsetXDp, it.offsetYDp) })
        assertEquals(2, warnings(bar).count { "does nothing at the bar's end" in it })
    }

    @Test
    fun framesAndColumnsOutOfRangeAreErrors() {
        leftOut(pet("", """<state name="idle" sheet="i.png" frames="0"/>"""), "frames=")
        leftOut(pet("", """<state name="idle" sheet="i.png" frames="25"/>"""), "frames=")
        leftOut(pet("", """<state name="idle" sheet="i.png" frames="4" columns="5"/>"""), "columns=")
        leftOut(pet("", """<state name="idle" sheet="i.png" frames="4" columns="0"/>"""), "columns=")
        leftOut(pet("", """<state name="idle" sheet="i.png" loop="yes"/>"""), "loop=")
        assertEquals(PetState("i.png", 24, 1, 8, true), read(pet("", """<state name="idle" sheet="i.png" frames="24" columns="1"/>""")).pet!!.idle)
    }

    @Test
    fun aStateMissingTwiceOrUnknown() {
        leftOut(pet("", """<state name="typing" sheet="t.png"/>"""), "needs a <state name=\"idle\">")
        leftOut(pet("", idle, idle), "the state idle is given twice")
        leftOut(pet("", idle, """<frame/>"""), "<frame> is not allowed in <pet>")
        leftOut(pet("", """<state sheet="i.png"/>"""), "name= is required")
        // a later version's state is not read at all
        val later = pet("", idle, """<state name="dance" sheet="d.png" tempo="fast"/>""")
        assertEquals(setOf(PetState.Name.IDLE), read(later).pet!!.states.keys)
        assertTrue(warnings(later).any { "the state dance is not one this version knows, ignored" in it })
    }

    @Test
    fun aSheetIsAPlainPngOrWebpName() {
        leftOut(pet("", """<state name="idle" sheet="pets/idle.png"/>"""), "sheet=")
        leftOut(pet("", """<state name="idle" sheet="..\idle.png"/>"""), "sheet=")
        leftOut(pet("", """<state name="idle" sheet="idle.gif"/>"""), "is not a .png or .webp")
        leftOut(pet("", """<state name="idle"/>"""), "sheet= is required")
    }

    @Test
    fun idleAlwaysLoops() {
        val p = """<state name="idle" sheet="i.png" loop="false"/>"""
        assertTrue(read(pet("", p)).pet!!.idle.loop)
        assertTrue(warnings(pet("", p)).any { "loop=\"false\" on idle is taken as true" in it })
        assertEquals(false, read(pet("", idle, """<state name="sleep" sheet="s.png" loop="false"/>""")).pet!!.states[PetState.Name.SLEEP]!!.loop)
    }

    @Test
    fun overNinetySixFramesInAllIsAnError() {
        val four = PetState.Name.entries.take(4).map { """<state name="${it.id}" sheet="${it.id}.png" frames="24"/>""" }.toTypedArray()
        assertEquals(96, PetLimits.total(read(pet("", *four)).pet!!))
        leftOut(pet("", *four, """<state name="sleep" sheet="s.png"/>"""), "over 96 frames in all")
    }

    @Test
    fun givenTwiceIsLeftOut() {
        val t = read(pet() + pet())
        assertNull(t.pet)
        assertTrue(warnings(pet() + pet()).any { "<pet> given twice" in it })
    }

    @Test
    fun aWrongPetLeavesTheRestOfTheThemeAsItIs() {
        val t = read(pet("", """<state name="typing" sheet="t.png"/>"""))
        assertEquals(Fixtures.materialLight.styles.size, t.styles.size)
        assertEquals(Fixtures.materialLight.colors, t.colors)
        // out of order is still the theme's error, as any element's
        assertTrue(ThemeReader.theme(Fixtures.materialLightSource.replace("<colors", pet() + "\n<colors")).problems().any { "out of order" in it.message })
    }

    @Test
    fun writtenAndReadBackItIsTheSame() {
        val all = PetState.Name.entries.map { """<state name="${it.id}" sheet="${it.id}&amp;1.png" frames="3" columns="2" fps="5"/>""" }.toTypedArray()
        for (attrs in listOf(""" size="48.5dp" offset-x="-4dp" offset-y="2dp" align="bottom-start"""", """ place="bar-end" size="20dp"""", "")) {
            val t = read(pet(attrs, *all))
            val again = ThemeReader.theme(ThemeWriter.write(t)).ok()
            assertEquals(t.pet, again.pet)
            assertEquals(ThemeWriter.write(t), ThemeWriter.write(again))
        }
        assertTrue("pet" !in ThemeWriter.write(Fixtures.materialLight))
    }

    @Test
    fun theDarkVersionAndAPacksPathsKeepIt() {
        val t = read(pet())
        assertEquals(t.pet, t.variant(!t.dark).pet)
        assertEquals("/p/pet-idle.png", t.withPaths { "/p/$it" }.pet!!.idle.sheet)
    }
}
