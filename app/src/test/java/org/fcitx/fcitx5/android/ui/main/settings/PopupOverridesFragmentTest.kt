/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.KeyListsCache
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

class PopupOverridesFragmentTest {

    // a 14-key code typed in the editor would be typed as that key
    @Test
    fun aKeyCodeIsFoundInWhatIsTyped() {
        val bn = g14("bn")
        assertEquals(bn, keyCodeIn("x $bn y"))
        // these older codes are text now
        assertNull(keyCodeIn("① b ⑴ ⒝ ⒉"))
        assertEquals(nineSp("2"), keyCodeIn("b ${nineSp("2")}"))
        assertEquals("\uF8C1", keyCodeIn("b \uF8C1"))
        // a supplementary tap code is two chars, one item
        val tap = String(Character.toChars(0xF0200))
        assertEquals(tap, keyCodeIn("x $tap"))
        // a code within longer text is text
        assertNull(keyCodeIn("a${nineSp("2")}"))
    }

    @Test
    fun aPrivateUseCodeIsShownByItsNumber() {
        assertEquals("U+F8C1", shownCode("\uF8C1"))
        assertEquals("U+F8DB", shownCode(g14("bn")))
        assertEquals("⒜", shownCode("⒜"))
        assertEquals("U+F0200", shownCode(String(Character.toChars(0xF0200))))
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class)
    class Dialog : EditorDialog() {

        @Test
        @Uat("ST-22")
        fun aKeyCodeAloneIsRefusedAndTheDialogStays() {
            val pref = AppPrefs.getInstance().internal.keyLists
            val before = pref.getValue()
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            KeyboardHarness.all(st.activity.window.decorView)
                .first { it.contentDescription?.toString()?.substringBefore(',') == "q" && it.hasOnClickListeners() }
                .performClick()
            st.idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val field = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().first()
            field.setText("\uF8C1")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertTrue(dialog.isShowing)
            assertNotNull(field.error)
            assertEquals(before, pref.getValue())
        }

        private val slotsPref get() = AppPrefs.getInstance().internal.keySwipes
        private fun slots() = slotsPref.getValue().let { if (it.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(it) }

        private fun open() {
            // put back after, passed or failed: a later swipe test expects the presets
            prefs.set(slotsPref, slotsPref.getValue())
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
            val k = AppPrefs.getInstance().keyboard
            listOf(k.swipeUpPosition, k.swipeDownPosition).forEach { prefs.set(it, it.getValue()) }
            listOf(k.swipeUp, k.swipeDown).forEach { prefs.set(it, it.getValue()) }
            st.activity
            st.open(SettingsRoute.PopupOverrides)
        }

        private fun click(title: Int) = clickText(title)

        private fun pick(index: Int) {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.listView.performItemClick(null, index, 0)
            st.idle()
        }

        // through the window, not performClick: caps once kept the touch and no dialog came
        private fun tap(v: View) {
            // the cards above take the top of a small screen
            v.requestRectangleOnScreen(android.graphics.Rect(0, 0, v.width, v.height), true)
            st.idle()
            val at = IntArray(2).also(v::getLocationInWindow)
            val x = at[0] + v.width / 2f
            val y = at[1] + v.height / 2f
            val root = st.activity.window.decorView
            root.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            root.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0))
            st.idle()
        }

        // a keyboard's card says its name as its text, a cap does not
        private fun firstCap() = KeyboardHarness.all(st.activity.window.decorView)
            .first { it is FrameLayout && it.hasOnClickListeners() && it.contentDescription != null }

        @Test
        fun aTappedKeyOpensItsDialogOnEveryTab() {
            open()
            val tabs = KeyShortcutPages.all
            assertEquals(8, tabs.size)
            for (page in tabs) {
                click(PopupOverridesFragment.pageTitle(page))
                val cap = firstCap()
                tap(cap)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue("${page.form}: ${cap.contentDescription}", dialog.isShowing)
                assertEquals(page.form, 8, KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<RadioButton>().count())
                dialog.dismiss()
                st.idle()
            }
        }

        // j's 下滑 at 底部 (360 dp, 紧凑, one-handed) by the engine's fixed measurer: a sign upright, shortened turned
        @Test
        @Config(qualifiers = "w360dp-h780dp-xxhdpi")
        @Uat("ST-106")
        fun eachWaysNoteIsWhatTheKeyboardMakesOfIt() {
            val k = AppPrefs.getInstance().keyboard
            prefs.set(k.swipeDownPosition, KeyMarkPosition.Bottom)
            prefs.set(k.keyboardHeightPercent, 20)
            prefs.set(k.keyboardHeightPercentLandscape, 34)
            prefs.setStyleDefault()
            prefs.setStyle(org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle.ONE_HANDED, landscape = false, side = org.fcitx.fcitx5.android.input.keyboard.OneHanded.Right)
            prefs.set(slotsPref, SwipeSlots.Default.with("full/j", false, SwipeSlot.Text("现在怎么样了")).serialize())
            org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.measurerFor = { org.fcitx.fcitx5.android.scene.engine.FixedMeasurer }
            try {
                open()
                val note = st.s(R.string.swipe_fate_both, st.s(R.string.swipe_fate_part_sign), st.s(R.string.swipe_fate_part_shortened, "现…"))
                val line = st.s(R.string.swipe_fate_key, "j", st.s(R.string.swipe_down), note)
                awaitNotes { line in st.texts() }
                assertTrue(st.texts().toString(), line in st.texts())
                keyCalled("j").performClick()
                st.idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                fun shown() = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }.toList()
                awaitNotes { note in shown() }
                assertTrue(shown().toString(), note in shown())
                // set to a command: recomputed a moment later
                val command = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<RadioButton>()
                    .filter { it.text.startsWith(st.s(R.string.shortcut_command)) }.toList()
                command.last().performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
                awaitNotes { note !in shown() }
                assertFalse(shown().toString(), note in shown())
                // the one note left is 下滑's icon's; 上滑, left as the keyboard has it, has none
                val iconNote = st.s(R.string.swipe_fate_both, st.s(R.string.swipe_fate_part_dropped), st.s(R.string.swipe_fate_part_as_set))
                val notes = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<TextView>()
                    .filter { it.id == R.id.swipe_slot_note && it.visibility == View.VISIBLE }.map { it.text.toString() }.toList()
                assertEquals(listOf(iconNote), notes)
                val section = SwipeSlotSection(st.activity, off = false, initial = SwipeSlot.Text("x"))
                section.note("n")
                assertEquals("n", section.noted)
                section.note(null)
                assertNull(section.noted)
            } finally {
                org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.measurerFor = null
            }
        }

        // made on another thread and posted
        private fun awaitNotes(done: () -> Boolean) {
            repeat(100) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (done()) return
                Thread.sleep(20)
            }
        }

        @Test
        fun theNoteNamesTheKeyboardThatDrawsTheWayWorst() {
            val ctx = st.activity
            fun seen(label: String, up: MarkFate, turned: MarkFate) = listOf(
                SwipeFates.Seen(label, false, mapOf("fn/space" to mapOf(true to up))),
                SwipeFates.Seen(label, true, mapOf("fn/space" to mapOf(true to turned))),
            )
            val both = seen("全键盘", MarkFate.AsSet, MarkFate.AsSet) + seen("九键", MarkFate.Sign, MarkFate.AsSet)
            val worst = SwipeFates.note(ctx, MarkFate.Sign, MarkFate.AsSet)!!
            assertEquals(st.s(R.string.swipe_fate_on, "九键", worst), SwipeFates.note(ctx, both, "space", true))
            // the same on both: no name
            assertEquals(worst, SwipeFates.note(ctx, seen("全键盘", MarkFate.Sign, MarkFate.AsSet) + seen("九键", MarkFate.Sign, MarkFate.AsSet), "space", true))
            // set to 空: no claim
            assertNull(SwipeFates.note(ctx, listOf(SwipeFates.Seen("全键盘", false, mapOf("fn/space" to emptyMap())), SwipeFates.Seen("全键盘", true, mapOf("fn/space" to emptyMap()))), "space", false))
            assertNull(SwipeFates.note(ctx, both, "retype", true))
            // a scene not made: no claim
            assertNull(SwipeFates.note(ctx, both + SwipeFates.Seen("九键", true, null), "space", true))

            // a shape code's roots filling the letter: said so, not 画不下
            val dropped = mapOf("full/j" to mapOf(true to MarkFate.Dropped, false to MarkFate.Dropped))
            fun rooted(label: String) = listOf(false, true).map { SwipeFates.Seen(label, it, dropped, roots = setOf("full/j")) }
            assertEquals(st.s(R.string.swipe_fate_roots), SwipeFates.note(ctx, rooted("五笔"), "full/j", true))
            val drawn = listOf(false, true).map { SwipeFates.Seen("全键盘", it, mapOf("full/j" to mapOf(true to MarkFate.AsSet))) }
            assertEquals(st.s(R.string.swipe_fate_on, "五笔", st.s(R.string.swipe_fate_roots)), SwipeFates.note(ctx, drawn + rooted("五笔"), "full/j", true))
        }

        @Test
        fun theNoteSaysEachRungAndBothOrientations() {
            val ctx = st.activity
            assertNull(SwipeFates.note(ctx, MarkFate.AsSet, null))
            assertEquals(st.s(R.string.swipe_fate_shortened, "现…"), SwipeFates.note(ctx, MarkFate.Shortened("现…"), MarkFate.Shortened("现…")))
            assertEquals(st.s(R.string.swipe_fate_sign), SwipeFates.note(ctx, MarkFate.Sign, MarkFate.Sign))
            assertEquals(st.s(R.string.swipe_fate_dropped), SwipeFates.note(ctx, MarkFate.Dropped, MarkFate.Dropped))
            assertEquals(
                st.s(R.string.swipe_fate_both, st.s(R.string.swipe_fate_part_sign), st.s(R.string.swipe_fate_part_as_set)),
                SwipeFates.note(ctx, MarkFate.Sign, MarkFate.AsSet),
            )
        }

        @Test
        fun theTabAndTheChosenKeysOutliveARotation() {
            open()
            click(R.string.shortcut_page_g14)
            click(R.string.swipe_multi_select)
            keyCalled("QW").performClick()
            st.idle()
            st.recreate()
            st.idle()
            assertTrue(st.texts().toString(), st.s(R.string.swipe_selected, 1) in st.texts())
            assertTrue("the 14 keys' tab", "QW" in st.texts())
        }

        @Test
        @Uat("ST-97")
        fun aLettersSlotsAreSetInItsDialog() {
            open()
            keyCalled("q").performClick()
            st.idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val views = KeyboardHarness.all(dialog.window!!.decorView).toList()
            // each way: the long press's first, a command, nothing, a text
            val radios = views.filterIsInstance<RadioButton>()
            assertEquals(8, radios.size)
            assertTrue("上滑 at its preset, the first", radios[0].isChecked)
            radios[7].performClick()
            views.filterIsInstance<EditText>().last().setText("你好")
            radios[2].performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlot.Text("你好"), slots().resolve("full/q", up = false))
            assertEquals(SwipeSlot.Empty, slots().resolve("full/q", up = true))
            undo()
            assertEquals(SwipeSlots.Default, slots())
        }

        // a short swipe then a stroke types pinyin, so the stroke is not reached; not for other settings or keys
        @Test
        @Uat("ST-120")
        fun aStrokeSetWhileStrokesAreDrawnSaysItIsNotReachedWhileTyping() {
            val drawing = AppPrefs.getInstance().keyboard.keyDrawing
            val pie = SwipeSlot.Command(KeyCommand.StrokePie)
            class Row(val why: String, val drawn: KeyDrawing, val tab: Int, val slotKey: String, val label: String, val slot: SwipeSlot, val says: Boolean)
            org.fcitx.fcitx5.android.engine.assertRows(
                listOf(
                    Row("笔画筛选, a stroke on q: said", KeyDrawing.StrokeFilter, R.string.shortcut_page_full, "full/q", "q", pie, true),
                    Row("笔画筛选, a stroke on 九键's 3, a letter key: said", KeyDrawing.StrokeFilter, R.string.shortcut_page_nine, "t9/3", "3", pie, true),
                    Row("笔画筛选, a stroke on 九键's 1: a stroke starts there too (the 3×3), said", KeyDrawing.StrokeFilter, R.string.shortcut_page_nine, "t9/1", "1", pie, true),
                    Row("在键上划 off, a stroke: always reached, nothing to say", KeyDrawing.Off, R.string.shortcut_page_full, "full/q", "q", pie, false),
                    Row("笔画筛选, another command: not a stroke", KeyDrawing.StrokeFilter, R.string.shortcut_page_full, "full/q", "q", SwipeSlot.Command(KeyCommand.Paste), false),
                    Row("笔画筛选, a text", KeyDrawing.StrokeFilter, R.string.shortcut_page_full, "full/q", "q", SwipeSlot.Text("x"), false),
                ),
                { it.why },
            ) { row ->
                // each row opens the page anew and changes nothing, so rows are independent
                prefs.set(drawing, row.drawn)
                prefs.set(slotsPref, SwipeSlots.Default.with(row.slotKey, true, row.slot).serialize())
                st.activity
                st.open(SettingsRoute.PopupOverrides)
                click(row.tab)
                val dialog = dialogOf(row.label)
                val said = strokeNoted(dialog)
                dialog.dismiss()
                st.idle()
                assertEquals(row.says, said)
            }
        }

        // a stroke picked through 「换」 says it, 「无」 picked after removes it
        @Test
        @Uat("ST-120")
        fun aStrokePickedInTheDialogSaysItIsNotReachedWhileTyping() {
            prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.StrokeFilter)
            prefs.set(slotsPref, SwipeSlots.Default.with("full/q", true, SwipeSlot.Command(KeyCommand.Paste)).serialize())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            val dialog = dialogOf("q")
            assertFalse("粘贴: not a stroke", strokeNoted(dialog))
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(R.string.shortcut_change) }.performClick()
            st.idle()
            pick(KeyCommand.entries.indexOf(KeyCommand.StrokePie))
            assertTrue("笔画：撇 picked", strokeNoted(dialog))
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<RadioButton>().first { it.text == st.s(R.string.slot_empty) }.performClick()
            st.idle()
            assertFalse("无 picked", strokeNoted(dialog))
            dialog.dismiss()
            st.idle()
        }

        private fun strokeNoted(dialog: AlertDialog) = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<TextView>()
            .any { it.visibility == View.VISIBLE && it.text.toString() == st.s(R.string.slot_stroke_drawn) }

        // a field auto-focusing once chose 文字 and lost a kept command on 确定; only typing chooses it
        @Test
        @Uat("ST-97")
        fun aFocusedTextFieldKeepsTheChosenCommandUntilTypedIn() {
            prefs.set(slotsPref, SwipeSlots.Default.with("full/q", true, SwipeSlot.Command(KeyCommand.Paste)).serialize())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            keyCalled("q").performClick()
            st.idle()
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().toList().takeLast(2).first().requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlot.Command(KeyCommand.Paste), slots().resolve("full/q", up = true))
            keyCalled("q").performClick()
            st.idle()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().toList().takeLast(2).first().setText("x")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlot.Text("x"), slots().resolve("full/q", up = true))
        }

        @Test
        @Uat("ST-98")
        fun flippingAllSwapsTheWaysAndTheirPlaces() {
            open()
            val k = AppPrefs.getInstance().keyboard
            val places = k.swipeUpPosition.getValue() to k.swipeDownPosition.getValue()
            click(R.string.swipe_flip_all)
            assertEquals(SwipeOrientation.FirstDown, slots().orientation)
            assertEquals(SwipeSlot.Command(KeyCommand.SelectAll), slots().resolve("full/a", up = true))
            assertEquals(places.second to places.first, k.swipeUpPosition.getValue() to k.swipeDownPosition.getValue())
            click(R.string.swipe_flip_all)
            assertEquals(SwipeSlots.Default, slots())
            assertEquals(places, k.swipeUpPosition.getValue() to k.swipeDownPosition.getValue())
        }

        @Test
        @Uat("ST-98")
        fun aPresetResetsEveryKeyAndCanBeTakenBack() {
            prefs.set(slotsPref, SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("Q")).serialize())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            val edited = slots()
            click(R.string.swipe_presets)
            pick(1)
            // asked first: cancelled, nothing changed
            val ask = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(ask.isShowing)
            ask.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            st.idle()
            assertEquals(edited, slots())
            click(R.string.swipe_presets)
            pick(1)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlots.Default.applied(SwipeOrientation.FirstDown), slots())
            undo()
            assertEquals(edited, slots())
        }

        @Test
        @Uat("ST-97")
        fun theStepBackOutlivesThePage() {
            open()
            click(R.string.swipe_flip_all)
            st.open(SettingsRoute.PopupOverrides)
            undo()
            assertEquals(SwipeSlots.Default, slots())
        }

        // the page, no longer there, was once redrawn and threw
        @Test
        @Uat("ST-98")
        fun theSnackbarsStepBackWorksAfterThePageIsLeft() {
            open()
            val page = st.page as PopupOverridesFragment
            click(R.string.swipe_flip_all)
            assertTrue(slots() != SwipeSlots.Default)
            page.findNavController().popBackStack()
            st.idle()
            assertFalse(page.isAdded)
            // the before-unlock copy follows: left with the change, then put back
            fun deviceCopy() = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(st.activity.applicationContext.createDeviceProtectedStorageContext())
                .getString(slotsPref.key, "")!!.let { if (it.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(it) }
            assertEquals(slots(), deviceCopy())
            page.undoLast()
            st.idle()
            assertEquals(SwipeSlots.Default, slots())
            assertEquals(SwipeSlots.Default, deviceCopy())
        }

        // 按键's 下滑 turned off after a flip: the switch must not be turned back on unseen
        @Test
        fun aStepChangedSinceElsewhereIsNoLongerTakenBack() {
            open()
            click(R.string.swipe_flip_all)
            val flipped = slots()
            AppPrefs.getInstance().keyboard.swipeDown.setValue(false)
            st.open(SettingsRoute.PopupOverrides)
            // dimmed, and a tap anyway takes nothing back
            assertFalse(menuItem(R.string.undo).isEnabled)
            undo()
            assertFalse(AppPrefs.getInstance().keyboard.swipeDown.getValue())
            assertEquals(flipped, slots())
        }

        // keys tapped are chosen, not edited; the batch sets their slots alone
        @Test
        @Uat("ST-99")
        fun selectedKeysAreSetAtOnce() {
            open()
            click(R.string.swipe_multi_select)
            keyCalled("q").performClick()
            st.idle()
            keyCalled("w").performClick()
            st.idle()
            assertTrue(st.texts().toString(), st.activity.getString(R.string.swipe_selected, 2) in st.texts())
            click(R.string.swipe_set_empty)
            pick(2)
            for (key in listOf("full/q", "full/w")) for (up in listOf(true, false)) assertEquals(SwipeSlot.Empty, slots().resolve(key, up))
            assertEquals("others as they were", SwipeSlot.First, slots().resolve("full/e", up = true))
            click(R.string.swipe_flip)
            assertEquals(SwipeSlot.Empty, slots().resolve("full/q", up = true))
            click(R.string.swipe_set_first)
            pick(1)
            assertEquals(SwipeSlot.First, slots().resolve("full/w", up = false))
            click(R.string.swipe_reset)
            assertEquals(SwipeSlots.Default, slots())
            click(R.string.done)
            assertTrue(st.texts().toString(), st.s(R.string.swipe_multi_select) in st.texts())
        }

        // it starts with the corner first; nothing is stored till a character is added
        @Test
        @Uat("ST-100")
        fun anotherKeyboardsLongPressIsSetByName() {
            open()
            click(R.string.shortcut_page_g14)
            fun dialog(): AlertDialog {
                keyCalled("QW").performClick()
                st.idle()
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            var d = dialog()
            val none = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == st.s(R.string.long_press_none) }
            assertFalse("the key has a long press: not said to have none", none.isVisible)
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals("kept as it is, nothing stored", KeyLists.HEADER, AppPrefs.getInstance().internal.keyLists.getValue())
            d = dialog()
            KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("①")
            val before = own("g14/qw")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            // ① after the keyboard's own; its first the corner 1
            val stored = qwStored()!!
            assertEquals(before + "①", stored.items)
            assertEquals(First.Literal("1"), stored.first)
        }

        @Test
        fun theDialogIsTitledByTheKeyAlone() {
            open()
            val backspace = st.s(R.string.fn_key_backspace)
            for ((page, label) in listOf(R.string.shortcut_page_full to "q", R.string.shortcut_page_g18 to "XC", R.string.shortcut_page_function to backspace)) {
                click(page)
                keyCalled(label).performClick()
                st.idle()
                val d = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(label, d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
                d.dismiss()
                st.idle()
            }
        }

        private fun qwDialog(): AlertDialog {
            keyCalled("QW").performClick()
            st.idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }

        private fun qwStored() = stored("g14/qw")


        private fun five(): AlertDialog {
            keyCalled("5").performClick()
            st.idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }

        private fun AlertDialog.typed(text: String) {
            KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first().setText(text)
        }

        // moved and back, edited back to the default, put back by 重置这个键
        @Test
        @Uat("ST-100")
        fun aListBackToTheEditorsDefaultStoresNothing() {
            open()
            click(R.string.shortcut_page_nine)
            val items = own("t9/5")
            assertEquals(listOf("J", "K", "L", "5", "j", "k", "l", "#", "(", ")"), items)
            five().apply {
                tapItem("k")
                button(R.string.long_press_move_left)
                button(R.string.long_press_move_right)
                ok()
            }
            assertEquals("moved back", null, stored("t9/5"))
            five().apply {
                typed("①")
                ok()
            }
            assertEquals(items + "①", stored("t9/5")!!.items)
            five().apply {
                tapItem("①")
                button(R.string.delete)
                ok()
            }
            assertEquals("edited back to the default", null, stored("t9/5"))
            five().apply {
                typed("①")
                ok()
            }
            five().apply {
                getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                st.idle()
            }
            assertEquals("重置这个键", null, stored("t9/5"))
        }

        // added after it, stored by the key's one name; 注音大千's keys have none of their own
        @Test
        @Uat("ST-100")
        fun everyKeysEditorStartsFromItsKeyboardsOwnLongPress() {
            open()
            for (page in KeyShortcutPages.all) {
                click(PopupOverridesFragment.pageTitle(page))
                val keys = if (page == KeyShortcutPages.Function) listOf("comma", "period").map { PopupOverridesFragment.FunctionLabels.getValue(it).let(st::s) to "fn/$it" }
                else page.rows.flatten().filterNot(page::elsewhere).map { (if (page == KeyShortcutPages.Full) it.label.lowercase() else it.label) to it.name }
                for ((label, name) in keys) {
                    val before = own(name)
                    // a function key's name may hold a comma (", key"): read whole
                    (if (page == KeyShortcutPages.Function) KeyboardHarness.all(st.activity.window.decorView)
                        .first { it.contentDescription?.toString()?.startsWith(label) == true && it.hasOnClickListeners() } else keyCalled(label)).performClick()
                    st.idle()
                    val d = ShadowDialog.getLatestDialog() as AlertDialog
                    KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("※")
                    d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    st.idle()
                    assertEquals(name, before + "※", stored(name)!!.items)
                    if (page == KeyShortcutPages.ZhuyinFull) assertEquals(name, emptyList<String>(), before)
                }
            }
        }

        private fun AlertDialog.emptyUp() {
            KeyboardHarness.all(window!!.decorView).filterIsInstance<RadioButton>().first { it.text.toString() == st.s(R.string.slot_empty) }.performClick()
            st.idle()
        }

        // key_lists not stored yet (an old backup, migrated next start): the old long presses still read till then
        @Test
        fun anUndoLeavesKeyListsUnstoredAsItWas() {
            open()
            val p = AppPrefs.getInstance()
            prefs.setStored(AppPrefs.POPUP_OVERRIDES, "q ?")
            p.setStoredKeyLists(null)
            assertEquals("?", KeyListsCache.user.current[KeyId("full/q")]?.items?.lastOrNull())
            dialogOf("q").apply {
                KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first().setText("①")
                ok()
            }
            undo()
            assertNull(p.storedKeyLists())
            assertEquals("?", KeyListsCache.user.current[KeyId("full/q")]?.items?.lastOrNull())
        }

        @Test
        fun nineKeysRowsAreTheUsersLetters() {
            prefs.set(AppPrefs.getInstance().keyboard.t9Letters, NineKeyLetters.STANDARD.withEdit('2', "abcq").serialize())
            open()
            val row = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard("t9/2"))!!.edit(KeyId.of("t9/2")).items
            assertTrue(row.toString(), "q" in row || "Q" in row)
        }

        // q's 1 is a symbol and its default first: `*` would be the moved one
        @Test
        fun movingSymbolsKeepsTheFirst() {
            open()
            val symbols = own("full/q")
            val first = firstOf("full/q")
            assertTrue(symbols.size >= 2 && first == symbols.first())
            dialogOf("q").apply {
                tapItem(symbols.last())
                repeat(symbols.size - 1) { button(R.string.long_press_move_left) }
                ok()
            }
            assertEquals(symbols.last(), stored("full/q")!!.items.first())
            assertEquals("the first kept", first, firstOf("full/q"))
        }

        // 九键 1's default differs between pinyin and English
        @Test
        fun aSlotAloneChangedWritesNoList() {
            open()
            click(R.string.shortcut_page_nine)
            dialogOf("1").apply {
                emptyUp()
                ok()
            }
            assertEquals(KeyLists.HEADER, AppPrefs.getInstance().internal.keyLists.getValue())
        }

        // a 注音九键 group: no `set !`
        @Test
        fun aGroupOfNoFirstOpenedWritesNothing() {
            open()
            click(R.string.shortcut_page_zy9)
            val key = KeyShortcutPages.ZhuyinNine.rows.flatten().filterNot(KeyShortcutPages.ZhuyinNine::elsewhere).first { k ->
                KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(k.name))!!.let { it.defaultOf(KeyId.of(k.name))?.first == null && it.edit(KeyId.of(k.name)).items.isNotEmpty() }
            }
            dialogOf(key.label).ok()
            assertEquals(KeyLists.HEADER, AppPrefs.getInstance().internal.keyLists.getValue())
        }

        @Test
        fun nineOnesEditorStartsFromTheStoredDefault() {
            open()
            click(R.string.shortcut_page_nine)
            val d = dialogOf("1")
            val items = listOf("^", ":", ";", "~", ".", "-", "@", "\\", "\"")
            val said = KeyboardHarness.all(d.window!!.decorView).filter { it.hasOnClickListeners() }.mapNotNull { it.contentDescription?.toString() }.filter { it in items }.toList()
            assertEquals(items, said)
        }

        // a phone's height, the editor opening on its long press card
        @Test
        @Config(qualifiers = "w393dp-h851dp-port-440dpi")
        fun anOffKeyIsSaidSoAndKeptOff() {
            open()
            // a key with a row, which a save would have turned back on
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER + "\nt9/5\toff")
            click(R.string.shortcut_page_nine)
            dialogOf("5").apply {
                assertTrue(KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == st.s(R.string.long_press_off) })
                emptyUp()
                ok()
            }
            assertEquals(UserList.Off, stored("t9/5"))
        }

        @Test
        fun anOffKeyAddedToAndBackStaysOff() {
            open()
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER + "\nt9/5\toff")
            click(R.string.shortcut_page_nine)
            dialogOf("5").apply {
                KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first().setText("①")
                KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(R.string.add) }.performClick()
                st.idle()
                tapItem("①")
                button(R.string.delete)
                ok()
            }
            assertEquals(UserList.Off, stored("t9/5"))
        }

        @Test
        fun aNewerVersionsListsAreNeverWritten() {
            val newer = "@4\nfull/q\tsomething"
            prefs.set(AppPrefs.getInstance().internal.keyLists, newer)
            open()
            prefs.set(AppPrefs.getInstance().internal.keyLists, newer)
            st.open(SettingsRoute.PopupOverrides)
            assertTrue(KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == st.s(R.string.long_press_newer) })
            dialogOf("q").apply {
                emptyUp()
                ok()
            }
            undo()
            assertEquals(newer, AppPrefs.getInstance().internal.keyLists.getValue())
        }

        // the number keyboard's long press reads it
        @Test
        @Uat("ST-109")
        fun numberPage() {
            open()
            click(R.string.shortcut_page_number)
            keyCalled("5").performClick()
            st.idle()
            val d = ShadowDialog.getLatestDialog() as AlertDialog
            KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("½")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals("½", KeyListsCache.user.current[KeyId("num/5")]!!.items.last())
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
    class Cards : EditorDialog() {

        private val decor get() = st.activity.window.decorView

        private fun open(swipes: SwipeSlots = SwipeSlots.Default) {
            prefs.set(AppPrefs.getInstance().internal.keySwipes, swipes.serialize())
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
            st.activity
            st.open(SettingsRoute.PopupOverrides)
        }

        // the card is the one target and node
        private fun board(form: String): View = KeyboardHarness.all(decor).first { it.tag == "view:keys/board/$form" }
        private fun body(form: String): View = KeyboardHarness.all(board(form)).first { it.hasOnClickListeners() }

        @Test
        @Uat("ST-151")
        fun theKeyboardsAreEightCardsToPickNotARowToSwipe() {
            open()
            assertTrue(KeyboardHarness.all(decor).none { it is android.widget.HorizontalScrollView })
            val forms = KeyShortcutPages.all.map { it.form }
            assertEquals(listOf("full", "t9", "g14", "g18", "zy9", "zyfull", "num", "fn"), forms)
            val nodes = forms.map { body(it).createAccessibilityNodeInfo() }
            assertTrue(nodes.all { it.className == RadioButton::class.java.name })
            assertEquals(listOf(true) + List(7) { false }, nodes.map { it.isChecked })
            // two rows of four on a phone, all on the first screen
            assertEquals(board("full").top, board("g18").top)
            assertTrue(board("zy9").top > board("full").bottom)
            assertTrue(KeyboardHarness.all(decor).first { it is androidx.core.widget.NestedScrollView || it is android.widget.ScrollView }.height > board("fn").bottom)
            body("g14").performClick()
            st.idle()
            assertTrue(body("g14").createAccessibilityNodeInfo().isChecked)
            assertTrue("QW" in st.texts())
        }

        @Test
        @Uat("ST-152")
        fun aKeyboardsCardSaysHowManyOfItsKeysAreChanged() {
            open(SwipeSlots.Default.with("full/q", true, SwipeSlot.Empty).with("t9/2", false, SwipeSlot.Empty))
            fun said(form: String) = androidx.core.view.ViewCompat.getStateDescription(body(form)).toString()
            val selected = st.s(R.string.shortcut_card_selected)
            assertEquals(st.s(R.string.shortcut_card_edited, selected, 1), said("full"))
            assertEquals(st.s(R.string.shortcut_card_edited, st.s(R.string.shortcut_card_unselected), 1), said("t9"))
            assertEquals(st.s(R.string.shortcut_card_unselected), said("g14"))
            // none where none is changed
            assertTrue(KeyboardHarness.all(board("full")).any { it is TextView && it.isShown && it.text.toString() == "1" })
            assertTrue(KeyboardHarness.all(board("g14")).none { it is TextView && it.isShown && it.text.toString() == "0" })
        }

        @Test
        @Uat("ST-152")
        fun theFunctionKeysAreTwelveCellsFourAcross() {
            open()
            body("fn").performClick()
            st.idle()
            val grid = KeyboardHarness.all(decor).filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.cards.CardGrid>().last()
            assertEquals(12, grid.childCount)
            assertEquals(4, grid.columns)
            assertEquals(3, grid.rows)
            val name = KeyboardHarness.all(grid.getChildAt(1)).filterIsInstance<TextView>().first { it.text.toString() == st.s(R.string.fn_key_backspace) }
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, name.importantForAccessibility)
        }

        // the longest name on a small phone at a large font: three lines (capped at two, it was cut)
        @Test
        @Config(qualifiers = "en-w360dp-h640dp-port-xhdpi", fontScale = 1.3f)
        @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
        fun theFunctionKeysNamesAreNeverCut() {
            open()
            body("fn").performClick()
            st.idle()
            val grid = KeyboardHarness.all(decor).filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.cards.CardGrid>().last()
            val names = (0 until grid.childCount).map { cell -> KeyboardHarness.all(grid.getChildAt(cell)).filterIsInstance<TextView>().last() }
            assertTrue(names.any { it.text.toString() == st.s(R.string.fn_key_separator) })
            names.forEach { name ->
                val layout = name.layout!!
                assertTrue(name.text.toString(), (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 })
                assertTrue(name.text.toString(), layout.height <= name.height - name.paddingTop - name.paddingBottom)
                // the row's cells as high as their highest
                assertTrue(name.text.toString(), name.bottom <= (name.parent as View).height)
            }
        }

        @Test
        @Uat("ST-152")
        fun undoIsTheTitleBarsAndResetAllInItsMenu() {
            open()
            assertTrue(st.texts().none { it == st.s(R.string.undo) })
            assertFalse(menuItem(R.string.undo).isEnabled)
            assertTrue((menuItem(R.string.undo) as androidx.appcompat.view.menu.MenuItemImpl).requiresActionButton())
            assertFalse(menuItem(R.string.reset_all).isEnabled)
            clickText(R.string.swipe_flip_all)
            assertTrue(menuItem(R.string.undo).isEnabled)
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER + "\nfull/q\toff")
            st.open(SettingsRoute.PopupOverrides)
            assertTrue(menuItem(R.string.reset_all).isEnabled)
            val item = menuItem(R.string.reset_all)
            item.javaClass.getMethod("invoke").invoke(item)
            st.idle()
            assertEquals(st.s(R.string.reset_all_long_press_message), (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<TextView>(android.R.id.message)!!.text.toString())
        }

        @Test
        @Uat("ST-155")
        fun multiSelectsStripsChooseAKeyboardsKeysAllOrNone() {
            open()
            clickText(R.string.swipe_multi_select)
            val strips = KeyboardHarness.all(decor).filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip>().filter { it.isShown }.toList()
            // the scroll lists' card has none
            assertEquals(8, strips.size)
            fun strip(form: String) = KeyboardHarness.all(board(form)).filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip>().first()
            assertEquals(com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED, strip("full").state)
            keyCalled("q").performClick()
            st.idle()
            assertEquals(com.google.android.material.checkbox.MaterialCheckBox.STATE_INDETERMINATE, strip("full").state)
            assertEquals("1/26", strip("full").count.text.toString())
            strip("full").performClick()
            st.idle()
            assertEquals(com.google.android.material.checkbox.MaterialCheckBox.STATE_CHECKED, strip("full").state)
            assertTrue(st.texts().toString(), st.s(R.string.swipe_selected, 26) in st.texts())
            // nothing stored: no step to take back
            assertFalse(menuItem(R.string.undo).isEnabled)
            strip("full").performClick()
            st.idle()
            assertEquals(com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED, strip("full").state)
            clickText(R.string.done)
            assertTrue(KeyboardHarness.all(decor).filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip>().none { it.isShown })
        }

        private fun AlertDialog.header(title: Int): View {
            var v: View = KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }
            while (!v.hasOnClickListeners()) v = v.parent as View
            return v
        }

        private fun AlertDialog.expanded(title: Int) =
            androidx.core.view.ViewCompat.getStateDescription(header(title)).toString() == st.s(R.string.settings_card_expanded)

        @Test
        @Uat("ST-153", "ST-154")
        fun theEditorsCardsOpenOneAtATimeEachSayingWhatIsSet() {
            open()
            dialogOf("a").apply {
                assertTrue(expanded(R.string.long_press_card))
                assertFalse(expanded(R.string.swipe_up))
                assertFalse(expanded(R.string.swipe_down))
                val first = firstOf("full/a")!!
                val up = header(R.string.swipe_up).contentDescription.toString()
                assertEquals(st.s(R.string.swipe_ways_said, st.s(R.string.swipe_up), st.s(R.string.swipe_way_said, st.s(R.string.slot_first), first)), up)
                assertTrue(header(R.string.swipe_down).contentDescription.toString(), st.s(R.string.shortcut_command) in header(R.string.swipe_down).contentDescription)
                header(R.string.swipe_down).performClick()
                st.idle()
                assertFalse(expanded(R.string.long_press_card))
                assertTrue(expanded(R.string.swipe_down))
                // its title row says so at once
                KeyboardHarness.all(window!!.decorView).filterIsInstance<RadioButton>().filter { it.text.toString() == st.s(R.string.slot_empty) }.last().performClick()
                st.idle()
                assertEquals(st.s(R.string.swipe_ways_said, st.s(R.string.swipe_down), st.s(R.string.slot_empty)), header(R.string.swipe_down).contentDescription.toString())
                // a text chosen with nothing typed: 确定 opens 下滑's card with its error
                header(R.string.long_press_card).performClick()
                st.idle()
                KeyboardHarness.all(window!!.decorView).filterIsInstance<RadioButton>().filter { it.text.toString() == st.s(R.string.shortcut_text) }.last().performClick()
                ok()
                assertTrue(isShowing)
                assertTrue(expanded(R.string.swipe_down))
                assertFalse(expanded(R.string.long_press_card))
                dismiss()
            }
        }

        private fun View.y() = IntArray(2).also(::getLocationInWindow)[1]

        @Test
        @Uat("ST-154")
        fun nothingMovesAsAnItemIsSelectedOrATextChosen() {
            open()
            dialogOf("a").apply {
                val field = KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first()
                val at = field.y()
                // there before anything is typed
                val boxes = KeyboardHarness.all(window!!.decorView).filterIsInstance<android.widget.CheckBox>().filter { it.isShown }.map { it.text.toString() }.toList()
                assertEquals(listOf(st.s(R.string.text_variables_use), st.s(R.string.text_as_is)), boxes)
                tapItem("â")
                assertEquals(at, field.y())
                tapItem("â")
                assertEquals(at, field.y())
                // its first three choices stay put as 文字 is chosen
                header(R.string.swipe_up).performClick()
                st.idle()
                val radios = KeyboardHarness.all(window!!.decorView).filterIsInstance<RadioButton>().filter { it.isShown }.toList()
                assertEquals(4, radios.size)
                assertEquals(st.s(R.string.shortcut_text), radios[3].text.toString())
                val tops = radios.take(3).map { it.y() }
                val upField = KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().toList()[1]
                assertFalse(upField.isShown)
                radios[3].performClick()
                st.idle()
                assertEquals(tops, radios.take(3).map { it.y() })
                assertTrue(upField.isShown)
                dismiss()
            }
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
    class ScrollLists : EditorDialog() {

        private val decor get() = st.activity.window.decorView
        private val pref get() = AppPrefs.getInstance().internal.scrollLists
        private fun store() = ScrollListStore.parse(pref.getValue())

        private fun open(lists: String = "") {
            prefs.set(pref, lists)
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
            st.activity
            st.open(SettingsRoute.PopupOverrides)
        }

        private fun board(form: String): View = KeyboardHarness.all(decor).first { it.tag == "view:keys/board/$form" }
        private fun body(form: String): View = KeyboardHarness.all(board(form)).first { it.hasOnClickListeners() }
        private fun row(id: String): View = KeyboardHarness.all(decor).first { it.tag == "view:keys/scroll/$id" }

        private fun editorOf(id: String): AlertDialog {
            body("scroll").performClick()
            st.idle()
            row(id).performClick()
            st.idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }

        private fun AlertDialog.field() = KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first()
        private fun AlertDialog.texts() = KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

        @Test
        @Uat("ST-218")
        fun theScrollListsAreACardBeforeTheFunctionKeysARowEach() {
            open()
            val boards = KeyboardHarness.all(decor).mapNotNull { it.tag as? String }.filter { it.startsWith("view:keys/board/") }.toList()
            assertEquals(boards.indexOf("view:keys/board/fn") - 1, boards.indexOf("view:keys/board/scroll"))
            body("scroll").performClick()
            st.idle()
            ScrollDefaults.IDS.forEach { id -> assertTrue(id, row(id).isShown) }
            // the number pad's row: its name and first six
            val said = row(ScrollDefaults.NUMBER).contentDescription.toString()
            assertEquals((listOf(st.s(R.string.scroll_list_number)) + listOf("+", "-", "*", "/", "%", ",")).joinToString(", "), said)
            assertNull(androidx.core.view.ViewCompat.getStateDescription(row(ScrollDefaults.NUMBER)))
        }

        @Test
        @Uat("ST-219")
        fun aListsItemsAreAddedDeletedAndACommandAdded() {
            open()
            val command = KeyCommand.Copy
            editorOf(ScrollDefaults.NUMBER).apply {
                tapItem("+")
                button(R.string.delete)
                // a scroll list has no first
                assertTrue(texts().none { it == st.s(R.string.long_press_make_first) })
                button(R.string.scroll_list_add_command)
                (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, ScrollListStore.COMMANDS.indexOf(command), 0)
                st.idle()
                // drawn as its icon, said by its name; a text of the same name is another item
                val label = st.s(org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks.of(command).label)
                assertTrue(KeyboardHarness.all(window!!.decorView).any { it.contentDescription?.toString() == label && it.hasOnClickListeners() })
                field().setText("copy y")
                ok()
            }
            val want = ScrollDefaults.of(ScrollDefaults.NUMBER)!!.drop(1) + ScrollItem.Command(command.key) + ScrollItem.Text("copy") + ScrollItem.Text("y")
            assertEquals(want, store()[ScrollDefaults.NUMBER])
            // the card says one category is changed, and its row is marked
            val selected = st.s(R.string.shortcut_card_selected)
            assertEquals(st.s(R.string.scroll_lists_card_edited, selected, 1), androidx.core.view.ViewCompat.getStateDescription(body("scroll")).toString())
            assertNotNull(androidx.core.view.ViewCompat.getStateDescription(row(ScrollDefaults.NUMBER)))
            // no undo step: undo is the long presses' and swipes'
            assertFalse(menuItem(R.string.undo).isEnabled)
        }

        @Test
        @Uat("ST-218")
        fun onlyTheNineKeysListsOfferNotConvertingAndNoneLeftCannotBeKept() {
            open()
            editorOf(ScrollDefaults.NUMBER).apply {
                assertTrue(texts().none { it == st.s(R.string.text_as_is) || it == st.s(R.string.text_variables_use) })
                // every item can be dragged; left and right stay
                val items = KeyboardHarness.all(window!!.decorView).filter { it.contentDescription?.toString() == "+" && it.hasOnClickListeners() }.toList()
                assertTrue(items.single().isLongClickable)
                tapItem("+")
                repeat(ScrollDefaults.of(ScrollDefaults.NUMBER)!!.size) { button(R.string.delete) }
                assertFalse(getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertTrue(st.s(R.string.scroll_list_keep_one) in texts())
                dismiss()
            }
            st.idle()
            assertFalse(store().isSet(ScrollDefaults.NUMBER))
            editorOf(ScrollDefaults.NINE).apply {
                assertTrue(st.s(R.string.text_as_is) in texts())
                assertTrue(texts().none { it == st.s(R.string.text_variables_use) })
                dismiss()
            }
        }

        // a long press's items are never dragged
        @Test
        @Uat("ST-218")
        fun aLongPressEditorsItemsAreNotHeld() {
            open()
            clickText(R.string.shortcut_page_g14)
            dialogOf("QW").apply {
                val q = KeyboardHarness.all(window!!.decorView).first { it.contentDescription?.toString() == "q" && it.hasOnClickListeners() }
                assertFalse(q.isLongClickable)
                dismiss()
            }
        }

        @Test
        @Uat("ST-218")
        fun aListIsPutBackAloneOrWithEverythingByResetAll() {
            val set = ScrollListStore.Empty.with(ScrollDefaults.URL, listOf(ScrollItem.Text("/"))).with(ScrollDefaults.EMAIL, listOf(ScrollItem.Text("@")))
            open(set.serialize())
            editorOf(ScrollDefaults.URL).apply {
                getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                st.idle()
            }
            assertFalse(store().isSet(ScrollDefaults.URL))
            assertTrue(store().isSet(ScrollDefaults.EMAIL))
            // a default list has no 恢复默认
            editorOf(ScrollDefaults.URL).apply {
                assertFalse(getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
                dismiss()
            }
            st.idle()
            // the long presses are all default: still enabled, for the scroll list
            assertTrue(menuItem(R.string.reset_all).isEnabled)
            val item = menuItem(R.string.reset_all)
            item.javaClass.getMethod("invoke").invoke(item)
            st.idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).ok()
            assertFalse(store().isSet(ScrollDefaults.EMAIL))
        }

        private fun resetAll(): AlertDialog {
            val item = menuItem(R.string.reset_all)
            item.javaClass.getMethod("invoke").invoke(item)
            st.idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }

        private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

        // the one step that changes both: its 撤销 puts the scroll lists back with the long presses
        @Test
        fun undoAfterResetAllBringsTheScrollListsBackToo() {
            val set = ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Text("#"))).serialize()
            val keys = KeyLists.HEADER + "\nfull/q\toff"
            prefs.set(pref, set)
            prefs.set(AppPrefs.getInstance().internal.keyLists, keys)
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            resetAll().ok()
            assertFalse(store().isSet(ScrollDefaults.NUMBER))
            undo()
            assertEquals(set, pref.getValue())
            assertEquals(keys, AppPrefs.getInstance().internal.keyLists.getValue())
            // the scroll lists alone changed: a step too
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
            st.open(SettingsRoute.PopupOverrides)
            resetAll().ok()
            assertTrue(menuItem(R.string.undo).isEnabled)
            undo()
            assertEquals(set, pref.getValue())
        }

        // a later version's are left as they are, so the question names only what is put back
        @Test
        fun resetAllSaysOnlyWhatItPutsBack() {
            prefs.set(pref, ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Text("#"))).serialize())
            prefs.set(AppPrefs.getInstance().internal.keyLists, "@9\nfull/q\toff")
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            resetAll().apply {
                assertEquals(st.s(R.string.reset_all_scroll_lists_message), message())
                dismiss()
            }
            prefs.set(pref, "@9\nnumber\tt|x")
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER + "\nfull/q\toff")
            st.open(SettingsRoute.PopupOverrides)
            resetAll().apply {
                assertEquals(st.s(R.string.reset_all_long_presses_only_message), message())
                dismiss()
            }
        }

        // what is typed is added at OK, so it alone can be kept
        @Test
        fun aListEmptiedThenTypedCanBeKept() {
            open()
            editorOf(ScrollDefaults.DATETIME).apply {
                tapItem("-")
                repeat(ScrollDefaults.of(ScrollDefaults.DATETIME)!!.size) { button(R.string.delete) }
                assertFalse(getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                field().setText("+")
                assertTrue(getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                ok()
            }
            assertEquals(listOf(ScrollItem.Text("+")), store()[ScrollDefaults.DATETIME])
        }

        // a fixed cell broke `.com` inside the word, showing `.co`
        @Test
        @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
        fun aRowsItemsAreWhole() {
            open()
            body("scroll").performClick()
            st.idle()
            val com = KeyboardHarness.all(row(ScrollDefaults.URL)).filterIsInstance<TextView>().first { it.text.toString() == ".com" }
            val layout = com.layout!!
            assertEquals(".com".length, layout.getLineEnd(0))
            assertEquals(0, layout.getEllipsisCount(0))
            assertTrue(layout.getLineWidth(0) <= com.width - com.paddingLeft - com.paddingRight)
        }

        @Test
        fun multiSelectLeavesTheScrollListsCardOut() {
            open()
            clickText(R.string.swipe_multi_select)
            fun strips(form: String) = KeyboardHarness.all(board(form)).filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip>().filter { it.isShown }
            assertTrue(strips("scroll").none())
            assertTrue(strips("full").any())
        }

        @Test
        fun aLaterVersionsListsAreShownNotWritten() {
            val later = "@9\nnumber\tt|x"
            open(later)
            body("scroll").performClick()
            st.idle()
            assertTrue(st.s(R.string.scroll_list_newer) in st.texts())
            row(ScrollDefaults.NUMBER).performClick()
            st.idle()
            val d = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(st.s(R.string.scroll_list_newer), d.findViewById<TextView>(android.R.id.message)!!.text.toString())
            d.ok()
            assertEquals(later, pref.getValue())
        }

        @Test
        fun theLongPressMenusRouteOpensItsListsEditor() {
            open()
            st.open(SettingsRoute.ScrollList(ScrollDefaults.URL))
            val d = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(d.isShowing)
            assertTrue(st.s(R.string.scroll_list_url) in KeyboardHarness.all(d.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList())
            assertTrue(body("scroll").createAccessibilityNodeInfo().isChecked)
        }

        @Test
        fun searchFindsEachList() {
            val entries = PopupOverridesFragment.searchEntries(st.activity)
            val titles = entries.map { it.title }
            assertTrue(st.s(R.string.scroll_lists) in titles)
            ScrollDefaults.IDS.forEach { id ->
                assertTrue(id, entries.any { it.id == "view:keys/scroll/$id" })
            }
            assertTrue("${st.s(R.string.scroll_lists)} · ${st.s(R.string.scroll_list_url)}" in titles)
        }
    }

    // a page with no title would throw and a key show its bare name: caught here
    @Test
    fun everyShortcutPageHasATitleAndEveryFunctionKeyAName() {
        KeyShortcutPages.all.forEach { PopupOverridesFragment.pageTitle(it) }
        assertEquals(emptyList<String>(), KeyShortcutPages.FUNCTION_KEYS.filter { it !in PopupOverridesFragment.FunctionLabels })
    }
    // the key's own letters and digit are items like any
    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class)
    class Row : EditorDialog() {

        private fun open(page: Int) {
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            clickText(page)
        }

        // neither its long press nor that slot types the list's first
        @Test
        @Uat("ST-153")
        fun aGroupOfNoFirstIsSaidToHaveNone() {
            open(R.string.shortcut_page_zy9)
            val key = KeyShortcutPages.ZhuyinNine.rows.flatten().filterNot(KeyShortcutPages.ZhuyinNine::elsewhere).first { k ->
                KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(k.name))!!.let { it.defaultOf(KeyId.of(k.name))?.first == null && it.edit(KeyId.of(k.name)).items.isNotEmpty() }
            }
            val items = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(key.name))!!.edit(KeyId.of(key.name)).items
            val d = dialogOf(key.label)
            val texts = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(texts.toString(), st.s(R.string.long_press_card_summary_no_first, items.size) in texts)
            assertTrue(texts.toString(), items.none { st.s(R.string.long_press_card_summary, it, items.size) in texts })
            assertTrue(texts.toString(), texts.none { t -> items.any { t == st.s(R.string.swipe_way_said, st.s(R.string.slot_first), it) } })
            d.dismiss()
            st.idle()
        }

        @Test
        @Uat("ST-100")
        fun anEmptiedListIsOffItsRowGoneToo() {
            open(R.string.shortcut_page_nine)
            val items = own("t9/5")
            dialogOf("5").apply {
                // a delete selects what took the deleted one's place
                tapItem(items.first())
                repeat(items.size) { button(R.string.delete) }
                ok()
            }
            assertEquals(UserList.Off, stored("t9/5"))
        }

        // its first is now the list's first item (A); stored and reopened
        @Test
        @Uat("T9-62")
        fun aDigitDeletedAndALetterMovedAreKept() {
            open(R.string.shortcut_page_nine)
            val items = own("t9/2")
            dialogOf("2").apply {
                tapItem("2")
                button(R.string.delete)
                repeat(items.size) { button(R.string.long_press_move_right) }
                ok()
            }
            val want = items.filter { it != "2" && it != "a" } + "a"
            assertEquals(UserList(off = false, items = want, first = First.Default), stored("t9/2"))
            assertEquals("A", firstOf("t9/2"))
            assertEquals(want, own("t9/2"))
        }

        @Test
        fun aRowItemIsMovedDeletedAndMadeTheFirst() {
            open(R.string.shortcut_page_g14)
            dialogOf("QW").apply {
                tapItem("q")
                assertTrue(enabled(R.string.delete).isEnabled)
                assertTrue(enabled(R.string.long_press_move_left).isEnabled)
                assertTrue(enabled(R.string.long_press_move_right).isEnabled)
                assertTrue(enabled(R.string.long_press_make_first).isEnabled)
                button(R.string.long_press_make_first)
                ok()
            }
            assertEquals(UserList(off = false, items = own("g14/qw"), first = First.Literal("q")), stored("g14/qw"))
            // the keyboards read it: its first is q now
            assertEquals("q", KeyListsCache.user.current[KeyId("g14/qw")]!!.let { (it.first as First.Literal).text })
        }
    }

    abstract class EditorDialog {

        protected val st = SettingsHarness()

        @get:Rule
        val prefs = PrefsRule()

        @After
        fun close() = st.close()

        // a text that is not its own target taps what it is on
        protected fun clickText(title: Int) {
            var v: View = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }
            while (!v.hasOnClickListeners()) v = v.parent as View
            v.performClick()
            st.idle()
        }

        // MenuItemImpl's own invoke, what its button's tap calls
        protected fun menuItem(title: Int): MenuItem {
            val menu = shadowOf(st.activity).optionsMenu
            return (0 until menu.size()).map(menu::getItem).first { it.title.toString() == st.s(title) }
        }

        protected fun undo() {
            val item = menuItem(R.string.undo)
            item.javaClass.getMethod("invoke").invoke(item)
            st.idle()
        }

        protected fun keyCalled(label: String) = KeyboardHarness.all(st.activity.window.decorView)
            .first { it.contentDescription?.toString()?.substringBefore(',') == label && it.hasOnClickListeners() }

        protected fun dialogOf(label: String): AlertDialog {
            keyCalled(label).performClick()
            st.idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }

        protected fun AlertDialog.tapItem(item: String) {
            KeyboardHarness.all(window!!.decorView).first { it.contentDescription?.toString() == item && it.hasOnClickListeners() }.performClick()
            st.idle()
        }

        protected fun AlertDialog.enabled(text: Int) = KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(text) }

        protected fun AlertDialog.button(text: Int) {
            enabled(text).performClick()
            st.idle()
        }

        protected fun AlertDialog.ok() {
            getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
        }

        protected fun stored(name: String): UserList? = KeyLists.parse(AppPrefs.getInstance().internal.keyLists.getValue())!![KeyId(name)]

        protected fun edit(name: String) = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(name))!!.edit(KeyId.of(name))

        protected fun own(name: String): List<String> = edit(name).items

        protected fun firstOf(name: String): String? = edit(name).first
    }
}
