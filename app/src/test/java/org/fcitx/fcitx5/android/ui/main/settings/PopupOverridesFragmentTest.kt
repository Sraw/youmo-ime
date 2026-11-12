/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
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
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.scene.keys.KeyId
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
import org.robolectric.shadows.ShadowDialog

/** The long-press and swipe editor: what it starts from is what the keyboard pops up, what it stores is key_lists. */
class PopupOverridesFragmentTest {

    // a 14-key code typed in the editor would be typed as that key: the editor says so instead of adding it
    @Test
    fun aKeyCodeIsFoundInWhatIsTyped() {
        val bn = g14("bn")
        assertEquals(bn, keyCodeIn("x $bn y"))
        // the codes before R16 F4356 (⒝, ⒉) are text now
        assertNull(keyCodeIn("① b ⑴ ⒝ ⒉"))
        // each item as PopupOverrides rejects it, not the 14 keys' alone: said here rather than lost there
        assertEquals(nineSp("2"), keyCodeIn("b ${nineSp("2")}"))
        // 双拼's 18 keys' private-use code
        assertEquals("\uF8C1", keyCodeIn("b \uF8C1"))
        // a supplementary tap code is two chars, one item: found, not thrown on
        val tap = String(Character.toChars(0xF0200))
        assertEquals(tap, keyCodeIn("x $tap"))
        // a code within longer text is text, which PopupOverrides keeps
        assertNull(keyCodeIn("a${nineSp("2")}"))
    }

    // the error names a code most fonts draw blank by its number
    @Test
    fun aPrivateUseCodeIsShownByItsNumber() {
        assertEquals("U+F8C1", shownCode("\uF8C1"))
        assertEquals("U+F8DB", shownCode(g14("bn")))
        assertEquals("⒜", shownCode("⒜"))
        assertEquals("U+F0200", shownCode(String(Character.toChars(0xF0200))))
    }

    // F4444: a code alone typed as a long-press character is refused in the dialog, which stays open
    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class)
    class Dialog {

        private val st = SettingsHarness()

        @get:Rule
        val prefs = PrefsRule()

        @After
        fun close() = st.close()

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
            // the long press's field first, the shortcut's text below it
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
            // put back after, passed or failed: a later swipe test expects the presets (full/a = 全选 down)
            prefs.set(slotsPref, slotsPref.getValue())
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
            val k = AppPrefs.getInstance().keyboard
            listOf(k.swipeUpPosition, k.swipeDownPosition).forEach { prefs.set(it, it.getValue()) }
            listOf(k.swipeUp, k.swipeDown).forEach { prefs.set(it, it.getValue()) }
            st.activity
            st.open(SettingsRoute.PopupOverrides)
        }

        private fun keyCalled(label: String) = KeyboardHarness.all(st.activity.window.decorView)
            .first { it.contentDescription?.toString()?.substringBefore(',') == label && it.hasOnClickListeners() }

        private fun click(title: Int) {
            KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }.performClick()
            st.idle()
        }

        // a list dialog's item picked: a preset, a way
        private fun pick(index: Int) {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.listView.performItemClick(null, index, 0)
            st.idle()
        }

        // a key tapped as a finger does, down and up through the window (not performClick): beta1's caps kept the
        // touch in the engine's cell, a gesture view, and no dialog came
        private fun tap(v: View) {
            val at = IntArray(2).also(v::getLocationInWindow)
            val x = at[0] + v.width / 2f
            val y = at[1] + v.height / 2f
            val root = st.activity.window.decorView
            root.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            root.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0))
            st.idle()
        }

        // a key cap on the page: a frame with its click (the tabs are chips, the buttons buttons)
        private fun firstCap() = KeyboardHarness.all(st.activity.window.decorView)
            .first { it is FrameLayout && it !is Chip && it.hasOnClickListeners() && it.contentDescription != null }

        @Test
        fun aTappedKeyOpensItsDialogOnEveryTab() {
            open()
            // 数字 too (ST-109): every page one slotPage
            val tabs = KeyShortcutPages.all
            assertEquals(8, tabs.size)
            for (page in tabs) {
                click(PopupOverridesFragment.pageTitle(page))
                val cap = firstCap()
                tap(cap)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue("${page.form}: ${cap.contentDescription}", dialog.isShowing)
                // its two ways, four choices each
                assertEquals(page.form, 8, KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<RadioButton>().count())
                dialog.dismiss()
                st.idle()
            }
        }

        // the owner's 「现在怎么样了」 on j's 下滑 at 底部 (360 dp, 紧凑, one-handed): the page and the dialog say how the
        // keyboard draws it, measured by the engine's fixed measurer (Robolectric's fonts differ by SDK): a sign upright,
        // shortened turned; nothing once a command (an icon, drawn as set) is picked (uat ST-106 on the emulator)
        @Test
        @Config(qualifiers = "w360dp-h780dp-xxhdpi")
        @Uat("ST-106")
        fun eachWaysNoteIsWhatTheKeyboardMakesOfIt() {
            val k = AppPrefs.getInstance().keyboard
            prefs.set(k.swipeDownPosition, KeyMarkPosition.Bottom)
            prefs.set(k.keyboardHeightPercent, 20)
            prefs.set(k.keyboardHeightPercentLandscape, 34)
            prefs.set(org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.oneHanded, org.fcitx.fcitx5.android.input.keyboard.OneHanded.Right)
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
                // 下滑 set to a command: worked out again a moment later, its icon drawn as set
                val command = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<RadioButton>()
                    .filter { it.text.startsWith(st.s(R.string.shortcut_command)) }.toList()
                command.last().performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
                awaitNotes { note !in shown() }
                assertFalse(shown().toString(), note in shown())
                // 上滑 left as the keyboard has it: no note of it
                assertEquals(shown().toString(), 1, shown().count { it == st.s(R.string.swipe_fate_both, st.s(R.string.swipe_fate_part_dropped), st.s(R.string.swipe_fate_part_as_set)) || it.startsWith(st.s(R.string.swipe_fate_dropped).take(3)) || it.startsWith(st.s(R.string.swipe_fate_both, "", "").take(5)) })
                // a note given is shown, none hidden
                val section = SwipeSlotSection(st.activity, "", off = false, initial = SwipeSlot.Text("x"))
                section.note("n")
                assertEquals("n", section.noted)
                section.note(null)
                assertNull(section.noted)
            } finally {
                org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.measurerFor = null
            }
        }

        // the notes are made on another thread and posted: waited for, a while at most
        private fun awaitNotes(done: () -> Boolean) {
            repeat(100) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (done()) return
                Thread.sleep(20)
            }
        }

        // the keyboards that differ: the worst named; a scene not made, no claim
        @Test
        fun theNoteNamesTheKeyboardThatDrawsTheWayWorst() {
            val ctx = st.activity
            fun seen(label: String, up: MarkFate, turned: MarkFate) = listOf(
                SwipeFates.Seen(label, false, mapOf("space" to mapOf(true to up))),
                SwipeFates.Seen(label, true, mapOf("space" to mapOf(true to turned))),
            )
            val both = seen("全键盘", MarkFate.AsSet, MarkFate.AsSet) + seen("九键", MarkFate.Sign, MarkFate.AsSet)
            val worst = SwipeFates.note(ctx, MarkFate.Sign, MarkFate.AsSet)!!
            assertEquals(st.s(R.string.swipe_fate_on, "九键", worst), SwipeFates.note(ctx, both, "space", true))
            // the same on both: no name
            assertEquals(worst, SwipeFates.note(ctx, seen("全键盘", MarkFate.Sign, MarkFate.AsSet) + seen("九键", MarkFate.Sign, MarkFate.AsSet), "space", true))
            // set, and no fate in a scene: drawn nowhere
            assertEquals(st.s(R.string.swipe_fate_dropped), SwipeFates.note(ctx, listOf(SwipeFates.Seen("全键盘", false, mapOf("space" to emptyMap())), SwipeFates.Seen("全键盘", true, mapOf("space" to emptyMap()))), "space", false))
            // a key on no keyboard: nothing
            assertNull(SwipeFates.note(ctx, both, "retype", true))
            // a scene not made: no claim
            assertNull(SwipeFates.note(ctx, both + SwipeFates.Seen("九键", true, null), "space", true))

            // a shape code's roots filling the letter: said so, not 画不下; beside a keyboard drawing it, named
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

        // the tab, 多选 and the keys chosen kept as the page is made again (a rotation)
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

        // a letter's dialog has its two ways below its characters: a text typed for 下滑 is what its swipe down runs
        @Test
        @Uat("ST-97")
        fun aLettersSlotsAreSetInItsDialog() {
            open()
            keyCalled("q").performClick()
            st.idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val views = KeyboardHarness.all(dialog.window!!.decorView).toList()
            // each way: the long press's first, a command, a text, nothing; 上滑 then 下滑
            val radios = views.filterIsInstance<RadioButton>()
            assertEquals(8, radios.size)
            assertTrue("上滑 at its preset, the first", radios[0].isChecked)
            radios[6].performClick()
            views.filterIsInstance<EditText>().last().setText("你好")
            radios[3].performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlot.Text("你好"), slots().resolve("full/q", up = false))
            assertEquals(SwipeSlot.Empty, slots().resolve("full/q", up = true))
            // 撤销: one step back
            click(R.string.undo)
            assertEquals(SwipeSlots.Default, slots())
        }

        // the bug: the dialog focusing a way's text field by itself (a key with no long press to edit has it
        // first) chose 文字 for it, so a command kept there became an empty text on 确定. Only typing chooses it
        @Test
        @Uat("ST-97")
        fun aFocusedTextFieldKeepsTheChosenCommandUntilTypedIn() {
            prefs.set(slotsPref, SwipeSlots.Default.with("full/q", true, SwipeSlot.Command(KeyCommand.Paste)).serialize())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            keyCalled("q").performClick()
            st.idle()
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            // 上滑's text field focused as the dialog would, then 确定 untouched
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().toList().takeLast(2).first().requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlot.Command(KeyCommand.Paste), slots().resolve("full/q", up = true))
            // typed in: the text chosen
            keyCalled("q").performClick()
            st.idle()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().toList().takeLast(2).first().setText("x")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(SwipeSlot.Text("x"), slots().resolve("full/q", up = true))
        }

        // 翻转上下滑: every key's two slots swapped, the places with them, and once more back
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

        // a preset puts every key back, edits too; 撤销 brings them back
        @Test
        @Uat("ST-98")
        fun aPresetResetsEveryKeyAndCanBeTakenBack() {
            prefs.set(slotsPref, SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("Q")).serialize())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            val edited = slots()
            click(R.string.swipe_presets)
            pick(1)
            // asked first, as 0.5 asked: cancelled, nothing changed
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
            click(R.string.undo)
            assertEquals(edited, slots())
        }

        // the user leaves the page to try the keys: back on it, 撤销 still takes the change back
        @Test
        @Uat("ST-97")
        fun theStepBackOutlivesThePage() {
            open()
            click(R.string.swipe_flip_all)
            st.open(SettingsRoute.PopupOverrides)
            click(R.string.undo)
            assertEquals(SwipeSlots.Default, slots())
        }

        // 撤销 on the snackbar, still up after the page was left: the step is put back and nothing crashes
        // (the page, no longer there, was once redrawn and threw)
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
            // the copy read before the first unlock follows: left with the page's change, then put back
            fun deviceCopy() = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(st.activity.applicationContext.createDeviceProtectedStorageContext())
                .getString(slotsPref.key, "")!!.let { if (it.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(it) }
            assertEquals(slots(), deviceCopy())
            page.undoLast()
            st.idle()
            assertEquals(SwipeSlots.Default, slots())
            assertEquals(SwipeSlots.Default, deviceCopy())
        }

        // a change made elsewhere since (按键's 下滑 turned off) after a flip: 撤销 has nothing to take back, the switch
        // never turned back on unseen
        @Test
        fun aStepChangedSinceElsewhereIsNoLongerTakenBack() {
            open()
            click(R.string.swipe_flip_all)
            val flipped = slots()
            AppPrefs.getInstance().keyboard.swipeDown.setValue(false)
            st.open(SettingsRoute.PopupOverrides)
            val undo = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(R.string.undo) }
            assertFalse(undo.isEnabled)
            undo.performClick()
            st.idle()
            assertFalse(AppPrefs.getInstance().keyboard.swipeDown.getValue())
            assertEquals(flipped, slots())
        }

        // 多选: keys tapped are chosen, not edited; the batch sets their slots alone
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

        // another keyboard's key: its editor starts from what the key pops up on its keyboard (its corner first,
        // as the cap's small character), kept as it is nothing is stored; a character added stores the list by name
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
            // the row (1 q w) never stored: the symbols alone, ① after the keyboard's own
            val stored = qwStored()!!
            assertEquals(before + "①", stored.symbols)
            assertEquals(First.Default, stored.first)
        }

        // the dialog titled by the key alone on every tab (it edits the slots too, not the long press only)
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

        // a character in the dialog's list tapped (selected), a button of the dialog's clicked
        private fun AlertDialog.tapItem(item: String) {
            KeyboardHarness.all(window!!.decorView).first { it.contentDescription?.toString() == item && it.hasOnClickListeners() }.performClick()
            st.idle()
        }

        private fun AlertDialog.button(text: Int) {
            KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(text) }.performClick()
            st.idle()
        }

        private fun AlertDialog.ok() {
            getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
        }

        private fun stored(name: String): UserList? = KeyLists.parse(AppPrefs.getInstance().internal.keyLists.getValue())!![KeyId(name)]

        private fun qwStored() = stored("g14/qw")

        // a key's symbols as its page's keyboard has them with nothing set
        private fun own(name: String): List<String> = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(name))!!.edit(KeyId.of(name)).symbols

        // moved and moved back, edited back to the keyboard's own, put back by 重置这个键: nothing stored;
        // every symbol deleted: the row alone stored (the row can't be deleted)
        @Test
        @Uat("ST-100")
        fun aListBackToTheOwnStoresNothingAndOneEmptiedKeepsItsRow() {
            open()
            click(R.string.shortcut_page_nine)
            // 九键 5's own symbols: # ( ), above its row J K L 5 j k l
            val symbols = own("t9/5")
            assertEquals(listOf("#", "(", ")"), symbols)
            fun five(): AlertDialog {
                keyCalled("5").performClick()
                st.idle()
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            five().apply {
                tapItem("(")
                button(R.string.long_press_move_left)
                button(R.string.long_press_move_right)
                ok()
            }
            assertEquals("moved back", null, stored("t9/5"))
            five().apply {
                KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first().setText("①")
                ok()
            }
            assertEquals(symbols + "①", stored("t9/5")!!.symbols)
            five().apply {
                tapItem("①")
                button(R.string.delete)
                ok()
            }
            assertEquals("edited back to the own", null, stored("t9/5"))
            five().apply {
                KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first().setText("①")
                ok()
            }
            five().apply {
                getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                st.idle()
            }
            assertEquals("重置这个键", null, stored("t9/5"))
            five().apply {
                // a delete selects what took the place of the one deleted
                tapItem(symbols.first())
                repeat(symbols.size) { button(R.string.delete) }
                ok()
            }
            assertEquals("every symbol deleted: the row kept", UserList(off = false, symbols = emptyList(), first = First.Default), stored("t9/5"))
        }

        // the row greyed (060-keys-unify S5): chosen as the first, never deleted or moved
        @Test
        fun aRowItemIsOnlyChosenAsTheFirst() {
            open()
            click(R.string.shortcut_page_g14)
            qwDialog().apply {
                tapItem("q")
                fun enabled(text: Int) = KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(text) }.isEnabled
                assertFalse(enabled(R.string.delete))
                assertFalse(enabled(R.string.long_press_move_left))
                assertFalse(enabled(R.string.long_press_move_right))
                assertTrue(enabled(R.string.long_press_make_first))
                button(R.string.long_press_make_first)
                ok()
            }
            assertEquals(UserList(off = false, symbols = own("g14/qw"), first = First.Literal("q")), qwStored())
            // the keyboards read it: its first is q now
            assertEquals("q", KeyListsCache.user.current[KeyId("g14/qw")]!!.let { (it.first as First.Literal).text })
        }

        // every key of every tab starts from its keyboard's own long press (owner, 0.6.0 beta2): what is added goes after
        // it, stored in key_lists by the key's one name; 注音大千's keys have none of their own
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
                    // a function key's name may hold a comma (", key"): its caption read whole
                    (if (page == KeyShortcutPages.Function) KeyboardHarness.all(st.activity.window.decorView)
                        .first { it.contentDescription?.toString()?.startsWith(label) == true && it.hasOnClickListeners() } else keyCalled(label)).performClick()
                    st.idle()
                    val d = ShadowDialog.getLatestDialog() as AlertDialog
                    KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("※")
                    d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    st.idle()
                    assertEquals(name, before + "※", stored(name)!!.symbols)
                    if (page == KeyShortcutPages.ZhuyinFull) assertEquals(name, emptyList<String>(), before)
                }
            }
        }

        // the first the key resolves to, as the editor starts it
        private fun firstOf(name: String): String? = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(name))!!.edit(KeyId.of(name)).first

        private fun dialogOf(label: String): AlertDialog {
            keyCalled(label).performClick()
            st.idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }

        // 上滑's 空 picked (the first of the two ways' 空)
        private fun AlertDialog.emptyUp() {
            KeyboardHarness.all(window!!.decorView).filterIsInstance<RadioButton>().first { it.text.toString() == st.s(R.string.slot_empty) }.performClick()
            st.idle()
        }

        // re-review 1: key_lists not stored yet (a 0.5 backup imported, migrated at the next start) is not stored by
        // an undo either: the old long presses still read till then
        @Test
        fun anUndoLeavesKeyListsUnstoredAsItWas() {
            open()
            val p = AppPrefs.getInstance()
            prefs.setStored(AppPrefs.POPUP_OVERRIDES, "q ?")
            p.setStoredKeyLists(null)
            assertEquals("?", KeyListsCache.user.current[KeyId("full/q")]?.symbols?.lastOrNull())
            dialogOf("q").apply {
                KeyboardHarness.all(window!!.decorView).filterIsInstance<EditText>().first().setText("①")
                ok()
            }
            click(R.string.undo)
            assertNull(p.storedKeyLists())
            assertEquals("?", KeyListsCache.user.current[KeyId("full/q")]?.symbols?.lastOrNull())
        }

        // review 6: 九键's rows are the user's t9_letters
        @Test
        fun nineKeysRowsAreTheUsersLetters() {
            prefs.set(AppPrefs.getInstance().keyboard.t9Letters, NineKeyLetters.STANDARD.withEdit('2', "abcq").serialize())
            open()
            val row = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard("t9/2"))!!.edit(KeyId.of("t9/2")).row
            assertTrue(row.toString(), "q" in row || "Q" in row)
        }

        // review 1: a symbol moved to the front keeps the first (q's 1 a symbol, its default's first: `*` would be the moved one)
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
            assertEquals(symbols.last(), stored("full/q")!!.symbols.first())
            assertEquals("the first kept", first, firstOf("full/q"))
        }

        // review 2: a slot alone changed on 九键 1 (its default differs between pinyin and English) writes no list
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

        // review 3: a 注音九键 group with no default first, opened and OK'd, writes nothing (no `set !`)
        @Test
        fun aGroupOfNoFirstOpenedWritesNothing() {
            open()
            click(R.string.shortcut_page_zy9)
            val key = KeyShortcutPages.ZhuyinNine.rows.flatten().filterNot(KeyShortcutPages.ZhuyinNine::elsewhere).first { k ->
                KeyCaps.resolver(st.activity, KeyCaps.defaultBoard(k.name))!!.let { it.defaultOf(KeyId.of(k.name))?.first == null && it.edit(KeyId.of(k.name)).row.isNotEmpty() }
            }
            dialogOf(key.label).ok()
            assertEquals(KeyLists.HEADER, AppPrefs.getInstance().internal.keyLists.getValue())
        }

        // review L2: 九键 1's editor starts from the keyboard's own list (KeyResolver.edit), not the views' old one
        @Test
        fun nineOnesEditorStartsFromTheStoredDefault() {
            open()
            click(R.string.shortcut_page_nine)
            val d = dialogOf("1")
            val items = listOf("^", ":", ";", "~", ".", "-", "@", "\\", "\"")
            val said = KeyboardHarness.all(d.window!!.decorView).filter { it.hasOnClickListeners() }.mapNotNull { it.contentDescription?.toString() }.filter { it in items }.toList()
            assertEquals(items, said)
        }

        // review 4: a key turned off is said so, and stays off when only a slot is changed
        @Test
        fun anOffKeyIsSaidSoAndKeptOff() {
            open()
            // 九键 5: a key with a row, which a save would have turned back on
            prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER + "\nt9/5\toff")
            click(R.string.shortcut_page_nine)
            dialogOf("5").apply {
                assertTrue(KeyboardHarness.all(window!!.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == st.s(R.string.long_press_off) })
                emptyUp()
                ok()
            }
            assertEquals(UserList.Off, stored("t9/5"))
        }

        // an off key's character added and deleted again: still off
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

        // review 8: a later version's key_lists is read-only here: said so, never written over
        @Test
        fun aNewerVersionsListsAreNeverWritten() {
            val newer = "@2\nfull/q\tsomething"
            prefs.set(AppPrefs.getInstance().internal.keyLists, newer)
            open()
            prefs.set(AppPrefs.getInstance().internal.keyLists, newer)
            st.open(SettingsRoute.PopupOverrides)
            assertTrue(KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == st.s(R.string.long_press_newer) })
            dialogOf("q").apply {
                emptyUp()
                ok()
            }
            click(R.string.undo)
            assertEquals(newer, AppPrefs.getInstance().internal.keyLists.getValue())
        }

        // 数字's keys edited like any other (ST-109): 5 given ½, the number keyboard's long press reads it
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
            assertEquals("½", KeyListsCache.user.current[KeyId("num/5")]!!.symbols.last())
        }
    }

    // a page or function key ime-core adds with no title or name here: the page's title would throw
    // (an unknown page has no fallback) and the key show its bare name; caught here, not on a phone
    @Test
    fun everyShortcutPageHasATitleAndEveryFunctionKeyAName() {
        KeyShortcutPages.all.forEach { PopupOverridesFragment.pageTitle(it) }
        assertEquals(emptyList<String>(), KeyShortcutPages.FUNCTION_KEYS.filter { it !in PopupOverridesFragment.FunctionLabels })
    }
}
