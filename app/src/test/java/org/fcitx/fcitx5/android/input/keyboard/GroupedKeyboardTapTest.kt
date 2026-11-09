/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.MotionEvent
import androidx.core.view.allViews
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A 14-key tap sends where across the key the finger came down (design k14-tap-position §1.4,
 * review M1, M2): driven by real touches, as a finger's, never by performClick, which no touch
 * makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboardTapTest {

    private val context = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<KeyAction>()

    // the keys' timers run in the view tree's lifecycle
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun grouped() = GroupedKeyboard(context, ThemePreset.PixelDark).apply {
        setViewTreeLifecycleOwner(Owner())
        keyActionListener = KeyActionListener { action, _ -> sent += action }
        laidOut(1080, 800)
    }

    private var keyboard = grouped()

    // the keyboard made again, nothing sent yet
    private fun drawn(check: () -> Unit) {
        keyboard = grouped()
        sent.clear()
        check()
    }

    private fun key(face: String) = keyboard.shownKey(face)

    // a test's layouts under [key]
    private fun source(key: String, read: (String) -> String?) = object : LayoutSource {
        override val key = key

        override fun text(id: String) = read(id)
    }

    private fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

    private fun tap(c: Char, at: Float) = KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(c, at)), KeyStates.Virtual)

    // a touch on [key]: down at [x] across it ([down] its height down it), then each move by (dx, dy) from there, then
    // up where the last left it; to the keyboard (whose touches go to the scene's keys) at the key's place
    private fun touch(key: ShownKey, x: Float, vararg moves: Pair<Float, Float>, down: Float = 0.5f) {
        key as SceneShownKey
        // the bounds the canvas's own (no window here): its place in the keyboard added
        val (view, left, top) = Triple(keyboard, key.canvas.left + key.bounds.left, key.canvas.top + key.bounds.top)
        val y = top + key.height * down
        var time = 1_000L
        fun send(action: Int, ex: Float, ey: Float) {
            val event = MotionEvent.obtain(1_000L, time, action, ex, ey, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            time += 10
        }
        send(MotionEvent.ACTION_DOWN, left + x, y)
        var last = left + x to y
        for ((dx, dy) in moves) {
            last = left + x + dx to y + dy
            send(MotionEvent.ACTION_MOVE, last.first, last.second)
        }
        send(MotionEvent.ACTION_UP, last.first, last.second)
    }

    @Test
    fun aTapSendsItsCodeWithWhereTheFingerCameDown() = drawn {
        val bn = key("BN")
        assertTrue(bn.width > 0)
        touch(bn, bn.width * 0.75f)
        assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.75f)), sent)
        sent.clear()
        touch(bn, bn.width * 0.25f)
        assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.25f)), sent)
    }

    // 060-render-p3 T9b: the 14 keys' taps send what they always did, wherever down the key the finger came, the
    // same touch the same character (where down goes with a layout's own groups' codes alone)
    @Test
    fun theFourteenKeysTapsAreTheSameWhereverDownTheKey() = drawn {
        for (face in listOf("QW", "BN", "AS", "CV")) {
            val key = key(face)
            val first = key.label.lowercase()[0]
            for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
                for (down in listOf(0.1f, 0.5f, 0.9f)) touch(key, key.width * x, down = down)
                assertEquals("$face $x", List(3) { tap(g14Code(first), x) }, sent)
                sent.clear()
            }
        }
    }

    @Test
    fun aFingerThatMovedALittleStillTapsWithItsPlace() = drawn {
        // a real finger moves a few pixels between down and up: no swipe, the place kept
        val bn = key("BN")
        touch(bn, bn.width * 0.75f, 2f to 1f)
        assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.75f)), sent)
    }

    @Test
    fun aSwipeTheOtherWayIsATapWithNoPlace() = drawn {
        // up, past the threshold, where the corner is down: KeySwipe's Tap, the code alone
        val bn = key("BN")
        touch(bn, bn.width * 0.75f, 0f to -bn.height.toFloat() / 2 + 1, 0f to -bn.height.toFloat() / 2 + 1)
        assertTrue(sent.toString(), sent.isNotEmpty() && sent.all { it == code(g14Code('b')) })
    }

    // 060-render-v2 §15 按落点加权: whether a tap leans is its layout's (lean=), whichever draws the keys
    @Test
    fun aGroupItsLayoutLeansNotSendsItsCodeAlone() {
        val builtIn = SceneLayouts.source
        SceneLayouts.source = { ctx ->
            val own = BuiltInLayouts(ctx)
            source("lean-off") { id -> own.text(id)?.let { if (id == "k14") it.replace("letters=\"bn\"", "letters=\"bn\" lean=\"off\"") else it } }
        }
        try {
            drawn {
                val bn = key("BN")
                touch(bn, bn.width * 0.75f)
                assertEquals(listOf<KeyAction>(code(g14Code('b'))), sent)
                sent.clear()
                // the others as they were
                val cv = key("CV")
                touch(cv, cv.width * 0.75f)
                assertEquals(listOf<KeyAction>(tap(g14Code('c'), 0.75f)), sent)
            }
        } finally {
            SceneLayouts.source = builtIn
        }
    }

    // 060-render-v2 §15, 060-render-p3 T9b: groups a layout writes that are not the keyboard's (G14), on a keyboard
    // that types none of its own (made here as 双拼's is: ownGroups off), make no codes of their own: logged, and the
    // keyboard's groups typed, its codes, record and leaning as ever (GroupedKeyboardOwnGroupsTest: pinyin's own)
    @Test
    fun aLayoutsOtherGroupsAreLoggedAndTheKeyboardsKept() {
        val builtIn = SceneLayouts.source
        SceneLayouts.source = { ctx ->
            val own = BuiltInLayouts(ctx)
            source("nb") { id -> own.text(id)?.let { if (id == "k14") it.replace("letters=\"bn\"", "letters=\"nb\"") else it } }
        }
        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == android.util.Log.ERROR) logged += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            keyboard = grouped()
            val bn = key("BN")
            assertTrue(bn is SceneShownKey)
            touch(bn, bn.width * 0.25f)
            assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.25f)), sent)
            assertTrue(logged.toString(), logged.single().contains(NineKeyLetters.G14.tag()))
        } finally {
            timber.log.Timber.uproot(tree)
            SceneLayouts.source = builtIn
        }
    }

    @Test
    fun aKeyOfOneLetterAndAClickNoTouchMadeSendTheCode() = drawn {
        val m = key("M")
        touch(m, m.width * 0.9f)
        assertEquals(listOf<KeyAction>(code(g14Code('m'))), sent)
        sent.clear()
        // TalkBack's double tap clicks with no touch
        key("BN").click()
        assertEquals(listOf<KeyAction>(code(g14Code('b'))), sent)
    }

    // a node a scene made anew does other than it did gets a key of its own: the old key's gestures cancelled,
    // its long press pending never fires (review S4a #9)
    @Test
    fun aKeyANodeNoLongerDoesAsHasItsGesturesCancelled() {
        run {
            keyboard = grouped()
            val canvas = keyboard.allViews.filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView>().single()
            var fired = 0
            val replaced = mutableListOf<org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKey>()
            val keys = SceneKeys(keyboard) { key, _ -> key.onLongClick { fired++; true } }
            keys.byNodes(canvas, object : SceneKeyDef.Table {}, replaced::add)
            val n = canvas.scene!!.keys.first { it.id == "space" }
            val old = keys.of(n)!!
            // the same node again: the same key
            assertTrue(old === keys.of(n))
            old.touch(android.graphics.Rect(0, 0, 100, 100), MotionEvent.ACTION_DOWN, 50f, 50f, 0, android.os.SystemClock.uptimeMillis())
            val other = org.fcitx.fcitx5.android.scene.KeyNode(
                id = n.id, copy = n.copy, kind = n.kind, role = n.role, shape = n.shape, bounds = n.bounds, hit = n.hit, anchor = n.anchor,
                up = n.up, states = n.states, faces = n.faces, a11y = n.a11y, actions = n.actions + ("send" to "sym:Tab"),
            )
            val new = keys.of(other)!!
            assertTrue(new !== old)
            assertEquals(listOf(old), replaced)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            assertEquals(0, fired)
        }
    }
}
