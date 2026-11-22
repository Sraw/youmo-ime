/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Fixtures.problems
import org.fcitx.fcitx5.android.scene.Fixtures.warnings
import org.fcitx.fcitx5.android.scene.engine.BarLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `<picture variant="dark">`: the dark version's own picture. */
class ThemeDarkPictureTest {

    private val light = """<picture src="bg.png"><cut orientation="portrait" file="bg-p.png"/></picture>"""
    private val dark = """<picture variant="dark" src="bg-dark.png"><cut orientation="portrait" file="bg-dark-p.png"/></picture>"""

    private fun with(vararg pictures: String, variants: String = "light dark") =
        Fixtures.materialLightSource.replace("dark=\"false\"", "variants=\"$variants\"")
            .replaceFirst("</colors>", "</colors>\n" + pictures.joinToString("\n"))

    @Test
    fun bothAreReadByTheirVariantNotTheirOrder() {
        for (order in listOf(listOf(light, dark), listOf(dark, light))) {
            val t = ThemeReader.theme(with(*order.toTypedArray())).ok()
            assertEquals("bg.png", t.picture?.src)
            assertEquals("bg-dark.png", t.darkPicture?.src)
            assertEquals(mapOf("portrait" to "bg-dark-p.png"), t.darkPicture?.cuts)
        }
    }

    @Test
    fun eachVersionDrawsItsOwn() {
        val t = ThemeReader.theme(with(light, dark)).ok()
        assertEquals("bg.png", t.variant(false).shownPicture?.src)
        assertEquals("bg-dark.png", t.variant(true).shownPicture?.src)
        val one = ThemeReader.theme(with(light)).ok()
        assertNull(one.darkPicture)
        assertEquals("bg.png", one.variant(true).shownPicture?.src)
    }

    @Test
    fun theBarsPictureIsTheVersionsOwn() {
        val t = ThemeReader.theme(
            with(light.replace("<picture ", "<picture brightness=\"100%\" "), dark.replace("<picture ", "<picture brightness=\"60%\" "))
                .replace("</theme>", "<style select=\"bar\" fill=\"picture\"/>\n</theme>"),
        ).ok()
        fun brightness(dark: Boolean) = (BarLook.of(Cascades(t.variant(dark)), emptySet()).base as BarLook.Base.Picture).brightness
        assertEquals(100.0 to 60.0, brightness(false) to brightness(true))
    }

    @Test
    fun everyFileItDrawsIsAtItsPath() {
        val t = ThemeReader.theme(
            with(light, dark).replace(
                "</theme>",
                """<style select="qwerty/q" sticker="q.png" fill="image(k.png, 1 1 1 1)" effect="ripple-image(r.webp, 80%, 300ms)"/>
                <style select="qwerty/w" sticker="none"/>
                <icon name="shift" src="s.png" tint="true"/>
                </theme>""",
            ),
        ).ok()
        val at = t.withPaths { "/p/$it" }
        assertEquals("/p/bg.png" to "/p/bg-dark-p.png", at.picture?.src to at.darkPicture?.cuts?.get("portrait"))
        assertEquals("/p/s.png", at.icons["shift"])
        assertEquals(setOf("shift"), at.tintedIcons)
        val q = at.styles.single { it.values["sticker"] == Value.Word("/p/q.png") }.values
        assertEquals("/p/k.png", ((q["fill"] as Value.OfFill).fill as Fill.Image).file)
        assertEquals("/p/r.webp", (q["effect"] as Value.OfEffect).effect?.file)
        assertTrue(at.styles.any { it.values["sticker"] == Value.Word(Values.NONE) })
        assertEquals(t.variants, at.variants)
    }

    @Test
    fun aDarkOneAloneOrThreeAreRefused() {
        assertEquals(listOf("<picture variant=\"dark\"> needs a <picture> without variant="), problems(with(dark)))
        assertEquals(listOf("<picture variant=\"dark\"> given twice"), problems(with(light, dark, dark)))
        assertEquals(listOf("<picture> given twice"), problems(with(light, light, dark)))
        assertTrue(problems(with(light.replace("<picture ", "<picture variant=\"light\" "))).single().contains("variant="))
        assertEquals(
            listOf("<picture variant=\"dark\"> in a theme with no dark version is not drawn"),
            warnings(with(light, dark, variants = "light")).filter { !it.contains("min-app") },
        )
    }

    @Test
    fun writtenLightFirstThenDark() {
        val t = ThemeReader.theme(with(dark, light)).ok()
        val xml = ThemeWriter.write(t)
        assertTrue(xml.indexOf("<picture src=\"bg.png\"") in 0 until xml.indexOf("<picture variant=\"dark\" src=\"bg-dark.png\""), xml)
        val again = ThemeReader.theme(xml).ok()
        assertEquals(t.darkPicture?.src, again.darkPicture?.src)
        assertEquals(t.darkPicture?.cuts, again.darkPicture?.cuts)
        assertTrue(ThemeFeatures.since063(t))
    }
}
