/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Intent
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu

/** UAT cases of the toolbar, the text editing panel, the clipboard and the status area (uat/05). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ToolbarUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
    }

    private fun s(id: Int) = h.service.getString(id)

    private fun has(description: Int) = KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription == s(description) }

    private fun expand() {
        if (has(R.string.hide_toolbar)) return
        h.tap(h.described(s(R.string.expand_toolbar)))
        h.after(500)
        h.relayout()
    }

    private fun button(text: String): View {
        val t = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is TextView && it.text.toString() == text }
            ?: error("no $text in ${h.texts(h.inputView)}")
        return generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    private fun editing() {
        expand()
        h.tap(h.described(s(R.string.text_editing)))
    }

    private fun typed(text: String, cursor: Int = text.length) {
        h.field.setText(text)
        h.field.setSelection(cursor)
        h.idle()
    }

    private fun menu(entry: String, item: Int) {
        h.entry(entry)!!.performLongClick()
        h.idle()
        h.menuPick(s(item))
    }

    @Test
    @Uat("TB-02")
    fun expandingTheToolbar() {
        h.start()
        expand()
        val order = listOf(R.string.undo, R.string.redo, R.string.text_editing, R.string.clipboard, R.string.status_area).map(::s)
        val shown = KeyboardHarness.all(h.inputView).filter { h.shown(it) && it.contentDescription?.toString() in order }
            .sortedBy { h.box(it).left }.map { it.contentDescription.toString() }.toList()
        assertEquals(order, shown)
    }

    @Test
    @Uat("TB-03")
    fun undoAndRedo() {
        h.start()
        h.field.text.clear()
        h.service.currentInputConnection.commitText("你好", 1)
        h.idle()
        expand()
        h.tap(h.described(s(R.string.undo)))
        assertEquals("", h.field.text.toString())
        h.tap(h.described(s(R.string.redo)))
        assertEquals("你好", h.field.text.toString())
    }

    @Test
    @Uat("TB-06")
    fun editingPanelMovesAndSelects() {
        h.start()
        val text = "今天天气很好，我们去公园散步吧。"
        typed(text)
        editing()
        h.tap(h.described(s(R.string.move_cursor_to_start)))
        assertEquals(0, h.field.selectionStart)
        h.tap(button(s(R.string.select)))
        repeat(3) { h.tap(h.described(s(R.string.move_cursor_right))) }
        assertEquals("今天天", h.field.text.substring(h.field.selectionStart, h.field.selectionEnd))
        h.tap(button(h.service.getString(android.R.string.copy)))
        h.tap(h.described(s(R.string.move_cursor_to_end)))
        assertEquals(h.field.selectionStart, h.field.selectionEnd)
        assertEquals(text.length, h.field.selectionStart)
        h.tap(button(h.service.getString(android.R.string.paste)))
        assertEquals(text + "今天天", h.field.text.toString())
    }

    @Test
    @Uat("TB-07")
    fun editingPanelSelectAllCutBack() {
        h.start()
        typed("你好")
        editing()
        h.tap(button(h.service.getString(android.R.string.selectAll)))
        assertEquals(0 to 2, h.field.selectionStart to h.field.selectionEnd)
        h.tap(button(h.service.getString(android.R.string.cut)))
        assertEquals("", h.field.text.toString())
        h.tap(h.described(s(R.string.back_to_keyboard)))
        assertTrue(h.texts().toString(), "q" in h.texts())
    }

    @Test
    @Uat("TB-09")
    fun justCopiedOnTheBar() {
        h.start()
        h.copied("散步")
        h.start()
        h.tap(button("散步"))
        assertEquals("散步", h.field.text.toString())
    }

    @Test
    @Uat("TB-40")
    fun justCopiedGoneAfterThirtySeconds() {
        h.start()
        h.copied("散步")
        h.start()
        assertTrue(h.texts().toString(), "散步" in h.texts())
        h.after(31_000)
        h.relayout()
        assertFalse(h.texts().toString(), "散步" in h.texts())
    }

    @Test
    @Uat("TB-10")
    fun aPinnedEntryOutlivesTheLimit() {
        prefs.set(AppPrefs.getInstance().clipboard.clipboardHistoryLimit, 3)
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.pin)
        h.tap(h.described(s(R.string.back_to_keyboard)))
        for (w in listOf("今天", "天气", "我们", "一起")) h.copied(w)
        h.clipboard("一起")
        // the oldest unpinned trimmed to the limit, the pinned kept though older still
        h.waitFor("the limit kept") { h.entry("今天") == null }
        assertNotNull(h.entry("公园"))
        assertEquals(listOf("天气", "我们", "一起"), listOf("今天", "天气", "我们", "一起").filter { h.entry(it) != null })
    }

    @Test
    @Uat("TB-11")
    fun editingAndDeletingAnEntry() {
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.edit)
        val started = shadowOf(h.service).nextStartedActivity ?: shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        val edit = org.robolectric.Robolectric.buildActivity(org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity::class.java, started).setup().get()
        h.idle()
        val box = edit.findViewById<android.widget.EditText>(R.id.clipboard_edit_text)
        h.waitFor("the entry read: ${started.extras?.keySet()}, ${box.text}") { box.text.toString() == "公园" }
        box.setText("公园门口")
        edit.findViewById<View>(R.id.clipboard_edit_ok).performClick()
        h.waitFor("saved") { h.entry("公园门口") != null }
        menu("公园门口", R.string.delete)
        h.after(500)
        h.relayout()
        assertNull(h.entry("公园门口"))
    }

    private fun deleteAll(confirm: Boolean = true): String {
        val before = ShadowPopupMenu.getLatestPopupMenu()
        h.tap(h.described(s(R.string.delete_all)))
        // asked once the pinned are counted, off the main thread
        h.waitFor("the question") { ShadowPopupMenu.getLatestPopupMenu() !== before }
        val question = ShadowPopupMenu.getLatestPopupMenu().menu.getItem(0).title.toString()
        if (confirm) h.menuPick(h.service.getString(android.R.string.ok))
        h.after(500)
        h.relayout()
        return question
    }

    @Test
    @Uat("TB-33")
    fun clearingAskedUndoneThenThePinned() {
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.pin)
        h.tap(h.described(s(R.string.back_to_keyboard)))
        h.copied("散步")
        h.clipboard("散步")
        assertEquals(s(R.string.delete_all_except_pinned), deleteAll())
        h.waitFor("the notice") { h.notice() != null }
        assertTrue(h.noticeTexts().toString(), h.service.resources.getString(R.string.num_items_deleted, 1) in h.noticeTexts())
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>().last { it.text.toString() == s(R.string.undo) }
        assertTrue(undo.performClick())
        h.waitFor("散步 back") { h.entry("散步") != null }
        deleteAll()
        h.waitFor("only 公园") { h.entry("散步") == null && h.entry("公园") != null }
        assertEquals(s(R.string.delete_all_pinned_items), deleteAll())
        h.waitFor("empty") { h.entry("公园") == null }
    }

    private fun opened(entry: String?, description: Int? = null): Intent {
        expand()
        h.tap(h.described(s(R.string.status_area)))
        if (entry != null) h.tap(button(entry)) else h.tap(h.described(s(description!!)))
        return shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity ?: error("nothing opened")
    }

    // the page an intent opens: the activity made from it, its page's route
    private fun page(intent: Intent): String = SettingsHarness(intent = intent).use { st ->
        val nav = st.activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
        nav.navController.currentDestination?.route.orEmpty()
    }

    @Test
    @Uat("TB-18")
    fun theStatusAreasWaysIntoTheSettings() {
        h.start()
        val theme = opened(s(R.string.theme))
        page(theme).let { assertTrue(it, it.contains(SettingsRoute.Theme::class.qualifiedName!!.substringAfterLast('.'))) }
        h.start()
        val im = opened(s(R.string.input_method_options))
        page(im).let { assertTrue(it, it.contains("InputMethodConfig")) }
        h.start()
        val keys = opened(s(R.string.virtual_keyboard))
        page(keys).let { assertTrue(it, it.contains("Keys")) }
        h.start()
        val gear = opened(null, R.string.open_input_method_settings)
        assertEquals(MainActivity::class.java.name, gear.component?.className)
        assertNull(gear.getParcelableExtra<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE))
    }

    private fun keyboardBox() = h.box(h.board)

    @Test
    @Uat("TB-20")
    fun oneHandedToASideAcrossAndBack() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Off)
        val width = h.service.resources.displayMetrics.widthPixels
        h.start()
        expand()
        h.tap(h.described(s(R.string.status_area)))
        h.tap(button(s(R.string.one_handed)))
        h.start()
        val right = keyboardBox()
        assertTrue("$right", right.width() in (width * 0.7).toInt()..(width * 0.9).toInt() && right.right >= width - 2)
        assertTrue(h.box(h.bar).left >= right.left - 4)
        h.tap(h.described(s(R.string.split_dock_move_left)))
        val left = keyboardBox()
        assertTrue("$left", left.left <= 2)
        assertTrue(has(R.string.split_dock_move_right))
        h.tap(KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription?.toString() == s(R.string.one_handed_exit) })
        assertTrue("${keyboardBox()}", keyboardBox().width() >= width - 2)
        expand()
        h.tap(h.described(s(R.string.status_area)))
        h.tap(button(s(R.string.one_handed)))
        h.start()
        assertTrue("${keyboardBox()}", keyboardBox().left <= 2 && keyboardBox().width() < width * 0.9)
        // typing as ever
        h.tap(h.key("n"))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey(key=n") })
    }

    @Test
    @Uat("TB-21")
    fun oneHandedPanelsAsNarrow() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        h.start()
        val kb = keyboardBox()
        h.tap(h.key("符"))
        assertEquals(kb, keyboardBox())
        h.tap(h.key(s(R.string.picker_back)))
        h.tap(h.described(s(R.string.a11y_key_emoji)))
        assertEquals(kb, keyboardBox())
        h.tap(h.key("ABC"))
        h.copied("公园")
        h.clipboard("公园")
        assertEquals(kb, keyboardBox())
    }

    @Test
    @Uat("TB-25")
    fun aSumsResultOnTheBar() {
        harness = KeyboardHarness(im = KeyboardHarness.ENGLISH)
        h.start()
        typed("12*3.5")
        // the engine commits the = typed (English passes keys on as they are)
        h.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("=", -1)))
        val result = button("= 42")
        h.tap(result)
        assertEquals("12*3.5=42", h.field.text.toString())
    }

    @Test
    @Uat("TB-28")
    fun copyingNothingLeavesTheCursor() {
        h.start()
        typed("今天天气很好")
        editing()
        h.tap(h.described(s(R.string.move_cursor_to_start)))
        h.tap(button(s(R.string.select)))
        h.tap(button(h.service.getString(android.R.string.copy)))
        assertEquals(0 to 0, h.field.selectionStart to h.field.selectionEnd)
        h.tap(h.described(s(R.string.move_cursor_right)))
        assertEquals(1 to 1, h.field.selectionStart to h.field.selectionEnd)
    }

    @Test
    @Uat("TB-30")
    fun copyAndCutSaidNothingCopiedNotSaid() {
        h.start()
        typed("今天天气很好")
        editing()
        h.tap(button(h.service.getString(android.R.string.selectAll)))
        h.tap(button(h.service.getString(android.R.string.copy)))
        assertTrue(h.noticeTexts().toString(), s(R.string.text_copied) in h.noticeTexts())
        h.tap(button(h.service.getString(android.R.string.selectAll)))
        h.tap(button(h.service.getString(android.R.string.cut)))
        assertTrue(h.noticeTexts().toString(), s(R.string.text_cut) in h.noticeTexts())
        h.after(3000)
        assertNull(h.notice())
        h.tap(button(h.service.getString(android.R.string.copy)))
        assertNull(h.notice())
    }

    @Test
    @Uat("TB-31")
    fun pasteStaysInTheClipboardWhenSoSet() {
        prefs.set(AppPrefs.getInstance().clipboard.clipboardReturnAfterPaste, false)
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        h.tap(h.entry("公园")!!)
        assertEquals("公园", h.field.text.toString())
        h.tap(h.entry("公园")!!)
        assertEquals("公园公园", h.field.text.toString())
    }
}
