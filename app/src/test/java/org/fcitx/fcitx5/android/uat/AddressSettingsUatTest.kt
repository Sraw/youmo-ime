/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.TwoStatePreference
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.address.AddressLists
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.fcitx.fcitx5.android.ui.main.settings.AddressSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.AddressSuffixesFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** uat/14-settings.md's 「通用输入 → 网址和邮箱」 (AddressSettingsFragment, AddressSuffixesFragment). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class AddressSettingsUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val st = SettingsHarness()

    private val internal = AppPrefs.getInstance().internal
    private val keyboard = AppPrefs.getInstance().keyboard

    @Before
    fun defaults() {
        prefs.set(internal.addressCounts, "")
        prefs.set(internal.rememberedAddresses, "")
        prefs.set(keyboard.emailSuffixes, AddressLists.serialize(AddressLists.DEFAULT_EMAIL))
        prefs.set(keyboard.urlSuffixes, AddressLists.serialize(AddressLists.DEFAULT_URL))
        prefs.set(keyboard.rememberAddresses, true)
    }

    @After
    fun close() = st.close()

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun shown(): List<String> = KeyboardHarness.all(st.activity.window.decorView)
        .filter { it.isShown && it is TextView }.map { (it as TextView).text.toString() }.toList()

    // the list page's + and its dialog, [typed] put in and OK pressed; whether the dialog closed
    private fun add(typed: String): Boolean {
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val d = dialog()
        KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().single().setText(typed)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        return !d.isShowing.also { if (it) d.dismiss() }
    }

    private fun list() = (st.page as AddressSuffixesFragment).ui.entries

    @Test
    @Uat("ST-44")
    fun theAddressPageItsListsAndRememberedAddresses() {
        prefs.set(internal.rememberedAddresses, "2\tzhang@qq.com\n1\tli@163.com")
        // 通用输入's first group's row (its page needs the engine's options: SettingsUatTest's ST-33 opens it)
        val all = InputMethodSettings.SHARED.first() as InputMethodSettings.Section
        val row = all.items.filterIsInstance<InputMethodSettings.Page>().single { it.key == InputMethodSettings.ADDRESSES }
        assertEquals(R.string.address_suggestions, row.title)
        st.open(row.route)
        assertTrue(st.page.javaClass.name, st.page is AddressSettingsFragment)
        assertTrue(st.pref(R.string.email_suffixes).summary.toString().startsWith("@qq.com  @163.com"))
        assertEquals(AddressLists.DEFAULT_URL.joinToString("  "), st.pref(R.string.url_suffixes).summary.toString())
        // the switch on by default, off kept
        val remember = st.pref(R.string.remember_addresses) as TwoStatePreference
        assertTrue(remember.isChecked)
        remember.performClick()
        assertFalse(keyboard.rememberAddresses.getValue())
        // the remembered, one forgotten, then all
        assertTrue(st.titles().toString(), st.titles().containsAll(listOf("zhang@qq.com", "li@163.com")))
        st.pref("li@163.com").performClick()
        st.idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertEquals(listOf("zhang@qq.com"), AddressStore.book().addresses.map { it.address })
        st.pref(R.string.forget_all_addresses).performClick()
        st.idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertEquals("", internal.rememberedAddresses.getValue())
        assertTrue(st.titles().toString(), st.s(R.string.remembered_addresses_none) in st.titles())

        // the email list: added as typed, each once, no non-domain; moved; put back as it came
        st.open(SettingsRoute.AddressSuffixes(AddressSuffixesFragment.EMAIL))
        assertTrue(shown().toString(), "@gmail.com" in shown())
        assertTrue(add("@Work.CN"))
        assertFalse(add("abc"))
        assertFalse(add("qq.com"))
        assertEquals(AddressLists.DEFAULT_EMAIL + "work.cn", AddressStore.lists().email)
        (st.page as AddressSuffixesFragment).ui.swapItem(list().size - 1, 0)
        st.idle()
        assertEquals("work.cn", AddressStore.lists().email.first())
        val menu = shadowOf(st.activity).optionsMenu
        val reset = (0 until menu.size()).map { menu.getItem(it) }.first { it.title.toString() == st.s(R.string.reset) }
        reset.javaClass.getMethod("invoke").invoke(reset)
        st.idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertEquals(AddressLists.DEFAULT_EMAIL, list())
        assertEquals(AddressLists.DEFAULT_EMAIL, AddressStore.lists().email)

        // the URL list: a dot put before; one taken away
        st.open(SettingsRoute.AddressSuffixes(AddressSuffixesFragment.URL))
        assertTrue(add("IO"))
        assertEquals(AddressLists.DEFAULT_URL + ".io", AddressStore.lists().url)
        (st.page as AddressSuffixesFragment).ui.removeItem(0)
        st.idle()
        assertEquals(AddressLists.DEFAULT_URL.drop(1) + ".io", AddressStore.lists().url)
    }
}
