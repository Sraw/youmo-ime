/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import android.text.InputType
import android.os.Vibrator
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
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
 * password field's number row, the symbol grid's cells): what each key does to a finger is what it is
 * for (the expected values written here, not read from the drawing).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class SmallBoardsSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    // whether [root] has a canvas shown: the scene draws it
    private fun drawnByScene(root: View) = KeyboardHarness.all(root).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE }

    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    // the emoji panel's full stop (emoji.xml): its face as punctuation maps it, the key it sends the one the input
    // panel commits mapped the same way, no bubble (popups="off"): with Chinese punctuation 。, without it .
    @Test
    @Uat("PAN-24")
    fun thePickersFullStopShowsWhatItTypes() {
        // the last picker remembered as it was before any tap here, put back after (the emoticon page's tap sets it)
        val last = AppPrefs.getInstance().internal.lastPickerType
        prefs.set(last, last.getValue())
        thePickersFullStopShows()
    }

    private fun thePickersFullStopShows() = KeyboardHarness().start().use { h ->
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        h.relayout()
        h.typesAsShown("period", "。", ".")
        // the emoticon page's comma the same (emoticon.xml)
        h.tap(h.key(":-)"))
        h.relayout()
        h.typesAsShown("comma", "，", ",")
    }

    // before what the marks type is known (none kept from the last run, the host not up): the panel's 。 types
    // nothing, as the text keyboards' marks are held back, the first tap of it felt once (KeyboardWindow.heldBack);
    // known, it types the mark mapped
    @Test
    @Uat("PAN-24")
    fun thePickersFullStopTypesNothingBeforeTheMarksAreKnown() {
        val app = RuntimeEnvironment.getApplication()
        // felt as a buzz of its own: a key's press buzz is over before the finger lifts
        AppPrefs.getInstance().keyboard.hapticOnKeyPress.setValue(InputFeedbackMode.Enabled)
        AppPrefs.getInstance().keyboard.buttonPressVibrationMilliseconds.setValue(BUZZ_MS)
        val vibrator = shadowOf(app.getSystemService(Vibrator::class.java))
        KeyboardHarness().start().use { h ->
            h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
            h.relayout()
            // not known: the harness's host tells its marks as the view is made (in its init), before a test can
            // say otherwise, so the component is set back by hand
            val punctuation = h.inputView.javaClass.getDeclaredField("punctuation").apply { isAccessible = true }
                .get(h.inputView) as PunctuationComponent
            PunctuationStartKeys.forget(app)
            PunctuationComponent::class.java.getDeclaredField("known").apply { isAccessible = true }.setBoolean(punctuation, false)
            val kb = KeyboardHarness.all(h.inputView).filterIsInstance<PickerLayout.Keyboard>().first { h.shown(it) }
            // whether the lift buzzed: the press's buzz let run out first
            fun tapPeriod(): Boolean {
                h.press(h.keys(kb).single { it.id == "period" })
                h.advance(BUZZ_MS * 4L)
                h.release()
                return vibrator.isVibrating.also { h.advance(BUZZ_MS * 4L) }
            }
            assertTrue("the first held back felt", tapPeriod())
            assertFalse("felt once", tapPeriod())
            assertEquals(h.api.calls.toString(), "", h.field.text.toString())
            punctuation.update(mapOf("." to "。", "," to "，"))
            h.relayout()
            assertFalse("typed: not held back", tapPeriod())
            assertEquals("。", h.field.text.toString())
        }
    }

    // [id] on the picker shown: [chinese] with the Chinese punctuation mapping, [plain] with none, as its face and typed
    private fun KeyboardHarness.typesAsShown(id: String, chinese: String, plain: String) {
        val kb = KeyboardHarness.all(inputView).filterIsInstance<PickerLayout.Keyboard>().first { shown(it) }
        // the recently used as none: what another test left is not this one's
        val recentPrefs = FcitxApplication.getInstance().directBootAwareContext.getSharedPreferences(RecentlyUsed.PREFERENCE_NAME, Context.MODE_PRIVATE)
        recentPrefs.edit().clear().commit()
        for ((mapping, face) in listOf(mapOf("." to "。", "," to "，") to chinese, emptyMap<String, String>() to plain)) {
            // the input method's punctuation component, which broadcasts to the panel and its keyboard
            val punctuation = inputView.javaClass.getDeclaredField("punctuation").apply { isAccessible = true }
                .get(inputView) as PunctuationComponent
            punctuation.update(mapping)
            relayout()
            val key = keys(kb).single { it.id == id }
            assertEquals(face, key.label)
            api.calls.clear()
            press(key)
            assertEquals("no bubble", emptyList<Any>(), popupChoices())
            release()
            // typed as the face shows it (committed by the panel as punctuation maps it, not through the engine)
            assertEquals(api.calls.toString(), face, field.text.toString())
            assertTrue(api.calls.toString(), api.calls.none { it.startsWith("sendKey") })
            // a mark, not a symbol picked: not among the recently used (PAN-24)
            // nothing kept at all (a match in the stored text would take , for the list's own commas)
            assertEquals(face, emptyMap<String, Any?>(), recentPrefs.all)
            field.setText("")
            idle()
        }
    }

    @Test
    fun emojiSearch() {
        val out = run {
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
        val keys = out[0].removePrefix("keys [").removeSuffix("]").split(", ")
        assertEquals(out[0], 28, keys.size)
        assertTrue(out[0], ('a'..'z').all { "$it" in keys })
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
        val out = run {
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
                    // off again: the rest is a finger's, as without a screen reader
                    shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setEnabled(false)
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
        // the column's keys: the two pages, ⌫ and Return among them (Return said as the field's action: the harness's is Done)
        val app = RuntimeEnvironment.getApplication()
        for (d in listOf(app.getString(R.string.a11y_candidates_prev_page), app.getString(R.string.a11y_candidates_next_page), "Backspace", app.getString(R.string.a11y_key_action_done))) {
            assertTrue(out[0], d in out[0])
        }
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
        val out = run {
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
        val out = run {
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

    private companion object {
        // a buzz this long, ms: over before the finger lifts when let run out
        const val BUZZ_MS = 5
    }
}
