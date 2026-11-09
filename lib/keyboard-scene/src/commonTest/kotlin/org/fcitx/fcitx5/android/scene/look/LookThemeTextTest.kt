/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.palette
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.themeFile
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The text LookTheme writes for looks that take each of its ways (a picture, images, single keys of
 * every kind, flat, the old settings' square keys), held as files (data/look-theme): a change to
 * what a 3.0 theme becomes is seen and meant.
 */
class LookThemeTextTest {

    @Test
    fun theTextIsTheFiles() {
        val failed = LOOKS.mapNotNull { (name, look) ->
            val text = LookTheme.xml(look, name, name.endsWith("dark"))
            val path = "$TEST_DATA/look-theme/$name.xml"
            val committed = readText(path)
            when {
                recording() -> null.also { writeText(path, text) }
                committed == null -> "no $path: run the JVM tests with -Psnapshots.record=true"
                committed != text -> "$path differs: review, then record anew"
                else -> null
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    companion object {
        private fun slice(file: String, insets: Int = 12, px: Int = 3) = NineSlice("/data/themes/t/$file", Insets(insets, insets, insets, insets), px)

        private val material by lazy { LookDefaults.look(palette(themeFile("material-light.xml"))) }

        private val picture = Picture(
            "/data/themes/t/src.jpg",
            Crop("/data/themes/t/port.png", Box(0, 300, 1440, 1800), 0),
            Crop("/data/themes/t/land.png", Box(0, 900, 1440, 1500), 90),
            70,
            0,
        )

        /** The looks, by the name their file has (`…-dark` written as a dark theme). */
        val LOOKS: List<Pair<String, ThemeLook>> by lazy {
            listOf(
                "default" to material,
                "default-dark" to LookDefaults.look(palette(themeFile("material-dark.xml"))),
                // the old settings: flat, square, no gaps, a stroke, a ripple; the bar its colour
                "legacy-flat" to LegacyLook(false, true, true, 0, 0, 0, 0, 0).applyTo(material),
                "legacy-blocks" to LegacyLook(true, false, false, 10, 5, 2, 9, 6).applyTo(LookDefaults.look(ThemeLookTest.PALETTE)),
                "picture" to material.copy(
                    picture = picture,
                    base = BoardBase.Picture,
                    bar = BarBase.Picture(12, 60),
                    keys = material.keys.copy(
                        radiusDp = 8,
                        borderDp = 1,
                        shadowDp = 0,
                        letter = KindStyle(KeyFill.Picture(10, 80), KeyPressed.Darken),
                        function = KindStyle(KeyFill.Color, KeyPressed.Color(0xff5cb5ab.toInt())),
                        space = KindStyle(KeyFill.Image(slice("space.png")), KeyPressed.Darken),
                        enter = KindStyle(KeyFill.Image(slice("enter.png")), KeyPressed.Image(slice("enter-down.png"))),
                    ),
                    text = Text("/data/themes/t/font.ttf", "/data/themes/t/candidates.ttf", 700, 300, 115),
                ),
                // the picture under the keys only, the board and bar of colours
                "picture-keys" to material.copy(
                    picture = picture.copy(landscape = null, blurDp = 6),
                    bar = BarBase.Color,
                    keys = material.keys.copy(function = KindStyle(KeyFill.Picture(3, 100), KeyPressed.Image(slice("fn-down.png")))),
                ),
                // single keys of each face: an own fill alone, an own pressed alone, both, Darken with and without a
                // fill, label hidden; over kinds of each fill; a ripple
                "single-keys" to material.copy(
                    picture = picture,
                    keys = material.keys.copy(
                        press = PressEffect.Ripple,
                        function = KindStyle(KeyFill.Picture(4, 90), KeyPressed.Darken),
                        space = KindStyle(KeyFill.Color, KeyPressed.Image(slice("down.png", 1, 1))),
                        enter = KindStyle(KeyFill.Image(slice("enter.png")), KeyPressed.Color(0xff112233.toInt())),
                        overrides = mapOf(
                            "qwerty/q" to KeyOverride(KeyFill.Picture(2, 80), null, KeyLabel.Show),
                            "qwerty/a" to KeyOverride(KeyFill.Picture(2, 80), null, KeyLabel.Show),
                            "*/shift" to KeyOverride(KeyFill.Image(slice("key.png", 1, 1)), KeyPressed.Image(slice("down.png", 1, 1)), KeyLabel.Show),
                            "*/backspace" to KeyOverride(KeyFill.Color, null, KeyLabel.Show),
                            "*/enter" to KeyOverride(null, KeyPressed.Image(slice("down.png", 1, 1)), KeyLabel.Show),
                            "*/space" to KeyOverride(null, KeyPressed.Color(0xff445566.toInt()), KeyLabel.Hide),
                            "qwerty/w" to KeyOverride(KeyFill.Image(slice("key.png", 1, 1)), KeyPressed.Color(0xff445566.toInt()), KeyLabel.Hide),
                            "qwerty/e" to KeyOverride(null, KeyPressed.Darken, KeyLabel.Show),
                            "qwerty/r" to KeyOverride(KeyFill.Image(slice("r.png")), KeyPressed.Darken, KeyLabel.Hide),
                            "*/lang" to KeyOverride(null, KeyPressed.Darken, KeyLabel.Show),
                        ),
                    ),
                ),
                // names a selector reads otherwise, two files of one name in two folders, a name to escape
                "names-dark" to material.copy(
                    keys = material.keys.copy(
                        letter = KindStyle(KeyFill.Image(NineSlice("/t/a/key.png", Insets(1, 1, 1, 1), 1)), KeyPressed.Image(NineSlice("/t/b/key.png", Insets(0, 0, 0, 0), 1))),
                        overrides = listOf("zhuyin/,", "zhuyin/.", "zhuyin/;", "zhuyin//", "number/*", "number/:", "zhuyin-nine/r1c1")
                            .associateWith { KeyOverride(KeyFill.Image(slice("k.png")), null, KeyLabel.Hide) },
                    ),
                ),
            )
        }
    }
}
