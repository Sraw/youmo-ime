/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.inLandscape
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle

/**
 * The code row and the candidates while pinyin is half typed and the keyboard is made anew or put
 * away (uat/18, A2): a setting that makes it anew ends what was typed as leaving the field does
 * (pinyin's code dropped, English's word in the field committed); a view made anew otherwise (the
 * system's, floating and docked) shows the engine's whole state; nothing left that the next key does
 * not replace. On a real [KotlinHost] (its own thread, its own caches) over a scripted engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StuckBarUatTest {

    // what a case and the host change in the store put back, whatever fails: startedHost's firstRun, the keyboard, the
    // style (D7: this class's flake at SDK 29 never reproduced; these were what it left behind for the next test)
    @get:Rule
    val prefs = PrefsRule()

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var engine: ScriptedEngine
    private lateinit var h: KeyboardHarness

    private val keyboard get() = AppPrefs.getInstance().keyboard

    private fun start() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        engine = ScriptedEngine(home)
        fcitx = startedHost(engine)
        h = keyboardOn(fcitx)
        h.start()
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        if (::home.isInitialized) home.deleteRecursively()
        ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
    }

    private fun until(what: String, state: () -> String = { "" }, done: () -> Boolean) {
        waitUntil(what, state, step = { h.idle() }, done = done)
        h.relayout()
    }

    private fun shown(text: String): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is TextView && it.text.toString() == text && h.shown(it) }

    private fun state() = "preedit [${fcitx.inputPanelCached.preedit}] field [${h.field.text}] shown ${h.texts().take(12)}"

    // n i on the full keyboard: the code row says ni, the bar offers 你好
    private fun ni() {
        "ni".forEach { h.tap(h.key(it.toString())) }
        until("ni typed", ::state) { fcitx.inputPanelCached.preedit.toString() == "ni" && shown("你好") != null }
        assertTrue(state(), shown("ni") != null)
    }

    // what was typed dropped, not committed: the field empty, the code row and the bar too, the engine reset
    private fun nothingLeft() {
        until("ni dropped", ::state) { fcitx.inputPanelCached.preedit.isEmpty() && shown("你好") == null }
        assertEquals(state(), "", h.field.text.toString())
        assertTrue(state(), shown("ni") == null)
        assertTrue("the engine reset: ${engine.events}", EngineEvent.RESET in engine.events)
    }

    // h a o typed after: the bar follows the new keys, nothing of ni before them
    private fun haoFollows() {
        "hao".forEach { h.tap(h.key(it.toString())) }
        until("hao typed", ::state) { fcitx.inputPanelCached.preedit.toString() == "hao" && shown("你好") != null }
        assertTrue(state(), shown("hao") != null)
        assertEquals(state(), "", h.field.text.toString())
    }

    @Test
    @Uat("LC-14")
    fun aKeyboardSettingChangedMidPinyinDropsIt() {
        start()
        ni()
        engine.events.clear()
        // the setting that swaps pinyin's keyboard: the input view made anew
        prefs.set(keyboard.pinyinKeyboard, PinyinKeyboard.Keys14)
        h.idle()
        nothingLeft()
        prefs.set(keyboard.pinyinKeyboard, PinyinKeyboard.Full)
        h.idle()
        h.relayout()
        haoFollows()
    }

    @Test
    @Uat("LC-15")
    fun aThemeChangedMidPinyinDropsIt() {
        start()
        ni()
        engine.events.clear()
        ThemeManager.setNormalModeTheme(TestThemes.PixelDark)
        h.idle()
        nothingLeft()
        haoFollows()
    }

    @Test
    @Uat("LC-16")
    fun aViewMadeAnewMidPinyinShowsTheEnginesState() {
        start()
        ni()
        // the system makes the views anew (initViews) with the field still started: no setting changed
        h.service.onCreateInputView()
        h.service.onStartInputView(h.service.currentInputEditorInfo, true)
        h.idle()
        h.relayout()
        assertEquals(state(), "ni", fcitx.inputPanelCached.preedit.toString())
        assertTrue("the code row: ${state()}", shown("ni") != null)
        assertTrue("the candidates: ${state()}", shown("你好") != null)
    }

    @Test
    @Uat("LC-17")
    fun aThemeChangedMidEnglishWordCommitsIt() {
        start()
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH))
            activateIme(InputMethodCatalog.ENGLISH)
        }
        until("English", ::state) { fcitx.inputMethodEntryCached.uniqueName == InputMethodCatalog.ENGLISH }
        "he".forEach { h.tap(h.key(it.toString())) }
        // English's word is the field's composing text, not a code above the bar
        until("he composing", ::state) { BaseInputConnection.getComposingSpanStart(h.field.text) >= 0 && h.field.text.toString() == "he" }
        engine.events.clear()
        ThemeManager.setNormalModeTheme(TestThemes.PixelDark)
        h.idle()
        until("he committed", ::state) { BaseInputConnection.getComposingSpanStart(h.field.text) < 0 }
        assertEquals(state(), "he", h.field.text.toString())
        assertTrue("the engine reset: ${engine.events}", EngineEvent.RESET in engine.events)
        // the next letter a new word after it, not he's
        h.tap(h.key("x"))
        until("x composing", ::state) { h.field.text.toString() == "hex" }
        assertEquals(state(), 2, BaseInputConnection.getComposingSpanStart(h.field.text))
    }

    @Test
    @Uat("LC-18")
    fun floatingAndDockedMidPinyinKeepIt() {
        start()
        ni()
        engine.events.clear()
        for (floating in listOf(true, false)) {
            // the handle dragged off the bottom, then back: the keyboard made anew, nothing typed ended
            if (floating) prefs.setStyle(KeyboardStyle.FLOATING) else prefs.setStyleDefault()
            h.idle()
            h.relayout()
            until("ni kept ${if (floating) "floating" else "docked"}", ::state) { shown("ni") != null && shown("你好") != null }
            assertEquals(state(), "ni", fcitx.inputPanelCached.preedit.toString())
            assertEquals(state(), "", h.field.text.toString())
        }
        assertTrue("no reset: ${engine.events}", EngineEvent.RESET !in engine.events)
    }

    @Test
    @Uat("LC-03")
    fun pinyinLeftForAnotherAppAndBackLeavesNothing() {
        start()
        ni()
        // Home, the recents' other app, a notification's: the keyboard put away and the field left
        h.service.onFinishInputView(true)
        h.service.onFinishInput()
        h.idle()
        h.start()
        until("ni dropped", ::state) { fcitx.inputPanelCached.preedit.isEmpty() && shown("你好") == null }
        assertTrue(state(), shown("ni") == null)
        haoFollows()
    }

    @Test
    @Uat("LC-01")
    fun theScreenTurnedMidPinyinLeavesNothing() {
        start()
        ni()
        engine.events.clear()
        h.inLandscape(relayout = true) { nothingLeft() }
        haoFollows()
    }
}
