/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * One way of a key (上滑 or 下滑) chosen (060-swipe-slots): the long press's first, a command, a text,
 * or nothing. [off] says the way is switched off in the settings, its choice kept for when it is on.
 */
class SwipeSlotSection(private val ctx: Context, title: String, off: Boolean, initial: SwipeSlot) {

    private var command = (initial as? SwipeSlot.Command)?.command ?: KeyCommand.SelectAll

    private val first = RadioButton(ctx).apply { setText(R.string.slot_first) }
    private val commandButton = RadioButton(ctx)
    private val textButton = RadioButton(ctx).apply { setText(R.string.shortcut_text) }
    private val empty = RadioButton(ctx).apply { setText(R.string.slot_empty) }
    private val radios = listOf(first, commandButton, textButton, empty)

    private val field = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(KeyShortcut.MAX_TEXT))
        setText((initial as? SwipeSlot.Text)?.text)
        // typed in: the text chosen. Not on focus, which the dialog may give it unasked (a key with no
        // long press to edit has this field first) and so change a choice kept
        doAfterTextChanged { pick(textButton) }
    }

    val view: LinearLayout = ctx.verticalLayout {
        add(TextView(ctx).apply {
            text = if (off) title + ctx.getString(R.string.slot_section_off) else title
            textSize = 14f
            setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
            setPadding(0, ctx.dp(12), 0, ctx.dp(4))
        }, lParams(matchParent))
        add(first, lParams(matchParent))
        add(ctx.horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            add(commandButton, lParams(0, wrapContent) { weight = 1f })
            add(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.shortcut_change)
                setOnClickListener { chooseCommand() }
            }, lParams())
        }, lParams(matchParent))
        add(textButton, lParams(matchParent))
        add(field, lParams(matchParent) { marginStart = ctx.dp(32) })
        add(empty, lParams(matchParent))
    }

    init {
        radios.forEach { button -> button.setOnClickListener { pick(button) } }
        showCommand()
        pick(
            when (initial) {
                SwipeSlot.First -> first
                is SwipeSlot.Command -> commandButton
                is SwipeSlot.Text -> textButton
                SwipeSlot.Empty -> empty
            },
        )
    }

    private fun pick(button: RadioButton) = radios.forEach { it.isChecked = it === button }

    private fun showCommand() {
        commandButton.text = ctx.getString(R.string.shortcut_command_is, ctx.getString(R.string.shortcut_command), ctx.getString(KeyShortcutLooks.of(command).label))
    }

    private fun chooseCommand() {
        // every command, voice input too: without the voice pack it opens the packs page
        val commands = KeyCommand.entries
        AlertDialog.Builder(ctx)
            .setTitle(R.string.shortcut_command)
            .setSingleChoiceItems(commands.map { ctx.getString(KeyShortcutLooks.of(it).label) }.toTypedArray(), commands.indexOf(command)) { d, i ->
                command = commands[i]
                showCommand()
                pick(commandButton)
                d.dismiss()
            }
            .show()
    }

    /** What is chosen; null when the text is chosen but empty, said on the field. */
    fun chosen(): SwipeSlot? = when {
        first.isChecked -> SwipeSlot.First
        commandButton.isChecked -> SwipeSlot.Command(command)
        textButton.isChecked -> field.text.toString().takeIf { it.isNotEmpty() }?.let(SwipeSlot::Text)
            ?: null.also { field.error = ctx.getString(R.string.invalid_value) }
        else -> SwipeSlot.Empty
    }

    companion object {
        /** What a slot is said as (a screen reader, the page): the first's words, a command's name, a text as it is (its whole, [KeyShortcut.MAX_TEXT] at most); none for nothing. */
        fun shortLabel(ctx: Context, slot: SwipeSlot): String? = when (slot) {
            SwipeSlot.First -> ctx.getString(R.string.slot_first)
            is SwipeSlot.Command -> ctx.getString(KeyShortcutLooks.of(slot.command).label)
            is SwipeSlot.Text -> slot.text
            SwipeSlot.Empty -> null
        }
    }
}
