/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Fixtures.warnings
import org.fcitx.fcitx5.android.scene.theme.ThemeFeatures
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pet is what 0.6.3 added: the theme says min-app 0.6.3, and an older app refuses it rather than drop it unsaid. */
class ThemePetTest {

    private val pet = """<pet><state name="idle" sheet="pet-idle.png"/></pet>"""

    private fun with(meta: String?, pet: String) = Fixtures.materialLightSource.replace("</theme>", "$pet\n</theme>").let { s ->
        if (meta == null) s else s.replaceFirst("<colors", "$meta\n<colors")
    }

    private val told = "the theme uses what 0.6.3 added, which an older app refuses or draws wrong: write min-app=\"0.6.3\""

    @Test
    fun aPetIsWhat063Added() {
        assertTrue(ThemeFeatures.since063(ThemeReader.theme(with(null, pet)).ok()))
        assertFalse(ThemeFeatures.since063(Fixtures.materialLight))
        assertEquals(listOf(told), warnings(with(null, pet)))
        assertEquals(emptyList(), warnings(with("""<meta min-app="0.6.3"/>""", pet)))
    }

    @Test
    fun anOlderAppRefusesIt() {
        val source = with("""<meta min-app="0.6.3"/>""", pet)
        assertTrue(ThemeReader.theme(source, appVersion = "0.6.2").problems().any { "needs the app 0.6.3" in it.message })
    }

    @Test
    fun aPetLeftOutAsksNoMinApp() {
        val broken = """<pet><state name="typing" sheet="t.png"/></pet>"""
        assertFalse(warnings(with(null, broken)).contains(told))
    }
}
