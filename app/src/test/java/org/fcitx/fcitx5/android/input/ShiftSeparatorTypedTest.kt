/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.HostConfig
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * KB-106, KB-107: the full keyboard's Shift is 分词 while pinyin or 双拼 is typed, through a real engine (its data
 * built here, PinyinDataBuilder): what the separator splits. ShiftSeparatorCanvasTest has the scenes and a theme's own qwerty.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ShiftSeparatorTypedTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("先", -2f, 0f)
        .unigram("西安", -3f, 0f)
        .entry("先", intArrayOf(Syllables.id("xian")))
        .entry("西安", intArrayOf(Syllables.id("xi"), Syllables.id("an")))
        .build()
        .toByteArray()

    // [shuangpin]: the 双拼 scheme the engine reads keys by (EngineSettings), as the app's setting would tell it
    // every event's argument the engine was given: a key's is its code
    private val args = mutableListOf<Int>()

    private fun side(home: File, shuangpin: String? = null) = object : KotlinHost.EngineSide {
        val engines = Engines(
            blobs = { path -> if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path) },
            userDir = null,
            warmUps = Executor(Runnable::run),
        )
        val base = EnginesHost(engines)

        // the engine's answers told as EngineBridge.follow tells them (the bridge needs the app's data): whether the input
        // method has anything typed, what the keyboard's Shift follows
        override val host: EngineHost = object : EngineHost by base {
            override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot? =
                base.event(im, event, arg, learning, payload).also { args += arg }?.also { s ->
                    EngineState.syllablesState.value = EngineState.SyllableState(im, s.preedit.isNotEmpty(), s.syllables, s.syllablesId)
                }

            override fun settings(settings: EngineSettings) {
                base.settings(shuangpin?.let { settings.copy(shuangpin = it) } ?: settings)
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
        EngineState.syllablesState.value = EngineState.SyllableState()
        EngineState.shuangpinScheme = schemeWas
    }

    private val schemeWas = EngineState.shuangpinScheme

    private fun until(what: String, state: () -> String = ::state, done: () -> Boolean) =
        waitUntil(what, state, step = { h.idle(); h.relayout() }, done = done)

    private fun state() = "preedit [${fcitx.inputPanelCached.preedit}] field [${h.field.text}] syllables ${EngineState.syllables.value} im ${fcitx.inputMethodEntryCached.uniqueName} keys ${h.texts()}"

    private fun shownText(text: String): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is TextView && it.text.toString() == text && h.shown(it) }

    private fun start(shuangpin: String? = null) {
        home = File(appContext.filesDir, "shift-separator").apply { deleteRecursively() }
        fcitx = startedHost(side(home, shuangpin))
        h = keyboardOn(fcitx)
        h.start()
        until("pinyin's letters") { fcitx.inputMethodEntryCached.uniqueName == InputMethodCatalog.PINYIN && "q" in h.texts() }
    }

    private fun pick(text: String) {
        var v = shownText(text) ?: error("no candidate $text: ${state()}")
        while (!v.isClickable) v = v.parent as View
        h.tap(v)
    }

    // KB-106: pinyin typed, Shift is 分词 (its label and reading); xi 分词 an is xi'an, not xian (先 first), whose 西安 is
    // committed; nothing typed, Shift again
    @Test
    fun shiftSplitsXianWhilePinyinIsTyped() {
        start()
        val shift = android.graphics.Rect(h.describedKey("Shift").bounds)
        "xi".forEach { h.tap(h.key(it.toString())) }
        until("xi typed, 分词 in Shift's place") { fcitx.inputPanelCached.preedit.toString() == "xi" && h.keys().any { it.label == "分词" } }
        val separator = h.key("分词")
        assertEquals("Syllable separator", separator.description)
        assertEquals(shift, separator.bounds)
        assertNull(h.keys().firstOrNull { it.description == "Shift" })
        h.tap(separator)
        "an".forEach { h.tap(h.key(it.toString())) }
        until("xi'an") { fcitx.inputPanelCached.preedit.toString() == "xi'an" }
        pick("西安")
        until("西安 committed, Shift again") { h.field.text.toString() == "西安" && h.keys().any { it.description == "Shift" } }
        assertNull(h.keys().firstOrNull { it.label == "分词" })
        // Shift as it was: lowercase letters, a tap upper case once
        assertEquals("q", h.keyNamed("q").label)
        h.tap(h.describedKey("Shift"))
        until("Shift once") { h.keyNamed("q").label == "Q" }
    }

    // KB-106: 自然码 on the full keyboard (双拼's default): x 分词 aj (an's keys), x'an, is 西安 (xi abbreviated), the
    // separator the engine's key; committed, Shift again
    @Test
    fun shiftSplitsOnZiranmasFullKeyboard() {
        EngineState.shuangpinScheme = ShuangpinScheme.ZIRANMA
        start(shuangpin = EngineSettings.ZIRANMA)
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN))
            activateIme(InputMethodCatalog.SHUANGPIN)
        }
        until("双拼 active") { fcitx.inputMethodEntryCached.uniqueName == InputMethodCatalog.SHUANGPIN && h.keys().any { it.description == "Shift" } }
        h.tap(h.key("x"))
        until("x typed, 分词 in Shift's place") { fcitx.inputPanelCached.preedit.isNotEmpty() && h.keys().any { it.label == "分词" } }
        args.clear()
        h.tap(h.key("分词"))
        until("the separator the engine's") { '\''.code in args }
        "aj".forEach { h.tap(h.key(it.toString())) }
        until("西安 offered") { shownText("西安") != null }
        pick("西安")
        until("西安 committed, Shift again") { h.field.text.toString() == "西安" && h.keys().any { it.description == "Shift" } }
    }
}
