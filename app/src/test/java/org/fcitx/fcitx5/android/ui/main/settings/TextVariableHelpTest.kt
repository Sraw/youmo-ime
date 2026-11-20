/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.Activity
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class TextVariableHelpTest {

    private val app = RuntimeEnvironment.getApplication()
    private val ctx = ContextThemeWrapper(app, R.style.Theme_FcitxAppTheme)

    // descriptions match names by place (TextVariableHelp.choose): one each in every language
    @Test
    fun theDescriptionsAreOnePerVariableInEachLanguage() {
        assertRows(
            *listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE).map { locale ->
                "$locale" to {
                    val res = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) }).resources
                    assertEquals(TextVariables.NAMES.size, res.getStringArray(R.array.text_variable_descriptions).size)
                }
            }.toTypedArray(),
        )
    }

    // what is typed now, at the help's clock; what is not filled in told
    @Test
    fun theHelpPreviewsAtItsClockAndTellsWhatIsNotFilledIn() {
        val field = EditText(ctx)
        val at = Calendar.getInstance().apply { set(2001, Calendar.FEBRUARY, 3) }
        val help = TextVariableHelp(ctx, field, now = { at })
        help.on = true
        field.setText("\${year}")
        assertEquals(ctx.getString(R.string.text_variables_now, "2001"), help.previewed)
        assertEquals("", help.told)
        field.setText("\${yaer}")
        assertEquals(ctx.getString(R.string.text_variables_dropped, "\${yaer}"), help.told)
    }

    private fun section(initial: SwipeSlot): Pair<SwipeSlotSection, Activity> {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val section = SwipeSlotSection(ContextThemeWrapper(host, R.style.Theme_FcitxAppTheme), off = false, initial = initial)
        host.setContentView(section.view)
        return section to host
    }

    private fun SwipeSlotSection.radio(text: Int) =
        KeyboardHarness.all(view).filterIsInstance<RadioButton>().first { it.text.toString() == ctx.getString(text) }

    // the help under 文字, the summary as it types now
    @Test
    fun aSwipesTextWithVariablesIsSummedUpFilledIn() {
        val (section, _) = section(SwipeSlot.Text("x"))
        KeyboardHarness.all(section.view).filterIsInstance<CheckBox>().single().performClick()
        val field = KeyboardHarness.all(section.view).filterIsInstance<EditText>().single()
        field.setText("\${year}")
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        assertEquals(ctx.getString(R.string.text_variables_now, year), section.variableHelp.previewed)
        assertEquals(ctx.getString(R.string.shortcut_command_is, ctx.getString(R.string.shortcut_text), year), section.summary())
    }

    // another way picked: the field hidden and let go, or the keys type into it and pick 文字 again
    @Test
    @Uat("ST-97")
    fun anotherWayPickedPutsTheTextsKeyboardAway() {
        val (section, host) = section(SwipeSlot.First)
        section.radio(R.string.shortcut_text).performClick()
        val field = KeyboardHarness.all(section.view).filterIsInstance<EditText>().single()
        val imm = host.getSystemService(InputMethodManager::class.java)
        assertTrue(field.hasFocus())
        assertTrue(shadowOf(imm).isSoftInputVisible)
        section.radio(R.string.slot_empty).performClick()
        assertFalse(field.hasFocus())
        assertFalse(shadowOf(imm).isSoftInputVisible)
        assertEquals(SwipeSlot.Empty, section.chosen())
    }
}
