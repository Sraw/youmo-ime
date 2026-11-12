/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The whole keyboard on the Kotlin host: the service, its keys and its bar as on a device, typing
 * through a real [KotlinHost] (events and answers its own, on its own thread) to a scripted engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KotlinHostKeyboardTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var engine: ScriptedEngine
    private lateinit var h: KeyboardHarness

    private val keyboard get() = AppPrefs.getInstance().keyboard

    // the host on the scripted engine and an app's field focused; with [im], that one shown (the
    // keyboard's settings set before, as they are read when it is made)
    private fun start(im: String? = null) {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        engine = ScriptedEngine(home)
        fcitx = startedHost(engine)
        h = keyboardOn(fcitx)
        h.start()
        if (im == null) return
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, im).distinct().toTypedArray())
            activateIme(im)
        }
        until("$im active") { fcitx.inputMethodEntryCached.uniqueName == im }
        // the keyboard follows on the main thread once the host has told it: on a loaded runner the
        // one made before (no input method yet, its space "(Not Available)") was still up (ST-22's QW not found)
        until("$im's keyboard", { "${h.texts()}" }) {
            h.relayout()
            h.service.getString(R.string._not_available_) !in h.texts()
        }
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        if (::home.isInitialized) home.deleteRecursively()
        keyboard.pinyinKeyboard.let { it.setValue(it.defaultValue) }
        keyboard.shuangpinKeyboard.let { it.setValue(it.defaultValue) }
        AppPrefs.getInstance().internal.keyLists.setValue(KeyLists.HEADER)
    }

    private fun setList(key: String, vararg symbols: String) =
        AppPrefs.getInstance().internal.keyLists.setValue(KeyLists.of(mapOf(KeyId(key) to UserList(off = false, items = symbols.toList()))).serialize())

    // the host answers on its thread, the bar is drawn on the main one: run both till [done]
    private fun until(what: String, state: () -> String = { "" }, done: () -> Boolean) =
        waitUntil(what, state, step = { h.idle() }, done = done)

    private fun shownText(text: String): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is TextView && it.text.toString() == text && h.shown(it) }

    @Test
    fun pinyinTypedOnTheKeysAndACandidateTappedCommitsIt() {
        start()
        "nihao".forEach { h.tap(h.key(it.toString())) }
        // the keys' jobs run one after another on the host: all five typed before the tap
        until("the five letters typed", { "preedit [${fcitx.inputPanelCached.preedit}]" }) { fcitx.inputPanelCached.preedit.toString() == "nihao" }
        until("the candidates on the bar", { "preedit [${fcitx.inputPanelCached.preedit}] im ${fcitx.inputMethodEntryCached.uniqueName} bar ${h.texts().take(8)} engine ${engine.events}" }) {
            shownText("你好") != null
        }
        var tapped = shownText("你好")!!
        while (!tapped.isClickable) tapped = tapped.parent as View
        h.tap(tapped)
        until("the commit in the field") { h.field.text.toString() == "你好" }
        assertEquals("你好", h.field.text.toString())
        until("the bar cleared", { "bar ${h.texts().take(12)} engine ${engine.events} preedit [${fcitx.inputPanelCached.preedit}]" }) { shownText("你") == null }
        assertTrue(fcitx.inputPanelCached.preedit.toString().isEmpty())
    }

    private fun typedAsText(text: String) {
        until("$text in the field") { h.field.text.toString() == text }
        assertEquals(text, h.field.text.toString())
        assertEquals("", fcitx.inputPanelCached.preedit.toString())
    }

    // ⒜ and ⒉, once the 14 keys' and 九键双拼's key codes, are text since the codes moved to the
    // private use area: set as a long-press character they go into the field as they are, never
    // read or swallowed as a grouped key's code
    @Test
    @Uat("ST-22")
    fun aCircledLetterLongPressedOnTheFourteenKeysIsTypedAsText() {
        keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        // set on QW itself: q's own no longer reaches it (060-keys-unify L3)
        setList("g14/qw", "⒜")
        start(InputMethodCatalog.PINYIN)
        h.longPress(h.key("QW"), "⒜")
        typedAsText("⒜")
    }

    @Test
    @Uat("ST-22")
    fun aParenthesisedDigitLongPressedOnShuangpinsNineKeysIsTypedAsText() {
        keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys)
        setList("t9/2", "⒉")
        start(InputMethodCatalog.SHUANGPIN)
        h.longPress(h.nineKey("2"), "⒉")
        typedAsText("⒉")
    }
}
