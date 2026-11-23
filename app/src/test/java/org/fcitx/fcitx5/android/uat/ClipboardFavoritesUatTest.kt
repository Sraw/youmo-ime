/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.PersistableBundle
import android.view.View
import android.widget.TextView
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.DefaultTaskExecutor
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.paging.PagingSource
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu
import java.util.concurrent.ConcurrentLinkedQueue

/** The clipboard panel's 最近 and 收藏: a favourite a copy, each page its own. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ClipboardFavoritesUatTest {

    private val h = KeyboardHarness()

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() = h.close()

    private fun s(id: Int) = h.service.getString(id)

    private fun menuItems(entry: View): List<String> {
        entry.performLongClick()
        h.idle()
        val menu = ShadowPopupMenu.getLatestPopupMenu()
        return (0 until menu.menu.size()).map { menu.menu.getItem(it).title.toString() }.also { menu.dismiss() }
    }

    private fun menu(entry: String, item: Int) {
        h.entry(entry)!!.performLongClick()
        h.idle()
        h.menuPick(s(item))
    }

    private fun tab(page: Int) = h.entry(s(page)) ?: error("no tab ${s(page)} in ${h.texts(h.inputView)}")

    private fun open(page: Int) {
        h.tap(tab(page))
        h.relayout()
    }

    // the database's answer comes in real time: the fake clock held, or the notice could lapse unseen
    private fun said(text: Int) = until("said ${s(text)}", { h.noticeTexts() }) {
        h.idle()
        h.notice()?.let { n -> KeyboardHarness.all(n.contentView).any { (it as? TextView)?.text?.toString() == s(text) } } == true
    }

    private fun undo() {
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>().last { it.text.toString() == s(R.string.undo) }
        assertTrue(undo.performClick())
    }

    private fun back() = h.tap(h.described(s(R.string.back_to_keyboard)))

    private fun texts(favorites: Boolean) = runBlocking {
        ClipboardManager.coroutineContext.job.children.forEach { it.join() }
        val source = if (favorites) ClipboardManager.favoriteEntries() else ClipboardManager.recentEntries()
        (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.text }
    }

    // as KeyboardHarness.copied, the clip marked as a password manager marks it
    private fun copiedSensitive(text: String) {
        ClipboardManager.lastClipTimestamp = -1L
        ClipboardManager.lastEntry = null
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        h.service.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        h.waitFor("$text stored") { ClipboardManager.lastEntry?.text == text }
        h.idle()
    }

    private fun listUnder(entry: View) = generateSequence(entry) { it.parent as? View }.filterIsInstance<RecyclerView>().first()

    private fun swipes(list: RecyclerView) = (0 until list.itemDecorationCount).any { list.getItemDecorationAt(it) is ItemTouchHelper }

    @Test
    @Uat("TB-70")
    fun aFavouriteIsMadeFrom最近AndPastedFrom收藏() {
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.clipboard_favorite)
        said(R.string.clipboard_favorited)
        assertTrue("still in 最近", h.entry("公园") != null)
        open(R.string.clipboard_favorites)
        h.waitFor("公园 in 收藏") { h.entry("公园") != null }
        h.capture("ClipboardFavoritesUatTest/favorites")
        h.tap(h.entry("公园")!!)
        assertEquals("公园", h.field.text.toString())
    }

    @Test
    @Uat("TB-71")
    fun deletingAndClearing最近LeaveTheFavourites() {
        h.start()
        h.copied("公园")
        h.copied("散步")
        h.clipboard("公园")
        menu("公园", R.string.clipboard_favorite)
        menu("公园", R.string.delete)
        h.waitFor("公园 gone from 最近") { h.entry("公园") == null }
        h.tap(h.described(s(R.string.delete_all_recent_description)))
        h.menuPick(s(android.R.string.ok))
        h.waitFor("最近 empty") { h.entry("散步") == null }
        open(R.string.clipboard_favorites)
        h.waitFor("公园 in 收藏") { h.entry("公园") != null }
        back()
        h.copied("公园")
        h.waitFor("a copy of it in 最近") { texts(favorites = false) == listOf("公园") }
        assertEquals("one favourite still", listOf("公园"), texts(favorites = true))
    }

    @Test
    @Uat("TB-72")
    fun unfavouritingTakesTheCopyOrMovesTheOnlyOneTo最近AndIsUndone() {
        h.start()
        h.copied("公园")
        h.copied("北京")
        h.clipboard("北京")
        menu("公园", R.string.clipboard_favorite)
        // the notice's window is taken before the menus over it are: they are popups too
        said(R.string.clipboard_favorited)
        menu("北京", R.string.clipboard_favorite)
        // its recent copy gone: 北京 is a favourite alone
        menu("北京", R.string.delete)
        h.waitFor("北京 gone from 最近") { h.entry("北京") == null }
        open(R.string.clipboard_favorites)
        h.waitFor("both in 收藏") { h.entry("公园") != null && h.entry("北京") != null }
        val list = listUnder(h.entry("公园")!!)
        assertFalse("no swipe on 收藏", swipes(list))
        assertFalse("no trash on 收藏", KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription == s(R.string.delete_all_recent_description) })

        menu("公园", R.string.clipboard_unfavorite)
        said(R.string.clipboard_unfavorited)
        h.waitFor("公园 gone from 收藏") { h.entry("公园") == null }
        undo()
        h.waitFor("公园 back") { h.entry("公园") != null }
        assertEquals(listOf("北京", "公园"), texts(favorites = true))

        menu("北京", R.string.clipboard_unfavorite)
        said(R.string.clipboard_unfavorited_moved)
        h.waitFor("北京 first in 最近") { texts(favorites = false).firstOrNull() == "北京" }
        undo()
        h.waitFor("北京 back in place") { texts(favorites = true) == listOf("北京", "公园") }
        assertEquals(listOf("公园"), texts(favorites = false))
    }

    @Test
    @Uat("TB-73")
    fun movedFirstAlreadyThereAndASensitiveOneSaid() {
        h.start()
        h.copied("甲")
        h.copied("乙")
        h.clipboard("乙")
        menu("甲", R.string.clipboard_favorite)
        said(R.string.clipboard_favorited)
        menu("乙", R.string.clipboard_favorite)
        menu("甲", R.string.clipboard_favorite)
        said(R.string.clipboard_already_favorite)
        back()
        copiedSensitive("mima1234")
        h.clipboard("乙")
        val dots = h.described(s(R.string.a11y_sensitive_hidden))
        dots.performLongClick()
        h.idle()
        h.menuPick(s(R.string.clipboard_favorite))
        said(R.string.clipboard_favorited_sensitive)
        open(R.string.clipboard_favorites)
        h.waitFor("the favourites listed") { h.entry("甲") != null }
        val first = menuItems(h.described(s(R.string.a11y_sensitive_hidden)))
        val notFirst = menuItems(h.entry("甲")!!)
        menu("甲", R.string.clipboard_move_first)
        assertRows(
            "the first has nowhere to move" to { assertFalse(first.toString(), s(R.string.clipboard_move_first) in first) },
            "a dotted one: no pieces, no quick input" to {
                assertEquals(listOf(R.string.clipboard_unfavorite, R.string.edit, R.string.share).map(::s), first)
            },
            "one further down moves first, and no delete on 收藏" to {
                assertEquals(
                    listOf(
                        R.string.clipboard_unfavorite, R.string.clipboard_move_first, R.string.clipboard_split,
                        R.string.edit, R.string.clipboard_save_quick_phrase, R.string.share,
                    ).map(::s),
                    notFirst,
                )
            },
            "moved" to { h.waitFor("甲 first") { texts(favorites = true) == listOf("甲", "mima1234", "乙") } },
        )
    }

    @Test
    @Uat("TB-74")
    fun thePanelOpensWhereItWasLeftUnlessSomethingWasCopied() {
        prefs.set(AppPrefs.getInstance().clipboard.clipboardReturnAfterPaste, false)
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.clipboard_favorite)
        open(R.string.clipboard_favorites)
        back()
        h.clipboard()
        val leftOnFavorites = tab(R.string.clipboard_favorites).isSelected
        back()
        h.copied("散步")
        h.clipboard()
        val copied = tab(R.string.clipboard_recent).isSelected
        // Room's answer comes in its own time: slower at 29
        h.waitFor("散步 in 最近") { h.entry("散步") != null }
        h.tap(h.entry("散步")!!)
        back()
        h.clipboard()
        val pasted = tab(R.string.clipboard_recent).isSelected
        open(R.string.clipboard_favorites)
        h.waitFor("公园 in 收藏") { h.entry("公园") != null }
        menu("公园", R.string.clipboard_split)
        h.tap(h.described(s(R.string.back_to_clipboard)))
        val backFromSplit = tab(R.string.clipboard_favorites).isSelected
        assertRows(
            "left on 收藏, nothing copied since: 收藏" to { assertTrue(leftOnFavorites) },
            "a copy since: 最近, the copy under the finger" to { assertTrue(copied) },
            "a paste is no copy: the page it was left on" to { assertTrue(pasted) },
            "back from 切词: the page it was opened from" to { assertTrue(backFromSplit) },
        )
    }

    @Test
    @Uat("TB-75")
    fun withTheHistoryOffTheFavouritesStillPaste() {
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.clipboard_favorite)
        back()
        prefs.set(AppPrefs.getInstance().clipboard.clipboardListening, false)
        h.clipboard()
        open(R.string.clipboard_recent)
        val prompt = h.texts(h.inputView).any { it == s(R.string.instruction_enable_clipboard_listening) }
        open(R.string.clipboard_favorites)
        h.waitFor("公园 in 收藏") { h.entry("公园") != null }
        h.tap(h.entry("公园")!!)
        assertTrue(prompt)
        assertEquals("公园", h.field.text.toString())
    }

    @Test
    @Uat("TB-75")
    fun withTheHistoryOffUnfavouritingTheOnlyCopyDeletesItSaidAndUndone() {
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.clipboard_favorite)
        said(R.string.clipboard_favorited)
        menu("公园", R.string.delete)
        h.waitFor("公园 gone from 最近") { h.entry("公园") == null }
        back()
        prefs.set(AppPrefs.getInstance().clipboard.clipboardListening, false)
        h.clipboard()
        open(R.string.clipboard_favorites)
        h.waitFor("公园 in 收藏") { h.entry("公园") != null }
        menu("公园", R.string.clipboard_unfavorite)
        said(R.string.clipboard_unfavorited_dropped)
        h.waitFor("公园 gone from 收藏") { h.entry("公园") == null }
        val hidden = texts(favorites = false)
        undo()
        h.waitFor("公园 back") { h.entry("公园") != null }
        assertRows(
            "not put in a 最近 the page cannot show" to { assertEquals(emptyList<String>(), hidden) },
            "the undo brings it back" to { assertEquals(listOf("公园") to emptyList<String>(), texts(favorites = true) to texts(favorites = false)) },
        )
    }

    private fun favouritesEmptyHint(): String {
        h.clipboard()
        open(R.string.clipboard_favorites)
        return h.texts(h.inputView).first { s(R.string.favorites_empty) in it }
    }

    @Test
    @Uat("TB-75")
    fun theEmptyFavouritesSayHowToGetOneWithTheHistoryOffOrALimitOf0() {
        val listening = AppPrefs.getInstance().clipboard.clipboardListening
        val limit = AppPrefs.getInstance().clipboard.clipboardHistoryLimit
        val defaultLimit = limit.getValue()
        h.start()
        // each opened anew with its prefs set before, as a user comes to it
        fun hintWith(on: Boolean, max: Int, shot: String? = null): String {
            prefs.set(listening, on)
            prefs.set(limit, max)
            return favouritesEmptyHint().also {
                shot?.let { h.capture(it) }
                back()
            }
        }
        assertRows(
            "the history off: nothing comes into 最近 to make one from" to {
                val historyOff = hintWith(on = false, max = defaultLimit, shot = "ClipboardFavoritesUatTest/favorites_empty")
                assertEquals("${s(R.string.favorites_empty)}\n${s(R.string.favorites_empty_history_off)}", historyOff)
            },
            "a limit of 0: 最近 keeps none to make one from" to {
                assertEquals("${s(R.string.favorites_empty)}\n${s(R.string.favorites_empty_limit_zero)}", hintWith(on = true, max = 0))
            },
        )
    }

    @Test
    @Uat("TB-75")
    fun theEmptyFavouritesHintFollowsALimitChangedWhileOpen() {
        h.start()
        val before = favouritesEmptyHint()
        prefs.set(AppPrefs.getInstance().clipboard.clipboardHistoryLimit, 0)
        h.idle()
        val after = h.texts(h.inputView).first { s(R.string.favorites_empty) in it }
        assertRows(
            "a limit above 0: how to make one" to { assertEquals(s(R.string.favorites_empty_limit_zero) in before, false) },
            "set to 0 with the panel open: said at once" to {
                assertEquals("${s(R.string.favorites_empty)}\n${s(R.string.favorites_empty_limit_zero)}", after)
            },
        )
    }

    @Test
    @Uat("AX-22")
    fun tabsCardsAndTheirActionsAreRead() {
        h.start()
        h.copied("公园")
        copiedSensitive("mima1234")
        h.clipboard("公园")
        val recentTab = AccessibilityNodeInfoCompat.wrap(tab(R.string.clipboard_recent).createAccessibilityNodeInfo())
        val otherTab = AccessibilityNodeInfoCompat.wrap(tab(R.string.clipboard_favorites).createAccessibilityNodeInfo())
        fun actions(v: View) = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo()).actionList.mapNotNull { it.label?.toString() }
        val dotted = h.described(s(R.string.a11y_sensitive_hidden))
        val onRecent = actions(h.entry("公园")!!)
        menu("公园", R.string.clipboard_favorite)
        h.described(s(R.string.a11y_sensitive_hidden)).performLongClick()
        h.idle()
        h.menuPick(s(R.string.clipboard_favorite))
        open(R.string.clipboard_favorites)
        // the dotted one made first after 公园 was listed: 公园's card bound again, now second
        h.waitFor("both in 收藏") {
            h.relayout()
            h.entry("公园") != null && KeyboardHarness.all(h.inputView).count { h.shown(it) && it.contentDescription == s(R.string.a11y_sensitive_hidden) } == 1
        }
        val onFavorite = actions(h.entry("公园")!!)
        assertRows(
            "the page shown: its tab, selected" to {
                assertEquals(s(R.string.a11y_tab) to true, recentTab.roleDescription.toString() to recentTab.isSelected)
            },
            "the other: a tab, not selected" to { assertEquals(s(R.string.a11y_tab) to false, otherTab.roleDescription.toString() to otherTab.isSelected) },
            "a dotted card: hidden, not a row of dots" to { assertEquals(s(R.string.a11y_sensitive_hidden), dotted.contentDescription.toString()) },
            "a card on 最近 offers 收藏" to { assertTrue(onRecent.toString(), s(R.string.clipboard_favorite) in onRecent) },
            "one further down 收藏 offers both" to {
                assertTrue(onFavorite.toString(), onFavorite.containsAll(listOf(R.string.clipboard_unfavorite, R.string.clipboard_move_first).map(::s)))
            },
        )
    }

    @Test
    @Uat("LS-54")
    fun oneHandedTheBarHoldsBothTabsAndTheTrash() {
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        menu("公园", R.string.clipboard_favorite)
        h.capture("ClipboardFavoritesUatTest/one_handed")
        val tabs = listOf(R.string.clipboard_recent, R.string.clipboard_favorites).map { tab(it) as TextView }
        assertTrue(tabs.all { it.layout.getEllipsisCount(0) == 0 })
        assertTrue(h.shown(h.described(s(R.string.delete_all_recent_description))))
        open(R.string.clipboard_favorites)
        h.waitFor("公园 in 收藏") { h.entry("公园") != null }
        h.tap(h.entry("公园")!!)
        assertEquals("公园", h.field.text.toString())
    }

    private val told get() = AppPrefs.getInstance().internal.clipboardPinsMovedTold

    // from the keyboard; the last notice put away first, so one up still from before is not taken for this open's
    private fun opensSayingPinsMoved(): Boolean {
        h.notice()?.dismiss()
        h.clipboard()
        h.idle()
        return s(R.string.clipboard_pins_moved) in h.noticeTexts()
    }

    private fun favouritedAsAnUpgradesPin(text: String) {
        h.copied(text)
        runBlocking {
            ClipboardManager.coroutineContext.job.children.forEach { it.join() }
            ClipboardManager.favorite(ClipboardManager.lastEntry!!.id)
        }
    }

    // a 0.6.2 install's pins are the favourites: said once
    @Test
    @Uat("TB-76")
    fun anUpgradesPinsAreSaidMovedOnce() {
        h.start()
        favouritedAsAnUpgradesPin("钉")
        h.clipboard("钉")
        said(R.string.clipboard_pins_moved)
        back()
        val again = opensSayingPinsMoved()
        assertRows(
            "marked told" to { assertTrue(told.getValue()) },
            "not said at the next open" to { assertFalse(again) },
        )
    }

    // a fresh install has none: told nothing, nor once it makes one
    @Test
    @Uat("TB-76")
    fun anInstallWithNoPinsIsNeverToldTheyMoved() {
        h.start()
        h.copied("公园")
        val first = opensSayingPinsMoved()
        h.waitFor("公园 listed") { h.entry("公园") != null }
        menu("公园", R.string.clipboard_favorite)
        said(R.string.clipboard_favorited)
        back()
        val afterOne = opensSayingPinsMoved()
        assertRows(
            "none at the first open" to { assertFalse(first) },
            "nor once a favourite is made" to { assertFalse(afterOne) },
        )
    }

    // a start counts the file after its open: a panel opened before would take an upgrade's pins for none
    @SuppressLint("RestrictedApi")
    @Test
    @Uat("TB-76")
    fun anUpgradesPinsAreSaidMovedOnceCountedNotBefore() {
        h.start()
        favouritedAsAnUpgradesPin("钉")
        runBlocking { ClipboardManager.closeDatabase() }
        // Room's answers held across a start, as its open's count is out
        val held = ConcurrentLinkedQueue<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(object : DefaultTaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) {
                held += runnable
            }
        })
        val early = try {
            ClipboardManager.init(RuntimeEnvironment.getApplication())
            opensSayingPinsMoved() to told.getValue()
        } finally {
            ArchTaskExecutor.getInstance().setDelegate(null)
            generateSequence { held.poll() }.forEach(ArchTaskExecutor.getIOThreadExecutor()::execute)
        }
        runBlocking { ClipboardManager.coroutineContext.job.children.forEach { it.join() } }
        back()
        val counted = opensSayingPinsMoved()
        assertRows(
            "opened before the count: nothing said, nothing marked" to { assertEquals(false to false, early) },
            "opened after: said" to { assertTrue(counted) },
            "and marked" to { assertTrue(told.getValue()) },
        )
    }
}
