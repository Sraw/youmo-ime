/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.problems
import org.fcitx.fcitx5.android.scene.Fixtures.warnings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A theme using what 0.6.3 added is told to say so in `min-app`, never refused for it. */
class ThemeMinAppTest {

    private val sticker = """<style select="qwerty/q" sticker="q.png"/>"""

    private fun with(meta: String?, vararg styles: String) = Fixtures.withStyle(*styles).let { s ->
        if (meta == null) s else s.replaceFirst("<colors", "$meta\n<colors")
    }

    private val told = "the theme uses what 0.6.3 added, which an older app refuses or draws wrong: write min-app=\"0.6.3\""

    @Test
    fun usedWithoutMinAppOrUnderItIsAWarning() {
        assertEquals(listOf(told), warnings(with(null, sticker)))
        assertEquals(listOf(told), warnings(with("""<meta min-app="0.6.0"/>""", sticker)))
        assertEquals(listOf(told), warnings(with("""<meta min-app="0.6.2"/>""", sticker)))
    }

    @Test
    fun saidOrUnusedIsNothing() {
        assertEquals(emptyList<String>(), warnings(with("""<meta min-app="0.6.3"/>""", sticker)))
        assertEquals(emptyList<String>(), warnings(with("""<meta min-app="0.7.0"/>""", sticker)))
        assertEquals(emptyList<String>(), warnings(with("""<meta min-app="0.6.0"/>""")))
    }

    @Test
    fun anOlderAppRefusesIt() {
        val source = with("""<meta min-app="${ThemeFeatures.MIN_APP_063}"/>""", sticker)
        assertTrue(ThemeReader.theme(source, appVersion = "0.6.2").problems().single().message.contains("needs the app 0.6.3"))
        assertTrue(problems(source).isEmpty())
    }
}
