/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.text.InputType
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class NumberColumnTest {

    private val h = KeyboardHarness()

    private val pref get() = AppPrefs.getInstance().internal.scrollLists

    @After
    fun close() {
        h.close()
        pref.setValue(ScrollListStore.HEADER)
    }

    private fun numbers() {
        h.start()
        h.tap(h.key("123"))
    }

    private fun columnView(): ScrollView = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }

    private fun column(): List<TextView> = (columnView().getChildAt(0) as ViewGroup).children.filterIsInstance<TextView>().filter { it.isVisible }.toList()

    private fun labels() = column().map { it.text.toString() }

    private fun item(text: String) = column().firstOrNull { it.text.toString() == text } ?: error("no $text in ${labels()}")

    private fun sent(sym: Int) = h.api.calls.any { it.startsWith("sendKey") && "sym=$sym," in it }

    @Test
    @Uat("PAN-36")
    fun theFullPadsColumnShowsTheOperatorsFirst() {
        numbers()
        assertEquals(listOf("+", "-", "*", "/", "%", ",", ":", "(", ")", "#"), labels())
        // four in view: each item a quarter of the column inside its padding
        val v = columnView()
        val inside = v.height - v.paddingTop - v.paddingBottom
        assertEquals(inside / 4f, column().first().height.toFloat(), 1f)
    }

    @Test
    @Uat("PAN-36", "PAN-37")
    fun aTapTypesAsThePadsKeysDid() {
        numbers()
        for ((text, sym) in listOf("+" to FcitxKeyMapping.FcitxKey_KP_Add, "-" to FcitxKeyMapping.FcitxKey_KP_Subtract, "*" to FcitxKeyMapping.FcitxKey_KP_Multiply, "/" to FcitxKeyMapping.FcitxKey_KP_Divide, "," to KP_SEPARATOR)) {
            h.api.calls.clear()
            h.tap(item(text))
            assertTrue("$text: ${h.api.calls}", sent(sym))
        }
        h.api.calls.clear()
        h.tap(item("%"))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("commit") && "%" in it } || h.field.text.endsWith("%"))
    }

    @Test
    @Uat("PAN-37")
    fun aPlusIsThePadsKey() {
        // what the pad's key was: the keypad's, with NumLock, so the engine takes a candidate first
        assertEquals(
            KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(FcitxKeyMapping.FcitxKey_KP_Add), NumLockState),
            ScrollColumn.typed(ScrollDefaults.NUMBER, ScrollItem.Text("+"), english = false, marks = emptyMap()),
        )
        assertEquals(KeyAction.CommitAction("#"), ScrollColumn.typed(ScrollDefaults.PHONE, ScrollItem.Text("#"), english = false, marks = emptyMap()))
        // a mark of the nine keys' as the input method types it
        assertEquals(KeyAction.CommitAction("？", punctuation = true), ScrollColumn.typed(ScrollDefaults.NINE, ScrollItem.Text("?"), english = false, marks = mapOf("?" to "？")))
        assertEquals(KeyAction.CommitAction("?"), ScrollColumn.typed(ScrollDefaults.NINE, ScrollItem.Text("?", asIs = true), english = false, marks = mapOf("?" to "？")))
    }

    // the first finger down owns its target: each order goes through another one's touch handling
    private fun bothType(first: () -> Unit, second: () -> Rect, key: Int) {
        numbers()
        try {
            h.api.calls.clear()
            first()
            val at = second()
            h.secondFingerThenLift(at.exactCenterX(), at.exactCenterY())
            assertTrue(h.api.calls.toString(), sent(FcitxKeyMapping.FcitxKey_KP_Add))
            assertTrue(h.api.calls.toString(), sent(key))
        } finally {
            // put away: the next row's field opens on the letters again
            h.hide()
        }
    }

    @Test
    @Uat("PAN-37")
    fun aFingerOnTheColumnAndOneOnAKeyEachType() = assertRows(
        "the column first: a key's finger is not the column's second" to {
            bothType({ h.press(item("+")) }, { h.key("1").bounds }, FcitxKeyMapping.FcitxKey_KP_1)
        },
        "a key first: the column's finger is not the key's second" to {
            bothType({ h.press(h.key("5")) }, { h.box(item("+")) }, FcitxKeyMapping.FcitxKey_KP_5)
        },
    )

    @Test
    @Uat("PAN-37")
    fun twoFingersOnTheColumnTypeNothing() {
        numbers()
        h.api.calls.clear()
        h.press(item("+"))
        val minus = h.box(item("-"))
        h.secondFingerThenLift(minus.exactCenterX(), minus.exactCenterY())
        assertEquals(emptyList<String>(), h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") })
        assertEquals("", h.field.text.toString())
    }

    @Test
    @Uat("PAN-37")
    fun aSecondFingerOnTheColumnLeavesItsDragGoingOn() {
        numbers()
        h.api.calls.clear()
        val plus = h.box(item("+"))
        h.drag(plus, plus.exactCenterX(), plus.exactCenterY() - plus.height(), lift = false)
        val dragged = columnView().scrollY.also { assertTrue(it > 0) }
        val column = h.box(columnView())
        h.secondFingerThenLift(column.exactCenterX(), column.exactCenterY()) {
            h.move(plus.exactCenterX(), plus.exactCenterY() - 2 * plus.height())
        }
        assertTrue(columnView().scrollY > dragged)
        assertEquals(emptyList<String>(), h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") })
        assertEquals("", h.field.text.toString())
    }

    private fun scrolledToEnd(): Int {
        columnView().scrollTo(0, Int.MAX_VALUE / 2)
        h.idle()
        return columnView().scrollY.also { assertTrue(it > 0) }
    }

    @Test
    @Uat("PAN-36")
    fun anotherFieldShowsTheColumnFromItsStart() {
        numbers()
        scrolledToEnd()
        // the number keyboard kept for the app's next field, the same list in it
        h.another()
        assertEquals("+", labels().first())
        assertEquals(0, columnView().scrollY)
    }

    @Test
    @Uat("PAN-36")
    fun aNumberKeyboardKeptForLaterOpensFromItsStartInAnotherField() {
        numbers()
        scrolledToEnd()
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        h.start()
        h.tap(h.key("123"))
        assertEquals(0, columnView().scrollY)
    }

    // restartInput's new EditorInfo
    private fun restartedKeeps(fieldId: Int) {
        h.start(fieldId = fieldId)
        try {
            h.tap(h.key("123"))
            val at = scrolledToEnd()
            h.restart()
            assertEquals(at, columnView().scrollY)
        } finally {
            h.hide()
        }
    }

    @Test
    @Uat("PAN-36")
    fun aFieldRestartedKeepsWhereTheColumnWas() = assertRows(
        "an app's field, told by its id" to { restartedKeeps(7) },
        "a WebView's field without an id: the restart is all that says it is the same" to { restartedKeeps(-1) },
    )

    @Test
    @Uat("PAN-36")
    fun theSameFieldLeftAndComeBackToKeepsWhereTheColumnWas() {
        h.start(fieldId = 7)
        h.tap(h.key("123"))
        val at = scrolledToEnd()
        // the keyboard kept for later shows it there
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        h.hide()
        h.start(fieldId = 7)
        h.tap(h.key("123"))
        assertEquals(at, columnView().scrollY)
    }

    @Test
    @Uat("PAN-38")
    fun theColumnSitsOnItsSlot() {
        numbers()
        val canvas = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyboardCanvasView>().first { h.shown(it) }
        val (_, slot) = canvas.slots(setOf("scroll")).single()
        val at = IntArray(2).also(canvas::getLocationInWindow)
        slot.offset(at[0], at[1])
        assertEquals(slot, h.box(columnView()))
    }

    @Test
    fun aChangedListIsShownAtOnce() {
        numbers()
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Text("="), ScrollItem.Text("+"))).serialize())
        h.idle()
        assertEquals(listOf("=", "+"), labels())
    }

    @Test
    @Uat("KB-118")
    fun aCommandInTheColumnRunsOnATap() {
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Command(KeyCommand.SelectToStart.key))).serialize())
        h.start(prefill = { it.setText("hello world"); it.setSelection(5) })
        h.tap(h.key("123"))
        h.tap(column().single())
        assertEquals(0 to 5, h.field.let { minOf(it.selectionStart, it.selectionEnd) to maxOf(it.selectionStart, it.selectionEnd) })
        assertEquals("nothing typed", "hello world", h.field.text.toString())
    }

    @Test
    fun aSceneRemadeForTheLockLeavesTheColumnAsItWas() {
        numbers()
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<NumberKeyboard>().first { h.shown(it) }
        val updates = board.columns.updates
        board.locked = true
        h.relayout()
        // a scene remade (the lock, Shift, each letter on the English nine keys) reads no list again: no menus nor TalkBack names rebuilt
        assertEquals(updates, board.columns.updates)
        assertEquals("+", labels().first())
    }

    @Test
    fun aCommandReadingTheFieldIsNotRunInAPassword() {
        val copy = ScrollItem.Command(KeyCommand.Copy.key)
        assertEquals(KeyAction.ShortcutAction(KeyShortcut.Command(KeyCommand.Copy)), ScrollColumn.typed(ScrollDefaults.NUMBER, copy, english = false, marks = emptyMap()))
        assertNull(ScrollColumn.typed(ScrollDefaults.NUMBER, copy, english = false, marks = emptyMap(), password = true))
    }

    @Test
    @Uat("AX-24")
    fun theLockIsDrawnAsThePanelsIsAndReadAsThePads() {
        numbers()
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<NumberKeyboard>().first { h.shown(it) }
        assertTrue(h.keys().mapNotNull { it.description }.toString(), h.keys().any { it.description == h.service.getString(R.string.number_pad_lock) })
        board.locked = true
        h.relayout()
        assertTrue(h.keys().mapNotNull { it.description }.toString(), h.keys().any { it.description == h.service.getString(R.string.number_pad_unlock) })
        // its own keys' states kept: the digits still drawn
        assertTrue(h.texts().toString(), h.texts().containsAll((0..9).map { it.toString() }))
        board.locked = false
        h.relayout()
        assertTrue(h.keys().any { it.description == h.service.getString(R.string.number_pad_lock) })
    }

    @Test
    fun aMarkedPlaceIsTheColumnsNotAKey() {
        numbers()
        val box: Rect = h.box(columnView())
        assertTrue(h.keys().none { it.enabled && Rect.intersects(it.bounds, box) })
    }

    private companion object {
        // fcitx's KP_Separator, which FcitxKeyMapping lacks
        const val KP_SEPARATOR = 0xffac
    }
}
