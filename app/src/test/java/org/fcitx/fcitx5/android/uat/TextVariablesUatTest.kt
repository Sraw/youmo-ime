/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyClock
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.Op
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import java.util.SimpleTimeZone

/** Long press items and swipe texts that fill in the date and time: shown the value now, typed as picked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class TextVariablesUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null
    private val h: KeyboardHarness get() = made ?: KeyboardHarness(TestThemes.PixelLight).also { made = it }

    @After
    fun close() {
        made?.close()
        KeyClock.clock = JvmClock
    }

    // in UTC+8
    private class SetClock(var at: Long) : LearnClock {
        override fun now() = at
        override fun offset(at: Long) = OFFSET
    }

    private fun time(day: Int, hour: Int, minute: Int, second: Int = 0) =
        Calendar.getInstance(SimpleTimeZone(OFFSET, "t")).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, minute, second) }.timeInMillis

    // every test here but anItemOfVariablesShowsAndTypesToday runs on a set clock: its values known, none turns mid-test
    private fun setClock(at: Long) = SetClock(at).also { KeyClock.clock = it }

    // [typed] one of its values about now (a minute may turn while the test runs)
    private fun now(template: String) = TextVariables.expand(template, Calendar.getInstance())
    private fun assertTypedNow(template: String, before: String, typed: String) =
        assertTrue("$typed of $template", typed == before || typed == now(template))

    private fun slot(name: String, up: Boolean, slot: SwipeSlot) {
        val pref = AppPrefs.getInstance().internal.keySwipes
        val stored = pref.getValue().let { if (it.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(it) }
        prefs.set(pref, stored.with(name, up, slot).serialize())
    }

    private val date = "\$month月\$day日"

    @Test
    @Uat("KB-93")
    fun anItemOfVariablesShowsAndTypesToday() {
        prefs.setList("full/q", listOf(date, "1"), marks = mapOf(date to ItemMark.Variables))
        prefs.setWays(up = true, down = true)
        h.start()
        val value = now(date)
        val q = h.key("q") as SceneShownKey
        assertTrue(q.drawnTexts.toString(), value.take(2) + "…" in q.drawnTexts)
        val shown = h.longPress(h.key("q"), pick = value)
        assertEquals(listOf(value, "1"), shown)
        assertTypedNow(date, value, h.field.text.toString())
    }

    @Test
    @Uat("KB-94")
    fun aSwipesTextOfVariablesTypesTheTimeItIsSwiped() {
        val clock = setClock(time(14, 9, 4))
        prefs.setWays(up = true, down = true)
        slot("full/w", up = false, SwipeSlot.Text("\${fullhour}:\$minute", variables = true))
        h.start()
        clock.at = time(14, 9, 5)
        h.swipe(h.key("w"), 0f, h.key("w").height * 1.3f)
        assertEquals("09:05", h.field.text.toString())
    }

    // not marked, typed as written; marked, never in capitals nor turned into Chinese punctuation
    @Test
    @Uat("KB-95")
    fun withoutTheMarkAsWrittenWithItAsFilledIn() {
        val dotted = "x\$month.\$day"
        setClock(time(14, 9, 5))
        prefs.setList("full/q", listOf("\$year", dotted, "1"), marks = mapOf(dotted to ItemMark.Variables))
        h.start()
        h.chinesePunctuation()
        h.relayout()
        val value = "x11.14"
        h.longPress(h.key("q"), pick = "\$year").let { assertTrue(it.toString(), it.containsAll(listOf("\$year", value))) }
        assertEquals("\$year", h.field.text.toString())
        h.field.setText("")
        h.tap(h.describedKey("Shift"))
        val shifted = h.longPress(h.key("Q"), pick = value)
        assertTrue(shifted.toString(), "\$YEAR" in shifted && value in shifted)
        assertEquals(value, h.field.text.toString())
    }

    @Test
    @Uat("KB-97")
    fun anItemOfVariablesFirstIsTypedBySwipingUp() {
        val clock = setClock(time(14, 12, 0))
        prefs.setList("full/q", listOf("1", date), first = date, marks = mapOf(date to ItemMark.Variables))
        prefs.setWays(up = true, down = true)
        h.start()
        clock.at = time(15, 0, 1)
        h.swipe(h.key("q"), 0f, -h.key("q").height * 1.3f)
        assertEquals("11月15日", h.field.text.toString())
    }

    // a long value in the long press shrunk to the least size, then cut with 「…」, said whole
    @Test
    @Uat("KB-93")
    fun aLongValueIsCutInItsCellAndSaidWhole() {
        // within the 64 characters an item is stored with
        val long = "\$year年\$month月\$day日 星期\$weekday_cn " + "很长的一段文字".repeat(4)
        setClock(time(14, 9, 5))
        prefs.setList("full/q", listOf(long, "1"), marks = mapOf(long to ItemMark.Variables))
        h.start()
        val value = "2026年11月14日 星期六 " + "很长的一段文字".repeat(4)
        val f = h.finger(h.key("q"), h.at(h.key("q")))
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        val cells = KeyboardHarness.all(h.inputView).filterIsInstance<PopupSceneView>().filter { h.shown(it) }
            .flatMap { it.scene?.keys.orEmpty() }.toList()
        val cell = cells.singleOrNull { it.label == value } ?: error("$value not among ${cells.map { it.label to it.a11y }}")
        f.up(h.at(h.key("q")))
        assertEquals(value, cell.a11y)
        val drawn = cell.ops(false).filterIsInstance<Op.Text>().single().text
        assertTrue(drawn, drawn.endsWith("…") && value.startsWith(drawn.removeSuffix("…")))
    }

    // a long press opened before midnight and picked after it types the new day, whatever it showed
    @Test
    @Uat("KB-93")
    fun anItemPickedAfterMidnightTypesTheNewDay() {
        val clock = setClock(time(14, 23, 59, 50))
        prefs.setList("full/q", listOf(date, "1"), marks = mapOf(date to ItemMark.Variables))
        h.start()
        val shown = h.longPress(h.key("q"), pick = "11月14日") { clock.at = time(15, 0, 0, 10) }
        assertEquals(listOf("11月14日", "1"), shown)
        assertEquals("11月15日", h.field.text.toString())
    }

    @Test
    @Uat("KB-93")
    fun shownAgainTheCornerIsOfTheMinuteNow() {
        val day = "\$day日"
        val clock = setClock(time(14, 22, 0))
        prefs.setList("full/q", listOf(day, "1"), marks = mapOf(day to ItemMark.Variables))
        prefs.setWays(up = true, down = true)
        h.start()
        assertTrue((h.key("q") as SceneShownKey).drawnTexts.toString(), "14…" in (h.key("q") as SceneShownKey).drawnTexts)
        h.hide()
        clock.at = time(15, 8, 0)
        h.restart()
        val q = h.key("q") as SceneShownKey
        assertTrue(q.drawnTexts.toString(), "15…" in q.drawnTexts)
    }

    // the catch-up alone (KeyboardWindow.onStartInput): keys drawn in a minute gone made anew, though a long press asked
    // for this minute meanwhile; in the minute drawn, the scene left as it is
    @Test
    @Uat("KB-93")
    fun theCatchUpRedrawsOnlyForANewMinute() {
        val day = "\$day日"
        val clock = setClock(time(14, 23, 59, 0))
        prefs.setList("full/q", listOf(day, "1"), marks = mapOf(day to ItemMark.Variables))
        prefs.setWays(up = true, down = true)
        h.start()
        val q = h.key("q") as SceneShownKey
        val board = generateSequence(q.canvas.parent) { it.parent }.filterIsInstance<BaseKeyboard>().first()
        assertTrue(q.drawnTexts.toString(), "14…" in q.drawnTexts)
        clock.at = time(15, 0, 0, 30)
        h.longPress(h.key("q"))
        board.catchUpMinute()
        assertTrue(q.drawnTexts.toString(), "15…" in q.drawnTexts)
        val drawn = q.canvas.scene
        clock.at = time(15, 0, 0, 50)
        board.catchUpMinute()
        assertSame(drawn, q.canvas.scene)
    }

    // the keyboard's rows counted draw nothing: keys of a minute gone are still made anew by the catch-up after it
    @Test
    fun rowsCountedInANewMinuteLeaveTheCatchUpToDrawIt() {
        val day = "\$day日"
        val clock = setClock(time(14, 23, 59))
        prefs.setList("full/q", listOf(day, "1"), marks = mapOf(day to ItemMark.Variables))
        prefs.setWays(up = true, down = true)
        h.start()
        val q = h.key("q") as SceneShownKey
        val board = generateSequence(q.canvas.parent) { it.parent }.filterIsInstance<BaseKeyboard>().first()
        assertTrue(q.drawnTexts.toString(), "14…" in q.drawnTexts)
        clock.at = time(15, 0, 0)
        KeyboardWindow.rowUnits(board, ThemeManager.shownLook(TestThemes.PixelLight))
        board.catchUpMinute()
        assertTrue(q.drawnTexts.toString(), "15…" in q.drawnTexts)
    }

    private companion object {
        const val OFFSET = 8 * 3_600_000
    }
}
