/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keys the scene draws take their touches themselves (060-render-p2 S1, [SceneTouch]): each
 * gesture done with the canvas on and off, the same done to the text, the engine told the same, and
 * with it on no key view touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SceneTouchTest {

    @get:Rule
    val prefs = PrefsRule()

    private val delay get() = AppPrefs.getInstance().keyboard.longPressDelay.getValue().toLong()

    /**
     * [gesture] on the keyboard with the canvas off, then on: what it came to, the same both times.
     * [routed]: off, the views take the touches as the keyboard hands them (vivo's workaround, as the
     * canvas did before S1), not as the system splits them among the views.
     */
    private fun <T> both(im: InputMethodEntry = KeyboardHarness.PINYIN, routed: Boolean = false, gesture: (KeyboardHarness, Boolean) -> T): T {
        val results = listOf(false, true).map { on ->
            prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
            prefs.set(AppPrefs.getInstance().advanced.vivoKeypressWorkaround, routed && !on)
            KeyboardHarness(im = im).start().use { h ->
                assertEquals(on, h.canvas() != null)
                val touched = mutableListOf<String>()
                if (on) h.untouchedViews(touched)
                h.field.setText("hello world")
                h.field.setSelection(5)
                h.idle()
                h.api.calls.clear()
                gesture(h, on).also { assertEquals("key views touched", emptyList<String>(), touched) }
            }
        }
        assertEquals(results[0], results[1])
        return results[1]
    }

    private fun KeyboardHarness.canvas(): KeyboardCanvasView? =
        KeyboardHarness.all(inputView).filterIsInstance<KeyboardCanvasView>().firstOrNull()

    private fun KeyboardHarness.textKeyboard(): TextKeyboard = KeyboardHarness.all(inputView).filterIsInstance<TextKeyboard>().first()

    private fun KeyboardHarness.views(name: String): List<KeyView> =
        KeyboardHarness.all(textKeyboard()).filterIsInstance<KeyView>().filter { it.themeKey == name }.toList()

    private fun KeyboardHarness.keyView(name: String): KeyView = views(name).first()

    // each of the keyboard's key views records a touch reaching it (BaseKeyboard hands its touch listener the event first)
    @SuppressLint("ClickableViewAccessibility")
    private fun KeyboardHarness.untouchedViews(touched: MutableList<String>) {
        KeyboardHarness.all(textKeyboard()).filterIsInstance<KeyView>().forEach { v ->
            v.setOnTouchListener { _, e -> touched += "${v.themeKey} ${e.actionMasked}"; false }
        }
    }

    private fun KeyboardHarness.typed() = api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }

    // fingers at [at] (the window's), as the window passes them on
    private class Fingers(private val h: KeyboardHarness, private val at: List<Pair<Float, Float>>) {
        private val down = SystemClock.uptimeMillis()
        private val props = Array(at.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        private val coords = Array(at.size) { MotionEvent.PointerCoords().apply { x = at[it].first; y = at[it].second } }

        fun send(action: Int, n: Int, index: Int = 0) {
            val a = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), a, n, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            ((h.inputView.parent as? ViewGroup) ?: h.inputView).dispatchTouchEvent(e)
            e.recycle()
            h.idle()
        }
    }

    private fun KeyboardHarness.middle(v: View) = box(v).let { it.exactCenterX() to it.exactCenterY() }

    // a quick tap where a finger does it (not performClick, which no touch makes)
    private fun KeyboardHarness.touchTap(v: View) {
        val f = finger(v, at(v))
        after(16)
        f.up(at(v))
    }

    // off, as the keyboard routes them: as the system splits them among the views Robolectric's split
    // event moves q's finger (q swiped, 1 typed), which no device does
    @Test
    fun twoFingersOnTwoKeysEachTypesItsOwn() {
        val typed = both(routed = true) { h, on ->
            val f = Fingers(h, listOf(h.middle(h.keyView("q")), h.middle(h.keyView("p"))))
            f.send(MotionEvent.ACTION_DOWN, 1)
            f.send(MotionEvent.ACTION_POINTER_DOWN, 2, 1)
            if (on) assertTrue(h.canvas()!!.shownPressed("q") && h.canvas()!!.shownPressed("p"))
            f.send(MotionEvent.ACTION_POINTER_UP, 2, 1)
            if (on) assertFalse(h.canvas()!!.shownPressed("p"))
            f.send(MotionEvent.ACTION_UP, 1)
            if (on) assertFalse(h.canvas()!!.shownPressed("q"))
            h.typed()
        }
        // p lifted first, typed first
        assertEquals(typed.toString(), 2, typed.size)
        assertTrue(typed.toString(), "p" in typed[0] && "q" in typed[1])
    }

    @Test
    fun aLongPressShowsItsChoicesAndTypesThePicked() {
        // e's choices (3, é, …), the finger slid onto 3
        val (labels, typed) = both { h, _ -> h.longPress(h.keyView("e"), pick = "3") to h.typed() }
        assertTrue(labels.toString(), labels.size > 1)
        assertTrue(typed.toString(), typed.isNotEmpty())
    }

    // the finger's place on the popup read from the key's cell's corner, as from its view's: A's cell
    // starts after the row's blank end, which is A's to touch but no part of where its popup is
    @Test
    fun aPopupsKeyPickedWhereTheFingerIs() {
        val picked = both { h, _ ->
            val a = h.keyView("a")
            val labels = h.longPress(a)
            assertTrue(labels.toString(), labels.size > 2)
            labels.drop(1).associateWith { label ->
                h.api.calls.clear()
                h.longPress(a, pick = label)
                h.typed()
            }
        }
        // the harness's slide lands on the popup's rows as laid out, not always on the label it names: what
        // each typed is compared, the canvas on against off (both), not with the label
        assertTrue(picked.toString(), picked.values.all { it.size == 1 })
    }

    // the key's touch read in its cell, as in its view: a finger slid from A's middle into the row's blank
    // end before it has left A (its long press called off), though the blank end is A's to start a touch in
    @Test
    fun aFingerSlidOffTheKeysCellHasLeftIt() {
        val shown = both { h, _ ->
            val a = h.keyView("a")
            val indent = h.box(a).left - h.box(h.textKeyboard()).left
            val slop = ViewConfiguration.get(a.context).scaledTouchSlop
            assertTrue("$indent", indent > 4)
            val f = h.finger(a, h.at(a))
            h.after(16)
            f.move(KeyboardHarness.Touch(-slop - 2f, a.height / 2f))
            h.after(delay + 100L)
            h.popupChoices().size.also { f.up(KeyboardHarness.Touch(-slop - 2f, a.height / 2f)) }
        }
        // the press's bubble at most, none of its long press's keys (12 on A)
        assertTrue("$shown", shown <= 1)
    }

    @Test
    fun backspaceHeldRepeats() {
        val typed = both { h, _ ->
            val b = h.keyView("backspace")
            val f = h.finger(b, h.at(b))
            h.after(delay + 10 * 50L)
            f.up(h.at(b))
            h.typed()
        }
        assertTrue(typed.toString(), typed.size > 5)
    }

    @Test
    fun shiftTappedTwiceLocks() {
        both { h, on ->
            val shift = h.keyView("shift")
            h.touchTap(shift)
            h.touchTap(shift)
            assertTrue(h.textKeyboard().capsLocked)
            if (on) assertEquals("Q", h.canvas()!!.keyAt("q")?.label)
            h.textKeyboard().capsLocked
        }
    }

    @Test
    fun spaceSwipedMovesTheCursor() {
        // the cursor's keys sent to the engine (FakeHostApi moves no cursor)
        val typed = both { h, _ ->
            val space = h.keyView("space")
            h.swipe(space, -h.box(space).width() / 2f, 0f)
            h.typed()
        }
        assertTrue(typed.toString(), typed.size > 1 && typed.distinct().size == 1)
    }

    @Test
    fun backspaceSwipedUpClearsDownRestores() {
        // what is cleared is the engine's and the field's (Clear): the same commands, the text back as it was
        val (cleared, restored) = both { h, _ ->
            val b = h.keyView("backspace")
            val height = h.box(b).height().toFloat()
            h.swipe(b, 0f, -height)
            val cleared = h.api.calls.toList() to h.field.text.toString()
            h.swipe(b, 0f, height)
            cleared to h.field.text.toString()
        }
        assertTrue(cleared.toString(), "clearTyped()" in cleared.first)
        // what was before the cursor cleared, what was after it kept
        assertEquals(" world", cleared.second)
        assertEquals("hello world", restored)
    }

    @Test
    fun aSplitRowsTwoGsEachTypeOnce() {
        ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Always)
        prefs.later { ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Auto) }
        val typed = both { h, on ->
            val gs = h.views("g")
            assertEquals(2, gs.size)
            gs.map { g ->
                h.api.calls.clear()
                val f = h.finger(g, h.at(g))
                if (on) {
                    val second = g === gs[1]
                    assertTrue(h.canvas()!!.shownPressed("g", if (second) 1 else 0))
                    assertFalse(h.canvas()!!.shownPressed("g", if (second) 0 else 1))
                }
                f.up(h.at(g))
                h.typed()
            }
        }
        typed.forEach { assertEquals(typed.toString(), 1, it.size) }
    }

    // a long press that does nothing buzzes for nothing (A4.13): the scene's space, wired after the
    // keyboard set its views' (updateSpaceBadges), told as they were
    @Test
    fun theSpacesLongPressBuzzesAsTheViewsDid() {
        listOf(SpaceLongPressBehavior.None to false, SpaceLongPressBehavior.Enumerate to true).forEach { (behavior, buzzes) ->
            prefs.set(AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior, behavior)
            val shown = both { h, on ->
                val space = h.keyView("space")
                if (!on) return@both space.longPressFeedbackEnabled
                val scene = checkNotNull(h.textKeyboard().let { kb -> kb.sceneKey(checkNotNull(h.canvas()?.keyAt("space"))) })
                assertEquals(behavior.name, space.longPressFeedbackEnabled, scene.keyGesture.longPressFeedbackEnabled)
                scene.keyGesture.longPressFeedbackEnabled
            }
            assertEquals(behavior.name, buzzes, shown)
        }
    }

    // a split row's second copy with no key of its own is the first's ([SceneTouch.find]): a finger on
    // each copy, each read from its own copy's cell, not from where the other finger came down
    @Test
    fun aKeysTwoCopiesEachReadFromItsOwnCell() {
        ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Always)
        prefs.later { ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Auto) }
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        KeyboardHarness(im = KeyboardHarness.PINYIN).start().use { h ->
            val kb = h.textKeyboard()
            val c = checkNotNull(h.canvas())
            val gs = h.views("g")
            assertEquals(2, gs.size)
            val g = checkNotNull(kb.sceneKey(checkNotNull(c.keyAt("g"))))
            val keys = c.scene!!.keys.mapNotNull { kb.sceneKey(it) }.filter { it.ref != KeyRef.scene("g", 1) }
            val touch = SceneTouch(c, keys) { true }
            val origin = h.box(kb)
            val at = gs.map { v -> h.box(v).let { it.exactCenterX() - origin.left to it.exactCenterY() - origin.top } }
            val pointers = at.map { (x, y) -> checkNotNull(touch.find(x, y)) }
            pointers.forEach { assertSame(g, it.key) }
            val xs = mutableListOf<Float>()
            val wired = g.gestureListener
            g.gestureListener = { k, e -> xs += e.x; wired?.invoke(k, e) ?: false }
            // the first finger's step, after the second came down: from the first copy's corner, mid-key
            val t = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, at[0].first, at[0].second, 0)
            pointers[0].send(e, MotionEvent.ACTION_DOWN, 0)
            e.recycle()
            pointers[0].cancel()
            g.gestureListener = wired
            assertEquals(gs[0].width / 2f, xs.first(), 2f)
        }
    }

    // a key the scene drawn last has not: no place for a popup over it, so none shown (not at the window's corner)
    @Test
    fun aKeyTheSceneHasNotHasNoPopupPlace() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        KeyboardHarness(im = KeyboardHarness.PINYIN).start().use { h ->
            val kb = h.textKeyboard()
            val q = checkNotNull(kb.sceneKey(checkNotNull(h.canvas()?.keyAt("q"))))
            assertNotNull(q.bounds)
            assertNull(SceneKey(KeyRef.scene("gone", 0), q.def, checkNotNull(h.canvas()), kb).bounds)
        }
    }

    @Test
    fun aGlidesPathAndLettersAsOnTheViews() {
        prefs.set(AppPrefs.getInstance().keyboard.glideTyping, true)
        val glide = both(KeyboardHarness.ENGLISH) { h, _ ->
            val keys = "hello".map { h.keyView("$it") }
            val first = h.box(keys.first())
            h.drag(keys.first(), first.exactCenterX(), first.exactCenterY(), steps = 0, lift = false)
            for (k in keys.drop(1)) h.box(k).let { h.move(it.exactCenterX(), it.exactCenterY()) }
            h.box(keys.last()).let { h.move(it.exactCenterX(), it.exactCenterY(), steps = 1, lift = true) }
            val g = h.api.glides.singleOrNull()
            assertNotNull(h.api.calls.toString(), g)
            g!!.path.toList() to ('a'..'z').map { g.layout.centre(it) }
        }
        assertTrue(glide.first.size > 4)
    }
}
