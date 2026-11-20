/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * A theme's own letter groups in place of pinyin's 14 keys (k17.xml): each tap its group's code with its place,
 * lean="off" the code alone; the engine told the groups ([EngineState.ownKeys]); 双拼's refuses them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboardOwnGroupsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<KeyAction>()

    private val touches = ArrayList<Touch?>()
    private val popups = ArrayList<PopupAction>()
    private val sourceWas = SceneLayouts.source

    private val sample = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")

    private val k17 = sample
        .replace("letters=\"xyz\"/>", "letters=\"xyz\">\n      <popup from=\"list\" as-is=\"true\"><item text=\"xyz\"/><item text=\"XYZ\"/></popup>\n    </key>")

    // the letters of one key on another too: ABC (leaning) and AB C of two rows in XYZ's place
    private val twice = sample
        .replace("letters=\"abc\" lean=\"off\"", "letters=\"abc\"")
        .replace("label=\"XYZ\" letters=\"xyz\"", "label=\"AB C\" letters=\"ab c\"")
    private val writtenTwice = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m", "pq rs", "abc", "ab c")

    // null: the app's own
    private var k14Text = k17
    private var k18Text: String? = null

    private val written = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m", "pq rs", "abc", "xyz")
    private val letters = NineKeyLetters.custom(written)

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Before
    fun theSampleInPlaceOfTheFourteenKeys() {
        EngineState.ownKeys = null
        SceneLayouts.source = { ctx ->
            val app = BuiltInLayouts(ctx)
            object : LayoutSource {
                override val key = "k17-sample"

                override fun text(id: String) = when (id) {
                    "k14" -> k14Text
                    "k18" -> k18Text ?: app.text(id)
                    else -> app.text(id)
                }
            }
        }
    }

    @After
    fun asBuiltIn() {
        SceneLayouts.source = sourceWas
        EngineState.ownKeys = null
        NineKeyLetters.clearInUse()
    }

    // made, not shown: KeyboardWindow's onAttach is each test's to call
    private fun GroupedKeyboard.wired() = apply {
        setViewTreeLifecycleOwner(Owner())
        keyActionListener = KeyActionListener { action, _ ->
            touches += (action as? KeyAction.SymAction)?.touch
            sent += (action as? KeyAction.SymAction)?.copy(touch = null) ?: action
        }
        popupActionListener = PopupActionListener { popups += it }
        laidOut(1080, 800)
    }

    private fun grouped(ownGroups: Boolean = true) = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = ownGroups).wired()

    private fun pinyin18() =
        GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false, ownGroups = true).wired()

    private fun symOf(action: KeyAction) = (action as KeyAction.SymAction).sym.sym - UNICODE

    private fun codeOf(group: String) = letters.codes[written.indexOf(group)]

    private fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

    private fun tap(c: Char, x: Float, y: Float) = KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(c, x, y)), KeyStates.Virtual)

    private fun GroupedKeyboard.touch(key: ShownKey, x: Float, y: Float, heldMillis: Long = 0) {
        key as SceneShownKey
        val left = key.canvas.left + key.bounds.left
        val top = key.canvas.top + key.bounds.top
        val ex = left + key.bounds.width() * x
        val ey = top + key.bounds.height() * y
        fun send(action: Int, time: Long) {
            val event = MotionEvent.obtain(1_000L, time, action, ex, ey, 0)
            dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1_000L)
        if (heldMillis > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(heldMillis))
        send(MotionEvent.ACTION_UP, 1_000L + heldMillis + 10)
    }

    @Test
    fun theSampleIsDrawnAndItsGroupsToldTheEngine() {
        val keyboard = grouped()
        val labels = keyboard.shownKeys().map { it.label }
        assertTrue(labels.toString(), labels.containsAll(listOf("PQRS", "ABC", "XYZ", "AS", "BN")))
        assertTrue(keyboard.shownKeys().any { it.id == "space" })
        // told as shown, not as made: a preview tells nothing
        assertNull(EngineState.ownKeys)
        keyboard.onAttach()
        assertEquals(written, EngineState.ownKeys)
    }

    // a layout lacking a letter (the sample without M): its own keys drawn and told, never the app's in its place
    @Test
    fun theSampleLackingALetterIsDrawnAsItIs() {
        k14Text = k17.replace(Regex("<key id=\"r3c2\"[^>]*/>"), "<gap width=\"20\"/>")
        check(k14Text != k17)
        val grouping = GroupedKeyboard.grouping(context, NineKeyLetters.G14, own = true, log = false)
        assertEquals(written - "m", grouping.own)
        val keyboard = grouped()
        val labels = keyboard.shownKeys().map { it.label }
        assertTrue(labels.toString(), labels.containsAll(listOf("PQRS", "ABC", "BN")) && "M" !in labels)
        keyboard.onAttach()
        assertEquals(written - "m", EngineState.ownKeys)
        keyboard.touch(keyboard.shownKey("PQRS"), 0.75f, 0.25f)
        val noM = NineKeyLetters.custom(written - "m")
        assertEquals(listOf<KeyAction>(tap(noM.codes[(written - "m").indexOf("pq rs")], 0.75f, 0.25f)), sent)
    }

    @Test
    fun eachKeyboardShownTellsItsOwnGroupsAgain() {
        k18Text = twice
        val k14 = grouped()
        val k18 = pinyin18()
        val shuangpin = grouped(ownGroups = false)
        assertNull(EngineState.ownKeys)
        k14.onAttach()
        assertEquals(written, EngineState.ownKeys)
        k18.onAttach()
        assertEquals(writtenTwice, EngineState.ownKeys)
        shuangpin.onAttach()
        assertEquals(writtenTwice, EngineState.ownKeys)
        k14.onAttach()
        assertEquals(written, EngineState.ownKeys)
    }

    // in pinyin's 18 keys' place: the layout's own codes, never wired as the 18 keys'
    @Test
    fun theEighteenKeysPlaceSendsTheLayoutsOwnCodes() {
        k18Text = k17
        val keyboard = pinyin18()
        keyboard.onAttach()
        assertEquals(written, EngineState.ownKeys)
        keyboard.touch(keyboard.shownKey("PQRS"), 0.75f, 0.25f)
        keyboard.touch(keyboard.shownKey("M"), 0.5f, 0.5f)
        keyboard.touch(keyboard.shownKey("ABC"), 0.9f, 0.5f)
        assertEquals(listOf<KeyAction>(tap(codeOf("pq rs"), 0.75f, 0.25f), tap(codeOf("m"), 0.5f, 0.5f), code(codeOf("abc"))), sent)
    }

    @Test
    fun twoKeysOfTheSameLettersEachSendTheirOwnCode() {
        k14Text = twice
        val keyboard = grouped()
        val own = NineKeyLetters.custom(writtenTwice)
        keyboard.touch(keyboard.shownKey("ABC"), 0.9f, 0.1f)
        keyboard.touch(keyboard.shownKey("AB C"), 0.9f, 0.1f)
        assertEquals(listOf<KeyAction>(tap(own.codes[15], 0.9f, 0.1f), tap(own.codes[16], 0.9f, 0.1f)), sent)
        // as EngineBridge.syncOwnKeys has it before each key
        NineKeyLetters.inUse(own)
        assertEquals(listOf('c', 'b'), sent.map { Keyboard.typedLetter(symOf(it)) })
    }

    @Test
    fun aTapSendsItsGroupsCodeWithWhereAcrossAndDownItFell() {
        val keyboard = grouped()
        val pqrs = keyboard.shownKey("PQRS")
        for ((x, y) in listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f)) {
            keyboard.touch(pqrs, x, y)
            assertEquals("$x, $y", listOf<KeyAction>(tap(codeOf("pq rs"), x, y)), sent)
            sent.clear()
        }
        NineKeyLetters.inUse(letters)
        val typed = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f).map { (x, y) ->
            keyboard.touch(pqrs, x, y)
            Keyboard.typedLetter(symOf(sent.single())).also { sent.clear() }
        }
        assertEquals(listOf('p', 'q', 'r', 's'), typed)
        val bn = keyboard.shownKey("BN")
        keyboard.touch(bn, 0.75f, 0.5f)
        assertEquals(listOf<KeyAction>(tap(codeOf("bn"), 0.75f, 0.5f)), sent)
    }

    // a one-letter key has its place where other groups lean, for a slip onto one that weighs by it
    @Test
    fun leanOffAndAClickNoTouchMadeSendTheCodeAlone() {
        val keyboard = grouped()
        keyboard.touch(keyboard.shownKey("ABC"), 0.9f, 0.5f)
        keyboard.touch(keyboard.shownKey("M"), 0.9f, 0.5f)
        keyboard.shownKey("XYZ").click()
        assertEquals(listOf<KeyAction>(code(codeOf("abc")), tap(codeOf("m"), 0.9f, 0.5f), code(codeOf("xyz"))), sent)
        keyboard.touch(keyboard.shownKey("AS"), 0.5f, 0.5f)
        assertEquals(codeOf("as"), Keyboard.tappedCode((sent.last() as KeyAction.SymAction).sym.sym - UNICODE))
    }

    @Test
    fun theLongPressIsItsLettersOrTheOneItsLayoutWrote() {
        val keyboard = grouped()
        val sentinel = KeyDef.Popup.Keyboard.Explicit(arrayOf("?"))
        // no popup written and no name: its letters' built-in list (KeyDefaults.grouped)
        keyboard.touch(keyboard.shownKey("PQRS"), 0.5f, 0.5f, heldMillis = 1_500)
        val letters = popups.filterIsInstance<PopupAction.ShowKeyboardAction>().single().keyboard as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("P", "Q", "R", "S", "p", "q", "r", "s"), letters.items.take(8))
        assertEquals(8, letters.symbolsFrom)
        assertEquals(4, letters.inRow)
        assertTrue(letters.items.drop(8).toString(), letters.items.drop(8).none { t -> t.any { it.isLetterOrDigit() } })
        popups.clear()
        keyboard.onPopupAction(PopupAction.ShowKeyboardAction(keyboard.shownKey("XYZ").ref(), sentinel, Rect()))
        assertSame(sentinel, (popups.single() as PopupAction.ShowKeyboardAction).keyboard)
        popups.clear()
        keyboard.touch(keyboard.shownKey("XYZ"), 0.5f, 0.5f, heldMillis = 1_500)
        val opened = popups.filterIsInstance<PopupAction.ShowKeyboardAction>().single().keyboard as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("xyz", "XYZ"), opened.items.toList())
    }

    // a group of no name with a digit corner: its long press's digit a keypad's, as a named group's
    @Test
    fun aGroupOfNoNamesDigitCornerIsAKeypadsInItsLongPress() {
        k14Text = k17.replace("label=\"QW\" letters=\"qw\"/>", "label=\"QW\" letters=\"qw\" swipe-up=\"1\"/>")
        val keyboard = grouped()
        keyboard.touch(keyboard.shownKey("QW"), 0.5f, 0.5f, heldMillis = 1_500)
        val opened = popups.filterIsInstance<PopupAction.ShowKeyboardAction>().single().keyboard as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("Q", "W", "1", "q", "w"), opened.items.take(5))
        val actions = opened.actions!!
        assertEquals(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + 1), KeyStates.Virtual), actions[2])
        assertEquals(KeyAction.HostKeyAction("q"), actions[3])
    }

    // 双拼's 14 keys type the built-in groups alone: the sample refused, logged, the app's drawn
    @Test
    fun shuangpinsKeyboardRefusesTheSampleAndDrawsTheBuiltInOne() {
        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == android.util.Log.ERROR) logged += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val keyboard = grouped(ownGroups = false)
            val labels = keyboard.shownKeys().map { it.label }
            assertTrue(labels.toString(), "PQRS" !in labels && "BN" in labels)
            keyboard.onAttach()
            assertNull(EngineState.ownKeys)
            val bn = keyboard.shownKey("BN")
            keyboard.touch(bn, 0.75f, 0.5f)
            assertEquals(listOf<KeyAction>(KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(g14Code('b'), 0.75f)), KeyStates.Virtual)), sent)
            assertTrue(logged.toString(), logged.single().contains("pinyin's alone"))
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    // the built-in groups on other keys, stacked: each key's letters where they are on it (NineKeyLetters.rowsOf)
    @Test
    fun theBuiltInGroupsRearrangedAndStackedReachTheKeyboardAsWritten() {
        val app = BuiltInLayouts(context).text("k14")!!
        val qw = Regex("<key [^>]*letters=\"qw\"[^>]*/>").find(app)!!.value
        val er = Regex("<key [^>]*letters=\"er\"[^>]*/>").find(app)!!.value
        k14Text = app.replace(qw, "@@").replace(er, qw.replace("letters=\"qw\"", "letters=\"q w\"")).replace("@@", er)
        val stacked = GroupedKeyboard.grouping(context, NineKeyLetters.G14, own = true, log = false)
        assertEquals(NineKeyLetters.G14, stacked.letters)
        assertNull(stacked.own)
        assertEquals(listOf("q", "w"), stacked.letters.rowsOf(NineKeyLetters.G14.key('q')))
        assertEquals(listOf("er"), stacked.letters.rowsOf(NineKeyLetters.G14.key('e')))
        // a tap on ER has QW around it stacked: a slip onto it placed down it (T9Segmenter.placeOn)
        val qwCode = NineKeyLetters.G14.key('q')
        val keyboard = grouped()
        keyboard.onAttach()
        keyboard.touch(keyboard.shownKey("QW"), 0.1f, 0.9f)
        keyboard.touch(keyboard.shownKey("QW"), 0.9f, 0.1f)
        assertEquals(listOf(Keyboard.tapKey(qwCode, 0.9f), Keyboard.tapKey(qwCode, 0.1f)), sent.map(::symOf))
        assertEquals(listOf('w', 'q'), sent.map { Keyboard.typedLetter(symOf(it)) })
        keyboard.touch(keyboard.shownKey("ER"), 0.5f, 0.5f)
        val around = touches.last()!!.near(qwCode)
        assertTrue(touches.last()!!.near.toString(), around != null && around.stacked)
        assertTrue(touches.last()!!.near.toString(), touches.last()!!.near.filter { it.char != qwCode }.none { it.stacked })
        val k18 = BuiltInLayouts(context).text("k18")!!
        val q = Regex("<key [^>]*letters=\"q\"[^>]*/>").find(k18)!!.value
        val we = Regex("<key [^>]*letters=\"we\"[^>]*/>").find(k18)!!.value
        k18Text = k18.replace(q, "@@").replace(we, q).replace("@@", we)
        val swapped = GroupedKeyboard.grouping(context, NineKeyLetters.G18, own = false, log = false)
        assertEquals(NineKeyLetters.G18, swapped.letters)
        assertEquals(listOf("we"), swapped.letters.rowsOf(NineKeyLetters.G18.key('w')))
    }

    // the 18 keys' groups on other keys are still the 18 keys: each key its group's code, no groups of its own told
    @Test
    @Uat("PY-154", "SP-51")
    fun theEighteenKeysRearrangedAreDrawnAsWrittenAndTypedAsTheEighteenKeys() {
        val app = BuiltInLayouts(context).text("k18")!!
        val q = Regex("<key [^>]*letters=\"q\"[^>]*/>").find(app)!!.value
        val we = Regex("<key [^>]*letters=\"we\"[^>]*/>").find(app)!!.value
        k18Text = app.replace(q, "@@").replace(we, q).replace("@@", we)
        // 双拼's the code; pinyin's its own code (Keyboard.pinyin18Key) or, leaning, a tap with its place
        val wq = listOf(NineKeyLetters.G18.key('w'), NineKeyLetters.G18.key('q'))
        for (keyboard in listOf(GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18).wired(), pinyin18())) {
            sent.clear()
            val labels = keyboard.shownKeys().map { it.label }
            assertTrue(labels.toString(), labels.indexOf("WE") in 0 until labels.indexOf("Q"))
            keyboard.onAttach()
            assertNull(EngineState.ownKeys)
            keyboard.touch(keyboard.shownKey("WE"), 0.5f, 0.5f)
            keyboard.touch(keyboard.shownKey("Q"), 0.5f, 0.5f)
            assertEquals(wq, sent.map { symOf(it).let { c -> Keyboard.tappedCode(c) ?: Keyboard.keyedChar(c) ?: c.toChar() } })
        }
    }
}
