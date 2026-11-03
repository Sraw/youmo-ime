/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.data.theme.deriveCustomNoBackground
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.AddressSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListAdapter
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** UAT cases of uat/14 on the settings app's own pages: search, help, language, about, themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class SettingsMenuUatTest {

    @Before
    fun phone() = phoneStorage()

    private val st = SettingsHarness()

    @After
    fun close() = st.close()

    private fun texts() = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }.toList()

    private val navController get() = (st.activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
        .navController

    private val route get() = navController.currentDestination?.route.orEmpty()

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    @Uat("ST-02")
    fun searchingFindsFuzzyPinyinAndGoesThere() {
        st.open(SettingsRoute.Search)
        val field = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<EditText>().first()
        field.setText("模糊音")
        st.idle()
        val list = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first()
        list.measure(0, 0)
        list.layout(0, 0, 1080, 2000)
        st.idle()
        val shown = texts()
        assertTrue(shown.toString(), "模糊音" in shown && shown.any { st.s(R.string.shared_input) in it })
        val row = (0 until list.childCount).map(list::getChildAt)
            .first { r -> KeyboardHarness.all(r).filterIsInstance<TextView>().any { it.text.toString() == "模糊音" } }
        row.performClick()
        st.idle()
        assertTrue(route, "SharedInput" in route)
    }

    @Test
    @Uat("ST-47")
    fun searchingFindsTheNineKeysAndTheAddressRows() {
        // a 九键 setting while pinyin is on the full keyboard: its page, pointed at the 键盘 row that shows it
        val (key, via) = searchAndOpen(st.s(R.string.t9_multi_tap)) { st.s(R.string.im_pinyin) in it }
        assertTrue(route, "InputMethodConfig" in route)
        assertEquals(AppPrefs.T9_MULTI_TAP, key)
        assertEquals(AppPrefs.PINYIN_KEYBOARD, via)
        st.activity.onBackPressedDispatcher.onBackPressed()
        st.idle()
        // a row of 网址和邮箱: that page, pointed at the row itself
        assertEquals(AddressSettingsFragment.EMAIL_KEY to null, searchAndOpen(st.s(R.string.email_suffixes)) { true })
        assertTrue(route, "Addresses" in route)
    }

    /**
     * Searches [query] and opens the result titled so whose path [under] takes; the setting the
     * opened page is to point at and the row to point at while it is hidden. Read from the result's
     * entry as SearchHighlight keeps them: the flash itself waits for an input method page's
     * options, which come from the engine, not here.
     */
    private fun searchAndOpen(query: String, under: (String) -> Boolean): Pair<String?, String?> {
        if ("Search" !in route) st.open(SettingsRoute.Search)
        val field = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<EditText>().first()
        field.setText(query)
        st.idle()
        val list = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first()
        list.measure(0, 0)
        list.layout(0, 0, 1080, 2000)
        st.idle()
        val row = (0 until list.childCount).map(list::getChildAt).first { r ->
            val shown = KeyboardHarness.all(r).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            query in shown && shown.any { it != query && under(it) }
        }
        row.performClick()
        val pointed = SearchHighlight.pointedAt(navController.currentBackStackEntry!!)
        st.idle()
        return pointed
    }

    @Test
    @Uat("ST-03")
    fun helpOpensTheManual() {
        val menu = shadowOf(st.activity).optionsMenu
        val item = (0 until menu.size()).map(menu::getItem).first { it.title.toString() == st.s(R.string.help) }
        // MenuItemImpl's own, what its button's tap calls
        item.javaClass.getMethod("invoke").invoke(item)
        st.idle()
        val started = shadowOf(st.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Const.helpUrl, started.dataString)
        assertTrue(Const.helpUrl, Const.helpUrl.startsWith("https://github.com/"))
    }

    @Test
    @Uat("ST-25")
    fun theLanguagesOfferedAreFour() {
        st.pref(R.string.app_language).performClick()
        st.idle()
        val d = dialog()
        val items = (0 until d.listView.adapter.count).map { d.listView.adapter.getItem(it).toString() }
        assertEquals(listOf("跟随系统", "简体中文", "繁體中文", "English"), items)
        try {
            d.listView.performItemClick(null, 3, 3)
            st.idle()
            assertEquals("en", AppPrefs.getInstance().internal.appLanguage.getValue())
            assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        } finally {
            AppPrefs.getInstance().internal.appLanguage.setValue("")
            AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    @Uat("ST-31")
    fun aboutListsVersionsSourceAndLicenses() {
        st.open(SettingsRoute.About)
        val titles = st.titles()
        for (id in listOf(R.string.current_version, R.string.build_git_hash, R.string.build_time, R.string.engine_data,
            R.string.source_code, R.string.privacy_policy, R.string.license, R.string.open_source_licenses)) {
            assertTrue("${st.s(id)} in $titles", st.s(id) in titles)
        }
        st.pref(R.string.open_source_licenses).performClick()
        st.idle()
        assertTrue(route, "License" in route)
    }

    private fun themeList(): RecyclerView = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>()
        .first { it.adapter is ThemeListAdapter }

    private fun pick(name: String) {
        val list = themeList()
        // under the 新增 row
        val at = (list.adapter as ThemeListAdapter).entries.indexOfFirst { it.name == name } + 1
        list.scrollToPosition(at)
        st.idle()
        list.findViewHolderForAdapterPosition(at)!!.itemView.performClick()
        st.idle()
    }

    private fun keyboardIsDark(): Boolean = KeyboardHarness(theme = null).use { h ->
        h.start()
        val bitmap = Bitmap.createBitmap(h.keyboard.width, h.keyboard.height, Bitmap.Config.ARGB_8888)
        h.keyboard.draw(Canvas(bitmap))
        Color.luminance(bitmap.getPixel(2, bitmap.height - 2)) < 0.2f
    }

    @Test
    @Uat("ST-36")
    fun followingTheSystemAThemeTappedAsks() {
        val follow = ThemeManager.prefs.followSystemDayNightTheme
        follow.setValue(true)
        ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
        try {
            st.open(SettingsRoute.Theme)
            val before = ThemeManager.activeTheme.name
            pick(ThemePreset.PixelDark.name)
            assertTrue(texts().toString() + dialogText(), st.s(R.string.theme_message_follow_system_day_night_mode_enabled) == dialogText())
            dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            st.idle()
            assertEquals(before, ThemeManager.activeTheme.name)
            pick(ThemePreset.PixelDark.name)
            dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            st.idle()
            assertFalse(follow.getValue())
            assertEquals(ThemePreset.PixelDark.name, ThemeManager.activeTheme.name)
            assertTrue(keyboardIsDark())
        } finally {
            ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
            follow.setValue(follow.defaultValue)
        }
    }

    private fun dialogText() = dialog().findViewById<TextView>(android.R.id.message)?.text?.toString()

    @Test
    @Uat("ST-35")
    fun themesAreMadeOnTheWebAndOnesOwnExportedOrDeletedFromALongPress() {
        val follow = ThemeManager.prefs.followSystemDayNightTheme
        follow.setValue(false)
        val mine = ThemePreset.PixelDark.deriveCustomNoBackground("mine")
        assertTrue(ThemeManager.saveTheme(mine))
        try {
            st.open(SettingsRoute.Theme)
            // 新增主题: an import and the web page, nothing made here
            themeList().findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            st.idle()
            val adds = dialog().listView.adapter.let { a -> (0 until a.count).map { a.getItem(it).toString() } }
            assertEquals(listOf("导入文件…", "在网页上制作…"), adds)
            dialog().dismiss()
            st.idle()
            // one's own theme: no edit button, its long press 导出 or 删除
            pick("mine")
            assertEquals("mine", ThemeManager.activeTheme.name)
            val at = (themeList().adapter as ThemeListAdapter).entries.indexOfFirst { it.name == "mine" } + 1
            themeList().findViewHolderForAdapterPosition(at)!!.itemView.performLongClick()
            st.idle()
            val actions = dialog().listView.adapter.let { a -> (0 until a.count).map { a.getItem(it).toString() } }
            assertEquals(listOf("导出", "删除"), actions)
            dialog().listView.performItemClick(null, 1, 1)
            st.idle()
            dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            st.idle()
            assertTrue(ThemeManager.getAllThemes().none { it.name == "mine" })
            assertTrue((themeList().adapter as ThemeListAdapter).entries.none { it.name == "mine" })
            // the theme in use gone: the default's back
            assertEquals(ThemeManager.prefs.normalModeTheme.defaultValue.name, ThemeManager.activeTheme.name)
        } finally {
            ThemeManager.deleteTheme("mine")
            ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
            follow.setValue(follow.defaultValue)
        }
    }

    // SDK 33: the keyboard selected is read from Settings.Secure there, which Robolectric keeps; from 34
    // it is InputMethodManager.getCurrentInputMethodInfo, which it has no shadow for
    @Test
    @Uat("SET-02")
    @Config(sdk = [33])
    fun enabledAndSelectedTheAppOpensOnItsHomePage() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ours = android.view.inputmethod.InputMethodInfo(context.packageName, InputMethodUtil.serviceName, "幽默输入法", null)
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        shadowOf(imm).setEnabledInputMethodInfoList(listOf(ours))
        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, InputMethodUtil.componentName)
        SetupActivity.skipped = false
        try {
            st.activity
            // no wizard over it: the home page's ten rows
            val next = shadowOf(st.activity).nextStartedActivity
            assertTrue("started ${next?.component}", next?.component?.className != SetupActivity::class.java.name)
            assertEquals(MainFragment.HOME.map { st.s(it.title) } + st.s(R.string.app_language), st.titles())
        } finally {
            shadowOf(imm).setEnabledInputMethodInfoList(emptyList())
            android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, null)
        }
    }
}
