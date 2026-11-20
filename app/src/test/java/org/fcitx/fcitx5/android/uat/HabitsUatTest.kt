/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.preference.TwoStatePreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.picker.PickerGridAdapter
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class HabitsUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val habits = AppPrefs.getInstance().internal.useHabits

    @After
    fun close() {
        FcitxApplication.getInstance().directBootAwareContext
            .getSharedPreferences(RecentlyUsed.PREFERENCE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun KeyboardHarness.s(id: Int) = service.getString(id)

    private fun KeyboardHarness.statusArea() {
        runCatching { described(s(R.string.expand_toolbar)) }.getOrNull()?.let {
            tap(it)
            after(500)
        }
        tap(described(s(R.string.status_area)))
    }

    private fun KeyboardHarness.shown(title: String): View? =
        KeyboardHarness.all(inputView).firstOrNull { shown(it) && it is TextView && it.text.toString() == title }

    private fun KeyboardHarness.statusEntry(title: String): View =
        generateSequence(shown(title) ?: error("no $title in ${texts(inputView)}")) { it.parent as? View }.first { it.hasOnClickListeners() }

    private fun KeyboardHarness.emoji(): PickerLayout {
        tap(described(s(R.string.a11y_key_emoji)))
        return KeyboardHarness.find<PickerLayout>(inputView)!!
    }

    private fun KeyboardHarness.recentEmpty(): Boolean {
        emoji()
        return (KeyboardHarness.find<PickerGridView>(inputView)!!.adapter as PickerGridAdapter).recentEmpty
    }

    @Test
    @Uat("PR-17")
    fun offTheEntrySaysToRestoreAndTheRecentEmojiAreGoneTillOn() {
        prefs.set(habits, true)
        KeyboardHarness().use { h ->
            h.start()
            h.emoji()
            h.tap(KeyboardHarness.cells(KeyboardHarness.find<PickerGridView>(h.inputView)!!).first { KeyboardHarness.cellText(it) == "😀" })
            h.start()
            assertEquals(false, h.recentEmpty())
            h.start()
            h.statusArea()
            h.tap(h.statusEntry(h.s(R.string.learning_habits_off)))
            assertEquals(false, habits.getValue())
            h.start()
            assertEquals(true, h.recentEmpty())
            h.start()
            h.statusArea()
            h.tap(h.statusEntry(h.s(R.string.learning_habits_on)))
            assertEquals(true, habits.getValue())
            h.start()
            assertEquals(false, h.recentEmpty())
        }
    }

    @Test
    @Uat("PR-17")
    fun theSwitchOnTopOfTheUserWordsIsTheSameAsInTheStatusArea() {
        prefs.set(habits, false)
        SettingsHarness().use { st ->
            st.activity
            st.open(SettingsRoute.UserWords)
            val switch = st.pref(R.string.learning_use_habits) as TwoStatePreference
            assertEquals(false, switch.isChecked)
            switch.performClick()
            st.idle()
            assertEquals(true, habits.getValue())
        }
    }
}
