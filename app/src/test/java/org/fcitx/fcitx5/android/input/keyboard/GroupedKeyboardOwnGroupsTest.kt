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
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
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
 * A theme's layout of its own letter groups in place of pinyin's 14 keys (060-render-p3 T9b), the sample 17
 * keys (keyboard-scene's test data k17.xml, the grouped bottom row under them) read through a test
 * [LayoutSource]: each tap its group's own code with where across and down the key the finger came down,
 * lean="off" the code alone, the long press its letters' or the one its layout wrote; the engine told
 * its groups ([EngineState.ownKeys]); 双拼's keyboard refuses them and draws the built-in 14 keys.
 * Real touches, as GroupedKeyboardTapTest's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboardOwnGroupsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<KeyAction>()
    private val popups = ArrayList<PopupAction>()
    private val sourceWas = SceneLayouts.source

    // the sample, its XYZ key with a long press of its own written, the grouped bottom row under it all
    private val k17 = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("letters=\"xyz\"/>", "letters=\"xyz\">\n      <popup from=\"list\" as-is=\"true\"><item text=\"xyz\"/><item text=\"XYZ\"/></popup>\n    </key>")
        .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")

    // the letters as the engine reads the sample's groups: the order of the keys, row by row
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

                override fun text(id: String) = if (id == "k14") k17 else app.text(id)
            }
        }
    }

    @After
    fun asBuiltIn() {
        SceneLayouts.source = sourceWas
        EngineState.ownKeys = null
        NineKeyLetters.inUse(NineKeyLetters.G14)
    }

    private fun grouped(ownGroups: Boolean = true) = GroupedKeyboard(context, ThemePreset.PixelDark, ownGroups = ownGroups).apply {
        setViewTreeLifecycleOwner(Owner())
        keyActionListener = KeyActionListener { action, _ -> sent += action }
        popupActionListener = PopupActionListener { popups += it }
        laidOut(1080, 800)
    }

    private fun codeOf(group: String) = letters.codes[written.indexOf(group)]

    private fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

    private fun tap(c: Char, x: Float, y: Float) = KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(c, x, y)), KeyStates.Virtual)

    // the keyboard's own touch at ([x], [y]) of [key]'s cell, each a part of its width and height; held [heldMillis] before up
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
        // the grouped bottom row under them
        assertTrue(keyboard.shownKeys().any { it.id == "space" })
        assertEquals(written, EngineState.ownKeys)
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
        // what a temporary mode reads of them, the keyboard in use the one told (EngineBridge.syncOwnKeys, before each key): the letter nearest each
        NineKeyLetters.inUse(NineKeyLetters.custom(EngineState.ownKeys!!))
        val typed = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f).map { (x, y) ->
            keyboard.touch(pqrs, x, y)
            Keyboard.typedLetter((sent.single() as KeyAction.SymAction).sym.sym - UNICODE).also { sent.clear() }
        }
        assertEquals(listOf('p', 'q', 'r', 's'), typed)
        // a key of one row: across as ever, down too (the engine's middle row for it)
        val bn = keyboard.shownKey("BN")
        keyboard.touch(bn, 0.75f, 0.5f)
        assertEquals(listOf<KeyAction>(tap(codeOf("bn"), 0.75f, 0.5f)), sent)
    }

    @Test
    fun leanOffOneLetterAndAClickNoTouchMadeSendTheCodeAlone() {
        val keyboard = grouped()
        keyboard.touch(keyboard.shownKey("ABC"), 0.9f, 0.5f)
        keyboard.touch(keyboard.shownKey("M"), 0.9f, 0.5f)
        keyboard.shownKey("XYZ").click()
        assertEquals(listOf<KeyAction>(code(codeOf("abc")), code(codeOf("m")), code(codeOf("xyz"))), sent)
        // a letter on two keys: each key its own group's code (a on AS and on ABC)
        keyboard.touch(keyboard.shownKey("AS"), 0.5f, 0.5f)
        assertEquals(codeOf("as"), Keyboard.tappedCode((sent.last() as KeyAction.SymAction).sym.sym - UNICODE))
    }

    @Test
    fun theLongPressIsItsLettersOrTheOneItsLayoutWrote() {
        val keyboard = grouped()
        val sentinel = KeyDef.Popup.Keyboard.Explicit(arrayOf("?"))
        // no popup written: its letters, made as it opens; PQRS swipes no corner, so none among them
        keyboard.onPopupAction(PopupAction.ShowKeyboardAction(keyboard.shownKey("PQRS").ref(), sentinel, Rect()))
        val letters = (popups.single() as PopupAction.ShowKeyboardAction).keyboard as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("P", "Q", "R", "S", "p", "q", "r", "s"), letters.items.take(8))
        assertEquals(8, letters.symbolsFrom)
        assertEquals(4, letters.inRow)
        assertTrue(letters.items.drop(8).toString(), letters.items.drop(8).none { t -> t.any { it.isLetterOrDigit() } })
        popups.clear()
        // its layout's own: kept as it is
        keyboard.onPopupAction(PopupAction.ShowKeyboardAction(keyboard.shownKey("XYZ").ref(), sentinel, Rect()))
        assertSame(sentinel, (popups.single() as PopupAction.ShowKeyboardAction).keyboard)
        popups.clear()
        // held down: what the layout wrote opens
        keyboard.touch(keyboard.shownKey("XYZ"), 0.5f, 0.5f, heldMillis = 1_500)
        val opened = popups.filterIsInstance<PopupAction.ShowKeyboardAction>().single().keyboard as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("xyz", "XYZ"), opened.items.toList())
    }

    // 双拼's 14 keys type the built-in groups alone: the sample refused, logged, the app's 14 keys drawn and typed
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
            assertNull(EngineState.ownKeys)
            val bn = keyboard.shownKey("BN")
            keyboard.touch(bn, 0.75f, 0.5f)
            assertEquals(listOf<KeyAction>(KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(g14Code('b'), 0.75f)), KeyStates.Virtual)), sent)
            assertTrue(logged.toString(), logged.single().contains("pinyin's alone"))
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }
}
