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
import android.widget.ScrollView
import android.widget.TextView
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
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp

/**
 * Edits what a long press on a key offers, on a keyboard drawn as the user sees it: each key
 * with its first long-press character in the corner, a changed one outlined. The decisions
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
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.dp(16)
            setPadding(pad, ctx.dp(8), pad, pad)
        }
        return ScrollView(ctx).apply {
            clipToPadding = false
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                insets
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val current = overrides
        content.removeAllViews()
        content.addView(text(ctx, getString(R.string.long_press_page_hint), body = true))

        content.addView(heading(ctx, R.string.letters))
        Rows.forEachIndexed { i, row ->
            content.addView(LinearLayout(ctx).apply {
                // the rows staggered as on the keyboard: half a key in, then one and a half
                weightSum = Rows[0].length.toFloat()
                gravity = Gravity.CENTER_HORIZONTAL
                row.forEach { addView(key(ctx, it.toString(), current), keyParams(ctx, weight = 1f)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.dp(6) })
        }

        content.addView(heading(ctx, R.string.other_keys))
        content.addView(FlexboxLayout(ctx).apply {
            flexWrap = FlexWrap.WRAP
            current.labels.filter { it !in Letters }.forEach { addView(key(ctx, it, current), otherParams(ctx)) }
            addView(KeyCaps.key(ctx, "+", "", marked = false, dimmed = false).apply {
                contentDescription = getString(R.string.add_key)
                setOnClickListener { askLabel() }
            }, otherParams(ctx))
        })

        content.addView(text(ctx, getString(R.string.long_press_legend), body = false).apply {
            setPadding(0, ctx.dp(16), 0, 0)
        })
        if (!current.isEmpty) {
            content.addView(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.reset_all)
                setOnClickListener { confirmResetAll() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(12) })
        }
    }

    private fun key(ctx: Context, label: String, current: PopupOverrides): View {
        val shown = current.resolve(label, PopupPreset[label])?.toList().orEmpty()
        val custom = current[label]
        return KeyCaps.key(ctx, label, shown.firstOrNull().orEmpty(), marked = custom != null, dimmed = shown.isEmpty()).apply {
            setOnClickListener { edit(label) }
        }
    }

    private fun keyParams(ctx: Context, weight: Float) = LinearLayout.LayoutParams(0, ctx.dp(KeyHeight), weight).apply {
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
        val items = current.resolve(label, preset)?.toMutableList() ?: mutableListOf()
        var selected = -1
        val keys = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        val empty = text(ctx, getString(R.string.long_press_none), body = true)
        val actions = FlexboxLayout(ctx).apply { flexWrap = FlexWrap.WRAP }
        lateinit var render: () -> Unit
        // each button with when it applies to the selected index, and what it makes that index
        fun action(text: Int, enabled: (Int) -> Boolean, act: (Int) -> Int) = MaterialButton(
            ctx, null, MaterialR.attr.materialButtonOutlinedStyle
        ).apply {
            setText(text)
            setOnClickListener {
                selected = act(selected)
                render()
            }
        } to enabled
        val buttons = listOf(
            action(R.string.long_press_make_first, { it > 0 }) { i -> items.add(0, items.removeAt(i)); 0 },
            action(R.string.long_press_move_left, { it > 0 }) { i -> items.add(i - 1, items.removeAt(i)); i - 1 },
            action(R.string.long_press_move_right, { it in 0 until items.lastIndex }) { i -> items.add(i + 1, items.removeAt(i)); i + 1 },
            action(R.string.delete, { it >= 0 }) { i -> items.removeAt(i); if (items.isEmpty()) -1 else minOf(i, items.lastIndex) },
        )
        buttons.forEach { (button, _) ->
            actions.addView(button, FlexboxLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(8) })
        }
        render = {
            keys.removeAllViews()
            items.forEachIndexed { i, item ->
                keys.addView(KeyCaps.key(ctx, item, "", marked = i == 0, dimmed = false, selected = i == selected).apply {
                    setOnClickListener {
                        selected = if (selected == i) -1 else i
                        render()
                    }
                }, otherParams(ctx))
            }
            empty.isVisible = items.isEmpty()
            buttons.forEach { (button, enabled) -> button.isEnabled = enabled(selected) }
        }
        val field = EditText(ctx).apply {
            hint = getString(R.string.long_press_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        fun add() {
            val added = PopupOverrides.tokens(field.text.toString()).distinct().filter { it !in items }
            items.addAll(added)
            field.text = null
            render()
        }
        field.setOnEditorActionListener { _, _, _ -> add(); true }
        val addRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.add)
                setOnClickListener { add() }
            })
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            addView(text(ctx, getString(R.string.long_press_editor_hint), body = false))
            addView(keys, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })
            addView(empty)
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(addRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        }
        render()
        val builder = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.long_press_of, label))
            .setView(ScrollView(ctx).apply { addView(layout) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // a word left typed in the field counts as added
                items.addAll(PopupOverrides.tokens(field.text.toString()).distinct().filter { it !in items })
                // putting the built-in list back is not an edit
                overrides = if (items == preset?.toList().orEmpty()) current.without(label)
                else current.with(label, items)
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
        val box = LinearLayout(ctx).apply {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.add_key)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = field.text.toString().trim()
                if (label.isNotEmpty() && PopupOverrides.tokens(label).size == 1) edit(label)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    companion object {
        private val Rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val Letters = ('a'..'z').map { it.toString() }.toSet()
        private const val KeyHeight = 52
        private const val OtherKeyWidth = 44
    }
}
