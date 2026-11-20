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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.QuickPhraseFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.quickPhraseImportToast
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
import kotlinx.coroutines.runBlocking

/** Quick input's settings page over a fake engine and the real files. */
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
        // its key in another case is the same entry, refused, the editor left open
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val again = dialog()
        fill("ADDR", addr.content, again)
        ok(again)
        assertTrue(again.isShowing)
        assertEquals(s(R.string.quick_phrase_duplicate), fields(again)[1].error)
        again.cancel()
        st.idle()
        row(addr.content).performClick()
        st.idle()
        assertEquals(listOf("addr", addr.content), fields().take(2).map { it.text.toString() })
        fill(null, "上海市")
        ok()
        saved("changed", listOf(QuickPhraseEntry("addr", "上海市"), gh))
        val search = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
        search.setText("GITHUB")
        st.idle()
        until("searched") { gh.content in shown() && "上海市" !in shown() }
        search.setText("")
        st.idle()
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
        saved("undone, back where it was", listOf(QuickPhraseEntry("addr", "上海市"), gh))
        until("the keyboard told") { "reloadConfig()" in st.api.calls }
    }

    private fun chip(label: String): Chip {
        until("$label chip shown") { KeyboardHarness.all(st.activity.window.decorView).any { it is Chip && it.isShown && it.text.toString() == label } }
        return KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<Chip>().first { it.isShown && it.text.toString() == label }
    }

    private fun pickItem(index: Int) {
        val list = dialog().listView
        list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
        st.idle()
    }

    // a key is ASCII, told as it is typed; a folder's chip shows its entries alone; renamed and deleted, no entry is lost
    @Test
    @Uat("QU-15", "QU-25", "QU-26")
    fun aKeyIsAsciiANoteAndAFolderAreSavedAndFolderChipsSortThePage() {
        open(gh)
        chip(s(R.string.quick_phrase_folder_new)).performClick()
        st.idle()
        fields().single().setText("网址")
        ok()
        until("the folder made") { QuickPhraseManager.loadAll().folders == listOf("网址") }
        until("its chip picked") { chip("网址").isChecked }
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val d = dialog()
        fill("#wz百度", "www.baidu.com", d)
        assertEquals(s(R.string.quick_phrase_key_invalid, QuickPhraseBook.MAX_KEY), fields(d)[0].error)
        ok(d)
        assertTrue(d.isShowing)
        fill("#wz", null, d)
        assertEquals(null, fields(d)[0].error)
        fields(d)[2].setText("百度")
        ok(d)
        val baidu = QuickPhraseEntry("#wz", "www.baidu.com", "百度", "网址")
        saved("with its note in the folder", listOf(baidu, gh))
        until("the folder's alone") { baidu.content in shown() && gh.content !in shown() }
        chip(s(R.string.quick_phrase_unfiled)).performClick()
        st.idle()
        until("those in none") { gh.content in shown() && baidu.content !in shown() }
        chip(s(R.string.quick_phrase_all)).performClick()
        st.idle()
        row(baidu.content).performClick()
        st.idle()
        val change = dialog()
        assertEquals("百度", fields(change)[2].text.toString())
        KeyboardHarness.all(change.window!!.decorView).filterIsInstance<MaterialButton>().first { it.text.toString() == s(R.string.quick_phrase_folder, "网址") }.performClick()
        st.idle()
        pickItem(0)
        ok(change)
        saved("out of the folder", listOf(baidu.copy(folder = ""), gh))
        QuickPhraseManager.put(baidu.copy(folder = ""), baidu, now = 9)
        chip(s(R.string.quick_phrase_unfiled)).performClick()
        st.idle()
        chip("网址").performLongClick()
        st.idle()
        pickItem(0)
        fields().single().setText("站点")
        ok()
        until("renamed") { QuickPhraseManager.loadAll().folders == listOf("站点") && QuickPhraseManager.load().any { it.folder == "站点" } }
        chip("站点").performLongClick()
        st.idle()
        pickItem(1)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        until("deleted, its entry kept") { QuickPhraseManager.loadAll().let { it.folders.isEmpty() && it.entries.toSet() == setOf(baidu.copy(folder = ""), gh) } }
    }

    @Test
    @Uat("QU-26")
    fun aFolderRenamedWhileShownStaysShown() {
        QuickPhraseManager.addFolder("网址")
        open(gh)
        chip("网址").performClick()
        st.idle()
        chip("网址").performLongClick()
        st.idle()
        pickItem(0)
        fields().single().setText("站点")
        ok()
        until("renamed and still shown") { QuickPhraseManager.loadAll().folders == listOf("站点") && chip("站点").isChecked }
    }

    // a folder renamed to another's name or a shelf's is told and kept; an entry deleted in its folder and undone goes
    // back into it; the keyboard's 编辑 of an entry the page moved edits the one there
    @Test
    @Uat("QU-26")
    fun aFolderNameTakenIsToldAndADeletionUndoneAndAKeyboardsEditKeepTheFolder() {
        QuickPhraseManager.addFolder("网址")
        QuickPhraseManager.addFolder("空的")
        val filed = gh.copy(folder = "网址")
        open(addr, filed)
        chip("网址").performLongClick()
        st.idle()
        pickItem(0)
        for ((name, error) in listOf(
            "空的" to s(R.string.quick_phrase_folder_exists),
            s(R.string.quick_phrase_all) to s(R.string.quick_phrase_folder_reserved, s(R.string.quick_phrase_all)),
            "未分类" to s(R.string.quick_phrase_folder_reserved, "未分类"),
        )) {
            val d = dialog()
            fields(d).single().setText(name)
            ok(d)
            assertTrue(name, d.isShowing)
            assertEquals(name, error, fields(d).single().error)
        }
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        st.idle()
        assertEquals(listOf("网址", "空的"), QuickPhraseManager.loadAll().folders)
        chip("网址").performClick()
        st.idle()
        row(gh.content).performClick()
        st.idle()
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        st.idle()
        saved("deleted", listOf(addr))
        until("the undo offered") { s(R.string.undo) in shown() }
        KeyboardHarness.all(st.activity.window.decorView).first { it.isShown && it is TextView && it.text.toString() == s(R.string.undo) }.performClick()
        st.idle()
        saved("undone into its folder", listOf(filed, addr))
        st.open(SettingsRoute.QuickPhrase(edit = true, key = gh.key, content = gh.content, existing = true))
        until("the editor up") {
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }?.let { fields(it).size == 3 } == true
        }
        val d = dialog()
        assertEquals(s(R.string.quick_phrase_folder, "网址"), KeyboardHarness.all(d.window!!.decorView).filterIsInstance<MaterialButton>().first { it.text.toString().startsWith(s(R.string.quick_phrase_folder, "")) }.text.toString())
        fields(d)[1].setText("https://github.com/sraw")
        ok(d)
        until("changed in place") { QuickPhraseManager.load().toSet() == setOf(filed.copy(content = "https://github.com/sraw"), addr) }
    }

    // a file of before read converted; the one whose key was all Chinese listed, kept, and given a key on the page
    @Test
    @Uat("QU-27")
    fun aFileOfBeforeIsConvertedAndItsKeylessOnesAreListedToBeGivenAKey() {
        val dir = File(HostConfigDir.inUse(ConfigHome.DATA).dir, QuickPhraseBook.DIR).apply { mkdirs() }
        File(dir, QuickPhraseBook.MINE).writeText("#wz百度 www.baidu.com\n#wz新浪 www.sina.com.cn\n地址 北京市海淀区\n")
        st.open(SettingsRoute.QuickPhrase())
        until("the page read") { st.page.view != null && "www.baidu.com" in shown() }
        assertTrue(s(R.string.quick_phrase_keyless_banner, 1) in shown())
        assertTrue("#wz  百度" in shown())
        chip(s(R.string.quick_phrase_keyless_shelf, 1)).performClick()
        st.idle()
        until("the keyless alone") { "北京市海淀区" in shown() && "www.baidu.com" !in shown() }
        assertTrue(s(R.string.quick_phrase_no_key) + "  地址" in shown())
        row("北京市海淀区").performClick()
        st.idle()
        assertEquals(listOf("", "北京市海淀区", "地址"), fields().map { it.text.toString() })
        fill("dz", null)
        ok()
        until("given a key, nothing left to") {
            QuickPhraseManager.load().any { it.key == "dz" && it.note == "地址" } && s(R.string.quick_phrase_keyless_banner, 1) !in shown()
        }
        assertTrue(File(dir, QuickPhraseBook.MINE).readText().startsWith(QuickPhraseFiles.HEADER + "\n"))
        assertTrue(chip(s(R.string.quick_phrase_all)).isChecked)
    }

    // the switches were once bound again from the page's first read: one turned off sprang back on
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
        // turned twice before either is saved, read back as last turned
        val emoji = switch(R.string.quick_phrase_built_in_emoji)
        val told = st.api.calls.count { it == "reloadConfig()" }
        emoji.performClick()
        emoji.performClick()
        // each tells the keyboard once it is written, not just the main looper idle
        until("both turns saved") { st.api.calls.count { it == "reloadConfig()" } >= told + 2 }
        assertTrue(QuickPhraseManager.builtInOn("emoji.mb"))
        assertTrue(switch(R.string.quick_phrase_built_in_emoji).isChecked)
    }

    // no field cuts what is filled in; too long is told at OK, nothing saved
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
        val tooLongKey = "k".repeat(QuickPhraseBook.MAX_KEY + 1)
        fill(tooLongKey, "x".repeat(QuickPhraseBook.MAX_CONTENT), d)
        assertEquals(tooLongKey, fields(d)[0].text.toString())
        ok(d)
        assertEquals(s(R.string.quick_phrase_key_invalid, QuickPhraseBook.MAX_KEY), fields(d)[0].error)
        val longest = "k".repeat(QuickPhraseBook.MAX_KEY)
        fill(longest, null, d)
        ok(d)
        saved("the longest", listOf(QuickPhraseEntry(longest, "x".repeat(QuickPhraseBook.MAX_CONTENT)), gh))
    }

    // an entry the keyboard saved meanwhile, unknown to the page, is refused by the file's check
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

    // the editor shown again by the activity made anew; Back with something changed asks first
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
        assertEquals(listOf("", "从剪贴板来的"), fields(first).take(2).map { it.text.toString() })
        fill("clip", "从剪贴板来的，改过", first)
        st.recreate()
        val again = dialog()
        assertNotSame(first, again)
        assertFalse(first.isShowing)
        assertEquals(listOf("clip", "从剪贴板来的，改过"), fields(again).take(2).map { it.text.toString() })
        // once more: the route's ask is not answered again over what was restored
        st.recreate()
        val third = dialog()
        assertEquals(listOf("clip", "从剪贴板来的，改过"), fields(third).take(2).map { it.text.toString() })
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

    // the activity made anew while the file is written: the save, finished, is not offered again as an editor
    @Test
    fun anEntryBeingSavedOverARotationIsNotEditedAgain() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        fill(addr.key, addr.content)
        holdingTheFiles {
            ok()
            assertTrue(dialog().isShowing)
            st.recreate()
        }
        saved("saved meanwhile", listOf(addr, gh))
        until("told") { ShadowToast.getTextOfLatestToast() == s(R.string.quick_phrase_saved) }
        until("listed") { addr.content in shown() }
        assertEquals(0, ShadowDialog.getShownDialogs().count { it.isShowing })
    }

    // kept over a rotation one past the limit (a Bundle holds no more), still told too long at OK
    @Test
    fun aHugePasteIsKeptOverARotationOnlyOnePastTheLimit() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        fill("k".repeat(QuickPhraseBook.MAX_CONTENT * 2), "长".repeat(QuickPhraseBook.MAX_CONTENT * 4))
        st.recreate()
        val d = dialog()
        assertEquals(listOf(QuickPhraseBook.MAX_CONTENT + 1, QuickPhraseBook.MAX_CONTENT + 1), fields(d).take(2).map { it.text.length })
        ok(d)
        assertEquals(s(R.string.quick_phrase_key_invalid, QuickPhraseBook.MAX_KEY), fields(d)[0].error)
        fill("long", null, d)
        ok(d)
        assertEquals(s(R.string.quick_phrase_content_too_long, QuickPhraseBook.MAX_CONTENT), fields(d)[1].error)
        assertEquals(listOf(gh), QuickPhraseManager.load())
    }

    // the question goes with the page, the editor shown again with what was typed
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
        assertEquals(listOf("gh", "https://gitlab.com"), fields(again).take(2).map { it.text.toString() })
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        fill(null, gh.content, again)
        again.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        st.idle()
        assertFalse(again.isShowing)
        assertEquals(0, ShadowDialog.getShownDialogs().count { it.isShowing })
    }

    // an import's toast counts every line it skipped, a key that cannot be one too
    @Test
    @Uat("QU-15")
    fun theImportToastCountsEveryLineSkipped() {
        val app = RuntimeEnvironment.getApplication()
        fun said(id: Int, vararg args: Any): String = app.getString(id, *args)
        // a picture picked for the file: its lines are no lines, so nothing at all is counted
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52) +
            ByteArray(64) { (0x80 + it).toByte() } + byteArrayOf(0x20, 0xc3.toByte(), 0x0a, 0x49, 0x45, 0x4e, 0x44)
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val picture = try {
            QuickPhraseManager.import(png, dir = dir)
        } finally {
            dir.deleteRecursively()
        }
        val rows = listOf(
            Triple("nothing read: not a .mb file", QuickPhraseManager.Imported(0, 0, 0), said(R.string.quick_phrase_import_none)),
            Triple("added and duplicates only", QuickPhraseManager.Imported(2, 1, 0), said(R.string.quick_phrase_import_done, 2, 1)),
            Triple(
                "too long ones said with the limit", QuickPhraseManager.Imported(2, 1, 3),
                said(R.string.quick_phrase_import_done_too_long, 2, 1, 3, QuickPhraseBook.MAX_CONTENT),
            ),
            Triple(
                "bad keys said after the rest", QuickPhraseManager.Imported(2, 0, 0, badKey = 4),
                said(R.string.quick_phrase_import_bad_key, said(R.string.quick_phrase_import_done, 2, 0), 4, QuickPhraseBook.MAX_KEY, QuickPhraseBook.MAX_NOTE),
            ),
            Triple(
                "only bad keys: said, not 'not a .mb file'", QuickPhraseManager.Imported(0, 0, 0, badKey = 1),
                said(R.string.quick_phrase_import_bad_key, said(R.string.quick_phrase_import_done, 0, 0), 1, QuickPhraseBook.MAX_KEY, QuickPhraseBook.MAX_NOTE),
            ),
            Triple("a picture picked for the file: said to be no 快速输入 file", picture, said(R.string.quick_phrase_import_none)),
            Triple(
                "keyless ones added said last", QuickPhraseManager.Imported(3, 0, 0, keyless = 2),
                said(R.string.quick_phrase_import_keyless, said(R.string.quick_phrase_import_done, 3, 0), 2),
            ),
            Triple(
                "past the list's limit: the rest said, not 'not a .mb file' when none went in", QuickPhraseManager.Imported(0, 0, 0, overLimit = 5),
                said(R.string.import_over_limit, said(R.string.quick_phrase_import_done, 0, 0), 5, QuickPhraseManager.MAX_ENTRIES),
            ),
        )
        assertRows(rows, { it.first }) { (_, r, expected) -> assertEquals(expected, quickPhraseImportToast(app, r)) }
    }

    /**
     * [block] run with the files' lock held by another thread. Waits only because QuickPhraseManager's methods are
     * @Synchronized on the object itself: moved to a private lock, the tests using this no longer test their race.
     */
    private fun holdingTheFiles(atRelease: () -> Unit = {}, block: () -> Unit): Thread {
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        val holder = thread(isDaemon = true) {
            synchronized(QuickPhraseManager) {
                held.countDown()
                release.await()
                atRelease()
            }
        }
        held.await()
        try {
            block()
        } finally {
            release.countDown()
        }
        return holder
    }

    private fun ask(content: String) = st.newIntent(
        Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.QuickPhrase(edit = true, content = content)),
    )

    private fun shownDialogs() = ShadowDialog.getShownDialogs().filter { it.isShowing }

    // the keyboard asking for the page again while its editor has something typed: asked now, a request too long ignored
    @Test
    @Uat("QU-19", "QU-21")
    fun theKeyboardAskingAgainOverAChangedEditorAsksFirst() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        val typing = dialog()
        fill("k", "typed here", typing)
        ask("from the clipboard")
        val first = dialog()
        assertNotSame(typing, first)
        assertEquals(s(R.string.quick_phrase_discard_confirm), first.findViewById<TextView>(android.R.id.message)!!.text.toString())
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        st.idle()
        assertTrue(typing.isShowing)
        assertEquals(listOf("k", "typed here"), fields(typing).take(2).map { it.text.toString() })
        ask("from the clipboard")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertFalse(typing.isShowing)
        val asked = dialog()
        assertEquals(listOf("", "from the clipboard"), fields(asked).take(2).map { it.text.toString() })
        ask("again")
        until("the next request's editor") { fields().take(2).map { it.text.toString() } == listOf("", "again") }
        assertEquals(1, shownDialogs().size)
        ask("长".repeat(QuickPhraseBook.MAX_CONTENT + 1))
        assertEquals(listOf("", "again"), fields().take(2).map { it.text.toString() })
        assertEquals(1, shownDialogs().size)
    }

    // asking while the editor restored after a rotation is not shown yet: held till the editor is up, then asked over it
    @Test
    @Uat("QU-21")
    fun theKeyboardAskingBeforeARestoredEditorIsShownAsksOverItOnceItIs() {
        open(gh)
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        st.idle()
        fill("k", "typed here")
        holdingTheFiles {
            st.recreate()
            ask("from the clipboard")
            st.recreate()
        }
        fun asks(d: android.app.Dialog) = d.findViewById<TextView>(android.R.id.message)?.text?.toString() == s(R.string.quick_phrase_discard_confirm)
        until("asked over the editor") { shownDialogs().any(::asks) }
        val question = shownDialogs().single(::asks) as AlertDialog
        val restored = shownDialogs().single { it !== question && KeyboardHarness.all(it.window!!.decorView).any { v -> v is EditText } } as AlertDialog
        assertEquals(listOf("k", "typed here"), fields(restored).take(2).map { it.text.toString() })
        question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertEquals(listOf("", "from the clipboard"), fields().take(2).map { it.text.toString() })
    }

    // a switch turned twice while the files are busy: the first turn's read back, arriving after both were written,
    // once bound the switch to the first turn
    @Test
    @Uat("QU-15")
    fun aSwitchTurnedTwiceWhileTheFilesAreBusyNeverShowsTheFirstTurn() {
        open(gh)
        val label = s(R.string.quick_phrase_built_in_emoji)
        fun emoji() = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<MaterialSwitch>().first { it.isShown && it.text.toString() == label }
        assertTrue(switch(R.string.quick_phrase_built_in_emoji).isChecked)
        holdingTheFiles {
            emoji().performClick()
            emoji().performClick()
        }
        // the page's changes run one at a time in order, so one run after them returns once both have posted their read
        // back to the main thread
        runBlocking(QuickPhraseFragment.changes) {}
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue("both turns written", QuickPhraseManager.builtInOn("emoji.mb"))
        assertFalse("their read backs posted", looper.isIdle)
        val list = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first()
        val shown = mutableListOf<Boolean>()
        // a task at a time, the list laid out after each (as a frame would be between any two)
        repeat(200) {
            if (!looper.isIdle) looper.runOneTask()
            list.measure(
                View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(list.height, View.MeasureSpec.EXACTLY),
            )
            list.layout(list.left, list.top, list.right, list.bottom)
            shown += emoji().isChecked
        }
        st.idle()
        shown += emoji().isChecked
        assertTrue("$shown", shown.all { it })
        assertTrue(QuickPhraseManager.builtInOn("emoji.mb"))
    }

    // the page's save refused as a duplicate of the keyboard's same entry: once taken as saved and what was typed lost;
    // now the editor given again
    @Test
    fun aChangeRefusedAsADuplicateOverARotationIsEditedAgain() {
        open(gh, addr)
        row(addr.content).performClick()
        st.idle()
        val typed = QuickPhraseEntry("new", "typed")
        fill(typed.key, typed.content)
        val keyboard = holdingTheFiles(atRelease = { QuickPhraseManager.put(null, typed, now = 9) }) {
            ok()
            st.recreate()
        }
        keyboard.join(10_000)
        val again = dialog()
        assertEquals(listOf(typed.key, typed.content), fields(again).take(2).map { it.text.toString() })
        assertTrue(ShadowToast.getTextOfLatestToast() != s(R.string.quick_phrase_saved))
        assertTrue(addr in QuickPhraseManager.load())
    }
}
