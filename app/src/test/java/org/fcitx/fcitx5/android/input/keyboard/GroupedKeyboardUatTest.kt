/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.preedit.SyllableStrip
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * uat/06's and 07's 14- and 18-key cases on the whole keyboard over a fake engine: what the keys
 * look like and send, their popups, the code row's height per input method. What the engine
 * makes of the keys sent is KeyboardHalvesUatTest's and Keys14UatTest's (uatTest, the app's data).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class GroupedKeyboardUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    @After
    fun close() {
        harness?.close()
        EngineState.syllablesState.value = EngineState.SyllableState()
        PunctuationManager.forget()
    }

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
    private val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)

    private fun keys14(im: InputMethodEntry = KeyboardHarness.PINYIN): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys14)
        return KeyboardHarness(ThemePreset.PixelLight, im).also {
            harness = it
            it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
            it.api.enabledIme = it.api.availableIme
        }
    }

    private fun group(face: String): AltTextKeyView = KeyboardHarness.all(h.inputView).filterIsInstance<AltTextKeyView>()
        .firstOrNull { h.shown(it) && it.mainText.text.toString() == face } ?: error("no $face in ${h.texts()}")

    private fun sent() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun keypad(d: Int) = "sym=${FcitxKeyMapping.FcitxKey_KP_0 + d},"

    private fun switchTo(im: InputMethodEntry) {
        h.api.inputMethodEntryCached = im
        h.emit(HostEvent.InputMethodChanged(im))
        h.relayout()
    }

    // what fcitx tells while typing: the code row's text, a few candidates, the strip's syllables
    private fun typing(im: String, vararg syllables: String, code: String = "bn ui") {
        val typed = FormattedText(arrayOf(code), intArrayOf(0), code.length)
        h.api.inputPanelCached = HostEvent.InputPanel(typed, FormattedText.Empty, FormattedText.Empty)
        h.emit(h.api.inputPanelCached)
        h.api.candidates = arrayOf(CandidateWord("", "不", ""), CandidateWord("", "你", ""), CandidateWord("", "补", ""))
        h.emit(HostEvent.Candidates(3, h.api.candidates.toList()))
        EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true, syllables = syllables.toList(), id = 1)
        h.idle()
        h.relayout()
    }

    // fcitx's Chinese punctuation, as its addon has it on
    private fun chinesePunctuation(im: InputMethodEntry, vararg marks: Pair<String, String>) {
        runBlocking { h.api.setPunctuationMap("zh_CN", marks.map { (k, v) -> PunctuationMapEntry(k, v, "") }) }
        PunctuationManager.forget()
        val active = listOf(StatusEntry(1, "punctuation", "fcitx-punc-active", ""))
        h.api.statusAreaCached = active
        h.emit(HostEvent.StatusArea(active, im))
    }

    @Test
    @Uat("PY-84")
    fun theFourteenKeysAsIFlytekLaysThemOut() {
        keys14().start()
        // the third row's corners shown as fcitx types them
        chinesePunctuation(KeyboardHarness.PINYIN, "~" to "～", "!" to "！", "?" to "？", "," to "，", "." to "。")
        val corners = listOf("QW", "ER", "TY", "UI", "OP", "AS", "DF", "GH", "JK", "L", "ZX", "CV", "BN", "M")
            .associateWith { group(it).altText.text.toString() }
        assertEquals("1 2 3 4 5 6 7 8 9 0 ～ ！ @ ？".split(" "), corners.values.toList())
        // three rows, top to bottom, each across the keyboard
        val rows = listOf(listOf("QW", "OP"), listOf("AS", "L"), listOf("ZX", "M")).map { (a, b) -> h.box(group(a)) to h.box(group(b)) }
        assertTrue("$rows", rows.zipWithNext().all { (a, b) -> a.first.top < b.first.top })
        val width = h.box(h.keyboard).width()
        assertTrue("$rows of $width", rows.take(2).all { (a, b) -> b.right - a.left > width * 0.95 })
        val labels = h.texts()
        for (l in listOf("分词", "符", "123", "，", "。")) assertTrue("$l in $labels", l in labels)
        listOf(R.id.button_space, R.id.button_lang, R.id.button_return, R.id.button_backspace).forEach {
            assertTrue(h.shown(h.keyboard.findViewById<View>(it)))
        }
        assertFalse(labels.toString(), "中/英" in labels)
        assertFalse(KeyboardHarness.all(h.keyboard).any { h.shown(it) && it.contentDescription?.toString() == "Shift" })
        h.capture("GroupedKeyboardUatTest/keys14_chinese")
    }

    @Test
    @Uat("PY-88")
    fun aSwipeDownTypesTheCornerAndWhileTypingPicksThatCandidate() {
        keys14().start()
        h.swipe(group("QW"), 0f, group("QW").height * 1.3f)
        h.swipe(group("BN"), 0f, group("BN").height * 1.3f)
        val idle = sent()
        assertEquals(idle.toString(), 2, idle.size)
        assertTrue(idle[0], keypad(1) in idle[0])
        assertTrue(idle[1], "key=@," in idle[1])
        typing(InputMethodCatalog.PINYIN, "ni", "bu")
        h.swipe(group("ER"), 0f, group("ER").height * 1.3f)
        // the keypad's 2: the engine takes it as the second candidate (KeyboardHalvesUatTest)
        assertTrue(sent().toString(), keypad(2) in sent().last() && sent().size == 3)
    }

    @Test
    @Uat("PY-89")
    fun theLongPressIsTheGroupsLettersAroundTheCornerWithTheLettersSymbolsAbove() {
        keys14().start()
        // q and w long-press to their digit and capital alone on the full keyboard: nothing above
        assertEquals(listOf("Q", "W", "1", "q", "w"), h.longPress(group("QW")))
        // let go where it opened: the finger was on the corner
        assertEquals("1", h.field.text.toString())
        h.field.setText("")
        val asRow = h.longPress(group("AS"))
        assertEquals(listOf("A", "S", "6", "a", "s"), asRow.takeLast(5))
        val above = asRow.dropLast(5)
        assertTrue("$above", "@" in above && "*" in above && above.none { it.length == 1 && it[0].isLetterOrDigit() && it[0].code < 128 })
        h.field.setText("")
        typing(InputMethodCatalog.PINYIN, "ni", "bu")
        h.api.calls.clear()
        // w, the cell beside the corner: the harness slides by distance, two cells over lands short of q (any letter of the row goes the same way)
        h.longPress(group("QW"), "w")
        // what was typed ended first (the engine puts its first candidate in on reset), then the letter
        assertTrue(h.api.calls.toString(), "reset()" in h.api.calls)
        assertTrue(h.field.text.toString(), h.field.text.toString().endsWith("w"))
        assertTrue(sent().toString(), sent().isEmpty())
    }

    // a grouped keyboard with no 中/英 key ([probe] its letters' group): the globe asks the host for the
    // next input method, English's own full keyboard shown, and back
    private class Globe(val why: String, val make: () -> KeyboardHarness, val im: InputMethodEntry, val probe: String)

    @Test
    @Uat("PY-91", "SP-20")
    fun theGlobeGoesToTheEnglishKeyboardAndBack() = assertRows(
        listOf(
            Globe("PY-91: the fourteen keys' pinyin", { keys14() }, KeyboardHarness.PINYIN, "QW"),
            Globe("SP-20: the eighteen keys' shuangpin", ::keys18Shuangpin, shuangpin, "WE"),
        ),
        { it.why },
    ) { row ->
        harness?.close()
        row.make().start()
        assertTrue(h.texts().toString(), row.probe in h.texts() && "中/英" !in h.texts())
        h.tap(h.keyboard.findViewById<View>(R.id.button_lang))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("enumerateIme") || it == "toggleIme()" })
        switchTo(KeyboardHarness.ENGLISH)
        val english = h.texts()
        assertTrue(english.toString(), "q" in english && row.probe !in english && "中/英" !in english)
        switchTo(row.im)
        assertTrue(h.texts().toString(), row.probe in h.texts())
    }

    private fun keys18Shuangpin(): KeyboardHarness {
        // pinyin's own keyboard back to full: a row before may have left it fourteen keys, which the
        // globe's switch back would show instead
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Full)
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, ShuangpinKeyboard.Keys18)
        return KeyboardHarness(ThemePreset.PixelLight, shuangpin).also {
            harness = it
            it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, shuangpin, KeyboardHarness.ENGLISH)
            it.api.enabledIme = it.api.availableIme
        }
    }

    // a password field: androidengine puts English up for it (PR-03), the full keyboard
    @Test
    @Uat("PY-91")
    fun aPasswordOnTheFourteenKeysIsEnglishsFullKeyboard() {
        keys14().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        switchTo(KeyboardHarness.ENGLISH)
        val password = h.texts()
        assertTrue(password.toString(), "q" in password && "QW" !in password)
    }

    // an address field: the group's code to the engine (letters out, KeyboardHalvesUatTest), .com and @ as they are
    @Test
    @Uat("PY-93")
    fun anAddressFieldsDotComAndAtGoInAsTheyAre() {
        keys14().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        h.tap(group("BN"))
        h.tap(group("UI"))
        assertEquals(sent().toString(), 2, sent().size)
        h.tap(h.key(".com"))
        assertTrue(h.field.text.toString(), h.field.text.toString().endsWith(".com"))
        assertFalse("中/英" in h.texts())
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        h.tap(h.key("@"))
        assertTrue(h.field.text.toString(), h.field.text.toString().endsWith("@"))
        assertTrue(h.field.text.toString(), h.field.text.codePoints().noneMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() })
        assertFalse("中/英" in h.texts())
    }

    // the engine half (' with nothing typed is ‘, xi'an is 西安): Keys14UatTest
    @Test
    @Uat("PY-87")
    fun theSeparatorWithNothingTypedIsPairedQuotes() {
        keys14().start()
        h.tap(h.key("分词"))
        // ' as a key to fcitx, however it is spelled (its keysym is its code)
        assertTrue(sent().toString(), sent().any { "key='," in it || "char='," in it || "sym=${'\''.code}," in it })
        // fcitx's Chinese punctuation commits the key as ‘; the app pairs it, as the full keyboard's '
        h.emit(HostEvent.Commit("‘", -1))
        h.idle()
        assertEquals("‘’", h.field.text.toString())
        assertEquals(1, h.field.selectionStart)
    }

    @Test
    @Uat("PY-98")
    fun theFourteenKeysDoNotGlide() {
        prefs.set(AppPrefs.getInstance().keyboard.glideTyping, true)
        keys14().start()
        val ui = h.box(group("UI"))
        val gh = h.box(group("GH"))
        h.drag(group("BN"), ui.exactCenterX(), ui.exactCenterY(), lift = false)
        h.move(gh.exactCenterX(), gh.exactCenterY())
        h.capture("GroupedKeyboardUatTest/keys14_dragged")
        h.release()
        assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
        // a swipe on BN alone: at most its one key
        assertTrue(sent().toString(), sent().size <= 1)
    }

    @Test
    @Uat("ST-41")
    fun theCodeRowIsTallOnlyInPinyinOnTheFourteenKeys() {
        fun density() = h.inputView.resources.displayMetrics.density
        fun row() = h.box(h.inputView.findViewById<View>(R.id.code_row_text)).height() / density()
        fun area() = h.box(h.board).bottom - h.box(h.inputView.findViewById<View>(R.id.code_row_text)).top
        fun keys() = h.box(h.board).height()
        // the full keyboard first
        harness = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN)
        h.start()
        h.relayout()
        assertEquals(CodeRowHeight.THIN_DP.toFloat(), row(), 1f)
        val fullArea = area()
        h.close()
        harness = null
        keys14().start()
        h.relayout()
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        val keysHigh = keys()
        assertEquals(14f, (area() - fullArea) / density(), 1f)
        // typing: the row stays as it was
        typing(InputMethodCatalog.PINYIN, "ni", "bu")
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        for (other in listOf(KeyboardHarness.ENGLISH, wubi)) {
            switchTo(other)
            assertEquals("${other.uniqueName}", CodeRowHeight.THIN_DP.toFloat(), row(), 1f)
            assertEquals("${other.uniqueName}", fullArea.toFloat(), area().toFloat(), density())
        }
        switchTo(KeyboardHarness.PINYIN)
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        assertEquals(keysHigh, keys())
        h.close()
        harness = null
        // 双拼's 18 keys the same, its row alone
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Full)
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, ShuangpinKeyboard.Keys18)
        harness = KeyboardHarness(ThemePreset.PixelLight, shuangpin)
        h.start()
        h.relayout()
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        for (other in listOf(KeyboardHarness.ENGLISH, wubi, KeyboardHarness.PINYIN)) {
            switchTo(other)
            assertEquals("${other.uniqueName}", CodeRowHeight.THIN_DP.toFloat(), row(), 1f)
        }
        h.close()
        harness = null
        // pinyin's nine keys: the thin row (their column shows what is typed), switched to and from 双拼's 18 keys
        harness = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN, keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS, shuangpin = ImKeyboard.KEYS18))
        h.start()
        h.relayout()
        assertEquals(CodeRowHeight.THIN_DP.toFloat(), row(), 1f)
        switchTo(shuangpin)
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        switchTo(KeyboardHarness.PINYIN)
        assertEquals(CodeRowHeight.THIN_DP.toFloat(), row(), 1f)
    }

    // 微软's ; beside L: as wide as the row's other keys, A and L too
    @Test
    @Uat("SP-03")
    fun microsoftsSemicolonKeyBesideL() {
        val scheme = EngineState.shuangpinScheme
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        prefs.later { EngineState.shuangpinScheme = scheme }
        harness = KeyboardHarness(ThemePreset.PixelLight, shuangpin)
        h.start()
        val semicolon = h.keyWithId(R.id.button_semicolon)!!
        val l = h.key("l").bounds
        assertTrue("${semicolon.bounds} after $l", semicolon.bounds.left >= l.right - 2)
        val s = h.key("s").width
        for (k in listOf("a", "l")) assertEquals(k, s, h.key(k).width)
        assertEquals(s, semicolon.width)
        h.capture("GroupedKeyboardUatTest/shuangpin_microsoft")
        h.api.calls.clear()
        h.tap(semicolon)
        // the key's own ;: the engine reads it as a final or a mark (HostCasesUatTest.microsoftsSemicolonIsAFinal)
        assertTrue(sent().toString(), sent().size == 1 && "key=;," in sent().single())
    }

    private fun strip() = h.inputView.findViewById<SyllableStrip>(R.id.code_row_syllables)

    private fun stripItems() = (strip().getChildAt(0) as ViewGroup).children.filterIsInstance<TextView>().filter { it.isVisible }.toList()

    private fun syllableSent(i: Int, id: Int = 1) = "sym=${UNICODE + Keyboard.syllableKey(i, id).code},"

    // the strip's half: buttons, the 60% cap, the long code scrolled; what a pick and ⌫ make of
    // the code is KeyboardHalvesUatTest's (the app's data)
    @Test
    @Uat("PY-86")
    fun theCodeRowsSyllablesBesideTheCodeTheirWidthsAndTheirCap() {
        keys14().start()
        h.relayout()
        assertFalse("nothing typed", h.shown(strip()))
        typing(InputMethodCatalog.PINYIN, "ni", "bu", "nu", "bi")
        val dp = h.inputView.resources.displayMetrics.density
        val row = h.box(h.inputView.findViewById<View>(R.id.code_row_text))
        assertTrue(h.shown(strip()))
        assertEquals(listOf("ni", "bu", "nu", "bi"), stripItems().map { it.text.toString() })
        // at the row's right end, each button a touch wide and the row's height (32dp)
        assertTrue("${h.box(strip())} after $row", h.box(strip()).left >= row.right - 1)
        for (b in stripItems()) {
            assertTrue("${b.text} ${b.width / dp}dp", b.width / dp >= 40f)
            assertEquals("${b.text}", 32f, b.height / dp, 1f)
        }
        assertEquals(32f, row.height() / dp, 1f)
        h.capture("GroupedKeyboardUatTest/keys14_strip")
        h.api.calls.clear()
        h.tap(stripItems().first())
        assertTrue(sent().toString(), sent().size == 1 && syllableSent(0) in sent().single())
        // many syllables: held at 60% of the row, the rest scrolled to
        val many = arrayOf("zhuang", "zhuan", "zhua", "zhu", "zh", "z", "xu", "xi", "cu", "ci", "ce", "ca")
        typing(InputMethodCatalog.PINYIN, *many, code = "zx gh ui as bn gh")
        val text = h.box(h.inputView.findViewById<View>(R.id.code_row_text))
        val share = h.box(strip()).width().toFloat() / (h.box(strip()).right - text.left)
        assertTrue("$share", share in 0.55f..0.61f)
        assertTrue("more than shown", strip().getChildAt(0).width > strip().width)
        // a long code: the text part scrolled to the cursor at its end
        typing(InputMethodCatalog.PINYIN, *many, code = "zhuang bu hao kuo ni hao a ni hao a ni hao a ni hao")
        h.idle()
        h.relayout()
        val codeRow = h.inputView.findViewById<HorizontalScrollView>(R.id.code_row_text)
        val content = codeRow.getChildAt(0)
        assertTrue("${content.width} in ${codeRow.width}", content.width > codeRow.width)
        assertTrue("scrolled ${codeRow.scrollX}", codeRow.scrollX + codeRow.width >= content.width - 32 * dp)
    }

    private fun nineSp(type: Int = InputType.TYPE_CLASS_TEXT): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, ShuangpinKeyboard.NineKeys)
        return KeyboardHarness(ThemePreset.PixelLight, shuangpin).also {
            harness = it
            it.start(type)
            it.relayout()
        }
    }

    private fun column(): List<String> = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }
        .let { it.getChildAt(0) as ViewGroup }.children.filterIsInstance<TextView>().filter { it.isVisible }.map { it.text.toString() }.toList()

    private fun noPrivateUse() =
        assertTrue(h.field.text.toString(), h.field.text.codePoints().noneMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() })

    // the keyboard's half: 18 keys and 九键双拼 in an address field; the space's 15% is
    // GroupedKeyboard18Test's, the engine's letters KeyboardHalvesUatTest's
    @Test
    @Uat("SP-21")
    fun theEighteenAndNineKeysInAnAddressField() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, ShuangpinKeyboard.Keys18)
        harness = KeyboardHarness(ThemePreset.PixelLight, shuangpin)
        for ((type, tail) in listOf(InputType.TYPE_TEXT_VARIATION_URI to ".com", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS to "@")) {
            h.start(InputType.TYPE_CLASS_TEXT or type)
            h.relayout()
            assertFalse(h.texts().toString(), "中/英" in h.texts())
            h.api.calls.clear()
            h.tap(group("BN"))
            h.tap(group("IO"))
            assertEquals(sent().toString(), 2, sent().size)
            h.tap(h.key(tail))
            assertTrue(h.field.text.toString(), h.field.text.toString().endsWith(tail))
            noPrivateUse()
        }
        h.close()
        harness = null
        nineSp(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("ni", "mi"), id = 1)
        h.idle()
        val typingColumn = column()
        assertTrue("$typingColumn", "ni" in typingColumn && ".com" !in typingColumn)
        // the space's word in, nothing left typed
        h.field.setText("你")
        h.field.setSelection(1)
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = false, id = 2)
        h.idle()
        h.relayout()
        val committed = column()
        assertTrue("$committed", ".com" in committed)
        h.tap(KeyboardHarness.all(h.keyboard).first { h.shown(it) && it is TextView && it.text.toString() == ".com" })
        assertEquals("你.com", h.field.text.toString())
        noPrivateUse()
    }

    // the keyboard's half: 九键双拼's popups, its code row and the 123 page's strip; what a syllable
    // picked makes of the code is KeyboardHalvesUatTest's
    @Test
    @Uat("SP-24")
    fun nineKeyShuangpinsLongPressesCodeRowAndTheNumbersStrip() {
        val scheme = EngineState.shuangpinScheme
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        prefs.later { EngineState.shuangpinScheme = scheme }
        prefs.set(AppPrefs.getInstance().keyboard.t9Symbols, "2 ~")
        nineSp()
        chinesePunctuation(shuangpin, ";" to "；", ":" to "：", "~" to "～")
        h.relayout()
        val five = h.longPress(h.nineKey("5"))
        h.field.setText("")
        assertTrue("$five", listOf("j", "k", "l").all { l -> five.any { it.lowercase() == l } } && "：" in five && "；" in five)
        assertEquals("$five", 1, five.count { it == "；" || it == ";" })
        val two = h.longPress(h.nineKey("2"), "～")
        assertTrue("$two", "～" in two)
        // the mark to the engine, which types its Chinese ～ (KeyboardHalvesUatTest)
        assertTrue(sent().toString(), "key=~," in sent().last())
        h.field.setText("")
        fun row() = h.box(h.inputView.findViewById<View>(R.id.code_row_text)).height() / h.inputView.resources.displayMetrics.density
        assertEquals(32f, row(), 1f)
        typing(InputMethodCatalog.SHUANGPIN, "mi", "ni")
        assertFalse("the column's on the letters", h.shown(strip()))
        assertTrue(column().toString(), "ni" in column())
        h.tap(h.key("123"))
        h.relayout()
        assertTrue("the strip on 123", h.shown(strip()))
        assertEquals(listOf("mi", "ni"), stripItems().map { it.text.toString() })
        h.api.calls.clear()
        h.tap(stripItems().first())
        assertTrue(sent().toString(), sent().size == 1 && syllableSent(0) in sent().single())
        EngineState.syllablesState.value = EngineState.SyllableState()
        h.close()
        // pinyin's nine keys the same symbols
        harness = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).also { it.start() }
        chinesePunctuation(KeyboardHarness.PINYIN, "~" to "～")
        h.relayout()
        assertTrue("～" in h.longPress(h.nineKey("2")))
        h.close()
        // 拼音 on the full keyboard keeps its thin row
        harness = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).also { it.start() }
        typing(InputMethodCatalog.PINYIN, code = "ni")
        assertEquals(18f, row(), 1f)
    }
}
