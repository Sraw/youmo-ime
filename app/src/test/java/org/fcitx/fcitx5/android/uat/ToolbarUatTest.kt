/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.ui.common.DynamicListAdapter
import org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.ToolbarButtonsFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu

/** UAT cases of the toolbar, the text editing panel, the clipboard and the status area (uat/05). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi", shadows = [HideRecordingShadow::class])
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

    // a 切词 piece's cell, which takes its taps
    private fun piece(text: String): View =
        generateSequence(h.entry(text)!!) { it.parent as? View }.first { it.isClickable }

    // the place in the order shown on [text]'s piece, null for one not picked
    private fun place(text: String): String? = KeyboardHarness.all(piece(text)).filterIsInstance<TextView>()
        .firstOrNull { it.visibility == View.VISIBLE && it.text.toString() != text }?.text?.toString()

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
    @Uat("TB-44")
    fun aPieceOfAnEntryCommittedAlone() {
        h.start()
        h.copied("发到 li@x.com 看看")
        h.clipboard("发到 li@x.com 看看")
        menu("发到 li@x.com 看看", R.string.clipboard_split)
        h.waitFor("the pieces shown") { h.entry("x.com") != null }
        assertNotNull(h.entry("@"))
        assertNotNull(h.entry("li"))
        // ← back to the list it came from, not the keyboard
        h.tap(h.described(s(R.string.back_to_clipboard)))
        h.waitFor("the list back") { h.entry("发到 li@x.com 看看") != null && h.entry("x.com") == null }
        menu("发到 li@x.com 看看", R.string.clipboard_split)
        h.waitFor("the pieces shown again") { h.entry("x.com") != null }
        // a tap picks, commits nothing; 上屏 off till then
        assertFalse(h.entry(s(R.string.clipboard_split_commit))!!.isEnabled)
        h.tap(piece("x.com"))
        assertEquals("1", place("x.com"))
        assertEquals("", h.field.text.toString())
        h.tap(h.entry(s(R.string.clipboard_split_commit))!!)
        assertEquals("x.com", h.field.text.toString())
        // back to the keyboard, as after a paste
        h.waitFor("the keyboard back") { h.entry("li") == null }
    }

    @Test
    @Uat("TB-46")
    fun piecesPickedByTapsCommittedInTapOrder() {
        h.start()
        h.copied("发到 li@x.com 看看")
        h.clipboard("发到 li@x.com 看看")
        menu("发到 li@x.com 看看", R.string.clipboard_split)
        h.waitFor("the pieces shown") { h.entry("x.com") != null }
        h.tap(piece("x.com"))
        h.tap(piece("li"))
        h.tap(piece("@"))
        // each picked with its place in the order
        assertEquals(listOf("1", "2", "3"), listOf("x.com", "li", "@").map(::place))
        // tapped again, dropped; tapped once more, last
        h.tap(piece("li"))
        assertEquals(listOf("1", null, "2"), listOf("x.com", "li", "@").map(::place))
        h.tap(piece("li"))
        assertEquals(listOf("1", "3", "2"), listOf("x.com", "li", "@").map(::place))
        h.tap(h.entry(s(R.string.clipboard_split_commit))!!)
        // x.com then @ are not neighbours and @ is no letter: nothing between; @ then li neither, nothing again
        assertEquals("x.com@li", h.field.text.toString())
        // again: x.com then li, not neighbours but both Latin, a space; li then @, neighbours, the text between (none)
        h.field.setText("")
        menu("发到 li@x.com 看看", R.string.clipboard_split)
        h.waitFor("the pieces shown again") { h.entry("x.com") != null }
        // 重选 drops what was picked
        h.tap(piece("li"))
        h.tap(h.entry(s(R.string.clipboard_split_reset))!!)
        assertEquals(listOf(null, null, null), listOf("x.com", "li", "@").map(::place))
        assertFalse(h.entry(s(R.string.clipboard_split_commit))!!.isEnabled)
        h.tap(piece("x.com"))
        h.tap(piece("li"))
        h.tap(piece("@"))
        h.tap(h.entry(s(R.string.clipboard_split_commit))!!)
        assertEquals("x.com li@", h.field.text.toString())
        // back to the keyboard, as after a paste
        h.waitFor("the keyboard back") { h.entry("li") == null && h.entry(s(R.string.clipboard_split_commit)) == null }
    }

    @Test
    @Uat("TB-11")
    fun editingAndDeletingAnEntry() {
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.edit)
        val started = shadowOf(h.service).nextStartedActivity ?: shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        val edit = Robolectric.buildActivity(ClipboardEditActivity::class.java, started).setup().get()
        h.idle()
        val box = edit.findViewById<EditText>(R.id.clipboard_edit_text)
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
        val nav = st.activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
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
        h.emit(HostEvent.Commit("=", -1))
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

    // the service's requests to put the keyboard away, recorded by HideRecordingShadow from here on: the
    // list goes on filling as the test runs, not a snapshot
    private fun startRecordingHides(): List<Int> = HideRecordingShadow.hides.also { it.clear() }

    // the system's answer to the request: the window hidden, then the field tapped again
    private fun hiddenThenTappedAgain() {
        h.service.hideWindow()
        h.idle()
        h.start()
    }

    @Test
    @Uat("TB-04")
    fun theHideButtonPutsTheKeyboardAway() {
        h.start()
        val asked = startRecordingHides()
        h.tap(h.described(s(R.string.hide_keyboard)))
        assertEquals(asked.toString(), 1, asked.size)
        hiddenThenTappedAgain()
        assertTrue(h.texts().toString(), h.shown(h.key("q")) && has(R.string.hide_keyboard))
    }

    private fun candidates(vararg words: String) {
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
    }

    @Test
    @Uat("TB-29")
    fun holdingTheCandidatesExpandButtonHides() {
        h.start()
        val asked = startRecordingHides()
        candidates(*Array(60) { "字$it" })
        h.longPress(h.described(s(R.string.expand_candidates_list)))
        assertEquals(asked.toString(), 1, asked.size)
        // the table not opened
        assertFalse(h.texts(h.inputView).toString(), "字20" in h.texts(h.inputView) || has(R.string.hide_candidates_list))
        hiddenThenTappedAgain()
        // 你 picked: 联想 after it, the same ⌄ on the bar. A tap opens their table
        candidates()
        h.service.currentInputConnection.commitText("你", 1)
        candidates(*Array(60) { "联$it" })
        h.tap(h.described(s(R.string.expand_candidates_list)))
        assertTrue(h.texts(h.inputView).toString(), "联20" in h.texts(h.inputView))
        h.tap(h.described(s(R.string.hide_candidates_list)))
        h.after(1000)
        assertFalse(h.texts(h.inputView).toString(), "联20" in h.texts(h.inputView))
        // held: away, no table
        h.longPress(h.described(s(R.string.expand_candidates_list)))
        assertEquals(asked.toString(), 2, asked.size)
        assertFalse(has(R.string.hide_candidates_list))
    }

    @Test
    @Uat("PR-05")
    fun aPasswordsBarIsTheDigitsRow() {
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // androidengine switches a password to English (PR-03)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        fun row() = KeyboardHarness.all(h.bar).filterIsInstance<TextKeyView>()
            .filter { h.shown(it) }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), row().map { it.mainText.text.toString() }.toList())
        assertFalse(has(R.string.expand_toolbar))
        h.api.calls.clear()
        for (d in listOf("1", "2", "3")) h.tap(row().first { it.mainText.text.toString() == d })
        // each digit to the engine as its key, as English types a digit (EngineReplay: PR-03's 123)
        val sent = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
            .map { Regex("sym=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals(h.api.calls.toString(), listOf(49, 50, 51), sent)
    }

    // the bar's buttons, in order, by their names: the toolbar's (opened) and the right's
    private fun barButtons(): List<String> {
        val names = ToolbarButton.entries.map { s(ToolbarButtonLooks.of(it).label) }.toSet() +
            listOf(R.string.status_simplified, R.string.status_traditional, R.string.learning_habits_on).map(::s)
        return KeyboardHarness.all(h.bar).filter { h.shown(it) && it.contentDescription?.toString() in names }
            .sortedBy { h.box(it).left }.map { it.contentDescription.toString() }.toList()
    }

    private fun lit(description: Int) = (h.described(s(description)) as ToolButton).iconTint == h.inputView.theme.accentKeyBackgroundColor

    private val toolbarButtons get() = AppPrefs.getInstance().internal.toolbarButtons

    // every button's situation: the voice build's microphone, one-handed and the keyboards' choice there
    private val everywhere = ToolbarConfig.Situation(voice = true, tradSimp = false, oneHanded = true, keyboards = true)

    // where [button] is on the toolbar buttons page
    private fun DynamicListAdapter<ToolbarConfig.Item>.indexOf(button: ToolbarButton) =
        entries.indexOfFirst { it is ToolbarConfig.Item.Button && it.button == button }

    private fun page(st: SettingsHarness): DynamicListAdapter<ToolbarConfig.Item> {
        st.open(SettingsRoute.ToolbarButtons)
        @Suppress("UNCHECKED_CAST")
        return KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first().adapter as DynamicListAdapter<ToolbarConfig.Item>
    }

    @Test
    @Uat("TB-42")
    fun theToolbarButtonsPageSetsTheBar() {
        prefs.set(toolbarButtons, "")
        SettingsHarness().use { st ->
            val adapter = page(st)
            val list = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().first()
            fun row(name: String): View = KeyboardHarness.all(list).first { it is TextView && it.text.toString() == name }.parent as View
            fun check(name: String) {
                KeyboardHarness.all(row(name)).filterIsInstance<CheckBox>().first { it.isShown }.performClick()
                st.idle()
            }
            // every button and the divider; the ⋯ fixed, not listed
            assertTrue(st.texts().containsAll(listOf(st.s(R.string.undo), st.s(R.string.theme), st.s(R.string.toolbar_divider))))
            assertFalse(st.texts().contains(st.s(R.string.status_area)))
            check(st.s(R.string.undo))
            check(st.s(R.string.theme))
            // the theme dragged below the divider: always there
            val divider = adapter.entries.indexOf(ToolbarConfig.Item.Divider)
            val theme = adapter.indexOf(ToolbarButton.Theme)
            for (i in theme until divider) adapter.swapItem(i, i + 1)
            st.idle()
        }
        val config = ToolbarConfig.parse(toolbarButtons.getValue())
        assertEquals(
            ToolbarConfig.Layout(
                listOf(ToolbarButton.Redo, ToolbarButton.CursorMove, ToolbarButton.Clipboard, ToolbarButton.QuickPhrase, ToolbarButton.More),
                listOf(ToolbarButton.Theme, ToolbarButton.Emoji, ToolbarButton.Voice, ToolbarButton.Hide)
            ),
            config.layout(everywhere)
        )
        h.start()
        // the theme there before the toolbar is opened, the undo not even then; the voice build's microphone as ever
        val pinned = listOfNotNull(R.string.theme, R.string.a11y_key_emoji, R.string.voice_input.takeIf { VoiceFeature.AVAILABLE }, R.string.hide_keyboard)
        assertEquals(pinned.map(::s), barButtons())
        expand()
        assertEquals((listOf(R.string.redo, R.string.text_editing, R.string.clipboard, R.string.quick_phrases, R.string.status_area) + pinned).map(::s), barButtons())
    }

    @Test
    @Uat("TB-42")
    fun theToolbarButtonsPageResetsAndKeepsTheVoice() {
        // a voice build's microphone moved first: a text build's edit leaves it there
        prefs.set(toolbarButtons, "undo,|,voice,emoji,hide")
        SettingsHarness().use { st ->
            val adapter = page(st)
            assertEquals(VoiceFeature.AVAILABLE, adapter.entries.any { it is ToolbarConfig.Item.Button && it.button == ToolbarButton.Voice })
            val emoji = adapter.indexOf(ToolbarButton.Emoji)
            adapter.swapItem(emoji, emoji + 1)
            st.idle()
            val pinned = ToolbarConfig.parse(toolbarButtons.getValue()).layout(everywhere).pinned
            assertEquals(listOf(ToolbarButton.Voice, ToolbarButton.Hide, ToolbarButton.Emoji), pinned)
            // the others above the divider: the microphone still below it
            (st.page as ToolbarButtonsFragment).reset()
            st.idle()
            for (b in listOf(ToolbarButton.Emoji, ToolbarButton.Hide)) {
                var at = adapter.indexOf(b)
                while (at > adapter.entries.indexOf(ToolbarConfig.Item.Divider)) adapter.swapItem(at, --at)
                st.idle()
            }
            val moved = ToolbarConfig.parse(toolbarButtons.getValue()).layout(everywhere)
            assertEquals(listOf(ToolbarButton.Voice), moved.pinned)
            (st.page as ToolbarButtonsFragment).reset()
            st.idle()
            assertEquals("", toolbarButtons.getValue())
            assertEquals(ToolbarConfig.DEFAULT.items.filter { VoiceFeature.AVAILABLE || (it as? ToolbarConfig.Item.Button)?.button != ToolbarButton.Voice }, adapter.entries)
        }
        if (VoiceFeature.AVAILABLE) return
        // a text build's microphone after the emoji: still after it once the emoji is moved and then unchecked
        prefs.set(toolbarButtons, "undo,|,emoji,voice,hide")
        SettingsHarness().use { st ->
            val adapter = page(st)
            val hide = adapter.indexOf(ToolbarButton.Hide)
            adapter.swapItem(hide, adapter.indexOf(ToolbarButton.Emoji))
            st.idle()
            val emoji = adapter.indexOf(ToolbarButton.Emoji)
            adapter.updateItem(emoji, ToolbarConfig.Item.Button(ToolbarButton.Emoji, shown = false))
            st.idle()
            val items = ToolbarConfig.parse(toolbarButtons.getValue()).items
            val at = items.indexOfFirst { it is ToolbarConfig.Item.Button && it.button == ToolbarButton.Emoji }
            assertEquals(items.toString(), ToolbarButton.Voice, (items.getOrNull(at + 1) as? ToolbarConfig.Item.Button)?.button)
        }
    }

    @Test
    @Uat("TB-43")
    fun thePanelsTogglesOnTheBar() {
        prefs.set(toolbarButtons, "habits,one_handed,trad_simp,|,emoji,hide")
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Off)
        prefs.set(AppPrefs.getInstance().internal.useHabits, true)
        h.start()
        expand()
        // no 简繁 till the input method offers it; the ⋯ there, fixed
        assertEquals(listOf(R.string.learning_habits_off, R.string.one_handed, R.string.status_area, R.string.a11y_key_emoji, R.string.hide_keyboard).map(::s), barButtons())
        val chttrans = listOf(StatusEntry(7, "chttrans", "fcitx-chttrans-inactive", ""))
        h.api.statusAreaCached = chttrans
        h.emit(HostEvent.StatusArea(chttrans, KeyboardHarness.PINYIN))
        h.relayout()
        assertTrue(barButtons().toString(), s(R.string.status_simplified) in barButtons())
        assertFalse(lit(R.string.status_simplified))
        h.api.calls.clear()
        h.tap(h.described(s(R.string.status_simplified)))
        h.idle()
        assertTrue(h.api.calls.toString(), "activateAction(7)" in h.api.calls)
        val traditional = listOf(StatusEntry(7, "chttrans", "fcitx-chttrans-active", ""))
        h.emit(HostEvent.StatusArea(traditional, KeyboardHarness.PINYIN))
        assertTrue(lit(R.string.status_traditional))
        // the panel's one-handed toggle, the same, lit while on; off from elsewhere, unlit
        assertFalse(lit(R.string.one_handed))
        h.tap(h.described(s(R.string.one_handed)))
        assertTrue(ThemeManager.prefs.oneHanded.getValue() != OneHanded.Off)
        assertTrue(lit(R.string.one_handed))
        ThemeManager.prefs.oneHanded.setValue(OneHanded.Off)
        h.idle()
        assertFalse(lit(R.string.one_handed))
        // habits off: lit, and named for turning them back on
        h.tap(h.described(s(R.string.learning_habits_off)))
        assertFalse(AppPrefs.getInstance().internal.useHabits.getValue())
        assertTrue(lit(R.string.learning_habits_on))
    }

    @Test
    @Uat("TB-43")
    fun thePanelsOthersOnTheBar() {
        prefs.set(toolbarButtons, "theme,input_method_settings,clear_recent,|,floating,one_handed,hide")
        prefs.set(ThemeManager.prefs.floatingKeyboard, false)
        h.start()
        expand()
        h.tap(h.described(s(R.string.theme)))
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        h.tap(h.described(s(R.string.input_method_options)))
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        val before = ShadowPopupMenu.getLatestPopupMenu()
        h.tap(h.described(s(R.string.learning_clear_recent)))
        assertTrue(ShadowPopupMenu.getLatestPopupMenu() !== before)
        assertEquals(s(R.string.learning_span_ten_minutes), ShadowPopupMenu.getLatestPopupMenu().menu.getItem(0).title.toString())
        assertFalse(lit(R.string.floating_keyboard))
        val spans = ShadowPopupMenu.getLatestPopupMenu()
        assertTrue(shadowOf(spans).isShowing)
        // the input view made anew for it: the spans gone with the old one
        h.tap(h.described(s(R.string.floating_keyboard)))
        assertTrue(ThemeManager.prefs.floatingKeyboard.getValue())
        h.after(500)
        assertFalse(shadowOf(spans).isShowing)
    }

    @Test
    @Uat("TB-43")
    fun floatingTheBarFitsAndHasNoOneHanded() {
        // more at the right than a floating bar holds: the rest on the toolbar, the menu key and ⋯ reachable
        prefs.set(toolbarButtons, "undo,|,emoji,voice,hide,theme,floating,one_handed,habits,clear_recent,input_method_settings,redo")
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        h.start()
        h.relayout()
        val menu = h.box(h.described(s(R.string.expand_toolbar)))
        val bar = h.box(h.bar)
        val right = barButtons()
        assertTrue(right.toString(), right.size in 1..6)
        for (name in right) {
            val b = h.box(h.described(name))
            assertTrue("$name $b over the menu key $menu", b.left >= menu.right && b.right <= bar.right)
        }
        assertFalse(s(R.string.one_handed) in right)
        // lit wherever it went, the right or the toolbar
        expand()
        assertTrue(lit(R.string.floating_keyboard))
        val all = barButtons()
        assertTrue(all.toString(), s(R.string.status_area) in all && s(R.string.input_method_options) in all)
        assertFalse(all.toString(), s(R.string.one_handed) in all)
    }
}
