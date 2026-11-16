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
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import java.util.Calendar

/**
 * One way of a key (上滑 or 下滑) chosen (060-swipe-slots): the long press's first, a command, nothing, or a text,
 * in that order, the text's field only while the text is chosen, under the last choice, so the three above never
 * move (061-settings-cards §5.3). Drawn in the editor's card of the way, whose title row says [summary]; [off]
 * says the way is switched off in the settings, its choice kept for when it is on.
 * [strokesDrawn]: 在键上划 is 笔画筛选 and the key one a stroke drawn starts on, where a short swipe while pinyin
 * is typed is a stroke drawn, so a stroke set here is not reached then (BaseKeyboard's glide): said under a stroke chosen.
 */
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
        // typed in: the text chosen. Not on focus, which the dialog may give it unasked (a key with no
        // long press to edit has this field first) and so change a choice kept
        doAfterTextChanged { pick(textButton) }
    }

    // the text's variables filled in as it is typed (061-text-variables §2.4); what it types now, under it
    private val help = TextVariableHelp(ctx, field)

    private val variables = CheckBox(ctx).apply {
        setText(R.string.text_variables_use)
        isChecked = (initial as? SwipeSlot.Text)?.variables == true
        setOnCheckedChangeListener { _, on ->
            help.on = on
            pick(textButton)
        }
    }

    /** The variables help under the text: a test's. */
    val variableHelp: TextVariableHelp get() = help

    /** Called when what is chosen may have changed (a way picked, a command, the text typed). */
    var onChange: (() -> Unit)? = null

    /** The key's first of long press as the editor has it now, said after 长按首选 (none: the key has none). */
    var firstChar: String? = null
        set(value) {
            field = value
            first.text = value?.let { ctx.getString(R.string.swipe_way_said, ctx.getString(R.string.slot_first), it) } ?: ctx.getString(R.string.slot_first)
        }

    // how the keyboard draws what is chosen where that is not as set (SwipeFates.note): none, hidden
    private val noteView = small().apply {
        id = R.id.swipe_slot_note
        visibility = View.GONE
    }

    // a stroke chosen while strokes are drawn on the keys: not reached while pinyin is typed (issue #4)
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
        // the notes that come and go at the foot: only the card's foot moves
        add(strokeView, lParams(matchParent) { topMargin = ctx.dp(4) })
        add(noteView, lParams(matchParent) { topMargin = ctx.dp(4) })
    }

    init {
        help.on = variables.isChecked
        radios.forEach { button -> button.setOnClickListener { pick(button) } }
        // the text chosen by a tap: its field to type in at once
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
        // the field and what goes with it shown while the text is chosen, the variables' help while they are on too
        field.isVisible = textButton.isChecked
        variables.isVisible = textButton.isChecked
        help.view.isVisible = textButton.isChecked && help.on
        strokeView.visibility = if (strokesDrawn && commandButton.isChecked && command.stroke != null) View.VISIBLE else View.GONE
        onChange?.invoke()
    }

    /** The page's note of how the keyboard draws it ([SwipeFates.note]); null, none. */
    fun note(text: String?) {
        noteView.text = text
        noteView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** The note shown now, null for none: a test's. */
    val noted: String? get() = noteView.text?.toString()?.takeIf { noteView.visibility == View.VISIBLE }

    /** What is chosen, said on nothing: an empty text as nothing typed yet. */
    fun peek(): SwipeSlot? = when {
        first.isChecked -> SwipeSlot.First
        commandButton.isChecked -> SwipeSlot.Command(command)
        textButton.isChecked -> text()
        else -> SwipeSlot.Empty
    }

    // the text typed, with or without its variables (without where they would type it the same: stored as ever); none while empty
    private fun text(): SwipeSlot.Text? = field.text.toString().takeIf { it.isNotEmpty() }?.let { SwipeSlot.Text.of(it, variables.isChecked) }

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
        textButton.isChecked -> text()
            ?: null.also { field.error = ctx.getString(R.string.invalid_value) }
        else -> SwipeSlot.Empty
    }

    /** What is chosen, one line for the card's title row: 长按首选 @, 命令：全选, 文字：what it types now, 空. */
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

    /** The chosen command's icon, as the key draws it, for the summary; none for another choice. */
    val summaryIcon: Int? get() = if (commandButton.isChecked) KeyShortcutLooks.of(command).icon else null

    private fun small() = TextView(ctx).apply {
        textSize = 13f
        setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
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
