/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.NineKeySymbols
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout

/**
 * Sets what a long press on each of 九键's keys 1 to 9 offers besides its digit and letters, on
 * the keys drawn as the keyboard has them: each with its symbols and its digit small, one set
 * otherwise than by default outlined. Tapping one asks for its symbols; [NineKeySymbols] keeps
 * them to what a long press can type.
 */
class NineKeySymbolsFragment : KeyCapsPageFragment() {

    private val pref get() = AppPrefs.getInstance().keyboard.t9Symbols

    private val symbols: NineKeySymbols get() = NineKeySymbols.parse(pref.getValue())

    // the keyboard reads it again as it is made anew (recreateInputViewPrefs)
    private fun save(value: NineKeySymbols) = pref.setValue(value.serialize())

    override fun rebuild() {
        val ctx = requireContext()
        val current = symbols
        content.apply {
            removeAllViews()
            add(text(ctx, getString(R.string.t9_symbols_hint), body = true), lParams(matchParent))
            Rows.forEachIndexed { i, row ->
                add(ctx.horizontalLayout {
                    row.forEach { add(key(ctx, it, current), lParams(0, ctx.dp(KeyHeight)) {
                        weight = 1f
                        marginStart = ctx.dp(3)
                        marginEnd = ctx.dp(3)
                    }) }
                }, lParams(matchParent) { topMargin = ctx.dp(if (i == 0) 16 else 6) })
            }
            if (!current.isStandard) {
                add(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.reset_all)
                    setOnClickListener { confirmResetAll() }
                }, lParams { topMargin = ctx.dp(12) })
            }
        }
    }

    private fun key(ctx: Context, digit: Char, current: NineKeySymbols): View {
        val shown = current.symbols(digit)
        val edited = !current.isDefault(digit)
        val state = getString(R.string.edited_x, digit.toString()).takeIf { edited }
        return KeyCaps.key(ctx, label(shown), digit.toString(), marked = edited, dimmed = shown.isEmpty(), state = state).apply {
            setOnClickListener { edit(digit) }
        }
    }

    private fun edit(digit: Char) {
        val ctx = requireContext()
        val current = symbols
        val default = NineKeySymbols.DEFAULTS[digit].orEmpty()
        val field = EditText(ctx).apply {
            setText(current.symbols(digit).joinToString(" "))
            hint = getString(R.string.t9_symbols_field_hint, default.joinToString(" "))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        val box = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            add(text(ctx, getString(R.string.t9_symbols_editor_hint, NineKeySymbols.MAX), body = false), lParams(matchParent))
            add(field, lParams(matchParent) { topMargin = ctx.dp(8) })
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.t9_symbols_of, digit.toString()))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // emptied, the key offers none: its defaults are the reset button's
                val kept = NineKeySymbols.edited(field.text.toString())
                if (kept == null) {
                    ctx.toast(R.string.t9_symbols_none_kept)
                    return@setPositiveButton
                }
                save(current.with(digit, kept))
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (!current.isDefault(digit)) {
            builder.setNeutralButton(R.string.reset) { _, _ ->
                save(current.with(digit, default))
                rebuild()
            }
        }
        builder.show()
        // after the dialog focused it, which put the cursor at the start
        field.post { field.setSelection(field.text.length) }
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_all)
            .setMessage(R.string.t9_symbols_reset_all_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                save(NineKeySymbols.STANDARD)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun text(ctx: Context, s: String, body: Boolean) = TextView(ctx).apply {
        text = s
        textSize = if (body) 14f else 12f
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
    }

    internal companion object {
        val Rows = listOf("123", "456", "789")
        const val KeyHeight = 60

        // how many a key shows before the count of the rest: more wrap on a phone
        private const val SHOWN = 3

        /** A key's [symbols] as drawn on it: the first [SHOWN], then how many more. */
        fun label(symbols: List<String>): String =
            if (symbols.size <= SHOWN) symbols.joinToString(" ")
            else symbols.take(SHOWN).joinToString(" ") + " +" + (symbols.size - SHOWN)
    }
}
