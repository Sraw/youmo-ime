/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.core.putPunctuationTable
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.until
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
import java.util.concurrent.atomic.AtomicBoolean

/** 连按, 1's punctuation and English's letters on the nine keys, as the keyboard reads them on the clock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class NineKeysTapUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var h: KeyboardHarness? = null

    private fun harness(inputType: Int = InputType.TYPE_CLASS_TEXT) = KeyboardHarness(
        im = KeyboardHarness.PINYIN,
        keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS, english = ImKeyboard.NINE_KEYS),
    ).also {
        h = it
        it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
        it.api.enabledIme = it.api.availableIme
    }.start(inputType)

    private fun KeyboardHarness.chinese() = apply { chinesePunctuation() }

    @After
    fun close() {
        h?.close()
        EngineState.syllablesState.value = EngineState.SyllableState()
        EngineState.englishTypingState.value = false
    }

    private fun KeyboardHarness.english() {
        emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
    }

    private fun KeyboardHarness.presses(keys: String, gap: Long) {
        keys.forEachIndexed { i, k ->
            if (i > 0) advance(gap)
            if (k == '⇧') tap(describedKey("Shift")) else tap(nineKey(k.toString()))
        }
        advance(PAUSE)
    }

    private fun KeyboardHarness.sentKeys() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
        .map { Regex("key=(.+?),").find(it)?.groupValues?.get(1) ?: Regex("sym=(\\d+)").find(it)!!.groupValues[1] }

    private fun nine(digits: String) = digits.map { (UNICODE + Keyboard.pinyinNineKey(it)).toString() }

    private fun letterAgain(c: Char) = (UNICODE + Keyboard.letterKey(c, again = true)).toString()

    private val KeyboardHarness.text get() = field.text.toString()

    private fun KeyboardHarness.clear() {
        field.setText("")
        idle()
        api.calls.clear()
    }

    // what 1 types goes in as typed, a bracket paired; no shipped table puts one on 1. A multi-tap run's later taps
    // replace what the first typed and are never paired, so a run starting with an opening bracket would leave its
    // closer behind
    @Test
    fun onesMarkGoesInAsTypedABracketPaired() {
        val h = harness()
        h.chinesePunctuation(marks = ChinesePunctuation.map + ("," to "「"))
        h.tap(h.nineKey("1"))
        assertEquals("「」", h.text)
        assertEquals(1, h.field.selectionStart)
    }

    @Test
    @Uat("T9-19")
    fun withMultiTapOnOnesPunctuationCyclesAndAQuickSecondDigitIsItsLetter() {
        prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
        val h = harness().chinese()
        h.presses("11", QUICK)
        assertEquals("。", h.text)
        h.clear()
        h.presses("44", SLOW)
        assertEquals(nine("44"), h.sentKeys())
        h.clear()
        h.presses("44", QUICK)
        assertEquals(nine("4") + letterAgain('h'), h.sentKeys())
        h.clear()
        h.presses("7", 0)
        h.presses("44", QUICK)
        h.tap(h.keyWithId(R.id.button_backspace)!!)
        assertEquals(nine("74") + listOf(letterAgain('h'), FcitxKeyMapping.FcitxKey_BackSpace.toString()), h.sentKeys())
    }

    // fcitx's 「字母或数字后用半角标点」 on: after a digit 1's run is half-width, its second tap taking back the , the
    // first committed
    @Test
    @Uat("T9-19b")
    fun afterADigitWithHalfWidthOnOnesSecondTapReplacesTheCommaTyped() {
        prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
        val home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        File(home, "config/" + ModuleConfigs.PUNCTUATION).apply { parentFile!!.mkdirs() }.writeText("HalfWidthPuncAfterLetterOrNumber=True\n")
        val fcitx = startedHost(ScriptedEngine(home))
        try {
            runBlocking { fcitx.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "，", ""), PunctuationMapEntry(".", "。", ""))) }
            val h = KeyboardHarness(host = fcitx, keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)).also { this.h = it }
            h.service.postFcitxJob { activate(1, "org.example.app") }
            h.start()
            fun hostDone() {
                val done = AtomicBoolean()
                h.service.postFcitxJob { done.set(true) }
                until("the host's queue done", step = { h.idle() }) { done.get() }
                h.idle()
            }
            hostDone()
            val three = h.nineKey("3")
            h.swipe(three, 0f, -three.bounds.height().toFloat())
            until("3 typed", step = { h.idle() }) { h.text == "3" }
            hostDone()
            assertEquals(',', h.nineKey("1").label.first())
            h.advance(PAUSE)
            h.tap(h.nineKey("1"))
            until(", typed", step = { h.idle() }) { h.text == "3," }
            // the engine's answer to the , (no digit: full width again) before the second tap, as on a slow machine; the run
            // still types half width
            hostDone()
            assertEquals('，', h.nineKey("1").label.first())
            h.advance(QUICK)
            h.tap(h.nineKey("1"))
            h.advance(PAUSE)
            until("the second mark typed", step = { h.idle() }) { h.text.length >= 2 && h.text != "3," }
            assertEquals("3.", h.text)
        } finally {
            h?.close()
            h = null
            fcitx.stop()
            home.deleteRecursively()
            PunctuationManager.forget()
            PunctuationStartKeys.forget(appContext)
        }
    }

    @Test
    @Uat("T9-24")
    fun multiTapOffByDefaultOneIsACommaEachPress() {
        assertEquals(T9MultiTap.Off, AppPrefs.getInstance().keyboard.t9MultiTap.getValue())
        val h = harness().chinese()
        h.presses("11", FAST)
        assertEquals("，，", h.text)
        h.clear()
        h.presses("44", FAST)
        assertEquals(nine("44"), h.sentKeys())
    }

    @Test
    @Uat("T9-32")
    fun englishsOneWithNothingTypedCyclesItsMarks() {
        val h = harness()
        h.english()
        h.presses("11", FAST)
        assertEquals(".", h.text)
        assertTrue(h.sentKeys().toString(), h.sentKeys().isEmpty())
    }

    @Test
    @Uat("T9-33")
    fun englishByMultiTapCommitsALetterAtATime() {
        prefs.set(AppPrefs.getInstance().keyboard.t9EnglishTyping, T9EnglishTyping.MultiTap)
        val h = harness()
        h.english()
        for (run in listOf("44", "33", "555", "555", "666")) h.presses(run, FASTER)
        assertEquals("hello", h.text)
        assertTrue(h.sentKeys().toString(), h.sentKeys().isEmpty())
    }

    @Test
    @Uat("T9-44")
    fun aFieldAskingNoSuggestionsTypesEnglishByMultiTap() {
        val h = harness(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        h.english()
        h.presses("44", FASTER)
        assertEquals("h", h.text)
        assertTrue(h.sentKeys().toString(), h.sentKeys().isEmpty())
    }

    @Test
    @Uat("T9-49")
    fun shiftEndsARunTheNextPressANewCapital() {
        val h = harness(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        h.english()
        h.presses("4⇧4", FAST)
        assertEquals("gG", h.text)
    }

    private fun KeyboardHarness.textView(text: String): TextView =
        KeyboardHarness.all(inputView).filterIsInstance<TextView>().last { shown(it) && it.text.toString() == text }

    private fun KeyboardHarness.longPressTo(key: ShownKey, label: String) {
        val f = finger(key, at(key))
        advance(700)
        f.move(at(key))
        fun centre(text: String) = popupChoices().last { it.text == text }.cell.let { it.exactCenterX() to it.exactCenterY() }
        val from = centre("4")
        val to = centre(label)
        val step = org.fcitx.fcitx5.android.input.popup.PopupFocus.STEP / service.resources.displayMetrics.density
        val p = at(key, (to.first - from.first) * step, (to.second - from.second) * step)
        f.move(p)
        f.up(p)
    }

    @Test
    @Uat("T9-36")
    fun anEnglishWordIsEndedByEnterTheColumnZeroOrAHeldDigit() {
        val h = harness()
        h.english()
        fun typing() {
            h.api.calls.clear()
            h.presses("4663", FAST)
            EngineState.englishTypingState.value = true
            h.idle()
        }
        typing()
        assertEquals(listOf("4663").flatMap { d -> d.map { (UNICODE + Keyboard.nineKey(it, upper = false)).toString() } }, h.sentKeys())
        h.tap(h.keyWithId(R.id.button_return)!!)
        assertEquals(FcitxKeyMapping.FcitxKey_Return.toString(), h.sentKeys().last())
        typing()
        h.tap(h.textView(","))
        assertEquals(",", h.sentKeys().last())
        typing()
        h.tap(h.key("0"))
        assertEquals("0", h.sentKeys().last())
        typing()
        h.longPressTo(h.nineKey("4"), "4")
        // the keypad's 4: the engine picks the fourth word with it, nothing ended first
        assertTrue(h.api.calls.toString(), "reset()" !in h.api.calls)
        assertEquals(FcitxKeyMapping.FcitxKey_KP_4.toString(), h.sentKeys().last())
    }

    private companion object {
        const val FASTER = 120L
        const val FAST = 150L
        const val QUICK = 300L
        const val SLOW = 1_200L
        const val PAUSE = 1_000L
    }
}
