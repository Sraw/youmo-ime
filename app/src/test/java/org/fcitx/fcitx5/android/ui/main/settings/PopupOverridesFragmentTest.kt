/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

        // a letter's dialog has its shortcut below its characters: a text typed there is what its swipe runs
        @Test
        fun aLettersShortcutIsSetInItsDialog() {
            val pref = AppPrefs.getInstance().internal.keyShortcuts
            // put back after, passed or failed: a later swipe test expects the presets (full/a = 全选)
            prefs.set(pref, pref.getValue())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            KeyboardHarness.all(st.activity.window.decorView)
                .first { it.contentDescription?.toString()?.substringBefore(',') == "q" && it.hasOnClickListeners() }
                .performClick()
            st.idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val views = KeyboardHarness.all(dialog.window!!.decorView).toList()
            // none, a command, a text
            views.filterIsInstance<RadioButton>()[2].performClick()
            views.filterIsInstance<EditText>().last().setText("你好")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(KeyShortcut.Text("你好"), KeyShortcuts.parse(pref.getValue()).resolve("full/q"))
        }

        // a whole page's shortcuts cleared only once the user says so: cancelled, nothing changed
        @Test
        fun aPageIsClearedOfItsShortcutsOnlyOnceConfirmed() {
            val pref = AppPrefs.getInstance().internal.keyShortcuts
            // put back after, passed or failed: a later swipe test expects the presets (full/a = 全选)
            prefs.set(pref, pref.getValue())
            st.activity
            st.open(SettingsRoute.PopupOverrides)
            fun clearPage(): AlertDialog {
                KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString() == st.s(R.string.shortcut_clear_page) }.performClick()
                st.idle()
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            clearPage().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            st.idle()
            assertEquals(KeyShortcut.Command(KeyCommand.SelectAll), KeyShortcuts.parse(pref.getValue()).resolve("full/a"))
            clearPage().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(KeyShortcut.None, KeyShortcuts.parse(pref.getValue()).resolve("full/a"))
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
