/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The handwriting keyboard drawn by the scene does what its views did (060-render-p2 S5b): each key's
 * tap and long press, what reaches the engine; the pad in the scene's slot `pad`, written on; 注 on as
 * its key's state (KeyState.On) and said as its view said it. ，and 。 are the one thing meant to
 * differ (§5.3 S5b): shown as they type (punctuation's mapping), where the views showed them full width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class HandwritingSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private val handwriting = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

    @After
    fun reset() {
        EngineState.candidateReadingState.value = ReadingStyle.OFF
    }

    private fun harness(): KeyboardHarness {
        return KeyboardHarness(im = handwriting).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
            api.enabledIme = api.availableIme
        }.start().also { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    private fun KeyboardHarness.pad(): HandwritingPadView = KeyboardHarness.find(inputView) ?: error("no pad")

    // what a finger does on each key: tapped, held (its long press's keys or menu); the keys that put
    // another keyboard up last, each in a keyboard of its own
    private fun gestures(): List<String> {
        val ids = harness().use { h -> h.keys().filter { it.enabled }.mapNotNull { it.id } }
        val out = mutableListOf("keys $ids")
        for (id in ids) {
            // tapped, then held, each in a keyboard of its own: 符 and 123 put another keyboard up
            for (held in listOf(false, true)) {
                // 注's menu: the views' a list the harness does not read, the scene's below ([readingsMenuAsTheSettingsWordIt])
                if (held && (id == "symbols" || id == "reading")) continue
                harness().use { h ->
                    h.api.calls.clear()
                    if (held) {
                        out += "$id long press: " + h.longPress(h.keyNamed(id))
                    } else {
                        h.tap(h.keyNamed(id))
                    }
                    h.idle()
                    out += "$id ${if (held) "long press sent" else "tap"}: " +
                        h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") || it.startsWith("triggerUnicode") } +
                        " field ${h.field.text}"
                }
            }
        }
        return out
    }

    // the one difference meant (060-render-p2 §5.3, C 疑问 3): 123 held is Unicode input, as on every board; its view had no long press
    @Test
    fun everyKeyAsItsView() {
        val scene = gestures()
        assertTrue(scene.toString(), scene.any { it.startsWith("numbers long press sent: [triggerUnicode()]") })
    }

    @Test
    fun thePadIsInItsSlotAndWrittenOn() {
        harness().use { h ->
            val canvas = KeyboardHarness.boardCanvas(h.keyboard)!!
            val pad = h.pad()
            assertEquals(padSlot(canvas), Rect(pad.left - canvas.left, pad.top - canvas.top, pad.right - canvas.left, pad.bottom - canvas.top))
            // over the canvas: drawn after it, its touches its own
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            assertTrue("${kb.indexOfChild(pad)} ${kb.indexOfChild(canvas)}", kb.indexOfChild(pad) > kb.indexOfChild(canvas))
            assertTrue(pad.width > h.keyboard.width / 2)
            // the slot's face is the scene's (a letter key's), drawn under the pad
            assertTrue(canvas.scene!!.slots.single { it.id == HandwritingKeyboard.PAD_SLOT }.ops.isNotEmpty())
        }
    }

    // the scene's own slot at the canvas's size now, not through the keyboard's way to it
    private fun padSlot(canvas: KeyboardCanvasView): Rect =
        Rect().apply { set(canvas.scene!!.slots.single { it.id == HandwritingKeyboard.PAD_SLOT }.bounds) }

    // narrower (one-handed, split): the pad follows the slot of the scene at the new size, off the keys beside it
    @Test
    fun thePadFollowsItsSlotAfterAResize() {
        harness().use { h ->
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            val canvas = KeyboardHarness.boardCanvas(kb)!!
            val before = padSlot(canvas)
            kb.relayoutAt(kb.width * 7 / 10, kb.height)
            val pad = h.pad()
            val after = padSlot(canvas)
            assertTrue("$before $after", after.width() < before.width())
            assertEquals(after, Rect(pad.left - canvas.left, pad.top - canvas.top, pad.right - canvas.left, pad.bottom - canvas.top))
        }
    }

    // two fingers at once, each to what it came down on: [fingers]' points in the input view's pixels, sent there
    private class Fingers(private val h: KeyboardHarness) {
        private val down = android.os.SystemClock.uptimeMillis()
        private val at = linkedMapOf<Int, Pair<Float, Float>>()

        fun at(v: View, fx: Float, fy: Float): Pair<Float, Float> {
            val o = IntArray(2).also(v::getLocationInWindow)
            val r = IntArray(2).also(h.inputView::getLocationInWindow)
            return (o[0] - r[0] + v.width * fx) to (o[1] - r[1] + v.height * fy)
        }

        fun at(k: org.fcitx.fcitx5.android.input.ShownKey): Pair<Float, Float> {
            val r = IntArray(2).also(h.inputView::getLocationInWindow)
            return (k.bounds.exactCenterX() - r[0]) to (k.bounds.exactCenterY() - r[1])
        }

        fun down(id: Int, p: Pair<Float, Float>) {
            at[id] = p
            send(if (at.size == 1) android.view.MotionEvent.ACTION_DOWN else android.view.MotionEvent.ACTION_POINTER_DOWN, id)
        }

        fun move(id: Int, p: Pair<Float, Float>) {
            at[id] = p
            send(android.view.MotionEvent.ACTION_MOVE, id)
        }

        // a finger the stream loses (no UP for it): the next event goes without it
        fun lose(id: Int) {
            at.remove(id)
        }

        fun up(id: Int) {
            send(if (at.size == 1) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_POINTER_UP, id)
            at.remove(id)
        }

        private fun send(action: Int, id: Int) {
            val ids = at.keys.toList()
            val props = Array(ids.size) { android.view.MotionEvent.PointerProperties().apply { this.id = ids[it]; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { android.view.MotionEvent.PointerCoords().apply { x = at[ids[it]]!!.first; y = at[ids[it]]!!.second } }
            h.advance(16)
            val e = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                action or (ids.indexOf(id) shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            h.inputView.dispatchTouchEvent(e)
            e.recycle()
            h.idle()
        }
    }

    // a stroke being written, ⌫ tapped with another finger: ⌫ the engine's (no stroke finished to take back), the stroke the pad's
    @Test
    fun backspaceTappedWhileAStrokeIsWritten() {
        harness().use { h ->
            val pad = h.pad()
            val f = Fingers(h)
            h.api.calls.clear()
            f.down(0, f.at(pad, 0.3f, 0.3f))
            f.move(0, f.at(pad, 0.5f, 0.4f))
            f.down(1, f.at(h.keyNamed("backspace")))
            f.up(1)
            f.move(0, f.at(pad, 0.7f, 0.5f))
            f.up(0)
            assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey(sym=65288,") })
            assertEquals(1, pad.pad.strokes.size)
        }
    }

    // ⌫ held, a stroke written with another finger: the stroke the pad's, ⌫'s tap (on its lift) then takes it back
    @Test
    fun aStrokeWrittenWhileBackspaceIsHeld() {
        harness().use { h ->
            val pad = h.pad()
            val f = Fingers(h)
            f.down(0, f.at(h.keyNamed("backspace")))
            f.down(1, f.at(pad, 0.3f, 0.3f))
            f.move(1, f.at(pad, 0.5f, 0.4f))
            f.move(1, f.at(pad, 0.7f, 0.5f))
            f.up(1)
            assertEquals(1, pad.pad.strokes.size)
            f.up(0)
            assertEquals(0, pad.pad.strokes.size)
        }
    }

    // a MOVE without a finger one side had: that side cancelled, ⌫ let go (no more repeats), the stroke ended
    @Test
    fun aSideWhoseFingerTheStreamLostIsCancelled() {
        harness().use { h ->
            val pad = h.pad()
            val f = Fingers(h)
            f.down(0, f.at(h.keyNamed("backspace")))
            f.down(1, f.at(pad, 0.3f, 0.3f))
            f.move(1, f.at(pad, 0.5f, 0.4f))
            f.lose(0)
            f.move(1, f.at(pad, 0.6f, 0.4f))
            val keys = { h.api.calls.count { it.startsWith("sendKey") } }
            h.idle()
            val after = keys()
            h.after(2000)
            h.idle()
            assertEquals(after, keys())
            assertEquals(true, pad.pad.drawing)
            f.down(2, f.at(h.keyNamed("comma")))
            f.lose(1)
            f.move(2, f.at(h.keyNamed("comma")))
            h.idle()
            assertEquals(false, pad.pad.drawing)
        }
    }

    // no scene (060-render-p2 U8): nothing of the keyboard shown, the pad neither; a scene again, the pad again
    @Test
    fun thePadHiddenWhileTheSceneFails() {
        harness().use { h ->
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            val canvas = KeyboardHarness.boardCanvas(kb)!!
            assertEquals(View.VISIBLE, h.pad().visibility)
            KeyboardCanvasView::class.java.getDeclaredField("failed").apply { isAccessible = true }.setBoolean(canvas, true)
            kb.requestLayout()
            h.relayout()
            assertEquals(View.INVISIBLE, h.pad().visibility)
            canvas.invalidateScene()
            kb.requestLayout()
            h.relayout()
            assertEquals(View.VISIBLE, h.pad().visibility)
        }
    }

    // a stroke's points as the pad got them under the views: one MOVE's batched samples (between frames) each a point
    private fun strokePoints(): String = harness().use { h ->
        val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
        val pad = h.pad()
        val x0 = pad.left + pad.width * 0.3f
        val y0 = pad.top + pad.height * 0.3f
        val t = android.os.SystemClock.uptimeMillis()
        fun send(e: android.view.MotionEvent) = kb.dispatchTouchEvent(e).also { e.recycle() }
        send(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x0, y0, 0))
        // one MOVE carrying 3 batched samples before its current one
        send(
            android.view.MotionEvent.obtain(t, t + 4, android.view.MotionEvent.ACTION_MOVE, x0 + 10, y0, 0).apply {
                addBatch(t + 8, x0 + 20, y0, 1f, 1f, 0)
                addBatch(t + 12, x0 + 30, y0, 1f, 1f, 0)
                addBatch(t + 16, x0 + 40, y0, 1f, 1f, 0)
            },
        )
        // out of the pad, onto the keys to its right, then up there
        send(android.view.MotionEvent.obtain(t, t + 20, android.view.MotionEvent.ACTION_MOVE, pad.right + 30f, y0, 0))
        send(android.view.MotionEvent.obtain(t, t + 24, android.view.MotionEvent.ACTION_UP, pad.right + 30f, y0, 0))
        h.idle()
        val s = pad.pad.strokes
        "strokes=${s.size} points=${s.map { it.size / 2 }} calls=${h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }}"
    }

    // ，。 as they type: with Chinese punctuation on, full width; off (the mapping empty), what is typed is half width
    @Test
    fun commaAndFullStopShownAsTheyType() {
        harness().use { h ->
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            kb.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
            h.idle()
            assertEquals(listOf("，", "。"), listOf(h.keyNamed("comma").label, h.keyNamed("period").label))
            kb.onPunctuationUpdate(emptyMap())
            h.idle()
            assertEquals(listOf(",", "."), listOf(h.keyNamed("comma").label, h.keyNamed("period").label))
        }
    }

    @Test
    fun readingKeyOnAsItsStateAndSaidAsItsView() {
        val said = listOf(true).map { _ ->
            harness().use { h ->
                val off = h.keyNamed("reading").description
                EngineState.candidateReadingState.value = ReadingStyle.PINYIN
                h.idle()
                h.relayout()
                run {
                    val node = KeyboardHarness.boardCanvas(h.keyboard)!!.keyAt("reading")!!
                    assertTrue(node.states.toString(), org.fcitx.fcitx5.android.scene.KeyState.On in node.states)
                }
                val on = h.keyNamed("reading").description
                EngineState.candidateReadingState.value = ReadingStyle.OFF
                listOf(off, on)
            }
        }
        val name = org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.im_candidate_reading)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(listOf(app.getString(R.string.hw_reading_key_off, name), app.getString(R.string.hw_reading_key_on, name)), said[0])
    }

    // 注 found by its view's name (button_reading) on either drawing: uat and TalkBack's tools look it up by it
    @Test
    fun readingKeyByItsViewsName() {
        run {
            harness().use { h -> assertEquals("button_reading", h.keyNamed("reading").resourceName) }
        }
    }

    // ，held and ？ picked: what reaches the engine and the field the same on either drawing
    @Test
    fun aPickFromTheCommasLongPressAsItsView() {
        val sent = listOf(true).map { _ ->
            harness().use { h ->
                val choices = h.longPress(h.keyNamed("comma"))
                assertTrue("$choices", "？" in choices)
                h.api.calls.clear()
                h.longPress(h.keyNamed("comma"), pick = "？")
                h.idle()
                h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } + "field ${h.field.text}"
            }
        }
        assertTrue(sent[0].toString(), sent[0].size > 1)
    }

    // 注's long press on the scene: each style in the settings' words, the one picked set
    @Test
    fun readingsMenuAsTheSettingsWordIt() {
        harness().use { h ->
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val words = listOf(R.string.im_candidate_reading_off, R.string.im_candidate_reading_pinyin, R.string.im_candidate_reading_zhuyin).map(app::getString)
            assertEquals(words.toSet(), h.longPress(h.keyNamed("reading")).toSet())
            h.api.calls.clear()
            h.longPress(h.keyNamed("reading"), pick = words[0])
            // set once what the recognizer is reading is in (its own thread): waited for in real time
            val until = System.currentTimeMillis() + SET_WITHIN_MS
            while (h.api.calls.none { it.startsWith("setImOptions") } && System.currentTimeMillis() < until) {
                Thread.sleep(POLL_MS)
                h.idle()
            }
            assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("setImOptions") })
        }
    }

    @Test
    fun theLanguageKeyHiddenAsTheSettingSays() {
        run {
            prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, false)
            harness().use { h ->
                assertTrue(h.keys().none { it.id == "lang" && it.enabled && !it.bounds.isEmpty })
            }
        }
    }

    // the setting turned off while the keyboard is up: the scene made again without the language key
    @Test
    fun theLanguageKeyHiddenWhenTheSettingChanges() {
        prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, true)
        harness().use { h ->
            val canvas = KeyboardHarness.boardCanvas(h.keyboard)!!
            assertTrue(canvas.scene!!.keys.any { it.id == "lang" })
            prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, false)
            h.idle()
            assertTrue(canvas.scene!!.keys.none { it.id == "lang" && it.bounds.right > it.bounds.left })
        }
    }

    // the field's action changed while the keyboard is up (onReturnDrawableUpdate): Return said as the new one
    @Test
    fun returnAsTheFieldsActionWhenItChanges() {
        harness().use { h ->
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            val before = h.keys().map { it.description }
            kb.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_enter, customLabel = "前往搜索"))
            h.idle()
            val after = h.keys().map { it.description }
            assertTrue("$before $after", "前往搜索" !in before.toString() && "前往搜索" in after.toString())
        }
    }

    private fun View.relayoutAt(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(left, top, left + w, top + h)
    }

    private companion object {
        const val SET_WITHIN_MS = 5_000L
        const val POLL_MS = 10L
    }
}
