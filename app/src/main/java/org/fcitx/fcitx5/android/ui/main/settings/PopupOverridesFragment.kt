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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
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
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView
import timber.log.Timber
import com.google.android.material.R as MaterialR

/**
 * 按键字符和快捷键 (060-swipe-slots): each keyboard's keys drawn as the user sees them, a tap editing a key's
 * long press and its two slots (上滑 and 下滑: the long press's first, a command, a text, nothing). Every page's
 * long presses are by the key's one name ([KeyLists] in `key_lists`, 060-keys-unify S5), each starting from what
 * the key pops up on its page's keyboard ([org.fcitx.fcitx5.android.input.keys.KeyResolver.edit]); the slots are
 * [SwipeSlots]. Above the keys: 预设, 翻转上下滑, 多选 (keys
 * flipped, set to the first or nothing, put back, at once) and 撤销, which takes the last change back.
 */
class PopupOverridesFragment : KeyCapsPageFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val prefs get() = AppPrefs.getInstance()

    /**
     * Every key's long press the user set, as the keyboards read it (the old keys migrated where key_lists is not yet).
     * Written only where [listsNewer] is not: a later version's lists are never written over, whoever writes (撤销's
     * 恢复此键 reaches here with a slot alone set, its key's list read-only).
     */
    private var lists: KeyLists
        get() = KeyListsCache.user.current
        set(value) {
            if (!listsNewer) prefs.internal.keyLists.setValue(value.serialize())
        }

    // [listsNewer] of the stored text it was read from: a page rebuilt or a key edited reads it several times
    private var newerOf: Pair<String, Boolean>? = null

    /** A later version's key_lists (`@3`, §4.3, 060-row-unlock §5): read as none, never written over; the long presses read-only here. */
    private val listsNewer: Boolean
        get() {
            val stored = prefs.internal.keyLists.getValue()
            newerOf?.takeIf { it.first == stored }?.let { return it.second }
            return (KeyLists.parse(stored) == null && !KeyLists.needsMigration(stored)).also { newerOf = stored to it }
        }

    // [slots] of the stored text it was parsed from: one instance while it is unchanged, so the keyboards' resolvers
    // made of it (SceneBoard's, kept by their slots) are made once, not at each rebuild
    private var slotsOf: Pair<String, SwipeSlots>? = null

    private var slots: SwipeSlots
        get() {
            val stored = prefs.internal.keySwipes.getValue()
            slotsOf?.takeIf { it.first == stored }?.let { return it.second }
            return (if (stored.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(stored)).also { slotsOf = stored to it }
        }
        set(value) = prefs.internal.keySwipes.setValue(value.serialize())

    // the tab shown ([pages]), kept as the page is rebuilt
    private var page = KeyShortcutPages.Full

    // 多选: on, and the keys chosen (their slots' names), kept across tabs
    private var selecting = false
    private val selected = LinkedHashSet<String>()

    /** Every setting this page writes, as it was before the last change: what 撤销 puts back. */
    internal data class Snapshot(
        // key_lists as stored: null where it is not yet (a 0.5 or beta backup imported, not migrated till the next start)
        val swipes: String, val lists: String?,
        val up: Boolean, val down: Boolean, val upAt: KeyMarkPosition, val downAt: KeyMarkPosition,
    )

    /** A change: what was there [before], what it left [after] (another page's change since drops the step). */
    internal data class Step(val before: Snapshot, val after: Snapshot)

    // the tab, 多选 and the keys chosen kept across a rotation (the fragment made again)
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

    // [block] done, what it changed kept for one 撤销 (a change that changed nothing keeps the one before)
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

    /** The last change taken back: one step, a second 撤销 has nothing to take. */
    internal fun undoLast() {
        val step = undo ?: return
        undo = null
        // changed since elsewhere (按键's switches and places): nothing put back over that
        if (snapshot() == step.after) restore(step.before)
        // 撤销 on the snackbar after the page was left: the step still put back, the page no longer there to
        // redraw, and its onStop past, so the keyboard's device-protected copy is brought up to date here
        if (isAdded && view != null) rebuild() else AppPrefs.getInstance().syncToDeviceEncryptedStorage()
    }

    private val pages get() = KeyShortcutPages.all

    override fun rebuild() {
        val ctx = requireContext()
        // what the step would put back was changed since by another page (按键's 上滑, 下滑 and their places,
        // the page left and come back to): taking it back would undo that too, unseen, so there is none
        if (undo?.after?.let { it != snapshot() } == true) undo = null
        content.apply {
            removeAllViews()
            add(tabs(ctx), lParams(matchParent))
            val hint = when {
                page === KeyShortcutPages.Full -> R.string.swipe_slots_hint
                page.form == KeyShortcuts.FUNCTION -> R.string.swipe_slots_hint_function
                else -> R.string.swipe_slots_hint_page
            }
            add(text(ctx, getString(hint), body = true), lParams(matchParent) { topMargin = ctx.dp(8) })
            if (listsNewer) add(text(ctx, getString(R.string.long_press_newer), body = true), lParams(matchParent) { topMargin = ctx.dp(8) })
            add(if (selecting) selectionBar(ctx) else actionBar(ctx), lParams(matchParent) { topMargin = ctx.dp(8) })
            slotPage(ctx, page)
            add(text(ctx, getString(R.string.long_press_legend), body = false).apply {
                setPadding(0, ctx.dp(16), 0, 0)
            }, lParams(matchParent))
            // the function keys drawn in place: greyed as a faded key is, though their long press is on
            page.rows.flatten().filter(page::elsewhere).map { it.label }.distinct().forEach {
                add(text(ctx, getString(R.string.long_press_legend_elsewhere, it), body = false), lParams(matchParent) { topMargin = ctx.dp(4) })
            }
            if (lists.lists.isNotEmpty()) {
                add(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.reset_all)
                    setOnClickListener { confirmResetAll() }
                }, lParams { topMargin = ctx.dp(12) })
            }
        }
    }

    private fun button(ctx: Context, @StringRes title: Int, enabled: Boolean = true, onClick: () -> Unit) =
        MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            setText(title)
            isEnabled = enabled
            setOnClickListener { onClick() }
        }

    private fun bar(ctx: Context, buttons: List<View>) = FlexboxLayout(ctx).apply {
        flexWrap = FlexWrap.WRAP
        buttons.forEach { add(it, FlexboxLayout.LayoutParams(wrapContent, wrapContent).apply { marginEnd = ctx.dp(8) }) }
    }

    private fun actionBar(ctx: Context) = bar(
        ctx,
        listOf(
            button(ctx, R.string.swipe_presets) { choosePreset() },
            button(ctx, R.string.swipe_flip_all) { change { flipAll() } },
            button(ctx, R.string.swipe_multi_select) { selecting = true; rebuild() },
            button(ctx, R.string.undo, enabled = undo != null) { undoLast() },
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
                button(ctx, R.string.undo, enabled = undo != null) { undoLast() },
                button(ctx, R.string.done) { selecting = false; selected.clear(); rebuild() },
            )), lParams(matchParent))
        }
    }

    // which way a batch sets: 上滑, 下滑, or (where [both]) the two; null for the two
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

    // every key on every keyboard, asked first as 0.5 asked (撤销 takes it back all the same)
    private fun confirmPreset(name: String, apply: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setMessage(R.string.swipe_preset_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> apply() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // every key back to [orientation]'s presets; the first's mark kept where it was drawn, its way changed or not
    private fun applyPreset(orientation: SwipeOrientation) {
        val now = slots
        slots = now.applied(orientation)
        if (orientation != now.orientation) swapPositions()
    }

    // 翻转上下滑: every key's two slots swapped, and with them the two ways' switches and places, so the
    // marks stay where they were and each swipe does what the other did
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

    // the slots' names of [page]'s keys, a function key once
    private fun slotKeys(page: KeyShortcutPages.Page): List<String> =
        if (page.form == KeyShortcuts.FUNCTION) page.rows.map { SwipeSlots.functionKey(it.first().label) } else page.names

    // a tap on a key: chosen or not while 多选 is on, else its editor
    private fun tapped(slotKey: String, edit: () -> Unit) {
        if (!selecting) return edit()
        if (!selected.remove(slotKey)) selected.add(slotKey)
        rebuild()
    }

    private fun tabs(ctx: Context) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        var shown: Chip? = null
        add(ChipGroup(ctx).apply {
            isSingleLine = true
            isSingleSelection = true
            pages.forEach { p ->
                addView(Chip(ctx).apply {
                    isCheckable = true
                    setText(pageTitle(p))
                    isChecked = p === page
                    if (isChecked) shown = this
                    setOnClickListener { page = p; rebuild() }
                })
            }
        }, lParams(wrapContent, wrapContent))
        // the page's tab in sight: the row is rebuilt with each page, scrolled back to its start, and
        // the tabs are wider than a phone (注音全键盘's was off the edge once 注音九键's came before it)
        post { shown?.let { scrollTo((it.right - width).coerceAtLeast(0), 0) } }
    }

    // a key's two slots as a screen reader says them: 上滑 then 下滑, a way with nothing left out
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

    private fun captionWidth(ctx: Context, page: KeyShortcutPages.Page): Int {
        val paint = KeyCaps.label(ctx, "", CAPTION_SP).paint
        return page.rows.maxOf { paint.measureText(functionLabel(it.first().label)) }.toInt() + ctx.dp(CAPTION_GAP_DP)
    }

    // a function key of ime-core's not named here yet shows as it is called there, not a crash
    private fun functionLabel(name: String) = FunctionLabels[name]?.let(::getString) ?: name

    /** A tab: its keys in their rows, a tap editing the key's slots and long press. */
    /**
     * Each cell's weight by its key's name: its key's width on the page's board (KeyCaps.widths), so mixed widths
     * (注音九键's 14 and 24) draw at one height and line up as the keyboard's columns; a function page's all alike.
     */
    private fun cellWeights(ctx: Context, page: KeyShortcutPages.Page, function: Boolean): (String) -> Float {
        val widths = if (function) emptyMap() else KeyCaps.widths(ctx, KeyCaps.defaultBoard(page.names.first()), page.names.first())
        // a key the board has none of is logged where its cap is drawn (SceneKeyCell.onBoard); its cell a letter's share
        return { name -> (widths[KeyId.of(name).name] ?: widths.values.minOrNull() ?: 1.0).toFloat() }
    }

    private fun slotPage(ctx: Context, page: KeyShortcutPages.Page) {
        val stored = slots
        val user = lists
        val function = page.form == KeyShortcuts.FUNCTION
        // a function key's name beside its engine-drawn cap, on one line: as wide as the widest name on the page
        val captionWidth = if (function) captionWidth(ctx, page) else 0
        val weight = cellWeights(ctx, page, function)
        page.rows.forEach { row ->
            val keys = if (function) row.take(1) else row
            with(content) { add(ctx.horizontalLayout {
                gravity = if (page.leftAligned) Gravity.START else Gravity.CENTER_HORIZONTAL
                keys.forEach { key ->
                    // a function key in its place (注音九键's ，): drawn as on the keyboard, not faded (faded says a long press is off), a tap saying where it is changed
                    if (page.elsewhere(key)) {
                        val note = getString(R.string.shortcut_key_elsewhere, key.label)
                        // drawn as the board's own key (its r4c4), fitted as its neighbours, not a lone key filling the cell
                        add(KeyCaps.key(ctx, key.label, "", marked = false, dimmed = false, state = note,
                            shortcut = KeyCaps.Shortcut(key.name, stored, layout = KeyCaps.defaultBoard(page.names.first()))).apply {
                            setOnClickListener { v -> Snackbar.make(v, note, Snackbar.LENGTH_SHORT).show() }
                        }, keyParams(ctx, weight = weight(key.name)))
                        return@forEach
                    }
                    val (name, slotKey, label) = pageKey(page, key)
                    val longPress = longPressOf(page, key, name)
                    // the engine's cap: a character key by its name, a function key by its own (its up and down)
                    val shortcut = KeyCaps.Shortcut(name, stored)
                    // the key's first as a screen reader says it beside the label (the cap draws it where the keyboard does)
                    val first = longPress?.let { resolverOf(ctx, it)?.edit(it.id)?.first }
                    val hint = first?.takeIf { it != label }.orEmpty()
                    // the engine draws a function key's very face (space blank, the comma a ,): its name beside it, so
                    // the page says which key it is
                    // every function key has its caption (the width at least the gap), a character key none
                    val caption = captionWidth > 0
                    val params = if (caption) lParams(0, ctx.dp(KeyHeight)) { this.weight = 1f } else keyParams(ctx, weight = weight(key.name))
                    if (caption) add(KeyCaps.label(ctx, label, CAPTION_SP).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        maxLines = 1
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, lParams(captionWidth, ctx.dp(KeyHeight)))
                    val set = longPress?.let { user[it.id] }
                    val marked = stored.isSet(slotKey) || set != null
                    // the outline and the fading, said
                    val state = listOfNotNull(
                        getString(R.string.edited_x, label).takeIf { marked },
                        getString(R.string.long_press_none).takeIf { set?.off == true },
                    ).joinToString(" ").ifEmpty { null }
                    add(KeyCaps.key(ctx, label, hint, marked = marked, dimmed = set?.off == true, selected = slotKey in selected, state = state, said = said(slotKey, stored), shortcut = shortcut).apply {
                        setOnClickListener { tapped(slotKey) { edit(label, slotKey, longPress) } }
                    }, params)
                }
                if (!function) weightSum = page.rows.maxOf { r -> r.sumOf { weight(it.name).toDouble() } }.toFloat()
            }, lParams(matchParent) { topMargin = ctx.dp(6) }) }
        }
        pageNotes(ctx, page, stored)
    }

    /** Whose long press a dialog edits: the key's one name, and the board its page draws it on (whose resolver starts it). */
    private data class LongPress(val id: KeyId, val board: String)

    // a character key's long press, and the function keys' with a list of characters (， 。)
    private fun longPressOf(page: KeyShortcutPages.Page, key: KeyShortcutPages.Key, name: String): LongPress? = when {
        page.form != KeyShortcuts.FUNCTION -> LongPress(KeyId.of(name), KeyCaps.defaultBoard(name))
        KeyDefaults.function(key.label) != null -> LongPress(KeyId.function(key.label), KeyCaps.defaultBoard(key.label))
        else -> null
    }

    private fun resolverOf(ctx: Context, longPress: LongPress) =
        KeyCaps.resolver(ctx, longPress.board) ?: null.also { Timber.w("no layout %s for the long press editor", longPress.board) }

    /** A page's key: its engine shortcut's name (a function key's own: `space`), its slots' key, what the page labels it. */
    private data class PageKey(val name: String, val slotKey: String, val label: String)

    // the full keyboard's letters as its page draws them, lower-case (its keys are named upper-case)
    private fun pageKey(page: KeyShortcutPages.Page, key: KeyShortcutPages.Key): PageKey = when {
        page.form == KeyShortcuts.FUNCTION -> PageKey(key.label, SwipeSlots.functionKey(key.label), functionLabel(key.label))
        page === KeyShortcutPages.Full -> PageKey(key.name, key.name, key.label.lowercase())
        else -> PageKey(key.name, key.name, key.label)
    }

    // under the keys, each way set on the page that the keyboard draws otherwise than set: 「j 下滑：画不下，键上显示为图标」;
    // the scenes made off the main thread, the notes put in when they are (none for a page rebuilt since)
    private fun pageNotes(ctx: Context, page: KeyShortcutPages.Page, stored: SwipeSlots) {
        val function = page.form == KeyShortcuts.FUNCTION
        val ways = page.rows.flatMap { if (function) it.take(1) else it }.filterNot(page::elsewhere).flatMap { key ->
            val k = pageKey(page, key)
            listOf(true, false).filter { stored.isSet(k.slotKey, it) }.map { k to it }
        }
        if (ways.isEmpty()) return
        val box = ctx.verticalLayout()
        with(content) { add(box, lParams(matchParent)) }
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

    // the pages built: a page's notes come in only while it is the last
    private var pagesMade = 0

    // the notes' scenes: the keyboards of the input methods on (read once from fcitx), made on a thread of their own
    private val fates = SwipeFatesLoader(this) {
        viewModel.fcitx.runOnReady {
            val on = enabledIme().toList()
            on to on.filter { it.uniqueName in InputMethodCatalog.TABLES }.associate { im ->
                im.uniqueName to imOptions(im.uniqueName)?.entries?.firstOrNull { it.key.substringAfterLast('/') == TableSettings.KEY_LABELS }?.value
            }
        }
    }

    // the editor's notes worked out again a moment after each change
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Every scene asked of the notes' thread so far made (tests: the notes then come with the main looper's next idle). */
    @VisibleForTesting
    internal fun awaitFates() = fates.await()

    override fun onDestroyView() {
        fates.releaseView()
        mainHandler.removeCallbacksAndMessages(null)
        // no page of the next view one of this one's: a note still on its way is of none
        pagesMade++
        super.onDestroyView()
    }

    override fun onDestroy() {
        fates.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // each way's note in the editor ([SwipeFates.note]): on opening, and again a moment after each change, both
    // ways from one making; none after [open] says the dialog is gone
    private fun notes(slotKey: String, sections: List<SwipeSlotSection>, open: () -> Boolean): Runnable {
        var asked = 0
        val compute = Runnable {
            var s = slots
            sections.forEachIndexed { i, section -> section.peek()?.let { s = s.with(slotKey, i == 0, it) } }
            val n = ++asked
            fates.request(ASK_EDITOR, s, { open() && n == asked }) { seen ->
                val ctx = context ?: return@request
                // a way left as the keyboard has it is no one's to say of (the engine gives its fate to the set alone)
                sections.forEachIndexed { i, section -> section.note(SwipeFates.note(ctx, seen, slotKey, i == 0).takeIf { s.isSet(slotKey, i == 0) }) }
            }
        }
        sections.forEach { section ->
            section.onChange = {
                mainHandler.removeCallbacks(compute)
                mainHandler.postDelayed(compute, NOTE_DELAY_MS)
            }
        }
        compute.run()
        return compute
    }

    // a way's section, titled by the way, said to be off where its switch is
    private fun section(ctx: Context, slotKey: String, up: Boolean): SwipeSlotSection {
        val on = if (up) prefs.keyboard.swipeUp.getValue() else prefs.keyboard.swipeDown.getValue()
        return SwipeSlotSection(ctx, getString(if (up) R.string.swipe_up else R.string.swipe_down), !on, slots.resolve(slotKey, up))
    }

    private fun LinearLayout.keyParams(ctx: Context, weight: Float) = lParams(0, ctx.dp(KeyHeight)) {
        this.weight = weight
        marginStart = ctx.dp(2)
        marginEnd = ctx.dp(2)
    }

    private fun otherParams(ctx: Context) = FlexboxLayout.LayoutParams(ctx.dp(OtherKeyWidth), ctx.dp(KeyHeight)).apply {
        setMargins(0, 0, ctx.dp(6), ctx.dp(6))
    }

    private fun heading(ctx: Context, title: Int) = TextView(ctx).apply {
        setText(title)
        textSize = 14f
        setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
        setPadding(0, ctx.dp(20), 0, ctx.dp(8))
    }

    private fun text(ctx: Context, s: String, body: Boolean) = TextView(ctx).apply {
        text = s
        textSize = if (body) 14f else 12f
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
    }

    /**
     * The characters drawn as the popup draws them, the first (what letting go gives) outlined; every one, the key's
     * own letters and digit too, moved, deleted and made the first alike (060-row-unlock).
     * A tap only selects one; what is done with it is up to the buttons below, apart from it: a
     * tap that moved or removed a character was a surprise, and a × beside it easily hit.
     * Each view with its top margin in dp, in order: the characters, a line said when there are
     * none, the buttons, [field] with its add button.
     */
    private fun chars(ctx: Context, editor: PopupEditor, field: EditText, @StringRes emptyText: Int, adder: (() -> Boolean) -> Unit): List<Pair<View, Int>> {
        val keys = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        val empty = text(ctx, getString(emptyText), body = true)
        val actions = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        lateinit var render: () -> Unit
        // each button with when it applies
        val buttons = listOf(
            Action(R.string.long_press_make_first, { editor.canMakeFirst }, editor::makeFirst),
            Action(R.string.long_press_move_left, { editor.canMoveLeft }, editor::moveLeft),
            Action(R.string.long_press_move_right, { editor.canMoveRight }, editor::moveRight),
            Action(R.string.delete, { editor.canDelete }, editor::delete),
        ).map { action ->
            val button = MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                setText(action.text)
                setOnClickListener { action.run(); render() }
            }
            with(actions) { add(button, FlexboxLayout.LayoutParams(wrapContent, wrapContent).apply { marginEnd = ctx.dp(8) }) }
            button to action.enabled
        }
        render = {
            keys.removeAllViews()
            editor.items.forEachIndexed { i, item ->
                val key = KeyCaps.key(ctx, item, "", marked = item == editor.first, dimmed = false, selected = i == editor.selected)
                key.setOnClickListener { editor.tap(i); render() }
                keys.add(key, otherParams(ctx))
            }
            empty.isVisible = editor.items.isEmpty()
            buttons.forEach { (button, enabled) -> button.isEnabled = enabled() }
        }
        // false where the field holds a key code or what was typed did not all fit, said under the field
        fun addTyped(): Boolean {
            if (rejected(field)) return false
            val fit = editor.add(field.text.toString())
            field.text = null
            if (!fit) {
                field.error = getString(R.string.long_press_full, KeyLists.MAX_ITEMS)
                field.requestFocus()
            }
            render()
            return fit
        }
        adder(::addTyped)
        // Done adds and puts the keyboard away, the whole dialog then in view; an error is left with the keyboard up
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
        return listOf(keys to 12, empty to 0, actions to 4, addRow to 8)
    }

    /**
     * [edit]'s long press part, each view with its top margin in dp: a later version's lists said read-only (where the
     * key has a long press), else the hint, a key turned off said so (migrated beta lists; kept off while its list is
     * left as it is), and [chars].
     */
    private fun longPressViews(
        ctx: Context,
        hasLongPress: Boolean,
        start: KeyEdit?,
        editor: PopupEditor?,
        field: EditText,
        adder: (() -> Boolean) -> Unit,
    ): List<Pair<View, Int>> = when {
        hasLongPress && listsNewer -> listOf(text(ctx, getString(R.string.long_press_newer), body = true) to 0)
        start == null || editor == null -> emptyList()
        else -> listOfNotNull(
            text(ctx, getString(R.string.long_press_editor_hint), body = false) to 0,
            if (start.user?.off == true) text(ctx, getString(R.string.long_press_off), body = true) to 8 else null,
        ) + chars(ctx, editor, field, R.string.long_press_none, adder)
    }

    /**
     * A key edited under [title]: its long press ([chars]) where [longPress] names one, its two slots
     * ([section]). [title] is the key alone as its cap shows it, on every tab (q, XC, ，键, 空格): the dialog
     * edits the slots too, so no 长按 before it.
     */
    private fun edit(title: String, slotKey: String, longPress: LongPress?) {
        val ctx = requireContext()
        val resolver = longPress?.let { resolverOf(ctx, it) }
        // a later version's key_lists (§4.3) is never written over: its long presses not edited here
        val start = longPress?.takeIf { !listsNewer }?.let { resolver?.edit(it.id) }
        val default = longPress?.let { resolver?.defaultOf(it.id) }
        val editor = start?.let { PopupEditor(it.items, it.first) }
        val field = EditText(ctx).apply {
            hint = getString(R.string.long_press_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val sections = listOf(section(ctx, slotKey, up = true), section(ctx, slotKey, up = false))
        var open = true
        var addTyped: () -> Boolean = { true }
        val compute = notes(slotKey, sections) { open }
        val layout = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            longPressViews(ctx, longPress != null, start, editor, field) { addTyped = it }.forEach { (view, top) -> add(view, lParams(matchParent) { topMargin = ctx.dp(top) }) }
            sections.forEach { add(it.view, lParams(matchParent)) }
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout.wrapInScrollView())
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        val stored = slots
        val edited = !listsNewer && longPress?.let { lists[it.id] } != null
        if (edited || stored.isSet(slotKey)) {
            builder.setNeutralButton(R.string.reset_this_key) { _, _ ->
                change {
                    longPress?.let { lists = lists.without(listOf(it.id)) }
                    slots = stored.without(listOf(slotKey))
                }
            }
        }
        builder.setOnDismissListener {
            open = false
            mainHandler.removeCallbacks(compute)
        }
        builder.show().apply { setCanceledOnTouchOutside(false); window?.let(::aboveTheKeyboard) }
            // a word left typed in the field counts as added; one holding a key code or not fitting is said so, the
            // dialog kept
            .onPositiveButtonClick onClick@{
                val chosen = sections.map { it.chosen() ?: return@onClick false }
                if (editor != null && !addTyped()) return@onClick false
                change {
                    if (longPress != null && start != null) editor?.let { save(longPress, it, start, default) }
                    slots = stored.with(slotKey, true, chosen[0]).with(slotKey, false, chosen[1])
                }
                true
            }
    }

    /**
     * The dialog kept above the keyboard while one is up, its content scrolling and its buttons in reach: a floating
     * window is neither resized nor moved for it from API 30 (SOFT_INPUT_ADJUST_RESIZE did nothing on 36), so there
     * the window takes the screen's height and its content the room above the keyboard's top, as the keyboard comes
     * and goes; on 29 the window is resized as asked.
     */
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

    /**
     * [editor]'s list stored for [longPress]'s key: nothing touched while its items and first are as they started (a
     * slot alone edited; a key off kept off); none where it is the editor's [default] (KeyLists.with). Its first `*`
     * for the list's first item, `!` for none, else the item itself (KeyEdit.firstToStore).
     */
    private fun save(longPress: LongPress, editor: PopupEditor, start: KeyEdit, default: DefaultList?) {
        if (editor.items == start.items && editor.first == start.first) return
        lists = lists.with(longPress.id, UserList(off = false, items = editor.items, first = KeyEdit.firstToStore(editor.first, editor.items)), default)
    }

    /**
     * Whether [field] holds a keyboard's key code, which PopupOverrides never keeps (a key typing it
     * would be read as that key): said under the field, not dropped without a word.
     */
    private fun rejected(field: EditText): Boolean {
        val code = keyCodeIn(field.text.toString()) ?: return false
        field.error = getString(R.string.popup_overrides_code_rejected, shownCode(code))
        field.requestFocus()
        return true
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_all)
            .setMessage(R.string.reset_all_long_press_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> change { lists = KeyLists.Empty } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class Action(@StringRes val text: Int, val enabled: () -> Boolean, val run: () -> Unit)

    companion object {
        // the one step back, kept past the page: the user leaves it to try the keys and comes back to take it back
        private var undo: Step? = null

        private const val STATE_PAGE = "page"
        private const val STATE_SELECTING = "selecting"
        private const val STATE_SELECTED = "selected"

        @VisibleForTesting
        internal fun forgetUndo() {
            undo = null
        }

        private const val KeyHeight = 52
        private const val OtherKeyWidth = 44

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

        // a function key's name beside its engine-drawn cap: its size, and the room after the widest
        private const val CAPTION_SP = 16f
        private const val CAPTION_GAP_DP = 12

        // the notes worked out again this long after the last change (a scene each way, upright and turned)

        // who asks for the notes' scenes: a later ask of one supersedes its earlier ones (SwipeFatesLoader.request)
        private const val ASK_PAGE = "page"
        private const val ASK_EDITOR = "editor"
        private const val NOTE_DELAY_MS = 300L

        // the function keys' names on the page ([KeyShortcutPages.FUNCTION_KEYS])
        internal val FunctionLabels = mapOf(
            "space" to R.string.fn_key_space, "backspace" to R.string.fn_key_backspace, "return" to R.string.fn_key_return,
            "shift" to R.string.fn_key_shift, "comma" to R.string.fn_key_comma, "period" to R.string.fn_key_period,
            "symbols" to R.string.fn_key_symbols, "numbers" to R.string.fn_key_numbers, "lang" to R.string.fn_key_lang,
            "retype" to R.string.fn_key_retype, "zero" to R.string.fn_key_zero, "separator" to R.string.fn_key_separator,
        )
    }
}

/**
 * The first item of [text] that PopupOverrides rejects (a keyboard's key code alone), or null: a
 * code within longer text is text, kept as [PopupOverrides.with] keeps it.
 */
internal fun keyCodeIn(text: String): String? = PopupOverrides.tokens(text).firstOrNull(PopupOverrides::rejects)

/**
 * [code] (one code point, a supplementary tap code too) as the error says it: a private-use one
 * (双拼's 18 keys') as U+F8C1, most fonts drawing it blank.
 */
internal fun shownCode(code: String): String = code.codePointAt(0).let {
    if (Character.getType(it) == Character.PRIVATE_USE.toInt()) "U+%04X".format(it) else code
}

