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
import org.fcitx.fcitx5.android.core.HostConfig
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * 微软's 九键双拼 typed on the keys through the Kotlin host to a real engine (a tiny dictionary):
 * 9 then 1 is y then `;`, ying, its word on the bar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class T9ShuangpinIngTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness
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

    // the engine on [pinyin] alone, 微软's whatever the config says
    private fun side(home: File) = object : KotlinHost.EngineSide {
        val engines = Engines(
            blobs = { path -> if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path) },
            userDir = null,
            warmUps = Executor(Runnable::run),
        )
        override val host: EngineHost = object : EngineHost by EnginesHost(engines) {
            override fun settings(settings: EngineSettings) {
                engines.settings = settings.copy(shuangpin = EngineSettings.MS)
            }
        }
        override fun data() = HostConfig(File(home, "data"), writable = true)
        override fun config() = HostConfig(File(home, "config"), writable = true)
        override fun start() = Unit
        override fun close() = Unit
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        if (::home.isInitialized) home.deleteRecursively()
        keyboard.shuangpinKeyboard.let { it.setValue(it.defaultValue) }
        EngineState.shuangpinScheme = schemeWas
    }

    private fun until(what: String, state: () -> String = { "" }, done: () -> Boolean) =
        waitUntil(what, state, step = { h.idle() }, done = done)

    private fun shownText(text: String): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is TextView && it.text.toString() == text && h.shown(it) }

    @Test
    fun nineThenOneTypesYingOnMicrosofts() {
        keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys)
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        home = File(appContext.filesDir, "t9-ing").apply { deleteRecursively() }
        fcitx = startedHost(side(home))
        h = keyboardOn(fcitx)
        h.start()
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN))
            activateIme(InputMethodCatalog.SHUANGPIN)
        }
        until("双拼 active") { fcitx.inputMethodEntryCached.uniqueName == InputMethodCatalog.SHUANGPIN }
        until("its nine keys", { "${h.texts()}" }) {
            h.relayout()
            h.service.getString(R.string._not_available_) !in h.texts() && "WXYZ" in h.texts()
        }
        h.tap(h.nineKey("9"))
        until("y typed", { "preedit [${fcitx.inputPanelCached.preedit}]" }) { fcitx.inputPanelCached.preedit.isNotEmpty() }
        h.tap(h.nineKey("1"))
        until("应 on the bar", { "preedit [${fcitx.inputPanelCached.preedit}] bar ${h.texts().take(12)}" }) { shownText("应") != null }
        var tapped = shownText("应")!!
        while (!tapped.isClickable) tapped = tapped.parent as View
        h.tap(tapped)
        until("应 in the field") { h.field.text.toString() == "应" }
        assertEquals("应", h.field.text.toString())
    }
}
