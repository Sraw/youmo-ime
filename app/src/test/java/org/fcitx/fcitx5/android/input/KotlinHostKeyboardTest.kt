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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole keyboard typing through a real [KotlinHost] (its own events and thread) to a scripted engine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KotlinHostKeyboardTest {

    @get:Rule
    val host = HostKeyboardRule("kotlin-host") {
        keyboard.pinyinKeyboard.let { it.setValue(it.defaultValue) }
        keyboard.shuangpinKeyboard.let { it.setValue(it.defaultValue) }
        AppPrefs.getInstance().internal.keyLists.setValue(KeyLists.HEADER)
    }
    private val h get() = host.h
    private val fcitx get() = host.fcitx
    private lateinit var engine: ScriptedEngine

    private val keyboard get() = AppPrefs.getInstance().keyboard

    // settings set first: they are read when the keyboard is made
    private fun start(im: String? = null) {
        host.up({ ScriptedEngine(it).also { engine = it } })
        h.start()
        if (im == null) return
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, im).distinct().toTypedArray())
            activateIme(im)
        }
        host.until("$im active") { fcitx.inputMethodEntryCached.uniqueName == im }
        // the keyboard follows on main once the host told it: on a loaded runner the previous one was still up
        host.until("$im's keyboard", { "${h.texts()}" }) {
            h.relayout()
            h.service.getString(R.string._not_available_) !in h.texts()
        }
    }

    private fun setList(key: String, vararg symbols: String) =
        AppPrefs.getInstance().internal.keyLists.setValue(KeyLists.of(mapOf(KeyId(key) to UserList(off = false, items = symbols.toList()))).serialize())

    private fun shownText(text: String): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is TextView && it.text.toString() == text && h.shown(it) }

    @Test
    fun pinyinTypedOnTheKeysAndACandidateTappedCommitsIt() {
        start()
        "nihao".forEach { h.tap(h.key(it.toString())) }
        // the keys' jobs run in turn on the host: all five typed before the tap
        host.until("the five letters typed", { "preedit [${fcitx.inputPanelCached.preedit}]" }) { fcitx.inputPanelCached.preedit.toString() == "nihao" }
        host.until("the candidates on the bar", { "preedit [${fcitx.inputPanelCached.preedit}] im ${fcitx.inputMethodEntryCached.uniqueName} bar ${h.texts().take(8)} engine ${engine.events}" }) {
            shownText("你好") != null
        }
        var tapped = shownText("你好")!!
        while (!tapped.isClickable) tapped = tapped.parent as View
        h.tap(tapped)
        host.until("the commit in the field") { h.field.text.toString() == "你好" }
        assertEquals("你好", h.field.text.toString())
        host.until("the bar cleared", { "bar ${h.texts().take(12)} engine ${engine.events} preedit [${host.preedit}]" }) { shownText("你") == null }
        assertTrue(fcitx.inputPanelCached.preedit.toString().isEmpty())
    }

    private fun typedAsText(text: String) {
        host.until("$text in the field") { h.field.text.toString() == text }
        assertEquals(text, h.field.text.toString())
        assertEquals("", fcitx.inputPanelCached.preedit.toString())
    }

    // ⒜ and ⒉ were once grouped key codes; the codes moved to the private use area, so as long-press characters they go in as they are
    @Test
    @Uat("ST-22")
    fun aCircledLetterLongPressedOnTheFourteenKeysIsTypedAsText() {
        keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        // set on QW itself: q's own no longer reaches it
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
