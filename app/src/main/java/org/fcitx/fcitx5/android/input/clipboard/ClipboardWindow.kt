/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.Keep
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.mechdancer.dependency.manager.must

/** [backFrom]: the page 切词 was opened from, shown again on the way back. */
class ClipboardWindow(private val backFrom: ClipboardPage? = null) :
    InputWindow.ExtendedInputWindow<ClipboardWindow>(), InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val imeScope by manager.imeScope()

    private val prefs = AppPrefs.getInstance().clipboard
    private val internal = AppPrefs.getInstance().internal

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> showPage() }

    // the empty favourites' hint names a limit of 0
    private val clipboardLimitListener = ManagedPreference.OnChangeListener<Int> { _, _ -> showPage() }

    private var page = ClipboardPage.Recent

    private val empty = mutableMapOf(ClipboardPage.Recent to false, ClipboardPage.Favorites to false)

    private inner class PageList(val page: ClipboardPage) {
        val pager = Pager(PagingConfig(pageSize = 16)) {
            if (page == ClipboardPage.Recent) ClipboardManager.recentEntries() else ClipboardManager.favoriteEntries()
        }

        val adapter = adapter(page)

        val loadStateListener: (CombinedLoadStates) -> Unit = {
            // not while a load is under way: its append is not at the end only because no page is in yet, which taken for
            // a list turned the empty history's hint into the clear button over no rows till Room's query came back
            if (it.refresh !is LoadState.Loading) {
                empty[page] = it.append.endOfPaginationReached && adapter.itemCount < 1
                if (page == this@ClipboardWindow.page) showPage()
            }
        }

        var submitJob: Job? = null
    }

    private val lists by lazy { ClipboardPage.entries.associateWith { PageList(it) } }

    private fun adapter(page: ClipboardPage): ClipboardAdapter {
        val look = ScenePanels.clipboard(context, theme)
        return object : ClipboardAdapter(
            theme,
            look,
            ScenePanels.clipboardRadius(context, look),
            clipboardMaskSensitive,
            page,
        ) {
            override fun onFavorite(id: Int) {
                service.lifecycleScope.launch {
                    val said = when (ClipboardManager.favorite(id) ?: return@launch) {
                        ClipboardManager.Favorited.Added -> R.string.clipboard_favorited
                        ClipboardManager.Favorited.AddedSensitive -> R.string.clipboard_favorited_sensitive
                        ClipboardManager.Favorited.Already -> R.string.clipboard_already_favorite
                        ClipboardManager.Favorited.Gone -> R.string.clipboard_entry_gone
                    }
                    service.say(said)
                }
            }

            override fun onUnfavorite(id: Int) {
                service.lifecycleScope.launch {
                    val unfavored = ClipboardManager.unfavorite(id) ?: return@launch
                    val (_, undo) = unfavorites.deleted(listOf(unfavored))
                    val said = when {
                        unfavored.dropped -> R.string.clipboard_unfavorited_dropped
                        unfavored.timestamp != null -> R.string.clipboard_unfavorited_moved
                        else -> R.string.clipboard_unfavorited
                    }
                    service.say(context.getString(said), undo)
                }
            }

            override fun onMoveFirst(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.moveFavoriteFirst(id) }
            }

            override fun onEdit(id: Int) {
                AppUtil.launchClipboardEdit(context, id)
            }

            override fun onSaveQuickPhrase(entry: ClipboardEntry) {
                AppUtil.launchQuickPhraseEditor(context, content = entry.text)
            }

            override fun onSplit(entry: ClipboardEntry) {
                windowManager.attachWindow(ClipboardChunksWindow(entry.text, page))
            }

            override fun onShare(entry: ClipboardEntry) {
                val target = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, entry.text)
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    ClipboardManager.delete(id)
                    sayDeleted(id)
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                service.commitOverEngine(entry.text)
                // moved first when the panel closes: moved now, another entry would be under the finger
                pasted += entry.id
                if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            for ((list, view) in listOf(ClipboardPage.Recent to recentList, ClipboardPage.Favorites to favoritesList)) {
                view.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                view.adapter = lists.getValue(list).adapter
            }
            // a favourite goes by 取消收藏 only: a scroll gone sideways would lose it
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // NO_POSITION while a page update is pending: getItem(-1) would throw
                    val position = viewHolder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return
                    val entry = lists.getValue(ClipboardPage.Recent).adapter.getEntryAt(position) ?: return
                    service.lifecycleScope.launch {
                        ClipboardManager.delete(entry.id)
                        sayDeleted(entry.id)
                    }
                }
            }).attachToRecyclerView(recentList)
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
            deleteAllButton.setOnClickListener { promptDeleteAll() }
            recentTab.setOnClickListener { selectPage(ClipboardPage.Recent) }
            favoritesTab.setOnClickListener { selectPage(ClipboardPage.Favorites) }
        }
    }

    override fun onCreateView(): View = ui.root

    private var promptMenu: PopupMenu? = null

    private fun promptDeleteAll() {
        promptMenu?.dismiss()
        promptMenu = PopupMenu(context, ui.deleteAllButton).apply {
            menu.add(buildSpannedString {
                bold {
                    color(context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)) {
                        append(context.getString(R.string.delete_all_recent))
                    }
                }
            }).isEnabled = false
            menu.add(android.R.string.cancel)
            menu.item(android.R.string.ok) {
                service.lifecycleScope.launch {
                    val ids = ClipboardManager.deleteAllRecent()
                    sayDeleted(*ids)
                }
            }
            setOnDismissListener {
                if (it === promptMenu) promptMenu = null
            }
            show()
        }
    }

    // one per page: an undo on one never brings back what the other took
    private val deleted = DeletedEntries<Int>(
        undoDelete = { ids -> service.lifecycleScope.launch { ClipboardManager.undoDelete(*ids.toIntArray()) } },
        purge = { ids -> service.lifecycleScope.launch { ClipboardManager.purgeMarked(*ids.toIntArray()) } },
    )

    private val unfavorites = DeletedEntries<ClipboardManager.Unfavored>(
        undoDelete = { u -> service.lifecycleScope.launch { ClipboardManager.undoUnfavorite(*u.toTypedArray()) } },
        purge = { u -> service.lifecycleScope.launch { ClipboardManager.purgeUnfavored(*u.toTypedArray()) } },
    )

    private fun sayDeleted(vararg id: Int) {
        val (count, undo) = deleted.deleted(id.toList())
        service.say(context.resources.getString(R.string.num_items_deleted, count), undo)
    }

    private fun selectPage(to: ClipboardPage) {
        page = to
        internal.clipboardPage.setValue(to.name)
        ui.select(to)
        showPage()
    }

    private fun showPage() {
        val listening = clipboardEnabledPref.getValue()
        if (page == ClipboardPage.Favorites) {
            ui.favoritesEmptyUi.setHint(ClipboardPages.favoritesEmpty(listening, prefs.clipboardHistoryLimit.getValue()))
        }
        ui.show(page, ClipboardPages.shows(page, listening, empty.getValue(page)))
    }

    override fun onAttached() {
        empty[ClipboardPage.Recent] = ClipboardManager.recentCount == 0
        empty[ClipboardPage.Favorites] = ClipboardManager.favoriteCount == 0
        val remembered = ClipboardPage.entries.firstOrNull { it.name == internal.clipboardPage.getValue() } ?: ClipboardPage.Recent
        selectPage(ClipboardPages.opening(remembered, copiesAtLeave, ClipboardManager.copies, clipboardEnabledPref.getValue(), backFrom))
        for (list in lists.values) {
            list.adapter.addLoadStateListener(list.loadStateListener)
            list.submitJob = imeScope.launch {
                list.pager.flow.collect { list.adapter.submitData(it) }
            }
        }
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
        prefs.clipboardHistoryLimit.registerOnChangeListener(clipboardLimitListener)
        // said once: an upgrade's pins are the favourites now; a new install has none and is told nothing. Not before the
        // file's favourites are counted, or an upgrade's would be taken for none
        if (!internal.clipboardPinsMovedTold.getValue() && ClipboardManager.countedTheFile) {
            internal.clipboardPinsMovedTold.setValue(true)
            if (ClipboardManager.favoriteCount > 0) service.say(R.string.clipboard_pins_moved)
        }
    }

    private val pasted = mutableListOf<Int>()

    private fun movePasted() {
        if (pasted.isEmpty()) return
        val ids = pasted.toList()
        pasted.clear()
        service.lifecycleScope.launch { ClipboardManager.used(ids) }
    }

    // the input view replaced (a rotation, a theme) never detaches its windows, but finishes their input
    override fun onFinishInput() = movePasted()

    override fun onDetached() {
        movePasted()
        copiesAtLeave = ClipboardManager.copies
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        prefs.clipboardHistoryLimit.unregisterOnChangeListener(clipboardLimitListener)
        for (list in lists.values) {
            list.adapter.onDetached()
            list.adapter.removeLoadStateListener(list.loadStateListener)
            list.submitJob?.cancel()
        }
        promptMenu?.dismiss()
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    override val extensionInTitle = true

    override fun onCreateBarExtension(): View = ui.extension

    private companion object {
        // in memory: a restarted process has copied nothing since, as its count starts at 0 too
        var copiesAtLeave = 0L
    }
}
