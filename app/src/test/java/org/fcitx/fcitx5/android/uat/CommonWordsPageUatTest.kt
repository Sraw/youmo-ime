/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.pinyin.CommonWordsManager
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.input.KeyboardHarness
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.ui.common.DynamicListAdapter
import org.fcitx.fcitx5.android.ui.common.DynamicListTouchCallback
import org.fcitx.fcitx5.android.ui.main.settings.CommonWordsFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until as waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration

/**
 * 常用词's page (dev/design/060-common-words.md, WD-39, WD-42): what [SettingsPagesUatTest] has for
 * the other pages, for this one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CommonWordsPageUatTest {

    @Before
    fun phone() = phoneStorage()

    private val api = FakeHostApi()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity

    @After
    fun close() = st.close()

    private fun idle() = st.idle()

    // a page's work done off the main thread (a search, a list read): waited for, as a user would
    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

    private fun open(route: SettingsRoute) = st.open(route)

    private val page: Fragment get() = st.page

    private fun s(id: Int, vararg args: Any) = st.s(id, *args)

    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { i ->
            val p = group.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }.filter { it.isVisible }

    private fun shown(): List<String> = KeyboardHarness.all(activity.window.decorView)
        .filter { it.isShown && it is TextView }.map { (it as TextView).text.toString() }.toList()

    private fun view(text: String): View = KeyboardHarness.all(activity.window.decorView)
        .first { it.isShown && it is TextView && it.text.toString() == text }

    private fun clickable(v: View): View = generateSequence(v) { it.parent as? View }.first { it.hasOnClickListeners() || it.isLongClickable }

    // a dialog opened by a tap may show a looper turn later under load: wait for it rather than read null
    private fun dialog(): AlertDialog {
        until("a dialog showing") { ShadowDialog.getLatestDialog()?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun commonWordsSaved(): List<String> {
        open(SettingsRoute.About)
        until("saved") { "reloadEngine()" in api.calls }
        api.calls.clear()
        return CommonWordsManager.load().words.map { it.toString() }
    }

    // 常用词 (060-common-words): added with the pinyin left blank, filled as the dictionary reads it for
    // the user to check, the replacements one a line; listed with the keys that type it; a file imported
    // after saying what it adds and skips; exported tab-separated under its header
    @Test
    @Uat("WD-39", "WD-42")
    fun aCommonWordAddedImportedAndExported() {
        CommonWordsManager.write(CommonWords.EMPTY)
        api.readings["米字旁"] = "mi zi pang"
        try {
            open(SettingsRoute.PinyinDict(""))
            until("listed on the words page") {
                preferences((page as PreferenceFragmentCompat).preferenceScreen).any { it.title == s(R.string.common_words) }
            }
            open(SettingsRoute.CommonWords)
            KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
            idle()
            val d = dialog()
            val (text, pinyin, replacements) = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()
            text.setText("米字旁")
            replacements.setText("米字旁")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until("pinyin filled") { pinyin.text.toString() == "mi zi pang" }
            assertEquals(s(R.string.common_word_same_as_word), d.getButton(AlertDialog.BUTTON_POSITIVE).let { it.performClick(); idle(); replacements.error?.toString() })
            replacements.setText("米\n糸")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            until("listed with its keys") { "米字旁 → 米、糸\nmizipang · mzp" in shown() }
            assertEquals(listOf("米字旁[mi'zi'pang] 米 糸"), commonWordsSaved())
            open(SettingsRoute.CommonWords)
            until("read") { shown().any { "米字旁" in it } }
            val file = Uri.parse("content://words.test/import.txt")
            shadowOf(activity.contentResolver).registerInputStream(
                file,
                ByteArrayInputStream("木字旁 木\n中华人民共和国[zhong hua ren min gong he guo] 中国\n米字旁[mi'zi'pang]\n".toByteArray()),
            )
            api.readings["木字旁"] = "mu zi pang"
            (page as CommonWordsFragment).importWords(file)
            val asked = dialog()
            val said = KeyboardHarness.all(asked.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(said, s(R.string.common_words_import_counts, 2, 0, 1) in said)
            assertTrue(said, s(R.string.common_words_import_line, 3, "米字旁[mi'zi'pang]", s(R.string.common_word_no_replacement)) in said)
            asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            val exportTo = Uri.parse("content://words.test/export.txt")
            val exported = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(exportTo, exported)
            (page as CommonWordsFragment).exportWords(exportTo)
            until("exported") { exported.size() > 0 }
            assertEquals(
                "# youmo-common-words 1\n米字旁[mi'zi'pang]\t米\t糸\n木字旁[mu'zi'pang]\t木\n中华人民共和国[zhong'hua'ren'min'gong'he'guo]\t中国\n",
                exported.toString(Charsets.UTF_8),
            )
            assertEquals(3, commonWordsSaved().size)
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }

    private val miZiPang = "米字旁[mi'zi'pang] 米 糸\n木字旁[mu'zi'pang] 木\n"

    private fun commonWordsPage() {
        open(SettingsRoute.CommonWords)
        until("read") { shown().any { it.startsWith("木字旁") } }
    }

    // the edit dialog of the row shown as [row]: its fields text, pinyin, replacements
    private fun editRow(row: String): Pair<AlertDialog, List<EditText>> {
        clickable(view(row)).performClick()
        val d = dialog()
        return d to KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()
    }

    private fun rowsOfWords() = shown().filter { " → " in it }.map { it.substringBefore('\n') }

    // 常用词's rows: edited in their dialog, which refuses a second row of one word and a replacement
    // with a space; one swiped off and back from the snackbar's undo; all deleted at once
    @Test
    @Uat("WD-39")
    fun aCommonWordEditedSwipedOffAndBackAndAllDeleted() {
        CommonWordsManager.write(CommonWords.parse(miZiPang + "你好[ni'hao] 您好\n").words)
        try {
            commonWordsPage()
            assertEquals(listOf("米字旁 → 米、糸", "木字旁 → 木", "你好 → 您好"), rowsOfWords())
            // a word of two's initials typed on full pinyin's 26 keys alone, as its row says
            assertTrue(shown().toString(), "你好 → 您好\nnihao · ${s(R.string.common_word_keys_full_pinyin_only, "nh")}" in shown())
            val (d, fields) = editRow("米字旁 → 米、糸\nmizipang · mzp")
            val (text, _, replacements) = fields
            text.setText("木字旁")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertEquals(s(R.string.common_word_listed), text.error?.toString())
            text.setText("米字旁")
            replacements.setText("米 糸")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertEquals(s(R.string.common_word_blank), replacements.error?.toString())
            replacements.setText("米\n糸\n丝")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            until("edited") { rowsOfWords() == listOf("米字旁 → 米、糸、丝", "木字旁 → 木", "你好 → 您好") }
            // swiped left off, and back
            val list = KeyboardHarness.all(activity.window.decorView).filterIsInstance<RecyclerView>().single { it.adapter is DynamicListAdapter<*> }
            @Suppress("UNCHECKED_CAST")
            val words = list.adapter as DynamicListAdapter<CommonWords.Word>
            val callback = DynamicListTouchCallback(activity, words)
            val swiped = words.entries[1]
            callback.onSwiped(list.findViewHolderForAdapterPosition(1)!!, ItemTouchHelper.LEFT)
            idle()
            until("swiped off") { rowsOfWords() == listOf("米字旁 → 米、糸、丝", "你好 → 您好") }
            // undone: what the snackbar's undo does (BaseDynamicListUi.onItemRemoved), the row put back
            // where it was; Robolectric shows no Snackbar on this page to tap
            words.addItem(1, swiped)
            idle()
            until("back") { rowsOfWords() == listOf("米字旁 → 米、糸、丝", "木字旁 → 木", "你好 → 您好") }
            assertEquals(listOf("米字旁[mi'zi'pang] 米 糸 丝", "木字旁[mu'zi'pang] 木", "你好[ni'hao] 您好"), commonWordsSaved())
            commonWordsPage()
            (page as CommonWordsFragment).deleteAll()
            val asked = dialog()
            assertTrue(KeyboardHarness.all(asked.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == s(R.string.common_words_delete_all, 3) })
            asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            until("none") { rowsOfWords().isEmpty() }
            assertEquals(emptyList<String>(), commonWordsSaved())
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }

    // the keyboard deletes a replacement while the page is open, and while it is away: the page's
    // save keeps the deletion, the page shows it once back, and its next edit does not put it back
    @Test
    @Uat("WD-39", "WD-43")
    fun whatTheKeyboardDeletedStaysDeletedThroughThePagesEdits() {
        val base = CommonWords.parse(miZiPang).words
        CommonWordsManager.write(base)
        try {
            commonWordsPage()
            val (d, fields) = editRow("木字旁 → 木\nmuzipang · mzp")
            fields[2].setText("木\n目")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            until("edited") { "木字旁 → 木、目" in rowsOfWords() }
            // the keyboard took 糸 off, the page open
            CommonWordsManager.saveOver(base, base.without("米字旁", "糸"))
            st.leaveAndReturn()
            until("shown as saved") { rowsOfWords() == listOf("米字旁 → 米", "木字旁 → 木、目") }
            // and 米 with the page away
            val saved = CommonWordsManager.load()
            activityStopped {
                CommonWordsManager.saveOver(saved, saved.without("米字旁", "米"))
            }
            until("shown as the keyboard left it") { rowsOfWords() == listOf("木字旁 → 木、目") }
            val (d2, fields2) = editRow("木字旁 → 木、目\nmuzipang · mzp")
            fields2[2].setText("目")
            d2.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertEquals(listOf("木字旁[mu'zi'pang] 目"), commonWordsSaved())
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }

    // an edit dialog open as the page comes back and reads the file anew: what it saves goes to the
    // row of its word as listed now, not to where the word was
    @Test
    @Uat("WD-39")
    fun anEditOpenAsTheFileIsReadAgainGoesToItsWordsRow() {
        val base = CommonWords.parse(miZiPang).words
        CommonWordsManager.write(base)
        try {
            commonWordsPage()
            val (d, fields) = editRow("米字旁 → 米、糸\nmizipang · mzp")
            // the keyboard took 糸 off: the word the dialog opened with is listed no more
            activityStopped { CommonWordsManager.saveOver(base, base.without("米字旁", "糸")) }
            until("read again") { rowsOfWords() == listOf("米字旁 → 米", "木字旁 → 木") }
            fields[2].setText("米\n丝")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            until("edited") { rowsOfWords() == listOf("米字旁 → 米、丝", "木字旁 → 木") }
            assertEquals(listOf("米字旁[mi'zi'pang] 米 丝", "木字旁[mu'zi'pang] 木"), commonWordsSaved())
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }

    // an export carries the file's lines the page could not read (one with no pinyin), as the file keeps them:
    // moved to a new phone with it, nothing is lost
    @Test
    @Uat("WD-42")
    fun anExportKeepsTheLinesNotRead() {
        val file = "# youmo-common-words 1\n木字旁[mu'zi'pang]\t木\n米字旁\t米\n"
        CommonWordsManager.write(CommonWords.parse(file).words)
        try {
            open(SettingsRoute.CommonWords)
            until("read") { shown().any { it.startsWith("木字旁") } }
            val exportTo = Uri.parse("content://words.test/export.txt")
            val exported = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(exportTo, exported)
            (page as CommonWordsFragment).exportWords(exportTo)
            until("exported") { exported.size() > 0 }
            assertEquals(file, exported.toString(Charsets.UTF_8))
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }

    // what happens while the page is away, another app over it
    private fun activityStopped(meanwhile: () -> Unit) = st.leaveAndReturn(meanwhile)

}
