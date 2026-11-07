/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Theme file 3.0 drawn (§4.2 of dev/design/051-d3-theme-maker.md): five sample themes, light and
 * dark, on every keyboard and in every mode. A few are goldens (src/test/screenshots/
 * ThemeLookScreenshotTest), a regression for `KeyFaceDrawable`, the picture and the bar; the whole
 * set, for the eye, with `-PthemeShots=<dir>`.
 *
 * The images are made in ThemeLookSamples: a picture of bands and rings (where a key's part of it is shows), a
 * nine-slice key with a thick frame (a stretched corner shows), a letter of each hue. A font file
 * is none: the font sample's keys and candidates are drawn in monospace, as a font read would be.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class ThemeLookScreenshotTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val dir = TemporaryFolder()

    private val shots: File? = System.getProperty("themeShots.dir")?.takeIf { it.isNotEmpty() }?.let(::File)

    private val realDecoder = ThemeAssets.decoder
    private val realRunner = ThemeAssets.runner
    private val realPoster = ThemeAssets.poster

    @Before
    fun setUp() {
        ThemeAssets.clear()
        // decoded at once on this thread: each shot has its images in
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
        ThemeAssets.decoder = object : ThemeAssets.Decoder by realDecoder {
            override fun typeface(path: String): Typeface? = Typeface.MONOSPACE
        }
    }

    @After
    fun tearDown() {
        ThemeAssets.decoder = realDecoder
        ThemeAssets.runner = realRunner
        ThemeAssets.poster = realPoster
        ThemeAssets.clear()
    }

    private val looks by lazy { ThemeLookSamples(dir.root) }

    private class Board(val name: String, val keyboards: ImKeyboards.Chosen? = null, val inputType: Int = InputType.TYPE_CLASS_TEXT)

    private val boards = listOf(
        Board("full", ImKeyboards.Chosen()),
        Board("nine", ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)),
        Board("k14", ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)),
        Board("k18", ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)),
        Board("zhuyin_nine", ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)),
        Board("zhuyin_full", ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)),
        Board("number", inputType = InputType.TYPE_CLASS_NUMBER),
        Board("symbols"),
    )

    // [golden] under src/test/screenshots, compared; the set's every shot into [shots] when asked
    private fun shoot(theme: Theme.Custom, board: Board, mode: String, golden: Boolean) {
        if (!golden && shots == null) return
        // the 注音 keyboards' 简繁 hint not said
        prefs.set(org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        KeyboardHarness(theme, keyboards = board.keyboards).use { h ->
            h.start(board.inputType)
            if (board.name == "symbols") {
                h.tap(h.key("符"))
                h.idle()
            }
            val name = "${theme.name}_${board.name}_$mode"
            // a picture not made ahead (a floating keyboard's blur) is asked for at the first draw and comes in
            // from the decode thread, the view told after: drawn once, then shot as the next frame shows it
            h.inputView.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(maxOf(1, h.inputView.width), maxOf(1, h.inputView.height), android.graphics.Bitmap.Config.ARGB_8888)))
            h.idle()
            if (golden) h.capture("ThemeLookScreenshotTest/$name", h.inputView)
            shots?.let { save(h, h.inputView, File(it, "$name.png")) }
            // ⌫ held: its own pressed colour, not its face darkened
            if (golden && theme.name.startsWith("each_key") && board.name == "full") {
                val backspace = KeyboardHarness.all(h.inputView).filterIsInstance<KeyView>().first { it.themeKey == "backspace" && h.shown(it) }
                backspace.isPressed = true
                h.capture("ThemeLookScreenshotTest/${name}_backspace_down", h.inputView)
                backspace.isPressed = false
            }
        }
    }

    private fun save(h: KeyboardHarness, v: View, f: File) {
        h.relayout()
        f.parentFile?.mkdirs()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun everyTheme(mode: String, golden: (ThemeLookSamples.Sample, Boolean, Board) -> Boolean) {
        for (s in looks.samples) for (dark in listOf(false, true)) {
            val t = looks.theme(s, dark)
            for (b in boards) shoot(t, b, mode, golden(s, dark, b))
        }
    }

    @Test
    @Uat("ST-52")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun everyThemeOnEveryKeyboard() = everyTheme("port") { s, _, b ->
        // each sample on the 26 keys light and dark; the one of each key's image on every keyboard light
        b.name == "full" || s.name == "each_key"
    }

    @Test
    @Uat("ST-52")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun oneHandedAndFloatingShowTheirOwnPartOfThePicture() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        everyTheme("one_handed") { s, dark, b -> s.name == "glass" && !dark && b.name == "full" }
        ThemeManager.prefs.oneHanded.setValue(OneHanded.Off)
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        everyTheme("floating") { s, dark, b -> s.name == "glass" && !dark && b.name == "full" }
    }

    @Test
    @Uat("ST-52")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun splitTurned() {
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Always)
        everyTheme("split_land") { s, dark, b -> (s.name == "glass" || s.name == "cutout") && !dark && b.name == "full" }
    }
}
