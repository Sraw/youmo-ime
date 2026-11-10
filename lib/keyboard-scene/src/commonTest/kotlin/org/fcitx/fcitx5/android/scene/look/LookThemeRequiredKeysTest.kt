/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.measurer
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.palette
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.portrait
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.themeFile
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals

// a 3.0 theme the app took converts to one it imports: a required key hidden behind its own image keeps its words
// wherever its face is no image, pressed too; where its pressed face is an image (its own, its kind's) or Darken over
// its image, its words stay hidden as 3.0 drew them (once they showed over the kind's pressed image, the words twice)
class LookThemeRequiredKeysTest {
    private val material by lazy { LookDefaults.look(palette(themeFile("material-light.xml"))) }

    private fun slice(file: String) = NineSlice("/data/themes/t/$file", Insets(12, 12, 12, 12), 3)

    // the key, its own pressed face, its kind's, whether its words show pressed, why
    private class Row(val id: String, val own: KeyPressed?, val kind: KeyPressed, val shown: Boolean, val why: String)

    @Test
    fun aRequiredKeyHiddenBehindItsImageShowsItsWordsPressedOnlyOverAColour() {
        val colour = KeyPressed.Color(0xff112233.toInt())
        val down = KeyPressed.Image(slice("down.png"))
        val rows = listOf(
            Row("enter", colour, down, true, "a pressed colour of its own: no image there"),
            Row("enter", null, colour, true, "none of its own, its kind's a colour"),
            Row("enter", null, down, false, "none of its own, its kind's an image: that image is what shows"),
            Row("enter", null, KeyPressed.Darken, false, "none of its own, its kind's Darken: its own image tinted"),
            Row("enter", down, colour, false, "a pressed image of its own"),
            Row("shift", null, colour, true, "a function key, its kind's a colour"),
            Row("shift", null, down, false, "a function key, its kind's an image"),
        )
        for (r in rows) {
            val kind = KindStyle(KeyFill.Color, r.kind)
            val keys = material.keys.copy(
                function = kind, enter = kind,
                overrides = mapOf("*/${r.id}" to KeyOverride(KeyFill.Image(slice("own.png")), r.own, KeyLabel.Hide)),
            )
            val xml = LookTheme.xml(material.copy(keys = keys), "t", false)
            val theme = ThemeReader.theme(xml).ok()
            val drawnAs = SceneBuilder(theme, portrait, measurer).build(Fixtures.qwerty, emptyMap()).ok().keys.first { it.id == r.id }.kind
            // the converted theme's words pressed, as its cascade settles them for the key drawn as its kind (the
            // engine settles a key's words from its unpressed face, so its pressed ops cannot show them)
            val pressed = Selector.Target("key", "qwerty", r.id, r.id, setOf(drawnAs), setOf("pressed"), part = "label")
            val unhid = !TextLook(Cascade(theme, emptySet()).of(pressed), theme).hidden
            assertEquals(r.shown, unhid, "${r.why}: $xml")
            // never blank, so the converted theme imports
            assertEquals(emptyList<BlankKeys.Blank>(), BlankKeys.find(theme, listOf(BlankKeys.Board(Fixtures.qwerty)), limit = 10), r.why)
        }
    }
}
