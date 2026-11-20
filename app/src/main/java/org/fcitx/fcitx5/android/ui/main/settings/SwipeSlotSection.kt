/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.utils.letGoOfFocusedField
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import java.util.Calendar

/** The text field sits under the last choice so the others never move. [off]: the way is switched off, its choice kept. */
class SwipeSlotSection(private val ctx: Context, off: Boolean, initial: SwipeSlot, private val strokesDrawn: Boolean = false) {

    private var command = (initial as? SwipeSlot.Command)?.command ?: KeyCommand.SelectAll

    private val first = RadioButton(ctx).apply { setText(R.string.slot_first) }
    private val commandButton = RadioButton(ctx)
    private val textButton = RadioButton(ctx).apply { setText(R.string.shortcut_text) }
    private val empty = RadioButton(ctx).apply { setText(R.string.slot_empty) }
    private val radios = listOf(first, commandButton, empty, textButton)

    private val field = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(KeyShortcut.MAX_TEXT))
        setText((initial as? SwipeSlot.Text)?.text)
        // typed in: the text chosen. Not on focus, which the dialog may give unasked and so change a kept choice
        doAfterTextChanged { pick(textButton) }
    }

    private val help = TextVariableHelp(ctx, field)

    private val variables = CheckBox(ctx).apply {
        setText(R.string.text_variables_use)
        isChecked = (initial as? SwipeSlot.Text)?.variables == true
        setOnCheckedChangeListener { _, on ->
            help.on = on
            pick(textButton)
        }
    }

    val variableHelp: TextVariableHelp get() = help

    var onChange: (() -> Unit)? = null

    var firstChar: String? = null
        set(value) {
            field = value
            first.text = value?.let { ctx.getString(R.string.swipe_way_said, ctx.getString(R.string.slot_first), it) } ?: ctx.getString(R.string.slot_first)
        }

    private val noteView = small().apply {
        id = R.id.swipe_slot_note
        visibility = View.GONE
    }

    private val strokeView = small().apply {
        setText(R.string.slot_stroke_drawn)
        visibility = View.GONE
    }

    val view: LinearLayout = ctx.verticalLayout {
        if (off) add(small().apply { setText(R.string.slot_card_off_note) }, lParams(matchParent) { bottomMargin = ctx.dp(4) })
        add(first, lParams(matchParent))
        add(ctx.horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            add(commandButton, lParams(0, wrapContent) { weight = 1f })
            add(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.shortcut_change)
                setOnClickListener { chooseCommand() }
            }, lParams())
        }, lParams(matchParent))
        add(empty, lParams(matchParent))
        add(textButton, lParams(matchParent))
        add(field, lParams(matchParent) { marginStart = ctx.dp(32) })
        add(variables, lParams(matchParent) { marginStart = ctx.dp(32) })
        add(help.view, lParams(matchParent) { marginStart = ctx.dp(32) })
        // notes that come and go at the foot: only the card's foot moves
        add(strokeView, lParams(matchParent) { topMargin = ctx.dp(4) })
        add(noteView, lParams(matchParent) { topMargin = ctx.dp(4) })
    }

    init {
        help.on = variables.isChecked
        radios.forEach { button -> button.setOnClickListener { pick(button) } }
        textButton.setOnClickListener {
            pick(textButton)
            field.requestFocus()
            ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
        }
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

    private fun pick(button: RadioButton) {
        radios.forEach { it.isChecked = it === button }
        // hidden with the keyboard on it: put away, or its keys type into it and pick the text again
        if (button !== textButton) field.letGoOfFocusedField()
        field.isVisible = textButton.isChecked
        variables.isVisible = textButton.isChecked
        help.view.isVisible = textButton.isChecked && help.on
        strokeView.visibility = if (strokesDrawn && commandButton.isChecked && command.stroke != null) View.VISIBLE else View.GONE
        onChange?.invoke()
    }

    fun note(text: String?) {
        noteView.text = text
        noteView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    val noted: String? get() = noteView.text?.toString()?.takeIf { noteView.visibility == View.VISIBLE }

    fun peek(): SwipeSlot? = when {
        first.isChecked -> SwipeSlot.First
        commandButton.isChecked -> SwipeSlot.Command(command)
        textButton.isChecked -> text()
        else -> SwipeSlot.Empty
    }

    // stored without variables where they would type the same
    private fun text(): SwipeSlot.Text? = field.text.toString().takeIf { it.isNotEmpty() }?.let { SwipeSlot.Text.of(it, variables.isChecked) }

    private fun showCommand() {
        commandButton.text = ctx.getString(R.string.shortcut_command_is, ctx.getString(R.string.shortcut_command), ctx.getString(KeyShortcutLooks.of(command).label))
    }

    private fun chooseCommand() {
        // voice input too: without the voice pack it opens the packs page
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

    fun chosen(): SwipeSlot? = when {
        first.isChecked -> SwipeSlot.First
        commandButton.isChecked -> SwipeSlot.Command(command)
        textButton.isChecked -> text()
            ?: null.also { field.error = ctx.getString(R.string.invalid_value) }
        else -> SwipeSlot.Empty
    }

    fun summary(): String = when {
        first.isChecked -> first.text.toString()
        commandButton.isChecked -> commandButton.text.toString()
        textButton.isChecked -> ctx.getString(
            R.string.shortcut_command_is,
            ctx.getString(R.string.shortcut_text),
            field.text.toString().let { if (variables.isChecked) TextVariables.expand(it, Calendar.getInstance()) else it },
        )
        else -> ctx.getString(R.string.slot_empty)
    }

    val summaryIcon: Int? get() = if (commandButton.isChecked) KeyShortcutLooks.of(command).icon else null

    private fun small() = TextView(ctx).apply {
        textSize = 13f
        setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
    }

    companion object {
        fun shortLabel(ctx: Context, slot: SwipeSlot): String? = when (slot) {
            SwipeSlot.First -> ctx.getString(R.string.slot_first)
            is SwipeSlot.Command -> ctx.getString(KeyShortcutLooks.of(slot.command).label)
            is SwipeSlot.Text -> slot.text
            SwipeSlot.Empty -> null
        }
    }
}
