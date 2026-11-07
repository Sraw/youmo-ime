/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.widget.ImageView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
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
 * uat/10-tables.md's keyboard half (the engine's is TablesUatTest in :lib:ime-eval): what a table's
 * letters keyboard shows as the engine tells it (EngineState.tableKeyboard): its roots, its 拼 key,
 * 确定, the code keys its face has nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class TablesKeysUatTest {

    private var h: KeyboardHarness? = null

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", "", name.take(1), "zh_CN", "androidengine", false)

    // the keyboard of [im], as the engine tells it after its first answer
    private fun harness(im: String, name: String, state: TableKeyboard?): KeyboardHarness =
        KeyboardHarness(im = entry(im, name)).also { h = it }.start().apply { told(state) }

    // the letter key whose corner shows [letter] (a table's roots on its face)
    private fun letterKey(h: KeyboardHarness, letter: String) = KeyboardHarness.all(h.inputView).filterIsInstance<AltTextKeyView>()
        .first { h.shown(it) && it.altText.text.toString() == letter }

    // the command icons drawn on [letter]'s key: its shortcut's mark
    private fun KeyboardHarness.shortcutIcons(letter: String) = KeyboardHarness.all(letterKey(this, letter)).count { it is ImageView && shown(it) }

    @get:Rule
    val prefs = PrefsRule()

    private fun KeyboardHarness.told(state: TableKeyboard?) {
        EngineState.tableKeyboardState.value = state
        idle()
        relayout()
    }

    @After
    fun close() {
        h?.close()
        EngineState.tableKeyboardState.value = null
    }

    private fun KeyboardHarness.switchTo(im: InputMethodEntry) {
        api.inputMethodEntryCached = im
        emit(HostEvent.InputMethodChanged(im))
    }

    private fun KeyboardHarness.view(id: Int): View = inputView.findViewById(id)

    private fun KeyboardHarness.typed(code: String, auxUp: String = "") {
        emit(HostEvent.InputPanel(FormattedText(arrayOf(code), intArrayOf(0), code.length), FormattedText(arrayOf(auxUp), intArrayOf(0), -1), FormattedText.Empty))
    }

    private fun KeyboardHarness.sentSyms() = api.calls.filter { it.startsWith("sendKey(sym=") && "up=false" in it }

    private val cangjie = TableKeyboard(InputMethodCatalog.CANGJIE, setOf('*'), false, KeyLabels.NAMES, TableRoots.CANGJIE)

    private fun wubi(labels: KeyLabels = KeyLabels.NONE, on: Boolean = false) =
        TableKeyboard(InputMethodCatalog.WUBI, emptySet(), on, labels, TableRoots.WUBI86)

    @Test
    @Uat("TBL-46")
    fun cangjieKeysShowItsRoots() {
        val h = harness(InputMethodCatalog.CANGJIE, "仓颉", cangjie)
        val labels = h.texts()
        for (root in listOf("日", "月", "金", "難", "重")) assertTrue("$root in $labels", root in labels)
        assertFalse("no capital: $labels", labels.any { it.length == 1 && it[0] in 'A'..'Z' })
        // its letters in the corners
        assertTrue(labels.toString(), "a" in labels && "z" in labels)
        assertTrue(h.shown(h.view(R.id.button_star)))
        assertFalse(h.shown(h.view(R.id.button_caps)))
        h.capture("TablesKeysUatTest/cangjie_roots", h.inputView)
        // off: the letters, no * key (S's swipe types it)
        h.told(cangjie.copy(labels = KeyLabels.NONE))
        assertTrue(h.texts().toString(), "a" in h.texts() && "日" !in h.texts())
        assertFalse(h.shown(h.view(R.id.button_star)))
    }

    @Test
    @Uat("TBL-47")
    fun theLookUpKeyIsInShiftsPlaceAndDarkWhileOn() {
        val h = harness(InputMethodCatalog.WUBI, "五笔", wubi())
        assertFalse(h.shown(h.view(R.id.button_caps)))
        assertTrue(h.shown(h.view(R.id.button_lookup)))
        assertFalse(h.shown(h.view(R.id.button_lookup_on)))
        h.tap(h.view(R.id.button_lookup))
        assertEquals(h.api.calls.toString(), "sym=${UNICODE + Keyboard.LOOKUP}", h.sentSyms().last().substringAfter('(').substringBefore(','))
        h.told(wubi(on = true))
        assertFalse(h.shown(h.view(R.id.button_lookup)))
        assertTrue(h.shown(h.view(R.id.button_lookup_on)))
        h.typed("hao", auxUp = "拼")
        h.capture("TablesKeysUatTest/wubi_lookup_on", h.inputView)
        // pressed again, off: the same key sent, shown light
        h.tap(h.view(R.id.button_lookup_on))
        assertEquals(2, h.sentSyms().count { "${UNICODE + Keyboard.LOOKUP}" in it })
        // not a table: Shift, no 拼
        h.switchTo(entry(InputMethodCatalog.PINYIN, "拼音"))
        assertTrue(h.shown(h.view(R.id.button_caps)))
        assertFalse(h.shown(h.view(R.id.button_lookup)) || h.shown(h.view(R.id.button_lookup_on)))
    }

    @Test
    @Uat("TBL-48")
    fun returnSaysConfirmWhileATableHasACode() {
        val h = harness(InputMethodCatalog.WUBI, "五笔", wubi())
        assertTrue(h.shown(h.view(R.id.button_return)))
        assertFalse(h.shown(h.view(R.id.button_confirm)))
        h.typed("ggg")
        assertTrue(h.shown(h.view(R.id.button_confirm)))
        assertFalse(h.shown(h.view(R.id.button_return)))
        assertTrue(h.texts().toString(), h.service.getString(R.string.key_confirm) in h.texts())
        h.capture("TablesKeysUatTest/wubi_confirm", h.inputView)
        h.tap(h.view(R.id.button_confirm))
        assertTrue(h.api.calls.toString(), h.sentSyms().last().startsWith("sendKey(sym=${0xff0d},"))
        h.typed("")
        assertTrue(h.shown(h.view(R.id.button_return)))
    }

    @Test
    @Uat("TBL-49")
    fun wubiKeysShowNamesOrAllRoots() {
        val h = harness(InputMethodCatalog.WUBI, "五笔", wubi())
        assertTrue(h.texts().toString(), "g" in h.texts())
        h.told(wubi(KeyLabels.NAMES))
        val names = h.texts()
        for (n in listOf("王", "土", "大", "木", "工", "纟", "z")) assertTrue("$n in $names", n in names)
        assertEquals("a's select all marked beside its name", 1, h.shortcutIcons("a"))
        h.capture("TablesKeysUatTest/wubi_names", h.inputView)
        h.told(wubi(KeyLabels.ROOTS))
        assertTrue(h.texts().toString(), "王龶戋\n五一" in h.texts())
        // the roots fill the key: no shortcut drawn over them, its swipe up selecting all the same
        assertEquals(0, h.shortcutIcons("a"))
        h.capture("TablesKeysUatTest/wubi_roots", h.inputView)
        h.field.setText("你好")
        val a = letterKey(h, "a")
        h.swipe(a, 0f, -a.height.toFloat())
        assertEquals(0 to 2, h.field.selectionStart to h.field.selectionEnd)
        // held: its roots, large, nothing typed
        val before = h.api.calls.size
        val shown = h.longPress(h.key("王龶戋\n五一"))
        assertTrue(shown.toString(), "王 龶 戋 五 一" in shown)
        assertTrue(h.api.calls.drop(before).toString(), h.api.calls.drop(before).none { it.startsWith("sendKey") && "up=false" in it })
    }

    @Test
    @Uat("TBL-51")
    fun wubisAllRootsAreItsNamesAtCompact() {
        val keyboard = AppPrefs.getInstance().keyboard
        fun level(portrait: Int, landscape: Int) {
            prefs.set(keyboard.keyboardHeightPercent, portrait)
            prefs.set(keyboard.keyboardHeightPercentLandscape, landscape)
        }
        val h = harness(InputMethodCatalog.WUBI, "五笔", wubi(KeyLabels.NAMES))
        level(20, 34)
        h.idle()
        h.relayout()
        val names = h.texts()
        val namesMarks = h.shortcutIcons("a")
        h.told(wubi(KeyLabels.ROOTS))
        // 紧凑: the names, as with 键名 set, its marks too
        assertEquals(names, h.texts())
        assertTrue(names.toString(), "王" in names && "王龶戋\n五一" !in names)
        assertEquals(namesMarks, h.shortcutIcons("a"))
        h.capture("TablesKeysUatTest/wubi_roots_compact", h.inputView)
        // held: its symbols, not the roots large
        val shown = h.longPress(h.key("王"))
        assertFalse(shown.toString(), "王 龶 戋 五 一" in shown)
        h.idle()
        // out of 紧凑, all the roots again: the setting was kept
        level(38, 60)
        h.idle()
        h.relayout()
        assertTrue(h.texts().toString(), "王龶戋\n五一" in h.texts())
        assertEquals(0, h.shortcutIcons("a"))
    }

    @Test
    @Uat("TBL-50")
    fun erbisSemicolonHasAKey() {
        val erbi = TableKeyboard(InputMethodCatalog.ERBI, setOf(';', '\'', ',', '.', '/', '*'), false, KeyLabels.NONE, null)
        val h = harness(InputMethodCatalog.ERBI, "二笔", erbi)
        val semicolon = h.view(R.id.button_semicolon)
        assertTrue(h.shown(semicolon))
        // its * a swipe types (S), no key of its own
        assertFalse(h.shown(h.view(R.id.button_star)))
        h.capture("TablesKeysUatTest/erbi_semicolon", h.inputView)
        h.tap(semicolon)
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") && ";" in it })
        // 五笔's codes are letters
        h.switchTo(entry(InputMethodCatalog.WUBI, "五笔"))
        h.told(wubi())
        assertFalse(h.shown(h.view(R.id.button_semicolon)))
    }

    // the halves split on a phone held sideways: the key after L in the right half, the room
    // between them giving way, the keyboard no wider (review b5)
    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun theSplitHalvesHaveTheKeyAfterLToo() {
        ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Always)
        try {
            val erbi = TableKeyboard(InputMethodCatalog.ERBI, setOf(';', '\'', ',', '.', '/', '*'), false, KeyLabels.NONE, null)
            val h = harness(InputMethodCatalog.ERBI, "二笔", erbi)
            fun left(v: View) = IntArray(2).also(v::getLocationInWindow)[0]
            fun left(k: ShownKey) = k.bounds.left
            val semicolon = h.view(R.id.button_semicolon)
            val l = h.key("l")
            assertTrue(h.shown(semicolon))
            assertTrue("${left(semicolon)} after ${left(l)}", left(semicolon) >= left(l) + l.width - 1)
            assertTrue(left(semicolon) + semicolon.width <= h.service.resources.displayMetrics.widthPixels + 1)
            // the right half's other rows end where this one does
            val p = h.key("p")
            assertEquals((left(p) + p.width).toFloat(), (left(semicolon) + semicolon.width).toFloat(), 2f)
            h.capture("TablesKeysUatTest/erbi_split", h.inputView)
            // 仓颉's * there as well
            h.switchTo(entry(InputMethodCatalog.CANGJIE, "仓颉"))
            h.told(cangjie)
            assertTrue(h.shown(h.view(R.id.button_star)))
            assertFalse(h.shown(semicolon))
        } finally {
            ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Auto)
        }
    }

    // 五笔's 键名, then 仓颉 told before the switch shows: 仓颉's roots, not 五笔's names (review b5)
    @Test
    @Uat("TBL-49")
    fun anotherTablesRootsRelabelTheKeys() {
        val h = harness(InputMethodCatalog.WUBI, "五笔", wubi(KeyLabels.NAMES))
        assertTrue(h.texts().toString(), "王" in h.texts())
        EngineState.tableKeyboardState.value = cangjie
        h.switchTo(entry(InputMethodCatalog.CANGJIE, "仓颉"))
        h.idle()
        h.relayout()
        val labels = h.texts()
        assertTrue(labels.toString(), "日" in labels && "王" !in labels)
    }
}
