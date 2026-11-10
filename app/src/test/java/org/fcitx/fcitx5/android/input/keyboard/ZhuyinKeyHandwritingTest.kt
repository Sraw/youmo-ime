/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.Looper
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * 键盘手写 on the 注音 keyboards (大千 and 注音九键), as the docs promise it: a stroke drawn over the symbol
 * keys is read as the input method's handwriting (offerInk onKeys), as on the letter, nine and grouped keys.
 * The keyboard's ink once had no place on these two, every touch going straight to the keys.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ZhuyinKeyHandwritingTest {

    @get:Rule
    val prefs = PrefsRule()

    private var open: KeyboardHarness? = null

    private val shared = HandwritingRecognition.shared
    private val reader = shared.reader.also {
        shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>) = InkReading(listOf("人"), doubleArrayOf(-1.0))
        }
    }

    @After
    fun close() {
        shared.reader = reader
        open?.close()
    }

    // the keyboard [board] shown with 键盘手写 chosen, a stroke drawn down from the key [face]: read as handwriting
    private fun writesOn(board: PinyinKeyboard, face: String) {
        val p = AppPrefs.getInstance().keyboard
        prefs.set(p.keyDrawing, KeyDrawing.Handwriting)
        prefs.set(p.pinyinKeyboard, board)
        val h = KeyboardHarness().also { open = it }.start()
        assertTrue(KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)?.keyInkForTest != null)
        h.api.calls.clear()
        val b = h.box(h.key(face))
        h.drag(h.key(face), b.exactCenterX(), b.exactCenterY() + b.height())
        waitUntil("read on $board", { h.api.calls }, 5_000, step = { shadowOf(Looper.getMainLooper()).idle() }) {
            h.api.calls.any { it.startsWith("offerInk(") && ", keys," in it }
        }
        h.close()
        open = null
    }

    @Test
    fun theDachenKeysAreWrittenOn() = writesOn(PinyinKeyboard.ZhuyinFull, "ㄅ")

    @Test
    fun theZhuyinNineKeysAreWrittenOn() = writesOn(PinyinKeyboard.ZhuyinNine, "ㄅㄉㄚ")
}
