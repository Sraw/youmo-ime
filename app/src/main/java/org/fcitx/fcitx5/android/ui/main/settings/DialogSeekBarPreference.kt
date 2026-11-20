/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.preference.DialogPreference
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.seekBar
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.verticalMargin
import splitties.views.gravityHorizontalCenter
import splitties.views.textAppearance

/**
 * A seek bar in a dialog, its value in the summary, with min / max / step.
 *
 * @property step values below 1 are taken as 1.
 * @property unit shown after the value; empty for none.
 */
class DialogSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : DialogPreference(context, attrs, defStyleAttr) {

    private var value = 0
    var min: Int = 0
    var max: Int = 100
    var step: Int = 1
        // a step of 0 would divide by zero in progressForValue
        set(value) {
            field = value.coerceAtLeast(1)
        }
    var unit: String = ""

    var default: Int = 0
    var defaultLabel: String? = null

    /** Called on release (按键音音量 plays a key's sound at it). */
    var onRelease: ((Int) -> Unit)? = null

    override fun persistInt(value: Int): Boolean {
        return super.persistInt(value).also {
            if (it) this@DialogSeekBarPreference.value = value
        }
    }

    override fun setDefaultValue(defaultValue: Any?) {
        super.setDefaultValue(defaultValue)
        default = defaultValue as? Int ?: 0
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInteger(index, default)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedInt(defaultValue as? Int ?: default)
    }

    override fun onClick() {
        showSeekBarDialog()
    }

    private fun showSeekBarDialog() {
        val textView = context.textView {
            text = textForValue(value)
            textAppearance = context.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        }
        val seekBar = context.seekBar {
            max = progressForValue(this@DialogSeekBarPreference.max)
            progress = progressForValue(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    onRelease?.invoke(valueForProgress(seekBar.progress))
                }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    textView.text = textForValue(valueForProgress(progress))
                }
            })
        }
        val dialogContent = context.verticalLayout {
            gravity = gravityHorizontalCenter
            if (dialogMessage != null) {
                val messageText = textView { text = dialogMessage }
                add(messageText, lParams {
                    verticalMargin = dp(8)
                    horizontalMargin = dp(24)
                })
            }
            add(textView, lParams {
                verticalMargin = dp(24)
            })
            add(seekBar, lParams {
                width = matchParent
                horizontalMargin = dp(10)
                bottomMargin = dp(10)
            })
        }
        AlertDialog.Builder(context)
            .setTitle(this@DialogSeekBarPreference.dialogTitle)
            .setView(dialogContent)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = valueForProgress(seekBar.progress)
                setValue(value)
            }
            .setNeutralButton(R.string.default_) { _, _ ->
                setValue(default)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setValue(value: Int) {
        if (callChangeListener(value)) {
            persistInt(value)
            notifyChanged()
        }
    }

    /** Android's SeekBar step is fixed at 1 and min at 0. */
    private fun progressForValue(value: Int) = (value - min) / step

    private fun valueForProgress(progress: Int) = (progress * step) + min

    private fun textForValue(value: Int = this@DialogSeekBarPreference.value): String =
        if (value == default && defaultLabel != null) defaultLabel!! else "$value $unit"

    object SimpleSummaryProvider : SummaryProvider<DialogSeekBarPreference> {
        override fun provideSummary(preference: DialogSeekBarPreference): CharSequence {
            return preference.textForValue()
        }
    }
}
