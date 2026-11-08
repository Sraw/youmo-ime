/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ViewShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Each keyboard moved to the scene (060-render-p2 S4 step 7), the views' keys and the scene's side by
 * side in nine looks: for the eye (build/outputs/canvas-compare/<board>-<look>.png), and close enough
 * pixel for pixel (what differs: specs/app.md, the canvas; 060-render-p2 §5.3). A keyboard moved
 * adds its row to [boards]; the number pads' rows are `number-<pad>`, each shown by a field of its own
 * ([FIELDS]).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class BoardCanvasCompareTest(private val board: String, private val pinyinKeyboard: PinyinKeyboard) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun boards() = listOf(
            arrayOf<Any>("qwerty", PinyinKeyboard.Full),
            arrayOf<Any>("k14", PinyinKeyboard.Keys14),
            arrayOf<Any>("k18", PinyinKeyboard.Keys18),
            arrayOf<Any>("zhuyin", PinyinKeyboard.ZhuyinFull),
            arrayOf<Any>("zhuyin-nine", PinyinKeyboard.ZhuyinNine),
        ) + FIELDS.keys.map { arrayOf<Any>(it, PinyinKeyboard.Full) }

        // each number pad by the field that shows it (NumberPad.of): the full pad's a plain number field's
        private val FIELDS = mapOf(
            "number-full" to InputType.TYPE_CLASS_NUMBER,
            "number-digits" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "number-signed" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED),
            "number-decimal" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            "number-signed-decimal" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            "number-phone" to InputType.TYPE_CLASS_PHONE,
            "number-datetime" to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL),
        )

        // the same phone on its side: 851 dp wide, wider than the 600 split starts from
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"

        // a channel off by this little is anti-aliasing; this share of the pixels so, at most
        private const val CHANNEL_SLACK = 24
        private const val MOST_DIFFERING = 0.015
    }

    @get:Rule
    val prefs = PrefsRule()

    // [set] the case's preferences, giving back what puts each back: the next case starts from the defaults
    private class Case(val name: String, val theme: Theme, val qualifiers: String? = null, val set: () -> List<() -> Unit> = { emptyList() })

    private fun <T : Any> ManagedPreference<T>.during(value: T): () -> Unit {
        val was = getValue()
        setValue(value)
        return { setValue(was) }
    }

    @Test
    fun sideBySide() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, pinyinKeyboard)
        // 注音's 简繁 hint not said: no notice over the keys
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        val keyboard = AppPrefs.getInstance().keyboard
        val cases = listOf(
            Case("pixel-light", ThemePreset.PixelLight),
            Case("material-light", ThemePreset.MaterialLight),
            Case("nord-light", ThemePreset.NordLight),
            Case("pixel-dark", ThemePreset.PixelDark),
            Case("material-dark", ThemePreset.MaterialDark),
            Case("amoled-black", ThemePreset.AMOLEDBlack),
            Case("compact", ThemePreset.PixelLight) {
                val compact = KeyboardHeightLevels.ALL[KeyboardHeightLevels.COMPACT]
                listOf(keyboard.keyboardHeightPercent.during(compact.portrait), keyboard.keyboardHeightPercentLandscape.during(compact.landscape))
            },
            Case("landscape", ThemePreset.PixelLight, LANDSCAPE) { listOf(ThemeManager.prefs.splitKeyboard.during(SplitKeyboardMode.Off)) },
            Case("split", ThemePreset.PixelLight, LANDSCAPE) { listOf(ThemeManager.prefs.splitKeyboard.during(SplitKeyboardMode.Always)) },
        )
        val shares = cases.associate { it.name to differingShare(it) }
        val told = shares.entries.joinToString("\n") { (name, share) -> "$board $name ${"%.2f".format(share * 100)}%" }
        File("build/outputs/canvas-compare/$board-shares.txt").apply { parentFile!!.mkdirs() }.writeText(told + "\n")
        assertTrue(told, shares.values.all { it < MOST_DIFFERING })
    }

    // the share of the pixels that differ between the views' keys and the scene's: a channel off by more
    // than anti-aliasing's (the letters a pixel over where a row's height is odd: specs/app.md, the
    // canvas); a key missing or misplaced is several percent
    private fun differingShare(case: Case): Double {
        prefs.set(ThemeManager.prefs.floatingKeyboard, false)
        val undo = case.set()
        case.qualifiers?.let(RuntimeEnvironment::setQualifiers)
        val shots = try {
            listOf(false, true).map { on ->
                prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
                KeyboardHarness(case.theme).start(FIELDS[board] ?: InputType.TYPE_CLASS_TEXT).use { h ->
                    // past the keyboard's sliding in
                    h.after(1000)
                    h.relayout()
                    val v = h.keyboard
                    // the scene's side is the scene's: its canvas over the keyboard, every key one of its (review S4a #2)
                    val canvases = KeyboardHarness.all(v).filterIsInstance<KeyboardCanvasView>().toList()
                    val keys = KeyboardHarness.keysOf(v)
                    if (on) {
                        assertEquals(case.name, 1, canvases.size)
                        assertTrue(case.name, canvases.single().isShown)
                        assertTrue(case.name, keys.isNotEmpty() && keys.all { it is SceneShownKey })
                    } else {
                        assertTrue(case.name, canvases.isEmpty() && keys.isNotEmpty() && keys.all { it is ViewShownKey })
                    }
                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
                }
            }
        } finally {
            if (case.qualifiers != null) RuntimeEnvironment.setQualifiers(KeyboardCanvasViewTest.PORTRAIT)
            undo.asReversed().forEach { it() }
        }
        // the shots let go of at once: 9 cases × 2 a board, kept, ran the full suite's heap out (ThemeMakerLayoutTest's OOM)
        try {
            save(case.name, shots)
            val (old, new) = shots
            assertEquals(case.name, old.width to old.height, new.width to new.height)
            return differing(old, new, CHANNEL_SLACK).toDouble() / (old.width * old.height)
        } finally {
            shots.forEach(Bitmap::recycle)
        }
    }

    private fun save(name: String, shots: List<Bitmap>) {
        val title = 64
        val gap = 16
        val w = shots.sumOf { it.width } + gap
        val out = Bitmap.createBitmap(w, shots.maxOf { it.height } + title, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = Color.BLACK }
        var x = 0f
        for ((i, s) in shots.withIndex()) {
            c.drawText(if (i == 0) "旧" else "新", x + s.width / 2f, 48f, paint)
            c.drawBitmap(s, x, title.toFloat(), null)
            x += s.width + gap
        }
        val file = File("build/outputs/canvas-compare/$board-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        assertTrue(file.length() > 0)
    }

    private fun differing(a: Bitmap, b: Bitmap, slack: Int): Int {
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return pa.indices.count { i ->
            val x = pa[i]
            val y = pb[i]
            (0 until 4).any { ch -> kotlin.math.abs((x ushr (ch * 8) and 0xff) - (y ushr (ch * 8) and 0xff)) > slack }
        }
    }
}
