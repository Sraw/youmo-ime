/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.tinyEngineSide
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.host.EngineHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** 微软's 九键双拼 through the host to a tiny real engine: 9 then 1 is y then `;`, ying. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class T9ShuangpinIngTest {

    @get:Rule
    val host = HostKeyboardRule("t9-ing") {
        keyboard.shuangpinKeyboard.let { it.setValue(it.defaultValue) }
        EngineState.shuangpinScheme = schemeWas
    }
    private val h get() = host.h
    private val fcitx get() = host.fcitx
    private val keyboard get() = AppPrefs.getInstance().keyboard
    private val schemeWas = EngineState.shuangpinScheme

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("应", -2f, 0f)
        .unigram("一", -2.5f, 0f)
        .entry("应", intArrayOf(Syllables.id("ying")))
        .entry("一", intArrayOf(Syllables.id("yi")))
        .build()
        .toByteArray()

    private fun side(home: File) = tinyEngineSide(home, { path ->
        if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path)
    }) { engines, base ->
        object : EngineHost by base {
            override fun settings(settings: EngineSettings) {
                engines.settings = settings.copy(shuangpin = EngineSettings.MS)
            }
        }
    }

    private fun shownText(text: String): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is TextView && it.text.toString() == text && h.shown(it) }

    @Test
    fun nineThenOneTypesYingOnMicrosofts() {
        keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys)
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        host.up(::side)
        h.start()
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN))
            activateIme(InputMethodCatalog.SHUANGPIN)
        }
        host.until("双拼 active") { fcitx.inputMethodEntryCached.uniqueName == InputMethodCatalog.SHUANGPIN }
        host.until("its nine keys", { "${h.texts()}" }) {
            h.relayout()
            h.service.getString(R.string._not_available_) !in h.texts() && "WXYZ" in h.texts()
        }
        h.tap(h.nineKey("9"))
        host.until("y typed", { "preedit [${fcitx.inputPanelCached.preedit}]" }) { fcitx.inputPanelCached.preedit.isNotEmpty() }
        h.tap(h.nineKey("1"))
        host.until("应 on the bar", { "preedit [${fcitx.inputPanelCached.preedit}] bar ${h.texts().take(12)}" }) { shownText("应") != null }
        var tapped = shownText("应")!!
        while (!tapped.isClickable) tapped = tapped.parent as View
        h.tap(tapped)
        host.until("应 in the field") { h.field.text.toString() == "应" }
        assertEquals("应", h.field.text.toString())
    }
}
