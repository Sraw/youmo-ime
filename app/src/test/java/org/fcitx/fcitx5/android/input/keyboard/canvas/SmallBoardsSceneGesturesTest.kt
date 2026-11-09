/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.keyboard.KeyboardCanvasViewTest
import org.fcitx.fcitx5.android.input.picker.EmojiSearchUi
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The small boards 060-render-p2 S6 moved to the scene (表情搜索's letters, 展开候选's column, the
 * password field's number row, the symbol grid's cells): the same gestures on each drawing give the
 * same output, and that output is what each key is for (the expected values written here, not read
 * from either drawing).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class SmallBoardsSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    // what a board's keys did (compared with their views' till S8a deleted them)
    private fun both(what: () -> List<String>): List<String> = what()

    // whether [root] has a canvas shown: the scene draws it
    private fun drawnByScene(root: View) = KeyboardHarness.all(root).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE }

    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    // the emoji panel's full stop (emoji.xml): its face as punctuation maps it, the key it sends the one the input
    // panel commits mapped the same way, no bubble (popups="off"): with Chinese punctuation 。, without it .
    @Test
    fun thePickersFullStopShowsWhatItTypes() = KeyboardHarness().start().use { h ->
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        h.relayout()
        val kb = KeyboardHarness.all(h.inputView).filterIsInstance<PickerLayout.Keyboard>().first { h.shown(it) }
        for ((mapping, face) in listOf(mapOf("." to "。", "," to "，") to "。", emptyMap<String, String>() to ".")) {
            // the input method's punctuation component, which broadcasts to the panel and its keyboard
            val punctuation = h.inputView.javaClass.getDeclaredField("punctuation").apply { isAccessible = true }
                .get(h.inputView) as PunctuationComponent
            punctuation.update(mapping)
            h.relayout()
            val period = h.keys(kb).single { it.id == "period" }
            assertEquals(face, period.label)
            h.api.calls.clear()
            h.press(period)
            assertEquals("no bubble", emptyList<Any>(), h.popupChoices())
            h.release()
            // typed as the face shows it (committed by the panel as punctuation maps it, not through the engine)
            assertEquals(h.api.calls.toString(), face, h.field.text.toString())
            h.field.setText("")
            h.idle()
        }
    }

    @Test
    fun emojiSearch() {
        val out = both {
            KeyboardHarness().start().use { h ->
                // text in the field before: a held ⌫ clears the query and stops there, never deleting into it
                h.field.setText("ab")
                h.field.setSelection(2)
                h.idle()
                h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
                // the bar's 表情 opens the panel last shown: no 搜索 (the 颜文字), its own 表情 key back to the emoji
                h.relayout()
                val searchName = h.service.getString(R.string.emoji_search)
                if (h.keys().none { it.description == searchName }) {
                    h.tap(h.keys().first { it.description == h.service.getString(R.string.a11y_key_emoji) })
                    h.relayout()
                }
                h.tap(h.describedKey(searchName))
                val search = KeyboardHarness.find<EmojiSearchUi>(h.inputView)!!
                assertTrue(drawnByScene(search))
                val keys = h.keys(search)
                val out = mutableListOf("keys ${keys.map { it.label ?: it.description }}")
                for (c in "xiao") h.tap(keys.single { it.label == c.toString() })
                out += "xiao: ${"😄" in h.texts(search)}"
                h.tap(h.keys(search).single { it.description == "Backspace" })
                out += "⌫: query '${h.texts(search).first()}' field '${h.field.text}' engine ${h.sent()}"
                // held: the query cleared, the field kept
                h.press(h.keys(search).single { it.description == "Backspace" })
                h.after(3000)
                h.release()
                out += "⌫ held: query '${h.texts(search).first()}' field '${h.field.text}' engine ${h.sent()}"
                h.tap(h.keys(search).single { it.label == h.service.getString(R.string.picker_back) })
                out += "back: search ${KeyboardHarness.find<EmojiSearchUi>(h.inputView)?.let(h::shown) == true}"
                out
            }
        }
        // independently: 26 letters, 返回 and ⌫; xiao finds 😄; the field's ab kept, nothing typed into it nor deleted; 返回 closes the search
        assertEquals(28, out[0].removePrefix("keys [").removeSuffix("]").split(", ").size)
        assertEquals("xiao: true", out[1])
        // ⌫ takes the query's last letter; held, the query is cleared to its hint and stops there: the field's ab
        // kept and nothing sent to the engine either time
        val hint = RuntimeEnvironment.getApplication().getString(R.string.emoji_search_hint)
        assertEquals("⌫: query 'xia' field 'ab' engine []", out[2])
        assertEquals("⌫ held: query '$hint' field 'ab' engine []", out[3])
        assertTrue(out.toString(), out.last() == "back: search false")
    }

    @Test
    fun expandedColumn() {
        val out = both {
            KeyboardHarness().start().use { h ->
                h.candidates(*Array(80) { "字$it" })
                h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
                val side = KeyboardHarness.find<ExpandedCandidateLayout.Keyboard>(h.inputView)!!
                assertTrue(drawnByScene(side))
                val up = { h.keys(side).single { it.description == h.service.getString(R.string.a11y_candidates_prev_page) } }
                val down = { h.keys(side).single { it.description == h.service.getString(R.string.a11y_candidates_next_page) } }
                val list = KeyboardHarness.find<ExpandedCandidateLayout>(h.inputView)!!.recyclerView
                // the first candidate wholly in sight, as the list has it
                val first = {
                    val rv = list
                    val at = (0 until rv.childCount).map(rv::getChildAt).filter { it.top >= 0 }.minOf(rv::getChildAdapterPosition)
                    "字$at"
                }
                val out = mutableListOf("keys ${h.keys(side).map { it.description }}", "first ${first()}")
                out += "up disabled ${KeyState.Disabled in KeyboardHarness.find<KeyboardCanvasView>(side)!!.keyAt("page-up")!!.states}"
                // what the column sends to a finger (not a click: a screen reader's): none from a page that cannot
                // turn, its view disabled, the scene's key :disabled and faded
                val acts = mutableListOf<String>()
                val was = side.keyActionListener
                side.keyActionListener = org.fcitx.fcitx5.android.input.keyboard.KeyActionListener { a, src -> acts += a.toString(); was?.onKeyAction(a, src) }
                h.press(up())
                h.release()
                out += "up at the top: first ${first()} sent $acts"
                run {
                    // a screen reader's double tap on the page that cannot turn: refused, nothing sent (060-render-p2 §5.3)
                    shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
                    val c = KeyboardHarness.find<KeyboardCanvasView>(side)!!
                    val i = c.scene!!.keys.indexOfFirst { it.id == "page-up" }
                    assertFalse(c.accessibilityNodeProvider!!.performAction(i, AccessibilityNodeInfo.ACTION_CLICK, null))
                    h.idle()
                    assertEquals("first 字0 sent []", "first ${first()} sent $acts")
                }
                h.tap(down())
                h.after(1000)
                h.relayout()
                val paged = first()
                out += "down: moved ${paged != "字0"}"
                h.tap(up())
                h.after(1000)
                h.relayout()
                out += "up: first ${first()}"
                h.api.calls.clear()
                h.tap(h.keys(side).single { it.description == "Backspace" })
                h.tap(h.keys(side).single { it.description?.startsWith("Enter") == true || it.resourceName == "button_return" || it.id == "enter" })
                out += "sent ${h.sent().map { it.substringAfter("sym=").substringBefore(',') }}"
                out.filterNot { it.startsWith("up disabled") }.also { assertTrue(out.toString(), "up disabled true" in out) }
            }
        }
        // independently: a page down moves past 字0, a page up at the top does nothing and back again goes to 字0;
        // ⌫ and Return reach the engine as BackSpace (0xff08) and Return (0xff0d)
        assertEquals("first 字0", out[1])
        assertEquals("up at the top: first 字0 sent []", out[2])
        assertEquals("down: moved true", out[3])
        assertEquals("up: first 字0", out[4])
        assertEquals("sent [${0xff08}, ${0xff0d}]", out[5])
    }

    private fun KeyboardHarness.candidates(vararg words: String) {
        api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        emit(HostEvent.Candidates(words.size, api.candidates.toList()))
    }

    private fun password(h: KeyboardHarness) {
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
    }

    @Test
    fun numberRow() {
        val out = both {
            KeyboardHarness().use { h ->
                password(h)
                val row = KeyboardHarness.find<NumberRow>(h.inputView)!!
                assertTrue(drawnByScene(row))
                val keys = h.keys(row)
                val out = mutableListOf("keys ${keys.map { it.label }}")
                h.api.calls.clear()
                h.tap(keys.single { it.label == "1" })
                h.tap(keys.single { it.label == "0" })
                out += "sent ${h.sent().map { it.substringAfter("sym=").substringBefore(',') }}"
                // swiped right across it: collapsed, nothing typed
                h.api.calls.clear()
                val one = h.keys(row).single { it.label == "1" }
                h.drag(one, one.bounds.exactCenterX() + row.width * 0.6f, one.bounds.exactCenterY())
                out += "swiped: row ${h.shown(row)} sent ${h.sent()}"
                out
            }
        }
        // independently: XK_1 is 0x31, XK_0 0x30 (the digits' own, not the keypad's)
        assertEquals("keys [1, 2, 3, 4, 5, 6, 7, 8, 9, 0]", out[0])
        assertEquals("sent [${0x31}, ${0x30}]", out[1])
        assertEquals("swiped: row false sent []", out[2])
    }

    @Test
    fun symbolGrid() {
        val out = both {
            KeyboardHarness().start().use { h ->
                h.tap(h.key("符"))
                h.relayout()
                val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
                assertTrue(KeyboardHarness.all(grid).any { it is SceneKeyCell })
                val cells = KeyboardHarness.all(grid).filterIsInstance<SceneKeyCell>()
                    .filter(h::shown).toList()
                val label = { v: SceneKeyCell -> v.keyNode?.label.orEmpty() }
                val labels = cells.map(label)
                val out = mutableListOf("cells ${labels.take(12)}")
                val comma = cells.first { label(it) == "，" }
                h.tap(comma)
                out += "tap: field '${h.field.text}'"
                out
            }
        }
        // independently: a tap on ， commits ，
        assertEquals("tap: field '，'", out[1])
    }
}
