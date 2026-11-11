/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.GroupedKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.popup.PopupEditor
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
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
import com.google.android.material.R as MaterialR

/**
 * 按键字符和快捷键 (060-swipe-slots): each keyboard's keys drawn as the user sees them, a tap editing a key's
 * long press and its two slots (上滑 and 下滑: the long press's first, a command, a text, nothing). The full
 * keyboard's long presses are by the key's label ([PopupOverrides] in `popup_overrides`), the other keyboards'
 * by the key's name (`key_popups`); the slots are [SwipeSlots]. Above the keys: 预设, 翻转上下滑, 多选 (keys
 * flipped, set to the first or nothing, put back, at once) and 撤销, which takes the last change back.
 */
class PopupOverridesFragment : KeyCapsPageFragment() {

    private val prefs get() = AppPrefs.getInstance()

    private var overrides: PopupOverrides
        get() = PopupOverrides.parse(prefs.internal.popupOverrides.getValue())
        set(value) = prefs.internal.popupOverrides.setValue(value.serialize())

    // the other keyboards' long presses, by the key's name
    private var byName: PopupOverrides
        get() = PopupOverrides.parse(prefs.internal.keyPopups.getValue())
        set(value) = prefs.internal.keyPopups.setValue(value.serialize())

    private var slots: SwipeSlots
        get() = prefs.internal.keySwipes.getValue().let { if (it.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(it) }
        set(value) = prefs.internal.keySwipes.setValue(value.serialize())

    // the tab shown ([pages]), kept as the page is rebuilt
    private var page = KeyShortcutPages.Full

    // 多选: on, and the keys chosen (their slots' names), kept across tabs
    private var selecting = false
    private val selected = LinkedHashSet<String>()

    /** Every setting this page writes, as it was before the last change: what 撤销 puts back. */
    internal data class Snapshot(
        val swipes: String, val popups: String, val keyPopups: String,
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
            internal.keySwipes.getValue(), internal.popupOverrides.getValue(), internal.keyPopups.getValue(),
            keyboard.swipeUp.getValue(), keyboard.swipeDown.getValue(), keyboard.swipeUpPosition.getValue(), keyboard.swipeDownPosition.getValue(),
        )
    }

    private fun restore(s: Snapshot) = with(prefs) {
        internal.keySwipes.setValue(s.swipes)
        internal.popupOverrides.setValue(s.popups)
        internal.keyPopups.setValue(s.keyPopups)
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

    private val pages get() = KeyShortcutPages.all.filter { it !== KeyShortcutPages.Number }

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
            add(if (selecting) selectionBar(ctx) else actionBar(ctx), lParams(matchParent) { topMargin = ctx.dp(8) })
            if (page === KeyShortcutPages.Full) fullPage(ctx) else slotPage(ctx, page)
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

    // a key 多选 cannot take (the full keyboard's others, +: no slots of their own), said so
    private fun notSelectable() = Toast.makeText(requireContext(), R.string.swipe_select_keys_only, Toast.LENGTH_SHORT).show()

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
    private fun tapped(slotKey: String?, edit: () -> Unit) {
        if (!selecting) return edit()
        if (slotKey == null) return notSelectable()
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

    /** A tab other than the full keyboard's: its keys in their rows, a tap editing the key's slots and long press. */
    private fun slotPage(ctx: Context, page: KeyShortcutPages.Page) {
        val stored = slots
        val names = byName
        val function = page.form == KeyShortcuts.FUNCTION
        val symbols = symbolsOf(page).iterator()
        // a function key's name beside its engine-drawn cap, on one line: as wide as the widest name on the page
        val captionWidth = if (function) captionWidth(ctx, page) else 0
        page.rows.forEach { row ->
            val keys = if (function) row.take(1) else row
            with(content) { add(ctx.horizontalLayout {
                gravity = if (page.leftAligned) Gravity.START else Gravity.CENTER_HORIZONTAL
                keys.forEach { key ->
                    val fnName = key.label.takeIf { function }
                    val slotKey = fnName?.let(SwipeSlots::functionKey) ?: key.name
                    // the long press by the key's name: a character key's, and the function keys' with a list of characters
                    val popupName = if (function) fnName?.takeIf { it in KeyShortcutPages.FUNCTION_POPUPS } else key.name
                    val label = if (function) functionLabel(key.label) else key.label
                    val symbol = if (function) null else symbols.next()
                    // the engine's cap: a character key by its name, a function key by its own (its up and down)
                    val shortcut = KeyCaps.Shortcut(fnName ?: key.name, stored)
                    // the symbol the engine's cap draws where the first's way puts it (its alt), as the keyboard's key
                    val hint = (symbol as? KeyCaps.Mark.Text)?.text.orEmpty()
                    // the engine draws a function key's very face (space blank, the comma a ,): its name beside it, so
                    // the page says which key it is
                    // every function key has its caption (the width at least the gap), a character key none
                    val caption = captionWidth > 0
                    val params = if (caption) lParams(0, ctx.dp(KeyHeight)) { weight = 1f } else keyParams(ctx, weight = 1f)
                    if (caption) add(KeyCaps.label(ctx, label, CAPTION_SP).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        maxLines = 1
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, lParams(captionWidth, ctx.dp(KeyHeight)))
                    val marked = stored.isSet(slotKey) || popupName?.let { it in names.labels } == true
                    val state = getString(R.string.edited_x, label).takeIf { marked }
                    add(KeyCaps.key(ctx, label, hint, marked = marked, dimmed = false, selected = slotKey in selected, state = state, said = said(slotKey, stored), shortcut = shortcut).apply {
                        setOnClickListener { tapped(slotKey) { edit(label, slotKey, popupName?.let(LongPress::ByName)) } }
                    }, params)
                }
                if (!function) weightSum = page.rows.maxOf { it.size }.toFloat()
            }, lParams(matchParent) { topMargin = ctx.dp(6) }) }
        }
    }

    // each key's symbol on [page] as its keyboard draws it, in the page's order: the 14 and 18 keys'
    // corners (GroupedKeyboard); the nine keys' and 注音's labelled by theirs, so not drawn again
    private fun symbolsOf(page: KeyShortcutPages.Page): List<KeyCaps.Mark?> {
        val n = page.names.size
        return when (page.form) {
            KeyShortcutPreset.GROUPED14 -> GroupedKeyboard.CORNERS.map(KeyCaps.Mark::Text)
            KeyShortcutPreset.GROUPED18 -> GroupedKeyboard.CORNERS18.map(KeyCaps.Mark::Text)
            else -> List(n) { KeyCaps.Mark.Unshown }
        }
    }

    // a way's section, titled by the way, said to be off where its switch is
    private fun section(ctx: Context, slotKey: String, up: Boolean): SwipeSlotSection {
        val on = if (up) prefs.keyboard.swipeUp.getValue() else prefs.keyboard.swipeDown.getValue()
        return SwipeSlotSection(ctx, getString(if (up) R.string.swipe_up else R.string.swipe_down), !on, slots.resolve(slotKey, up))
    }

    private fun fullPage(ctx: Context) {
        val current = overrides
        content.apply {
            add(heading(ctx, R.string.letters), lParams(matchParent))
            Rows.forEachIndexed { i, row ->
                add(ctx.horizontalLayout {
                    // the rows staggered as on the keyboard: half a key in, then one and a half
                    weightSum = Rows[0].length.toFloat()
                    gravity = Gravity.CENTER_HORIZONTAL
                    row.forEach { add(key(ctx, it.toString(), current), keyParams(ctx, weight = 1f)) }
                }, lParams(matchParent) { if (i > 0) topMargin = ctx.dp(6) })
            }

            add(heading(ctx, R.string.other_keys), lParams(matchParent))
            add(FlexboxLayout(ctx).apply {
                flexWrap = FlexWrap.WRAP
                current.labels.filter { it !in Letters }.forEach { add(key(ctx, it, current), otherParams(ctx)) }
                add(KeyCaps.key(ctx, "+", "", marked = false, dimmed = false).apply {
                    contentDescription = getString(R.string.add_key)
                    setOnClickListener { if (selecting) notSelectable() else askLabel() }
                }, otherParams(ctx))
            }, lParams(matchParent))

            add(text(ctx, getString(R.string.long_press_legend), body = false).apply {
                setPadding(0, ctx.dp(16), 0, 0)
            }, lParams(matchParent))
            if (!current.isEmpty) {
                add(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.reset_all)
                    setOnClickListener { confirmResetAll() }
                }, lParams { topMargin = ctx.dp(12) })
            }
        }
    }

    // a letter's slots' name (full/a); the other keys have none of their own here
    private fun slotName(label: String) = label.takeIf { it in Letters }?.let { KeyShortcuts.charKey(KeyShortcutPreset.FULL, it) }

    private fun key(ctx: Context, label: String, current: PopupOverrides): View {
        val shown = current.resolve(label, PopupPreset[label])?.toList().orEmpty()
        val custom = current[label]
        // the outline and the fading, said
        val state = listOfNotNull(
            custom?.let { getString(R.string.edited_x, label) },
            getString(R.string.long_press_none).takeIf { shown.isEmpty() },
        ).joinToString(" ").ifEmpty { null }
        val name = slotName(label)
        val stored = slots
        val marked = custom != null || name?.let(stored::isSet) == true
        val symbol = shown.firstOrNull()
        return KeyCaps.key(
            ctx, label, symbol.orEmpty(), marked = marked, dimmed = shown.isEmpty(), selected = name != null && name in selected,
            state = state, said = name?.let { said(it, stored) }, shortcut = name?.let { KeyCaps.Shortcut(it, stored) },
        ).apply {
            setOnClickListener { tapped(name) { edit(getString(R.string.long_press_of, label), name, LongPress.ByLabel(label)) } }
        }
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

    /** Whose long press a dialog edits: the full keyboard's by the key's label, the others' by the key's name. */
    private sealed interface LongPress {
        data class ByLabel(val label: String) : LongPress
        data class ByName(val name: String) : LongPress
    }

    /**
     * The characters drawn as the popup draws them, the first (what letting go gives) outlined.
     * A tap only selects one; what is done with it is up to the buttons below, apart from it: a
     * tap that moved or removed a character was a surprise, and a × beside it easily hit.
     * Each view with its top margin in dp, in order: the characters, a line said when there are
     * none, the buttons, [field] with its add button.
     */
    private fun chars(ctx: Context, editor: PopupEditor, field: EditText, @StringRes emptyText: Int): List<Pair<View, Int>> {
        val keys = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        val empty = text(ctx, getString(emptyText), body = true)
        val actions = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        lateinit var render: () -> Unit
        // each button with when it applies
        val buttons = listOf(
            Action(R.string.long_press_make_first, editor::canMakeFirst, editor::makeFirst),
            Action(R.string.long_press_move_left, editor::canMoveLeft, editor::moveLeft),
            Action(R.string.long_press_move_right, editor::canMoveRight, editor::moveRight),
            Action(R.string.delete, editor::canDelete, editor::delete),
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
                val key = KeyCaps.key(ctx, item, "", marked = i == 0, dimmed = false, selected = i == editor.selected)
                key.setOnClickListener { editor.tap(i); render() }
                keys.add(key, otherParams(ctx))
            }
            empty.isVisible = editor.items.isEmpty()
            buttons.forEach { (button, enabled) -> button.isEnabled = enabled() }
        }
        fun addTyped() {
            if (rejected(field)) return
            editor.add(field.text.toString())
            field.text = null
            render()
        }
        field.setOnEditorActionListener { _, _, _ -> addTyped(); true }
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
     * A key edited under [title]: its long press ([chars]) where [longPress] names one, its two slots
     * ([section]) where [slotKey] does (a full keyboard's key of no letter has none).
     */
    private fun edit(title: String, slotKey: String?, longPress: LongPress?) {
        val ctx = requireContext()
        val editor = longPress?.let { PopupEditor(startOf(it)) }
        val field = EditText(ctx).apply {
            hint = getString(R.string.long_press_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val sections = slotKey?.let { k -> listOf(section(ctx, k, up = true), section(ctx, k, up = false)) }.orEmpty()
        val layout = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            if (editor != null) {
                add(text(ctx, getString(R.string.long_press_editor_hint), body = false), lParams(matchParent))
                // by name, an empty list is the keyboard's own (the user's removed), not no long press
                val empty = if (longPress is LongPress.ByName) R.string.long_press_by_name_hint else R.string.long_press_none
                chars(ctx, editor, field, empty).forEach { (view, top) -> add(view, lParams(matchParent) { topMargin = ctx.dp(top) }) }
            }
            sections.forEach { add(it.view, lParams(matchParent)) }
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout.wrapInScrollView())
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        val stored = slots
        if (longPress?.let(::isEdited) == true || slotKey?.let(stored::isSet) == true) {
            builder.setNeutralButton(R.string.reset_this_key) { _, _ ->
                change {
                    longPress?.let { save(it, null) }
                    slotKey?.let { slots = stored.without(listOf(it)) }
                }
            }
        }
        builder.show().apply { setCanceledOnTouchOutside(false) }
            // a word left typed in the field counts as added; one holding a key code is said so, the dialog kept
            .onPositiveButtonClick onClick@{
                if (editor != null && rejected(field)) return@onClick false
                val chosen = sections.map { it.chosen() ?: return@onClick false }
                editor?.add(field.text.toString())
                change {
                    if (longPress != null) save(longPress, editor?.items.orEmpty())
                    if (slotKey != null) slots = stored.with(slotKey, true, chosen[0]).with(slotKey, false, chosen[1])
                }
                true
            }
    }

    // the list shown first: the label's (its preset under it), or the name's the user set (none set: empty, the keyboard's own kept)
    private fun startOf(longPress: LongPress): List<String> = when (longPress) {
        is LongPress.ByLabel -> overrides.resolve(longPress.label, PopupPreset[longPress.label])?.toList().orEmpty()
        is LongPress.ByName -> byName.resolve(longPress.name, null)?.toList().orEmpty()
    }

    private fun isEdited(longPress: LongPress): Boolean = when (longPress) {
        is LongPress.ByLabel -> overrides[longPress.label] != null
        is LongPress.ByName -> longPress.name in byName.labels
    }

    // [items] the long press now, null its default back
    private fun save(longPress: LongPress, items: List<String>?) {
        when (longPress) {
            is LongPress.ByLabel -> overrides = items?.let { overrides.withEdit(longPress.label, it, PopupPreset[longPress.label]) }
                ?: overrides.without(longPress.label)
            is LongPress.ByName -> byName = items?.let { byName.withEdit(longPress.name, it, null) } ?: byName.without(longPress.name)
        }
    }

    private fun askLabel() {
        val ctx = requireContext()
        val field = EditText(ctx).apply {
            hint = getString(R.string.key_label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val box = ctx.horizontalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            add(field, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.add_key)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            // a label that is no one key is said so, not dropped with the dialog
            .onPositiveButtonClick onClick@{
                val label = keyLabel(field.text.toString())
                // a key named by a code is no key (keys look their long press up by letter): refused, so the user is told
                if (rejected(field)) return@onClick false
                if (label == null) {
                    field.error = getString(R.string.invalid_value)
                    field.requestFocus()
                    return@onClick false
                }
                edit(getString(R.string.long_press_of, label), slotName(label), LongPress.ByLabel(label))
                true
            }
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
            .setPositiveButton(android.R.string.ok) { _, _ -> change { overrides = PopupOverrides.Empty } }
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

        private val Rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val Letters = ('a'..'z').map { it.toString() }.toSet()
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
 * [items] as what [label] offers. Putting back what the key offers without an override of its own
 * is not an edit; for an upper-case key that is its lower-case key's edit, not the built-in list,
 * so the built-in list put back there is stored like any other.
 */
internal fun PopupOverrides.withEdit(label: String, items: List<String>, preset: Array<String>?): PopupOverrides {
    val unedited = without(label)
    return if (unedited.resolve(label, preset)?.toList().orEmpty() == items) unedited else with(label, items)
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

/** The key [text] names, or null: a label is one word of the stored format, so none or two name no key. */
internal fun keyLabel(text: String): String? = PopupOverrides.tokens(text).singleOrNull()
