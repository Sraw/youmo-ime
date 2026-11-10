/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard's half of glide typing's cases (uat/06 PY-50, uat/07 SP-09): the finger's path drawn
 * while it glides, then the glide handed to the engine once on lifting. What the engine reads of it is
 * PinyinUatTest.pinyinGlide and ShuangpinUatTest.everyKeyboardGlides (:lib:ime-eval).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class GlideUatTest {

    private val glides = AppPrefs.getInstance().keyboard.keyDrawing
    // the harness open, closed after each test (the tests name theirs h)
    private var open: KeyboardHarness? = null

    @After
    fun close() {
        open?.close()
        glides.setValue(glides.defaultValue)
    }

    private fun harness(im: InputMethodEntry, keyboards: ImKeyboards.Chosen? = null): KeyboardHarness {
        glides.setValue(KeyDrawing.Glide)
        return KeyboardHarness(im = im, keyboards = keyboards).also { open = it }.start()
    }

    /** A finger down on the first of [keys], across the others' middles, shot as [shot] before it lifts on the last. */
    private fun KeyboardHarness.glide(keys: List<ShownKey>, shot: String) {
        val first = box(keys.first())
        drag(keys.first(), first.exactCenterX(), first.exactCenterY(), steps = 0, lift = false)
        for (k in keys.drop(1)) box(k).let { move(it.exactCenterX(), it.exactCenterY()) }
        capture(shot)
        val last = box(keys.last())
        move(last.exactCenterX(), last.exactCenterY(), steps = 1, lift = true)
        assertEquals(api.calls.toString(), 1, api.calls.count { it.startsWith("offerGlide") })
    }

    @Test
    @Uat("PY-50")
    fun pinyinsGlideIsDrawnAsTheFingerGoes() {
        val h = harness(KeyboardHarness.PINYIN)
        h.glide("nihao".map { h.key("$it") }, "GlideUatTest/pinyin_nihao")
    }

    @Test
    @Uat("SP-09")
    fun shuangpinWubiAndTheNineKeysGlidesAreDrawn() {
        val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        harness(shuangpin).let { h -> h.glide("nihk".map { h.key("$it") }, "GlideUatTest/shuangpin_nihk") }
        open?.close()
        val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)
        harness(wubi).let { h -> h.glide("wqiy".map { h.key("$it") }, "GlideUatTest/wubi_wqiy") }
        open?.close()
        harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS).let { h -> h.glide("6426".map { h.nineKey("$it") }, "GlideUatTest/t9_6426") }
    }
}
