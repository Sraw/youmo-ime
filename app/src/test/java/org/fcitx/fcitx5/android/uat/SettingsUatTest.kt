/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** UAT cases of the settings app (uat/13, 14), its pages on a fake engine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsUatTest {

    private val api = FakeFcitxAPI()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity

    @After
    fun close() = st.close()

    private fun idle() = st.idle()

    private fun open(route: SettingsRoute) = st.open(route)

    private val page: Fragment get() = st.page

    private fun s(id: Int) = st.s(id)

    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { i ->
            val p = group.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }.filter { it.isVisible }

    private fun titles() = preferences((page as PreferenceFragmentCompat).preferenceScreen).map { it.title.toString() }

    private fun capture(name: String) = st.capture("SettingsUatTest/$name")

    @Test
    @Uat("ST-01")
    fun theHomePage() {
        val prefs = preferences((page as PreferenceFragmentCompat).preferenceScreen)
        // no group titles: ten rows, each with what is behind it
        assertEquals(MainFragment.HOME.map { s(it.title) } + s(R.string.app_language), prefs.map { it.title.toString() })
        assertTrue(prefs.all { !it.summary.isNullOrEmpty() })
        // 🔍 and ? on the bar, the rest under ⋮
        val menu = shadowOf(activity).optionsMenu
        assertEquals(
            listOf(s(R.string.search_settings), s(R.string.help), s(R.string.developer), s(R.string.about)),
            (0 until menu.size()).map { menu.getItem(it).title.toString() },
        )
        capture("home")
    }

    @Test
    @Uat("ST-33")
    fun pagesFromTopToBottom() {
        activity
        open(SettingsRoute.UserWords)
        val words = preferences((page as PreferenceFragmentCompat).preferenceScreen)
        // the two switches on top, both on
        val switches = words.filterIsInstance<TwoStatePreference>()
        assertEquals(listOf(s(R.string.learning_use_habits), s(R.string.learning_auto_backup)), switches.map { it.title.toString() })
        assertTrue(switches.all { it.isChecked })
        val titles = words.map { it.title.toString() }
        for (t in listOf(R.string.learning_clear_learned, R.string.learning_backups, R.string.english_words, R.string.export_words)) {
            assertTrue("${s(t)} in $titles", s(t) in titles)
        }
        capture("user_words")
        open(SettingsRoute.HardwareKeyboard)
        val hk = titles()
        assertEquals(
            listOf(R.string.show_candidates_window, R.string.hardware_switch_shortcuts, R.string.candidates_orientation, R.string.candidates_font_size).map(::s),
            hk.filter { it.isNotEmpty() }.take(4),
        )
        capture("hardware_keyboard")
    }

    @Test
    @Uat("WD-02")
    fun aBlankPinyinIsFilledIn() {
        api.readings["幽默"] = "you mo"
        activity
        open(SettingsRoute.UserWordList(org.fcitx.fcitx5.android.engine.host.Engines.UserWord.Kind.ADDED.name))
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val (text, pinyin) = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        text.setText("幽默")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        // read as the dictionary reads it, for the user to check
        assertEquals("you mo", pinyin.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(api.calls.toString(), "addUserWord(幽默, you mo)" in api.calls)
        val list = KeyboardHarness.all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(list.toString(), "幽默" in list && list.any { "you mo" in it })
    }

    @Test
    @Uat("ST-05")
    fun aDarkThemeTurnsPreviewAndKeyboardDark() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
        try {
            activity
            open(SettingsRoute.Theme)
            val list = KeyboardHarness.all(activity.window.decorView).filterIsInstance<RecyclerView>()
                .firstOrNull { it.adapter is ThemeListAdapter }
                ?: error(KeyboardHarness.all(activity.window.decorView).filterIsInstance<RecyclerView>().map { it.adapter?.javaClass?.name }.toList().toString())
            val at = ThemeManager.getAllThemes().indexOfFirst { it.name == ThemePreset.PixelDark.name } + 1
            list.scrollToPosition(at)
            idle()
            list.findViewHolderForAdapterPosition(at)!!.itemView.performClick()
            idle()
            assertEquals(ThemePreset.PixelDark.name, ThemeManager.activeTheme.name)
            capture("theme_dark")
            // the keyboard made after it: its background the dark theme's
            KeyboardHarness(theme = null).use { h ->
                h.start()
                val bitmap = Bitmap.createBitmap(h.keyboard.width, h.keyboard.height, Bitmap.Config.ARGB_8888)
                h.keyboard.draw(Canvas(bitmap))
                val corner = bitmap.getPixel(2, bitmap.height - 2)
                assertTrue("background ${Integer.toHexString(corner)}", Color.luminance(corner) < 0.2f)
            }
        } finally {
            ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
            ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        }
    }
}
