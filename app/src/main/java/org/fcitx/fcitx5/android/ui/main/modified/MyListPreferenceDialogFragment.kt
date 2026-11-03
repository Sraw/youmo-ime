/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import androidx.appcompat.R as AppCompatR

class MyListPreferenceDialogFragment : ListPreferenceDialogFragmentCompat() {
    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val p = preference as ListPreference
        val taken = (p as? ManagedPreferenceUi.TakenEntry)?.takeIf { it.takenIndex >= 0 }
        // with no default (an input method's own lists have none) the button would only close the dialog;
        // none either where the default is the place another list holds
        if (p.hasDefault() && (taken == null || p.findIndexOfValue(p.defaultValue()) != taken.takenIndex)) {
            builder.setNeutralButton(R.string.default_) { _, _ ->
                p.restore()
            }
        }
        super.onPrepareDialogBuilder(builder)
        if (taken != null) takenGreyedOut(builder, p, taken)
    }

    // the place another list holds offered greyed out, not picked, and said whose it is
    private fun takenGreyedOut(builder: AlertDialog.Builder, p: ListPreference, taken: ManagedPreferenceUi.TakenEntry) {
        val ctx = builder.context
        val at = taken.takenIndex
        val labels = p.entries.mapIndexed { i, e -> if (i == at) ctx.getString(R.string.mark_at_taken, e, ctx.getString(taken.takenNote)) else e }
        // the dialog's own row, as AlertController picks it
        val a = ctx.obtainStyledAttributes(null, AppCompatR.styleable.AlertDialog, AppCompatR.attr.alertDialogStyle, 0)
        val row = a.getResourceId(AppCompatR.styleable.AlertDialog_singleChoiceItemLayout, AppCompatR.layout.select_dialog_singlechoice_material)
        a.recycle()
        val adapter = object : ArrayAdapter<CharSequence>(ctx, row, android.R.id.text1, labels) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = position != at
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply { isEnabled = position != at }
        }
        builder.setSingleChoiceItems(adapter, p.findIndexOfValue(p.value)) { dialog, which ->
            // a touch never picks a row not enabled; a screen reader's or a test's click can
            if (which == at) return@setSingleChoiceItems
            val value = p.entryValues[which].toString()
            if (p.callChangeListener(value)) p.value = value
            dialog.dismiss()
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        fixDialogMargin(view)
    }

    companion object {
        fun newInstance(key: String): MyListPreferenceDialogFragment {
            val fragment = MyListPreferenceDialogFragment()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }
    }
}