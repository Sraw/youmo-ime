/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.theme.BarBase
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyLabel
import org.fcitx.fcitx5.android.input.theme.KeyOverride
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.KindStyle
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.Palette
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.ThemeLook
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
 * The images are made here: a picture of bands and rings (where a key's part of it is shows), a
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

    // a file in the temporary folder of [w]×[h], drawn by [draw]; its path
    private fun png(name: String, w: Int, h: Int, draw: Canvas.() -> Unit): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).draw()
        val f = File(dir.root, name)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.path
    }

    private val picture by lazy {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bands(w: Int, h: Int): Canvas.() -> Unit = {
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFF4511E.toInt(), 0xFFFDD835.toInt()), null, Shader.TileMode.CLAMP)
            drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w / 60f
            paint.color = Color.WHITE
            for (i in 1..6) drawCircle(w * 0.3f, h * 0.6f, i * w / 10f, paint)
            paint.style = Paint.Style.FILL
        }
        val port = png("pic-port.png", 1080, 1000, bands(1080, 1000))
        val land = png("pic-land.png", 2340, 900, bands(2340, 900))
        Picture(port, Crop(port, null, 0), Crop(land, null, 0), 85, 0)
    }

    // a key of a thick frame, the corners 24 px (8 dp at 3 px a dp), the middle [fill]
    private fun frame(name: String, edge: Int, fill: Int): NineSlice {
        val path = png(name, 96, 96) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = edge
            drawRoundRect(RectF(0f, 0f, 96f, 96f), 24f, 24f, p)
            p.color = fill
            drawRoundRect(RectF(10f, 10f, 86f, 86f), 16f, 16f, p)
        }
        return NineSlice(path, Insets(24, 24, 24, 24), 3)
    }

    private class Sample(val name: String, val look: (Palette, Boolean) -> ThemeLook)

    private fun palette(dark: Boolean) = (if (dark) ThemePreset.PixelDark else ThemePreset.PixelLight).look.palette

    private fun glassPalette(dark: Boolean) = (if (dark) ThemePreset.TransparentDark else ThemePreset.TransparentLight).look.palette

    private val samples = listOf(
        // 纯色分块: the defaults, blocks, a bigger radius and a border
        Sample("color") { p, _ ->
            LookDefaults.look(p).let { it.copy(keys = it.keys.copy(radiusDp = 10, borderDp = 1, press = PressEffect.Ripple)) }
        },
        // 主题图 + 毛玻璃: the picture under it all, the letters it shown through blurred under their colour
        Sample("glass") { p, _ ->
            LookDefaults.look(p, picture).let {
                it.copy(
                    base = BoardBase.Picture,
                    bar = BarBase.Picture(8, 80),
                    keys = it.keys.copy(
                        letter = KindStyle(KeyFill.Picture(10, 100), KeyPressed.Darken),
                        function = KindStyle(KeyFill.Picture(16, 80), KeyPressed.Darken),
                        space = KindStyle(KeyFill.Picture(10, 100), KeyPressed.Darken),
                    ),
                )
            }
        },
        // 镂空: a coloured base, the keys the picture as it is
        Sample("cutout") { p, _ ->
            LookDefaults.look(p, picture).let {
                it.copy(
                    base = BoardBase.Color,
                    keys = it.keys.copy(
                        letter = KindStyle(KeyFill.Picture(0, 100), KeyPressed.Darken),
                        function = KindStyle(KeyFill.Picture(0, 70), KeyPressed.Darken),
                        space = KindStyle(KeyFill.Picture(0, 100), KeyPressed.Darken),
                    ),
                )
            }
        },
        // 九宫格图片 + 自带字体: every kind a framed image stretched to its key, the keys' and candidates' font
        Sample("nine_slice_font") { p, dark ->
            val edge = if (dark) 0xFF26A69A.toInt() else 0xFF00796B.toInt()
            val fill = p.keyBackgroundColor
            LookDefaults.look(p).let {
                it.copy(
                    keys = it.keys.copy(
                        letter = KindStyle(KeyFill.Image(frame("frame-letter-$dark.png", edge, fill)), KeyPressed.Darken),
                        function = KindStyle(KeyFill.Image(frame("frame-fn-$dark.png", edge, p.altKeyBackgroundColor)), KeyPressed.Darken),
                        space = KindStyle(KeyFill.Image(frame("frame-space-$dark.png", edge, fill)), KeyPressed.Darken),
                    ),
                    text = it.text.copy(keyFont = File(dir.root, "font.ttf").path, candidateFont = File(dir.root, "font.ttf").path),
                )
            }
        },
        // 每个键一张图: the 26 letters each its own, ⌫ pressed its own colour, 注音's q its own
        Sample("each_key") { p, dark ->
            val letters = ('a'..'z').associate { c ->
                val hue = (c - 'a') * 360f / 26
                val color = Color.HSVToColor(floatArrayOf(hue, if (dark) 0.6f else 0.35f, if (dark) 0.45f else 1f))
                "qwerty/$c" to KeyOverride(KeyFill.Image(frame("key-$c-$dark.png", color, color)), null, KeyLabel.Show)
            }
            LookDefaults.look(p).let {
                it.copy(
                    keys = it.keys.copy(
                        overrides = letters + mapOf(
                            "*/backspace" to KeyOverride(null, KeyPressed.Color(0xFFE53935.toInt()), KeyLabel.Show),
                            "zhuyin/q" to KeyOverride(KeyFill.Image(frame("zhuyin-q-$dark.png", 0xFFFF7043.toInt(), 0xFFFFCCBC.toInt())), null, KeyLabel.Show),
                        ),
                    ),
                )
            }
        },
    )

    private fun theme(s: Sample, dark: Boolean): Theme.Custom {
        val p = if (s.name == "glass" || s.name == "cutout") glassPalette(dark) else palette(dark)
        return Theme.Custom("${s.name}_${if (dark) "dark" else "light"}", dark, s.look(p, dark))
    }

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

    private fun everyTheme(mode: String, golden: (Sample, Boolean, Board) -> Boolean) {
        for (s in samples) for (dark in listOf(false, true)) {
            val t = theme(s, dark)
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
