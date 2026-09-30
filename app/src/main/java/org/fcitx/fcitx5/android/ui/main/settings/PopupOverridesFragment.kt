/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import splitties.dimensions.dp

/**
 * Edits what a long press on a key offers. The decisions (replace, disable, carry over to
 * Shift) live in [PopupOverrides]; this only shows the table and writes it back.
 */
class PopupOverridesFragment : PaddingPreferenceFragment() {

    private val pref get() = AppPrefs.getInstance().internal.popupOverrides

    private var overrides: PopupOverrides
        get() = PopupOverrides.parse(pref.getValue())
        set(value) = pref.setValue(value.serialize())

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(preferenceManager.context)
        rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val current = overrides
        val screen = preferenceScreen.apply { removeAll() }

        val letters = ('a'..'z').map { it.toString() }
        screen.addPreference(PreferenceCategory(ctx).apply {
            setTitle(R.string.letters)
            screen.addPreference(this)
            letters.forEach { addPreference(keyPreference(it, current)) }
        })
        val others = current.labels.filter { it !in letters }
        screen.addPreference(PreferenceCategory(ctx).apply {
            setTitle(R.string.other_keys)
            screen.addPreference(this)
            others.forEach { addPreference(keyPreference(it, current)) }
            addPreference(Preference(ctx).apply {
                setTitle(R.string.add_key)
                setOnPreferenceClickListener { askLabel(); true }
            })
        })
        if (!current.isEmpty) {
            screen.addPreference(Preference(ctx).apply {
                setTitle(R.string.reset_all)
                setOnPreferenceClickListener { confirmResetAll(); true }
            })
        }
    }

    private fun keyPreference(label: String, current: PopupOverrides) = Preference(requireContext()).apply {
        title = label
        val custom = current[label]
        summary = when {
            custom == null -> PopupPreset[label]?.joinToString(" ") ?: getString(R.string.none)
            custom.isEmpty() -> getString(R.string.disabled)
            else -> getString(R.string.customized, custom.joinToString(" "))
        }
        setOnPreferenceClickListener { edit(label); true }
    }

    private fun input(text: String, hint: String? = null): Pair<FrameLayout, EditText> {
        val ctx = requireContext()
        val field = EditText(ctx).apply {
            setText(text)
            setSelection(text.length)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val margin = ctx.dp(20)
        val box = FrameLayout(ctx).apply {
            addView(field, FrameLayout.LayoutParams(-1, -2).apply { setMargins(margin, margin / 2, margin, 0) })
        }
        return box to field
    }

    private fun edit(label: String) {
        val preset = PopupPreset[label]
        val current = overrides
        val shown = current.resolve(label, preset)?.toList().orEmpty()
        val (box, field) = input(shown.joinToString(" "))
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.long_press_of, label))
            .setMessage(R.string.long_press_editor_hint)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val items = PopupOverrides.tokens(field.text.toString())
                // typing the built-in list back is not an edit
                // a key with no built-in list has nothing to disable
                overrides = if (items == preset?.toList().orEmpty()) current.without(label)
                else current.with(label, items)
                rebuild()
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                overrides = current.without(label)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askLabel() {
        val (box, field) = input("", getString(R.string.key_label_hint))
        AlertDialog.Builder(requireContext())
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
}
