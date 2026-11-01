/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu

/** uat/05's 切词 of entries the panel lists but does not split whole, and pieces picked by a sweep: ToolbarUatTest's harness, a class of its own for size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi", shadows = [HideRecordingShadow::class])
class ClipboardSplitUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
    }

    private fun s(id: Int) = h.service.getString(id)

    // an entry's long press, which its row takes, not the text in it
    private fun held(text: View) {
        generateSequence(text) { it.parent as? View }.first { it.isLongClickable }.performLongClick()
        h.idle()
    }

    @Test
    @Uat("TB-48")
    fun aLongEntrySplitsItsStartAndADottedOneNotAtAll() {
        h.start()
        // past ClipboardChunksWindow.MAX_CHARS: its start split, the last cell saying how much
        val long = "看看 abc ".repeat(800)
        h.copied(long)
        h.clipboard()
        fun startingWith(prefix: String) = KeyboardHarness.all(h.inputView)
            .firstOrNull { h.shown(it) && it is TextView && it.text.startsWith(prefix) }
        h.waitFor("the long entry listed") { startingWith("看看 abc") != null }
        held(startingWith("看看 abc")!!)
        h.menuPick(s(R.string.clipboard_split))
        h.waitFor("the pieces shown") { h.entry("abc") != null }
        val cut = h.service.getString(R.string.clipboard_split_cut, 5000)
        val list = generateSequence(h.entry("abc")!!) { it.parent as? View }.filterIsInstance<RecyclerView>().first()
        list.scrollToPosition(list.adapter!!.itemCount - 1)
        h.waitFor("the note at the end") { cut in h.texts(h.inputView) }
        h.tap(h.described(s(R.string.back_to_clipboard)))
        // the panel's list loaded before the next clip: one stored while its first page loads may
        // miss the page's invalidation and never be listed (CI, once in two runs)
        h.waitFor("the clipboard again") { startingWith("看看 abc") != null }
        // a password shown as dots: its pieces would show it
        ClipboardManager.lastClipTimestamp = -1L
        ClipboardManager.lastEntry = null
        val clip = ClipData.newPlainText("", "mima1234").apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        h.service.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        h.waitFor("stored") { ClipboardManager.lastEntry?.text == "mima1234" }
        h.waitFor("dots listed") { startingWith("•") != null }
        held(startingWith("•")!!)
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }
        assertTrue(titles.toString(), s(R.string.delete) in titles)
        assertFalse(titles.toString(), s(R.string.clipboard_split) in titles)
    }

    // a 切词 piece's cell, which takes its taps
    private fun piece(text: String): View =
        generateSequence(h.entry(text)!!) { it.parent as? View }.first { it.isClickable }

    // the place in the order shown on [text]'s piece, null for one not picked
    private fun place(text: String): String? = KeyboardHarness.all(piece(text)).filterIsInstance<TextView>()
        .firstOrNull { it.visibility == View.VISIBLE && it.text.toString() != text }?.text?.toString()

    @Test
    @Uat("TB-49")
    fun piecesPickedBySweepingAcross() {
        h.start()
        h.copied("发到 li@x.com 看看")
        h.clipboard("发到 li@x.com 看看")
        held(h.entry("发到 li@x.com 看看")!!)
        h.menuPick(s(R.string.clipboard_split))
        h.waitFor("the pieces shown") { h.entry("x.com") != null }
        fun over(text: String) = h.box(piece(text)).let { it.exactCenterX() to it.exactCenterY() }
        fun sweep(from: String, to: String) = over(to).let { (x, y) -> h.drag(piece(from), x, y) }
        // a finger down, still: a tap, as before
        sweep("li", "li")
        assertEquals(listOf("1", null, null), listOf("li", "@", "x.com").map(::place))
        // up and down: the grid's, no pick
        over("@").let { (x, y) -> h.drag(piece("@"), x, y + h.box(piece("@")).height() * 2) }
        assertEquals(listOf("1", null, null), listOf("li", "@", "x.com").map(::place))
        // from a picked piece: the range dropped
        sweep("li", "@")
        assertEquals(listOf(null, null, null), listOf("li", "@", "x.com").map(::place))
        // right to left: the range picked in the text's order
        sweep("x.com", "li")
        assertEquals(listOf("1", "2", "3"), listOf("li", "@", "x.com").map(::place))
        h.tap(h.entry(s(R.string.clipboard_split_commit))!!)
        assertEquals("li@x.com", h.field.text.toString())
    }
}
