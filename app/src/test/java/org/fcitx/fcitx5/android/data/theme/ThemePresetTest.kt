/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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

    /** Every colour a [Theme] exposes, found by reflection so a newly added field is picked up by the diff checks. */
    private val colourGetters = Theme::class.java.declaredMethods.filter {
        it.name.startsWith("get") && it.returnType == Int::class.javaPrimitiveType &&
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && !it.isSynthetic
    }

    private fun Theme.colours(): Map<String, Int> = colourGetters.associate {
        it.name.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } to it.invoke(this) as Int
    }

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
    fun thereAreColoursToCheck() {
        assertTrue("reflection found too few colours, the diff checks are vacuous", colourGetters.size >= 21)
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
}
