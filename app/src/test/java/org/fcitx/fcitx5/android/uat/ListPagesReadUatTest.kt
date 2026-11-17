/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.net.Uri
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.pinyin.CommonWordsManager
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.input.KeyboardHarness
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
import java.time.Duration

/**
 * UAT 0.6.1 (in 0.6.0 too, WD-65): a list page's read of its file went in as a batch the user added, so
 * opening 自定义短语 or 常用词 offered 「已添加 N 个项目 / 撤销」, and the undo deleted what was read, saved so
 * as the page was left. 常用词's import, the whole list put in again, offered the same, its undo deleting the
 * words there before the import too. What is read is shown, not added (`DynamicListAdapter.showAll`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ListPagesReadUatTest {

    @Before
    fun phone() = phoneStorage()

    private val api = FakeHostApi()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity

    @After
    fun close() = st.close()

    private fun idle() = st.idle()

    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

    private fun s(id: Int, vararg args: Any) = st.s(id, *args)

    private fun shown(): List<String> = KeyboardHarness.all(activity.window.decorView)
        .filter { it.isShown && it is TextView }.map { (it as TextView).text.toString() }.toList()

    private fun dialog(): AlertDialog {
        until("a dialog showing") { ShadowDialog.getLatestDialog()?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun undoOffered() = shown().any { it == s(R.string.undo) }

    // the page left, as going back to type: it saves then, and the engine reads it again
    private fun <T> savedAs(read: () -> T): T {
        st.open(SettingsRoute.About)
        until("saved") { "reloadEngine()" in api.calls }
        api.calls.clear()
        return read()
    }

    @Test
    @Uat("WD-65")
    fun openingTheCustomPhrasesOffersNoUndoOfThePhrasesRead() {
        CustomPhraseManager.save(listOf(PinyinCustomPhrase("bj", 1, "北京")))
        try {
            st.open(SettingsRoute.PinyinCustomPhrase)
            until("listed") { shown().any { "北京" in it } }
            idle()
            assertTrue(shown().toString(), shown().none { it == s(R.string.added_n_items, 1) } && !undoOffered())
            // what the page does say of a phrase the user adds, so the snackbar would be seen were it shown
            KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
            idle()
            val d = dialog()
            val fields = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()
            listOf("sh", "1", "上海").zip(fields).forEach { (v, f) -> f.setText(v) }
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until("said") { shown().any { it == s(R.string.added_x, "sh,1=上海") } }
            assertEquals(listOf("bj,1=北京", "sh,1=上海"), savedAs { CustomPhraseManager.load().map { "${it.key},${it.order}=${it.value}" } })
        } finally {
            CustomPhraseManager.save(emptyList())
        }
    }

    @Test
    @Uat("WD-65")
    fun openingOrImportingTheCommonWordsOffersNoUndoThatDeletesThem() {
        CommonWordsManager.write(CommonWords.parse("米字旁[mi'zi'pang] 米 糸\n木字旁[mu'zi'pang] 木\n").words)
        try {
            st.open(SettingsRoute.CommonWords)
            until("read") { shown().any { it.startsWith("木字旁") } }
            idle()
            assertTrue(shown().toString(), !undoOffered())
            val file = Uri.parse("content://words.test/import.txt")
            shadowOf(activity.contentResolver).registerInputStream(file, ByteArrayInputStream("你好[ni'hao] 您好\n".toByteArray()))
            (st.page as CommonWordsFragment).importWords(file)
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until("imported") { shown().any { it.startsWith("你好 → 您好") } }
            idle()
            assertTrue(shown().toString(), !undoOffered())
            assertEquals(
                listOf("米字旁[mi'zi'pang] 米 糸", "木字旁[mu'zi'pang] 木", "你好[ni'hao] 您好"),
                savedAs { CommonWordsManager.load().words.map { it.toString() } },
            )
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }
}
