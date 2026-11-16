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
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.deriveCustomNoBackground
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.AddressSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsIndexModel
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListAdapter
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** UAT cases of uat/14 on the settings app's own pages: search, help, language, about, themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class SettingsMenuUatTest {

    @Before
    fun phone() {
        phoneStorage()
        // the index built where it is asked for: each case's results there after an idle; the cases of its own
        // thread below set theirs
        SettingsIndexModel.Hooks.dispatcher = Dispatchers.Unconfined
        SettingsIndexModel.Hooks.builds.set(0)
    }

    private val st = SettingsHarness()

    @After
    fun close() {
        st.close()
        SettingsIndexModel.Hooks.dispatcher = Dispatchers.Default
        SettingsIndexModel.Hooks.latest = null
    }

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
        assertEquals("pref:" + AppPrefs.T9_MULTI_TAP, key)
        assertEquals("pref:" + AppPrefs.PINYIN_KEYBOARD, via)
        st.activity.onBackPressedDispatcher.onBackPressed()
        st.idle()
        // a row of 网址和邮箱: that page, pointed at the row itself
        assertEquals("pref:" + AddressSettingsFragment.EMAIL_KEY to null, searchAndOpen(st.s(R.string.email_suffixes)) { true })
        assertTrue(route, "Addresses" in route)
    }

    /** The titles of the search page's results, the list's rows (not the field, which holds the query). */
    private fun resultTitles(): List<String> {
        val list = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first()
        list.measure(0, 0)
        list.layout(0, 0, 1080, 2000)
        st.idle()
        return (0 until list.childCount).map { KeyboardHarness.all(list.getChildAt(it)).filterIsInstance<TextView>().first().text.toString() }
    }

    private fun type(query: String) =
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<EditText>().first().setText(query)

    // the index lands after the page is up: what was typed before is searched then, the last of it, with no
    // 没有结果 shown meanwhile
    @Test
    fun whatIsTypedBeforeTheIndexLandsIsSearchedWhenItDoes() {
        val build = StandardTestDispatcher()
        SettingsIndexModel.Hooks.dispatcher = build
        st.open(SettingsRoute.Search)
        type(st.s(R.string.search_stroke_commands))
        type("模糊音")
        st.idle()
        assertEquals(0, SettingsIndexModel.Hooks.builds.get())
        assertEquals(emptyList<String>(), resultTitles())
        assertFalse(texts().toString(), st.s(R.string.search_settings_none) in texts())
        build.scheduler.runCurrent()
        st.idle()
        val titles = resultTitles()
        assertTrue(titles.toString(), "模糊音" in titles && st.s(R.string.search_stroke_commands) !in titles)
        assertEquals(1, SettingsIndexModel.Hooks.builds.get())
    }

    // a build not yet run when the page goes is cancelled with it: it never runs
    @Test
    fun aBuildNotDoneIsCancelledWithThePage() {
        val build = StandardTestDispatcher()
        SettingsIndexModel.Hooks.dispatcher = build
        st.open(SettingsRoute.Search)
        val pending = SettingsIndexModel.Hooks.latest!!
        navController.popBackStack()
        st.idle()
        assertFalse(route, "Search" in route)
        assertTrue(pending.isCancelled)
        build.scheduler.runCurrent()
        assertEquals(0, SettingsIndexModel.Hooks.builds.get())
    }

    // a rotation takes the build under way, or the one done, not another
    @Test
    fun aRotationKeepsTheBuild() {
        val build = StandardTestDispatcher()
        SettingsIndexModel.Hooks.dispatcher = build
        st.open(SettingsRoute.Search)
        val underWay = SettingsIndexModel.Hooks.latest
        st.recreate()
        assertTrue(route, "Search" in route)
        assertSame(underWay, SettingsIndexModel.Hooks.latest)
        build.scheduler.runCurrent()
        st.idle()
        st.recreate()
        build.scheduler.runCurrent()
        st.idle()
        assertSame(underWay, SettingsIndexModel.Hooks.latest)
        assertEquals(1, SettingsIndexModel.Hooks.builds.get())
        type("模糊音")
        st.idle()
        assertTrue("模糊音" in resultTitles())
    }

    // the language changed while the page is up: the build of the old one is dropped, the new one built and searched
    @Test
    @Config(sdk = [35])
    fun aNewLanguageBuildsTheIndexAnew() {
        val build = StandardTestDispatcher()
        SettingsIndexModel.Hooks.dispatcher = build
        st.open(SettingsRoute.Search)
        val chinese = SettingsIndexModel.Hooks.latest!!
        RuntimeEnvironment.setQualifiers("en-rUS-w393dp-h851dp-port-440dpi")
        st.recreate()
        assertTrue(route, "Search" in route)
        assertTrue(chinese.isCancelled)
        build.scheduler.runCurrent()
        st.idle()
        // the Chinese one, cancelled before it ran, never did
        assertEquals(1, SettingsIndexModel.Hooks.builds.get())
        type("fuzzy")
        st.idle()
        val titles = resultTitles()
        assertTrue(titles.toString(), titles.any { it.startsWith("Fuzzy") })
    }

    // 6T: 84 ms the first time, over a frame: never on the main thread (061-settings-search.md §4.11)
    @Test
    fun theIndexIsNotBuiltOnTheMainThread() {
        SettingsIndexModel.Hooks.dispatcher = Dispatchers.Default
        st.open(SettingsRoute.Search)
        // done, however long it takes: no clock
        runBlocking { SettingsIndexModel.Hooks.latest!!.await() }
        st.idle()
        assertEquals(1, SettingsIndexModel.Hooks.builds.get())
        assertNotSame(Looper.getMainLooper().thread, SettingsIndexModel.Hooks.builtOn)
        type("模糊音")
        st.idle()
        assertTrue("模糊音" in resultTitles())
    }

    /** Searches [query]: each result's title and the line under it, in order. */
    private fun results(query: String): List<Pair<String, String>> {
        if ("Search" !in route) st.open(SettingsRoute.Search)
        val field = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<EditText>().first()
        field.setText(query)
        st.idle()
        val list = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first()
        list.measure(0, 0)
        list.layout(0, 0, 1080, 20000)
        st.idle()
        return (0 until list.childCount).map(list::getChildAt).map { r ->
            val shown = KeyboardHarness.all(r).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            shown[0] to shown.getOrElse(1) { "" }
        }
    }

    /** ST-156, ST-159: 笔画筛选 finds four in this order, said as what each is. */
    private fun strokeFilterFindsFour(query: String, alias: String) {
        val found = results(query)
        assertEquals(
            found.toString(),
            listOf(st.s(R.string.stroke_filter), st.s(R.string.stroke_lift), st.s(R.string.stroke_singles), st.s(R.string.search_stroke_commands)),
            found.map { it.first },
        )
        assertTrue(found[0].second, found[0].second.lines().first().endsWith(" · " + st.s(R.string.search_is_choice)))
        assertTrue(found[2].second, found[2].second.lines().first().endsWith(" · " + st.s(R.string.search_also_called, alias)))
        assertTrue(found[3].second, found[3].second.lines().first().endsWith(" · " + st.s(R.string.search_is_command) + " · " + st.s(R.string.search_also_called, alias)))
    }

    @Test
    @Uat("ST-156")
    fun searchingAChoiceOpensItsSetting() {
        strokeFilterFindsFour("笔画筛选", "笔画筛选")
        val (key, via) = searchAndOpen(st.s(R.string.stroke_filter)) { st.s(R.string.search_is_choice) in it }
        assertTrue(route, "SharedInput" in route)
        assertEquals("pref:" + AppPrefs.KEY_DRAWING to null, key to via)
        // the row pointed at, its list not opened
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true)
    }

    @Test
    @Uat("ST-157")
    fun searchingAKeyCommandOpensTheKeysPageWithAHint() {
        val hint = { name: String -> st.s(R.string.search_command_hint, name) }
        // by another name of the five, as ST-157 has it
        searchAndOpen("横竖撇点折", title = st.s(R.string.search_stroke_commands)) { st.s(R.string.search_is_command) in it }
        assertTrue(route, "PopupOverrides" in route)
        assertTrue(texts().toString(), hint(st.s(R.string.search_strokes_one)) in texts())
        st.activity.onBackPressedDispatcher.onBackPressed()
        st.idle()
        searchAndOpen(st.s(R.string.shortcut_copy)) { st.s(R.string.search_is_command) in it }
        assertTrue(route, "PopupOverrides" in route)
        assertTrue(texts().toString(), hint(st.s(R.string.shortcut_copy)) in texts())
        // it stays while the user does what it says, until 知道了
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(texts().toString(), hint(st.s(R.string.shortcut_copy)) in texts())
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == st.s(R.string.search_got_it) }.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(texts().toString(), hint(st.s(R.string.shortcut_copy)) in texts())
        // nor does it outlive the page it is about: back to the search, it is gone
        st.activity.onBackPressedDispatcher.onBackPressed()
        st.idle()
        searchAndOpen(st.s(R.string.shortcut_copy)) { st.s(R.string.search_is_command) in it }
        assertTrue(texts().toString(), hint(st.s(R.string.shortcut_copy)) in texts())
        st.activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(route, "Search" in route)
        assertFalse(texts().toString(), hint(st.s(R.string.shortcut_copy)) in texts())
    }

    @Test
    @Uat("ST-158")
    fun searchingBySynonym() {
        for ((query, title) in listOf(
            "震动" to R.string.button_haptic_feedback,
            "罕见字" to R.string.rare_chars,
            "夜间模式" to R.string.follow_system_day_night_theme,
        )) {
            val first = results(query).first()
            assertEquals(query, st.s(title), first.first)
            assertTrue(first.second, st.s(R.string.search_also_called, query) in first.second)
        }
        val height = results("键盘高度")
        assertEquals(st.s(R.string.keyboard_height), height.first().first)
        // its choices are found by their own names, not by the setting's
        assertTrue(height.toString(), height.none { it.first in setOf("紧凑", "标准", "很高") })
    }

    @Test
    @Uat("ST-159")
    @Config(qualifiers = "zh-rTW-w393dp-h851dp-port-440dpi")
    fun strokeFilterFindsTheSameFourInTraditionalChinese() = strokeFilterFindsFour("筆畫篩選", "筆畫篩選")

    @Test
    @Uat("ST-159")
    @Config(qualifiers = "en-w393dp-h851dp-port-440dpi")
    fun strokeFilterFindsTheSameFourInEnglish() = strokeFilterFindsFour("stroke filter", "stroke filter")

    // the home page's language row: back to the home page already under the search, pointed at the row
    @Test
    fun theLanguageRowGoesBackToTheHomePage() {
        val depth = { navController.currentBackStack.value.size }
        val home = depth()
        val (key, _) = searchAndOpen(st.s(R.string.app_language)) { true }
        assertEquals("pref:" + MainFragment.LANGUAGE_KEY, key)
        assertTrue(route, "Index" in route)
        assertEquals(home, depth())
    }

    /**
     * Searches [query] and opens the result titled so ([title]) whose path [under] takes; the setting the
     * opened page is to point at and the row to point at while it is hidden. Read from the result's
     * entry as SearchHighlight keeps them: the flash itself waits for an input method page's
     * options, which come from the engine, not here.
     */
    private fun searchAndOpen(query: String, title: String = query, under: (String) -> Boolean): Pair<String?, String?> {
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
            title in shown && shown.any { it != title && under(it) }
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

    // not at 29: Robolectric there dies in appcompat's recreate below Android 13 (a sync barrier token
    // "not posted"); picking English on Android 10 itself (AVD youmo29) switches the app as it should
    @Test
    @Uat("ST-25")
    @Config(sdk = [35])
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

    private fun pick(name: String) = pickTheme(themeList(), name, st::idle)

    private fun keyboardIsDark(): Boolean = KeyboardHarness(theme = null).use { h ->
        h.start()
        val bitmap = Bitmap.createBitmap(h.keyboard.width, h.keyboard.height, Bitmap.Config.ARGB_8888)
        h.keyboard.draw(Canvas(bitmap))
        Color.luminance(bitmap.getPixel(2, bitmap.height - 2)) < 0.2f
    }

    @Test
    @Uat("ST-36")
    fun followingTheSystemAThemeTappedTakesBothSlots() {
        val prefs = ThemeManager.prefs
        prefs.followSystemDayNightTheme.setValue(true)
        try {
            st.open(SettingsRoute.Theme)
            // one of two versions: each slot its version, no dialog
            pick(TestThemes.PixelDark.name)
            assertEquals("pixel#light", prefs.lightModeTheme.getValue().name)
            assertEquals("pixel#dark", prefs.darkModeTheme.getValue().name)
            // one of one version (dark only): both slots, used in the light mode too
            pick("amoled-black")
            assertEquals("amoled-black", prefs.lightModeTheme.getValue().name)
            assertEquals("amoled-black", prefs.darkModeTheme.getValue().name)
            assertEquals("amoled-black", ThemeManager.activeTheme.name)
            // the normal slot too, the one used once following is turned off
            assertEquals("amoled-black", prefs.normalModeTheme.getValue().name)
            assertTrue(keyboardIsDark())
        } finally {
            prefs.lightModeTheme.setValue(prefs.lightModeTheme.defaultValue)
            prefs.darkModeTheme.setValue(prefs.darkModeTheme.defaultValue)
            prefs.normalModeTheme.setValue(prefs.normalModeTheme.defaultValue)
            prefs.followSystemDayNightTheme.setValue(prefs.followSystemDayNightTheme.defaultValue)
        }
    }

    @Test
    @Uat("ST-35")
    fun themesAreImportedAndOnesOwnExportedOrDeletedFromALongPress() {
        val follow = ThemeManager.prefs.followSystemDayNightTheme
        follow.setValue(false)
        val mine = TestThemes.PixelDark.deriveCustomNoBackground("mine")
        assertTrue(ThemeManager.saveTheme(mine))
        try {
            st.open(SettingsRoute.Theme)
            // 新增主题: a menu (060-render-p3 M5), 导入主题文件 the import's file picker, 在网页上制作 the browser
            themeList().findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            st.idle()
            val menu = dialog().listView
            assertEquals(2, menu.adapter.count)
            menu.performItemClick(menu.adapter.getView(0, null, menu), 0, menu.adapter.getItemId(0))
            st.idle()
            assertEquals(Intent.ACTION_GET_CONTENT, shadowOf(st.activity).nextStartedActivity?.action)
            // one's own theme: no edit button, its long press 信息 (060-render-p3 T8a), 导出 or 删除
            pick("mine")
            assertEquals("mine", ThemeManager.activeTheme.name)
            val at = (themeList().adapter as ThemeListAdapter).entries.indexOfFirst { it.name == "mine" } + 1
            val row = themeList().findViewHolderForAdapterPosition(at)!!.itemView
            // no edit button: nothing in the row takes a tap but the row itself (which picks the theme)
            assertEquals(0, KeyboardHarness.all(row).count { it !== row && it.hasOnClickListeners() })
            row.performLongClick()
            st.idle()
            val actions = dialog().listView.adapter.let { a -> (0 until a.count).map { a.getItem(it).toString() } }
            assertEquals(listOf("信息", "导出", "删除"), actions)
            dialog().listView.performItemClick(null, 2, 2)
            st.idle()
            dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            st.idle()
            assertTrue(ThemeManager.getAllThemes().none { it.name == "mine" })
            assertTrue((themeList().adapter as ThemeListAdapter).entries.none { it.name == "mine" })
            // the theme in use gone: the default's back
            assertEquals(ThemeManager.prefs.normalModeTheme.defaultValue.name, ThemeManager.activeTheme.name)
        } finally {
            ThemeManager.deleteTheme("mine")
            ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
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
