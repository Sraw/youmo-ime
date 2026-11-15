/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The full keyboard's 分词 in Shift's place while pinyin or 双拼 is typed (KB-106, KB-107), as the scene draws it: the
 * scenes it takes, what a press of it sends, and a theme's own qwerty without it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class ShiftSeparatorCanvasTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun harness(): KeyboardHarness = KeyboardHarness(im = KeyboardHarness.PINYIN).start()

    private fun KeyboardHarness.canvas(): KeyboardCanvasView? = KeyboardHarness.boardCanvas(inputView)

    private fun KeyboardHarness.textKeyboard(): TextKeyboard = KeyboardHarness.all(inputView).filterIsInstance<TextKeyboard>().first()

    // KB-106: pinyin typed, 分词 in Shift's place: one scene made as typing starts, none after (the kept two reused, as
    // the nine keys' 1); another input method's typing none of pinyin's
    @Test
    fun pinyinTypedSwapsShiftKeepingTheScenes() = harness().use { h ->
        val c = h.canvas()!!
        val board = h.textKeyboard()
        val im = KeyboardHarness.PINYIN.uniqueName
        try {
            val before = c.scene!!
            val made = board.scenesMade
            EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true)
            h.idle()
            val typing = c.scene!!
            assertNotNull(typing.keys.firstOrNull { it.id == "apostrophe" })
            assertNull(typing.keys.firstOrNull { it.id == "shift" })
            assertEquals(made + 1, board.scenesMade)
            EngineState.syllablesState.value = EngineState.SyllableState(im, composing = false)
            h.idle()
            assertSame(before, c.scene)
            EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true)
            h.idle()
            assertSame(typing, c.scene)
            assertEquals(made + 1, board.scenesMade)
            EngineState.syllablesState.value = EngineState.SyllableState("engine-shuangpin", composing = true)
            h.idle()
            assertSame(before, c.scene)
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    // the separator's keys sent since [from] (the 14 keys' `'`, Shift not held with it)
    private fun KeyboardHarness.separatorsSent(from: Int) =
        api.calls.drop(from).filter { it.startsWith("sendKey") && ("char=',") in it || it.startsWith("sendKey") && "key='," in it }

    // KB-106: 分词 types `'`, neither using up Shift once nor turning it off: typing done, Shift once as it was
    @Test
    fun theSeparatorKeepsShiftAsItWas() = harness().use { h ->
        val im = KeyboardHarness.PINYIN.uniqueName
        try {
            h.tap(h.describedKey("Shift"))
            h.idle()
            assertEquals("Q", h.keyNamed("q").label)
            EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true)
            h.idle()
            val from = h.api.calls.size
            h.tap(h.keyNamed("apostrophe"))
            h.idle()
            val sent = h.separatorsSent(from)
            assertTrue(h.api.calls.drop(from).toString(), sent.isNotEmpty())
            assertTrue(sent.toString(), sent.all { "states=${KeyStates.Virtual.states}," in it })
            EngineState.syllablesState.value = EngineState.SyllableState(im)
            h.idle()
            assertEquals("Q", h.keyNamed("q").label)
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    // a finger on [k] as it is now, its place kept as the scene changes under it (a ShownKey is read anew each step)
    private fun KeyboardHarness.fingerKept(k: ShownKey): KeyboardHarness.Finger {
        val r = Rect(k.bounds)
        return Finger { intArrayOf(r.left, r.top) }.also { it.send(MotionEvent.ACTION_DOWN, at(k)) }
    }

    // KB-107: a theme's or user's own qwerty with no 分词 key: its Shift stays Shift while pinyin is typed, drawn
    // and read as Shift, a tap Shift once, a long press caps lock; no `'` sent
    @Test
    fun aThemesOwnShiftStaysShiftWhilePinyinIsTyped() {
        val app = RuntimeEnvironment.getApplication()
        val builtIn = BuiltInLayouts(app)
        val own = builtIn.text("qwerty")!!.lines()
            .filterNot { "id=\"apostrophe\"" in it || "shortcut=\"separator\"" in it }
            .joinToString("\n").replace("when=\"!table !composing\"", "when=\"!table\"")
        val real = SceneLayouts.source
        SceneLayouts.source = {
            object : LayoutSource {
                override val key = "own-qwerty"
                override fun text(id: String) = if (id == "qwerty") own else builtIn.text(id)
            }
        }
        val im = KeyboardHarness.PINYIN.uniqueName
        try {
            harness().use { h ->
                EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true)
                h.idle()
                assertNull(h.canvas()!!.scene!!.keys.firstOrNull { it.id == "apostrophe" })
                val from = h.api.calls.size
                h.tap(h.describedKey("Shift"))
                h.idle()
                assertEquals("Q", h.keyNamed("q").label)
                h.tap(h.describedKey("Shift"))
                h.idle()
                assertEquals("q", h.keyNamed("q").label)
                h.longPress(h.describedKey("Shift"))
                h.idle()
                assertEquals("Q", h.keyNamed("q").label)
                assertTrue(h.api.calls.drop(from).toString(), h.separatorsSent(from).isEmpty())
            }
        } finally {
            SceneLayouts.source = real
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    // KB-107: a theme's own `'` key (its m sending it here) is no 分词: Shift once is used up on it, with Shift sent, as in 0.6.0
    @Test
    fun aThemesPlainApostropheKeyUsesUpShiftOnce() {
        val app = RuntimeEnvironment.getApplication()
        val builtIn = BuiltInLayouts(app)
        val own = builtIn.text("qwerty")!!.lines()
            .joinToString("\n") { if ("<key id=\"m\"" in it) "    <key id=\"m\" label=\"'\" send=\"'\"/>" else it }
        val real = SceneLayouts.source
        SceneLayouts.source = {
            object : LayoutSource {
                override val key = "own-quote"
                override fun text(id: String) = if (id == "qwerty") own else builtIn.text(id)
            }
        }
        try {
            harness().use { h ->
                h.tap(h.describedKey("Shift"))
                h.idle()
                assertEquals("Q", h.keyNamed("q").label)
                val from = h.api.calls.size
                h.tap(h.keyNamed("m"))
                h.idle()
                val sent = h.separatorsSent(from)
                assertTrue(h.api.calls.drop(from).toString(), sent.isNotEmpty())
                assertTrue(sent.toString(), sent.all { "states=${KeyStates(KeyState.Virtual, KeyState.Shift).states}," in it })
                assertEquals("q", h.keyNamed("q").label)
            }
        } finally {
            SceneLayouts.source = real
        }
    }

    // KB-107: Shift pressed as nothing was typed, lifted once 分词 is in its place (the first letter's answer in
    // between): 分词's `'`, Shift left off
    @Test
    fun shiftDownAsTypingBeginsUpIsTheSeparator() = harness().use { h ->
        val im = KeyboardHarness.PINYIN.uniqueName
        try {
            val shift = h.describedKey("Shift")
            val upAt = h.at(shift)
            val finger = h.fingerKept(shift)
            EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true)
            h.idle()
            // drawn, as a frame comes before the finger is lifted
            assertNotNull(h.canvas()!!.scene!!.keys.firstOrNull { it.id == "apostrophe" })
            val from = h.api.calls.size
            finger.up(upAt)
            h.idle()
            assertTrue(h.api.calls.drop(from).toString(), h.separatorsSent(from).isNotEmpty())
            EngineState.syllablesState.value = EngineState.SyllableState(im)
            h.idle()
            assertEquals("q", h.keyNamed("q").label)
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    // KB-107: 分词 pressed while typing, lifted once typing has ended (committed meanwhile): its `'` as ever, Shift
    // once kept, not used up on it
    @Test
    fun theSeparatorLiftedAfterTypingEndedKeepsShiftOnce() = harness().use { h ->
        val im = KeyboardHarness.PINYIN.uniqueName
        try {
            h.tap(h.describedKey("Shift"))
            h.idle()
            EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true)
            h.idle()
            val separator = h.keyNamed("apostrophe")
            val upAt = h.at(separator)
            val finger = h.fingerKept(separator)
            EngineState.syllablesState.value = EngineState.SyllableState(im)
            h.idle()
            h.canvas()!!.scene
            val from = h.api.calls.size
            finger.up(upAt)
            h.idle()
            val sent = h.separatorsSent(from)
            assertTrue(h.api.calls.drop(from).toString(), sent.isNotEmpty())
            assertTrue(sent.toString(), sent.all { "states=${KeyStates.Virtual.states}," in it })
            assertEquals("Q", h.keyNamed("q").label)
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    // KB-106: a field with an action (Send): Return's label and 分词 change in frames of their own as typing begins
    // and ends; the scenes made bounded, every round after the first made of kept ones alone
    @Test
    fun aFieldsActionAndTheSeparatorReuseTheKeptScenes() = KeyboardHarness(im = KeyboardHarness.PINYIN).start(imeOptions = EditorInfo.IME_ACTION_SEND).use { h ->
        val c = h.canvas()!!
        val board = h.textKeyboard()
        val im = KeyboardHarness.PINYIN.uniqueName
        val send = ReturnKeyAppearance(R.drawable.ic_baseline_send_24, R.string.a11y_key_action_send)
        fun frame(step: () -> Unit): Scene {
            step()
            h.idle()
            return c.scene!!
        }
        fun round(): List<Scene> = listOf(
            frame { board.onReturnDrawableUpdate(ReturnKeyAppearance.Composing) },
            frame { EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true) },
            frame { EngineState.syllablesState.value = EngineState.SyllableState(im) },
            frame { board.onReturnDrawableUpdate(send) },
        )
        try {
            frame { board.onReturnDrawableUpdate(send) }
            // a frame of its own first, so start-up's frames settle: without it, run with the class, the first round's
            // first frame was a scene other than the one its state keeps after (where from, not found; the count the same)
            frame { EngineState.syllablesState.value = EngineState.SyllableState(im) }
            val made = board.scenesMade
            round()
            // the two new states, Return's 确定 look without and with 分词
            assertEquals(made + 2, board.scenesMade)
            val again = board.scenesMade
            val second = round()
            val third = round()
            assertEquals(again, board.scenesMade)
            assertEquals(second.map(System::identityHashCode), third.map(System::identityHashCode))
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }
}
