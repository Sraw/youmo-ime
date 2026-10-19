/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.transition.Transition
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class PickerWindow(
    override val key: Key,
    // asked for when the view is made, as the picker first opens: the emoji are read from their data
    private val data: () -> List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerGridView.Density,
    private val switchKey: KeyDef,
    private val popupPreview: Boolean = true,
    private val followKeyBorder: Boolean = true,
    private val policy: PickerPolicy = DefaultPickerPolicy(),
    /** A key that finds emoji by the pinyin of their words ([EmojiSearchUi]). */
    private val search: Boolean = false,
) : InputWindow.ExtendedInputWindow<PickerWindow>(), EssentialWindow, InputBroadcastReceiver {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Emoticon
    }

    /** the symbols' way, after Sogou's: [SymbolPanel]; told by the density, not a flag that must agree with it */
    private val panel = density == PickerGridView.Density.Panel

    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

    private var locked by AppPrefs.getInstance().internal.symbolPanelLocked

    private val sidePanel by lazy { SymbolPanel(adapter.categories.size, FirstCategory) }

    private lateinit var pickerLayout: PickerLayout
    private lateinit var adapter: PickerGridAdapter

    /** a tab tapped: shown as picked, though a short last section cannot scroll to the top */
    private var tabPicked = false

    // what is picked in a password, or in a field that asks not to be learned from, is not kept as recent
    private var keepsNoRecent = false

    /** The pinyin typed in the search, while it is open. */
    private val query = EmojiSearchQuery()

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        keepsNoRecent = capFlags.hasAny(CapabilityFlag.PasswordOrSensitive)
        // the space's badge asks what the hold to talk asks (CommonKeyActionListener), a shown password too
        if (::pickerLayout.isInitialized) pickerLayout.embeddedKeyboard.inPasswordField = service.inPasswordField
    }

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> {
                // Switch to NumberKeyboard before attaching KeyboardWindow
                (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(it.act)
                // The real switchLayout (detachCurrentLayout and attachLayout) in KeyboardWindow is postponed,
                // so we have to postpone attachWindow as well
                ContextCompat.getMainExecutor(context).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is KeyAction.FcitxKeyAction -> {
                // we want the behavior of CommitAction (commit the character as-is),
                // but don't want to include it in recently used list
                commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(it.act), source)
            }

            KeyAction.PanelBackAction -> backToKeyboard()

            is KeyAction.EmojiSearchAction -> search(it.on)

            KeyAction.PanelLockAction -> {
                locked = !locked
                pickerLayout.embeddedKeyboard.showLocked(locked)
            }

            is KeyAction.CommitAction -> {
                if (!keepsNoRecent) {
                    adapter.insertRecent(it.text)
                    // locked, the panel stays: the recently used are there to go to now
                    updateRecentTab()
                }
                commonKeyActionListener.listener.onKeyAction(it, source)
                // a symbol picked from the panel (the keys below it send no CommitAction)
                if (panel && sidePanel.returnsAfterPick(locked)) backToKeyboard()
            }

            else -> commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    // where the search's keys go: EmojiSearchQuery
    private val searchKeyActionListener = KeyActionListener { it, source ->
        val outcome = when {
            it is KeyAction.FcitxKeyAction -> query.type(it.act)
            it is KeyAction.SymAction && it.sym == EmojiSearchUi.BACKSPACE -> query.backspace(it.states.has(KeyState.Repeat))
            else -> EmojiSearchQuery.Outcome.PASSED
        }
        when (outcome) {
            EmojiSearchQuery.Outcome.CHANGED -> searchFor(query.text)
            EmojiSearchQuery.Outcome.PASSED -> keyActionListener.onKeyAction(it, source)
            EmojiSearchQuery.Outcome.SWALLOWED -> Unit
        }
    }

    /** The search shown ([on]) from no query, or the emoji again. */
    private fun search(on: Boolean) {
        // made the first time it opens, not with the panel
        val ui = (if (on) pickerLayout.openSearch(::setUpSearch) else pickerLayout.search) ?: return
        query.clear()
        if (on) searchFor(query.text)
        ui.isVisible = on
    }

    private fun setUpSearch(ui: EmojiSearchUi) {
        ui.keyboard.popupActionListener = popup.listener
        ui.keyboard.keyActionListener = searchKeyActionListener
        ui.onPick = { keyActionListener.onKeyAction(KeyAction.CommitAction(it), KeyActionListener.Source.Keyboard) }
    }

    private fun searchFor(typed: String) {
        // in the skin tone a tap puts in
        val found = PickerData.EmojiWords.search(typed).filter(policy::filter).map(policy::transform)
        pickerLayout.search?.show(typed, found)
    }

    /** The panel's side lists the recently used once there are any; the emoji have no side. */
    private fun updateRecentTab() {
        pickerLayout.side?.setShown(SymbolPanel.RECENT, sidePanel.shows(SymbolPanel.RECENT, adapter.recentEmpty))
    }

    private val popupActionListener: PopupActionListener by lazy {
        PopupActionListener {
            when (it) {
                is PopupAction.PreviewAction -> {
                    if (!popupPreview) return@PopupActionListener
                }
                is PopupAction.ShowKeyboardAction -> {
                    // the finger moving over the popup keyboard is not the list scrolling
                    pickerLayout.grid.requestDisallowInterceptTouchEvent(true)
                }
                else -> {}
            }
            popup.listener.onPopupAction(it)
        }
    }

    private fun backToKeyboard() {
        // after the key's own handling, not while the view it was pressed on is taken away
        ContextCompat.getMainExecutor(context).execute {
            // gone already (two quick picks, or the bar's back arrow): not the keyboard again
            if (windowManager.isAttached(this)) windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onCreateView() = PickerLayout(context, theme, switchKey, density, panel, if (search) SearchKey else null).apply {
        pickerLayout = this
        val bordered = followKeyBorder && keyBorder
        adapter = PickerGridAdapter(grid, keyActionListener, popupActionListener, data(), key.name, bordered, policy)
        grid.adapter = adapter
        side?.apply {
            setCategories(adapter.categories.map { context.getString(if (it.short != 0) it.short else it.name) })
            onPick = ::select
        }
        if (panel) return@apply
        tabsUi.apply {
            setTabs(adapter.categories)
            setOnTabClickListener { i ->
                search(false)
                show(i)
            }
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) tabPicked = false
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (tabPicked) return
                tabsUi.activateTab(adapter.categoryAt(grid.gridLayoutManager.findFirstVisibleItemPosition()))
            }
        })
    }

    private fun show(category: Int) {
        // what was picked since the picker opened: no finger is in the list while a tab is tapped
        if (category == 0) adapter.rebuild()
        tabPicked = true
        // nothing used yet: the tab of what is shown, not one that looks empty
        pickerLayout.tabsUi.activateTab(adapter.categoryAt(adapter.startOf(category)))
        pickerLayout.grid.stopScroll()
        pickerLayout.grid.gridLayoutManager.scrollToPositionWithOffset(adapter.startOf(category), 0)
    }

    /** The panel's [category], alone beside the side, from its top. */
    private fun select(category: Int) {
        sidePanel.select(category)
        adapter.only = category
        adapter.rebuild()
        pickerLayout.side?.activate(category)
        pickerLayout.grid.stopScroll()
        pickerLayout.grid.scrollToPosition(0)
    }

    // the panel: the title bar, its back arrow and "符号"; the emoji: their tabs
    override fun onCreateBarExtension() = if (panel) null else pickerLayout.tabsUi.root

    override val title: String get() = if (panel) context.getString(R.string.picker_title) else ""

    override fun onAttached() {
        // a view made anew mid-field (a dark-mode switch replaces it) is told of no start: the field is asked
        service.currentInputEditorInfo?.let {
            keepsNoRecent = CapabilityFlags.forField(it, service.packageName).hasAny(CapabilityFlag.PasswordOrSensitive)
        }
        if (panel) {
            sidePanel.open(adapter.recentEmpty)
            updateRecentTab()
            select(sidePanel.selected)
            pickerLayout.embeddedKeyboard.showLocked(locked)
        } else {
            search(false)
            // opens on the first category (the common symbols), not on what was recently used
            adapter.rebuild()
            show(firstShown(adapter.categories.size))
        }
        pickerLayout.embeddedKeyboard.also {
            it.onReturnDrawableUpdate(returnKeyDrawable.appearance)
            it.keyActionListener = keyActionListener
            it.inPasswordField = service.inPasswordField
        }
        pickerLayout.search?.keyboard?.keyActionListener = searchKeyActionListener
    }

    override fun onDetached() {
        popup.dismissAll()
        pickerLayout.embeddedKeyboard.keyActionListener = null
        pickerLayout.search?.keyboard?.keyActionListener = null
    }

    override val showTitle get() = panel

    companion object {
        /** the common symbols (or the first emoji), not the recently used before them */
        private const val FirstCategory = 1

        /** The category a picker of [count] opens on: no emoji (the app without their data), the recently used, the only one there is. */
        internal fun firstShown(count: Int) = minOf(FirstCategory, count - 1)

        private val SearchKey = KeyDef(
            KeyDef.Appearance.Image(
                src = R.drawable.ic_baseline_search_24,
                variant = KeyDef.Appearance.Variant.Alternative,
                contentDescription = R.string.emoji_search
            ),
            setOf(KeyDef.Behavior.Press(KeyAction.EmojiSearchAction(true)))
        )
    }
}