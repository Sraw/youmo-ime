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
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

/**
 * What the long-press editor shows is what the popup shows. Putting the built-in list back on
 * "Q" while "q" was edited used to drop "Q"'s override, so the popup showed "q"'s edit upper-cased.
 */
class PopupOverridesFragmentTest {

    private val presetLower = arrayOf("1", "Q")
    private val presetUpper = arrayOf("1", "q")

    @Test
    fun theBuiltInListPutBackOnAnUpperCaseKeyIsStoredWhenItsLowerCaseKeyIsEdited() {
        val after = PopupOverrides.parse("q x y").withEdit("Q", presetUpper.toList(), presetUpper)
        assertEquals(presetUpper.toList(), after["Q"])
        assertArrayEquals(presetUpper, after.resolve("Q", presetUpper))
    }

    @Test
    fun theBuiltInListPutBackIsNotAnEdit() {
        val after = PopupOverrides.parse("q x y").withEdit("q", presetLower.toList(), presetLower)
        assertEquals(PopupOverrides.Empty, after)
    }

    @Test
    fun anUpperCaseKeySetToWhatItsLowerCaseEditGivesFollowsThatEdit() {
        val after = PopupOverrides.parse("q x y\nQ m").withEdit("Q", listOf("X", "Y"), presetUpper)
        assertNull(after["Q"])
        assertArrayEquals(arrayOf("X", "Y"), after.resolve("Q", presetUpper))
    }

    // a label that named no key closed the add-key dialog with nothing said
    @Test
    fun aLabelForAnotherKeyIsOneWordOfTheStoredFormat() {
        assertEquals(".", keyLabel(" . "))
        assertNull(keyLabel(""))
        assertNull(keyLabel("  \u3000"))
        assertNull(keyLabel("a b"))
        assertNull(keyLabel("a\u3000b"))
    }

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
            val pref = AppPrefs.getInstance().internal.popupOverrides
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
            prefs.set(AppPrefs.getInstance().internal.keyPopups, AppPrefs.getInstance().internal.keyPopups.getValue())
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
            val tabs = KeyShortcutPages.all.filter { it !== KeyShortcutPages.Number }
            assertEquals(7, tabs.size)
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
        fun theSnackbarsStepBackWorksAfterThePageIsLeft() {
            open()
            val page = st.page as PopupOverridesFragment
            click(R.string.swipe_flip_all)
            assertTrue(slots() != SwipeSlots.Default)
            page.findNavController().popBackStack()
            st.idle()
            assertFalse(page.isAdded)
            page.undoLast()
            st.idle()
            assertEquals(SwipeSlots.Default, slots())
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

        // another keyboard's key: its long press set by its name, the keyboard's own kept until then
        @Test
        @Uat("ST-100")
        fun anotherKeyboardsLongPressIsSetByName() {
            open()
            click(R.string.shortcut_page_g14)
            keyCalled("QW").performClick()
            st.idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val texts = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(texts.toString(), st.s(R.string.long_press_by_name_hint) in texts)
            KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().first().setText("① ②")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(listOf("①", "②"), PopupOverrides.parse(AppPrefs.getInstance().internal.keyPopups.getValue())["g14/qw"])
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
