/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * The long press editor's marks (061-text-variables §2.5: 使用变量; 061-no-cn-punct §2.3: 不转中文标点) and a swipe's
 * text of variables, as the page has them.
 */
// a phone's height: the editor's titles stay put and it opens on its long press card (061-settings-cards §5.1)
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PopupMarksEditorTest : PopupOverridesFragmentTest.EditorDialog() {

    private fun open() {
        prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
        prefs.set(AppPrefs.getInstance().internal.keySwipes, AppPrefs.getInstance().internal.keySwipes.getValue())
        st.activity
        st.open(SettingsRoute.PopupOverrides)
    }

    private fun AlertDialog.views() = KeyboardHarness.all(window!!.decorView)
    private fun AlertDialog.field() = views().filterIsInstance<EditText>().first()
    private fun AlertDialog.box(text: Int) = views().filterIsInstance<CheckBox>().first { it.text.toString() == st.s(text) }
    private fun AlertDialog.toggle(text: Int) = views().filterIsInstance<MaterialButton>().first { it.text.toString() == st.s(text) }
    private fun AlertDialog.shown() = views().filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.isShown }.map { it.text.toString() }.toList()

    // an item with a mark: its cap said with the mark's words after it
    private fun AlertDialog.tapMarked(item: String) {
        views().first { it.contentDescription?.toString()?.substringBefore(", ") == item && it.hasOnClickListeners() }.performClick()
        st.idle()
    }

    // the unit tests have no assets, the built-in table with them: the marks it converts as the user's table, [more] too
    private fun table(more: String = ""): File = File(HostConfigDir.inUse(ConfigHome.DATA).dir, "punctuation/punc.mb.zh_CN").apply {
        parentFile!!.mkdirs()
        writeText(". 。\n, ，\n< 《\n> 》\n~ ～\n_ ——\n$more")
    }

    private fun AlertDialog.check(text: Int) {
        box(text).performClick()
        st.idle()
    }

    @Test
    @Uat("ST-125")
    fun aFieldOfVariablesIsOneItemOfThemPreviewedAndTold() {
        open()
        dialogOf("q").apply {
            check(R.string.text_variables_use)
            val now = st.s(R.string.text_variables_now, "").trim()
            field().setText("  \$month月\$day日 星期\$weekday_cn\n x ")
            st.idle()
            assertTrue(shown().toString(), shown().any { it.startsWith(now) && "月" in it && "星期" in it })
            field().setText("\$yaer年")
            st.idle()
            assertTrue(shown().toString(), st.s(R.string.text_variables_dropped, "\$yaer") in shown())
            field().setText("  \$month月\$day日 星期\$weekday_cn\n x ")
            ok()
        }
        val q = stored("full/q")!!
        val item = "\$month月\$day日 星期\$weekday_cn  x"
        assertEquals(item, q.items.last())
        assertEquals(mapOf(item to ItemMark.Variables), q.marks)
        assertTrue(AppPrefs.getInstance().internal.keyLists.getValue().startsWith(KeyLists.HEADER_3))
        // the cap says it, the selected one's value over the buttons
        dialogOf("q").apply {
            assertTrue(views().any { it.contentDescription?.toString() == "$item, ${st.s(R.string.text_mark_variables)}" })
            tapMarked(item)
            assertTrue(shown().toString(), shown().any { it.startsWith(st.s(R.string.text_variables_now, "").trim()) })
            assertTrue(toggle(R.string.text_variables_use).isChecked)
            assertEquals(st.s(R.string.text_variables_use), toggle(R.string.text_variables_use).contentDescription)
            assertEquals(st.s(R.string.text_mark_on), ViewCompat.getStateDescription(toggle(R.string.text_variables_use)))
            assertFalse(toggle(R.string.text_as_is).isEnabled)
        }
    }

    @Test
    @Uat("ST-125")
    fun anItemOfVariablesIs64AtMostAndInsertedByName() {
        open()
        dialogOf("q").apply {
            check(R.string.text_variables_use)
            field().setText("x".repeat(70))
            ok()
        }
        assertEquals("x".repeat(64), stored("full/q")!!.items.last())
        dialogOf("w").apply {
            check(R.string.text_variables_use)
            views().filterIsInstance<MaterialButton>().first { it.text.toString() == st.s(R.string.text_variables_insert) }.performClick()
            st.idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, 0, 0)
            st.idle()
            assertEquals("\${year}", field().text.toString())
        }
    }

    // ST-127: on the button row only for an English mark a table converts; beside the field for those of what is added
    @Test
    @Uat("ST-127")
    fun noChinesePunctuationIsForTheMarksATableConverts() = table().let { t ->
        try {
            convertedOnly()
        } finally {
            t.delete()
        }
    }

    private fun convertedOnly() {
        open()
        // two English marks the built-in table converts, not on q's own list
        val (a, b) = listOf(",", ".", "<", ">", "~", "_").filter { it !in own("full/q") }.take(2)
        dialogOf("q").apply {
            check(R.string.text_as_is)
            assertTrue(shown().toString(), st.s(R.string.text_as_is_hint) in shown())
            // the two beside the field one or the other
            check(R.string.text_variables_use)
            assertFalse(box(R.string.text_as_is).isChecked)
            check(R.string.text_as_is)
            assertFalse(box(R.string.text_variables_use).isChecked)
            field().setText("$a ab $b")
            ok()
        }
        assertEquals(mapOf(a to ItemMark.AsIs, b to ItemMark.AsIs), stored("full/q")!!.marks)
        dialogOf("q").apply {
            tapItem("ab")
            assertFalse(toggle(R.string.text_as_is).isEnabled)
            assertTrue(toggle(R.string.text_variables_use).isEnabled)
            tapMarked(a)
            val asIs = toggle(R.string.text_as_is)
            assertTrue(asIs.isEnabled && asIs.isChecked)
            assertEquals(st.s(R.string.text_as_is), asIs.contentDescription)
            assertEquals(st.s(R.string.text_mark_on), ViewCompat.getStateDescription(asIs))
            asIs.performClick()
            st.idle()
            assertFalse(toggle(R.string.text_as_is).isChecked)
            assertEquals(st.s(R.string.text_mark_off), ViewCompat.getStateDescription(toggle(R.string.text_as_is)))
            ok()
        }
        assertEquals(mapOf(b to ItemMark.AsIs), stored("full/q")!!.marks)
    }

    // 061-no-cn-punct Q1: a key the user's own table adds can have it too
    @Test
    @Uat("ST-127")
    fun aKeyOfTheUsersOwnTableCanBeKeptAsIs() {
        val table = table("@ ＠\n")
        try {
            open()
            dialogOf("q").apply {
                check(R.string.text_as_is)
                field().setText("@ &")
                ok()
            }
            assertEquals(mapOf("@" to ItemMark.AsIs), stored("full/q")!!.marks)
        } finally {
            table.delete()
        }
    }

    // only 不转中文标点 turned on, on a key's own list: stored all the same (061-text-variables §3)
    @Test
    @Uat("ST-127")
    fun aDefaultListMarkedAloneIsStored() = table().let { t ->
        try {
            markedAlone()
        } finally {
            t.delete()
        }
    }

    private fun markedAlone() {
        open()
        click(R.string.shortcut_page_function)
        val comma = PopupOverridesFragment.FunctionLabels.getValue("comma").let(st::s)
        val before = edit("fn/comma").items
        KeyboardHarness.all(st.activity.window.decorView).first { it.contentDescription?.toString()?.startsWith(comma) == true && it.hasOnClickListeners() }.performClick()
        st.idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).apply {
            tapItem(".")
            assertTrue(toggle(R.string.text_as_is).isEnabled)
            toggle(R.string.text_as_is).performClick()
            st.idle()
            ok()
        }
        val stored = KeyLists.parse(AppPrefs.getInstance().internal.keyLists.getValue())!![KeyId("fn/comma")]!!
        assertEquals(before, stored.items)
        assertEquals(mapOf("." to ItemMark.AsIs), stored.marks)
    }

    @Test
    @Uat("ST-125")
    fun aSwipesTextOfVariablesIsSetAndPreviewed() {
        open()
        dialogOf("q").apply {
            val boxes = views().filterIsInstance<CheckBox>().filter { it.text.toString() == st.s(R.string.text_variables_use) }.toList()
            // the long press's, then each way's
            assertEquals(3, boxes.size)
            val texts = views().filterIsInstance<EditText>().toList()
            // 上滑's card opened (the long press's open first): its help shown under its field
            openCard(R.string.swipe_up)
            texts[1].setText("\${fullhour}:\$minute")
            boxes[1].performClick()
            st.idle()
            assertTrue(shown().toString(), shown().any { it.startsWith(st.s(R.string.text_variables_now, "").trim()) && ":" in it })
            ok()
        }
        val slots = SwipeSlots.parse(AppPrefs.getInstance().internal.keySwipes.getValue())
        assertEquals(SwipeSlot.Text("\${fullhour}:\$minute", variables = true), slots.resolve("full/q", true))
    }

    // a text tapped: where it is not its own target (a keyboard's card's name), what it is on
    private fun click(title: Int) {
        var v: View = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        st.idle()
    }

    // a card of the editor opened by its title row
    private fun AlertDialog.openCard(title: Int) {
        var v: View = views().filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        st.idle()
    }
}
