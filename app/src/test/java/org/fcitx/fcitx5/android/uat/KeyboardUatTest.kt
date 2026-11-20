/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardUatTest {

    private var h: KeyboardHarness? = null

    private fun harness(im: InputMethodEntry = KeyboardHarness.PINYIN) =
        KeyboardHarness(im = im).also { h = it }

    @After
    fun close() {
        h?.close()
    }

    @Test
    @Uat("PAN-01")
    fun numberPadAndBack() {
        val h = harness().start()
        h.tap(h.key("123"))
        val rows = h.texts()
        val back = h.service.getString(R.string.picker_back)
        assertEquals(listOf("+", "1", "2", "3", "-", "4", "5", "6", "*", "7", "8", "9", "/", "符", back, ",", "0", "=", "."), rows)
        space(h)
        val del = h.describedKey("Backspace").bounds
        assertEquals("⌫ in the row of 3", h.key("3").bounds.top, del.top)
        assertTrue("⌫ right of 3", del.left >= h.key("3").bounds.right)
        h.captureBoard("KeyboardUatTest/number_pad")
        h.tap(h.key("4"))
        h.tap(h.key("2"))
        assertEquals(
            listOf(FcitxKeyMapping.FcitxKey_KP_4, FcitxKeyMapping.FcitxKey_KP_2),
            h.api.calls.filter { it.startsWith("sendKey") }.map { Regex("sym=(\\d+)").find(it)!!.groupValues[1].toInt() },
        )
        h.tap(h.key(back))
        val letters = h.texts()
        assertTrue(letters.toString(), "q" in letters && "m" in letters && "123" in letters)
    }

    // the symbol panel's row the letters' way round, 返回 where 符 was, 123 second; its 123 opens the number pad
    @Test
    @Uat("KB-91")
    fun theSymbolPanelsRowIsBackThen123ThenTheLock() {
        val h = harness().start()
        h.tap(h.key("符"))
        val row = listOf("back", "numbers", "lock", "space", "backspace", "enter")
        assertEquals(row, h.keys().filter { it.id in row }.sortedBy { it.bounds.left }.map { it.id })
        assertEquals(h.service.getString(R.string.picker_back), h.keyNamed("back").label)
        assertEquals("123", h.keyNamed("numbers").label)
        h.tap(h.keyNamed("numbers"))
        assertTrue(h.texts().toString(), "=" in h.texts())
    }

    @Test
    @Uat("KB-22")
    fun addressFieldHasSlashDotAndDotCom() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val keys = h.texts()
        assertEquals(listOf("符", "123", "/", "Pinyin", ".", ".com"), keys.takeLast(6))
        assertFalse(keys.toString(), "，" in keys || "。" in keys || "," in keys)
        h.tap(h.key(".com"))
        assertEquals(".com", h.field.text.toString())
    }

    @Test
    @Uat("KB-39")
    fun shiftChangesAllThreeRows() {
        val h = harness().start()
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        fun letters() = h.texts().filter { it.length == 1 && it[0].lowercaseChar() in 'a'..'z' }.joinToString("")
        assertEquals(rows.joinToString(""), letters())
        h.capture("KeyboardUatTest/shift_off")
        h.tap(h.describedKey("Shift"))
        assertEquals(rows.joinToString("").uppercase(), letters())
        h.capture("KeyboardUatTest/shift_on")
        h.tap(h.describedKey("Shift"))
        assertEquals(rows.joinToString(""), letters())
    }

    @Test
    @Uat("PR-03")
    fun passwordFieldTypesEnglishWithNumberRow() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // CapabilityFlag.Password: androidengine switches it to English
        val flags = Regex("setCapFlags\\((\\d+)\\)").find(h.api.calls.joinToString())!!.groupValues[1].toULong()
        assertTrue("flags $flags", flags and CapabilityFlag.Password.flag != 0UL)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        val keys = h.texts()
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), keys.take(10))
        assertTrue(keys.toString(), "English" in keys)
        h.capture("KeyboardUatTest/password")
        h.tap(h.key("n"))
        assertTrue(h.api.calls.toString(), sent().any { "char=n," in it || "sym=110," in it || "key=n," in it })
    }

    private fun sent() = h!!.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun lastSent() = sent().lastOrNull().orEmpty()

    private fun s(id: Int) = h!!.service.getString(id)

    private fun space(h: KeyboardHarness) = h.keys()
        .first { it.description?.let { d -> d == "Space" || d.startsWith("Space,") } == true }

    @Test
    @Uat("KB-03")
    fun swipingALetterUpTypesItsCorner() {
        val h = harness().start()
        h.swipe(h.key("w"), 0f, -h.key("w").height * 1.3f)
        assertEquals(sent().toString(), 1, sent().size)
        assertTrue(lastSent(), "key=2," in lastSent())
    }

    @Test
    @Uat("KB-04")
    fun aLongPressLetGoTypesTheCorner() {
        val h = harness().start()
        h.longPress(h.key("e"))
        assertTrue(sent().toString(), sent().size == 1 && "key=3," in lastSent())
    }

    @Test
    @Uat("KB-05")
    fun aLongPressesChoicesTypeWhereTheFingerIs() {
        val h = harness().start()
        val shown = h.longPress(h.key("q"), "Q")
        assertTrue(shown.toString(), "1" in shown && "Q" in shown)
        assertTrue(sent().toString(), sent().size == 1 && "key=Q," in lastSent())
    }

    private fun corner(h: KeyboardHarness, letter: String) = h.key(letter).alt.orEmpty()

    @Test
    @Uat("KB-06")
    fun cornersShowChinesePunctuationWhileChinese() {
        val h = harness().start()
        val entries = listOf("\\" to "、", "?" to "？").map { (k, v) ->
            PunctuationMapEntry(k, v, "")
        }
        runBlocking { h.api.setPunctuationMap("zh_CN", entries) }
        PunctuationManager.forget()
        val active = listOf(StatusEntry(1, "punctuation", "fcitx-punc-active", "", true))
        h.api.statusAreaCached = active
        h.emit(HostEvent.StatusArea(active, KeyboardHarness.PINYIN, entries.groupBy { it.key }.mapValues { it.value.first().mapping }))
        assertEquals("、", corner(h, "m"))
        assertEquals("？", corner(h, "v"))
        val inactive = listOf(StatusEntry(1, "punctuation", "fcitx-punc-inactive", "", false))
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.emit(HostEvent.StatusArea(inactive, KeyboardHarness.ENGLISH, emptyMap()))
        assertEquals("\\", corner(h, "m"))
        assertEquals("?", corner(h, "v"))
        PunctuationManager.forget()
    }

    @Test
    @Uat("KB-08")
    fun shiftTwiceLocksCaps() {
        val h = harness(KeyboardHarness.ENGLISH).start()
        repeat(2) {
            h.press(h.describedKey("Shift"))
            h.release()
        }
        assertEquals(s(R.string.a11y_state_caps_lock), h.describedKey("Shift").state)
        h.tap(h.key("O"))
        h.tap(h.key("K"))
        assertTrue(sent().toString(), "key=O," in sent()[0] && "key=K," in sent()[1])
        h.tap(h.describedKey("Shift"))
        h.tap(h.key("a"))
        assertTrue(lastSent(), "key=a," in lastSent())
    }

    private fun backspaces() = sent().count { "sym=${FcitxKeyMapping.FcitxKey_BackSpace}," in it }

    // the keyboard's half: the space's slow drag sends ← to the engine, no long press
    @Test
    @Uat("PY-41")
    fun aSlowSpaceDragMovesThePinyinCursorAndTheRowFollowsIt() {
        // set: the default is voice input, whose hold takes the finger past the long press, so the drag would never reach
        // the cursor
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        val was = behavior.getValue()
        behavior.setValue(SpaceLongPressBehavior.None)
        try {
            slowSpaceDrag()
        } finally {
            behavior.setValue(was)
        }
    }

    // the default voice input but no voice pack: its hold does nothing then and takes no finger from the drag
    @Test
    @Uat("PY-41")
    fun withoutTheVoicePackTheDefaultHoldLeavesTheSlowDragItsCursor() {
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        assertEquals(SpaceLongPressBehavior.Voice, behavior.getValue())
        try {
            slowSpaceDrag { h ->
                TestShelves.use(text = true, voice = false)
                h.idle()
            }
        } finally {
            TestShelves.use()
        }
    }

    private fun slowSpaceDrag(before: (KeyboardHarness) -> Unit = {}) {
        val h = harness().start()
        before(h)
        val pinyin = "woxiangzhidaojintianwanshangwomenqunalichifan".repeat(2)
        fun cursorAt(at: Int) {
            h.api.inputPanelCached = HostEvent.InputPanel(FormattedText(arrayOf(pinyin), intArrayOf(0), at), FormattedText.Empty, FormattedText.Empty)
            h.emit(h.api.inputPanelCached)
            h.idle()
            h.relayout()
        }
        cursorAt(pinyin.length)
        val row = h.inputView.findViewById<HorizontalScrollView>(R.id.code_row_text)
        val atEnd = row.scrollX
        assertTrue("scrolled to the end: $atEnd", atEnd > 0)
        h.api.calls.clear()
        val key = space(h)
        val finger = h.finger(key, h.at(key))
        h.advance(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        val third = h.inputView.width / 3f / h.inputView.resources.displayMetrics.density
        for (i in 1..20) {
            h.advance(180)
            finger.move(h.at(key, dx = -third * i / 20))
        }
        finger.up(h.at(key, dx = -third))
        val lefts = sent().count { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it }
        assertTrue(h.api.calls.toString(), lefts >= 2)
        assertTrue(h.api.calls.toString(), h.api.calls.none { "enumerate" in it.lowercase() || "toggleIme" in it })
        assertTrue(h.popupChoices().toString(), h.popupChoices().isEmpty())
        assertEquals("", h.field.text.toString())
        assertEquals(0, h.field.selectionStart)
        cursorAt(pinyin.length / 3)
        val shown = row.scrollX
        assertTrue("$shown of $atEnd", shown < atEnd)
        val x = (row.getChildAt(0) as TextView).let { it.totalPaddingLeft + it.layout.getPrimaryHorizontal(pinyin.length / 3) }
        assertTrue("$x in $shown..${shown + row.width}", x >= shown && x <= shown + row.width)
        val box = h.box(row)
        h.drag(row, box.right - 10f, box.exactCenterY())
        assertTrue("${row.scrollX} after $shown", row.scrollX != shown)
    }

    @Test
    @Uat("KB-09")
    fun backspaceOnce() {
        val h = harness().start()
        h.tap(h.describedKey("Backspace"))
        assertEquals(1, backspaces())
    }

    @Test
    @Uat("KB-11")
    fun holdingBackspaceRepeatsFasterTillLetGo() {
        val h = harness().start()
        val key = h.describedKey("Backspace")
        h.press(key)
        h.after(1000)
        val first = backspaces()
        h.after(1000)
        val second = backspaces() - first
        h.release()
        val all = backspaces()
        h.after(500)
        assertEquals("none after letting go", all, backspaces())
        assertTrue("first second $first, then $second", first >= 2 && second > first)
        h.api.calls.clear()
        val b = h.box(key)
        h.drag(key, b.exactCenterX() + b.width() / 4f, b.exactCenterY(), holdMs = 1500, steps = 2, lift = true)
        assertTrue(sent().toString(), backspaces() >= 3)
    }

    @Test
    @Uat("KB-12")
    fun swipingBackspaceLeftSelectsAndDeletes() {
        val h = harness().start()
        h.field.setText("今天天气很好")
        h.field.setSelection(6)
        h.idle()
        val key = h.describedKey("Backspace")
        val b = h.box(key)
        h.drag(key, b.exactCenterX() - h.service.resources.displayMetrics.widthPixels * 0.4f, b.exactCenterY(), steps = 16, lift = false)
        assertTrue("selected ${h.field.selectionStart}..${h.field.selectionEnd}", h.field.selectionEnd - h.field.selectionStart >= 1)
        h.release()
        assertTrue(h.field.text.toString(), h.field.text.length < 6 && "今天天气很好".startsWith(h.field.text.toString()))
    }

    @Test
    @Uat("KB-13")
    fun swipingBackspaceWhileTypingClearsThePinyin() {
        val h = harness().start()
        h.api.inputPanelCached = HostEvent.InputPanel(FormattedText(arrayOf("zhong guo"), intArrayOf(0), 9), FormattedText.Empty, FormattedText.Empty)
        h.emit(h.api.inputPanelCached)
        val key = h.describedKey("Backspace")
        val b = h.box(key)
        h.drag(key, b.exactCenterX() - h.service.resources.displayMetrics.widthPixels * 0.4f, b.exactCenterY(), steps = 16)
        assertTrue(h.api.calls.toString(), "reset(drop)" in h.api.calls)
    }

    @Test
    @Uat("KB-16")
    fun holdingTheCommaOffersTwoRows() {
        val h = harness().start()
        val comma = h.key(",")
        val shown = h.longPress(comma)
        assertTrue(shown.toString(), listOf(".", "?", "!", ":", ";").all { it in shown })
        fun typed() = h.field.text.toString().ifEmpty {
            sent().lastOrNull()?.let { Regex("(?:char|key)=(.+?), states").find(it)?.groupValues?.get(1) }.orEmpty()
        }
        assertEquals(h.api.calls.toString() + h.field.text, ".", typed())
        val step = h.service.resources.displayMetrics.density * 25
        val b = h.box(comma)
        val got = listOf(step to 0f, 0f to -step, 0f to -b.height() * 6f).map { (dx, dy) ->
            h.field.setText("")
            h.api.calls.clear()
            h.drag(comma, b.exactCenterX() + dx, b.exactCenterY() + dy, holdMs = 700, steps = 4)
            typed().also { assertTrue("moved $dx $dy: ${h.api.calls} ${h.field.text}", it in shown) }
        }
        assertEquals(got.toString(), 3, (listOf(".") + got.take(2)).distinct().size)
    }

    @Test
    @Uat("KB-17")
    fun spaceSlidesMoveTheCursor() {
        val h = harness().start()
        val space = space(h)
        h.swipe(space, -space.width * 0.6f, 0f)
        val left = sent().count { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it }
        assertTrue(sent().toString(), left >= 2 && sent().all { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
        h.api.calls.clear()
        h.swipe(space, space.width * 0.2f, 0f)
        assertTrue(sent().toString(), sent().isNotEmpty() && sent().all { "sym=${FcitxKeyMapping.FcitxKey_Right}," in it })
        assertTrue(sent().size < left)
    }

    @Test
    @Uat("KB-70")
    fun spaceSlidesUpAndDownAfterSideways() {
        val up = "sym=${FcitxKeyMapping.FcitxKey_Up},"
        val down = "sym=${FcitxKeyMapping.FcitxKey_Down},"
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val space = space(h)
        val f = h.finger(space, h.at(space))
        f.move(h.at(space, dx = -15f))
        f.move(h.at(space, dx = -25f))
        f.move(h.at(space, dx = -25f, dy = -30f))
        f.move(h.at(space, dx = -25f, dy = -60f))
        assertTrue(sent().toString(), sent().count { up in it } == 2)
        f.move(h.at(space, dx = -25f, dy = -20f))
        f.up(h.at(space, dx = -25f, dy = -20f))
        assertTrue(sent().toString(), sent().count { down in it } == 1)
        h.api.calls.clear()
        val g = h.finger(space, h.at(space))
        g.move(h.at(space, dy = -60f))
        g.move(h.at(space, dy = -70f))
        g.up(h.at(space, dy = -70f))
        assertTrue(sent().toString(), sent().none { up in it })
    }

    @Test
    @Uat("KB-70")
    fun spaceSlidesDiagonallyMovesBothWaysInOneSample() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val space = space(h)
        val f = h.finger(space, h.at(space))
        f.move(h.at(space, dx = -25f))
        h.api.calls.clear()
        f.move(h.at(space, dx = -45f, dy = -32f))
        f.up(h.at(space, dx = -45f, dy = -32f))
        assertTrue(sent().toString(), sent().any { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
        assertTrue(sent().toString(), sent().count { "sym=${FcitxKeyMapping.FcitxKey_Up}," in it } == 1)
    }

    @Test
    @Uat("KB-70")
    fun spaceSlidesNoLine() {
        fun noLine(h: KeyboardHarness) = try {
            val space = space(h)
            val f = h.finger(space, h.at(space))
            f.move(h.at(space, dx = -25f))
            f.move(h.at(space, dx = -25f, dy = -60f))
            f.up(h.at(space, dx = -25f, dy = -60f))
            assertTrue(sent().toString(), sent().any { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
            assertTrue(sent().toString(), sent().none { "sym=${FcitxKeyMapping.FcitxKey_Up}," in it })
        } finally {
            h.close()
            this.h = null
        }
        assertRows(
            "快速输入 on: its Up and Down would turn the candidates' pages" to {
                val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                h.emit(HostEvent.InputPanel(FormattedText.Empty, FormattedText(arrayOf("快速输入: "), intArrayOf(0), -1), FormattedText.Empty, quickPhrase = true))
                noLine(h)
            },
            "a single-line field has no line to move to" to {
                val h = harness().start()
                noLine(h)
            },
        )
    }

    private fun actions(h: KeyboardHarness): MutableList<Int> {
        val done = mutableListOf<Int>()
        h.field.setOnEditorActionListener { _, action, _ -> done += action; true }
        return done
    }

    /** Given back as the host gives back a virtual key no input method took: the service's own handling is checked. */
    private fun returnLetThrough(h: KeyboardHarness) {
        assertTrue(h.api.calls.toString(), sent().any { "sym=${FcitxKeyMapping.FcitxKey_Return}," in it })
        h.emit(HostEvent.Key(FcitxKeyMapping.FcitxKey_Return, KeyStates.Virtual.states.toInt(), 0, false, 0))
    }

    @Test
    @Uat("KB-20")
    fun searchFieldsReturnSearches() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        val ret = h.describedKey(s(R.string.a11y_key_action_search))
        val done = actions(h)
        h.tap(ret)
        returnLetThrough(h)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), done)
        assertFalse(h.field.text.contains("\n"))
    }

    @Test
    @Uat("KB-21")
    fun nextFieldsReturnGoesOn() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT)
        val ret = h.describedKey(s(R.string.a11y_key_action_next))
        val done = actions(h)
        h.tap(ret)
        returnLetThrough(h)
        assertEquals(listOf(EditorInfo.IME_ACTION_NEXT), done)
    }

    @Test
    @Uat("KB-23")
    fun holdingDotComOffersMore() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val shown = h.longPress(h.key(".com"), ".cn")
        assertTrue(shown.toString(), listOf(".cn", ".net", ".org").all { it in shown })
        assertEquals(".cn", h.field.text.toString())
    }

    @Test
    @Uat("KB-30")
    fun aKeyHeldShowsItLarger() {
        val h = harness().start()
        h.press(h.key("a"))
        assertTrue(h.popupChoices().map { it.text }.toString(), h.popupChoices().any { it.text == "a" })
        h.release()
        h.after(300)
        assertTrue(h.popupChoices().map { it.text }.toString(), h.popupChoices().none { it.text == "a" })
    }

    @Test
    @Uat("KB-32")
    fun glideIsOffByDefault() {
        assertEquals(KeyDrawing.Off, AppPrefs.getInstance().keyboard.keyDrawing.getValue())
        val h = harness(KeyboardHarness.ENGLISH).start()
        val o = h.box(h.key("o"))
        h.drag(h.key("h"), o.exactCenterX(), o.exactCenterY(), steps = 12)
        assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
    }

    @Test
    @Uat("KB-35")
    fun tapsShortSwipesAndHoldsAreNoGlide() {
        val glides = AppPrefs.getInstance().keyboard.keyDrawing
        glides.setValue(KeyDrawing.Glide)
        try {
            val h = harness(KeyboardHarness.ENGLISH).start()
            h.tap(h.key("o"))
            h.tap(h.key("k"))
            assertTrue(sent().toString(), "key=o," in sent()[0] && "key=k," in sent()[1])
            h.swipe(h.key("q"), 0f, -h.key("q").height * 1.3f)
            assertTrue(lastSent(), "key=1," in lastSent())
            val shown = h.longPress(h.key("e"))
            assertTrue(shown.toString(), shown.size > 1)
            assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
        } finally {
            glides.setValue(KeyDrawing.Off)
        }
    }

    @Test
    @Uat("KB-38")
    fun holdingTheSpaceSetToNothingIsOneSpace() {
        // set: the default is voice input
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        val was = behavior.getValue()
        behavior.setValue(SpaceLongPressBehavior.None)
        try {
            val h = harness(KeyboardHarness.ENGLISH).start()
            h.press(space(h))
            h.after(1000)
            assertTrue(h.popupChoices().map { it.text }.toString(), h.popupChoices().isEmpty())
            h.release()
            assertEquals(sent().toString(), 1, sent().count { "sym=${FcitxKeyMapping.FcitxKey_space}," in it })
        } finally {
            behavior.setValue(was)
        }
    }

    @Test
    @Uat("KB-41")
    fun aFirstMoveSidewaysAfterSlippingOffKeepsThePopup() {
        val h = harness().start()
        val q = h.key("q")
        val b = h.box(q)
        val dp = h.service.resources.displayMetrics.density
        h.press(q)
        h.drag(q, b.exactCenterX(), b.bottom + 3 * dp, steps = 0, lift = false)
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        h.move(b.exactCenterX() + 25 * dp, b.bottom + 3 * dp, steps = 1)
        val keys = h.popupChoices()
        val shown = keys.map { it.text }
        assertTrue("the popup is still up", shown.isNotEmpty())
        // the key the finger is on as the popup shows it (the only one with a focus background): Robolectric leaves the
        // popup's keys unplaced, so the boxes cannot say it
        val x = b.exactCenterX() + 25 * dp
        val under = keys.filter { it.focused }.map { it.text }
        assertEquals("one key in focus: $shown", 1, under.size)
        h.move(x, b.bottom + 3 * dp, steps = 0, lift = true)
        val typed = h.field.text.toString().ifEmpty {
            sent().lastOrNull()?.let { Regex("(?:char|key)=(.+?), states").find(it)?.groupValues?.get(1) }.orEmpty()
        }
        assertEquals("$typed of $shown: ${h.api.calls}", under.single(), typed)
    }
}
