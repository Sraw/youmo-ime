/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants that must hold for every built-in theme. Presets are 300 lines of hand-written
 * colour literals, which is exactly the shape of code where a copy-paste slip hides: a theme
 * whose `name` does not match its property, a dark theme left with `isDark = false`, or a
 * `deriveCustom*` helper that quietly drops a colour when a new field is added to [Theme].
 *
 * Presets are discovered reflectively so that adding one to [ThemePreset] automatically puts
 * it under test.
 */
class ThemePresetTest {

    private val presets: List<Pair<String, Theme.Builtin>> =
        ThemePreset::class.java.declaredFields
            .filter { Theme.Builtin::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                field.name to (field.get(ThemePreset) as Theme.Builtin)
            }

    /** Every colour a [Theme] exposes, so a newly added field is picked up by the diff checks. */
    private fun Theme.colours(): Map<String, Int> = mapOf(
        "backgroundColor" to backgroundColor,
        "barColor" to barColor,
        "keyboardColor" to keyboardColor,
        "keyBackgroundColor" to keyBackgroundColor,
        "keyTextColor" to keyTextColor,
        "candidateTextColor" to candidateTextColor,
        "candidateLabelColor" to candidateLabelColor,
        "candidateCommentColor" to candidateCommentColor,
        "altKeyBackgroundColor" to altKeyBackgroundColor,
        "altKeyTextColor" to altKeyTextColor,
        "accentKeyBackgroundColor" to accentKeyBackgroundColor,
        "accentKeyTextColor" to accentKeyTextColor,
        "keyPressHighlightColor" to keyPressHighlightColor,
        "keyShadowColor" to keyShadowColor,
        "popupBackgroundColor" to popupBackgroundColor,
        "popupTextColor" to popupTextColor,
        "spaceBarColor" to spaceBarColor,
        "dividerColor" to dividerColor,
        "clipboardEntryColor" to clipboardEntryColor,
        "genericActiveBackgroundColor" to genericActiveBackgroundColor,
        "genericActiveForegroundColor" to genericActiveForegroundColor,
    )

    // region the presets themselves

    @Test
    fun thereAreBuiltinPresetsToCheck() {
        assertTrue("reflection found no presets, the rest of this class is vacuous", presets.size >= 8)
    }

    @Test
    fun everyPresetNamesItselfAfterItsProperty() {
        for ((property, theme) in presets) {
            assertEquals("ThemePreset.$property", property, theme.name)
        }
    }

    @Test
    fun presetNamesAreUnique() {
        val names = presets.map { it.second.name }
        assertEquals("duplicate theme names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}",
            names.size, names.toSet().size)
    }

    /** A `*Light` / `*Dark` suffix must agree with the `isDark` flag. */
    @Test
    fun lightAndDarkSuffixesMatchTheIsDarkFlag() {
        for ((property, theme) in presets) {
            when {
                property.endsWith("Light") ->
                    assertEquals("$property should not be dark", false, theme.isDark)
                property.endsWith("Dark") ->
                    assertEquals("$property should be dark", true, theme.isDark)
            }
        }
    }

    @Test
    fun everyPresetDefinesEveryColour() {
        for ((property, theme) in presets) {
            assertEquals("$property is missing a colour", 21, theme.colours().size)
        }
    }

    /** Text must not be fully transparent, whatever the background does. */
    @Test
    fun foregroundColoursAreNotFullyTransparent() {
        val foregrounds = listOf(
            "keyTextColor", "candidateTextColor", "candidateLabelColor",
            "candidateCommentColor", "altKeyTextColor", "accentKeyTextColor",
            "popupTextColor", "genericActiveForegroundColor",
        )
        for ((property, theme) in presets) {
            val colours = theme.colours()
            for (name in foregrounds) {
                val alpha = (colours.getValue(name) ushr 24) and 0xff
                assertTrue("$property.$name is fully transparent", alpha > 0)
            }
        }
    }

    // endregion

    // region deriveCustomNoBackground

    @Test
    fun derivingWithoutABackgroundKeepsEveryColour() {
        for ((property, theme) in presets) {
            val custom = theme.deriveCustomNoBackground("derived")
            assertEquals("$property lost a colour when derived", theme.colours(), custom.colours())
        }
    }

    @Test
    fun derivingWithoutABackgroundKeepsTheDarkFlagAndTakesTheNewName() {
        for ((property, theme) in presets) {
            val custom = theme.deriveCustomNoBackground("derived")
            assertEquals("$property.isDark changed", theme.isDark, custom.isDark)
            assertEquals("derived", custom.name)
        }
    }

    @Test
    fun derivingWithoutABackgroundLeavesNoBackgroundImage() {
        for ((_, theme) in presets) {
            assertNull(theme.deriveCustomNoBackground("derived").backgroundImage)
        }
    }

    // endregion

    // region deriveCustomBackground

    @Test
    fun derivingWithABackgroundKeepsEveryColour() {
        for ((property, theme) in presets) {
            val custom = theme.deriveCustomBackground("derived", "/cropped.png", "/src.png")
            assertEquals("$property lost a colour when derived", theme.colours(), custom.colours())
        }
    }

    @Test
    fun derivingWithABackgroundRecordsBothImagePaths() {
        val custom = presets.first().second
            .deriveCustomBackground("derived", "/cropped.png", "/src.png")
        val image = custom.backgroundImage
        assertNotNull(image)
        assertEquals("/cropped.png", image!!.croppedFilePath)
        assertEquals("/src.png", image.srcFilePath)
    }

    @Test
    fun derivingWithABackgroundDefaultsBrightnessAndLeavesTheCropUnset() {
        val image = presets.first().second
            .deriveCustomBackground("derived", "/cropped.png", "/src.png")
            .backgroundImage!!
        assertEquals(70, image.brightness)
        assertNull(image.cropRect)
        assertEquals(0, image.cropRotation)
    }

    @Test
    fun derivingWithABackgroundAcceptsAnExplicitBrightness() {
        val image = presets.first().second
            .deriveCustomBackground("derived", "/cropped.png", "/src.png", brightness = 30)
            .backgroundImage!!
        assertEquals(30, image.brightness)
    }

    // endregion

    // region the two derive helpers agree

    /**
     * The only difference between the two helpers must be the background image. If a new colour
     * is added to [Theme] and wired into one helper but not the other, this catches it.
     */
    @Test
    fun bothDeriveHelpersProduceTheSameColours() {
        for ((property, theme) in presets) {
            val withoutImage = theme.deriveCustomNoBackground("derived")
            val withImage = theme.deriveCustomBackground("derived", "/a.png", "/b.png")
            assertEquals("$property: the helpers disagree", withoutImage.colours(), withImage.colours())
            assertEquals(withoutImage.isDark, withImage.isDark)
            assertEquals(withoutImage.name, withImage.name)
        }
    }

    // endregion
}
