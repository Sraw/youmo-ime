/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.popup.PopupEditor
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupPreset
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
class PopupOverridesFragment : Fragment() {

    private val pref get() = AppPrefs.getInstance().internal.popupOverrides

    private var overrides: PopupOverrides
        get() = PopupOverrides.parse(pref.getValue())
        set(value) = pref.setValue(value.serialize())

    private lateinit var content: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        content = ctx.verticalLayout {
            val pad = ctx.dp(16)
            setPadding(pad, ctx.dp(8), pad, pad)
        }
        return content.wrapInScrollView {
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                insets
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onStop() {
        // before the first unlock the keyboard reads them from device-protected storage
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val current = overrides
        content.apply {
            removeAllViews()
            add(text(ctx, getString(R.string.long_press_page_hint), body = true), lParams(matchParent))

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

    private fun key(ctx: Context, label: String, current: PopupOverrides): View {
        val shown = current.resolve(label, PopupPreset[label])?.toList().orEmpty()
        val custom = current[label]
        // the outline and the fading, said
        val state = listOfNotNull(
            custom?.let { getString(R.string.edited_x, label) },
            getString(R.string.long_press_none).takeIf { shown.isEmpty() },
        ).joinToString(" ").ifEmpty { null }
        return KeyCaps.key(ctx, label, shown.firstOrNull().orEmpty(), marked = custom != null, dimmed = shown.isEmpty(), state = state).apply {
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
     */
    private fun edit(label: String) {
        val ctx = requireContext()
        val preset = PopupPreset[label]
        val current = overrides
        val editor = PopupEditor(current.resolve(label, preset)?.toList().orEmpty())
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
        val field = EditText(ctx).apply {
            hint = getString(R.string.long_press_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        fun addTyped() {
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
        val layout = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            add(text(ctx, getString(R.string.long_press_editor_hint), body = false), lParams(matchParent))
            add(keys, lParams(matchParent) { topMargin = ctx.dp(12) })
            add(empty, lParams(matchParent))
            add(actions, lParams(matchParent) { topMargin = ctx.dp(4) })
            add(addRow, lParams(matchParent) { topMargin = ctx.dp(8) })
        }
        render()
        val builder = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.long_press_of, label))
            .setView(layout.wrapInScrollView())
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // a word left typed in the field counts as added
                editor.add(field.text.toString())
                overrides = current.withEdit(label, editor.items, preset)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (current[label] != null) {
            builder.setNeutralButton(R.string.reset) { _, _ ->
                overrides = current.without(label)
                rebuild()
            }
        }
        builder.show().setCanceledOnTouchOutside(false)
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
                if (label == null) {
                    field.error = getString(R.string.invalid_value)
                    field.requestFocus()
                    return@onClick false
                }
                edit(label)
                true
            }
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

/** The key [text] names, or null: a label is one word of the stored format, so none or two name no key. */
internal fun keyLabel(text: String): String? = PopupOverrides.tokens(text).singleOrNull()
