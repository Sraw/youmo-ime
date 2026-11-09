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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.KindStyle
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import java.io.File

/**
 * The five sample themes of theme file 3.0 (colours, a frosted picture, the picture cut out by the
 * keys, nine-slice images with a font, an image per key), light and dark: ThemeLookScreenshotTest's,
 * and OldRenderBaselineTest's look of them kept for 060. Their images are made in [dir]; a font file
 * is none (the font sample's keys are drawn in monospace by the tests' decoder, as a font read would be).
 */
class ThemeLookSamples(private val dir: File) {

    // a file in [dir] of [w]×[h], drawn by [draw]; its path
    private fun png(name: String, w: Int, h: Int, draw: Canvas.() -> Unit): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).draw()
        val f = File(dir, name)
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

    class Sample(val name: String, val look: (Palette, Boolean) -> ThemeLook)

    private fun palette(dark: Boolean) = (if (dark) ThemePreset.PixelDark else ThemePreset.PixelLight).look.palette

    private fun glassPalette(dark: Boolean) = (if (dark) ThemePreset.TransparentDark else ThemePreset.TransparentLight).look.palette

    val samples = listOf(
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
                    text = it.text.copy(keyFont = File(dir, "font.ttf").path, candidateFont = File(dir, "font.ttf").path),
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

    fun theme(s: Sample, dark: Boolean): Theme.Custom {
        val p = if (s.name == "glass" || s.name == "cutout") glassPalette(dark) else palette(dark)
        return Theme.Custom("${s.name}_${if (dark) "dark" else "light"}", dark, s.look(p, dark))
    }

    /** Every sample, light then dark. */
    fun all(): List<Theme.Custom> = samples.flatMap { s -> listOf(false, true).map { theme(s, it) } }
}
