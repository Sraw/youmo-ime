/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keys.KeyEdit
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.KeyListsCache
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.popup.PopupEditor
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.ui.cards.CardColumns
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.cards.AccordionLayout
import org.fcitx.fcitx5.android.ui.main.settings.cards.CardGrid
import org.fcitx.fcitx5.android.ui.main.settings.cards.ChoiceCard
import org.fcitx.fcitx5.android.ui.main.settings.cards.FoldCard
import org.fcitx.fcitx5.android.ui.main.settings.cards.KeyboardGlyphView
import org.fcitx.fcitx5.android.ui.main.settings.cards.settingsCard
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchAnchors
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import timber.log.Timber

/** Long presses are keyed by the key's one name ([KeyLists]), starting from what it pops up on its page's keyboard. */
class PopupOverridesFragment : KeyCapsPageFragment(), SearchAnchors {

    private val viewModel: MainViewModel by activityViewModels()

    private val prefs get() = AppPrefs.getInstance()

    /** Never written where [listsNewer]: a later version's lists are not overwritten, whoever writes. */
    private var lists: KeyLists
        get() = KeyListsCache.user.current
        set(value) {
            if (!listsNewer) prefs.internal.keyLists.setValue(value.serialize())
        }

    private var newerOf: Pair<String, Boolean>? = null

    /** A later version's key_lists (`@4`): read as none, never written over; long presses read-only here. */
    private val listsNewer: Boolean
        get() {
            val stored = prefs.internal.keyLists.getValue()
            newerOf?.takeIf { it.first == stored }?.let { return it.second }
            return (KeyLists.parse(stored) == null && !KeyLists.needsMigration(stored)).also { newerOf = stored to it }
        }

    private val punctuationKeys: Set<String> by lazy { PopupItemMarks.convertible(requireContext()) }

    // one instance while the text is unchanged, so resolvers keyed by it are made once
    private var slotsOf: Pair<String, SwipeSlots>? = null

    private var slots: SwipeSlots
        get() {
            val stored = prefs.internal.keySwipes.getValue()
            slotsOf?.takeIf { it.first == stored }?.let { return it.second }
            return (if (stored.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(stored)).also { slotsOf = stored to it }
        }
        set(value) = prefs.internal.keySwipes.setValue(value.serialize())

    // kept by its form: a layout's tab is made anew each rebuild
    private var page = KeyShortcutPages.Full

    private var layoutTabs: List<KeyShortcutPages.Page>? = null

    private var selecting = false
    private val selected = LinkedHashSet<String>()

    internal data class Snapshot(
        // null where not yet migrated (an old backup imported, migrated at the next start)
        val swipes: String, val lists: String?,
        val up: Boolean, val down: Boolean, val upAt: KeyMarkPosition, val downAt: KeyMarkPosition,
    )

    internal data class Step(val before: Snapshot, val after: Snapshot)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState ?: return
        val form = savedInstanceState.getString(STATE_PAGE)
        page = pages.firstOrNull { it.form == form } ?: page
        selecting = savedInstanceState.getBoolean(STATE_SELECTING)
        savedInstanceState.getStringArrayList(STATE_SELECTED)?.let(selected::addAll)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.form)
        outState.putBoolean(STATE_SELECTING, selecting)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    private fun snapshot() = with(prefs) {
        Snapshot(
            internal.keySwipes.getValue(), storedKeyLists(),
            keyboard.swipeUp.getValue(), keyboard.swipeDown.getValue(), keyboard.swipeUpPosition.getValue(), keyboard.swipeDownPosition.getValue(),
        )
    }

    private fun restore(s: Snapshot) = with(prefs) {
        internal.keySwipes.setValue(s.swipes)
        setStoredKeyLists(s.lists)
        keyboard.swipeUp.setValue(s.up)
        keyboard.swipeDown.setValue(s.down)
        keyboard.swipeUpPosition.setValue(s.upAt)
        keyboard.swipeDownPosition.setValue(s.downAt)
    }

    private fun change(block: () -> Unit) {
        val before = snapshot()
        block()
        val after = snapshot()
        if (after != before) {
            undo = Step(before, after)
            view?.let { v -> Snackbar.make(v, R.string.swipe_changed, Snackbar.LENGTH_LONG).setAction(R.string.undo) { undoLast() }.show() }
        }
        rebuild()
    }

    internal fun undoLast() {
        val step = undo ?: return
        undo = null
        // changed since elsewhere: nothing put back over that
        if (snapshot() == step.after) restore(step.before)
        // 撤销 after the page was left: onStop is past, so the device-protected copy is synced here
        if (isAdded && view != null) rebuild() else AppPrefs.getInstance().syncToDeviceEncryptedStorage()
    }

    private val pages get() = KeyShortcutPages.all(layoutTabs ?: layoutPages().also { layoutTabs = it })

    private fun layoutPages(): List<KeyShortcutPages.Page> {
        val ctx = context ?: return emptyList()
        return ImKeyboards.inUse(prefs.keyboard.imKeyboards(), EngineState.shuangpinScheme).mapNotNull { c ->
            SceneLayouts.board(ctx, c.id)?.let { KeyShortcutPages.of(c.id, c.name, it.layout, it.fragments) }?.takeIf { it.rows.isNotEmpty() }
        }
    }

    private fun titleOf(page: KeyShortcutPages.Page): String = page.title ?: getString(pageTitle(page))

    private fun boardOf(page: KeyShortcutPages.Page, name: String = page.names.first()): String = page.layout ?: KeyCaps.defaultBoard(name)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val ctx = context ?: return
                val back = undo != null
                menu.add(R.string.undo).apply {
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_baseline_undo_24)?.mutate()?.apply {
                        setTint(ctx.styledColor(MaterialR.attr.colorOnSurface))
                        alpha = if (back) OPAQUE else DIMMED
                    }
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    isEnabled = back
                }.setOnMenuItemClickListener { undoLast(); true }
                menu.add(R.string.reset_all).apply {
                    isEnabled = !listsNewer && lists.lists.isNotEmpty()
                }.setOnMenuItemClickListener { confirmResetAll(); true }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
        SearchHighlight.ready(this)
    }

    override fun reveal(id: String, found: (View) -> Unit) {
        val form = id.removePrefix(BOARD)
        if (form != id) pages.firstOrNull { it.form == form }?.takeIf { it !== page }?.let { page = it; rebuild() }
        view?.findViewWithTag<View>(id)?.let(found)
    }

    override fun rebuild() {
        val ctx = requireContext()
        // another page changed what the step would put back: undoing would silently undo that too
        if (undo?.after?.let { it != snapshot() } == true) undo = null
        layoutTabs = null
        page = pages.firstOrNull { it.form == page.form } ?: KeyShortcutPages.Full
        val stored = slots
        val user = lists
        wide.apply {
            removeAllViews()
            isVisible = true
            add(selector(ctx, stored, user), lParams(matchParent))
        }
        content.apply {
            removeAllViews()
            add(keyCard(ctx, stored, user), lParams(matchParent))
            add(if (selecting) selectionBar(ctx) else actionBar(ctx), lParams(matchParent) { topMargin = ctx.dp(8) })
        }
        activity?.invalidateMenu()
    }

    private fun keyCard(ctx: Context, stored: SwipeSlots, user: KeyLists): View {
        val box = ctx.verticalLayout {
            val pad = ctx.dp(KEY_CARD_PAD_DP)
            setPadding(pad, pad, pad, pad)
            add(TextView(ctx).apply {
                text = titleOf(page)
                textSize = 14f
                setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
            }, lParams(matchParent))
            val hint = when {
                page === KeyShortcutPages.Full -> R.string.swipe_slots_hint
                page.form == KeyShortcuts.FUNCTION -> R.string.swipe_slots_hint_function
                else -> R.string.swipe_slots_hint_page
            }
            add(text(ctx, getString(hint), body = true), lParams(matchParent) { topMargin = ctx.dp(4) })
            if (listsNewer) add(text(ctx, getString(R.string.long_press_newer), body = true), lParams(matchParent) { topMargin = ctx.dp(8) })
        }
        if (page.form == KeyShortcuts.FUNCTION) functionPage(ctx, box, page, stored, user) else slotPage(ctx, box, page, stored, user)
        pageNotes(ctx, box, page, stored)
        with(box) {
            add(text(ctx, getString(R.string.long_press_legend), body = false), lParams(matchParent) { topMargin = ctx.dp(12) })
            // greyed as a faded key is, though their long press is on
            page.rows.flatten().filter(page::elsewhere).map { it.label }.distinct().forEach {
                add(text(ctx, getString(R.string.long_press_legend_elsewhere, it), body = false), lParams(matchParent) { topMargin = ctx.dp(4) })
            }
        }
        return settingsCard(ctx).apply { addView(box) }
    }

    private fun button(ctx: Context, @StringRes title: Int, enabled: Boolean = true, anchor: String? = null, onClick: () -> Unit) =
        MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            setText(title)
            isEnabled = enabled
            tag = anchor
            setOnClickListener { onClick() }
        }

    private fun bar(ctx: Context, buttons: List<View>) = FlexboxLayout(ctx).apply {
        flexWrap = FlexWrap.WRAP
        buttons.forEach { add(it, FlexboxLayout.LayoutParams(wrapContent, wrapContent).apply { marginEnd = ctx.dp(8) }) }
    }

    private fun actionBar(ctx: Context) = bar(
        ctx,
        listOf(
            button(ctx, R.string.swipe_presets, anchor = PRESETS) { choosePreset() },
            button(ctx, R.string.swipe_flip_all, anchor = FLIP) { change { flipAll() } },
            button(ctx, R.string.swipe_multi_select, anchor = MULTI) { selecting = true; rebuild() },
        ),
    )

    private fun selectionBar(ctx: Context): View {
        val any = selected.isNotEmpty()
        val keys = selected.toList()
        return ctx.verticalLayout {
            add(text(ctx, if (any) getString(R.string.swipe_selected, keys.size) else getString(R.string.swipe_none_selected), body = true), lParams(matchParent))
            add(bar(ctx, listOf(
                button(ctx, R.string.select_all) { selected.addAll(slotKeys(page)); rebuild() },
                button(ctx, R.string.swipe_flip, any) { change { slots = slots.flipped(keys) } },
                button(ctx, R.string.swipe_set_first, any) { askWay(both = false) { up -> change { slots = slots.withAll(keys, up, SwipeSlot.First) } } },
                button(ctx, R.string.swipe_set_empty, any) { askWay(both = true) { up -> change { slots = slots.withAll(keys, up, SwipeSlot.Empty) } } },
                button(ctx, R.string.swipe_reset, any) { change { slots = slots.without(keys) } },
                button(ctx, R.string.done) { selecting = false; selected.clear(); rebuild() },
            )), lParams(matchParent))
        }
    }

    private fun askWay(both: Boolean, then: (Boolean?) -> Unit) {
        val ways: List<Boolean?> = if (both) listOf(true, false, null) else listOf(true, false)
        val names = listOfNotNull(getString(R.string.swipe_up), getString(R.string.swipe_down), getString(R.string.swipe_both_ways).takeIf { both })
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.swipe_which_way)
            .setItems(names.toTypedArray()) { _, i -> then(ways[i]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choosePreset() {
        val presets = listOf(SwipeOrientation.FirstUp, SwipeOrientation.FirstDown)
        val names = listOf(R.string.swipe_preset_first_up, R.string.swipe_preset_first_down).map(::getString)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.swipe_presets)
            .setItems(names.toTypedArray()) { _, i -> confirmPreset(names[i]) { change { applyPreset(presets[i]) } } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmPreset(name: String, apply: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setMessage(R.string.swipe_preset_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> apply() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPreset(orientation: SwipeOrientation) {
        val now = slots
        slots = now.applied(orientation)
        if (orientation != now.orientation) swapPositions()
    }

    // the ways' switches and places swap too, so marks stay put
    private fun flipAll() {
        slots = slots.flippedAll(KeyShortcutPages.FUNCTION_KEYS.map(SwipeSlots::functionKey))
        swapPositions()
        with(prefs.keyboard) {
            val up = swipeUp.getValue()
            swipeUp.setValue(swipeDown.getValue())
            swipeDown.setValue(up)
        }
    }

    private fun swapPositions() = with(prefs.keyboard) {
        val up = swipeUpPosition.getValue()
        swipeUpPosition.setValue(swipeDownPosition.getValue())
        swipeDownPosition.setValue(up)
    }

    private fun slotKeys(page: KeyShortcutPages.Page): List<String> =
        if (page.form == KeyShortcuts.FUNCTION) page.rows.map { SwipeSlots.functionKey(it.first().label) } else page.names

    private fun tapped(slotKey: String, edit: () -> Unit) {
        if (!selecting) return edit()
        if (!selected.remove(slotKey)) selected.add(slotKey)
        rebuild()
    }

    /** Columns of 8, 4, 3 or 2 as the width takes, never a row to swipe. */
    private fun selector(ctx: Context, stored: SwipeSlots, user: KeyLists): View {
        val config = resources.configuration
        val width = config.screenWidthDp.toFloat()
        val columns = CardColumns.columns(width, config.fontScale, CardColumns.CHOICE_MIN_DP, CardColumns.CHOICE)
        val nameSp = CardColumns.nameSp(CardColumns.cardWidth(width, columns), config.fontScale)
        val names = pages.map(::titleOf)
        // one name breaking makes every name two lines high, so a row's cards line up
        val paint = TextPaint().apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, nameSp.toFloat(), resources.displayMetrics) }
        val room = ctx.dp(CardColumns.cardWidth(width, columns)) - 2 * ctx.dp(ChoiceCard.NAME_EDGE_DP)
        val lines = if (names.any { paint.measureText(it) > room }) 2 else 1
        // the badges hang over the cards' corners, past their cells
        val grid = CardGrid(ctx, columns, CollectionInfoCompat.SELECTION_MODE_SINGLE).apply { clipChildren = false }
        pages.forEachIndexed { i, p ->
            val glyph = KeyboardGlyphView(ctx, p.rows.map { it.size }, bars = p.form == KeyShortcuts.FUNCTION)
            val card = ChoiceCard(ctx, names[i], glyph).apply {
                cell.tag = BOARD + p.form
                this.nameSp = nameSp
                nameLines = lines
                selected = p.form == page.form
                edited = KeyShortcutPages.edited(p, stored, user)
                onPick = { page = p; rebuild() }
            }
            if (selecting) strip(card, names[i], slotKeys(p))
            grid.addView(card.cell)
        }
        return grid
    }

    private fun strip(card: ChoiceCard, name: String, keys: List<String>) {
        val n = keys.count { it in selected }
        card.showStrip = true
        card.strip.apply {
            state = when (n) {
                0 -> MaterialCheckBox.STATE_UNCHECKED
                keys.size -> MaterialCheckBox.STATE_CHECKED
                else -> MaterialCheckBox.STATE_INDETERMINATE
            }
            count.text = getString(R.string.shortcut_card_count, n, keys.size)
            contentDescription = getString(R.string.shortcut_card_strip, name, n, keys.size)
            onCheck = {
                if (n == keys.size) selected.removeAll(keys.toSet()) else selected.addAll(keys)
                rebuild()
            }
        }
    }

    private fun said(slotKey: String, s: SwipeSlots): String? {
        val ways = listOf(true, false).mapNotNull { up ->
            SwipeSlotSection.shortLabel(requireContext(), s.resolve(slotKey, up))?.let { getString(R.string.swipe_way_said, getString(if (up) R.string.swipe_up else R.string.swipe_down), it) }
        }
        return when (ways.size) {
            0 -> null
            1 -> ways[0]
            else -> getString(R.string.swipe_ways_said, ways[0], ways[1])
        }
    }

    // an unnamed function key shows its raw name, not a crash
    private fun functionLabel(name: String) = FunctionLabels[name]?.let(::getString) ?: name

    /** By the key's width on the board, so mixed widths line up as the keyboard's columns. */
    private fun cellWeights(ctx: Context, page: KeyShortcutPages.Page): (String) -> Float {
        val widths = KeyCaps.widths(ctx, boardOf(page), page.names.first())
        return { name -> (widths[KeyId.of(name).name] ?: widths.values.minOrNull() ?: 1.0).toFloat() }
    }

    private fun slotPage(ctx: Context, box: LinearLayout, page: KeyShortcutPages.Page, stored: SwipeSlots, user: KeyLists) {
        val weight = cellWeights(ctx, page)
        page.rows.forEach { row ->
            with(box) { add(ctx.horizontalLayout {
                gravity = if (page.leftAligned) Gravity.START else Gravity.CENTER_HORIZONTAL
                row.forEach { key -> add(keyView(ctx, page, key, stored, user), keyParams(ctx, weight = weight(key.name))) }
                weightSum = page.rows.maxOf { r -> r.sumOf { weight(it.name).toDouble() } }.toFloat()
            }, lParams(matchParent) { topMargin = ctx.dp(6) }) }
        }
    }

    /** The engine draws a function key's face (space blank), so a name under each cap says which key it is. */
    private fun functionPage(ctx: Context, box: LinearLayout, page: KeyShortcutPages.Page, stored: SwipeSlots, user: KeyLists) {
        val config = resources.configuration
        val width = minOf(config.screenWidthDp, MAX_WIDTH_DP) - 2f * KEY_CARD_PAD_DP
        val grid = CardGrid(ctx, CardColumns.columns(width, config.fontScale, FUNCTION_MIN_DP, FUNCTION_COLUMNS))
        page.rows.forEach { row ->
            val key = row.first()
            grid.addView(ctx.verticalLayout {
                add(keyView(ctx, page, key, stored, user), lParams(matchParent, ctx.dp(KeyHeight)))
                add(text(ctx, functionLabel(key.label), body = false).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, lParams(matchParent) { topMargin = ctx.dp(2) })
            })
        }
        with(box) { add(grid, lParams(matchParent) { topMargin = ctx.dp(8) }) }
    }

    private fun keyView(ctx: Context, page: KeyShortcutPages.Page, key: KeyShortcutPages.Key, stored: SwipeSlots, user: KeyLists): View {
        // in its place on another board: not faded (that means long press off); a tap says where it is changed
        if (page.elsewhere(key)) {
            val note = getString(R.string.shortcut_key_elsewhere, key.label)
            // fitted as its neighbours, not a lone key filling the cell
            return KeyCaps.key(ctx, key.label, "", marked = false, dimmed = false, state = note,
                shortcut = KeyCaps.Shortcut(key.name, stored, layout = boardOf(page))).apply {
                setOnClickListener { v -> Snackbar.make(v, note, Snackbar.LENGTH_SHORT).show() }
            }
        }
        val (name, slotKey, label) = pageKey(page, key)
        // a layout key whose name another tab has too: marked, its editor saying which
        val sharedTab = page.layout?.let { KeyShortcutPages.sharedWith(page, key.name, pages) }
        val longPress = longPressOf(page, key, name, sharedTab)
        val shortcut = KeyCaps.Shortcut(name, stored, layout = page.layout)
        val shared = sharedTab?.let(::titleOf)
        val first = longPress?.let { resolverOf(ctx, it)?.edit(it.id)?.first }
        val hint = first?.takeIf { it != label }.orEmpty()
        val set = longPress?.let { user[it.id] }
        val marked = stored.isSet(slotKey) || set != null
        val state = listOfNotNull(
            getString(R.string.edited_x, label).takeIf { marked },
            getString(R.string.long_press_none).takeIf { set?.off == true },
        ).joinToString(" ").ifEmpty { null }
        return KeyCaps.key(
            ctx, label, hint, marked = marked, dimmed = set?.off == true, selected = slotKey in selected, state = state, said = said(slotKey, stored), shortcut = shortcut,
            tag = shared?.let { getString(R.string.shortcut_shared_tag) },
        ).apply {
            setOnClickListener { tapped(slotKey) { edit(label, slotKey, longPress, shared?.let { getString(R.string.shortcut_shared_with, it) }) } }
        }
    }

    private data class LongPress(val id: KeyId, val board: String)

    // an app-tab name edits the app's key on the app's board; a name only layouts share uses its layout's board
    private fun longPressOf(page: KeyShortcutPages.Page, key: KeyShortcutPages.Key, name: String, sharedTab: KeyShortcutPages.Page?): LongPress? = when {
        page.form != KeyShortcuts.FUNCTION ->
            LongPress(KeyId.of(name), page.layout?.takeIf { sharedTab == null || sharedTab.layout != null } ?: KeyCaps.defaultBoard(name))
        KeyDefaults.function(key.label) != null -> LongPress(KeyId.function(key.label), KeyCaps.defaultBoard(key.label))
        else -> null
    }

    private fun resolverOf(ctx: Context, longPress: LongPress) =
        KeyCaps.resolver(ctx, longPress.board) ?: null.also { Timber.w("no layout %s for the long press editor", longPress.board) }

    private data class PageKey(val name: String, val slotKey: String, val label: String)

    // the full keyboard's letters as drawn, lower-case
    private fun pageKey(page: KeyShortcutPages.Page, key: KeyShortcutPages.Key): PageKey = when {
        page.form == KeyShortcuts.FUNCTION -> PageKey(key.label, SwipeSlots.functionKey(key.label), functionLabel(key.label))
        page === KeyShortcutPages.Full -> PageKey(key.name, key.name, key.label.lowercase())
        else -> PageKey(key.name, key.name, key.label)
    }

    // e.g. 「j 下滑：画不下，键上显示为图标」; made off the main thread, dropped if the page was rebuilt since
    private fun pageNotes(ctx: Context, card: LinearLayout, page: KeyShortcutPages.Page, stored: SwipeSlots) {
        val function = page.form == KeyShortcuts.FUNCTION
        val ways = page.rows.flatMap { if (function) it.take(1) else it }.filterNot(page::elsewhere).flatMap { key ->
            val k = pageKey(page, key)
            listOf(true, false).filter { stored.isSet(k.slotKey, it) }.map { k to it }
        }
        if (ways.isEmpty()) return
        val box = ctx.verticalLayout()
        with(card) { add(box, lParams(matchParent)) }
        val made = ++pagesMade
        fates.request(ASK_PAGE, stored, { made == pagesMade }) { seen ->
            ways.forEach { (k, up) ->
                SwipeFates.note(ctx, seen, k.name, up)?.let { note ->
                    val way = getString(if (up) R.string.swipe_up else R.string.swipe_down)
                    with(box) { add(text(ctx, getString(R.string.swipe_fate_key, k.label, way, note), body = false), lParams(matchParent) { topMargin = ctx.dp(6) }) }
                }
            }
        }
    }

    // a page's notes come in only while it is the last
    private var pagesMade = 0

    private val fates = SwipeFatesLoader(this) {
        viewModel.fcitx.runOnReady {
            val on = enabledIme().toList()
            on to on.filter { it.uniqueName in InputMethodCatalog.TABLES }.associate { im ->
                im.uniqueName to imOptions(im.uniqueName)?.entries?.firstOrNull { it.key.substringAfterLast('/') == TableSettings.KEY_LABELS }?.value
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Tests: the notes then come with the main looper's next idle. */
    @VisibleForTesting
    internal fun awaitFates() = fates.await()

    override fun onDestroyView() {
        fates.releaseView()
        mainHandler.removeCallbacksAndMessages(null)
        pagesMade++
        super.onDestroyView()
    }

    override fun onDestroy() {
        fates.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // none once [open] says the dialog is gone
    private fun notes(slotKey: String, sections: List<SwipeSlotSection>, open: () -> Boolean): Runnable {
        var asked = 0
        val compute = Runnable {
            var s = slots
            sections.forEachIndexed { i, section -> section.peek()?.let { s = s.with(slotKey, i == 0, it) } }
            val n = ++asked
            fates.request(ASK_EDITOR, s, { open() && n == asked }) { seen ->
                val ctx = context ?: return@request
                // the engine gives a fate to set ways only
                sections.forEachIndexed { i, section -> section.note(SwipeFates.note(ctx, seen, slotKey, i == 0).takeIf { s.isSet(slotKey, i == 0) }) }
            }
        }
        sections.forEach { section ->
            val also = section.onChange
            section.onChange = {
                also?.invoke()
                mainHandler.removeCallbacks(compute)
                mainHandler.postDelayed(compute, NOTE_DELAY_MS)
            }
        }
        compute.run()
        return compute
    }

    private fun wayOn(up: Boolean) = if (up) prefs.keyboard.swipeUp.getValue() else prefs.keyboard.swipeDown.getValue()

    private fun wayTitle(up: Boolean): String =
        getString(if (up) R.string.swipe_up else R.string.swipe_down).let { if (wayOn(up)) it else getString(R.string.slot_card_off, it) }

    private fun resetButton(builder: AlertDialog.Builder, slotKey: String, longPress: LongPress?, stored: SwipeSlots) {
        val edited = !listsNewer && longPress?.let { lists[it.id] } != null
        if (!edited && !stored.isSet(slotKey)) return
        builder.setNeutralButton(R.string.reset_this_key) { _, _ ->
            change {
                longPress?.let { lists = lists.without(listOf(it.id)) }
                slots = stored.without(listOf(slotKey))
            }
        }
    }

    private fun section(ctx: Context, slotKey: String, up: Boolean): SwipeSlotSection {
        val strokesDrawn = prefs.keyboard.keyDrawing.getValue() == KeyDrawing.StrokeFilter && startsStroke(slotKey)
        return SwipeSlotSection(ctx, !wayOn(up), slots.resolve(slotKey, up), strokesDrawn)
    }

    // where a stroke can start (GlideInput.down): not 注音, numbers or function keys
    private fun startsStroke(slotKey: String): Boolean =
        listOf(KeyShortcutPages.Full, KeyShortcutPages.Nine, KeyShortcutPages.Grouped14, KeyShortcutPages.Grouped18).any { slotKey in it.names }

    private fun LinearLayout.keyParams(ctx: Context, weight: Float) = lParams(0, ctx.dp(KeyHeight)) {
        this.weight = weight
        marginStart = ctx.dp(2)
        marginEnd = ctx.dp(2)
    }

    private fun longPressViews(
        ctx: Context,
        start: KeyEdit?,
        editor: PopupEditor?,
        field: EditText,
        onChange: () -> Unit,
        adder: (() -> Boolean) -> Unit,
    ): List<Pair<View, Int>> = when {
        listsNewer -> listOf(text(ctx, getString(R.string.long_press_newer), body = true) to 0)
        start == null || editor == null -> emptyList()
        else -> listOfNotNull(
            if (start.user?.off == true) text(ctx, getString(R.string.long_press_off), body = true) to 0 else null,
        ) + chars(ctx, editor, field, R.string.long_press_none, punctuationKeys, onChange, adder)
    }

    // e.g. 首选 @ · 共 12 个, 长按不弹出字符, 只读
    private fun longPressSummary(editor: PopupEditor?): String = when {
        listsNewer || editor == null -> getString(R.string.long_press_card_read_only)
        editor.items.isEmpty() -> getString(R.string.long_press_card_summary_none)
        // a key with no first: not said, since neither long press nor 长按首选 types it
        else -> editor.first?.let { getString(R.string.long_press_card_summary, it, editor.items.size) }
            ?: getString(R.string.long_press_card_summary_no_first, editor.items.size)
    }

    /** [title] is the key alone as its cap shows it: the dialog edits slots too, so no 长按 prefix. */
    private fun edit(title: String, slotKey: String, longPress: LongPress?, shared: String? = null) {
        val ctx = requireContext()
        val resolver = longPress?.let { resolverOf(ctx, it) }
        val start = longPress?.takeIf { !listsNewer }?.let { resolver?.edit(it.id) }
        val default = longPress?.let { resolver?.defaultOf(it.id) }
        val editor = start?.let { PopupEditor(it.items, it.first, it.user?.marks.orEmpty()) }
        val field = EditText(ctx).apply {
            hint = getString(R.string.long_press_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val sections = listOf(section(ctx, slotKey, up = true), section(ctx, slotKey, up = false))
        var open = true
        var addTyped: () -> Boolean = { true }
        val cards = EditorCards(
            ctx,
            press = longPress != null,
            hint = getString(R.string.long_press_editor_hint).takeIf { editor != null },
            ways = listOf(wayTitle(up = true), wayTitle(up = false)),
            sections = sections,
            first = { editor?.first },
            pressSummary = { longPressSummary(editor) },
        )
        if (longPress != null) cards.fill(longPressViews(ctx, start, editor, field, cards::changed) { addTyped = it })
        cards.changed()
        val compute = notes(slotKey, sections) { open }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(shared)
            .setView(cards.layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        val stored = slots
        resetButton(builder, slotKey, longPress, stored)
        builder.setOnDismissListener {
            open = false
            mainHandler.removeCallbacks(compute)
        }
        builder.show().apply { setCanceledOnTouchOutside(false); window?.let(::aboveTheKeyboard) }
            // a word left typed counts as added; an error keeps the dialog open on its card
            .onPositiveButtonClick onClick@{
                val chosen = sections.mapIndexed { i, section -> section.chosen() ?: return@onClick false.also { cards.showError(way = i) } }
                if (editor != null && !addTyped()) return@onClick false.also { cards.showError(way = null) }
                change {
                    if (longPress != null && start != null) editor?.let { save(longPress, it, start, default) }
                    slots = stored.with(slotKey, true, chosen[0]).with(slotKey, false, chosen[1])
                }
                true
            }
    }

    /** Untouched while unchanged; none where it equals [default]. First stored as `*` (the list's first) or `!` (none). */
    private fun save(longPress: LongPress, editor: PopupEditor, start: KeyEdit, default: DefaultList?) {
        if (editor.items == start.items && editor.first == start.first && editor.marks == start.user?.marks.orEmpty().filterKeys { it in start.items }) return
        val list = UserList(off = false, items = editor.items, first = KeyEdit.firstToStore(editor.first, editor.items), marks = editor.marks.toMap())
        lists = lists.with(longPress.id, list, default)
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_all)
            .setMessage(R.string.reset_all_long_press_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> change { lists = KeyLists.Empty } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object : SearchablePage {
        // kept past the page: the user leaves to try the keys and comes back to undo
        private var undo: Step? = null

        // ↶ and ⋮'s 全部恢复默认 have no View
        private const val BOARD = "view:keys/board/"
        private const val PRESETS = "view:keys/presets"
        private const val FLIP = "view:keys/flip"
        private const val MULTI = "view:keys/multi"

        override fun searchEntries(context: Context): List<PageEntry> =
            KeyShortcutPages.all.map { PageEntry(context.getString(pageTitle(it)), BOARD + it.form) } + listOf(
                PageEntry(context.getString(R.string.swipe_presets), PRESETS),
                PageEntry(context.getString(R.string.swipe_flip_all), FLIP),
                PageEntry(context.getString(R.string.swipe_multi_select), MULTI),
                PageEntry(context.getString(R.string.undo), "view:keys/undo", where = R.string.search_where_undo),
                PageEntry(context.getString(R.string.reset_all), "view:keys/reset_all", where = R.string.search_where_menu),
            )

        // 多选's bar exists only while keys are being chosen
        override val notSearched = setOf(
            R.string.select_all, R.string.swipe_flip, R.string.swipe_set_first, R.string.swipe_set_empty, R.string.swipe_reset, R.string.done,
        )

        private const val STATE_PAGE = "page"
        private const val STATE_SELECTING = "selecting"
        private const val STATE_SELECTED = "selected"

        @VisibleForTesting
        internal fun forgetUndo() {
            undo = null
        }

        internal const val KeyHeight = 52
        internal const val OtherKeyWidth = 44

        internal fun pageTitle(page: KeyShortcutPages.Page) = when (page.form) {
            KeyShortcutPreset.FULL -> R.string.shortcut_page_full
            KeyShortcutPreset.NINE -> R.string.shortcut_page_nine
            KeyShortcutPreset.GROUPED14 -> R.string.shortcut_page_g14
            KeyShortcutPreset.GROUPED18 -> R.string.shortcut_page_g18
            KeyShortcutPreset.ZHUYIN_NINE -> R.string.shortcut_page_zy9
            KeyShortcutPreset.ZHUYIN_FULL -> R.string.shortcut_page_zyfull
            KeyShortcutPreset.NUMBER -> R.string.shortcut_page_number
            KeyShortcuts.FUNCTION -> R.string.shortcut_page_function
            else -> error("no title for the shortcut page ${page.form}")
        }

        private const val KEY_CARD_PAD_DP = 12
        private const val FUNCTION_MIN_DP = 64
        private val FUNCTION_COLUMNS = listOf(4, 3)

        private const val OPAQUE = 255
        private const val DIMMED = 97

        // a later ask with this key supersedes earlier ones
        private const val ASK_PAGE = "page"
        private const val ASK_EDITOR = "editor"

        private const val NOTE_DELAY_MS = 300L

        internal val FunctionLabels = mapOf(
            "space" to R.string.fn_key_space, "backspace" to R.string.fn_key_backspace, "return" to R.string.fn_key_return,
            "shift" to R.string.fn_key_shift, "comma" to R.string.fn_key_comma, "period" to R.string.fn_key_period,
            "symbols" to R.string.fn_key_symbols, "numbers" to R.string.fn_key_numbers, "lang" to R.string.fn_key_lang,
            "retype" to R.string.fn_key_retype, "zero" to R.string.fn_key_zero, "separator" to R.string.fn_key_separator,
        )
    }
}

/** A key code within longer text is text, kept as [PopupOverrides.with] keeps it. */
internal fun keyCodeIn(text: String): String? = PopupOverrides.tokens(text).firstOrNull(PopupOverrides::rejects)

/** Private-use code points (双拼's 18 keys) are shown as U+F8C1: most fonts draw them blank. */
internal fun shownCode(code: String): String = code.codePointAt(0).let {
    if (Character.getType(it) == Character.PRIVATE_USE.toInt()) "U+%04X".format(it) else code
}

private fun text(ctx: Context, s: String, body: Boolean) = TextView(ctx).apply {
    text = s
    textSize = if (body) 14f else 12f
    setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
}

private fun otherParams(ctx: Context) = FlexboxLayout.LayoutParams(ctx.dp(PopupOverridesFragment.OtherKeyWidth), ctx.dp(PopupOverridesFragment.KeyHeight)).apply {
    setMargins(0, 0, ctx.dp(6), ctx.dp(6))
}

private class Action(@StringRes val text: Int, val enabled: () -> Boolean, val run: () -> Unit)

/** A tap only selects; buttons act on it (a moving tap or a × beside it surprised). Buttons always there, so nothing moves. */
private fun chars(
    ctx: Context,
    editor: PopupEditor,
    field: EditText,
    @StringRes emptyText: Int,
    punctuationKeys: Set<String>,
    onChange: () -> Unit,
    adder: (() -> Boolean) -> Unit,
): List<Pair<View, Int>> {
    val keys = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
    val empty = text(ctx, ctx.getString(emptyText), body = true)
    val actions = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
    lateinit var render: () -> Unit
    val marks = PopupItemMarks(ctx, editor, field, punctuationKeys) { render() }
    val buttons = listOf(
        Action(R.string.long_press_make_first, { editor.canMakeFirst }, editor::makeFirst),
        Action(R.string.long_press_move_left, { editor.canMoveLeft }, editor::moveLeft),
        Action(R.string.long_press_move_right, { editor.canMoveRight }, editor::moveRight),
        Action(R.string.delete, { editor.canDelete }, editor::delete),
    ).map { action ->
        val button = PopupItemMarks.textButton(ctx, action.text).apply {
            setOnClickListener { action.run(); render() }
        }
        with(actions) { add(button, FlexboxLayout.LayoutParams(wrapContent, wrapContent)) }
        button to action.enabled
    }
    marks.addTo(actions)
    render = {
        keys.removeAllViews()
        editor.items.forEachIndexed { i, item ->
            val key = KeyCaps.key(ctx, item, "", marked = item == editor.first, dimmed = false, selected = i == editor.selected, tag = marks.tag(item))
            key.setOnClickListener { editor.tap(i); render() }
            keys.add(key, otherParams(ctx))
        }
        empty.isVisible = editor.items.isEmpty()
        buttons.forEach { (button, enabled) -> button.isEnabled = enabled() }
        marks.render()
        onChange()
    }
    // false where the field holds a key code or what was typed did not all fit
    fun addTyped(): Boolean {
        if (rejected(ctx, field)) return false
        val fit = editor.add(field.text.toString(), marks.adding, punctuationKeys)
        field.text = null
        if (!fit) {
            field.error = ctx.getString(R.string.long_press_full, KeyLists.MAX_ITEMS)
            field.requestFocus()
        }
        render()
        return fit
    }
    adder(::addTyped)
    // on error the keyboard stays up
    field.setOnEditorActionListener { v, _, _ ->
        if (addTyped()) {
            v.clearFocus()
            ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        }
        true
    }
    val addRow = ctx.horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
        add(field, lParams(0, wrapContent) { weight = 1f })
        add(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.add)
            setOnClickListener { addTyped() }
        }, lParams())
    }
    render()
    val rule = View(ctx).apply {
        setBackgroundColor(ctx.styledColor(MaterialR.attr.colorOutlineVariant))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    }
    return listOf(keys to 4, empty to 0, marks.line to 4, actions to 0, rule to 4, addRow to 4) + marks.underField()
}

/** A key code is never kept (a key typing it would read as that key): said, not silently dropped. */
private fun rejected(ctx: Context, field: EditText): Boolean {
    val code = keyCodeIn(field.text.toString()) ?: return false
    field.error = ctx.getString(R.string.popup_overrides_code_rejected, shownCode(code))
    field.requestFocus()
    return true
}

/** From API 30 a floating window is not resized for the IME, so it takes the screen height and fits content above it. */
private fun aboveTheKeyboard(window: Window) {
    @Suppress("DEPRECATION")
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val content = window.findViewById<View>(android.R.id.content)
    var up: Boolean? = null
    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
        val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
        val below = if (ime) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
        content.updatePadding(bottom = below)
        if (ime != up) {
            up = ime
            window.setLayout(window.attributes.width, if (ime) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ViewCompat.onApplyWindowInsets(v, insets)
    }
}

private class EditorCards(
    ctx: Context,
    press: Boolean,
    hint: String?,
    ways: List<String>,
    private val sections: List<SwipeSlotSection>,
    private val first: () -> String?,
    private val pressSummary: () -> String,
) {
    private val pressCard = if (press) FoldCard(ctx, ctx.getString(R.string.long_press_card), hint) else null
    private val wayCards = ways.map { FoldCard(ctx, it) }
    private val cards = listOfNotNull(pressCard) + wayCards

    val layout = AccordionLayout(ctx, cards).apply {
        val pad = ctx.dp(16)
        setPadding(pad, ctx.dp(8), pad, ctx.dp(8))
        clipToPadding = false
    }

    init {
        sections.forEachIndexed { i, section ->
            section.onChange = { wayCards[i].summarize(section) }
            wayCards[i].content.addView(section.view)
        }
    }

    fun fill(views: List<Pair<View, Int>>) {
        val content = pressCard?.content ?: return
        views.forEach { (view, top) ->
            content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, view.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = content.context.dp(top)
            })
        }
    }

    fun changed() {
        pressCard?.summary(pressSummary())
        sections.forEachIndexed { i, section ->
            section.firstChar = first()
            wayCards[i].summarize(section)
        }
    }

    /** [way] 0 is 上滑; null is the long press card. */
    fun showError(way: Int?) = layout.showError(if (way == null) 0 else cards.indexOf(wayCards[way]))

    fun FoldCard.summarize(section: SwipeSlotSection) =
        summary(section.summary(), section.summaryIcon?.let { ContextCompat.getDrawable(view.context, it)?.mutate() })
}
