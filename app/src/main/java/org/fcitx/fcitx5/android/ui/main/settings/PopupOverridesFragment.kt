/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.res.Configuration
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.popup.PopupEditor
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.keyboard.GroupedKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.input.keyboard.markSpots
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.ui.main.settings.KeyShortcutSection.Companion.withChosen
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

/**
 * Edits what a long press on a key offers, on a keyboard drawn as the user sees it: each key
 * with its first long-press character small where the keyboard shows it, a changed one outlined. The decisions
 * (replace, disable, carry over to Shift) live in [PopupOverrides]; this shows and writes them.
 */
class PopupOverridesFragment : KeyCapsPageFragment() {

    private val pref get() = AppPrefs.getInstance().internal.popupOverrides

    private var overrides: PopupOverrides
        get() = PopupOverrides.parse(pref.getValue())
        set(value) = pref.setValue(value.serialize())

    private val shortcutsPref get() = AppPrefs.getInstance().internal.keyShortcuts

    private var shortcuts: KeyShortcuts
        get() = KeyShortcuts.parse(shortcutsPref.getValue())
        set(value) = shortcutsPref.setValue(value.serialize())

    // the tab shown ([KeyShortcutPages.all]), kept as the page is rebuilt
    private var page = KeyShortcutPages.Full

    private val layers get() = AppPrefs.getInstance().keyboard.swipeLayers.getValue()

    override fun rebuild() {
        val ctx = requireContext()
        content.apply {
            removeAllViews()
            add(tabs(ctx), lParams(matchParent))
            // the long press's characters only on the full keyboard's tab; the others change the shortcut alone
            val hint = if (page === KeyShortcutPages.Full) R.string.shortcut_hint else R.string.shortcut_hint_page
            add(text(ctx, getString(hint), body = true), lParams(matchParent) { topMargin = ctx.dp(8) })
            add(text(ctx, getString(R.string.shortcut_now, getString(layers.stringRes)), body = false), lParams(matchParent) { topMargin = ctx.dp(4) })
            if (page === KeyShortcutPages.Full) fullPage(ctx) else shortcutPage(ctx, page)
            add(ctx.horizontalLayout {
                // a tab with no presets (注音九键, the numbers) has none to put back
                if (page.hasPresets) add(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.shortcut_apply_presets)
                    setOnClickListener { confirm(R.string.shortcut_apply_presets_message) { shortcuts = shortcuts.withPresets(page.form) } }
                }, lParams { marginEnd = ctx.dp(8) })
                add(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.shortcut_clear_page)
                    setOnClickListener { confirm(R.string.shortcut_clear_page_message) { shortcuts = shortcuts.cleared(page.names) } }
                }, lParams())
            }, lParams { topMargin = ctx.dp(12) })
        }
    }

    // a whole page's shortcuts replaced at a tap, there being no taking it back: asked first
    private fun confirm(message: Int, change: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                change()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tabs(ctx: Context) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        var shown: Chip? = null
        add(ChipGroup(ctx).apply {
            isSingleLine = true
            isSingleSelection = true
            KeyShortcutPages.all.forEach { p ->
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
        // eight tabs are wider than a phone (注音全键盘's was off the edge once 注音九键's came before it)
        post { shown?.let { scrollTo((it.right - width).coerceAtLeast(0), 0) } }
    }

    // a shortcut as a screen reader says it
    private fun said(name: String): String? = KeyShortcutSection.shortLabel(requireContext(), shortcuts.resolve(name))

    // a shortcut as the key draws it: a command's icon, a text's whole
    private fun markOf(name: String): KeyCaps.Mark? = when (val shortcut = shortcuts.resolve(name)) {
        is KeyShortcut.Command -> KeyCaps.Mark.Icon(KeyShortcutLooks.of(shortcut.command).icon)
        is KeyShortcut.Text -> KeyCaps.Mark.Text(shortcut.text)
        KeyShortcut.None -> null
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // a character key's [symbol] and [name]'s shortcut placed as the keyboard places them (KeySwipes)
    private fun charMarks(symbol: KeyCaps.Mark?, name: String?): KeyMarks.Placed<KeyCaps.Mark> {
        val spots = markSpots()
        val shortcut = name?.let(::markOf)?.takeIf { layers.layers.wayOf(SwipeLayers.Layer.Shortcut) != null }
        return KeyMarks.place(symbol, shortcut, spots.symbol, spots.shortcut, landscape, keyboardCompact())
    }

    /** A tab other than the full keyboard's: its keys in their rows, a tap editing the shortcut alone. */
    private fun shortcutPage(ctx: Context, page: KeyShortcutPages.Page) {
        val stored = shortcuts
        val function = page.form == KeyShortcuts.FUNCTION
        val symbols = symbolsOf(page).iterator()
        page.rows.forEach { row ->
            val keys = if (function) row.take(1) else row
            with(content) { add(ctx.horizontalLayout {
                gravity = if (page.leftAligned) Gravity.START else Gravity.CENTER_HORIZONTAL
                keys.forEach { key ->
                    val names = if (function) row.map { it.name } else listOf(key.name)
                    // a function key of ime-core's not named here yet shows as it is called there, not a crash
                    val label = if (function) FunctionLabels[key.label]?.let(::getString) ?: key.label else key.label
                    val said = names.map { said(it) ?: "–" }.joinToString(" / ").takeIf { names.any { said(it) != null } }
                    val marks = if (function) functionMarks(names) else charMarks(symbols.next(), key.name)
                    // the engine's cap: a character key by its name, a function key by its own (its up and down)
                    val shortcut = KeyCaps.Shortcut(if (function) key.name.substringAfter('/').substringBeforeLast('.') else key.name, stored)
                    add(KeyCaps.key(ctx, label, "", marked = names.any(stored::isSet), dimmed = false, marks = marks, said = said, shortcut = shortcut).apply {
                        setOnClickListener { editShortcuts(label, names) }
                    }, if (function) lParams(matchParent, ctx.dp(KeyHeight)) else keyParams(ctx, weight = 1f))
                }
                if (!function) weightSum = page.rows.maxOf { it.size }.toFloat()
            }, lParams(matchParent) { topMargin = ctx.dp(6) }) }
        }
    }

    // each key's symbol on [page] as its keyboard draws it, in the page's order: the 14 and 18 keys'
    // corners (GroupedKeyboard); none on the number keys, a digit alone on each; the nine keys' and
    // 注音's labelled by theirs, so not drawn again, its place kept from the shortcut
    private fun symbolsOf(page: KeyShortcutPages.Page): List<KeyCaps.Mark?> {
        val n = page.names.size
        return when (page.form) {
            KeyShortcutPreset.GROUPED14 -> GroupedKeyboard.CORNERS.map(KeyCaps.Mark::Text)
            KeyShortcutPreset.GROUPED18 -> GroupedKeyboard.CORNERS18.map(KeyCaps.Mark::Text)
            KeyShortcutPreset.NUMBER -> List(n) { null }
            else -> List(n) { KeyCaps.Mark.Unshown }
        }
    }

    // a function key's up and down ([names]), each where it is drawn while that way is on
    private fun functionMarks(names: List<String>): KeyMarks.Placed<KeyCaps.Mark> {
        val (up, down) = names.map(::markOf)
        val l = layers.layers
        val spots = markSpots()
        return KeyMarks.placeFunction(
            up, down, l.up != SwipeLayers.Layer.Off, l.down != SwipeLayers.Layer.Off, spots.symbol, spots.shortcut, landscape, keyboardCompact(),
        )
    }

    /** [names]' shortcuts (one, or a function key's up and down) edited, under [label]. */
    private fun editShortcuts(label: String, names: List<String>) {
        val ctx = requireContext()
        val sections = names.map { section(ctx, it) }
        val layout = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            sections.forEach { add(it.view, lParams(matchParent)) }
        }
        val current = shortcuts
        val builder = AlertDialog.Builder(ctx)
            .setTitle(label)
            .setView(layout.wrapInScrollView())
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (names.any(current::isSet)) {
            builder.setNeutralButton(R.string.reset_this_key) { _, _ ->
                shortcuts = names.fold(current) { s, n -> s.without(n) }
                rebuild()
            }
        }
        builder.show().onPositiveButtonClick onClick@{
            val chosen = sections.map { it.chosen() ?: return@onClick false }
            shortcuts = names.zip(chosen).fold(current) { s, (n, c) -> s.withChosen(n, c) }
            rebuild()
            true
        }
    }

    // [name]'s section, titled by the way that reaches it now (a function key's name says its own)
    private fun section(ctx: Context, name: String): KeyShortcutSection {
        val layers = layers.layers
        val (title, off) = if (KeyShortcuts.formOf(name) == KeyShortcuts.FUNCTION) {
            val up = name.endsWith(".up")
            getString(if (up) R.string.shortcut_section_up else R.string.shortcut_section_down) to
                ((if (up) layers.up else layers.down) == SwipeLayers.Layer.Off)
        } else {
            val way = layers.wayOf(SwipeLayers.Layer.Shortcut)
            getString(if (way == false) R.string.shortcut_section_down else R.string.shortcut_section_up) to (way == null)
        }
        return KeyShortcutSection(ctx, title, off, shortcuts.resolve(name))
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
                    setOnClickListener { askLabel() }
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

    // a letter's name in the shortcuts (full/a); the other keys have none of their own here
    private fun shortcutName(label: String) = label.takeIf { it in Letters }?.let { KeyShortcuts.charKey(KeyShortcutPreset.FULL, it) }

    private fun key(ctx: Context, label: String, current: PopupOverrides): View {
        val shown = current.resolve(label, PopupPreset[label])?.toList().orEmpty()
        val custom = current[label]
        // the outline and the fading, said
        val state = listOfNotNull(
            custom?.let { getString(R.string.edited_x, label) },
            getString(R.string.long_press_none).takeIf { shown.isEmpty() },
        ).joinToString(" ").ifEmpty { null }
        val name = shortcutName(label)
        val marked = custom != null || name?.let(shortcuts::isSet) == true
        val symbol = shown.firstOrNull()
        val marks = charMarks(symbol?.let(KeyCaps.Mark::Text), name)
        return KeyCaps.key(ctx, label, symbol.orEmpty(), marked = marked, dimmed = shown.isEmpty(), state = state, marks = marks, said = name?.let(::said), shortcut = name?.let { KeyCaps.Shortcut(it, shortcuts) }).apply {
            setOnClickListener { edit(label) }
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

    /**
     * The characters drawn as the popup draws them, the first (what letting go gives) outlined.
     * A tap only selects one; what is done with it is up to the buttons below, apart from it: a
     * tap that moved or removed a character was a surprise, and a × beside it easily hit.
     * Each view with its top margin in dp, in order: the characters, a line said when there are
     * none, the buttons, [field] with its add button.
     */
    private fun chars(ctx: Context, editor: PopupEditor, field: EditText): List<Pair<View, Int>> {
        val keys = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        val empty = text(ctx, getString(R.string.long_press_none), body = true)
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

    /** A key's long press edited ([chars]), and its shortcut where it has one ([section]). */
    private fun edit(label: String) {
        val ctx = requireContext()
        val preset = PopupPreset[label]
        val current = overrides
        val editor = PopupEditor(current.resolve(label, preset)?.toList().orEmpty())
        val field = EditText(ctx).apply {
            hint = getString(R.string.long_press_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val name = shortcutName(label)
        val section = name?.let { section(ctx, it) }
        val layout = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            add(text(ctx, getString(R.string.long_press_editor_hint), body = false), lParams(matchParent))
            chars(ctx, editor, field).forEach { (view, top) -> add(view, lParams(matchParent) { topMargin = ctx.dp(top) }) }
            section?.let { add(it.view, lParams(matchParent)) }
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.long_press_of, label))
            .setView(layout.wrapInScrollView())
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        val stored = shortcuts
        if (current[label] != null || name?.let(stored::isSet) == true) {
            builder.setNeutralButton(R.string.reset_this_key) { _, _ ->
                overrides = current.without(label)
                name?.let { shortcuts = stored.without(it) }
                rebuild()
            }
        }
        builder.show().apply { setCanceledOnTouchOutside(false) }
            // a word left typed in the field counts as added; one holding a key code is said so, the dialog kept
            .onPositiveButtonClick onClick@{
                if (rejected(field)) return@onClick false
                val chosen = section?.let { it.chosen() ?: return@onClick false }
                editor.add(field.text.toString())
                overrides = current.withEdit(label, editor.items, preset)
                if (name != null && chosen != null) shortcuts = stored.withChosen(name, chosen)
                rebuild()
                true
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
                edit(label)
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
            .setPositiveButton(android.R.string.ok) { _, _ ->
                overrides = PopupOverrides.Empty
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class Action(@StringRes val text: Int, val enabled: () -> Boolean, val run: () -> Unit)

    companion object {
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
