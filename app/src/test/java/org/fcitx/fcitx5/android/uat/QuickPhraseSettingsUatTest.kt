/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until as waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Quick input's settings page (uat/11 QU-15, QU-19) over a fake engine and the real files: add,
 * change, delete, search, the built-in tables' switches, the editor kept over the activity made anew.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class QuickPhraseSettingsUatTest {

    private val gh = QuickPhraseEntry("gh", "https://github.com")
    private val addr = QuickPhraseEntry("addr", "北京市海淀区\n3 号楼")

    private var st = SettingsHarness()

    @Before
    fun phone() {
        phoneStorage()
        File(HostConfigDir.inUse(ConfigHome.DATA).dir, QuickPhraseBook.DIR).deleteRecursively()
    }

    @After
    fun close() = st.close()

    private fun s(id: Int, vararg args: Any) = st.s(id, *args)

    private fun shown(): List<String> = st.texts()

    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

    private fun open(vararg entries: QuickPhraseEntry) {
        entries.forEachIndexed { i, e -> QuickPhraseManager.put(null, e, now = i + 1L) }
        st.open(SettingsRoute.QuickPhrase())
        until("the page read") { st.page.view != null && KeyboardHarness.all(st.activity.window.decorView).any { it is FloatingActionButton } }
    }

    private fun dialog(): AlertDialog {
        until("a dialog showing") { ShadowDialog.getLatestDialog()?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun fields(d: AlertDialog = dialog()) = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()

    private fun fill(key: String?, content: String?, d: AlertDialog = dialog()) {
        val (k, c) = fields(d)
        key?.let(k::setText)
        content?.let(c::setText)
    }

    private fun ok(d: AlertDialog = dialog()) {
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
    }

    private fun saved(what: String, expected: List<QuickPhraseEntry>) =
        until("$what: $expected saved, ${QuickPhraseManager.load()} there") { QuickPhraseManager.load() == expected }

    private fun row(text: String): View {
        until("$text listed") { text in shown() }
        val t = KeyboardHarness.all(st.activity.window.decorView).first { it.isShown && it is TextView && it.text.toString() == text }
        return generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    private fun switch(label: Int): MaterialSwitch {
        val text = s(label)
        until("$text listed") { KeyboardHarness.all(st.activity.window.decorView).any { it is MaterialSwitch && it.isShown && it.text.toString() == text } }
        return KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<MaterialSwitch>().first { it.isShown && it.text.toString() == text }
    }

    @Test
    @Uat("QU-15")
    fun anEntryAddedChangedFoundAndDeleted() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        fill("addr", addr.content)
        ok()
        saved("added", listOf(addr, gh))
        until("listed") { addr.content in shown() }
        // its key in another case is the same entry (review L11), refused, the editor left open
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val again = dialog()
        fill("ADDR", addr.content, again)
        ok(again)
        assertTrue(again.isShowing)
        assertEquals(s(R.string.quick_phrase_duplicate), fields(again)[1].error)
        again.cancel()
        st.idle()
        // changed on its row
        row(addr.content).performClick()
        st.idle()
        assertEquals(listOf("addr", addr.content), fields().map { it.text.toString() })
        fill(null, "上海市")
        ok()
        saved("changed", listOf(QuickPhraseEntry("addr", "上海市"), gh))
        // the search finds content, case aside
        val search = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
        search.setText("GITHUB")
        st.idle()
        until("searched") { gh.content in shown() && "上海市" !in shown() }
        search.setText("")
        st.idle()
        // deleted from its editor, then undone from the snackbar
        row("上海市").performClick()
        st.idle()
        val change = dialog()
        assertEquals(s(R.string.delete), change.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        change.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        st.idle()
        saved("deleted", listOf(gh))
        until("gone") { "上海市" !in shown() }
        until("the undo offered") { s(R.string.undo) in shown() }
        val undo = KeyboardHarness.all(st.activity.window.decorView).first { it.isShown && it is TextView && it.text.toString() == s(R.string.undo) }
        undo.performClick()
        st.idle()
        saved("undone", listOf(gh, QuickPhraseEntry("addr", "上海市")))
        until("the keyboard told") { "reloadConfig()" in st.api.calls }
    }

    // review H1: the switches were bound again from the page's first read, so one turned off sprang
    // back on and could not be turned on again
    @Test
    @Uat("QU-15")
    fun aBuiltInTableTurnedOffStaysOffAndTurnsOnAgain() {
        open(gh)
        val latex = switch(R.string.quick_phrase_built_in_latex)
        assertTrue(latex.isChecked)
        latex.performClick()
        until("turned off") { !QuickPhraseManager.builtInOn("latex.mb") }
        st.idle()
        assertFalse(switch(R.string.quick_phrase_built_in_latex).isChecked)
        assertTrue(switch(R.string.quick_phrase_built_in_emoji).isChecked)
        switch(R.string.quick_phrase_built_in_latex).performClick()
        until("turned on") { QuickPhraseManager.builtInOn("latex.mb") }
        st.idle()
        assertTrue(switch(R.string.quick_phrase_built_in_latex).isChecked)
        // review L-1: turned twice before either is saved, read back as last turned
        val emoji = switch(R.string.quick_phrase_built_in_emoji)
        emoji.performClick()
        emoji.performClick()
        st.idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(QuickPhraseManager.builtInOn("emoji.mb"))
        assertTrue(switch(R.string.quick_phrase_built_in_emoji).isChecked)
    }

    // review M1: no field cuts what is filled in; too long is told at OK, nothing saved
    @Test
    @Uat("QU-19")
    fun aContentTooLongIsToldAtOkAndNeverCut() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val d = dialog()
        val long = "长".repeat(QuickPhraseBook.MAX_CONTENT + 1)
        fill("long", long, d)
        assertEquals(long.length, fields(d)[1].text.length)
        ok(d)
        assertTrue(d.isShowing)
        assertEquals(s(R.string.quick_phrase_content_too_long, QuickPhraseBook.MAX_CONTENT), fields(d)[1].error)
        // a key filled in is not cut either: 30 emoji are 60 UTF-16 units, a good key
        val emoji = "😀".repeat(QuickPhraseBook.MAX_KEY)
        fill(emoji, "x".repeat(QuickPhraseBook.MAX_CONTENT), d)
        assertEquals(emoji, fields(d)[0].text.toString())
        ok(d)
        saved("the longest", listOf(QuickPhraseEntry(emoji, "x".repeat(QuickPhraseBook.MAX_CONTENT)), gh))
    }

    // review L1: an entry the keyboard saved meanwhile, unknown to the page, is refused by the file's
    // check: told, the editor left open with what was typed
    @Test
    fun aDuplicateOnlyTheFileKnowsKeepsTheEditorOpen() {
        open(gh)
        QuickPhraseManager.put(null, addr, now = 9)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val d = dialog()
        fill(addr.key, addr.content, d)
        ok(d)
        until("refused") { fields(d)[1].error?.toString() == s(R.string.quick_phrase_duplicate) }
        assertTrue(d.isShowing)
        assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    // review M3: the editor, with what was typed, shown again by the activity made anew; Back with
    // something changed asks first
    @Test
    @Uat("QU-19")
    fun theEditorAndWhatWasTypedOutliveTheActivityMadeAnew() {
        st.close()
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.QuickPhrase(edit = true, content = "从剪贴板来的"))
        st = SettingsHarness(intent = intent)
        QuickPhraseManager.put(null, gh, now = 1)
        st.activity
        val first = dialog()
        assertEquals(listOf("", "从剪贴板来的"), fields(first).map { it.text.toString() })
        fill("clip", "从剪贴板来的，改过", first)
        st.recreate()
        val again = dialog()
        assertNotSame(first, again)
        assertFalse(first.isShowing)
        assertEquals(listOf("clip", "从剪贴板来的，改过"), fields(again).map { it.text.toString() })
        // once more: the route's ask is not answered again over what was restored
        st.recreate()
        val third = dialog()
        assertEquals(listOf("clip", "从剪贴板来的，改过"), fields(third).map { it.text.toString() })
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        third.onBackPressedDispatcher.onBackPressed()
        st.idle()
        val ask = dialog()
        assertNotSame(third, ask)
        assertTrue(third.isShowing)
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertFalse(third.isShowing)
        assertEquals(listOf(gh), QuickPhraseManager.load())
    }

    @Test
    fun backWithNothingChangedClosesTheEditorAtOnce() {
        open(gh)
        row(gh.content).performClick()
        st.idle()
        val d = dialog()
        d.onBackPressedDispatcher.onBackPressed()
        st.idle()
        assertFalse(d.isShowing)
        assertEquals(d, ShadowDialog.getLatestDialog())
    }

    // review M-1: OK tapped, the activity made anew while the file is written: the save, finished,
    // is not offered again as an editor whose OK would say 已经有了
    @Test
    fun anEntryBeingSavedOverARotationIsNotEditedAgain() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        fill(addr.key, addr.content)
        // the files' lock held: the save waits
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        thread {
            synchronized(QuickPhraseManager) {
                held.countDown()
                release.await()
            }
        }
        held.await()
        ok()
        assertTrue(dialog().isShowing)
        st.recreate()
        release.countDown()
        saved("saved meanwhile", listOf(addr, gh))
        until("told") { ShadowToast.getTextOfLatestToast() == s(R.string.quick_phrase_saved) }
        until("listed") { addr.content in shown() }
        assertEquals(0, ShadowDialog.getShownDialogs().count { it.isShowing })
    }

    // review M-2: pasted beyond any use, kept over a rotation one past the limit (a Bundle holds no
    // more), still told too long at OK
    @Test
    fun aHugePasteIsKeptOverARotationOnlyOnePastTheLimit() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        fill("k".repeat(QuickPhraseBook.MAX_CONTENT * 2), "长".repeat(QuickPhraseBook.MAX_CONTENT * 4))
        st.recreate()
        val d = dialog()
        assertEquals(listOf(QuickPhraseBook.MAX_CONTENT + 1, QuickPhraseBook.MAX_CONTENT + 1), fields(d).map { it.text.length })
        ok(d)
        assertEquals(s(R.string.quick_phrase_key_invalid), fields(d)[0].error)
        fill("long", null, d)
        ok(d)
        assertEquals(s(R.string.quick_phrase_content_too_long, QuickPhraseBook.MAX_CONTENT), fields(d)[1].error)
        assertEquals(listOf(gh), QuickPhraseManager.load())
    }

    // review L-2, L-3: 取消 with something changed asks too; the question goes with the page, the
    // editor shown again with what was typed
    @Test
    fun cancelWithSomethingChangedAsksAndTheQuestionGoesWithThePage() {
        open(gh)
        row(gh.content).performClick()
        st.idle()
        val d = dialog()
        fill(null, "https://gitlab.com", d)
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        st.idle()
        val ask = dialog()
        assertNotSame(d, ask)
        assertTrue(d.isShowing)
        st.recreate()
        assertFalse(ask.isShowing)
        val again = dialog()
        assertEquals(listOf("gh", "https://gitlab.com"), fields(again).map { it.text.toString() })
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        // 取消 with nothing changed closes at once
        fill(null, gh.content, again)
        again.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        st.idle()
        assertFalse(again.isShowing)
        assertEquals(0, ShadowDialog.getShownDialogs().count { it.isShowing })
    }
}
